package com.joystream.app.data.providers.dramachi

import com.joystream.app.core.arr
import com.joystream.app.core.asArr
import com.joystream.app.core.asObj
import com.joystream.app.core.int
import com.joystream.app.core.long
import com.joystream.app.core.obj
import com.joystream.app.core.str
import com.joystream.app.data.model.AudioTrack
import com.joystream.app.data.model.CatalogItem
import com.joystream.app.data.model.Episode
import com.joystream.app.data.model.MediaDetails
import com.joystream.app.data.model.MediaId
import com.joystream.app.data.model.MediaType
import com.joystream.app.data.model.Season
import com.joystream.app.data.model.StreamKind
import com.joystream.app.data.model.StreamRelease
import com.joystream.app.data.providers.MediaProvider
import com.joystream.app.data.providers.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/**
 * Dramachi: asian drama & movie catalog backed by a JSON API.
 * Mirrors `src/providers/dramachi` from the upstream MovieBox-Tui project.
 */
class DramachiProvider(private val okHttp: OkHttpClient) : MediaProvider {

    override val id: String = PROVIDER_ID
    override val displayName: String = "Dramachi"
    override val description: String = "Asian dramas and movies with multi-audio editions."

    private val titleCache = mutableMapOf<String, Pair<Long, JsonObject>>()
    private val episodeCache = mutableMapOf<String, Pair<Long, JsonArray>>()

    // ------------------------------------------------------------------- search

    override suspend fun search(query: String, page: Int): List<CatalogItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val url = "$BASE_URL?interface=search&q=${encode(trimmed)}&filter=all&page=${page.coerceAtLeast(1)}"
        val payload = fetchJson(url)
        val items = payload.arr("data") ?: JsonArray(emptyList())
        return items.mapNotNull { element ->
            val item = element.asObj() ?: return@mapNotNull null
            val itemId = item.str("id") ?: return@mapNotNull null
            val isMovie = item.str("content")?.lowercase()?.startsWith("movie") == true
            CatalogItem(
                provider = id,
                idValue = itemId,
                title = item.str("title") ?: "Unknown",
                mediaType = if (isMovie) MediaType.MOVIE else MediaType.SERIES,
                year = item.str("year"),
                posterUrl = item.str("thumb")?.let { "$IMAGE_CDN${it.trim()}" },
            )
        }
    }

    // ------------------------------------------------------------------ details

    override suspend fun details(id: MediaId): MediaDetails {
        val (titleId, targetRip) = splitId(id.value)
        val payload = fetchTitle(titleId)

        val album = payload.arr("album")?.firstOrNull()?.asObj()
            ?: throw ProviderException("Dramachi title not found", statusCode = 404)

        val isMovie = album.str("content")?.lowercase()?.startsWith("movie") == true
        val mediaType = if (isMovie) MediaType.MOVIE else MediaType.SERIES

        val seasonsJson = payload.obj("seasons")
            ?.let { seasons ->
                seasons.asObj()?.keys?.sortedBy { seasonNumber(it) ?: Int.MAX_VALUE }
                    ?.mapNotNull { key -> seasons.asObj()?.obj(key)?.let { key to it } }
            }.orEmpty()

        val audioTracks = seasonsJson.flatMap { (seasonKey, group) ->
            group.arr("versions")?.mapNotNull { versionElement ->
                val version = versionElement.asObj() ?: return@mapNotNull null
                val rip = version.str("rip") ?: return@mapNotNull null
                AudioTrack(
                    subjectId = "$titleId::$rip",
                    language = version.str("version_name", "versionName") ?: "Original",
                    label = if (seasonsJson.size > 1) {
                        "$seasonKey · ${version.str("version_name", "versionName") ?: rip}"
                    } else {
                        version.str("version_name", "versionName") ?: rip
                    },
                )
            }.orEmpty()
        }

        val seasons = if (mediaType == MediaType.SERIES) {
            coroutineScope {
                seasonsJson.map { (seasonKey, group) ->
                    async(Dispatchers.IO) {
                        val rip = targetRip
                            ?: group.arr("versions")?.firstOrNull()?.asObj()?.str("rip")
                            ?: seasonKey
                        val number = seasonNumber(seasonKey) ?: 1
                        val episodes = runCatching { fetchEpisodes(titleId, rip) }
                            .getOrDefault(JsonArray(emptyList()))
                        val parsed = episodes.mapNotNull { element ->
                            val ep = element.asObj() ?: return@mapNotNull null
                            val rawTitle = ep.str("f_title", "title") ?: return@mapNotNull null
                            Episode(
                                season = number,
                                number = parseEpisodeNumber(rawTitle) ?: 0,
                                title = cleanEpisodeTitle(rawTitle),
                            )
                        }.filter { it.number > 0 }
                            .distinctBy { it.number }
                            .sortedBy { it.number }

                        Season(
                            number = number,
                            episodes = parsed.ifEmpty {
                                listOf(Episode(season = number, number = 1))
                            },
                        )
                    }
                }.map { it.await() }
            }.sortedBy { it.number }
        } else {
            emptyList()
        }

        val cast = payload.arr("cast")?.mapNotNull { it.asObj()?.str("name") }.orEmpty()

        return MediaDetails(
            id = id,
            title = album.str("title") ?: "Unknown",
            mediaType = mediaType,
            year = album.str("year"),
            overview = album.str("storyline"),
            rating = null,
            genres = album.str("genres")?.split(',')
                ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            posterUrl = album.str("thumb")?.let { "$IMAGE_CDN${it.trim()}" },
            backdropUrl = album.str("thumb")?.let { "$IMAGE_CDN${it.trim()}" },
            cast = cast,
            director = album.str("director"),
            seasons = seasons,
            audioTracks = audioTracks,
            providerLabel = displayName,
        )
    }

    // ----------------------------------------------------------------- releases

    override suspend fun releases(id: MediaId, season: Int, episode: Int): List<StreamRelease> {
        val (titleId, targetRip) = splitId(id.value)
        val rip = targetRip ?: resolveRip(titleId, season)
            ?: if (season > 0) "Season ${season.toString().padStart(2, '0')}" else "hd Rip"

        val episodes = fetchEpisodes(titleId, rip)
        if (episodes.isEmpty()) return emptyList()

        val matches = if (season <= 0 && episode <= 0) {
            episodes.toList()
        } else {
            val exact = episodes.filter { element ->
                parseEpisodeNumber(element.asObj()?.str("f_title", "title").orEmpty()) == episode
            }
            when {
                exact.isNotEmpty() -> exact
                episode in 1..episodes.size -> listOf(episodes[episode - 1])
                else -> emptyList()
            }
        }

        val releases = coroutineScope {
            matches.mapNotNull { element ->
                val ep = element.asObj() ?: return@mapNotNull null
                val fid = ep.str("fid") ?: return@mapNotNull null
                val disk = ep.str("disk") ?: return@mapNotNull null
                if (fid.isBlank() || disk.isBlank()) return@mapNotNull null
                async(Dispatchers.IO) {
                    runCatching { resolveFileStream(fid, disk) }.getOrNull()?.let { (url, fileName) ->
                        StreamRelease(
                            provider = PROVIDER_ID,
                            title = fileName,
                            url = url,
                            kind = if (url.contains(".m3u8")) StreamKind.HLS else StreamKind.PROGRESSIVE,
                            qualityLabel = ep.str("quality"),
                            sizeBytes = parseSizeBytes(ep.str("size")),
                            season = season.takeIf { it > 0 },
                            episode = episode.takeIf { it > 0 },
                            resourceId = fid,
                            mirrorLabel = "Dramachi CDN",
                        )
                    }
                }
            }.mapNotNull { it.await() }
        }

        return releases.sortedByDescending { it.resolutionHeight ?: 0 }
    }

    private suspend fun resolveRip(titleId: String, season: Int): String? {
        val payload = runCatching { fetchTitle(titleId) }.getOrNull() ?: return null
        val seasons = payload.obj("seasons") ?: return null
        val key = if (season > 0) "Season ${season.toString().padStart(2, '0')}" else
            seasons.asObj()?.keys?.firstOrNull()
        val group = key?.let { seasons.obj(it) } ?: return null
        return group.arr("versions")?.firstOrNull()?.asObj()?.str("rip")
    }

    private suspend fun resolveFileStream(fid: String, disk: String): Pair<String, String> {
        val payload = fetchJson("$BASE_URL?interface=getFile&fid=${encode(fid)}&findex=${encode(disk)}")
        val host = payload.obj("hostInfo")?.str("host")
            ?: throw ProviderException("Dramachi host info missing")
        val file = payload.arr("fileInfo")?.firstOrNull()?.asObj()
            ?: throw ProviderException("Dramachi file info missing")
        val rawUrl = file.str("url") ?: throw ProviderException("Dramachi stream url missing")
        val fileName = file.str("filename", "f_title") ?: "$fid.mkv"
        val streamUrl = "https://${host.trimEnd('/')}/cdn/${rawUrl.trimStart('/')}"
        return streamUrl to fileName
    }

    // ------------------------------------------------------------------ caching

    private suspend fun fetchTitle(titleId: String): JsonObject {
        val cached = titleCache[titleId]
        if (cached != null && System.currentTimeMillis() - cached.first < CACHE_TTL_MS) {
            return cached.second
        }
        val payload = fetchJson("$BASE_URL?interface=title_v2&id=${encode(titleId)}")
        titleCache[titleId] = System.currentTimeMillis() to payload
        return payload
    }

    private suspend fun fetchEpisodes(titleId: String, rip: String): JsonArray {
        val key = "$titleId::$rip"
        val cached = episodeCache[key]
        if (cached != null && System.currentTimeMillis() - cached.first < CACHE_TTL_MS) {
            return cached.second
        }
        val payload = fetchJson("$BASE_URL?interface=eplist&season=${encode(rip)}&id=${encode(titleId)}")
        val list = payload.arr("episode_list") ?: JsonArray(emptyList())
        if (episodeCache.size > 24) episodeCache.clear()
        episodeCache[key] = System.currentTimeMillis() to list
        return list
    }

    private suspend fun fetchJson(url: String): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", com.joystream.app.core.Http.MOBILE_USER_AGENT)
            .header("Accept", "application/json")
            .build()
        try {
            okHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ProviderException(
                        "Dramachi responded with HTTP ${response.code}",
                        statusCode = response.code,
                    )
                }
                val body = response.body?.string().orEmpty()
                Json.parseToJsonElement(body) as? JsonObject
                    ?: throw ProviderException("Dramachi returned malformed JSON")
            }
        } catch (io: IOException) {
            throw ProviderException("Cannot reach Dramachi right now", cause = io)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun splitId(raw: String): Pair<String, String?> {
        val parts = raw.split("::", limit = 2)
        return parts[0].trim() to parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun seasonNumber(key: String): Int? =
        Regex("\\d+").find(key)?.value?.toIntOrNull()

    private fun parseEpisodeNumber(rawTitle: String): Int? {
        val cleaned = cleanEpisodeTitle(rawTitle).uppercase()
        val patterns = listOf(
            Regex("S\\d{1,2}[ ._-]?E(\\d{1,4})"),
            Regex("EPISODE[ ._-]?(\\d{1,4})"),
            Regex("EP[ ._-]?(\\d{1,4})"),
            Regex("E(\\d{1,4})\\b"),
        )
        for (pattern in patterns) {
            pattern.find(cleaned)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return cleaned.split(Regex("[\\s._-]+")).lastOrNull()?.toIntOrNull()
    }

    private fun cleanEpisodeTitle(rawTitle: String): String {
        var title = rawTitle.trim()
        listOf(
            " 720p", " 1080p", " 540p", " 480p", " 360p", " DUB", " hi DUB",
            " ENG Subbed Full",
        ).forEach { suffix ->
            if (title.endsWith(suffix, ignoreCase = true)) {
                title = title.dropLast(suffix.length)
            }
        }
        return title.trim()
    }

    private fun parseSizeBytes(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val match = Regex("([\\d.]+)\\s*(TB|GB|MB|KB|B)", RegexOption.IGNORE_CASE).find(raw)
            ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].uppercase()
        val multiplier = when (unit) {
            "TB" -> 1024.0 * 1024 * 1024 * 1024
            "GB" -> 1024.0 * 1024 * 1024
            "MB" -> 1024.0 * 1024
            "KB" -> 1024.0
            else -> 1.0
        }
        return (value * multiplier).toLong()
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        const val PROVIDER_ID = "dramachi"
        private const val BASE_URL = "https://api.nodeobjects.com/"
        private const val IMAGE_CDN = "https://static.nodeobjects.com/thumbnail/"
        private const val CACHE_TTL_MS = 60_000L
    }
}

package com.joystream.app.data.providers.moviebox

import com.joystream.app.core.arr
import com.joystream.app.core.asArr
import com.joystream.app.core.asObj
import com.joystream.app.core.double
import com.joystream.app.core.int
import com.joystream.app.core.long
import com.joystream.app.core.obj
import com.joystream.app.core.str
import com.joystream.app.core.util.extractYear
import com.joystream.app.data.model.AudioTrack
import com.joystream.app.data.model.CatalogItem
import com.joystream.app.data.model.Episode
import com.joystream.app.data.model.MediaDetails
import com.joystream.app.data.model.MediaId
import com.joystream.app.data.model.MediaType
import com.joystream.app.data.model.Season
import com.joystream.app.data.model.StreamKind
import com.joystream.app.data.model.StreamRelease
import com.joystream.app.data.model.SubtitleTrack
import com.joystream.app.data.providers.HomeRow
import com.joystream.app.data.providers.MediaProvider
import com.joystream.app.data.providers.ProviderException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import java.util.Locale

/**
 * MovieBox (aka oneroom) provider: the main catalog — movies, series, anime and
 * asian dramas with multi-quality direct files plus adaptive DASH streams.
 */
class MovieBoxProvider(private val client: MovieBoxClient) : MediaProvider {

    override val id: String = PROVIDER_ID
    override val displayName: String = "MovieBox"
    override val description: String =
        "Largest catalog: movies, series, anime and dramas with 4K/1080p sources."
    override val supportsHomeFeed: Boolean = true
    override val supportsSubtitles: Boolean = true

    // ---------------------------------------------------------------- home feed

    override suspend fun homeFeed(): List<HomeRow> {
        val payload = client.get(HOMEPAGE_PATH)
        val groups = payload.asArr() ?: payload.arr("items") ?: JsonArray(emptyList())
        val seen = mutableSetOf<String>()
        val rows = mutableListOf<HomeRow>()

        groups.forEachIndexed { index, element ->
            val group = element.asObj() ?: return@forEachIndexed
            val title = group.obj("customData")?.str("title", "name", "tabName")
                ?: group.str("title", "name", "tabName")
                ?: if (index == 0) "Trending Now" else "More to Explore"

            val subjects = mutableListOf<JsonObject>()
            group.obj("banner")?.arr("banners")?.forEach { banner ->
                banner.asObj()?.obj("subject")?.let(subjects::add)
            }
            group.obj("customData")?.arr("items")?.forEach { entry ->
                entry.asObj()?.obj("subject")?.let(subjects::add)
            }
            group.arr("subjects")?.forEach { subject ->
                subject.asObj()?.let(subjects::add)
            }

            val items = subjects.mapNotNull { toCatalogItem(it) }
                .filter { seen.add(it.key) }

            if (items.isNotEmpty()) {
                rows += HomeRow(title = title, items = items)
            }
        }

        return rows
    }

    // ------------------------------------------------------------------- search

    override suspend fun search(query: String, page: Int): List<CatalogItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val body = buildString {
            append("{\"keyword\":").append(quote(trimmed))
            append(",\"page\":").append(page.coerceAtLeast(1))
            append(",\"perPage\":15,\"subjectType\":0}")
        }
        val payload = client.post(SEARCH_PATH, body)
        val subjects = payload.obj("data")?.arr("results")?.firstOrNull()?.asObj()?.arr("subjects")
            ?: payload.arr("results")?.firstOrNull()?.asObj()?.arr("subjects")
            ?: payload.arr("list")
            ?: payload.arr("items")
            ?: JsonArray(emptyList())
        return subjects.mapNotNull { it.asObj()?.let(::toCatalogItem) }
    }

    // ------------------------------------------------------------------ details

    override suspend fun details(id: MediaId): MediaDetails {
        val subjectId = id.value
        val payload = client.get("$SUBJECT_PATH$subjectId")
        val subject = payload.obj("subject") ?: payload

        val subjectType = subject.int("subjectType", "stype") ?: 1
        val mediaType = if (subjectType == 2) MediaType.SERIES else MediaType.MOVIE

        var seasonJson: JsonElement? = subject.obj("seasons")?.let { seasonsContainer ->
            seasonsContainer.arr("seasons") ?: seasonsContainer
        }

        if (mediaType == MediaType.SERIES && seasonJson == null) {
            seasonJson = runCatching {
                client.get("$SEASON_PATH$subjectId")
            }.getOrNull()?.let { seasonPayload ->
                seasonPayload.arr("seasons") ?: seasonPayload
            }
        }

        val seasons = parseSeasons(seasonJson)
        val dubs = subject.arr("dubs")?.mapNotNull { element ->
            val dub = element.asObj() ?: return@mapNotNull null
            val dubSubject = dub.str("subjectId", "id") ?: return@mapNotNull null
            val language = dub.str("lanName", "language", "lang") ?: "Unknown"
            AudioTrack(
                subjectId = dubSubject,
                language = language,
                label = dub.str("title", "name", "lanName") ?: language,
            )
        }.orEmpty()

        return MediaDetails(
            id = id,
            title = subject.str("title", "name") ?: "Unknown",
            mediaType = mediaType,
            year = extractYear(subject.str("releaseDate", "year", "releaseInfo")),
            overview = subject.str("description", "intro"),
            tagline = subject.str("tagline"),
            rating = subject.double("imdbRatingValue", "rating")?.let {
                String.format(Locale.US, "%.1f", it)
            } ?: subject.str("imdbRatingValue", "rating"),
            genres = subject.arr("genre", "genres")
                ?.mapNotNull { it.asObj()?.str("name") ?: (it as? JsonPrimitive)?.content }
                .orEmpty(),
            runtime = subject.long("duration")?.takeIf { it > 0 }?.let { "${it / 60}m" }
                ?: subject.str("duration")?.takeIf { it != "0" && it != "0m" },
            posterUrl = subject.obj("cover")?.str("url") ?: subject.str("coverUrl", "poster"),
            backdropUrl = subject.obj("cover")?.str("url") ?: subject.str("coverUrl", "poster"),
            cast = subject.str("stars", "cast")?.split(',', '/')
                ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            director = subject.str("director"),
            seasons = seasons,
            audioTracks = dubs,
            providerLabel = displayName,
        )
    }

    private fun parseSeasons(seasonJson: JsonElement?): List<Season> {
        val array = seasonJson as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val season = element.asObj() ?: return@mapNotNull null
            val number = season.int("se", "season", "seasonNumber") ?: 1
            val numbers = season.arr("episodeNumbers")?.mapNotNull {
                (it as? JsonPrimitive)?.content?.toIntOrNull()
            }.orEmpty()
            val maxEp = season.int("maxEp", "episodeCount") ?: numbers.maxOrNull()
            val episodes = when {
                numbers.isNotEmpty() -> numbers.distinct().sorted()
                maxEp != null && maxEp > 0 -> (1..maxEp).toList()
                else -> emptyList()
            }.map { Episode(season = number, number = it) }
            Season(number = number, episodes = episodes)
        }.filter { it.episodes.isNotEmpty() }.sortedBy { it.number }
    }

    // ----------------------------------------------------------------- releases

    override suspend fun releases(id: MediaId, season: Int, episode: Int): List<StreamRelease> {
        val subjectId = id.value
        val playInfoPath = if (season <= 0 && episode <= 0) {
            "$PLAY_INFO_PATH$subjectId"
        } else {
            "$PLAY_INFO_PATH$subjectId&se=$season&ep=$episode"
        }

        val results = mutableListOf<StreamRelease>()
        val seenUrls = mutableSetOf<String>()
        val seenIds = mutableSetOf<String>()

        fun add(release: StreamRelease?) {
            val candidate = release ?: return
            val base = candidate.url.substringBefore('?')
            if (!seenUrls.add(base)) return
            val key = "${candidate.resourceId ?: base}"
            if (!seenIds.add("${candidate.kind}:$key")) return
            results += candidate
        }

        // 1) Direct file listings (best for downloads & seeking).
        runCatching { client.get("$SUBJECT_PATH$subjectId") }
            .onSuccess { payload ->
                val subject = payload.obj("subject") ?: payload
                subject.arr("resourceDetectors")?.forEach { detectorElement ->
                    val detector = detectorElement.asObj() ?: return@forEach
                    val resolutionList = detector.arr("resolutionList")
                    if (resolutionList != null && resolutionList.isNotEmpty()) {
                        resolutionList.forEach { entry ->
                            entry.asObj()?.let { add(resourceItemToRelease(it, season, episode)) }
                        }
                        return@forEach
                    }
                    val downloadUrl = detector.str("downloadUrl") ?: return@forEach
                    val synthetic = buildMap {
                        put("resourceLink", JsonPrimitive(downloadUrl))
                        put("resourceId", detector["resourceId"] ?: JsonPrimitive(""))
                        put("size", detector["firstSize"]?.takeIf { it is JsonPrimitive } ?: JsonPrimitive(0))
                        put("codecName", detector["codecName"]?.takeIf { it is JsonPrimitive } ?: JsonPrimitive(""))
                        put("se", JsonPrimitive(season))
                        put("ep", JsonPrimitive(episode))
                        resolutionFromLink(detector.str("resourceLink").orEmpty())?.let {
                            put("resolution", JsonPrimitive(it))
                        }
                    }
                    add(resourceItemToRelease(JsonObject(synthetic), season, episode))
                }
            }

        // 2) Resource list for the exact episode, with placeholder recovery.
        val resourcePath = resourceV2Path(subjectId, season, episode)
        runCatching { client.get(resourcePath) }
            .onSuccess { payload ->
                val items = payload.arr("list") ?: payload.asArr() ?: JsonArray(emptyList())
                items.forEach { element ->
                    val item = element.asObj() ?: return@forEach
                    val itemSeason = item.int("se") ?: 0
                    val itemEpisode = item.int("ep") ?: 0
                    if ((season > 0 || episode > 0) &&
                        (itemSeason != season || itemEpisode != episode)
                    ) {
                        return@forEach
                    }
                    val release = resourceItemToRelease(item, season, episode)
                    if (release != null && release.url.isNotBlank()) {
                        add(release)
                        return@forEach
                    }

                    // Placeholder clip: ask the API to re-sign the real file.
                    val resourceId = item.str("resourceId", "id") ?: return@forEach
                    val origin = item.str("resourceLink", "url") ?: return@forEach
                    runCatching {
                        val recovered = client.get(
                            resourcePositionPath(subjectId, resourceId, origin),
                        )
                        val list = recovered.arr("list") ?: JsonArray(emptyList())
                        list.forEach { candidateElement ->
                            val candidate = candidateElement.asObj() ?: return@forEach
                            if (candidate.str("resourceId", "id") != resourceId) return@forEach
                            val fixed = resourceItemToRelease(candidate, season, episode)
                            if (fixed != null) add(fixed)
                        }
                    }
                }
            }

        // 3) Adaptive streams from play-info (DASH manifests signed via cookie).
        runCatching { client.get(playInfoPath) }
            .onSuccess { payload ->
                val title = cleanTitle(payload.str("title") ?: "")
                val streamHeaders = linkedMapOf(
                    "Referer" to STREAM_REFERER,
                    "User-Agent" to client.userAgent,
                )
                payload.arr("streams")?.forEach { element ->
                    val stream = element.asObj() ?: return@forEach
                    val rawUrl = stream.str("url").orEmpty()
                    val signCookie = stream.str("signCookie").orEmpty()
                    val manifest = MovieBoxCrypto.dashManifestFromPolicy(signCookie)
                        ?: rawUrl.takeIf { it.startsWith("http") && !isPlaceholderUrl(it) }
                        ?: return@forEach

                    val headers = streamHeaders.toMutableMap()
                    if (signCookie.isNotBlank()) {
                        headers["Cookie"] = signCookie
                            .trimEnd(';')
                            .split(';')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .joinToString("; ")
                    }

                    val maxHeight = (stream.str("resolutions") ?: payload.str("displayResolutions"))
                        ?.split(',')
                        ?.mapNotNull { it.trim().toIntOrNull() }
                        ?.maxOrNull()
                        ?: 1080

                    val kind = when {
                        manifest.contains(".mpd") -> StreamKind.DASH
                        manifest.contains(".m3u8") -> StreamKind.HLS
                        else -> StreamKind.PROGRESSIVE
                    }

                    val releaseTitle = if (season > 0 && episode > 0) {
                        "$title S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
                    } else {
                        title.ifBlank { "MovieBox Stream" }
                    }

                    add(
                        StreamRelease(
                            provider = id,
                            title = releaseTitle,
                            url = manifest,
                            kind = kind,
                            headers = headers,
                            qualityLabel = if (kind == StreamKind.PROGRESSIVE) "${maxHeight}p" else "Adaptive 4K",
                            resolutionHeight = maxHeight,
                            codec = stream.str("codecName", "codec") ?: stream.str("format"),
                            sizeBytes = stream.long("size"),
                            season = season.takeIf { it > 0 },
                            episode = episode.takeIf { it > 0 },
                            resourceId = stream.str("id"),
                            mirrorLabel = if (kind == StreamKind.DASH) "DASH" else "MovieBox CDN",
                        ),
                    )
                }
            }

        if (results.isEmpty()) {
            throw ProviderException(
                "No playable sources for this title yet — MovieBox may be serving a placeholder clip.",
            )
        }

        return results.sortedWith(
            compareByDescending<StreamRelease> { it.resolutionHeight ?: 0 }
                .thenByDescending { it.sizeBytes ?: 0L }
                .thenBy { it.kind == StreamKind.DASH },
        )
    }

    // ---------------------------------------------------------------- subtitles

    override suspend fun subtitles(id: MediaId, release: StreamRelease): List<SubtitleTrack> {
        val resourceId = release.resourceId ?: return emptyList()
        val payload = runCatching {
            client.get("$CAPTIONS_PATH${id.value}&resourceId=$resourceId")
        }.getOrNull() ?: return emptyList()

        val captions = payload.arr("extCaptions")
            ?: payload.obj("data")?.arr("extCaptions")
            ?: return emptyList()

        val seen = mutableSetOf<String>()
        return captions.mapNotNull { element ->
            val caption = element.asObj() ?: return@mapNotNull null
            val url = caption.str("url") ?: return@mapNotNull null
            if (url.isBlank() || !seen.add(url)) return@mapNotNull null
            val size = caption.long("size") ?: 0L
            if (size in 1..50) return@mapNotNull null
            SubtitleTrack(
                name = caption.str("lanName", "lan") ?: "Subtitle",
                url = url,
                language = caption.str("lan"),
            )
        }
    }

    // ------------------------------------------------------------------ mapping

    private fun toCatalogItem(subject: JsonObject): CatalogItem? {
        val subjectId = subject.str("subjectId", "id") ?: return null
        val subjectType = subject.int("subjectType", "stype") ?: 1
        return CatalogItem(
            provider = id,
            idValue = subjectId,
            title = subject.str("title", "name") ?: "Unknown",
            mediaType = if (subjectType == 2) MediaType.SERIES else MediaType.MOVIE,
            year = extractYear(subject.str("releaseDate", "year", "releaseInfo")),
            posterUrl = subject.obj("cover")?.str("url")
                ?: subject.str("coverUrl", "poster", "pic"),
            rating = subject.double("imdbRatingValue", "rating")
                ?.let { String.format(Locale.US, "%.1f", it) },
            seasonCount = subject.int("season"),
        )
    }

    private fun resourceItemToRelease(
        item: JsonObject,
        season: Int,
        episode: Int,
    ): StreamRelease? {
        val url = item.str("resourceLink", "url") ?: return null
        if (url.isBlank() || isPlaceholderUrl(url)) return null

        val resolutionLabel = when (val resolution = item["resolution"]) {
            is JsonPrimitive -> resolution.content.takeIf { it.isNotBlank() && it != "null" }
            else -> null
        }
        val height = resolutionLabel?.substringBefore('p')?.toIntOrNull()
            ?: resolutionFromLink(url)

        return StreamRelease(
            provider = id,
            title = item.str("fileName", "title") ?: "MovieBox source",
            url = url,
            kind = StreamKind.PROGRESSIVE,
            headers = mapOf("User-Agent" to client.userAgent),
            qualityLabel = height?.let { "${it}p" },
            resolutionHeight = height,
            codec = item.str("codecName", "codec"),
            sizeBytes = item.long("size"),
            season = item.int("se")?.takeIf { it > 0 } ?: season.takeIf { it > 0 },
            episode = item.int("ep")?.takeIf { it > 0 } ?: episode.takeIf { it > 0 },
            resourceId = item.str("resourceId", "id"),
            mirrorLabel = item.str("uploadBy", "source") ?: "Direct",
        )
    }

    private fun resolutionFromLink(link: String): Int? {
        val match = Regex("(\\d{3,4})[pP]").find(link) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun cleanTitle(raw: String): String = raw
        .substringAfterLast(']')
        .substringBefore('[')
        .substringBefore('(')
        .replace(Regex("\\.[a-zA-Z0-9]{2,4}$"), "")
        .trim()

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun resourceV2Path(subjectId: String, season: Int, episode: Int): String =
        "$RESOURCE_V2_PATH$subjectId&page=1&perPage=20&all=0&startPosition=1&endPosition=1" +
            "&pagerMode=2&resolution=0&se=$season&epFrom=$episode&epTo=$episode&isVip=true"

    private fun resourcePositionPath(subjectId: String, resourceId: String, failUrl: String): String {
        val encoded = URLEncoder.encode(failUrl, "UTF-8")
        return "$RESOURCE_POSITION_PATH$subjectId&resourceId=$resourceId&failUrl=$encoded" +
            "&failCode=404&resourceNum=0&isVip=true"
    }

    companion object {
        const val PROVIDER_ID = "moviebox"
        private const val STREAM_REFERER = "https://sportslive.wine"

        private const val HOMEPAGE_PATH = "/wefeed-mobile-bff/tab-operating?page=1&tabId=2&version="
        private const val SEARCH_PATH = "/wefeed-mobile-bff/subject-api/search/v2"
        private const val SUBJECT_PATH = "/wefeed-mobile-bff/subject-api/get?subjectId="
        private const val SEASON_PATH = "/wefeed-mobile-bff/subject-api/season-info?subjectId="
        private const val PLAY_INFO_PATH = "/wefeed-mobile-bff/subject-api/play-info/v2?subjectId="
        private const val RESOURCE_V2_PATH = "/wefeed-mobile-bff/subject-api/resource/v2?subjectId="
        private const val RESOURCE_POSITION_PATH =
            "/wefeed-mobile-bff/subject-api/resource-position/v2?subjectId="
        private const val CAPTIONS_PATH =
            "/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId="

        fun isPlaceholderUrl(url: String): Boolean {
            val lower = url.lowercase()
            return lower.contains("1c7de0bd3393702d9191801f15f88f8d") ||
                lower.contains("9a0461bc39da389663bf3dbb17091d3f") ||
                lower.contains("b164fbfb4347792950bdfbfb563d39d9") ||
                lower.contains("/notice.mp4") ||
                (lower.contains("macdn.aoneroom.com") && lower.contains("/other/"))
        }
    }
}

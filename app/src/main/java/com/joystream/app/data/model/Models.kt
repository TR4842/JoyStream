package com.joystream.app.data.model

import kotlinx.serialization.Serializable

/** Media kinds JoyStream can present. */
enum class MediaType {
    MOVIE,
    SERIES,
    LIVE,
    UNKNOWN;

    val label: String
        get() = when (this) {
            MOVIE -> "Movie"
            SERIES -> "Series"
            LIVE -> "Live"
            UNKNOWN -> "Title"
        }

    companion object {
        fun fromRaw(raw: String?): MediaType = when (raw?.lowercase()) {
            "movie", "movies" -> MOVIE
            "series", "tv", "show", "drama" -> SERIES
            "live", "channel" -> LIVE
            else -> UNKNOWN
        }
    }
}

/**
 * A provider-scoped media id. `value` is whatever the provider uses
 * (a subject id for MovieBox, a path for 4KHDHub, `title::rip` for Dramachi).
 */
@Serializable
data class MediaId(val provider: String, val value: String) {
    val key: String get() = "$provider:$value"
}

/** A poster-sized catalog entry shown in rows, grids and search results. */
@Serializable
data class CatalogItem(
    val provider: String,
    val idValue: String,
    val title: String,
    val mediaType: MediaType = MediaType.UNKNOWN,
    val year: String? = null,
    val posterUrl: String? = null,
    val rating: String? = null,
    val seasonCount: Int? = null,
) {
    val id: MediaId get() = MediaId(provider, idValue)
    val key: String get() = id.key
}

data class Episode(
    val season: Int,
    val number: Int,
    val title: String? = null,
    val overview: String? = null,
) {
    val label: String
        get() = if (season > 0) "S${season.toString().padStart(2, '0')}E${number.toString().padStart(2, '0')}"
        else "E${number.toString().padStart(2, '0')}"
}

data class Season(val number: Int, val episodes: List<Episode>)

/** A selectable audio track / dub edition, e.g. "Hindi Dub". */
data class AudioTrack(val subjectId: String, val language: String, val label: String)

data class MediaDetails(
    val id: MediaId,
    val title: String,
    val mediaType: MediaType,
    val year: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val rating: String? = null,
    val genres: List<String> = emptyList(),
    val runtime: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val cast: List<String> = emptyList(),
    val director: String? = null,
    val seasons: List<Season> = emptyList(),
    val audioTracks: List<AudioTrack> = emptyList(),
    val providerLabel: String = "",
) {
    val hasEpisodes: Boolean get() = seasons.any { it.episodes.isNotEmpty() }
    val firstEpisode: Episode?
        get() = seasons.firstOrNull { it.episodes.isNotEmpty() }?.episodes?.firstOrNull()
}

enum class StreamKind { PROGRESSIVE, DASH, HLS }

/** One playable stream quality/source returned by a provider. */
data class StreamRelease(
    val provider: String,
    val title: String,
    val url: String,
    val kind: StreamKind = StreamKind.PROGRESSIVE,
    val headers: Map<String, String> = emptyMap(),
    val qualityLabel: String? = null,
    val resolutionHeight: Int? = null,
    val codec: String? = null,
    val sizeBytes: Long? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val resourceId: String? = null,
    val mirrorLabel: String = "Direct",
) {
    val canDownload: Boolean get() = kind == StreamKind.PROGRESSIVE && url.startsWith("http")

    val displayLabel: String
        get() {
            val quality = qualityLabel ?: resolutionHeight?.let { "${it}p" } ?: kind.name
            return listOfNotNull(quality, codec, sizeBytes?.let(::formatBytes)).joinToString(" • ")
        }
}

data class SubtitleTrack(
    val name: String,
    val url: String,
    val language: String? = null,
)

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${value.toInt()} ${units[index]}"
    else String.format("%.1f %s", value, units[index])
}

/** Everything the player needs, serialised into the player Intent. */
@Serializable
data class PlaybackRequest(
    val title: String,
    val subtitle: String? = null,
    val url: String,
    val kind: String = StreamKind.PROGRESSIVE.name,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleSpec> = emptyList(),
    val startPositionMs: Long = 0L,
    val resumeKey: String? = null,
    val posterUrl: String? = null,
    val maxHeight: Int? = null,
    val isLive: Boolean = false,
    val provider: String? = null,
    val idValue: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

@Serializable
data class SubtitleSpec(
    val name: String,
    val url: String,
    val language: String? = null,
)

package com.joystream.app.data.model

import kotlinx.serialization.Serializable

/** A title saved to My List. */
@Serializable
data class FavoriteItem(
    val key: String,
    val provider: String,
    val idValue: String,
    val title: String,
    val posterUrl: String? = null,
    val mediaType: String = "UNKNOWN",
    val year: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
) {
    val id: MediaId get() = MediaId(provider, idValue)
}

/** Per-episode resume state, keyed by "s01e02" inside a HistoryItem. */
@Serializable
data class EpisodeProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
) {
    val fraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** A continue-watching entry. */
@Serializable
data class HistoryItem(
    val key: String,
    val provider: String,
    val idValue: String,
    val title: String,
    val posterUrl: String? = null,
    val mediaType: String = "UNKNOWN",
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Local file for downloaded items, so playback works offline. */
    val localPath: String? = null,
    /** Per-episode positions, e.g. {"s01e02": EpisodeProgress(...)}. */
    val episodeProgress: Map<String, EpisodeProgress> = emptyMap(),
) {
    val id: MediaId get() = MediaId(provider, idValue)

    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val episodeLabel: String?
        get() = when {
            season == null && episode == null -> null
            (season ?: 0) > 0 -> "S${(season ?: 0).toString().padStart(2, '0')}E${(episode ?: 0).toString().padStart(2, '0')}"
            else -> "E${(episode ?: 0).toString().padStart(2, '0')}"
        }
}

enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

@Serializable
data class DownloadItem(
    val id: String,
    val provider: String,
    val idValue: String,
    val title: String,
    val episodeLabel: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val fileName: String = "video.mp4",
    val posterUrl: String? = null,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val status: String = DownloadStatus.QUEUED.name,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val localPath: String? = null,
) {
    val statusEnum: DownloadStatus
        get() = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.QUEUED)

    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/** Snapshot persisted to disk by LibraryStore. */
@Serializable
data class LibrarySnapshot(
    val favorites: List<FavoriteItem> = emptyList(),
    val history: List<HistoryItem> = emptyList(),
    val downloads: List<DownloadItem> = emptyList(),
    val searchHistory: List<String> = emptyList(),
)

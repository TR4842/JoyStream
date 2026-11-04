package com.joystream.app.data.providers

import com.joystream.app.data.model.CatalogItem
import com.joystream.app.data.model.MediaDetails
import com.joystream.app.data.model.MediaId
import com.joystream.app.data.model.StreamRelease
import com.joystream.app.data.model.SubtitleTrack

/** A titled strip of catalog items on the home screen. */
data class HomeRow(val title: String, val items: List<CatalogItem>)

class ProviderException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    val isRateLimited: Boolean get() = statusCode == 429
    val isNotFound: Boolean get() = statusCode == 404
}

/**
 * Contract every content source implements. Providers are stateless singletons;
 * the repository fans out across whichever ones the user has enabled.
 */
interface MediaProvider {
    val id: String
    val displayName: String

    /** Short description shown in Settings → Providers. */
    val description: String

    /** Whether this provider publishes a browsable home feed. */
    val supportsHomeFeed: Boolean get() = false

    /** Whether the provider can fetch subtitle tracks. */
    val supportsSubtitles: Boolean get() = false

    suspend fun homeFeed(): List<HomeRow> = emptyList()

    suspend fun search(query: String, page: Int = 1): List<CatalogItem> = emptyList()

    suspend fun details(id: MediaId): MediaDetails

    suspend fun releases(id: MediaId, season: Int = 0, episode: Int = 0): List<StreamRelease>

    suspend fun subtitles(id: MediaId, release: StreamRelease): List<SubtitleTrack> = emptyList()
}

package com.joystream.app.data.providers

import com.joystream.app.data.model.CatalogItem
import com.joystream.app.data.model.MediaDetails
import com.joystream.app.data.model.MediaId
import com.joystream.app.data.model.StreamRelease
import com.joystream.app.data.model.SubtitleTrack
import com.joystream.app.data.settings.SettingsRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async

data class HomeFeed(
    val rows: List<HomeRow> = emptyList(),
    val errors: List<String> = emptyList(),
)

data class SearchResults(
    val items: List<CatalogItem> = emptyList(),
    val errors: List<String> = emptyList(),
)

/**
 * Fans requests out across the user's enabled providers and merges the results.
 * A failing provider never blocks the rest of the app — its error is surfaced
 * alongside whatever content loaded successfully.
 */
class CatalogRepository(
    private val allProviders: List<MediaProvider>,
    private val settings: SettingsRepository,
) {

    fun all(): List<MediaProvider> = allProviders

    fun enabled(): List<MediaProvider> =
        allProviders.filter { settings.current.isProviderEnabled(it.id) }
            .ifEmpty { allProviders.take(1) }

    fun provider(providerId: String): MediaProvider? =
        allProviders.firstOrNull { it.id == providerId }

    private fun providerOrThrow(providerId: String): MediaProvider =
        provider(providerId)
            ?: throw ProviderException("Unknown provider \"$providerId\"")

    suspend fun homeFeed(): HomeFeed = coroutineScope {
        val providers = enabled().filter { it.supportsHomeFeed }
        val results = providers.map { provider ->
            async {
                runCatching { provider.homeFeed() }
                    .fold(
                        onSuccess = { rows -> provider.displayName to rows },
                        onFailure = { error -> null to listOf(error to provider.displayName) },
                    )
            }
        }.map { it.await() }

        val rows = mutableListOf<HomeRow>()
        val errors = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        for ((providerName, value) in results) {
            if (providerName != null) {
                value.filterIsInstance<HomeRow>().forEach { row ->
                    val items = row.items.filter { seen.add(it.key) }
                    if (items.isNotEmpty()) rows += row.copy(items = items)
                }
            } else {
                value.filterIsInstance<Pair<Throwable, String>>().forEach { (error, name) ->
                    errors += "$name: ${error.message ?: "unavailable"}"
                }
            }
        }

        HomeFeed(rows = rows, errors = errors)
    }

    suspend fun search(query: String, page: Int = 1): SearchResults = coroutineScope {
        val providers = enabled()
        val outcomes = providers.map { provider ->
            async {
                provider to runCatching { provider.search(query, page) }
            }
        }.map { it.await() }

        val items = mutableListOf<CatalogItem>()
        val errors = mutableListOf<String>()
        outcomes.forEach { (provider, result) ->
            result.onSuccess { items += it }
                .onFailure { errors += "${provider.displayName}: ${it.message ?: "search failed"}" }
        }

        SearchResults(items = items, errors = errors)
    }

    suspend fun details(id: MediaId): MediaDetails = providerOrThrow(id.provider).details(id)

    suspend fun releases(id: MediaId, season: Int, episode: Int): List<StreamRelease> =
        providerOrThrow(id.provider).releases(id, season, episode)

    suspend fun subtitles(id: MediaId, release: StreamRelease): List<SubtitleTrack> =
        runCatching { providerOrThrow(id.provider).subtitles(id, release) }.getOrDefault(emptyList())
}

package com.joystream.app.data.providers.livetv

import android.content.Context
import com.joystream.app.core.Http
import com.joystream.app.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

data class LiveTvState(
    val channels: List<Channel> = emptyList(),
    val groups: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val sourceLabel: String = "",
    val lastUpdated: Long = 0L,
) {
    val isEmpty: Boolean get() = channels.isEmpty()
}

/**
 * Loads M3U playlists (remote URL or imported text), caching the last good copy
 * on disk so Live TV keeps working offline.
 */
class LiveTvRepository(
    context: Context,
    private val settings: SettingsRepository,
) {

    private val cacheFile = File(context.applicationContext.filesDir, "live_tv_playlist.m3u")
    private val _state = MutableStateFlow(LiveTvState())
    val state: StateFlow<LiveTvState> = _state.asStateFlow()

    /** Loads from cache first (instant), then refreshes from the network. */
    suspend fun load(forceRefresh: Boolean = false) {
        if (!forceRefresh && _state.value.channels.isNotEmpty()) return

        val cached = runCatching { cacheFile.takeIf { it.exists() }?.readText() }.getOrNull()
        if (cached != null) {
            publish(cached, fromCache = true)
        }

        val configured = settings.current.liveTvUrl.trim()
        if (configured.isEmpty()) {
            if (cached == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = "Add an M3U playlist URL in Settings to watch live TV.",
                )
            }
            return
        }

        _state.value = _state.value.copy(loading = true, error = null)
        try {
            val content = if (configured.startsWith("http")) {
                fetchRemote(configured)
            } else {
                File(configured).readText()
            }
            if (content.isBlank() || !content.contains("#EXT", ignoreCase = true)) {
                throw IOException("That playlist has no channels")
            }
            runCatching { cacheFile.writeText(content) }
            publish(content, fromCache = false)
        } catch (error: Exception) {
            _state.value = _state.value.copy(
                loading = false,
                error = error.message ?: "Could not load the playlist",
            )
        }
    }

    /** Import a playlist from text (e.g. shared file or pasted content). */
    suspend fun importContent(content: String): Boolean {
        if (!content.contains("#EXT", ignoreCase = true)) return false
        runCatching { cacheFile.writeText(content) }
        publish(content, fromCache = false)
        return true
    }

    private fun publish(content: String, fromCache: Boolean) {
        val channels = M3uParser.parse(content)
        val groups = channels.map { it.group }.distinct().sorted()
        _state.value = LiveTvState(
            channels = channels,
            groups = groups,
            loading = false,
            error = null,
            sourceLabel = if (fromCache) "Cached playlist" else "Playlist",
            lastUpdated = System.currentTimeMillis(),
        )
    }

    private suspend fun fetchRemote(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Http.MOBILE_USER_AGENT)
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Playlist request failed (HTTP ${response.code})")
            }
            val body = response.body?.string().orEmpty()
            if (body.length > MAX_PLAYLIST_CHARS) {
                throw IOException("Playlist is too large (max 15 MB)")
            }
            body
        }
    }

    private companion object {
        const val MAX_PLAYLIST_CHARS = 15 * 1024 * 1024
    }
}

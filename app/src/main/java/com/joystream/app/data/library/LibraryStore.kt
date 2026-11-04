package com.joystream.app.data.library

import android.content.Context
import com.joystream.app.data.model.CatalogItem
import com.joystream.app.data.model.DownloadItem
import com.joystream.app.data.model.DownloadStatus
import com.joystream.app.data.model.FavoriteItem
import com.joystream.app.data.model.HistoryItem
import com.joystream.app.data.model.LibrarySnapshot
import com.joystream.app.data.model.MediaDetails
import com.joystream.app.data.model.MediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local-only library: favorites, watch history / resume positions, downloads and
 * search history. Persisted as a single JSON document with atomic writes.
 */
class LibraryStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "library.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
    private val mutex = Mutex()

    private val _snapshot = MutableStateFlow(LibrarySnapshot())
    val snapshot: StateFlow<LibrarySnapshot> = _snapshot.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val loaded = runCatching {
                if (file.exists()) json.decodeFromString(LibrarySnapshot.serializer(), file.readText())
                else LibrarySnapshot()
            }.getOrElse { LibrarySnapshot() }
            _snapshot.value = loaded
        }
    }

    private suspend fun mutate(transform: (LibrarySnapshot) -> LibrarySnapshot) {
        mutex.withLock {
            val next = transform(_snapshot.value)
            _snapshot.value = next
            withContext(Dispatchers.IO) { persist(next) }
        }
    }

    private fun persist(snapshot: LibrarySnapshot) {
        runCatching {
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(LibrarySnapshot.serializer(), snapshot))
            if (file.exists()) file.delete()
            temp.renameTo(file)
        }
    }

    // ---------------------------------------------------------------- favorites

    fun isFavorite(key: String): Boolean = _snapshot.value.favorites.any { it.key == key }

    suspend fun toggleFavorite(item: CatalogItem) = mutate { snapshot ->
        val existing = snapshot.favorites.firstOrNull { it.key == item.key }
        if (existing != null) {
            snapshot.copy(favorites = snapshot.favorites - existing)
        } else {
            val favorite = FavoriteItem(
                key = item.key,
                provider = item.provider,
                idValue = item.idValue,
                title = item.title,
                posterUrl = item.posterUrl,
                mediaType = item.mediaType.name,
                year = item.year,
            )
            snapshot.copy(favorites = listOf(favorite) + snapshot.favorites)
        }
    }

    suspend fun removeFavorite(key: String) = mutate { snapshot ->
        snapshot.copy(favorites = snapshot.favorites.filterNot { it.key == key })
    }

    // ------------------------------------------------------------------ history

    /** Upserts a history entry, keeping the newest position. */
    suspend fun recordPlayback(
        details: MediaDetails?,
        item: CatalogItem?,
        provider: String,
        idValue: String,
        positionMs: Long,
        durationMs: Long,
        season: Int? = null,
        episode: Int? = null,
        episodeTitle: String? = null,
        localPath: String? = null,
    ) = mutate { snapshot ->
        val key = MediaId(provider, idValue).key
        val existing = snapshot.history.firstOrNull { it.key == key }
        val updated = HistoryItem(
            key = key,
            provider = provider,
            idValue = idValue,
            title = details?.title ?: item?.title ?: existing?.title ?: "Untitled",
            posterUrl = details?.posterUrl ?: item?.posterUrl ?: existing?.posterUrl,
            mediaType = (details?.mediaType ?: item?.mediaType)?.name
                ?: existing?.mediaType ?: "UNKNOWN",
            season = season ?: existing?.season,
            episode = episode ?: existing?.episode,
            episodeTitle = episodeTitle ?: existing?.episodeTitle,
            positionMs = positionMs,
            durationMs = if (durationMs > 0) durationMs else existing?.durationMs ?: 0L,
            localPath = localPath ?: existing?.localPath,
            updatedAt = System.currentTimeMillis(),
        )
        val remaining = snapshot.history.filterNot { it.key == key }
        snapshot.copy(history = (listOf(updated) + remaining).take(60))
    }

    suspend fun clearHistory() = mutate { it.copy(history = emptyList()) }

    suspend fun removeHistory(key: String) = mutate { snapshot ->
        snapshot.copy(history = snapshot.history.filterNot { it.key == key })
    }

    fun resumePosition(key: String): Long {
        val entry = _snapshot.value.history.firstOrNull { it.key == key } ?: return 0L
        return if (entry.progress in 0.02f..0.95f) entry.positionMs else 0L
    }

    // ------------------------------------------------------------- search terms

    suspend fun recordSearch(query: String) = mutate { snapshot ->
        val trimmed = query.trim()
        if (trimmed.length < 2) return@mutate snapshot
        val terms = (listOf(trimmed) + snapshot.searchHistory.filterNot {
            it.equals(trimmed, ignoreCase = true)
        }).take(10)
        snapshot.copy(searchHistory = terms)
    }

    suspend fun clearSearchHistory() = mutate { it.copy(searchHistory = emptyList()) }

    // ----------------------------------------------------------------- downloads

    suspend fun upsertDownload(item: DownloadItem) = mutate { snapshot ->
        val others = snapshot.downloads.filterNot { it.id == item.id }
        snapshot.copy(downloads = listOf(item) + others)
    }

    suspend fun updateDownload(id: String, transform: (DownloadItem) -> DownloadItem) =
        mutate { snapshot ->
            snapshot.copy(
                downloads = snapshot.downloads.map { if (it.id == id) transform(it) else it },
            )
        }

    suspend fun removeDownload(id: String) = mutate { snapshot ->
        snapshot.copy(downloads = snapshot.downloads.filterNot { it.id == id })
    }

    fun download(id: String): DownloadItem? = _snapshot.value.downloads.firstOrNull { it.id == id }

    fun completedDownloads(): List<DownloadItem> = _snapshot.value.downloads.filter {
        it.statusEnum == DownloadStatus.COMPLETED
    }

    suspend fun setAllDownloadsStatus(from: DownloadStatus, to: DownloadStatus) = mutate { snapshot ->
        snapshot.copy(
            downloads = snapshot.downloads.map {
                if (it.statusEnum == from) it.copy(status = to.name) else it
            },
        )
    }
}

package com.joystream.app.ui.library

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.joystream.app.core.AppContainer
import com.joystream.app.data.download.DownloadManager
import com.joystream.app.data.model.CatalogItem
import com.joystream.app.data.model.DownloadItem
import com.joystream.app.data.model.DownloadStatus
import com.joystream.app.data.model.HistoryItem
import com.joystream.app.data.model.MediaId
import com.joystream.app.data.model.PlaybackRequest
import com.joystream.app.data.model.StreamKind
import com.joystream.app.data.model.formatBytes
import com.joystream.app.ui.components.EmptyState
import com.joystream.app.ui.components.PosterCard
import com.joystream.app.ui.player.startPlayer
import com.joystream.app.ui.theme.JoyGreen
import com.joystream.app.ui.theme.JoyMuted
import com.joystream.app.ui.theme.JoyPurple
import com.joystream.app.ui.theme.JoySurfaceHigh
import kotlinx.coroutines.launch
import java.io.File

private enum class LibraryTab(val label: String) { CONTINUE("Continue"), LIST("My List"), DOWNLOADS("Downloads") }

@Composable
fun LibraryScreen(
    container: AppContainer,
    onOpenDetails: (MediaId, Int?, Int?) -> Unit,
) {
    val snapshot by container.libraryStore.snapshot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by remember { mutableStateOf(LibraryTab.CONTINUE) }

    val continueWatching = snapshot.history
    val downloads = snapshot.downloads

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Text(
            text = "Library",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 10.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryTab.entries.forEach { entry ->
                Surface(
                    color = if (tab == entry) JoyPurple.copy(alpha = 0.28f) else JoySurfaceHigh,
                    shape = CircleShape,
                    modifier = Modifier.clickable { tab = entry },
                ) {
                    Text(
                        text = when (entry) {
                            LibraryTab.DOWNLOADS -> "${entry.label} (${downloads.size})"
                            LibraryTab.LIST -> "${entry.label} (${snapshot.favorites.size})"
                            else -> entry.label
                        },
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        when (tab) {
            LibraryTab.CONTINUE -> {
                if (continueWatching.isEmpty()) {
                    EmptyState(
                        title = "Nothing in progress",
                        message = "Titles you start watching show up here so you can pick up where you left off.",
                        actionLabel = "Browse",
                        onAction = { tab = LibraryTab.LIST },
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(continueWatching, key = { it.key }) { entry ->
                            ContinueRow(
                                entry = entry,
                                onClick = {
                                    if (entry.localPath != null) {
                                        playLocal(context, entry)
                                    } else {
                                        onOpenDetails(
                                            MediaId(entry.provider, entry.idValue),
                                            entry.season,
                                            entry.episode,
                                        )
                                    }
                                },
                                onRemove = {
                                    scope.launch { container.libraryStore.removeHistory(entry.key) }
                                },
                            )
                        }
                        item {
                            TextButton(
                                onClick = {
                                    scope.launch { container.libraryStore.clearHistory() }
                                },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            ) {
                                Text("Clear watch history")
                            }
                        }
                    }
                }
            }

            LibraryTab.LIST -> {
                if (snapshot.favorites.isEmpty()) {
                    EmptyState(
                        title = "My List is empty",
                        message = "Tap the bookmark on any title to keep it here for later.",
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(104.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(snapshot.favorites, key = { it.key }) { favorite ->
                            PosterCard(
                                item = CatalogItem(
                                    provider = favorite.provider,
                                    idValue = favorite.idValue,
                                    title = favorite.title,
                                    posterUrl = favorite.posterUrl,
                                    year = favorite.year,
                                    mediaType = com.joystream.app.data.model.MediaType.fromRaw(favorite.mediaType),
                                ),
                                onClick = {
                                    onOpenDetails(MediaId(favorite.provider, favorite.idValue), null, null)
                                },
                            )
                        }
                    }
                }
            }

            LibraryTab.DOWNLOADS -> {
                if (downloads.isEmpty()) {
                    EmptyState(
                        title = "No downloads yet",
                        message = "Pick a direct MP4 source from any title and tap download to watch offline.",
                        icon = Icons.Rounded.Download,
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(downloads, key = { it.id }) { item ->
                            DownloadRow(
                                item = item,
                                onPlay = {
                                    val path = item.localPath
                                    if (path != null && File(path).exists()) {
                                        startPlayer(
                                            context,
                                            PlaybackRequest(
                                                title = item.title,
                                                subtitle = item.episodeLabel,
                                                url = Uri.fromFile(File(path)).toString(),
                                                kind = StreamKind.PROGRESSIVE.name,
                                                resumeKey = MediaId(item.provider, item.idValue).key,
                                                provider = item.provider,
                                                idValue = item.idValue,
                                                season = item.season,
                                                episode = item.episode,
                                                posterUrl = item.posterUrl,
                                            ),
                                        )
                                    }
                                },
                                onPause = { DownloadManager.pauseAll(context) },
                                onResume = { DownloadManager.resumeAll(context) },
                                onDelete = {
                                    scope.launch {
                                        item.localPath?.let { runCatching { File(it).delete() } }
                                        container.libraryStore.removeDownload(item.id)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun playLocal(context: android.content.Context, entry: HistoryItem) {
    val path = entry.localPath ?: return
    if (!File(path).exists()) return
    startPlayer(
        context,
        PlaybackRequest(
            title = entry.title,
            subtitle = entry.episodeTitle ?: entry.episodeLabel,
            url = Uri.fromFile(File(path)).toString(),
            kind = StreamKind.PROGRESSIVE.name,
            resumeKey = entry.key,
            startPositionMs = entry.positionMs,
            provider = entry.provider,
            idValue = entry.idValue,
            season = entry.season,
            episode = entry.episode,
            posterUrl = entry.posterUrl,
        ),
    )
}

@Composable
private fun ContinueRow(entry: HistoryItem, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 74.dp, height = 104.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(JoySurfaceHigh),
        ) {
            AsyncImage(
                model = entry.posterUrl,
                contentDescription = entry.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    entry.episodeLabel,
                    if (entry.localPath != null) "Downloaded" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = JoyMuted,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { entry.progress },
                color = JoyPurple,
                trackColor = JoySurfaceHigh,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.Delete, contentDescription = "Remove", tint = JoyMuted)
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val status = item.statusEnum
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 78.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(JoySurfaceHigh),
        ) {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    item.episodeLabel,
                    status.name.lowercase().replaceFirstChar { it.uppercase() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = when (status) {
                    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                    DownloadStatus.COMPLETED -> JoyGreen
                    else -> JoyMuted
                },
            )
            if (status != DownloadStatus.COMPLETED) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { item.progress },
                    color = JoyPurple,
                    trackColor = JoySurfaceHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
                Text(
                    text = if (item.totalBytes > 0) {
                        "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}"
                    } else {
                        formatBytes(item.downloadedBytes)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = JoyMuted,
                )
            }
            item.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )
            }
        }
        when (status) {
            DownloadStatus.COMPLETED -> IconButton(onClick = onPlay) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = Color.White)
            }

            DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> IconButton(onClick = onPause) {
                Icon(Icons.Rounded.Pause, contentDescription = "Pause", tint = Color.White)
            }

            else -> IconButton(onClick = onResume) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Resume", tint = Color.White)
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = JoyMuted)
        }
    }
}

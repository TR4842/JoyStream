package com.joystream.app.ui.livetv

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.joystream.app.data.model.PlaybackRequest
import com.joystream.app.data.model.StreamKind
import com.joystream.app.data.providers.livetv.Channel
import com.joystream.app.ui.components.EmptyState
import com.joystream.app.ui.player.startPlayer
import com.joystream.app.ui.theme.JoyMuted
import com.joystream.app.ui.theme.JoySurfaceHigh
import kotlinx.coroutines.launch

@Composable
fun LiveTvScreen(container: AppContainer, onOpenSettings: () -> Unit) {
    val state by container.liveTv.state.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var selectedGroup by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { container.liveTv.load() }

    val channels = state.channels.filter { channel ->
        (selectedGroup == null || channel.group == selectedGroup) &&
            (query.isBlank() || channel.name.contains(query, ignoreCase = true))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Live TV", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = when {
                        state.loading -> "Loading playlist…"
                        state.channels.isNotEmpty() -> "${state.channels.size} channels"
                        settings.liveTvUrl.isBlank() -> "No playlist configured"
                        else -> "Playlist not loaded"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = JoyMuted,
                )
            }
            IconButton(onClick = { scope.launch { container.liveTv.load(forceRefresh = true) } }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh playlist")
            }
        }

        if (settings.liveTvUrl.isBlank() && state.channels.isEmpty() && !state.loading) {
            EmptyState(
                title = "Add your IPTV playlist",
                message = "Paste an M3U/M3U8 playlist URL in Settings — or open a playlist link with JoyStream. Channels are cached for offline browsing.",
                icon = Icons.Rounded.LiveTv,
                actionLabel = "Open settings",
                onAction = onOpenSettings,
            )
            return@Column
        }

        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(JoySurfaceHigh, CircleShape),
            placeholder = { Text("Find a channel", color = JoyMuted) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = JoySurfaceHigh,
                unfocusedContainerColor = JoySurfaceHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )

        if (state.groups.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    GroupChip(
                        label = "All",
                        selected = selectedGroup == null,
                        onClick = { selectedGroup = null },
                    )
                }
                items(state.groups) { group ->
                    GroupChip(
                        label = group,
                        selected = selectedGroup == group,
                        onClick = { selectedGroup = group },
                    )
                }
            }
        }

        if (state.loading && state.channels.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text("Fetching playlist…", color = JoyMuted)
            }
        }

        state.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(channels, key = { it.key }) { channel ->
                ChannelRow(channel = channel, onClick = { playChannel(context, channel) })
            }
            if (channels.isEmpty() && !state.loading && state.channels.isNotEmpty()) {
                item {
                    EmptyState(
                        title = "No channels match",
                        message = "Try a different search term or category.",
                    )
                }
            }
        }
    }
}

private fun playChannel(context: android.content.Context, channel: Channel) {
    val isHls = channel.url.contains(".m3u8", ignoreCase = true) ||
        channel.url.contains("m3u", ignoreCase = true)
    startPlayer(
        context,
        PlaybackRequest(
            title = channel.name,
            subtitle = channel.group,
            url = channel.url,
            kind = if (isHls) StreamKind.HLS.name else StreamKind.PROGRESSIVE.name,
            isLive = true,
        ),
    )
}

@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else JoySurfaceHigh,
        shape = CircleShape,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun ChannelRow(channel: Channel, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(JoySurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.logoUrl != null) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = channel.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp),
                )
            } else {
                Icon(Icons.Rounded.LiveTv, contentDescription = null, tint = JoyMuted)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = channel.group,
                style = MaterialTheme.typography.labelSmall,
                color = JoyMuted,
                maxLines = 1,
            )
        }
        Icon(
            Icons.Rounded.LiveTv,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
    Spacer(Modifier.height(2.dp))
}

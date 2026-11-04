package com.joystream.app.ui.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.joystream.app.JoyStreamApp
import com.joystream.app.data.model.CatalogItem
import com.joystream.app.data.model.MediaType
import com.joystream.app.data.model.PlaybackRequest
import com.joystream.app.data.model.StreamKind
import com.joystream.app.ui.theme.JoyStreamTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Launches the full-screen player for a resolved stream. */
fun startPlayer(context: Context, request: PlaybackRequest) {
    val payload = Json.encodeToString(PlaybackRequest.serializer(), request)
    context.startActivity(
        Intent(context, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_PLAYBACK, payload)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@UnstableApi
class PlayerActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var request: PlaybackRequest? = null
    private var lastSavedPosition = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val json = intent.getStringExtra(EXTRA_PLAYBACK)
        val parsed = json?.let {
            runCatching { Json.decodeFromString(PlaybackRequest.serializer(), it) }.getOrNull()
        }
        if (parsed == null) {
            finish()
            return
        }
        request = parsed

        player = buildPlayer(parsed)

        setContent {
            JoyStreamTheme {
                PlayerScreen(
                    player = player,
                    request = parsed,
                    onBack = { finish() },
                    onRotate = {
                        requestedOrientation =
                            if (resources.configuration.orientation ==
                                android.content.res.Configuration.ORIENTATION_LANDSCAPE
                            ) {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            }
                    },
                )
            }
        }

        player.playWhenReady = true
        player.prepare()
        if (parsed.startPositionMs > 5_000) {
            player.seekTo(parsed.startPositionMs)
        }

        scope.launch {
            while (isActive) {
                delay(5_000)
                persistProgress()
            }
        }
    }

    private fun buildPlayer(request: PlaybackRequest): ExoPlayer {
        val headers = request.headers.toMutableMap()
        headers.putIfAbsent("User-Agent", com.joystream.app.core.Http.MOBILE_USER_AGENT)

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setUserAgent(com.joystream.app.core.Http.MOBILE_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSize(4096, request.maxHeight?.coerceAtLeast(144) ?: 2160)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoNonSeamlessAdaptiveness(true),
            )
        }

        val exoPlayer = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .build()

        val kind = runCatching { StreamKind.valueOf(request.kind) }.getOrDefault(StreamKind.PROGRESSIVE)
        val lowered = request.url.lowercase()
        val mediaItemBuilder = MediaItem.Builder().setUri(request.url)

        if (request.subtitles.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(
                request.subtitles.map { subtitle ->
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle.url))
                        .setMimeType(mimeTypeFor(subtitle.url))
                        .setLanguage(subtitle.language)
                        .setLabel(subtitle.name)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                },
            )
        }
        val mediaItem = mediaItemBuilder.build()

        val mediaSource = when {
            kind == StreamKind.DASH || lowered.contains(".mpd") ->
                DashMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)

            kind == StreamKind.HLS || lowered.contains(".m3u8") ->
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)

            else -> ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        return exoPlayer
    }

    private fun mimeTypeFor(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".srt") -> "application/x-subrip"
            path.endsWith(".vtt") -> "text/vtt"
            path.endsWith(".ssa") || path.endsWith(".ass") -> "text/x-ssa"
            else -> "application/x-subrip"
        }
    }

    private fun persistProgress() {
        val request = this.request ?: return
        if (request.isLive) return
        val app = application as? JoyStreamApp ?: return
        val position = player.currentPosition
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        if (position <= 0) return
        if (kotlin.math.abs(position - lastSavedPosition) < 3_000) return
        lastSavedPosition = position

        val idValue = request.idValue ?: return
        val provider = request.provider ?: return
        val catalogItem = CatalogItem(
            provider = provider,
            idValue = idValue,
            title = request.title,
            mediaType = if ((request.episode ?: 0) > 0) MediaType.SERIES else MediaType.MOVIE,
            posterUrl = request.posterUrl,
        )
        scope.launch(Dispatchers.IO) {
            app.container.libraryStore.recordPlayback(
                details = null,
                item = catalogItem,
                provider = provider,
                idValue = idValue,
                positionMs = position,
                durationMs = duration,
                season = request.season,
                episode = request.episode,
                episodeTitle = request.subtitle,
            )
        }
    }

    override fun onPause() {
        super.onPause()
        persistProgress()
        player.pause()
    }

    override fun onDestroy() {
        persistProgress()
        scope.cancel()
        player.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PLAYBACK = "playback_request"
    }
}

@UnstableApi
@Composable
private fun PlayerScreen(
    player: ExoPlayer,
    request: PlaybackRequest,
    onBack: () -> Unit,
    onRotate: () -> Unit,
) {
    val context = LocalContext.current
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val activity = context as? ComponentActivity
        if (activity != null) {
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val activity = context as? ComponentActivity
            if (activity != null) {
                WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 3_500
                    controllerAutoShow = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    setOnClickListener { controlsVisible = !controlsVisible }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (controlsVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent),
                        ),
                    )
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = request.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    request.subtitle?.let { subtitle ->
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (request.subtitles.isNotEmpty()) {
                    IconButton(onClick = { showSubtitleDialog = true }) {
                        Icon(Icons.Rounded.Subtitles, contentDescription = "Subtitles", tint = Color.White)
                    }
                }
                IconButton(onClick = onRotate) {
                    Icon(Icons.Rounded.ScreenRotation, contentDescription = "Rotate", tint = Color.White)
                }
            }
        }
    }

    if (showSubtitleDialog) {
        SubtitlePicker(
            request = request,
            player = player,
            onDismiss = { showSubtitleDialog = false },
        )
    }
}

@Composable
private fun SubtitlePicker(
    request: PlaybackRequest,
    player: ExoPlayer,
    onDismiss: () -> Unit,
) {
    val options = remember(request) { listOf("Auto") + request.subtitles.map { it.name } + "Off" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subtitles") },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val parameters = player.trackSelectionParameters
                                player.trackSelectionParameters = when (option) {
                                    "Off" -> parameters.buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                        .build()

                                    "Auto" -> parameters.buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                        .setPreferredTextLanguage(null)
                                        .build()

                                    else -> {
                                        val language = request.subtitles
                                            .firstOrNull { it.name == option }?.language
                                        parameters.buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                            .setPreferredTextLanguage(language)
                                            .build()
                                    }
                                }
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(option, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

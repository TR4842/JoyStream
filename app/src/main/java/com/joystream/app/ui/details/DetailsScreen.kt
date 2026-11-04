package com.joystream.app.ui.details

import android.widget.Toast
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.joystream.app.core.AppContainer
import com.joystream.app.data.download.DownloadManager
import com.joystream.app.data.download.buildDownloadItem
import com.joystream.app.data.library.LibraryStore
import com.joystream.app.data.model.CatalogItem
import com.joystream.app.data.model.Episode
import com.joystream.app.data.model.HistoryItem
import com.joystream.app.data.model.MediaDetails
import com.joystream.app.data.model.MediaId
import com.joystream.app.data.model.PlaybackRequest
import com.joystream.app.data.model.Season
import com.joystream.app.data.model.StreamRelease
import com.joystream.app.data.model.SubtitleSpec
import com.joystream.app.data.model.formatBytes
import com.joystream.app.data.providers.CatalogRepository
import com.joystream.app.data.settings.SettingsRepository
import com.joystream.app.ui.components.ErrorState
import com.joystream.app.ui.components.LoadingState
import com.joystream.app.ui.components.MetaChip
import com.joystream.app.ui.components.ProviderChip
import com.joystream.app.ui.components.QualityPill
import com.joystream.app.ui.components.RatingChip
import com.joystream.app.ui.player.startPlayer
import com.joystream.app.ui.theme.JoyGreen
import com.joystream.app.ui.theme.JoyMuted
import com.joystream.app.ui.theme.JoyPurple
import com.joystream.app.ui.theme.JoySurfaceHigh
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailsUiState(
    val loading: Boolean = true,
    val details: MediaDetails? = null,
    val error: String? = null,
    val favorite: Boolean = false,
    val selectedSeason: Int = 1,
    val selectedAudio: Int = 0,
    val showSheet: Boolean = false,
    val releasesLoading: Boolean = false,
    val releasesError: String? = null,
    val releases: List<StreamRelease> = emptyList(),
    val pendingEpisode: Episode? = null,
    val playback: PlaybackRequest? = null,
    val preparingPlayback: Boolean = false,
    val toast: String? = null,
    val history: List<HistoryItem> = emptyList(),
) {
    val season: Season?
        get() = details?.seasons?.firstOrNull { it.number == selectedSeason }
            ?: details?.seasons?.firstOrNull()
}

class DetailsViewModel(
    private val catalog: CatalogRepository,
    private val library: LibraryStore,
    private val settings: SettingsRepository,
    private val initialId: MediaId,
    initialSeason: Int?,
    private val initialEpisode: Int?,
    private val autoplay: Boolean,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailsUiState())
    val state: StateFlow<DetailsUiState> = _state.asStateFlow()

    private var currentId: MediaId = initialId

    init {
        viewModelScope.launch {
            library.snapshot.collect { snapshot ->
                _state.update {
                    it.copy(
                        history = snapshot.history,
                        favorite = snapshot.favorites.any { fav -> fav.key == currentId.key },
                    )
                }
            }
        }
        load(initialSeason, initialEpisode, autoplay)
    }

    fun load(season: Int? = null, episode: Int? = null, autoplayNow: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { catalog.details(currentId) }
                .onSuccess { details ->
                    val selected = details.seasons.firstOrNull { it.number == season }?.number
                        ?: details.seasons.firstOrNull()?.number
                        ?: 1
                    _state.update {
                        it.copy(
                            loading = false,
                            details = details,
                            selectedSeason = selected,
                            error = null,
                        )
                    }
                    if (autoplayNow) {
                        val targetEpisode = details.seasons
                            .firstOrNull { it.number == selected }
                            ?.episodes
                            ?.firstOrNull { it.number == episode }
                            ?: details.firstEpisode
                        if (details.hasEpisodes) {
                            targetEpisode?.let { requestQuality(it) }
                        } else {
                            requestQuality(null)
                        }
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(loading = false, error = error.message ?: "Could not load this title")
                    }
                }
        }
    }

    fun selectSeason(number: Int) {
        _state.update { it.copy(selectedSeason = number) }
    }

    fun selectAudio(index: Int) {
        val details = _state.value.details ?: return
        val track = details.audioTracks.getOrNull(index) ?: return
        if (index == _state.value.selectedAudio) return
        currentId = MediaId(details.id.provider, track.subjectId)
        _state.update { it.copy(selectedAudio = index) }
        load(_state.value.selectedSeason)
    }

    fun requestQuality(episode: Episode?) {
        _state.update {
            it.copy(
                showSheet = true,
                releasesLoading = true,
                releasesError = null,
                releases = emptyList(),
                pendingEpisode = episode,
            )
        }
        viewModelScope.launch {
            val season = episode?.season ?: 0
            val number = episode?.number ?: 0
            runCatching { catalog.releases(currentId, season, number) }
                .onSuccess { releases ->
                    _state.update {
                        it.copy(releasesLoading = false, releases = releases, releasesError = null)
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            releasesLoading = false,
                            releasesError = error.message ?: "No sources available",
                        )
                    }
                }
        }
    }

    fun closeSheet() = _state.update { it.copy(showSheet = false) }

    fun preparePlayback(release: StreamRelease) {
        val details = _state.value.details ?: return
        val episode = _state.value.pendingEpisode
        _state.update { it.copy(preparingPlayback = true) }
        viewModelScope.launch {
            val subtitles = runCatching { catalog.subtitles(currentId, release) }
                .getOrDefault(emptyList())
                .map { subtitle ->
                    SubtitleSpec(name = subtitle.name, url = subtitle.url, language = subtitle.language)
                }

            val key = episodeKey(episode)
            val resume = library.resumePosition(key)

            val request = PlaybackRequest(
                title = details.title,
                subtitle = episode?.let { ep -> details.title + " · " + ep.label },
                url = release.url,
                kind = release.kind.name,
                headers = release.headers,
                subtitles = subtitles,
                startPositionMs = resume,
                resumeKey = key,
                posterUrl = details.posterUrl,
                maxHeight = qualityLimit(release),
                provider = details.id.provider,
                idValue = details.id.value,
                season = episode?.season,
                episode = episode?.number,
            )
            _state.update { it.copy(preparingPlayback = false, playback = request, showSheet = false) }
        }
    }

    fun consumePlayback() = _state.update { it.copy(playback = null) }

    /** Queues a download and starts the foreground download service. */
    fun enqueueDownload(context: android.content.Context, release: StreamRelease) {
        val details = _state.value.details ?: return
        if (!release.canDownload) {
            _state.update {
                it.copy(toast = "This source is adaptive-only — pick a direct MP4 to download.")
            }
            return
        }
        val episode = _state.value.pendingEpisode
        val item = buildDownloadItem(
            release = release,
            details = details,
            item = CatalogItem(
                provider = details.id.provider,
                idValue = details.id.value,
                title = details.title,
                mediaType = details.mediaType,
                posterUrl = details.posterUrl,
            ),
            season = episode?.season,
            episode = episode?.number,
        )
        viewModelScope.launch {
            library.upsertDownload(item)
            DownloadManager.enqueue(context.applicationContext, item)
            _state.update { it.copy(toast = "Queued \"${item.title}\" for download") }
        }
    }

    fun toggleFavorite() {
        val details = _state.value.details ?: return
        viewModelScope.launch {
            library.toggleFavorite(
                CatalogItem(
                    provider = details.id.provider,
                    idValue = details.id.value,
                    title = details.title,
                    mediaType = details.mediaType,
                    posterUrl = details.posterUrl,
                    year = details.year,
                ),
            )
        }
    }

    fun clearToast() = _state.update { it.copy(toast = null) }

    fun episodeKey(episode: Episode?): String {
        val details = _state.value.details ?: return currentId.key
        val base = if (details.id == currentId) details.id.key else currentId.key
        return if (episode == null) base else "$base:s${episode.season}e${episode.number}"
    }

    fun historyFor(key: String): HistoryItem? = _state.value.history.firstOrNull { it.key == key }

    private fun qualityLimit(release: StreamRelease): Int? {
        val preferred = settings.current.preferredQuality.toIntOrNull()
        return preferred ?: release.resolutionHeight
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    container: AppContainer,
    mediaId: MediaId,
    season: Int?,
    episode: Int?,
    autoplay: Boolean,
    onBack: () -> Unit,
) {
    val viewModel: DetailsViewModel = viewModel(
        key = "details-${mediaId.key}",
        factory = viewModelFactory {
            initializer {
                DetailsViewModel(
                    catalog = container.catalog,
                    library = container.libraryStore,
                    settings = container.settings,
                    initialId = mediaId,
                    initialSeason = season,
                    initialEpisode = episode,
                    autoplay = autoplay,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var overviewExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(state.playback) {
        state.playback?.let { request ->
            startPlayer(context, request)
            viewModel.consumePlayback()
        }
    }

    LaunchedEffect(state.toast) {
        state.toast?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    val details = state.details

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            state.loading && details == null -> LoadingState(
                message = "Loading title…",
                modifier = Modifier.align(Alignment.Center),
            )

            state.error != null && details == null -> ErrorState(
                message = state.error ?: "Something went wrong",
                onRetry = { viewModel.load() },
                modifier = Modifier.align(Alignment.Center),
            )

            details != null -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                item {
                    DetailsHero(
                        details = details,
                        favorite = state.favorite,
                        onBack = onBack,
                        onPlay = {
                            val target = state.season?.episodes?.firstOrNull()
                            if (details.hasEpisodes) viewModel.requestQuality(target)
                            else viewModel.requestQuality(null)
                        },
                        onToggleFavorite = viewModel::toggleFavorite,
                    )
                }

                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        details.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                            Text(
                                text = overview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (overviewExpanded) Int.MAX_VALUE else 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(
                                onClick = { overviewExpanded = !overviewExpanded },
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(if (overviewExpanded) "Show less" else "Read more")
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            ProviderChip(details.providerLabel.ifBlank { details.id.provider })
                            details.genres.take(3).forEach { MetaChip(it) }
                            details.runtime?.let { MetaChip(it) }
                        }
                        if (details.cast.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "Cast: " + details.cast.take(5).joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = JoyMuted,
                            )
                        }
                        details.director?.let { director ->
                            Text(
                                text = "Director: $director",
                                style = MaterialTheme.typography.bodySmall,
                                color = JoyMuted,
                            )
                        }
                    }
                }

                if (details.audioTracks.size > 1) {
                    item {
                        Column(Modifier.padding(top = 16.dp)) {
                            Text(
                                text = "Audio",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Spacer(Modifier.height(6.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(details.audioTracks) { track ->
                                    val index = details.audioTracks.indexOf(track)
                                    MetaChip(
                                        text = track.label,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .clickable { viewModel.selectAudio(index) },
                                    )
                                }
                            }
                        }
                    }
                }

                val seasons = details.seasons
                if (seasons.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(top = 16.dp)) {
                            Text(
                                text = "Episodes",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(seasons) { seasonItem ->
                                    val selected = seasonItem.number == state.selectedSeason
                                    Surface(
                                        color = if (selected) JoyPurple.copy(alpha = 0.3f) else JoySurfaceHigh,
                                        shape = CircleShape,
                                        modifier = Modifier.clickable {
                                            viewModel.selectSeason(seasonItem.number)
                                        },
                                    ) {
                                        Text(
                                            text = "Season ${seasonItem.number}",
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(
                                                horizontal = 14.dp,
                                                vertical = 7.dp,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val currentSeason = state.season
                    if (currentSeason != null) {
                        items(currentSeason.episodes, key = { "ep-${it.season}-${it.number}" }) { ep ->
                            val resumeKey = viewModel.episodeKey(ep)
                            val history = viewModel.historyFor(resumeKey)
                            EpisodeRow(
                                episode = ep,
                                progress = history?.progress ?: 0f,
                                onPlay = { viewModel.requestQuality(ep) },
                                onDownload = { viewModel.requestQuality(ep) },
                            )
                        }
                    }
                }
            }
        }

        if (state.preparingPlayback) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = JoyPurple)
            }
        }
    }

    if (state.showSheet) {
        ModalBottomSheet(
            onDismissRequest = viewModel::closeSheet,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            QualitySheet(
                state = state,
                onSelect = { release -> viewModel.preparePlayback(release) },
                onDownload = { release ->
                    viewModel.enqueueDownload(context, release)
                    viewModel.closeSheet()
                },
                onRetry = { viewModel.requestQuality(state.pendingEpisode) },
            )
        }
    }
}

@Composable
private fun DetailsHero(
    details: MediaDetails,
    favorite: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(430.dp),
    ) {
        AsyncImage(
            model = details.backdropUrl ?: details.posterUrl,
            contentDescription = details.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.55f),
                        0.4f to Color.Black.copy(alpha = 0.25f),
                        0.75f to MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                        1f to MaterialTheme.colorScheme.background,
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (favorite) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = if (favorite) "Remove from My List" else "Add to My List",
                    tint = if (favorite) JoyPurple else Color.White,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
        ) {
            Text(
                text = details.title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                details.year?.let { QualityPill(it) }
                details.rating?.let { RatingChip(it) }
                if (details.hasEpisodes) {
                    QualityPill("${details.seasons.size} season(s)")
                } else {
                    QualityPill("Movie")
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onPlay,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (details.hasEpisodes) "Play episode" else "Play")
                }
                OutlinedButton(onClick = onToggleFavorite, shape = CircleShape) {
                    Icon(
                        imageVector = if (favorite) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (favorite) "Saved" else "My List")
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    progress: Float,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = JoySurfaceHigh, shape = RoundedCornerShape(10.dp)) {
            Text(
                text = episode.number.toString().padStart(2, '0'),
                style = MaterialTheme.typography.labelLarge,
                color = JoyMuted,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = episode.title?.takeIf { it.isNotBlank() } ?: "Episode ${episode.number}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress > 0f) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    color = JoyGreen,
                    trackColor = JoySurfaceHigh,
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
            }
        }
        IconButton(onClick = onDownload) {
            Icon(Icons.Rounded.Download, contentDescription = "Download", tint = JoyMuted)
        }
        IconButton(onClick = onPlay) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = Color.White)
        }
    }
}

@Composable
private fun QualitySheet(
    state: DetailsUiState,
    onSelect: (StreamRelease) -> Unit,
    onDownload: (StreamRelease) -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
    ) {
        Text(
            text = state.pendingEpisode?.let { "Choose quality · ${it.label}" } ?: "Choose quality",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Tap a source to stream, or the download icon to save it offline.",
            style = MaterialTheme.typography.bodySmall,
            color = JoyMuted,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))

        when {
            state.releasesLoading -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = JoyPurple,
                )
                Spacer(Modifier.width(10.dp))
                Text("Finding sources…", color = JoyMuted)
            }

            state.releasesError != null -> Column(Modifier.padding(20.dp)) {
                Text(
                    text = state.releasesError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRetry) { Text("Retry") }
            }

            else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(state.releases) { release ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(release) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = release.qualityLabel ?: release.kind.name.lowercase(),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = listOfNotNull(
                                    release.codec,
                                    release.sizeBytes?.let(::formatBytes),
                                    release.mirrorLabel,
                                ).joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = JoyMuted,
                            )
                        }
                        if (release.canDownload) {
                            IconButton(onClick = { onDownload(release) }) {
                                Icon(
                                    Icons.Rounded.Download,
                                    contentDescription = "Download",
                                    tint = JoyMuted,
                                )
                            }
                        } else {
                            QualityPill("ADAPTIVE")
                        }
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

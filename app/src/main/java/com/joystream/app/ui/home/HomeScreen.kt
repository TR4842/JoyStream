package com.joystream.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.joystream.app.data.library.LibraryStore
import com.joystream.app.data.model.CatalogItem
import com.joystream.app.data.model.FavoriteItem
import com.joystream.app.data.model.HistoryItem
import com.joystream.app.data.model.MediaId
import com.joystream.app.data.providers.CatalogRepository
import com.joystream.app.data.providers.HomeFeed
import com.joystream.app.data.providers.HomeRow
import com.joystream.app.ui.components.EmptyState
import com.joystream.app.ui.components.LoadingState
import com.joystream.app.ui.components.PosterCard
import com.joystream.app.ui.components.PosterRow
import com.joystream.app.ui.components.ProviderErrorBanner
import com.joystream.app.ui.components.QualityPill
import com.joystream.app.ui.components.RatingChip
import com.joystream.app.ui.components.SectionHeader
import com.joystream.app.ui.components.UpdateBanner
import com.joystream.app.ui.theme.JoyMuted
import com.joystream.app.ui.theme.JoyPurple
import com.joystream.app.ui.theme.JoySurfaceHigh
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val rows: List<HomeRow> = emptyList(),
    val continueWatching: List<HistoryItem> = emptyList(),
    val favorites: List<FavoriteItem> = emptyList(),
    val errors: List<String> = emptyList(),
    val enabledProviderCount: Int = 0,
)

class HomeViewModel(
    private val catalog: CatalogRepository,
    private val library: LibraryStore,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            library.snapshot.collect { snapshot ->
                _state.update {
                    it.copy(
                        continueWatching = snapshot.history.filter { entry ->
                            entry.progress > 0.01f || entry.localPath != null
                        },
                        favorites = snapshot.favorites,
                    )
                }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.rows.isEmpty(), refreshing = true) }
            val feed = runCatching { catalog.homeFeed() }.getOrElse { error ->
                HomeFeed(errors = listOf(error.message ?: "Could not reach any provider"))
            }
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    rows = feed.rows.ifEmpty { it.rows },
                    errors = feed.errors,
                    enabledProviderCount = catalog.enabled().size,
                )
            }
        }
    }
}

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenDetails: (MediaId, Int?, Int?) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val viewModel: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer { HomeViewModel(container.catalog, container.libraryStore) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val updateState by container.updateController.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val heroItems = state.rows.firstOrNull()?.items?.take(6).orEmpty()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            HomeTopBar(
                refreshing = state.refreshing,
                onSearch = onOpenSearch,
                onSettings = onOpenSettings,
                onRefresh = viewModel::refresh,
            )
        }

        item {
            UpdateBanner(
                state = updateState,
                onUpdate = {
                    if (updateState.downloadedFile != null) {
                        when (val result = container.updateController.install()) {
                            is com.joystream.app.data.update.InstallResult.NeedsPermission ->
                                runCatching {
                                    context.startActivity(
                                        container.updateController.installPermissionIntent(),
                                    )
                                }

                            is com.joystream.app.data.update.InstallResult.Failed ->
                                runCatching {
                                    context.startActivity(
                                        container.updateController.externalInstallerIntent(
                                            updateState.downloadedFile!!,
                                        ),
                                    )
                                }

                            else -> Unit
                        }
                    } else {
                        container.updateController.download()
                    }
                },
                onDismiss = { container.updateController.dismiss() },
            )
        }

        if (heroItems.isNotEmpty()) {
            item { HeroPager(items = heroItems, onOpenDetails = onOpenDetails) }
        }

        item { ProviderErrorBanner(state.errors) }

        if (state.continueWatching.isNotEmpty()) {
            item { SectionHeader("Continue watching", actionLabel = "Downloads") { onOpenDownloads() } }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.continueWatching, key = { it.key }) { entry ->
                        ContinueCard(
                            entry = entry,
                            onClick = {
                                onOpenDetails(
                                    MediaId(entry.provider, entry.idValue),
                                    entry.season,
                                    entry.episode,
                                )
                            },
                        )
                    }
                }
            }
        }

        if (state.favorites.isNotEmpty()) {
            item { SectionHeader("My List") }
            item {
                PosterRow(
                    items = state.favorites.map { favorite ->
                        CatalogItem(
                            provider = favorite.provider,
                            idValue = favorite.idValue,
                            title = favorite.title,
                            posterUrl = favorite.posterUrl,
                            year = favorite.year,
                        )
                    },
                    onItemClick = { onOpenDetails(it.id, null, null) },
                )
            }
        }

        if (state.loading) {
            item { LoadingState("Loading your catalog…") }
        } else if (state.rows.isEmpty()) {
            val enabled = state.enabledProviderCount
            val failure = state.errors.firstOrNull()
            item {
                EmptyState(
                    title = when {
                        enabled == 0 -> "No provider enabled"
                        failure != null -> "The catalog is unreachable"
                        else -> "Nothing to show yet"
                    },
                    message = when {
                        enabled == 0 ->
                            "Turn on a provider in Settings to start browsing."
                        failure != null ->
                            "$failure\n\nRun \"Test connection\" in Settings to see which mirrors answer. " +
                                "If none of them do, the provider moved its API and the next app update will " +
                                "carry the fix."
                        else -> "Pull down to refresh."
                    },
                )
            }
        }

        state.rows.forEach { row ->
            item(key = "header-${row.title}") {
                SectionHeader(row.title)
            }
            item(key = "row-${row.title}") {
                PosterRow(items = row.items, onItemClick = { onOpenDetails(it.id, null, null) })
            }
        }
    }
}

@Composable
private fun HomeTopBar(
    refreshing: Boolean,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clickable(onClick = onRefresh)) {
            Text(
                text = "JoyStream",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = if (refreshing) "Refreshing catalog…" else "Movies, series & live TV",
                style = MaterialTheme.typography.labelSmall,
                color = JoyMuted,
            )
        }
        IconButton(onClick = onSearch) {
            Icon(Icons.Rounded.Search, contentDescription = "Search")
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Rounded.Settings, contentDescription = "Settings")
        }
    }
}

@Composable
private fun HeroPager(
    items: List<CatalogItem>,
    onOpenDetails: (MediaId, Int?, Int?) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { items.size })

    LaunchedEffect(pagerState, items.size) {
        if (items.size <= 1) return@LaunchedEffect
        while (true) {
            delay(7_000)
            val next = (pagerState.currentPage + 1) % items.size
            runCatching { pagerState.animateScrollToPage(next) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        HorizontalPager(state = pagerState, pageSpacing = 12.dp) { page ->
            val item = items[page]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(JoySurfaceHigh)
                    .clickable { onOpenDetails(item.id, null, null) },
            ) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.45f to Color.Black.copy(alpha = 0.35f),
                                1f to Color.Black.copy(alpha = 0.92f),
                            ),
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(18.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.mediaType.name == "SERIES") QualityPill("SERIES")
                        item.rating?.let { RatingChip(it) }
                        item.year?.let { QualityPill(it) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onOpenDetails(item.id, null, null) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                            ),
                            shape = CircleShape,
                        ) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Play")
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(items.size) { index ->
                val active = index == pagerState.currentPage
                Box(
                    modifier = Modifier
                        .size(width = if (active) 18.dp else 6.dp, height = 6.dp)
                        .clip(CircleShape)
                        .background(if (active) JoyPurple else Color.White.copy(alpha = 0.35f)),
                )
            }
        }
    }
}

@Composable
private fun ContinueCard(entry: HistoryItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(MaterialTheme.shapes.medium)
                .background(JoySurfaceHigh),
        ) {
            AsyncImage(
                model = entry.posterUrl,
                contentDescription = entry.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.2f)),
            )
            LinearProgressIndicator(
                progress = { entry.progress },
                color = JoyPurple,
                trackColor = Color.Transparent,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOfNotNull(entry.episodeLabel, "Resume").joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = JoyMuted,
            maxLines = 1,
        )
    }
}

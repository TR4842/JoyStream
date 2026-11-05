package com.joystream.app.ui.search

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.joystream.app.core.AppContainer
import com.joystream.app.data.library.LibraryStore
import com.joystream.app.data.model.CatalogItem
import com.joystream.app.data.model.MediaId
import com.joystream.app.data.providers.CatalogRepository
import com.joystream.app.ui.components.EmptyState
import com.joystream.app.ui.components.PosterCard
import com.joystream.app.ui.components.ProviderErrorBanner
import com.joystream.app.ui.theme.JoyMuted
import com.joystream.app.ui.theme.JoyPurple
import com.joystream.app.ui.theme.JoySurfaceHigh
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val searched: Boolean = false,
    val items: List<CatalogItem> = emptyList(),
    val errors: List<String> = emptyList(),
    val recent: List<String> = emptyList(),
)

class SearchViewModel(
    private val catalog: CatalogRepository,
    private val library: LibraryStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            library.snapshot.collect { snapshot ->
                _state.update { it.copy(recent = snapshot.searchHistory) }
            }
        }
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
        searchJob?.cancel()
        if (value.trim().length < 2) {
            _state.update { it.copy(items = emptyList(), searched = false, loading = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            runSearch(value)
        }
    }

    fun submit() {
        val query = _state.value.query.trim()
        if (query.length < 2) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(query) }
    }

    fun useRecent(term: String) {
        _state.update { it.copy(query = term) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(term) }
    }

    fun clearHistory() = viewModelScope.launch { library.clearSearchHistory() }

    private suspend fun runSearch(query: String) {
        _state.update { it.copy(loading = true, errors = emptyList()) }
        val result = runCatching { catalog.search(query) }.getOrElse { error ->
            _state.update {
                it.copy(loading = false, searched = true, errors = listOf(error.message ?: "Search failed"))
            }
            return
        }
        library.recordSearch(query)
        _state.update {
            it.copy(
                loading = false,
                searched = true,
                items = result.items,
                errors = result.errors,
            )
        }
    }
}

@Composable
fun SearchScreen(
    container: AppContainer,
    onOpenDetails: (MediaId) -> Unit,
) {
    val viewModel: SearchViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SearchViewModel(container.catalog, container.libraryStore) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Text(
            text = "Search",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )

        TextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(JoySurfaceHigh, CircleShape),
            placeholder = { Text("Movies, series, anime, dramas…", color = JoyMuted) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = JoySurfaceHigh,
                unfocusedContainerColor = JoySurfaceHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboard?.hide()
                    viewModel.submit()
                },
            ),
        )

        if (state.recent.isNotEmpty() && state.query.isBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Recent",
                    style = MaterialTheme.typography.labelLarge,
                    color = JoyMuted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::clearHistory) { Text("Clear") }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.recent) { term ->
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(JoySurfaceHigh)
                            .clickable { viewModel.useRecent(term) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(text = term, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        if (state.loading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = JoyPurple,
                )
                Spacer(Modifier.width(10.dp))
                Text("Searching every provider…", color = JoyMuted)
            }
        }

        ProviderErrorBanner(state.errors)

        if (!state.loading && state.searched && state.items.isEmpty() && state.errors.isEmpty()) {
            EmptyState(
                title = "No matches",
                message = "Nothing found for \"${state.query}\". Try a shorter title or a different spelling.",
            )
        } else if (!state.searched && state.query.isBlank()) {
            EmptyState(
                title = "Find something to watch",
                message = "Search across MovieBox and Dramachi at once — one query, every provider.",
                icon = Icons.Rounded.Search,
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.items, key = { it.key + it.title }) { item ->
                PosterCard(item = item, onClick = { onOpenDetails(item.id) })
            }
        }
    }
}

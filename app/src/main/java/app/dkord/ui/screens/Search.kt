package app.dkord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dkord.R
import app.dkord.core.BrowseItem
import app.dkord.core.ItemKind
import app.dkord.core.Track
import app.dkord.data.yt.MusicRepository
import app.dkord.data.yt.SearchCursor
import app.dkord.data.yt.SearchFilter
import app.dkord.ui.common.ErrorBox
import app.dkord.ui.common.Load
import app.dkord.ui.common.Loading
import app.dkord.ui.common.BrowseRow
import app.dkord.ui.common.koinVm
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

class SearchViewModel(private val repo: MusicRepository) : ViewModel() {
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(SearchFilter.SONGS)
    private val _results = MutableStateFlow<Load<List<BrowseItem>>?>(null)
    val results: StateFlow<Load<List<BrowseItem>>?> = _results
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions
    private val _more = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _more

    private var cursor: SearchCursor? = null
    private var suggestJob: Job? = null
    private var searchJob: Job? = null
    private var moreJob: Job? = null

    /** Debounced live suggestions while typing. */
    fun typed(text: String) {
        query.value = text
        suggestJob?.cancel()
        if (text.isBlank()) { _suggestions.value = emptyList(); return }
        suggestJob = viewModelScope.launch {
            delay(250)
            _suggestions.value = runCatching { repo.suggestions(text) }.getOrDefault(emptyList())
        }
    }

    fun setFilter(f: SearchFilter) { filter.value = f; if (_results.value != null) search(query.value) }

    fun search(text: String = query.value) {
        if (text.isBlank()) return
        query.value = text
        suggestJob?.cancel(); _suggestions.value = emptyList()
        searchJob?.cancel(); moreJob?.cancel(); _more.value = false
        _results.value = Load.Loading
        searchJob = viewModelScope.launch {
            _results.value = runCatching { repo.search(text, filter.value) }.fold(
                { cursor = it.next; Load.Ok(it.items) },
                { if (it is kotlinx.coroutines.CancellationException) throw it; cursor = null; Load.Failed(it) },
            )
        }
    }

    fun loadMore() {
        val c = cursor ?: return
        val cur = (_results.value as? Load.Ok)?.value ?: return
        if (_more.value) return
        _more.value = true
        moreJob = viewModelScope.launch {
            runCatching { repo.searchMore(c) }
                .onSuccess { cursor = it.next; _results.value = Load.Ok((cur + it.items).distinctBy { i -> i.kind to i.id }) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; cursor = null }
            _more.value = false
        }
    }

    fun clear() { searchJob?.cancel(); moreJob?.cancel(); suggestJob?.cancel(); _more.value = false; query.value = ""; _results.value = null; _suggestions.value = emptyList(); cursor = null }
}

@Composable
fun SearchScreen(onItem: (BrowseItem) -> Unit, onPlay: (List<Track>, Int) -> Unit, onMenu: (Track) -> Unit, modifier: Modifier = Modifier) {
    val repo: MusicRepository = koinInject()
    val vm = koinVm { SearchViewModel(repo) }
    val query by vm.query.collectAsState()
    val filter by vm.filter.collectAsState()
    val results by vm.results.collectAsState()
    val suggestions by vm.suggestions.collectAsState()

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            query, vm::typed,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(vm::clear) { Icon(Icons.Default.Clear, stringResource(R.string.clear)) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search() }),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SearchFilter.entries) { f ->
                FilterChip(filter == f, { vm.setFilter(f) }, { Text(stringResource(filterLabel(f))) })
            }
        }
        when {
            suggestions.isNotEmpty() && query.isNotBlank() -> LazyColumn {
                items(suggestions) { s ->
                    ListItem(
                        modifier = Modifier.clickable { vm.search(s) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Icon(Icons.Default.Search, null) },
                        headlineContent = { Text(s) },
                    )
                }
            }
            results == null -> Unit
            else -> when (val r = results) {
                Load.Loading -> Loading()
                is Load.Failed -> ErrorBox(r.error, { vm.search() })
                is Load.Ok -> {
                    val state = rememberLazyListState()
                    LaunchedEffect(state, r.value.size) {
                        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                            .collect { if (it >= r.value.size - 4) vm.loadMore() }
                    }
                    val songs = remember(r.value) { r.value.mapNotNull { it.track } }
                    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                        items(r.value, key = { it.kind.name + it.id }) { item ->
                            BrowseRow(
                                item,
                                onClick = {
                                    if (item.kind == ItemKind.SONG) onPlay(songs, songs.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
                                    else onItem(item)
                                },
                                onMenu = item.track?.let { t -> { onMenu(t) } },
                            )
                        }
                        if (r.value.isEmpty()) item("none") { Text(stringResource(R.string.no_results), Modifier.padding(32.dp)) }
                    }
                }
                null -> Unit
            }
        }
    }
}

private fun filterLabel(f: SearchFilter) = when (f) {
    SearchFilter.SONGS -> R.string.songs
    SearchFilter.ALBUMS -> R.string.albums
    SearchFilter.ARTISTS -> R.string.artists
    SearchFilter.PLAYLISTS -> R.string.playlists
}

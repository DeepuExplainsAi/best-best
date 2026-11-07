package app.dkord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dkord.R
import app.dkord.core.BrowseItem
import app.dkord.core.ItemKind
import app.dkord.core.Track
import app.dkord.data.search.MAX_PINNED_SEARCHES
import app.dkord.data.search.MusicSearchSource
import app.dkord.data.search.SavedSearch
import app.dkord.data.search.SearchHistoryNotice
import app.dkord.data.search.SearchHistoryRepository
import app.dkord.data.search.SearchHistoryState
import app.dkord.data.search.limitSearchInput
import app.dkord.data.search.matchingSavedSearches
import app.dkord.data.search.searchIdentity
import app.dkord.data.yt.MusicRepository
import app.dkord.data.yt.SearchFilter
import app.dkord.ui.common.BrowseRow
import app.dkord.ui.common.ErrorBox
import app.dkord.ui.common.Load
import app.dkord.ui.common.Loading
import app.dkord.ui.common.koinVm
import app.dkord.ui.common.softClickable
import app.dkord.ui.search.SearchController
import kotlinx.coroutines.flow.collect
import org.koin.compose.koinInject

class SearchViewModel(repo: MusicRepository, history: SearchHistoryRepository) : ViewModel() {
    private val controller = SearchController(MusicSearchSource(repo), history, viewModelScope)
    val query = controller.query
    val filter = controller.filter
    val results = controller.results
    val suggestions = controller.suggestions
    val hasMore = controller.hasMore
    val loadingMore = controller.loadingMore
    val moreFailed = controller.moreFailed
    fun typed(text: String) = controller.typed(text)
    fun setFilter(value: SearchFilter) = controller.setFilter(value)
    fun search(text: String = query.value) = controller.search(text)
    fun repeat(entry: SavedSearch) = controller.repeat(entry)
    fun edit(entry: SavedSearch) = controller.edit(entry)
    fun loadMore(retry: Boolean = false) = controller.loadMore(retry)
    fun clear() = controller.clear()
}

@Composable
fun SearchScreen(
    onItem: (BrowseItem) -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onMenu: (Track) -> Unit,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit = {},
) {
    val repo: MusicRepository = koinInject()
    val saved: SearchHistoryRepository = koinInject()
    val vm = koinVm { SearchViewModel(repo, saved) }
    val query by vm.query.collectAsState()
    val filter by vm.filter.collectAsState()
    val results by vm.results.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val history by saved.state.collectAsState()
    val hasMore by vm.hasMore.collectAsState()
    val loadingMore by vm.loadingMore.collectAsState()
    val moreFailed by vm.moreFailed.collectAsState()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(saved, context) {
        saved.notices.collect { notice ->
            onMessage(when (notice) {
                SearchHistoryNotice.WRITE_FAILED -> context.getString(R.string.search_history_write_failed)
                SearchHistoryNotice.PIN_LIMIT -> context.getString(R.string.search_pin_limit, MAX_PINNED_SEARCHES)
            })
        }
    }
    fun submitted(action: () -> Unit) {
        if (query.isBlank()) return
        keyboard?.hide(); focus.clearFocus()
        action()
        field = TextFieldValue(vm.query.value, TextRange(vm.query.value.length))
    }
    fun repeat(entry: SavedSearch) {
        keyboard?.hide(); focus.clearFocus()
        field = TextFieldValue(entry.query, TextRange(entry.query.length))
        vm.repeat(entry)
    }
    fun edit(entry: SavedSearch) {
        field = TextFieldValue(entry.query, TextRange(entry.query.length))
        vm.edit(entry)
        focusRequester.requestFocus(); keyboard?.show()
    }
    val localSuggestions = remember(history.entries, query, filter) { matchingSavedSearches(history.entries, query, filter) }
    val remoteSuggestions = remember(suggestions, localSuggestions) {
        val local = localSuggestions.map { searchIdentity(it.query) }.toSet()
        suggestions.filter { searchIdentity(it) !in local }
    }

    Column(modifier.fillMaxSize().imePadding()) {
        OutlinedTextField(
            field, { next ->
                val limited = limitSearchInput(next.text)
                field = if (limited == next.text) next else TextFieldValue(limited, TextRange(limited.length))
                vm.typed(limited)
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focusRequester),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { IconButton({ submitted { vm.search() } }, enabled = query.isNotBlank()) {
                Icon(Icons.Default.Search, stringResource(R.string.search_submit))
            } },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ field = TextFieldValue(); vm.clear() }) {
                Icon(Icons.Default.Clear, stringResource(R.string.clear))
            } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitted { vm.search() } }),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SearchFilter.entries, key = { it.name }) { value ->
                FilterChip(filter == value, {
                    vm.setFilter(value)
                    if (field.text != vm.query.value) field = TextFieldValue(vm.query.value, TextRange(vm.query.value.length))
                }, { Text(stringResource(filterLabel(value))) })
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                query.isBlank() -> SavedSearches(history, { confirmClear = true }, saved::reload, ::repeat, ::edit, saved::togglePinned, saved::remove)
                results == null -> LazyColumn(Modifier.fillMaxSize()) {
                    items(localSuggestions, key = { "local:${it.key}" }) { entry ->
                        SavedSearchRow(entry, { repeat(entry) }, { edit(entry) }, { saved.togglePinned(entry) }, { saved.remove(entry) })
                    }
                    items(remoteSuggestions, key = { "suggestion:${searchIdentity(it)}" }) { suggestion ->
                        ListItem(
                            modifier = Modifier.softClickable({ submitted { vm.search(suggestion) } }),
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = { Icon(Icons.Default.Search, null) },
                            headlineContent = { Text(suggestion, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { IconButton({ edit(SavedSearch(suggestion, filter)) }) {
                                Icon(Icons.Default.Edit, stringResource(R.string.search_edit_query))
                            } },
                        )
                    }
                }
                else -> when (val result = results) {
                    Load.Loading -> Loading()
                    is Load.Failed -> ErrorBox(result.error, { vm.search() })
                    is Load.Ok -> {
                        val listState = rememberLazyListState()
                        LaunchedEffect(listState, result.value.size) {
                            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                                .collect { if (it >= result.value.size - 4) vm.loadMore() }
                        }
                        val songs = remember(result.value) { result.value.mapNotNull { it.track } }
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(result.value, key = { "${it.kind.name}:${it.id}" }) { item ->
                                BrowseRow(item, onClick = {
                                    if (item.kind == ItemKind.SONG) onPlay(songs, songs.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
                                    else onItem(item)
                                }, onMenu = item.track?.let { track -> { onMenu(track) } })
                            }
                            if (result.value.isEmpty()) item("none") { Text(stringResource(R.string.no_results), Modifier.padding(32.dp)) }
                            item("pagination") {
                                Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (loadingMore) CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp))
                                    if (hasMore && !loadingMore && !moreFailed) TextButton({ vm.loadMore() }) { Text(stringResource(R.string.search_load_more)) }
                                    if (moreFailed) {
                                        Text(stringResource(R.string.search_more_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        TextButton({ vm.loadMore(retry = true) }) { Text(stringResource(R.string.retry)) }
                                    }
                                }
                            }
                        }
                    }
                    null -> Unit
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.search_clear_title)) },
        text = { Text(stringResource(R.string.search_clear_message)) },
        confirmButton = { TextButton({ confirmClear = false; saved.clearAll() }) { Text(stringResource(R.string.search_clear_all)) } },
        dismissButton = { TextButton({ confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SavedSearches(
    history: SearchHistoryState,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    onRepeat: (SavedSearch) -> Unit,
    onEdit: (SavedSearch) -> Unit,
    onPin: (SavedSearch) -> Unit,
    onRemove: (SavedSearch) -> Unit,
) {
    val entries = remember(history.entries) { history.entries.sortedByDescending { it.pinned } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item("header") {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.search_history_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (entries.isNotEmpty() || history.loadFailed) TextButton(onClear) { Text(stringResource(R.string.search_clear_all)) }
            }
        }
        if (history.loading) item("loading") {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
        }
        if (history.loadFailed) item("error") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.search_history_load_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
        if (!history.loading && !history.loadFailed && entries.isEmpty()) item("empty") {
            Text(stringResource(R.string.search_history_empty), Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(entries, key = { it.key }) { entry ->
            SavedSearchRow(entry, { onRepeat(entry) }, { onEdit(entry) }, { onPin(entry) }, { onRemove(entry) })
        }
    }
}

@Composable
private fun SavedSearchRow(entry: SavedSearch, onRepeat: () -> Unit, onEdit: () -> Unit, onPin: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.softClickable(onRepeat, onLongClick = { menu = true }),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(if (entry.pinned) Icons.Default.PushPin else Icons.Default.History, null,
            tint = if (entry.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(entry.query, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(if (entry.pinned) stringResource(R.string.search_filter_pinned, stringResource(filterLabel(entry.filter)))
            else stringResource(filterLabel(entry.filter)), style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Row {
                IconButton(onEdit) { Icon(Icons.Default.Edit, stringResource(R.string.search_edit_query)) }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(if (entry.pinned) R.string.search_unpin else R.string.search_pin)) },
                            leadingIcon = { Icon(Icons.Default.PushPin, null) }, onClick = { menu = false; onPin() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.search_remove)) },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = { menu = false; onRemove() })
                    }
                }
            }
        },
    )
}

private fun filterLabel(filter: SearchFilter) = when (filter) {
    SearchFilter.SONGS -> R.string.songs
    SearchFilter.ALBUMS -> R.string.albums
    SearchFilter.ARTISTS -> R.string.artists
    SearchFilter.PLAYLISTS -> R.string.playlists
}

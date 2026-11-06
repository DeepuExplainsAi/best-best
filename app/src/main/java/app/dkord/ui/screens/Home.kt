package app.dkord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dkord.R
import app.dkord.core.BrowseItem
import app.dkord.core.Chip
import app.dkord.core.Page
import app.dkord.core.Shelf
import app.dkord.data.yt.MusicRepository
import app.dkord.ui.common.ErrorBox
import app.dkord.ui.common.Load
import app.dkord.ui.common.Loading
import app.dkord.ui.common.ShelfRow
import app.dkord.ui.common.koinVm
import app.dkord.data.yt.SearchFilter
import app.dkord.core.ItemKind
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

class HomeViewModel(private val repo: MusicRepository) : ViewModel() {
    private val _page = MutableStateFlow<Load<Page>>(Load.Loading)
    val page: StateFlow<Load<Page>> = _page
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    private val _chip = MutableStateFlow<Chip?>(null)
    val chip: StateFlow<Chip?> = _chip

    private val _extra = MutableStateFlow<List<Shelf>>(emptyList())
    /** Curated shelves (search based), filled in progressively so the page is scrollable even for guests. */
    val extra: StateFlow<List<Shelf>> = _extra

    init { load(); loadExtra() }

    private fun loadExtra() {
        viewModelScope.launch {
            val sections = CURATED.map { (title, query) ->
                async { runCatching { Shelf(title, repo.search(query, SearchFilter.SONGS).items.take(15)) }.getOrNull() }
            }
            val out = mutableListOf<Shelf>()
            // Keep the declared order, show each shelf as soon as everything before it is ready.
            for (d in sections) { d.await()?.takeIf { it.items.isNotEmpty() }?.let { out += it; _extra.value = out.toList() } }
        }
    }

    fun select(c: Chip?) { _chip.value = if (_chip.value == c) null else c; load() }

    fun load(refresh: Boolean = false) {
        if (refresh) _refreshing.value = true else _page.value = Load.Loading
        viewModelScope.launch {
            _page.value = runCatching { repo.home(_chip.value) }.fold({ Load.Ok(it) }, { Load.Failed(it) })
            _refreshing.value = false
        }
    }
}

private val CURATED = listOf(
    "Bollywood Hits" to "latest bollywood hit songs",
    "Trending Punjabi" to "trending punjabi songs",
    "Romantic Hindi" to "romantic hindi songs",
    "Arijit Singh" to "arijit singh best songs",
    "Lo-fi & Chill" to "hindi lofi chill songs",
    "Workout Energy" to "gym workout songs",
    "Devotional" to "bhajan devotional songs",
    "Old Is Gold" to "old hindi classic songs kishore rafi",
    "English Pop" to "top english pop songs",
    "Hip-Hop & Rap" to "indian hip hop rap songs",
    "Sad Songs" to "hindi sad songs",
    "Party Mix" to "party dance songs hindi",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onItem: (BrowseItem) -> Unit, modifier: Modifier = Modifier) {
    val repo: MusicRepository = koinInject()
    val vm = koinVm { HomeViewModel(repo) }
    val page by vm.page.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val selected by vm.chip.collectAsState()
    val extra by vm.extra.collectAsState()
    val library: app.dkord.data.library.LibraryRepository = koinInject()
    val recent by remember { library.recent(15) }.collectAsState(emptyList())
    val recentShelf = recent.takeIf { it.isNotEmpty() }?.let { l ->
        Shelf(stringResource(R.string.recently_played), l.map { BrowseItem(ItemKind.SONG, it.id, it.title, it.artist, it.thumbnail, track = it) })
    }

    PullToRefreshBox(refreshing, { vm.load(refresh = true) }, modifier.fillMaxSize()) {
        when (val p = page) {
            Load.Loading -> Loading()
            is Load.Failed -> if (extra.isEmpty()) ErrorBox(p.error, { vm.load() }) else LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (recentShelf != null) item("recent") { ShelfRow(recentShelf, onItem) }
                items(extra) { ShelfRow(it, onItem) }
            }
            is Load.Ok -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (p.value.chips.isNotEmpty()) item("chips") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(p.value.chips) { c -> FilterChip(selected == c, { vm.select(c) }, { Text(c.title) }) }
                    }
                }
                if (recentShelf != null && selected == null) item("recent") { ShelfRow(recentShelf, onItem) }
                items(p.value.shelves) { ShelfRow(it, onItem) }
                if (selected == null) items(extra) { ShelfRow(it, onItem) }
                if (p.value.shelves.isEmpty() && extra.isEmpty()) item("empty") { Text(stringResource(R.string.nothing_here), Modifier.padding(32.dp)) }
            }
        }
    }
}

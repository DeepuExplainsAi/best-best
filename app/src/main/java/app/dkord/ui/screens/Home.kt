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
import app.dkord.data.auth.AuthStore
import app.dkord.data.yt.MusicRepository
import app.dkord.ui.common.ErrorBox
import app.dkord.ui.common.Load
import app.dkord.ui.common.Loading
import app.dkord.ui.common.ShelfRow
import app.dkord.ui.common.koinVm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

class HomeViewModel(private val repo: MusicRepository, private val auth: AuthStore) : ViewModel() {
    private val _page = MutableStateFlow<Load<Page>>(Load.Loading)
    val page: StateFlow<Load<Page>> = _page
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    private val _chip = MutableStateFlow<Chip?>(null)
    val chip: StateFlow<Chip?> = _chip
    private var wasLoggedIn = auth.loggedIn.value

    init { load() }

    fun select(c: Chip?) { _chip.value = if (_chip.value == c) null else c; load() }

    /** Reloads when the signed-in state changed since the last load (personalised home). */
    fun reloadIfAccountChanged() {
        if (auth.loggedIn.value != wasLoggedIn) { _chip.value = null; load() }
    }

    fun load(refresh: Boolean = false) {
        wasLoggedIn = auth.loggedIn.value
        if (refresh) _refreshing.value = true else _page.value = Load.Loading
        viewModelScope.launch {
            _page.value = runCatching { repo.home(_chip.value) }.fold({ Load.Ok(it) }, { Load.Failed(it) })
            _refreshing.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onItem: (BrowseItem) -> Unit, modifier: Modifier = Modifier) {
    val repo: MusicRepository = koinInject()
    val auth: AuthStore = koinInject()
    val vm = koinVm { HomeViewModel(repo, auth) }
    val page by vm.page.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val selected by vm.chip.collectAsState()
    val loggedIn by auth.loggedIn.collectAsState()
    LaunchedEffect(loggedIn) { vm.reloadIfAccountChanged() }

    PullToRefreshBox(refreshing, { vm.load(refresh = true) }, modifier.fillMaxSize()) {
        when (val p = page) {
            Load.Loading -> Loading()
            is Load.Failed -> ErrorBox(p.error, { vm.load() })
            is Load.Ok -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (p.value.chips.isNotEmpty()) item("chips") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(p.value.chips) { c -> FilterChip(selected == c, { vm.select(c) }, { Text(c.title) }) }
                    }
                }
                items(p.value.shelves) { ShelfRow(it, onItem) }
                if (p.value.shelves.isEmpty()) item("empty") { Text(stringResource(R.string.nothing_here), Modifier.padding(32.dp)) }
            }
        }
    }
}

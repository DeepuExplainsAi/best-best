package app.dkord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dkord.R
import app.dkord.core.BrowseItem
import app.dkord.core.ItemKind
import app.dkord.core.Page
import app.dkord.core.Track
import app.dkord.data.yt.MusicRepository
import app.dkord.ui.common.Actions
import app.dkord.ui.common.Cover
import app.dkord.ui.common.ErrorBox
import app.dkord.ui.common.Load
import app.dkord.ui.common.Loading
import app.dkord.ui.common.SectionTitle
import app.dkord.ui.common.ShelfRow
import app.dkord.ui.common.TrackRow
import app.dkord.ui.common.koinVm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Album, artist, playlist or mood page: one remote [Page] with a header, tracks and shelves. */
class DetailViewModel(private val repo: MusicRepository, private val item: BrowseItem) : ViewModel() {
    private val _page = MutableStateFlow<Load<Page>>(Load.Loading)
    val page: StateFlow<Load<Page>> = _page
    private val _more = MutableStateFlow(false)
    private var token: String? = null

    init { load() }

    fun load() {
        _page.value = Load.Loading
        viewModelScope.launch {
            _page.value = runCatching {
                when (item.kind) {
                    ItemKind.ALBUM -> repo.album(item.id)
                    ItemKind.ARTIST -> repo.artist(item.id)
                    ItemKind.MOOD -> repo.moodPage(item)
                    else -> repo.playlist(item.id)
                }
            }.fold({ token = it.continuation; Load.Ok(it) }, { Load.Failed(it) })
        }
    }

    fun loadMore() {
        val t = token ?: return
        val cur = (_page.value as? Load.Ok)?.value ?: return
        if (_more.value) return
        _more.value = true
        viewModelScope.launch {
            runCatching { repo.more(t) }
                .onSuccess { token = it.continuation; _page.value = Load.Ok(cur.copy(tracks = (cur.tracks + it.tracks).distinctBy { x -> x.id })) }
                .onFailure { token = null }
            _more.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    item: BrowseItem,
    onBack: () -> Unit,
    onItem: (BrowseItem) -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onMenu: (Track) -> Unit,
    onPickPlaylist: (List<Track>) -> Unit,
) {
    val repo: MusicRepository = koinInject()
    val actions: Actions = koinInject()
    val vm = koinVm(key = "${item.kind.name}|${item.id}|${item.params.orEmpty()}") { DetailViewModel(repo, item) }
    val page by vm.page.collectAsState()
    val playing by actions.player.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.title, maxLines = 1) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when (val p = page) {
                Load.Loading -> Loading()
                is Load.Failed -> ErrorBox(p.error, vm::load)
                is Load.Ok -> {
                    val tracks = p.value.tracks
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        item("header") {
                            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Cover(p.value.thumbnail ?: item.thumbnail, 200.dp, round = item.kind == ItemKind.ARTIST, radius = 8.dp)
                                Text(p.value.title.ifBlank { item.title }, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                                if (p.value.subtitle.isNotBlank()) Text(p.value.subtitle, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                if (tracks.isNotEmpty()) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button({ actions.player.setShuffle(false); onPlay(tracks, 0) }) { Icon(Icons.Default.PlayArrow, null); Text(stringResource(R.string.play)) }
                                    OutlinedButton({ actions.player.setShuffle(true); onPlay(tracks.shuffled(), 0) }) { Icon(Icons.Default.Shuffle, null); Text(stringResource(R.string.shuffle)) }
                                    OutlinedButton({ actions.download(tracks) }) { Icon(Icons.Default.Download, stringResource(R.string.download)) }
                                }
                            }
                        }
                        itemsIndexed(tracks, key = { i, t -> "$i${t.id}" }) { i, t ->
                            TrackRow(t, playing = playing.current?.id == t.id, onClick = { onPlay(tracks, i) }, onMenu = { onMenu(t) })
                            if (i >= tracks.size - 4) LaunchedEffect(tracks.size) { vm.loadMore() }
                        }
                        items(p.value.shelves) { ShelfRow(it, onItem) }
                        if (tracks.isEmpty() && p.value.shelves.isEmpty()) item("empty") { SectionTitle(stringResource(R.string.nothing_here)) }
                    }
                }
            }
        }
    }
}

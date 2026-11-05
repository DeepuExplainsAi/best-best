package app.dkord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dkord.R
import app.dkord.core.Track
import app.dkord.ui.common.Actions
import app.dkord.ui.common.ConfirmDialog
import app.dkord.ui.common.EmptyState
import app.dkord.ui.common.TextInputDialog
import app.dkord.ui.common.TrackRow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Shared body for local track lists: liked, recent and user playlists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackListScaffold(
    title: String,
    tracks: List<Track>,
    onBack: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onMenu: (Track) -> Unit,
    emptyText: String,
    actions: @Composable () -> Unit = {},
    rowExtra: (@Composable (index: Int, track: Track) -> Unit)? = null,
) {
    val a: Actions = koinInject()
    val now by a.player.state.collectAsState()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = { actions() },
        )
    }) { pad ->
        if (tracks.isEmpty()) { EmptyState(emptyText, Modifier.padding(pad)); return@Scaffold }
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item("bar") {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ a.player.setShuffle(false); onPlay(tracks, 0) }) { Icon(Icons.Default.PlayArrow, null); Text(stringResource(R.string.play)) }
                    OutlinedButton({ a.player.setShuffle(true); onPlay(tracks.shuffled(), 0) }) { Icon(Icons.Default.Shuffle, null); Text(stringResource(R.string.shuffle)) }
                    OutlinedButton({ a.download(tracks) }) { Icon(Icons.Default.Download, stringResource(R.string.download)) }
                }
            }
            itemsIndexed(tracks, key = { i, t -> "$i${t.id}" }) { i, t ->
                TrackRow(t, playing = now.current?.id == t.id, onClick = { onPlay(tracks, i) }, onMenu = { onMenu(t) },
                    trailing = rowExtra?.let { extra -> { extra(i, t) } })
            }
        }
    }
}

@Composable
fun LikedScreen(onBack: () -> Unit, onPlay: (List<Track>, Int) -> Unit, onMenu: (Track) -> Unit) {
    val a: Actions = koinInject()
    val tracks by remember { a.library.liked() }.collectAsState(emptyList())
    TrackListScaffold(stringResource(R.string.liked_songs), tracks, onBack, onPlay, onMenu, stringResource(R.string.no_liked))
}

@Composable
fun RecentScreen(onBack: () -> Unit, onPlay: (List<Track>, Int) -> Unit, onMenu: (Track) -> Unit) {
    val a: Actions = koinInject()
    val tracks by remember { a.library.recent() }.collectAsState(emptyList())
    TrackListScaffold(stringResource(R.string.recently_played), tracks, onBack, onPlay, onMenu, stringResource(R.string.no_recent))
}

@Composable
fun LocalPlaylistScreen(id: Long, onBack: () -> Unit, onPlay: (List<Track>, Int) -> Unit, onMenu: (Track) -> Unit) {
    val a: Actions = koinInject()
    val playlist by remember(id) { a.library.playlist(id) }.collectAsState(null)
    val entries by remember(id) { a.library.entries(id) }.collectAsState(emptyList())
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val tracks = entries.map { it.track.let { e -> Track(e.id, e.title, e.artist, e.album, e.durationSec, e.thumbnail) } }


    if (renaming) TextInputDialog(stringResource(R.string.rename), playlist?.name.orEmpty(), stringResource(R.string.save), { renaming = false }) {
        renaming = false; a.scope.launch { a.library.rename(id, it) }
    }
    if (deleting) ConfirmDialog(stringResource(R.string.delete_playlist), playlist?.name.orEmpty(), stringResource(R.string.delete), { deleting = false }) {
        deleting = false; a.scope.launch { a.library.delete(id) }; onBack()
    }

    TrackListScaffold(
        title = playlist?.name.orEmpty(), tracks = tracks, onBack = onBack, onPlay = onPlay, onMenu = onMenu,
        emptyText = stringResource(R.string.playlist_empty),
        actions = {
            IconButton({ renaming = true }) { Icon(Icons.Default.Edit, stringResource(R.string.rename)) }
            IconButton({ deleting = true }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
        },
        rowExtra = { i, _ ->
            Row {
                IconButton({ a.scope.launch { a.library.move(id, i, i - 1) } }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.move_up)) }
                IconButton({ a.scope.launch { a.library.move(id, i, i + 1) } }, enabled = i < entries.lastIndex) { Icon(Icons.Default.ArrowDownward, stringResource(R.string.move_down)) }
                IconButton({ a.scope.launch { a.library.remove(id, entries[i].itemId) } }) { Icon(Icons.Default.Delete, stringResource(R.string.remove)) }
            }
        },
    )
}

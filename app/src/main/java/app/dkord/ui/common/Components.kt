package app.dkord.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dkord.R
import app.dkord.core.BrowseItem
import app.dkord.core.ItemKind
import app.dkord.core.Shelf
import app.dkord.core.Track
import coil3.compose.AsyncImage
import org.koin.compose.koinInject

@Composable
fun Cover(url: String?, size: Dp, modifier: Modifier = Modifier, round: Boolean = false, radius: Dp = 6.dp) {
    val shape = if (round) CircleShape else RoundedCornerShape(radius)
    Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (url == null) Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else AsyncImage(hiRes(url, 360), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable
fun ErrorBox(error: Throwable, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val msg = when (error.uiError()) {
        UiError.NETWORK -> R.string.err_network
        UiError.SESSION_EXPIRED -> R.string.err_session
        UiError.GENERIC -> R.string.err_generic
    }
    Column(modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(stringResource(msg), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) =
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)

/** A song row. Tap plays; the menu button opens [TrackMenu]. [trailing] replaces the menu button. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    onClick: () -> Unit,
    onMenu: (() -> Unit)? = null,
    supporting: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    ListItem(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onMenu),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = { Cover(track.thumbnail, 52.dp) },
        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
        supportingContent = { Text(supporting ?: listOf(track.artist, track.album).filter { !it.isNullOrBlank() }.joinToString(" • "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = trailing ?: onMenu?.let { { IconButton(it) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) } } },
    )
}

/** Square card used in horizontal shelves. */
@Composable
fun ItemCard(item: BrowseItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val w = if (item.kind == ItemKind.MOOD) 150.dp else 140.dp
    Column(modifier.width(w).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (item.kind == ItemKind.MOOD) {
            Box(Modifier.size(w, 64.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp), Alignment.CenterStart) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Cover(item.thumbnail, w, round = item.kind == ItemKind.ARTIST)
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, textAlign = if (item.kind == ItemKind.ARTIST) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
            if (item.subtitle.isNotBlank()) Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun ShelfRow(shelf: Shelf, onItem: (BrowseItem) -> Unit) {
    Column {
        if (shelf.title.isNotBlank()) SectionTitle(shelf.title)
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(shelf.items, key = null) { ItemCard(it, onClick = { onItem(it) }) }
        }
    }
}

/** A BrowseItem as a list row (search results). */
@Composable
fun BrowseRow(item: BrowseItem, onClick: () -> Unit, onMenu: (() -> Unit)? = null) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = { Cover(item.thumbnail, 52.dp, round = item.kind == ItemKind.ARTIST) },
        headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { if (item.subtitle.isNotBlank()) Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = onMenu?.let { { IconButton(it) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) } } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String = "", confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton({ onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(label) },
    )
}

/** Bottom sheet with every action for one track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMenu(track: Track, onDismiss: () -> Unit, onAddToPlaylist: (Track) -> Unit, extra: @Composable (() -> Unit)? = null) {
    val actions: Actions = koinInject()
    val likedIds by remember { actions.library.likedIds() }.collectAsState(emptySet())
    val liked = track.id in likedIds
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(track.thumbnail, 48.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        MenuRow(Icons.Default.PlayArrow, stringResource(R.string.play_next)) { actions.player.playNext(track); onDismiss() }
        MenuRow(Icons.Default.QueueMusic, stringResource(R.string.add_to_queue)) { actions.player.addToQueue(track); onDismiss() }
        MenuRow(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, stringResource(if (liked) R.string.unlike else R.string.like)) { actions.like(track, !liked); onDismiss() }
        MenuRow(Icons.Default.PlaylistAdd, stringResource(R.string.add_to_playlist)) { onDismiss(); onAddToPlaylist(track) }
        MenuRow(Icons.Default.Download, stringResource(R.string.download)) { actions.download(listOf(track)); onDismiss() }
        extra?.invoke()
        Spacer(Modifier.size(24.dp))
    }
}

/** Picks an existing playlist or creates one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPicker(tracks: List<Track>, onDismiss: () -> Unit, onResult: (String) -> Unit = {}) {
    val actions: Actions = koinInject()
    val lists by remember { actions.library.playlists() }.collectAsState(emptyList())
    var creating by rememberSaveable { mutableStateOf(false) }
    val added = stringResource(R.string.added_to)
    if (creating) {
        TextInputDialog(stringResource(R.string.new_playlist), "", stringResource(R.string.create), { creating = false }) { name ->
            actions.createPlaylist(name, tracks) { onResult(added.format(name)) }
            onDismiss()
        }
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        MenuRow(Icons.Default.Add, stringResource(R.string.new_playlist)) { creating = true }
        lists.forEach { s ->
            ListItem(
                modifier = Modifier.clickable {
                    actions.addToPlaylist(s.playlist.id, tracks) { _ -> onResult(added.format(s.playlist.name)) }
                    onDismiss()
                },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                leadingContent = { Cover(s.cover, 48.dp) },
                headlineContent = { Text(s.playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(pluralStringResource(R.plurals.n_songs, s.trackCount, s.trackCount)) },
            )
        }
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
fun DownloadedBadge() = Icon(Icons.Default.DownloadDone, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))

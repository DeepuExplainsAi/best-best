package app.dkord.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Login
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dkord.R
import app.dkord.core.BrowseItem
import app.dkord.core.Page
import app.dkord.data.library.LibraryRepository
import app.dkord.data.yt.MusicRepository
import app.dkord.ui.common.Actions
import app.dkord.ui.common.Cover
import app.dkord.ui.common.ErrorBox
import app.dkord.ui.common.Load
import app.dkord.ui.common.Loading
import app.dkord.ui.common.SectionTitle
import app.dkord.ui.common.ShelfRow
import app.dkord.ui.common.TextInputDialog
import app.dkord.ui.common.koinVm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun LibraryScreen(
    onOpenLocalPlaylist: (Long) -> Unit,
    onOpenLiked: () -> Unit,
    onOpenRecent: () -> Unit,
    onItem: (BrowseItem) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions: Actions = koinInject()
    val library = actions.library
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlists by remember { library.playlists() }.collectAsState(emptyList())
    var creating by rememberSaveable { mutableStateOf(false) }

    val importMsg = stringResource(R.string.imported_n)
    val importFail = stringResource(R.string.import_failed)
    val exported = stringResource(R.string.exported)
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }
                library.importJson(text)
            }.onSuccess { onMessage(importMsg.format(it)) }.onFailure { onMessage(importFail) }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = library.exportJson()
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(json) } }
            }.onSuccess { onMessage(exported) }.onFailure { onMessage(importFail) }
        }
    }

    if (creating) TextInputDialog(stringResource(R.string.new_playlist), "", stringResource(R.string.create), { creating = false }) {
        creating = false; actions.createPlaylist(it) { id -> onOpenLocalPlaylist(id) }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item("quick") {
            Column {
                NavRow(Icons.Default.Favorite, stringResource(R.string.liked_songs), onOpenLiked)
                NavRow(Icons.Default.History, stringResource(R.string.recently_played), onOpenRecent)
            }
        }
        item("hdr") {
            Row(Modifier.padding(end = 8.dp)) {
                SectionTitle(stringResource(R.string.my_playlists), Modifier.weight(1f))
                IconButton({ import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Icon(Icons.Default.FileUpload, stringResource(R.string.import_playlists)) }
                IconButton({ export.launch("dkord-playlists.json") }) { Icon(Icons.Default.FileDownload, stringResource(R.string.export_playlists)) }
                IconButton({ creating = true }) { Icon(Icons.Default.Add, stringResource(R.string.new_playlist)) }
            }
        }
        items(playlists, key = { it.playlist.id }) { s ->
            ListItem(
                modifier = Modifier.clickable { onOpenLocalPlaylist(s.playlist.id) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Cover(s.cover, 52.dp) },
                headlineContent = { Text(s.playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(pluralStringResource(R.plurals.n_songs, s.trackCount, s.trackCount)) },
            )
        }
        if (playlists.isEmpty()) item("noplaylists") { Text(stringResource(R.string.no_playlists), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }

        item("account") { SectionTitle(stringResource(R.string.youtube_music)) }
        item("login") { Text(stringResource(R.string.login_unsupported), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(label) },
    )
}

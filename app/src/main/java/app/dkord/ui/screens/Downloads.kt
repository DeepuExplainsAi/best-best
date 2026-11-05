package app.dkord.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dkord.R
import app.dkord.core.Track
import app.dkord.data.db.DownloadState
import app.dkord.data.db.DownloadWithTrack
import app.dkord.data.db.toTrack
import app.dkord.ui.common.Actions
import app.dkord.ui.common.DownloadedBadge
import app.dkord.ui.common.EmptyState
import app.dkord.ui.common.SectionTitle
import app.dkord.ui.common.TrackRow
import app.dkord.ui.common.formatSize
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun DownloadsScreen(onPlay: (List<Track>, Int) -> Unit, onMenu: (Track) -> Unit, modifier: Modifier = Modifier) {
    val a: Actions = koinInject()
    val all by remember { a.downloads.downloads() }.collectAsState(emptyList())
    val now by a.player.state.collectAsState()
    val active = all.filter { it.download.state != DownloadState.DONE }
    val done = all.filter { it.download.state == DownloadState.DONE }
    val doneTracks = done.map { it.track.toTrack() }

    if (all.isEmpty()) { EmptyState(stringResource(R.string.no_downloads), modifier); return }

    LazyColumn(modifier.fillMaxSize()) {
        if (active.isNotEmpty()) {
            item("qhdr") {
                Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    SectionTitle(stringResource(R.string.download_queue), Modifier.weight(1f))
                    OutlinedButton({ a.scope.launch { a.downloads.pauseAll() } }) { Text(stringResource(R.string.pause_all)) }
                    OutlinedButton({ a.scope.launch { a.downloads.resumeAll() } }, Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.resume_all)) }
                }
            }
            items(active, key = { "q" + it.download.trackId }) { ActiveRow(it, a) }
        }
        if (done.isNotEmpty()) {
            item("dhdr") { SectionTitle(stringResource(R.string.downloaded)) }
            items(done.size, key = { "d" + done[it].download.trackId }) { i ->
                val d = done[i]
                TrackRow(
                    d.track.toTrack(),
                    playing = now.current?.id == d.download.trackId,
                    supporting = listOf(d.track.artist, formatSize(d.download.total)).joinToString(" • "),
                    onClick = { onPlay(doneTracks, i) },
                    onMenu = { onMenu(d.track.toTrack()) },
                    trailing = {
                        Row {
                            DownloadedBadge()
                            IconButton({ a.scope.launch { a.downloads.remove(d.download.trackId) } }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ActiveRow(d: DownloadWithTrack, a: Actions) {
    val id = d.download.trackId
    val state = d.download.state
    TrackRow(
        d.track.toTrack(),
        onClick = {},
        supporting = when (state) {
            DownloadState.QUEUED -> stringResource(R.string.waiting)
            DownloadState.RUNNING -> if (d.download.total > 0) "${formatSize(d.download.bytes)} / ${formatSize(d.download.total)}" else stringResource(R.string.starting)
            DownloadState.PAUSED -> stringResource(R.string.paused)
            DownloadState.FAILED -> d.download.error ?: stringResource(R.string.failed)
            DownloadState.DONE -> ""
        },
        trailing = {
            Row {
                when (state) {
                    DownloadState.RUNNING, DownloadState.QUEUED ->
                        IconButton({ a.scope.launch { a.downloads.pause(id) } }) { Icon(Icons.Default.Pause, stringResource(R.string.pause)) }
                    DownloadState.PAUSED ->
                        IconButton({ a.scope.launch { a.downloads.resume(id) } }) { Icon(Icons.Default.PlayArrow, stringResource(R.string.resume)) }
                    DownloadState.FAILED ->
                        IconButton({ a.scope.launch { a.downloads.resume(id) } }) { Icon(Icons.Default.Refresh, stringResource(R.string.retry)) }
                    DownloadState.DONE -> Unit
                }
                IconButton({ a.scope.launch { a.downloads.remove(id) } }) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
            }
        },
    )
    if (state == DownloadState.RUNNING) {
        if (d.download.total > 0) LinearProgressIndicator({ d.download.fraction }, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    } else if (state == DownloadState.FAILED) {
        Text(stringResource(R.string.failed), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
    }
}

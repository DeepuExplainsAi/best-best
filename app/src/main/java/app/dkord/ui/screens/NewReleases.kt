package app.dkord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dkord.R
import app.dkord.core.BrowseItem
import app.dkord.core.ItemKind
import app.dkord.core.Shelf
import app.dkord.core.Track
import app.dkord.data.releases.NewReleasesRepository
import app.dkord.data.releases.ReleaseWindow
import app.dkord.ui.common.ErrorBox
import app.dkord.ui.common.Load
import app.dkord.ui.common.Loading
import app.dkord.ui.common.ShelfRow
import app.dkord.ui.common.koinVm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

class NewReleasesViewModel(private val repo: NewReleasesRepository) : ViewModel() {
    private val _songs = MutableStateFlow<Load<List<Track>>>(Load.Loading)
    val songs: StateFlow<Load<List<Track>>> = _songs
    private var job: Job? = null

    fun load(window: ReleaseWindow) {
        job?.cancel()
        _songs.value = Load.Loading
        job = viewModelScope.launch {
            try { _songs.value = Load.Ok(repo.songs(window)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _songs.value = Load.Failed(e) }
        }
    }
}

@Composable
fun NewReleasesSection(onItem: (BrowseItem) -> Unit, onPlay: (List<Track>, Int) -> Unit) {
    val repo: NewReleasesRepository = koinInject()
    val vm = koinVm { NewReleasesViewModel(repo) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var window by rememberSaveable { mutableStateOf(ReleaseWindow.LAST_24_HOURS) }
    val songs by vm.songs.collectAsState()
    Column {
        TextButton({ expanded = !expanded }, Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.new_releases), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        if (expanded) {
            LaunchedEffect(window) { vm.load(window) }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(window == ReleaseWindow.LAST_24_HOURS, { window = ReleaseWindow.LAST_24_HOURS }, { Text(stringResource(R.string.last_24_hours)) })
                FilterChip(window == ReleaseWindow.LAST_7_DAYS, { window = ReleaseWindow.LAST_7_DAYS }, { Text(stringResource(R.string.last_7_days)) })
            }
            when (val result = songs) {
                Load.Loading -> Box(Modifier.fillMaxWidth().height(120.dp)) { Loading() }
                is Load.Failed -> ErrorBox(result.error, { vm.load(window) }, Modifier.height(160.dp))
                is Load.Ok -> if (result.value.isEmpty()) {
                    Text(stringResource(R.string.no_new_songs), Modifier.padding(24.dp))
                } else ShelfRow(Shelf("", result.value.map {
                    BrowseItem(ItemKind.SONG, it.id, it.title, it.artist, it.thumbnail, track = it)
                }), onItem, onPlay)
            }
        }
    }
}

package app.dkord.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dkord.R
import app.dkord.core.BrowseItem
import app.dkord.core.ItemKind
import app.dkord.core.Shelf
import app.dkord.core.Track
import app.dkord.data.releases.NewReleasesRepository
import app.dkord.data.releases.ReleaseCategory
import app.dkord.data.releases.ReleaseDatesUnavailable
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
    private val mutableSongs = MutableStateFlow<Load<List<Track>>>(Load.Loading)
    val songs: StateFlow<Load<List<Track>>> = mutableSongs
    private var job: Job? = null

    fun load(window: ReleaseWindow, category: ReleaseCategory, refresh: Boolean = false) {
        job?.cancel()
        mutableSongs.value = Load.Loading
        job = viewModelScope.launch {
            try { mutableSongs.value = Load.Ok(repo.songs(window, category, refresh)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableSongs.value = Load.Failed(e) }
        }
    }

    fun cancel() { job?.cancel() }
}

@Composable
fun NewReleasesSection(onItem: (BrowseItem) -> Unit, onPlay: (List<Track>, Int) -> Unit) {
    val repo: NewReleasesRepository = koinInject()
    val vm = koinVm { NewReleasesViewModel(repo) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var window by rememberSaveable { mutableStateOf(ReleaseWindow.LAST_7_DAYS) }
    var category by rememberSaveable { mutableStateOf(ReleaseCategory.ALL) }
    val songs by vm.songs.collectAsState()
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(200), label = "release-arrow")
    val description = stringResource(if (expanded) R.string.section_expanded else R.string.section_collapsed)
    LaunchedEffect(expanded, window, category) { if (expanded) vm.load(window, category) else vm.cancel() }
    DisposableEffect(vm) { onDispose { vm.cancel() } }
    Column {
        TextButton({ expanded = !expanded }, Modifier.fillMaxWidth().semantics { stateDescription = description }) {
            Text(stringResource(R.string.new_releases), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ExpandMore, null, Modifier.graphicsLayer { rotationZ = rotation })
        }
        AnimatedVisibility(expanded,
            enter = expandVertically(tween(220), expandFrom = Alignment.Top) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(180), shrinkTowards = Alignment.Top) + fadeOut(tween(120)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ReleaseCategory.entries, key = { it.name }) { value ->
                        FilterChip(category == value, { category = value }, { Text(stringResource(categoryLabel(value))) })
                    }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ReleaseWindow.entries, key = { it.name }) { value ->
                        FilterChip(window == value, { window = value }, { Text(stringResource(windowLabel(value))) })
                    }
                }
                when (val result = songs) {
                    Load.Loading -> Box(Modifier.fillMaxWidth().height(160.dp)) { Loading() }
                    is Load.Failed -> if (result.error is ReleaseDatesUnavailable) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.release_dates_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton({ vm.load(window, category, refresh = true) }) { Text(stringResource(R.string.retry)) }
                        }
                    } else ErrorBox(result.error, { vm.load(window, category, refresh = true) }, Modifier.height(160.dp))
                    is Load.Ok -> if (result.value.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.no_new_songs), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton({ vm.load(window, category, refresh = true) }) { Text(stringResource(R.string.retry)) }
                        }
                    } else ShelfRow(Shelf("", result.value.map {
                        BrowseItem(ItemKind.SONG, it.id, it.title, it.artist, it.thumbnail, track = it)
                    }), onItem, onPlay)
                }
            }
        }
    }
}

private fun categoryLabel(category: ReleaseCategory): Int = when (category) {
    ReleaseCategory.ALL -> R.string.release_all
    ReleaseCategory.HOLLYWOOD -> R.string.release_hollywood
    ReleaseCategory.BOLLYWOOD -> R.string.release_bollywood
    ReleaseCategory.CHOLIWOOD -> R.string.choliwood_name
    ReleaseCategory.PUNJABI -> R.string.release_punjabi
    ReleaseCategory.TAMIL -> R.string.release_tamil
    ReleaseCategory.TELUGU -> R.string.release_telugu
}

private fun windowLabel(window: ReleaseWindow): Int = when (window) {
    ReleaseWindow.LAST_24_HOURS -> R.string.last_24_hours
    ReleaseWindow.LAST_7_DAYS -> R.string.last_7_days
    ReleaseWindow.LAST_10_DAYS -> R.string.last_10_days
}

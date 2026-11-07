package app.dkord.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.dkord.R
import app.dkord.data.update.AppUpdater
import app.dkord.data.update.UpdateFailure
import app.dkord.data.update.UpdatePhase
import app.dkord.data.update.UpdateProgress
import org.koin.compose.koinInject
import java.util.Locale

/** Always mounted once: checks stay alive while a pushed screen or the full player is visible. */
@Composable
fun ObserveAppUpdates() {
    val updater: AppUpdater = koinInject()
    LifecycleResumeEffect(updater) {
        updater.onForeground()
        onPauseOrDispose { }
    }
}

/** Inline Home notice, not a dialog: no focus theft, backdrop, repeated popup or automatic download. */
@Composable
fun HomeUpdateNotice(visible: Boolean, onOpenSettings: () -> Unit) {
    val updater: AppUpdater = koinInject()
    val state by updater.state.collectAsState()
    val info = state.info
    val lastVersion = remember { mutableStateOf("") }
    SideEffect { info?.let { lastVersion.value = it.version } }
    val version = info?.version ?: lastVersion.value
    AnimatedVisibility(visible && state.phase == UpdatePhase.AVAILABLE && state.promptAllowed && info != null,
        enter = expandVertically(tween(DkordMotion.CONTENT)) + fadeIn(tween(DkordMotion.QUICK)),
        exit = shrinkVertically(tween(DkordMotion.CONTENT)) + fadeOut(tween(DkordMotion.QUICK))) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            BoxWithConstraints(Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)) {
                if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.update_banner_version, version), style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { UpdateNoticeActions(updater, onOpenSettings) }
                    }
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.update_banner_version, version), Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    UpdateNoticeActions(updater, onOpenSettings)
                }
            }
        }
    }
}

@Composable
private fun UpdateNoticeActions(updater: AppUpdater, onOpenSettings: () -> Unit) {
    TextButton({ updater.download(); onOpenSettings() }) { Text(stringResource(R.string.update_action)) }
    TextButton(updater::later) { Text(stringResource(R.string.update_later)) }
}

@Composable
fun AppUpdateCard() {
    val updater: AppUpdater = koinInject()
    val state by updater.state.collectAsState()
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).animateContentSize(tween(180))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.app_version, updater.currentVersion), style = MaterialTheme.typography.titleMedium)
            val info = state.info
            if (info != null && state.phase != UpdatePhase.PREPARING && state.phase != UpdatePhase.CHECKING) {
                Text(stringResource(R.string.update_available, info.version), style = MaterialTheme.typography.bodyMedium)
                if (state.phase != UpdatePhase.DOWNLOADING) (info.sizeBytes ?: state.progress.totalBytes)?.let {
                    Text(stringResource(R.string.update_size, megabytes(it)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when (state.phase) {
                UpdatePhase.CHECKING, UpdatePhase.PREPARING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.update_checking))
                    if (state.phase == UpdatePhase.PREPARING) TextButton(updater::cancel) { Text(stringResource(R.string.cancel)) }
                }
                UpdatePhase.DOWNLOADING -> {
                    DownloadProgress(state.progress)
                    TextButton(updater::cancel) { Text(stringResource(R.string.cancel)) }
                }
                UpdatePhase.READY -> {
                    Text(stringResource(if (state.needsInstallPermission) R.string.update_permission_needed else R.string.update_ready))
                    if (state.failure != null) Text(stringResource(R.string.update_installer_failed), color = MaterialTheme.colorScheme.error)
                    Button(updater::install) { Text(stringResource(if (state.needsInstallPermission) R.string.update_allow_installs else R.string.update_open_installer)) }
                    TextButton(updater::check) { Text(stringResource(R.string.update_check)) }
                }
                UpdatePhase.AVAILABLE -> Button(updater::download) { Text(stringResource(R.string.update_install)) }
                UpdatePhase.FAILED -> {
                    Text(stringResource(if (state.failure == UpdateFailure.VERIFICATION) R.string.update_verification_failed else R.string.update_failed), color = MaterialTheme.colorScheme.error)
                    TextButton({ if (info != null) updater.download() else updater.check() }) { Text(stringResource(R.string.retry)) }
                }
                else -> {
                    if (state.phase == UpdatePhase.UP_TO_DATE) Text(stringResource(R.string.update_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(updater::check) { Text(stringResource(R.string.update_check)) }
                }
            }
        }
    }
}

@Composable
private fun DownloadProgress(progress: UpdateProgress) {
    val fraction by animateFloatAsState(progress.fraction ?: 0f, tween(150), label = "update-progress")
    if (progress.fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    val total = progress.totalBytes
    Text(
        if (total != null && total > 0) stringResource(R.string.update_progress_known,
            megabytes(progress.downloadedBytes), megabytes(total), megabytes(progress.remainingBytes ?: 0))
        else stringResource(R.string.update_progress_unknown, megabytes(progress.downloadedBytes)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Decimal MB, matching the public release asset size rather than labelling MiB as MB. */
private fun megabytes(bytes: Long) = String.format(Locale.getDefault(), "%.1f", bytes.coerceAtLeast(0) / 1_000_000.0)

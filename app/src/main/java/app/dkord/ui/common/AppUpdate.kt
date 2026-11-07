package app.dkord.ui.common

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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

/** Check on foreground; ask only on Home, never over the player, menus or pushed screens. */
@Composable
fun HomeUpdatePrompt(visible: Boolean, onOpenSettings: () -> Unit) {
    val updater: AppUpdater = koinInject()
    val state by updater.state.collectAsState()
    LifecycleResumeEffect(updater) {
        updater.onForeground()
        onPauseOrDispose { }
    }
    val info = state.info
    if (visible && state.phase == UpdatePhase.AVAILABLE && state.promptAllowed && info != null) {
        AlertDialog(
            onDismissRequest = updater::later,
            title = { Text(stringResource(R.string.update_prompt_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.update_available, info.version))
                info.sizeBytes?.let { Text(stringResource(R.string.update_size, megabytes(it)), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } },
            confirmButton = { TextButton({ updater.download(); onOpenSettings() }) { Text(stringResource(R.string.update_install)) } },
            dismissButton = { TextButton(updater::later) { Text(stringResource(R.string.update_later)) } },
        )
    }
}

@Composable
fun AppUpdateCard() {
    val updater: AppUpdater = koinInject()
    val state by updater.state.collectAsState()
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).animateContentSize(tween(180))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.app_version, updater.currentVersion), style = MaterialTheme.typography.titleMedium)
            val info = state.info
            if (info != null) {
                Text(stringResource(R.string.update_available, info.version), style = MaterialTheme.typography.bodyMedium)
                if (state.phase != UpdatePhase.DOWNLOADING) info.sizeBytes?.let {
                    Text(stringResource(R.string.update_size, megabytes(it)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when (state.phase) {
                UpdatePhase.CHECKING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.update_checking))
                }
                UpdatePhase.DOWNLOADING -> {
                    DownloadProgress(state.progress)
                    TextButton(updater::cancel) { Text(stringResource(R.string.cancel)) }
                }
                UpdatePhase.READY -> {
                    Text(stringResource(if (state.needsInstallPermission) R.string.update_permission_needed else R.string.update_ready))
                    if (state.failure != null) Text(stringResource(R.string.update_installer_failed), color = MaterialTheme.colorScheme.error)
                    Button(updater::install) { Text(stringResource(if (state.needsInstallPermission) R.string.update_allow_installs else R.string.update_open_installer)) }
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

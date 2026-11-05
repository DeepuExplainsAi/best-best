package app.dkord.ui.common

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.dkord.data.auth.AuthStore
import app.dkord.data.yt.InnerTubeClient
import java.io.IOException

/** ViewModel without a Koin-ViewModel dependency: dependencies come from koinInject() at the call site. */
@Composable
inline fun <reified VM : ViewModel> koinVm(key: String? = null, noinline create: () -> VM): VM =
    viewModel(key = key, factory = viewModelFactory { initializer { create() } })

/** Loading state shared by all remote screens. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Failed(val error: Throwable) : Load<Nothing>
}

enum class UiError { NETWORK, SESSION_EXPIRED, GENERIC }

fun Throwable.uiError(): UiError = when {
    this is InnerTubeClient.AuthExpired -> UiError.SESSION_EXPIRED
    this is IOException -> UiError.NETWORK
    else -> UiError.GENERIC
}

/** Used by screens to show account state without each one touching [AuthStore] internals. */
val AuthStore.displayName: String? get() = accountName

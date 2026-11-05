package app.dkord.player

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Failures cannot travel through MediaController (the cause is lost across the session boundary),
 * so the service, which lives in the same process as the UI, publishes them here.
 */
object PlaybackEvents {
    private val _failures = MutableSharedFlow<PlaybackFailure>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val failures: SharedFlow<PlaybackFailure> = _failures.asSharedFlow()
    fun emit(f: PlaybackFailure) { _failures.tryEmit(f) }
}

package app.dkord.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the timer needs from the player. Lets the logic run on a plain JVM in tests. */
interface SleepTarget {
    fun setVolume(volume: Float)
    fun pause()
    fun setPauseAtEndOfTrack(enabled: Boolean)
}

sealed interface SleepState {
    data object Off : SleepState
    data class Countdown(val remainingMs: Long) : SleepState
    data object EndOfTrack : SleepState
}

/** Sleep timer with a gentle volume fade. All calls must happen on the player's (main) thread. */
class SleepTimer(private val scope: CoroutineScope, private val fadeMs: Long = 8_000, private val tickMs: Long = 1_000) {
    private val _state = MutableStateFlow<SleepState>(SleepState.Off)
    val state: StateFlow<SleepState> = _state

    private var target: SleepTarget? = null
    private var job: Job? = null

    fun attach(t: SleepTarget?) {
        if (t == null) cancel()
        target = t
    }

    fun start(minutes: Int) {
        require(minutes > 0)
        reset()
        _state.value = SleepState.Countdown(minutes * 60_000L)
        job = scope.launch {
            var remaining = minutes * 60_000L
            while (remaining > 0) {
                delay(tickMs)
                remaining -= tickMs
                _state.value = SleepState.Countdown(remaining.coerceAtLeast(0))
                if (remaining in 1 until fadeMs) target?.setVolume(remaining.toFloat() / fadeMs)
            }
            target?.pause()
            target?.setVolume(1f)
            _state.value = SleepState.Off
        }
    }

    fun startEndOfTrack() {
        reset()
        target?.setPauseAtEndOfTrack(true)
        _state.value = SleepState.EndOfTrack
    }

    /** Called when the player paused because the track ended. */
    fun onTrackEnded() {
        if (_state.value != SleepState.EndOfTrack) return
        target?.setPauseAtEndOfTrack(false)
        _state.value = SleepState.Off
    }

    fun cancel() = reset()

    private fun reset() {
        job?.cancel(); job = null
        if (_state.value == SleepState.EndOfTrack) target?.setPauseAtEndOfTrack(false)
        target?.setVolume(1f)
        _state.value = SleepState.Off
    }
}

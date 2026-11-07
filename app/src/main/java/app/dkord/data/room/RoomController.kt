package app.dkord.data.room

import app.dkord.core.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class RoomRole { HOST, GUEST }
enum class RoomError { INVALID_CODE, NETWORK, RATE_LIMIT, INVALID_MESSAGE, PLAYBACK }

data class RoomState(
    val code: String? = null,
    val role: RoomRole? = null,
    val connecting: Boolean = false,
    val host: RoomMessage? = null,
    val followAutomatically: Boolean = false,
    val error: RoomError? = null,
)

/** App-lifetime, opt-in room session. Leaving cancels publishing/subscription and pending syncs. */
class RoomController(
    private val transport: RoomTransport,
    private val playback: RoomPlayback,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(RoomState())
    val state: StateFlow<RoomState> = _state
    private var roomJob: Job? = null
    private var syncJob: Job? = null

    fun create() {
        leave()
        val code = RoomCode.generate()
        _state.value = RoomState(code, RoomRole.HOST, connecting = true)
        host(code)
    }

    fun join(input: String) {
        val code = RoomCode.normalize(input)
        if (code == null) { _state.update { it.copy(error = RoomError.INVALID_CODE) }; return }
        val follow = _state.value.followAutomatically
        leave()
        _state.value = RoomState(code, RoomRole.GUEST, connecting = true, followAutomatically = follow)
        roomJob = scope.launch {
            try {
                val cached = transport.latest(code)
                cached?.let(::receive)
                _state.update { it.copy(connecting = false, error = null) }
                transport.subscribe(code, cached?.cursor).collect(::receive)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e) }
        }
    }

    fun leave() {
        roomJob?.cancel(); roomJob = null
        syncJob?.cancel(); syncJob = null
        _state.value = RoomState()
    }

    fun retry() {
        val state = _state.value
        val code = state.code ?: return
        if (state.role == RoomRole.GUEST) join(code)
        else if (state.role == RoomRole.HOST) {
            roomJob?.cancel()
            _state.update { it.copy(connecting = true, error = null) }
            host(code)
        }
    }

    fun followAutomatically(follow: Boolean) {
        if (_state.value.role != RoomRole.GUEST) return
        _state.update { it.copy(followAutomatically = follow) }
        if (follow) syncNow() else { syncJob?.cancel(); syncJob = null }
    }

    fun syncNow() {
        val state = _state.value
        if (state.role != RoomRole.GUEST) return
        val message = state.host ?: return
        syncJob?.cancel()
        syncJob = scope.launch {
            try { playback.sync(message, synchronizedPosition(message, now())) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                _state.update { it.copy(error = RoomError.PLAYBACK) }
                DebugLog.w("room", "Guest playback synchronization failed")
            }
        }
    }

    private fun receive(event: RoomEvent) {
        val message = event.message
        val clock = now()
        if (message.timestamp > clock + 5 * 60_000 || message.timestamp < clock - 12 * 60 * 60_000) return
        val state = _state.value
        if (state.role != RoomRole.GUEST || message.timestamp <= (state.host?.timestamp ?: 0)) return
        _state.update { it.copy(host = message, connecting = false, error = null) }
        if (_state.value.followAutomatically) syncNow()
    }

    private fun host(code: String) {
        roomJob = scope.launch {
            coroutineScope {
                val changes = Channel<Unit>(Channel.CONFLATED)
                launch {
                    var previous: RoomPlaybackState? = null
                    var observedAt = now()
                    playback.states.collect { state ->
                        val time = now()
                        val old = previous
                        val expected = old?.let { it.positionMs + if (it.playing) (time - observedAt).coerceAtLeast(0) else 0 }
                        if (old == null || state.current != old.current || state.playing != old.playing ||
                            state.queue != old.queue || state.queueIndex != old.queueIndex ||
                            (expected != null && abs(state.positionMs - expected) > 1_200)) changes.trySend(Unit)
                        previous = state; observedAt = time
                    }
                }
                launch {
                    while (isActive) {
                        delay(15_000)
                        if (playback.current().playing) changes.trySend(Unit)
                    }
                }
                var sentAt = 0L
                for (ignored in changes) {
                    val stamp = maxOf(now(), sentAt + 1)
                    val message = roomMessage(playback.current(), stamp)
                    if (message == null) { _state.update { it.copy(connecting = false) }; continue }
                    try {
                        transport.publish(code, message)
                        sentAt = stamp
                        _state.update { it.copy(connecting = false, host = message, error = null) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        fail(e)
                        delay(if (e is RoomTransportException && e.rateLimited) 60_000 else 5_000)
                    }
                }
            }
        }
    }

    private fun fail(error: Exception) {
        val type = when {
            error is RoomTransportException && error.rateLimited -> RoomError.RATE_LIMIT
            error is IllegalArgumentException -> RoomError.INVALID_MESSAGE
            else -> RoomError.NETWORK
        }
        _state.update { it.copy(connecting = false, error = type) }
        // Deliberately no topic/code, payload, URL, or underlying exception message in the log.
        DebugLog.w("room", "Room relay failure: ${type.name}")
    }
}

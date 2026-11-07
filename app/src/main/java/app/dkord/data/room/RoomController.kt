package app.dkord.data.room

import app.dkord.core.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
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
    val syncing: Boolean = false,
    val hasSynced: Boolean = false,
)

/** App-lifetime opt-in sessions. Old jobs cannot change a newly joined room's state. */
class RoomController(
    private val transport: RoomTransport,
    private val playback: RoomPlayback,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(RoomState())
    val state: StateFlow<RoomState> = mutableState
    private var roomJob: Job? = null
    private var syncJob: Job? = null
    private var session = 0L
    private var syncRequest = 0L
    private var cursor: String? = null
    private data class Seek(val videoId: String, val positionMs: Long, val at: Long)
    private var pendingSeek: Seek? = null
    private var hostChanges: Channel<Unit>? = null

    fun seeked(positionMs: Long) {
        if (state.value.role != RoomRole.HOST) return
        val id = playback.current().current?.id ?: return
        pendingSeek = Seek(id, positionMs.coerceIn(0, RoomCodec.MAX_POSITION_MS), now())
        hostChanges?.trySend(Unit)
    }

    fun create() {
        if (state.value.role == RoomRole.HOST) return
        leave()
        val code = RoomCode.generate()
        mutableState.value = RoomState(code, RoomRole.HOST, connecting = true)
        host(code, session)
    }

    fun clearCodeError() {
        if (state.value.role == null && state.value.error == RoomError.INVALID_CODE) mutableState.update { it.copy(error = null) }
    }

    fun join(input: String) {
        val code = RoomCode.normalize(input)
        if (code == null) { mutableState.update { it.copy(error = RoomError.INVALID_CODE) }; return }
        val previous = state.value
        val sameRoom = previous.role == RoomRole.GUEST && previous.code == code
        if (sameRoom && previous.connecting && previous.error == null) return
        val resume = if (sameRoom) cursor else null
        leave()
        cursor = resume
        mutableState.value = RoomState(code, RoomRole.GUEST, connecting = true,
            host = previous.host.takeIf { sameRoom }, followAutomatically = sameRoom && previous.followAutomatically,
            hasSynced = sameRoom && previous.hasSynced)
        guest(code, session)
    }

    fun leave() {
        session++; syncRequest++
        roomJob?.cancel(); roomJob = null
        syncJob?.cancel(); syncJob = null
        hostChanges?.close(); hostChanges = null
        pendingSeek = null; cursor = null
        mutableState.value = RoomState()
    }

    fun retry() {
        val value = state.value
        val code = value.code ?: return
        if (value.connecting) return
        if (value.role == RoomRole.GUEST) join(code)
        else if (value.role == RoomRole.HOST) {
            session++
            roomJob?.cancel()
            hostChanges?.close(); hostChanges = null
            mutableState.update { it.copy(connecting = true, error = null) }
            host(code, session)
        }
    }

    fun followAutomatically(follow: Boolean) {
        if (state.value.role != RoomRole.GUEST) return
        mutableState.update { it.copy(followAutomatically = follow) }
        if (follow) syncNow() else {
            syncRequest++; syncJob?.cancel(); syncJob = null
            mutableState.update { it.copy(syncing = false) }
        }
    }

    fun syncNow() {
        val value = state.value
        if (value.role != RoomRole.GUEST) return
        val message = value.host ?: return
        val id = session
        val request = ++syncRequest
        syncJob?.cancel()
        mutableState.update { it.copy(syncing = true, error = it.error.takeUnless { error -> error == RoomError.PLAYBACK }) }
        syncJob = scope.launch {
            try {
                playback.sync(message, synchronizedPosition(message, now()))
                if (current(id) && request == syncRequest) mutableState.update { it.copy(hasSynced = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (current(id) && request == syncRequest) {
                    mutableState.update { it.copy(error = RoomError.PLAYBACK) }
                    DebugLog.w("room", "Guest playback synchronization failed")
                }
            } finally {
                if (current(id) && request == syncRequest) mutableState.update { it.copy(syncing = false) }
            }
        }
    }

    private fun guest(code: String, id: Long) {
        roomJob = scope.launch {
            var after = cursor
            while (isActive && current(id)) {
                update(id) { it.copy(connecting = true) }
                try {
                    if (after == null) {
                        val cached = transport.latest(code)
                        cached?.let { receive(it, id) }
                        after = cached?.cursor
                        if (current(id)) cursor = after
                    }
                    update(id) { it.copy(connecting = false, error = null) }
                    transport.subscribe(code, after).collect { event ->
                        currentCoroutineContext().ensureActive()
                        if (current(id)) {
                            event.cursor?.let { after = it; cursor = it }
                            receive(event, id)
                        }
                    }
                    throw IOException("Room connection closed")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    fail(e, id, reconnecting = true)
                    delay(retryDelay(e))
                }
            }
        }
    }

    private fun receive(event: RoomEvent, id: Long) {
        if (!current(id)) return
        val message = event.message
        val clock = now()
        if (message.timestamp > clock + 5 * 60_000 || message.timestamp < clock - 12 * 60 * 60_000) return
        val value = state.value
        if (value.role != RoomRole.GUEST || message.timestamp <= (value.host?.timestamp ?: 0)) return
        update(id) { it.copy(host = message, connecting = false, error = null) }
        if (state.value.followAutomatically) syncNow()
    }

    private fun host(code: String, id: Long) {
        roomJob = scope.launch {
            coroutineScope {
                val changes = Channel<Unit>(Channel.CONFLATED)
                if (current(id)) hostChanges = changes
                launch {
                    var previous: RoomPlaybackState? = null
                    var observedAt = now()
                    playback.states.collect { value ->
                        val time = now()
                        val old = previous
                        val expected = old?.let { it.positionMs + if (it.playing) (time - observedAt).coerceAtLeast(0) else 0 }
                        if (old == null || value.current != old.current || value.playing != old.playing ||
                            value.queue != old.queue || value.queueIndex != old.queueIndex ||
                            (expected != null && abs(value.positionMs - expected) > 1_200)) changes.trySend(Unit)
                        previous = value; observedAt = time
                    }
                }
                launch {
                    while (isActive) {
                        delay(15_000)
                        if (playback.current().playing) changes.trySend(Unit)
                    }
                }
                var sentAt = state.value.host?.timestamp ?: 0L
                for (ignored in changes) {
                    currentCoroutineContext().ensureActive()
                    val stamp = maxOf(now(), sentAt + 1)
                    var snapshot = playback.current()
                    pendingSeek?.let { seek ->
                        val expected = seek.positionMs + if (snapshot.playing) (now() - seek.at).coerceAtLeast(0) else 0
                        if (snapshot.current?.id == seek.videoId && now() - seek.at < 2_000 && abs(snapshot.positionMs - expected) > 50) {
                            snapshot = snapshot.copy(positionMs = expected)
                        } else pendingSeek = null
                    }
                    val message = roomMessage(snapshot, stamp)
                    if (message == null) { update(id) { it.copy(connecting = false) }; continue }
                    try {
                        transport.publish(code, message)
                        currentCoroutineContext().ensureActive()
                        sentAt = stamp
                        update(id) { it.copy(connecting = false, host = message, error = null) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        val retryable = e !is IllegalArgumentException
                        fail(e, id, reconnecting = retryable)
                        if (retryable) {
                            delay(retryDelay(e))
                            changes.trySend(Unit) // Also retry a paused host, which has no playing heartbeat.
                        }
                    }
                }
            }
        }
    }

    private fun current(id: Long) = session == id && state.value.role != null
    private inline fun update(id: Long, block: (RoomState) -> RoomState) {
        if (current(id)) mutableState.update { if (current(id)) block(it) else it }
    }
    private fun retryDelay(error: Exception) = if (error is RoomTransportException && error.rateLimited) 60_000L else 5_000L

    private fun fail(error: Exception, id: Long, reconnecting: Boolean) {
        if (!current(id)) return
        val type = when {
            error is RoomTransportException && error.rateLimited -> RoomError.RATE_LIMIT
            error is IllegalArgumentException -> RoomError.INVALID_MESSAGE
            else -> RoomError.NETWORK
        }
        update(id) { it.copy(connecting = reconnecting, error = type) }
        DebugLog.w("room", "Room connection failure: ${type.name}")
    }
}

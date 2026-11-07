package app.dkord

import app.dkord.core.DebugLog
import app.dkord.core.Track
import app.dkord.data.room.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomControllerTest {
    private val clock = 1_800_000_000_000L
    private val song = Track("abcdefghijk", "Song", "Artist")

    private class Playback(initial: RoomPlaybackState) : RoomPlayback {
        override val states = MutableStateFlow(initial)
        val syncs = mutableListOf<Pair<RoomMessage, Long>>()
        var failure: Exception? = null
        var syncDelay = 0L
        override fun current() = states.value
        override suspend fun sync(message: RoomMessage, positionMs: Long) {
            if (syncDelay > 0) withContext(NonCancellable) { delay(syncDelay) }
            failure?.let { throw it }
            syncs += message to positionMs
        }
    }

    private class Relay(var cached: RoomEvent? = null) : RoomTransport {
        val live = MutableSharedFlow<RoomEvent>(extraBufferCapacity = 10)
        val sent = mutableListOf<RoomMessage>()
        var resume: String? = null
        var polls = 0
        var failure: Exception? = null
        override suspend fun publish(code: String, message: RoomMessage) { failure?.let { throw it }; sent += message }
        override suspend fun latest(code: String): RoomEvent? { polls++; failure?.let { throw it }; return cached }
        override fun subscribe(code: String, after: String?) = live.also { resume = after }
    }

    @Test fun host_publishes_initial_state_changes_seeks_and_playing_heartbeats() = runTest {
        val playback = Playback(RoomPlaybackState(song, listOf(song), playing = true))
        val relay = Relay()
        val controller = RoomController(relay, playback, backgroundScope) { clock + testScheduler.currentTime }
        controller.create(); runCurrent()
        assertEquals(1, relay.sent.size)
        advanceTimeBy(500)
        playback.states.value = playback.states.value.copy(positionMs = 500); runCurrent()
        assertEquals(1, relay.sent.size) // Ordinary progress ticks do not publish every 500 ms.
        playback.states.value = playback.states.value.copy(positionMs = 8_000); runCurrent()
        assertEquals(2, relay.sent.size)
        advanceTimeBy(14_500); runCurrent()
        assertEquals(3, relay.sent.size)
        playback.states.value = playback.states.value.copy(playing = false); runCurrent()
        assertFalse(relay.sent.last().playing)
        val pausedCount = relay.sent.size
        advanceTimeBy(15_000); runCurrent()
        assertEquals(pausedCount, relay.sent.size)
        controller.leave()
    }

    @Test fun small_explicit_host_seek_is_published_before_player_acknowledgement() = runTest {
        val playback = Playback(RoomPlaybackState(song, listOf(song), playing = false))
        val relay = Relay()
        val controller = RoomController(relay, playback, backgroundScope) { clock }
        controller.create(); runCurrent()
        controller.seeked(100); runCurrent()
        assertEquals(100L, relay.sent.last().positionMs)
        assertEquals(2, relay.sent.size)
        controller.leave()
    }

    @Test fun guest_reads_cache_resumes_live_and_requires_sync_unless_follow_is_enabled() = runTest {
        val msg = RoomMessage(song.id, song.title, song.artist, positionMs = 1_000, playing = true, timestamp = clock - 1_000)
        val relay = Relay(RoomEvent(msg, "cached123"))
        val playback = Playback(RoomPlaybackState())
        val controller = RoomController(relay, playback, backgroundScope) { clock + testScheduler.currentTime }
        controller.join("abcdefghjk"); runCurrent()
        assertEquals(msg, controller.state.value.host)
        assertEquals("cached123", relay.resume)
        assertTrue(playback.syncs.isEmpty())
        controller.syncNow(); runCurrent()
        assertEquals(2_000L, playback.syncs.last().second)
        controller.followAutomatically(true); runCurrent()
        val count = playback.syncs.size
        relay.live.emit(RoomEvent(msg.copy(timestamp = clock + 1, positionMs = 3_000))); runCurrent()
        assertEquals(count + 1, playback.syncs.size)
        playback.states.value = RoomPlaybackState(song, listOf(song), playing = true); runCurrent()
        assertTrue(relay.sent.isEmpty()) // Guests never publish or control the host.
        controller.leave()
    }

    @Test fun stale_duplicate_and_future_messages_are_ignored_and_leave_cancels_subscription() = runTest {
        val msg = RoomMessage(song.id, song.title, song.artist, positionMs = 0, playing = false, timestamp = clock)
        val relay = Relay(RoomEvent(msg))
        val playback = Playback(RoomPlaybackState())
        val controller = RoomController(relay, playback, backgroundScope) { clock }
        controller.join("ABCDEFGHJK"); runCurrent()
        relay.live.emit(RoomEvent(msg.copy(title = "Old", timestamp = clock - 1))); runCurrent()
        relay.live.emit(RoomEvent(msg.copy(title = "Future", timestamp = clock + 600_000))); runCurrent()
        assertEquals("Song", controller.state.value.host?.title)
        controller.leave(); runCurrent()
        relay.live.emit(RoomEvent(msg.copy(timestamp = clock + 1))); runCurrent()
        assertNull(controller.state.value.host)
        assertNull(controller.state.value.role)
    }

    @Test fun rate_limits_are_visible_and_logs_do_not_contain_room_codes() = runTest {
        DebugLog.clear()
        val relay = Relay().apply { failure = RoomTransportException(true, "rate limited") }
        val controller = RoomController(relay, Playback(RoomPlaybackState()), backgroundScope) { clock }
        controller.join("ABCDEFGHJK"); runCurrent()
        assertEquals(RoomError.RATE_LIMIT, controller.state.value.error)
        assertTrue(DebugLog.dump().contains("RATE_LIMIT"))
        assertFalse(DebugLog.dump().contains("ABCDEFGHJK"))
        controller.leave()
    }

    @Test fun repeated_create_and_join_taps_do_not_replace_an_in_flight_room() = runTest {
        val relay = Relay()
        val controller = RoomController(relay, Playback(RoomPlaybackState(song, listOf(song))), backgroundScope) { clock }
        controller.create()
        val code = controller.state.value.code
        controller.create(); runCurrent()
        assertEquals(code, controller.state.value.code)
        assertEquals(1, relay.sent.size)
        controller.leave()
        controller.join("ABCDEFGHJK"); controller.join("ABCDEFGHJK"); runCurrent()
        assertEquals(1, relay.polls)
        controller.leave()
    }

    @Test fun a_paused_host_retries_a_failed_initial_publish_without_changing_code() = runTest {
        val relay = Relay().apply { failure = java.io.IOException("offline") }
        val controller = RoomController(relay, Playback(RoomPlaybackState(song, listOf(song))), backgroundScope) { clock + testScheduler.currentTime }
        controller.create(); runCurrent()
        val code = controller.state.value.code
        assertEquals(RoomError.NETWORK, controller.state.value.error)
        assertTrue(controller.state.value.connecting)
        relay.failure = null
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, relay.sent.size)
        assertEquals(code, controller.state.value.code)
        assertFalse(controller.state.value.connecting)
        assertNull(controller.state.value.error)
        controller.leave()
    }

    @Test fun guest_reconnects_after_the_last_received_cursor_without_losing_the_song() = runTest {
        val first = RoomMessage(song.id, song.title, song.artist, positionMs = 0, playing = false, timestamp = clock)
        val second = first.copy(title = "New song", timestamp = clock + 1)
        val resumed = mutableListOf<String?>()
        var polls = 0
        val relay = object : RoomTransport {
            override suspend fun publish(code: String, message: RoomMessage) = Unit
            override suspend fun latest(code: String): RoomEvent { polls++; return RoomEvent(first, "cache") }
            override fun subscribe(code: String, after: String?) = flow<RoomEvent> {
                resumed += after
                if (resumed.size == 1) {
                    emit(RoomEvent(second, "live"))
                    throw RoomTransportException(false, "disconnected")
                }
                awaitCancellation()
            }
        }
        val controller = RoomController(relay, Playback(RoomPlaybackState()), backgroundScope) { clock + testScheduler.currentTime }
        controller.join("ABCDEFGHJK"); runCurrent()
        assertEquals(second, controller.state.value.host)
        assertTrue(controller.state.value.connecting)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf("cache", "live"), resumed)
        assertEquals(1, polls)
        assertEquals(second, controller.state.value.host)
        assertNull(controller.state.value.error)
        controller.leave()
    }

    @Test fun rate_limit_reconnect_waits_a_full_minute() = runTest {
        val relay = Relay().apply { failure = RoomTransportException(true, "busy") }
        val controller = RoomController(relay, Playback(RoomPlaybackState()), backgroundScope) { clock + testScheduler.currentTime }
        controller.join("ABCDEFGHJK"); runCurrent()
        relay.failure = null
        advanceTimeBy(59_999); runCurrent()
        assertEquals(1, relay.polls)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, relay.polls)
        assertNull(controller.state.value.error)
        controller.leave()
    }

    @Test fun successful_manual_sync_clears_a_previous_playback_error() = runTest {
        val message = RoomMessage(song.id, song.title, song.artist, positionMs = 0, playing = false, timestamp = clock)
        val playback = Playback(RoomPlaybackState()).apply { failure = java.io.IOException("playback") }
        val controller = RoomController(Relay(RoomEvent(message)), playback, backgroundScope) { clock }
        controller.join("ABCDEFGHJK"); runCurrent()
        controller.syncNow(); runCurrent()
        assertEquals(RoomError.PLAYBACK, controller.state.value.error)
        assertFalse(controller.state.value.syncing)
        playback.failure = null
        controller.syncNow(); runCurrent()
        assertNull(controller.state.value.error)
        assertTrue(controller.state.value.hasSynced)
        controller.leave()
    }

    @Test fun joining_another_room_requires_fresh_follow_consent() = runTest {
        val message = RoomMessage(song.id, song.title, song.artist, positionMs = 0, playing = false, timestamp = clock)
        val playback = Playback(RoomPlaybackState())
        val controller = RoomController(Relay(RoomEvent(message)), playback, backgroundScope) { clock }
        controller.join("ABCDEFGHJK"); runCurrent()
        controller.followAutomatically(true); runCurrent()
        assertEquals(1, playback.syncs.size)
        controller.join("KLMNPQRSTU"); runCurrent()
        assertFalse(controller.state.value.followAutomatically)
        assertFalse(controller.state.value.hasSynced)
        assertEquals(1, playback.syncs.size)
        controller.leave()
    }

    @Test fun a_late_old_sync_failure_cannot_change_a_new_room() = runTest {
        val message = RoomMessage(song.id, song.title, song.artist, positionMs = 0, playing = false, timestamp = clock)
        val playback = Playback(RoomPlaybackState()).apply { syncDelay = 100; failure = java.io.IOException("late") }
        val controller = RoomController(Relay(RoomEvent(message)), playback, backgroundScope) { clock }
        controller.join("ABCDEFGHJK"); runCurrent()
        controller.syncNow(); runCurrent()
        controller.join("KLMNPQRSTU"); runCurrent()
        advanceTimeBy(100); runCurrent()
        assertEquals("KLMNPQRSTU", controller.state.value.code)
        assertNull(controller.state.value.error)
        assertFalse(controller.state.value.hasSynced)
        assertFalse(controller.state.value.syncing)
        controller.leave()
    }

    @Test fun one_shared_room_delivers_host_changes_to_multiple_friends() = runTest {
        val events = MutableSharedFlow<RoomEvent>(extraBufferCapacity = 10)
        var cached: RoomEvent? = null
        var publications = 0
        val relay = object : RoomTransport {
            override suspend fun publish(code: String, message: RoomMessage) {
                publications++
                cached = RoomEvent(message, "event$publications")
                events.emit(checkNotNull(cached))
            }
            override suspend fun latest(code: String) = cached
            override fun subscribe(code: String, after: String?) = events
        }
        val host = RoomController(relay, Playback(RoomPlaybackState(song, listOf(song))), backgroundScope) { clock + testScheduler.currentTime }
        host.create(); runCurrent()
        val playbacks = List(3) { Playback(RoomPlaybackState()) }
        val friends = playbacks.map { RoomController(relay, it, backgroundScope) { clock + testScheduler.currentTime } }
        friends.forEach { it.join(checkNotNull(host.state.value.code)) }; runCurrent()
        friends.forEach { it.followAutomatically(true) }; runCurrent()
        assertTrue(playbacks.all { it.syncs.size == 1 })
        host.seeked(8_000); runCurrent()
        assertTrue(playbacks.all { it.syncs.last().second == 8_000L })
        friends.first().leave()
        host.seeked(12_000); runCurrent()
        assertNull(friends.first().state.value.role)
        assertTrue(playbacks.drop(1).all { it.syncs.last().second == 12_000L })
        assertEquals(3, publications) // Only the host publishes, regardless of the guest count.
        friends.forEach { it.leave() }; host.leave()
    }

    @Test fun editing_an_invalid_code_clears_the_inline_error() = runTest {
        val controller = RoomController(Relay(), Playback(RoomPlaybackState()), backgroundScope) { clock }
        controller.join("bad")
        assertEquals(RoomError.INVALID_CODE, controller.state.value.error)
        controller.clearCodeError()
        assertNull(controller.state.value.error)
        assertNull(controller.state.value.role)
    }
}

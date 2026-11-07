package app.dkord

import app.dkord.core.DebugLog
import app.dkord.core.Track
import app.dkord.data.room.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        override fun current() = states.value
        override suspend fun sync(message: RoomMessage, positionMs: Long) { syncs += message to positionMs }
    }

    private class Relay(var cached: RoomEvent? = null) : RoomTransport {
        val live = MutableSharedFlow<RoomEvent>(extraBufferCapacity = 10)
        val sent = mutableListOf<RoomMessage>()
        var resume: String? = null
        var failure: Exception? = null
        override suspend fun publish(code: String, message: RoomMessage) { failure?.let { throw it }; sent += message }
        override suspend fun latest(code: String): RoomEvent? { failure?.let { throw it }; return cached }
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
}

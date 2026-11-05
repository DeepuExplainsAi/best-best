package app.dkord

import app.dkord.core.Track
import app.dkord.player.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Part3Test {
    private class FakePlayer : SleepTarget {
        var vol = 1f; var paused = false; var pauseAtEnd = false
        override fun setVolume(volume: Float) { vol = volume }
        override fun pause() { paused = true }
        override fun setPauseAtEndOfTrack(enabled: Boolean) { pauseAtEnd = enabled }
    }

    @Test fun sleep_countdown_fades_then_pauses_and_restores_volume() = runTest {
        val p = FakePlayer(); val t = SleepTimer(TestScope(testScheduler), fadeMs = 8_000); t.attach(p)
        t.start(1)
        advanceTimeBy(30_000); assertFalse(p.paused); assertEquals(1f, p.vol, 0f)
        advanceTimeBy(26_000) // 56s in: 4s left, inside the fade window
        assertTrue("faded, was ${p.vol}", p.vol < 1f && p.vol > 0f)
        advanceTimeBy(5_000)
        assertTrue(p.paused); assertEquals(1f, p.vol, 0f); assertEquals(SleepState.Off, t.state.value)
    }

    @Test fun sleep_cancel_restores_volume_and_never_pauses() = runTest {
        val p = FakePlayer(); val t = SleepTimer(TestScope(testScheduler)); t.attach(p)
        t.start(1); advanceTimeBy(57_000); t.cancel(); advanceTimeBy(10_000)
        assertFalse(p.paused); assertEquals(1f, p.vol, 0f); assertEquals(SleepState.Off, t.state.value)
    }

    @Test fun sleep_restart_replaces_previous_timer() = runTest {
        val p = FakePlayer(); val t = SleepTimer(TestScope(testScheduler)); t.attach(p)
        t.start(1); advanceTimeBy(30_000); t.start(5); advanceTimeBy(40_000)
        assertFalse(p.paused); assertTrue(t.state.value is SleepState.Countdown)
    }

    @Test fun sleep_end_of_track_flag_set_and_cleared() = runTest {
        val p = FakePlayer(); val t = SleepTimer(TestScope(testScheduler)); t.attach(p)
        t.startEndOfTrack(); assertTrue(p.pauseAtEnd); assertEquals(SleepState.EndOfTrack, t.state.value)
        t.onTrackEnded(); assertFalse(p.pauseAtEnd); assertEquals(SleepState.Off, t.state.value)
        t.onTrackEnded() // harmless when not armed
        t.startEndOfTrack(); t.cancel(); assertFalse(p.pauseAtEnd)
    }

    private fun tr(i: Int) = Track("id$i", "T$i", "A$i", "Al", 100L + i, "https://img/$i")

    @Test fun queue_roundtrip_and_corrupt_input() {
        val q = SavedQueue.of((0 until 3).map(::tr), 2, 12_345)
        val back = SavedQueue.decode(q.encode())!!
        assertEquals(q, back)
        assertNull(SavedQueue.decode(null)); assertNull(SavedQueue.decode("not json")); assertNull(SavedQueue.decode("""{"tracks":[],"index":0,"positionMs":0}"""))
    }

    @Test fun queue_decode_clamps_bad_index() {
        val s = SavedQueue(listOf(tr(0), tr(1)), 99, -5).encode()
        val q = SavedQueue.decode(s)!!; assertEquals(1, q.index); assertEquals(0, q.positionMs)
    }

    @Test fun huge_queue_is_windowed_around_current_track() {
        val all = (0 until 2000).map(::tr)
        val q = SavedQueue.of(all, 1500, 0)
        assertEquals(SavedQueue.MAX_TRACKS, q.tracks.size); assertEquals("id1500", q.tracks[q.index].id)
        val end = SavedQueue.of(all, 1999, 0); assertEquals("id1999", end.tracks[end.index].id)
        val start = SavedQueue.of(all, 0, 0); assertEquals("id0", start.tracks[start.index].id)
    }
}

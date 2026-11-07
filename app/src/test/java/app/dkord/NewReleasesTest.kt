package app.dkord

import app.dkord.core.Track
import app.dkord.data.releases.DatedTrack
import app.dkord.data.releases.NewReleasesRepository
import app.dkord.data.releases.ReleaseWindow
import app.dkord.data.releases.releasesInWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NewReleasesTest {
    private val now = 1_800_000_000_000L
    private fun track(id: String) = Track(id, "Song $id", "Artist")
    private fun dated(id: String, age: Long) = DatedTrack(track(id), now - age)

    @Test fun exact_24_hour_boundary_future_missing_and_old_dates() {
        val day = ReleaseWindow.LAST_24_HOURS.durationMs
        val songs = listOf(dated("old", day + 1), dated("edge", day), dated("new", 1),
            dated("future", -1), DatedTrack(track("missing"), 0))
        assertEquals(listOf("new", "edge"), releasesInWindow(songs, ReleaseWindow.LAST_24_HOURS, now).map { it.id })
    }

    @Test fun week_window_sorts_and_deduplicates_by_newest_upload() {
        val week = ReleaseWindow.LAST_7_DAYS.durationMs
        val songs = listOf(dated("a", week), dated("b", week + 1), dated("a", 2), dated("c", 1))
        assertEquals(listOf("c", "a"), releasesInWindow(songs, ReleaseWindow.LAST_7_DAYS, now).map { it.id })
    }

    @Test fun candidates_are_lazy_capped_deduplicated_and_dates_limited_to_six() = runTest {
        var loads = 0
        var active = 0
        var peak = 0
        var fetched = 0
        val repo = NewReleasesRepository(
            sources = listOf({ loads++; (0..60).map { track("$it") } }, { (0..60).map { track("$it") } }),
            uploadDate = { active++; peak = maxOf(peak, active); fetched++; delay(10); active--; now - 1 },
            now = { now }, elapsed = { testScheduler.currentTime },
        )
        assertEquals(0, loads)
        assertEquals(40, repo.songs(ReleaseWindow.LAST_7_DAYS).size)
        assertEquals(40, fetched)
        assertEquals(6, peak)
        assertEquals(1, loads)
    }

    @Test fun both_windows_share_a_30_minute_memory_cache() = runTest {
        var loads = 0
        var elapsed = 0L
        val repo = NewReleasesRepository(
            sources = listOf({ loads++; listOf(track("a")) }), uploadDate = { now - 1 },
            now = { now }, elapsed = { elapsed },
        )
        repo.songs(ReleaseWindow.LAST_24_HOURS)
        elapsed = NewReleasesRepository.CACHE_MS - 1
        repo.songs(ReleaseWindow.LAST_7_DAYS)
        assertEquals(1, loads)
        elapsed++
        repo.songs(ReleaseWindow.LAST_24_HOURS)
        assertEquals(2, loads)
    }

    @Test fun missing_upload_dates_are_not_guessed() = runTest {
        val repo = NewReleasesRepository(listOf({ listOf(track("a"), track("b")) }), { null }, { now })
        assertTrue(repo.songs(ReleaseWindow.LAST_7_DAYS).isEmpty())
    }
}

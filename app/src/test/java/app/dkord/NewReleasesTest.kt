package app.dkord

import app.dkord.core.Track
import app.dkord.data.releases.DatedTrack
import app.dkord.data.releases.NewReleasesRepository
import app.dkord.data.releases.ReleaseWindow
import app.dkord.data.releases.ReleaseCategory
import app.dkord.data.releases.ReleaseDatesUnavailable
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
            sources = listOf({ _ -> loads++; (0..60).map { track("$it") } }, { _ -> (0..60).map { track("$it") } }),
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
            sources = listOf({ _ -> loads++; listOf(track("a")) }), uploadDate = { now - 1 },
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
        val repo = NewReleasesRepository(listOf({ _ -> listOf(track("a"), track("b")) }), { null }, { now })
        try { repo.songs(ReleaseWindow.LAST_7_DAYS); fail("Missing dates must not masquerade as no releases") }
        catch (_: ReleaseDatesUnavailable) { }
    }

    @Test fun ten_day_window_includes_day_eight_but_never_day_eleven() {
        val day = ReleaseWindow.LAST_24_HOURS.durationMs
        val songs = listOf(dated("day8", 8 * day), dated("day10", 10 * day), dated("day11", 11 * day))
        assertEquals(listOf("day8", "day10"), releasesInWindow(songs, ReleaseWindow.LAST_10_DAYS, now).map { it.id })
        assertTrue(releasesInWindow(songs, ReleaseWindow.LAST_7_DAYS, now).isEmpty())
    }

    @Test fun categories_have_independent_caches_and_windows_share_only_their_own_category() = runTest {
        val calls = mutableListOf<ReleaseCategory>()
        val repo = NewReleasesRepository(listOf({ category -> calls += category; listOf(track(category.name)) }), { now - 1 }, { now })
        assertEquals("HOLLYWOOD", repo.songs(ReleaseWindow.LAST_7_DAYS, ReleaseCategory.HOLLYWOOD).single().id)
        assertEquals("CHOLIWOOD", repo.songs(ReleaseWindow.LAST_7_DAYS, ReleaseCategory.CHOLIWOOD).single().id)
        repo.songs(ReleaseWindow.LAST_10_DAYS, ReleaseCategory.HOLLYWOOD)
        assertEquals(listOf(ReleaseCategory.HOLLYWOOD, ReleaseCategory.CHOLIWOOD), calls)
    }

    @Test fun all_missing_dates_are_retryable_and_not_cached_as_a_successful_empty_result() = runTest {
        var calls = 0
        var date: Long? = null
        val repo = NewReleasesRepository(listOf({ _ -> calls++; listOf(track("a")) }), { date }, { now })
        try { repo.songs(ReleaseWindow.LAST_7_DAYS); fail("Expected metadata error") }
        catch (_: ReleaseDatesUnavailable) { }
        date = now - 1
        assertEquals("a", repo.songs(ReleaseWindow.LAST_7_DAYS).single().id)
        assertEquals(2, calls)
    }

    @Test fun true_empty_results_expire_quickly_and_retry_can_bypass_the_cache() = runTest {
        var calls = 0
        var elapsed = 0L
        val repo = NewReleasesRepository(listOf({ _ -> calls++; emptyList<Track>() }), { null }, { now }, { elapsed })
        repo.songs(ReleaseWindow.LAST_7_DAYS)
        elapsed = NewReleasesRepository.EMPTY_CACHE_MS - 1
        repo.songs(ReleaseWindow.LAST_10_DAYS)
        assertEquals(1, calls)
        elapsed++
        repo.songs(ReleaseWindow.LAST_7_DAYS)
        assertEquals(2, calls)
        repo.songs(ReleaseWindow.LAST_7_DAYS, refresh = true)
        assertEquals(3, calls)
    }

    @Test fun one_failed_date_does_not_hide_other_known_recent_songs() = runTest {
        val repo = NewReleasesRepository(listOf({ _ -> listOf(track("missing"), track("known")) }),
            { if (it.id == "missing") error("blocked") else now - 1 }, { now })
        assertEquals(listOf("known"), repo.songs(ReleaseWindow.LAST_7_DAYS).map { it.id })
    }


    @Test fun failed_sources_with_an_empty_source_are_not_reported_as_no_releases() = runTest {
        val repo = NewReleasesRepository(listOf({ _ -> error("offline") }, { _ -> emptyList<Track>() }), { null }, { now })
        try { repo.songs(ReleaseWindow.LAST_7_DAYS); fail("Expected source error") }
        catch (_: java.io.IOException) { }
    }

}

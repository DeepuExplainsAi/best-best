package app.dkord

import app.dkord.data.search.*
import app.dkord.data.yt.SearchFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchHistoryRepositoryTest {
    private val songs = SearchFilter.SONGS

    @Test fun a_new_repository_restores_recorded_queries_filters_and_pins() = runTest {
        val storage = MemorySearchStorage()
        val first = SearchHistoryRepository(storage, backgroundScope)
        first.record("Arijit", SearchFilter.ALBUMS); runCurrent()
        first.togglePinned(first.state.value.entries.single()); runCurrent()
        val restored = SearchHistoryRepository(storage, backgroundScope); runCurrent()
        assertFalse(restored.state.value.loading)
        assertEquals(listOf(SavedSearch("Arijit", SearchFilter.ALBUMS, true)), restored.state.value.entries)
    }

    @Test fun clear_runs_after_older_pending_writes_and_does_not_resurrect_history() = runTest {
        val storage = MemorySearchStorage().apply { writeDelay = 100 }
        val repo = SearchHistoryRepository(storage, backgroundScope)
        repo.record("First", songs); repo.record("Second", songs); repo.clearAll(); runCurrent()
        advanceTimeBy(300); runCurrent()
        assertTrue(repo.state.value.entries.isEmpty())
        assertNull(storage.raw.value)
        repo.record("After clear", songs); runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(listOf("After clear"), repo.state.value.entries.map { it.query })
    }

    @Test fun removing_one_filter_shortcut_keeps_the_other_and_other_queries() = runTest {
        val storage = MemorySearchStorage(listOf(SavedSearch("Artist", songs), SavedSearch("Artist", SearchFilter.ALBUMS), SavedSearch("Other", songs)))
        val repo = SearchHistoryRepository(storage, backgroundScope); runCurrent()
        repo.remove(repo.state.value.entries.first()); runCurrent()
        assertEquals(listOf(SavedSearch("Artist", SearchFilter.ALBUMS), SavedSearch("Other", songs)), repo.state.value.entries)
    }

    @Test fun failed_writes_leave_existing_history_intact_and_the_queue_can_recover() = runTest {
        val storage = MemorySearchStorage(listOf(SavedSearch("Keep", songs)))
        val repo = SearchHistoryRepository(storage, backgroundScope); runCurrent()
        storage.failWrite = true
        val notice = async { repo.notices.first() }
        repo.record("Failed", songs); runCurrent()
        assertEquals(SearchHistoryNotice.WRITE_FAILED, notice.await())
        assertEquals(listOf("Keep"), repo.state.value.entries.map { it.query })
        storage.failWrite = false
        repo.record("Recovered", songs); runCurrent()
        assertEquals(listOf("Recovered", "Keep"), repo.state.value.entries.map { it.query })
    }

    @Test fun failed_clear_does_not_claim_history_was_removed() = runTest {
        val storage = MemorySearchStorage(listOf(SavedSearch("Keep", songs, true)))
        val repo = SearchHistoryRepository(storage, backgroundScope); runCurrent()
        storage.failWrite = true
        val notice = async { repo.notices.first() }
        repo.clearAll(); runCurrent()
        assertEquals(SearchHistoryNotice.WRITE_FAILED, notice.await())
        assertEquals(listOf(SavedSearch("Keep", songs, true)), repo.state.value.entries)
    }

    @Test fun read_failure_is_retryable_and_not_a_successful_empty_history() = runTest {
        val storage = MemorySearchStorage(listOf(SavedSearch("Saved", songs))).apply { failRead = true }
        val repo = SearchHistoryRepository(storage, backgroundScope); runCurrent()
        assertTrue(repo.state.value.loadFailed)
        assertFalse(repo.state.value.loading)
        storage.failRead = false
        repo.reload(); runCurrent()
        assertFalse(repo.state.value.loadFailed)
        assertEquals("Saved", repo.state.value.entries.single().query)
    }

    @Test fun corrupt_history_is_not_overwritten_by_record_and_explicit_clear_repairs_it() = runTest {
        val storage = MemorySearchStorage().apply { raw.value = "corrupt private data" }
        val repo = SearchHistoryRepository(storage, backgroundScope); runCurrent()
        assertTrue(repo.state.value.loadFailed)
        val notice = async { repo.notices.first() }
        repo.record("New", songs); runCurrent()
        assertEquals(SearchHistoryNotice.WRITE_FAILED, notice.await())
        assertEquals("corrupt private data", storage.raw.value)
        repo.clearAll(); runCurrent()
        assertFalse(repo.state.value.loadFailed)
        assertTrue(repo.state.value.entries.isEmpty())
        repo.record("After repair", songs); runCurrent()
        assertEquals("After repair", repo.state.value.entries.single().query)
    }

    @Test fun clear_restarts_a_pending_read_before_its_old_corrupt_document_can_fail() = runTest {
        val storage = MemorySearchStorage().apply { raw.value = "corrupt"; readDelay = 100 }
        val repo = SearchHistoryRepository(storage, backgroundScope); runCurrent()
        assertTrue(repo.state.value.loading)
        repo.clearAll(); runCurrent()
        advanceTimeBy(100); runCurrent()
        assertFalse(repo.state.value.loadFailed)
        assertFalse(repo.state.value.loading)
        assertTrue(repo.state.value.entries.isEmpty())
    }

    @Test fun pin_limit_emits_an_actionable_notice_without_losing_entries() = runTest {
        val entries = (0 until MAX_PINNED_SEARCHES).map { SavedSearch("Pin $it", songs, true) } + SavedSearch("Extra", songs)
        val repo = SearchHistoryRepository(MemorySearchStorage(entries), backgroundScope); runCurrent()
        val notice = async { repo.notices.first() }
        repo.togglePinned(entries.last()); runCurrent()
        assertEquals(SearchHistoryNotice.PIN_LIMIT, notice.await())
        assertEquals(entries, repo.state.value.entries)
    }

    @Test fun blank_queries_do_not_write_and_quick_repeats_are_deduplicated() = runTest {
        val storage = MemorySearchStorage()
        val repo = SearchHistoryRepository(storage, backgroundScope)
        repo.record(" ", songs); runCurrent()
        assertEquals(0, storage.writes)
        repo.record("Song", songs); repo.record("SONG", songs); repo.record("Other", songs); runCurrent()
        assertEquals(listOf("Other", "SONG"), repo.state.value.entries.map { it.query })
    }

    @Test fun repeated_pin_or_unpin_clicks_from_a_stale_menu_are_idempotent() = runTest {
        val entry = SavedSearch("Favourite", songs)
        val repo = SearchHistoryRepository(MemorySearchStorage(listOf(entry)), backgroundScope); runCurrent()
        repo.togglePinned(entry); repo.togglePinned(entry); runCurrent()
        val pinned = repo.state.value.entries.single()
        assertTrue(pinned.pinned)
        repo.togglePinned(pinned); repo.togglePinned(pinned); runCurrent()
        assertFalse(repo.state.value.entries.single().pinned)
    }

    @Test fun clear_all_includes_pinned_queries() = runTest {
        val storage = MemorySearchStorage(listOf(SavedSearch("Pin", songs, true), SavedSearch("Recent", songs)))
        val repo = SearchHistoryRepository(storage, backgroundScope); runCurrent()
        storage.readDelay = 100
        repo.clearAll(); runCurrent()
        // Once deletion commits, even the cached UI snapshot must drop private queries immediately.
        assertTrue(repo.state.value.entries.isEmpty())
        assertNull(storage.raw.value)
        assertTrue(repo.state.value.loading)
        advanceTimeBy(100); runCurrent()
        assertFalse(repo.state.value.loading)
    }
}

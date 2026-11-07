package app.dkord

import app.dkord.core.BrowseItem
import app.dkord.core.ItemKind
import app.dkord.core.Track
import app.dkord.data.search.*
import app.dkord.data.yt.SearchFilter
import app.dkord.ui.common.Load
import app.dkord.ui.search.SearchController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SearchControllerTest {
    private data class Token(val name: String) : SearchPageToken
    private class Source : SearchSource {
        val searches = mutableListOf<Pair<String, SearchFilter>>()
        val suggested = mutableListOf<String>()
        val pages = mutableListOf<SearchPageToken>()
        var suggest: suspend (String) -> List<String> = { listOf("$it suggestion") }
        var find: suspend (String, SearchFilter) -> SearchPage = { query, _ -> SearchPage(listOf(song(query))) }
        var next: suspend (SearchPageToken) -> SearchPage = { SearchPage(emptyList()) }
        override suspend fun suggestions(query: String): List<String> { suggested += query; return suggest(query) }
        override suspend fun search(query: String, filter: SearchFilter): SearchPage { searches += query to filter; return find(query, filter) }
        override suspend fun more(token: SearchPageToken): SearchPage { pages += token; return next(token) }
    }
    private data class Fixture(val source: Source, val storage: MemorySearchStorage, val history: SearchHistoryRepository, val controller: SearchController)
    private fun TestScope.fixture(seed: List<SavedSearch> = emptyList()): Fixture {
        val source = Source()
        val storage = MemorySearchStorage(seed)
        val history = SearchHistoryRepository(storage, backgroundScope)
        return Fixture(source, storage, history, SearchController(source, history, backgroundScope))
    }
    private fun SearchController.ids() = (results.value as Load.Ok).value.map { it.id }
    private companion object {
        fun song(id: String) = BrowseItem(ItemKind.SONG, id, id, track = Track(id, id, "Artist"))
    }

    @Test fun typing_is_debounced_and_never_records_a_search() = runTest {
        val f = fixture()
        f.controller.typed("ar"); runCurrent(); advanceTimeBy(200)
        f.controller.typed("ari"); runCurrent(); advanceTimeBy(249); runCurrent()
        assertTrue(f.source.suggested.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("ari"), f.source.suggested)
        assertTrue(f.source.searches.isEmpty())
        assertEquals(0, f.storage.writes)
        assertTrue(f.history.state.value.entries.isEmpty())
    }

    @Test fun submission_normalizes_and_records_once_not_on_result_completion() = runTest {
        val f = fixture()
        f.controller.typed("  Arijit   Singh  "); f.controller.search(); runCurrent()
        assertEquals("Arijit Singh", f.controller.query.value)
        assertEquals(listOf("Arijit Singh" to SearchFilter.SONGS), f.source.searches)
        assertEquals(listOf("Arijit Singh"), f.controller.ids())
        assertEquals(listOf(SavedSearch("Arijit Singh", SearchFilter.SONGS)), f.history.state.value.entries)
        assertEquals(1, f.storage.writes)
        advanceTimeBy(1000); runCurrent()
        assertTrue(f.source.suggested.isEmpty())
        assertEquals(1, f.storage.writes)
    }

    @Test fun repeat_uses_the_saved_query_and_original_filter_and_preserves_its_pin() = runTest {
        val entry = SavedSearch("Collection", SearchFilter.PLAYLISTS, true)
        val f = fixture(listOf(entry))
        f.controller.repeat(entry); runCurrent()
        assertEquals("Collection", f.controller.query.value)
        assertEquals(SearchFilter.PLAYLISTS, f.controller.filter.value)
        assertEquals(listOf("Collection" to SearchFilter.PLAYLISTS), f.source.searches)
        assertEquals(listOf(entry), f.history.state.value.entries)
    }

    @Test fun edit_prefills_without_a_search_or_recency_write() = runTest {
        val entries = listOf(SavedSearch("New", SearchFilter.SONGS), SavedSearch("Old", SearchFilter.ALBUMS, true))
        val f = fixture(entries)
        f.controller.edit(entries.last()); runCurrent()
        assertEquals("Old", f.controller.query.value)
        assertEquals(SearchFilter.ALBUMS, f.controller.filter.value)
        assertNull(f.controller.results.value)
        advanceTimeBy(250); runCurrent()
        assertEquals(listOf("Old"), f.source.suggested)
        assertTrue(f.source.searches.isEmpty())
        assertEquals(0, f.storage.writes)
        assertEquals(entries, f.history.state.value.entries)
    }

    @Test fun filter_changes_search_only_if_the_current_query_was_submitted() = runTest {
        val f = fixture()
        f.controller.typed("Artist"); f.controller.setFilter(SearchFilter.ALBUMS); runCurrent()
        assertTrue(f.source.searches.isEmpty())
        assertTrue(f.history.state.value.entries.isEmpty())
        f.controller.search(); runCurrent()
        f.controller.setFilter(SearchFilter.ARTISTS); runCurrent()
        assertEquals(listOf("Artist" to SearchFilter.ALBUMS, "Artist" to SearchFilter.ARTISTS), f.source.searches)
        assertEquals(listOf(SearchFilter.ARTISTS, SearchFilter.ALBUMS), f.history.state.value.entries.map { it.filter })
    }

    @Test fun repeated_submit_during_loading_does_not_duplicate_requests_or_writes() = runTest {
        val f = fixture()
        f.source.find = { query, _ -> delay(100); SearchPage(listOf(song(query))) }
        f.controller.search("Same"); f.controller.search("Same"); runCurrent()
        assertEquals(Load.Loading, f.controller.results.value)
        assertEquals(1, f.source.searches.size)
        assertEquals(1, f.storage.writes)
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("Same"), f.controller.ids())
    }

    @Test fun old_uncancellable_search_cannot_replace_a_newer_query_or_filter() = runTest {
        val f = fixture()
        f.source.find = { _, filter ->
            withContext(NonCancellable) { delay(if (filter == SearchFilter.SONGS) 100 else 10) }
            SearchPage(listOf(song(filter.name)))
        }
        f.controller.search("Artist"); runCurrent()
        f.controller.setFilter(SearchFilter.ALBUMS); runCurrent()
        advanceTimeBy(10); runCurrent()
        assertEquals(listOf("ALBUMS"), f.controller.ids())
        advanceTimeBy(90); runCurrent()
        assertEquals(listOf("ALBUMS"), f.controller.ids())
        assertEquals(SearchFilter.ALBUMS, f.controller.filter.value)
    }

    @Test fun late_suggestions_cannot_replace_newer_normalized_deduplicated_suggestions() = runTest {
        val f = fixture()
        f.source.suggest = { query ->
            if (query == "old") { withContext(NonCancellable) { delay(1000) }; listOf("Stale") }
            else { delay(1); listOf(" New ", "new", " ", "Other", "other") }
        }
        f.controller.typed("old"); runCurrent(); advanceTimeBy(250); runCurrent()
        f.controller.typed("new"); runCurrent(); advanceTimeBy(250); runCurrent()
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("New", "Other"), f.controller.suggestions.value)
        advanceTimeBy(749); runCurrent()
        assertEquals(listOf("New", "Other"), f.controller.suggestions.value)
        assertEquals(0, f.storage.writes)
    }

    @Test fun remote_suggestions_are_bounded() = runTest {
        val f = fixture()
        f.source.suggest = { (0..30).map { "Suggestion $it" } }
        f.controller.typed("s"); runCurrent(); advanceTimeBy(250); runCurrent()
        assertEquals(8, f.controller.suggestions.value.size)
    }

    @Test fun clearing_input_cancels_late_results_but_does_not_delete_saved_history() = runTest {
        val f = fixture()
        f.source.find = { query, _ -> withContext(NonCancellable) { delay(100) }; SearchPage(listOf(song(query))) }
        f.controller.search("Slow"); runCurrent()
        f.controller.clear(); runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals("", f.controller.query.value)
        assertNull(f.controller.results.value)
        assertTrue(f.controller.suggestions.value.isEmpty())
        assertFalse(f.controller.loadingMore.value)
        assertEquals(listOf("Slow"), f.history.state.value.entries.map { it.query })
    }

    @Test fun editing_a_submitted_query_replaces_results_with_suggestions_without_saving_keystrokes() = runTest {
        val f = fixture()
        f.controller.search("First"); runCurrent()
        f.controller.typed("Changed"); runCurrent()
        assertNull(f.controller.results.value)
        assertEquals(listOf("First"), f.history.state.value.entries.map { it.query })
        assertEquals(1, f.source.searches.size)
    }

    @Test fun pagination_deduplicates_by_kind_and_id_and_does_not_change_history() = runTest {
        val f = fixture()
        val first = Token("first"); val second = Token("second")
        f.source.find = { _, _ -> SearchPage(listOf(song("one"), song("one"), BrowseItem(ItemKind.ALBUM, "one", "Album")), first) }
        f.source.next = { token -> delay(10); if (token == first) SearchPage(listOf(song("one"), song("two")), second) else SearchPage(listOf(song("three"))) }
        f.controller.search("Query"); runCurrent()
        assertEquals(listOf("one", "one"), f.controller.ids())
        f.controller.loadMore(); f.controller.loadMore(); runCurrent()
        assertTrue(f.controller.loadingMore.value)
        assertEquals(1, f.source.pages.size)
        advanceTimeBy(10); runCurrent()
        assertEquals(listOf("one", "one", "two"), f.controller.ids())
        f.controller.loadMore(); runCurrent(); advanceTimeBy(10); runCurrent()
        f.controller.loadMore(); runCurrent()
        assertEquals(listOf("one", "one", "two", "three"), f.controller.ids())
        assertEquals(2, f.source.pages.size)
        assertEquals(1, f.storage.writes)
    }

    @Test fun an_empty_page_keeps_a_valid_continuation_available_for_manual_loading() = runTest {
        val f = fixture()
        val first = Token("first"); val second = Token("second")
        f.source.find = { _, _ -> SearchPage(listOf(song("one")), first) }
        f.source.next = { if (it == first) SearchPage(emptyList(), second) else SearchPage(listOf(song("two"))) }
        f.controller.search("Query"); runCurrent()
        f.controller.loadMore(); runCurrent()
        assertEquals(listOf("one"), f.controller.ids())
        assertTrue(f.controller.hasMore.value)
        f.controller.loadMore(); runCurrent()
        assertEquals(listOf("one", "two"), f.controller.ids())
        assertFalse(f.controller.hasMore.value)
    }

    @Test fun a_reused_identical_page_token_stops_pagination() = runTest {
        val f = fixture()
        val token = Token("same")
        f.source.find = { _, _ -> SearchPage(listOf(song("one")), token) }
        f.source.next = { SearchPage(listOf(song("two")), token) }
        f.controller.search("Query"); runCurrent()
        f.controller.loadMore(); runCurrent()
        assertFalse(f.controller.hasMore.value)
        f.controller.loadMore(); runCurrent()
        assertEquals(1, f.source.pages.size)
    }

    @Test fun failed_pagination_keeps_rows_and_cursor_and_requires_explicit_retry() = runTest {
        val f = fixture()
        f.source.find = { _, _ -> SearchPage(listOf(song("one")), Token("next")) }
        f.source.next = { if (f.source.pages.size == 1) throw IOException("offline"); SearchPage(listOf(song("two"))) }
        f.controller.search("Query"); runCurrent()
        f.controller.loadMore(); runCurrent()
        assertEquals(listOf("one"), f.controller.ids())
        assertTrue(f.controller.moreFailed.value)
        assertFalse(f.controller.loadingMore.value)
        repeat(5) { f.controller.loadMore() }; runCurrent()
        assertEquals(1, f.source.pages.size)
        f.controller.loadMore(retry = true); runCurrent()
        assertEquals(listOf("one", "two"), f.controller.ids())
        assertFalse(f.controller.moreFailed.value)
        assertEquals(2, f.source.pages.size)
        assertEquals(1, f.storage.writes)
    }

    @Test fun stale_pagination_cannot_append_to_or_clear_loading_for_a_new_search() = runTest {
        val f = fixture()
        val old = Token("old"); val fresh = Token("new")
        f.source.find = { query, _ -> SearchPage(listOf(song(query)), if (query == "old") old else fresh) }
        f.source.next = { token ->
            withContext(NonCancellable) { delay(if (token == old) 100 else 200) }
            SearchPage(listOf(song(if (token == old) "stale" else "new-page")))
        }
        f.controller.search("old"); runCurrent(); f.controller.loadMore(); runCurrent()
        f.controller.search("new"); runCurrent(); f.controller.loadMore(); runCurrent()
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("new"), f.controller.ids())
        assertTrue(f.controller.loadingMore.value)
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("new", "new-page"), f.controller.ids())
        assertFalse(f.controller.loadingMore.value)
        assertFalse(f.controller.moreFailed.value)
    }

    @Test fun suggestion_failure_is_quiet_and_failed_submitted_search_is_still_saved_and_retryable() = runTest {
        val f = fixture()
        f.source.suggest = { throw IOException("offline") }
        f.source.find = { _, _ -> throw IOException("offline") }
        f.controller.typed("Offline"); runCurrent(); advanceTimeBy(250); runCurrent()
        assertTrue(f.controller.suggestions.value.isEmpty())
        assertNull(f.controller.results.value)
        f.controller.search(); runCurrent()
        assertTrue(f.controller.results.value is Load.Failed)
        assertEquals(listOf("Offline"), f.history.state.value.entries.map { it.query })
        f.source.find = { query, _ -> SearchPage(listOf(song(query))) }
        f.controller.search(); runCurrent()
        assertEquals(listOf("Offline"), f.controller.ids())
        assertEquals(1, f.history.state.value.entries.size)
    }

    @Test fun clearing_history_during_search_is_not_undone_when_results_arrive() = runTest {
        val f = fixture()
        f.source.find = { query, _ -> delay(100); SearchPage(listOf(song(query))) }
        f.controller.search("Slow"); runCurrent()
        f.history.clearAll(); runCurrent()
        assertTrue(f.history.state.value.entries.isEmpty())
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("Slow"), f.controller.ids())
        assertTrue(f.history.state.value.entries.isEmpty())
    }

    @Test fun blank_searches_are_ignored_and_long_input_is_bounded_before_network_and_storage() = runTest {
        val f = fixture()
        f.controller.search(" \t\n"); runCurrent()
        assertTrue(f.source.searches.isEmpty())
        assertEquals(0, f.storage.writes)
        f.controller.typed("x".repeat(1000))
        assertEquals(MAX_SEARCH_LENGTH, f.controller.query.value.length)
        f.controller.search(); runCurrent()
        assertEquals(MAX_SEARCH_LENGTH, f.source.searches.single().first.length)
        assertEquals(MAX_SEARCH_LENGTH, f.history.state.value.entries.single().query.length)
    }
}

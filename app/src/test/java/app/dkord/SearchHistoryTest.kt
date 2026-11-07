package app.dkord

import app.dkord.data.search.*
import app.dkord.data.yt.SearchFilter
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Locale

class SearchHistoryTest {
    private val songs = SearchFilter.SONGS
    private val albums = SearchFilter.ALBUMS

    @Test fun whitespace_is_normalized_and_blank_queries_are_not_saved() {
        assertEquals("Arijit Singh", normalizeSearchQuery(" \tArijit\u00a0  Singh\n "))
        assertTrue(rememberSearch(emptyList(), " \n\t", songs).isEmpty())
    }

    @Test fun resubmitting_a_case_variant_moves_one_entry_to_the_top_with_latest_display_text() {
        var saved = rememberSearch(emptyList(), "Arijit Singh", songs)
        saved = rememberSearch(saved, "Lo-fi", songs)
        saved = rememberSearch(saved, "  arijit   SINGH ", songs)
        assertEquals(listOf("arijit SINGH", "Lo-fi"), saved.map { it.query })
    }

    @Test fun the_same_query_in_different_filters_keeps_distinct_shortcuts() {
        val saved = rememberSearch(rememberSearch(emptyList(), "Arijit", songs), "Arijit", albums)
        assertEquals(listOf(albums, songs), saved.map { it.filter })
        assertNotEquals(saved.first().key, saved.last().key)
    }

    @Test fun pins_survive_repeat_searches_and_bounded_history_eviction() {
        var saved = listOf(SavedSearch("Favourite", songs, pinned = true))
        repeat(70) { saved = rememberSearch(saved, "Query $it", songs) }
        assertEquals(MAX_SAVED_SEARCHES, saved.size)
        assertTrue(saved.any { it.query == "Favourite" && it.pinned })
        assertEquals("Query 69", saved.first().query)
        saved = rememberSearch(saved, "favourite", songs)
        assertEquals("favourite", saved.first().query)
        assertTrue(saved.first().pinned)
        assertEquals(1, saved.count { it.query.equals("favourite", ignoreCase = true) })
    }

    @Test fun pin_limit_is_explicit_and_unpinning_frees_a_slot() {
        val pinned = (0 until MAX_PINNED_SEARCHES).map { SavedSearch("Pinned $it", songs, true) }
        val extra = SavedSearch("Extra", songs)
        val saved = pinned + extra
        try { toggleSearchPin(saved, extra.key); fail("Pin limit bypassed") }
        catch (_: SearchPinLimitException) { }
        val unpinned = toggleSearchPin(saved, pinned.first().key)
        assertEquals(MAX_PINNED_SEARCHES, toggleSearchPin(unpinned, extra.key).count { it.pinned })
    }

    @Test fun pinning_does_not_fake_a_new_search_or_change_recency_order() {
        val saved = listOf(SavedSearch("New", songs), SavedSearch("Old", songs))
        val changed = toggleSearchPin(saved, saved.last().key)
        assertEquals(saved.map { it.query }, changed.map { it.query })
        assertTrue(changed.last().pinned)
    }

    @Test fun local_matches_use_the_current_filter_and_rank_prefix_then_pin_then_recency() {
        val saved = listOf(SavedSearch("Live Arijit", songs, true), SavedSearch("Arijit album", albums),
            SavedSearch("Arijit new", songs), SavedSearch("Arijit favourite", songs, true))
        assertEquals(listOf("Arijit favourite", "Arijit new", "Live Arijit"), matchingSavedSearches(saved, "ari", songs).map { it.query })
        assertEquals(listOf("Arijit album"), matchingSavedSearches(saved, "ari", albums).map { it.query })
        assertTrue(matchingSavedSearches(saved, " ", songs).isEmpty())
    }

    @Test fun local_matches_are_bounded() {
        val saved = (0..20).map { SavedSearch("Artist $it", songs) }
        assertEquals(5, matchingSavedSearches(saved, "artist", songs).size)
    }

    @Test fun identities_are_locale_independent_and_normalize_unicode_width_without_changing_display() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(searchIdentity("INDIE"), searchIdentity("indie"))
            assertEquals(searchIdentity("ＦＵＮＫ"), searchIdentity("funk"))
            assertEquals("ＦＵＮＫ", normalizeSearchQuery("ＦＵＮＫ"))
        } finally { Locale.setDefault(original) }
    }

    @Test fun unicode_queries_filters_and_pins_round_trip() {
        val saved = listOf(SavedSearch("अरिजीत सिंह", songs, true), SavedSearch("Tamil இசை", albums),
            SavedSearch("Rock \"Live\" 🎵", SearchFilter.PLAYLISTS))
        assertEquals(saved, SearchHistoryCodec.decode(SearchHistoryCodec.encode(saved)))
        assertTrue(SearchHistoryCodec.decode(null).isEmpty())
    }

    @Test fun unknown_filter_entries_are_ignored_without_breaking_valid_history() {
        val json = """{"version":1,"entries":[{"query":"Unknown","filter":"FUTURE"},{"query":"Song","filter":"SONGS"}]}"""
        assertEquals(listOf(SavedSearch("Song", songs)), SearchHistoryCodec.decode(json))
    }

    @Test fun corrupt_future_and_oversized_documents_fail_instead_of_silently_becoming_empty() {
        for (json in listOf("not json", """{"version":2,"entries":[]}""", "x".repeat(128 * 1024 + 1))) {
            try { SearchHistoryCodec.decode(json); fail("Unreadable history accepted") }
            catch (_: IOException) { }
        }
    }

    @Test fun decoding_cleans_blank_duplicates_and_excess_pins_and_entries() {
        val rows = listOf("""{"query":" ","filter":"SONGS"}""", """{"query":" SONG 0 ","filter":"SONGS","pinned":true}""") +
            (0..70).map { """{"query":"Song $it","filter":"SONGS","pinned":true}""" }
        val saved = SearchHistoryCodec.decode("""{"version":1,"entries":[${rows.joinToString(",")}]}""")
        assertEquals(MAX_SAVED_SEARCHES, saved.size)
        assertEquals(MAX_PINNED_SEARCHES, saved.count { it.pinned })
        assertEquals(saved.size, saved.map { it.key }.distinct().size)
    }

    @Test fun normalization_is_idempotent_when_truncation_lands_on_a_space() {
        val normalized = normalizeSearchQuery("a".repeat(MAX_SEARCH_LENGTH - 1) + " b")
        assertEquals(normalized, normalizeSearchQuery(normalized))
        assertFalse(normalized.endsWith(" "))
    }

    @Test fun input_limits_do_not_split_an_emoji_surrogate_pair() {
        val text = "a".repeat(MAX_SEARCH_LENGTH - 1) + "🎵"
        val limited = limitSearchInput(text)
        assertEquals(MAX_SEARCH_LENGTH - 1, limited.length)
        assertFalse(limited.last().isHighSurrogate())
        assertEquals("🎵", limitSearchInput("🎵"))
    }
}

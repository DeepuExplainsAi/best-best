package app.dkord

import app.dkord.core.*
import app.dkord.data.releases.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReleaseDiscoveryTest {
    private fun album(id: String) = BrowseItem(ItemKind.ALBUM, id, "Album")
    private fun song(id: String) = Track(id, "Song $id", "Artist")

    @Test fun browse_album_releases_expand_to_actual_queueable_songs() = runTest {
        var browses = 0
        val discovery = ReleaseDiscovery(
            browseReleases = { browses++; Page(shelves = listOf(Shelf("New albums", listOf(album("a"))))) },
            findAlbums = { emptyList() }, loadAlbum = { Page(tracks = listOf(song("new"))) },
            findSongs = { emptyList() }, now = { 1_800_000_000_000 },
        )
        val sources = discovery.sources()
        assertEquals(0, browses)
        assertEquals(listOf("new"), sources.first()(ReleaseCategory.ALL).map { it.id })
        assertEquals(1, browses)
    }

    @Test fun a_category_uses_its_own_album_and_song_queries_not_the_unfiltered_browse_page() = runTest {
        val queries = mutableListOf<String>()
        val discovery = ReleaseDiscovery(
            browseReleases = { error("Unfiltered source must not run for a category") },
            findAlbums = { queries += it; listOf(album("a")) }, loadAlbum = { Page(tracks = listOf(song("album-song"))) },
            findSongs = { queries += it; listOf(song("search-song")) }, now = { 1_800_000_000_000 },
        )
        val tracks = discovery.sources().map { async { it(ReleaseCategory.CHOLIWOOD) } }.awaitAll().flatten()
        assertTrue(queries.all { "bhojpuri" in it })
        assertTrue(tracks.any { it.id == "album-song" })
        assertTrue(tracks.any { it.id == "search-song" })
    }

    @Test fun album_lookups_are_bounded_and_one_failed_album_does_not_erase_the_others() = runTest {
        var active = 0
        var peak = 0
        var calls = 0
        val discovery = ReleaseDiscovery(
            browseReleases = { Page(shelves = listOf(Shelf("", (0..8).map { album("$it") }))) },
            findAlbums = { emptyList() }, loadAlbum = { id ->
                calls++; active++; peak = maxOf(peak, active); delay(10); active--
                if (id == "0") error("unavailable") else Page(tracks = listOf(song(id)))
            }, findSongs = { emptyList() }, now = { 1_800_000_000_000 },
        )
        val result = discovery.sources().first()(ReleaseCategory.ALL)
        assertEquals(4, calls)
        assertEquals(3, peak)
        assertEquals(listOf("1", "2", "3"), result.map { it.id })
    }
}

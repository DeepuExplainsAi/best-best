package app.dkord.data.releases

import app.dkord.core.BrowseItem
import app.dkord.core.ItemKind
import app.dkord.core.Page
import app.dkord.core.Shelf
import app.dkord.core.Track
import app.dkord.data.yt.MusicRepository
import app.dkord.data.yt.SearchFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

/** Browse albums are expanded to songs; regular YouTube video search is never used. */
internal class ReleaseDiscovery(
    private val browseReleases: suspend () -> Page,
    private val findAlbums: suspend (String) -> List<BrowseItem>,
    private val loadAlbum: suspend (String) -> Page,
    private val findSongs: suspend (String) -> List<Track>,
    private val now: () -> Long,
) {
    private val albums = Semaphore(3)

    fun sources(): List<suspend (ReleaseCategory) -> List<Track>> {
        val releases: suspend (ReleaseCategory) -> List<Track> = { category ->
            if (category == ReleaseCategory.ALL) expand(browseReleases())
            else {
                val query = releaseQueries(category, now()).first().replace("songs", "albums")
                expand(Page(shelves = listOf(Shelf("", findAlbums(query)))))
            }
        }
        val searches = (0..3).map { index ->
            val search: suspend (ReleaseCategory) -> List<Track> = { category ->
                val query = releaseQueries(category, now()).getOrNull(index)
                if (query == null) emptyList() else findSongs(query)
            }
            search
        }
        return listOf(releases) + searches
    }

    private suspend fun expand(page: Page): List<Track> = coroutineScope {
        val items = page.shelves.flatMap { it.items }
        val direct = page.tracks + items.filter { it.kind == ItemKind.SONG }.mapNotNull { it.track }
        val releases = items.filter { it.kind == ItemKind.ALBUM }.distinctBy { it.id }.take(4)
        val albumTracks = releases.map { album -> async {
            albums.withPermit {
                try {
                    val result = loadAlbum(album.id)
                    Result.success(result.tracks + result.shelves.flatMap { shelf -> shelf.items.filter { it.kind == ItemKind.SONG }.mapNotNull { it.track } })
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Result.failure<List<Track>>(e) }
            }
        } }.awaitAll()
        if (direct.isEmpty() && albumTracks.isNotEmpty() && albumTracks.all { it.isFailure }) throw IOException("New release albums unavailable")
        (direct + albumTracks.flatMap { it.getOrDefault(emptyList()) }).distinctBy { it.id }
    }

    companion object {
        fun fromMusic(music: MusicRepository, now: () -> Long) = ReleaseDiscovery(
            browseReleases = music::newReleases,
            findAlbums = { music.search(it, SearchFilter.ALBUMS).items },
            loadAlbum = music::album,
            findSongs = { query ->
                val first = music.search(query, SearchFilter.SONGS)
                val tracks = first.items.mapNotNull { it.track }
                val more = first.next?.takeIf { tracks.size < NewReleasesRepository.MAX_CANDIDATES }?.let { cursor ->
                    try { music.searchMore(cursor).items.mapNotNull { it.track } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { emptyList() }
                }.orEmpty()
                tracks + more
            },
            now = now,
        )
    }
}

package app.dkord.data.yt

import app.dkord.core.BrowseItem
import app.dkord.core.ItemKind
import app.dkord.core.Page
import app.dkord.core.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory as F
import org.schabi.newpipe.extractor.stream.StreamInfoItem

enum class SearchFilter(internal val id: String) {
    SONGS(F.MUSIC_SONGS), ALBUMS(F.MUSIC_ALBUMS), ARTISTS(F.MUSIC_ARTISTS), PLAYLISTS(F.MUSIC_PLAYLISTS)
}

/** Opaque handle for the next search page. */
class SearchCursor internal constructor(internal val query: String, internal val filter: SearchFilter, internal val page: org.schabi.newpipe.extractor.Page)

class SearchResult(val items: List<BrowseItem>, val next: SearchCursor?)

class MusicRepository(private val client: InnerTubeClient) {

    // ---- Search & suggestions: NewPipeExtractor (no auth, no PO token needed) ----

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) emptyList() else ServiceList.YouTube.suggestionExtractor.suggestionList(query)
    }

    suspend fun search(query: String, filter: SearchFilter): SearchResult = withContext(Dispatchers.IO) {
        val yt = ServiceList.YouTube
        val info = SearchInfo.getInfo(yt, yt.searchQHFactory.fromQuery(query, listOf(filter.id), ""))
        SearchResult(info.relatedItems.mapNotNull(::mapItem), info.nextPage?.let { SearchCursor(query, filter, it) })
    }

    suspend fun searchMore(cursor: SearchCursor): SearchResult = withContext(Dispatchers.IO) {
        val yt = ServiceList.YouTube
        val page = SearchInfo.getMoreItems(yt, yt.searchQHFactory.fromQuery(cursor.query, listOf(cursor.filter.id), ""), cursor.page)
        SearchResult(page.items.mapNotNull(::mapItem), page.nextPage?.let { SearchCursor(cursor.query, cursor.filter, it) })
    }

    /** Songs related to [track] for autoplay: the extractor's related list, else a search by artist and title. */
    suspend fun related(track: Track): List<Track> = withContext(Dispatchers.IO) {
        val fromRelated = runCatching {
            org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=${track.id}")
                .relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull(::mapItem).mapNotNull { it.track }
        }.getOrDefault(emptyList())
        val songs = fromRelated.ifEmpty {
            runCatching { search("${track.artist} ${track.title}", SearchFilter.SONGS).items.mapNotNull { it.track } }.getOrDefault(emptyList())
        }
        songs.filter { it.id != track.id && it.durationSec in 30..900 }
    }

    // ---- Browse: InnerTube WEB_REMIX (personalised when signed in) ----

    suspend fun home(chip: app.dkord.core.Chip? = null): Page =
        MusicParser.parsePage(client.browse(chip?.browseId ?: "FEmusic_home", chip?.params))

    suspend fun homeMore(token: String): Page = MusicParser.parseContinuation(client.browse(continuation = token))

    suspend fun explore(): Page = MusicParser.parsePage(client.browse("FEmusic_explore"))
    suspend fun newReleases(): Page = MusicParser.parsePage(client.browse("FEmusic_new_releases"))
    suspend fun charts(): Page = MusicParser.parsePage(client.browse("FEmusic_charts"))
    suspend fun moodsAndGenres(): Page = MusicParser.parsePage(client.browse("FEmusic_moods_and_genres"))
    suspend fun moodPage(item: BrowseItem): Page = MusicParser.parsePage(client.browse(item.id, item.params))

    suspend fun album(browseId: String): Page = MusicParser.parsePage(client.browse(browseId), albumPage = true)
    suspend fun artist(browseId: String): Page = MusicParser.parsePage(client.browse(browseId))
    suspend fun playlist(browseId: String): Page = MusicParser.parsePage(client.browse(browseId))
    suspend fun more(token: String): Page = MusicParser.parseContinuation(client.browse(continuation = token))

    // ---- Library (requires login) ----

    suspend fun likedSongs(): Page = MusicParser.parsePage(client.browse("FEmusic_liked_videos"))
    suspend fun libraryPlaylists(): Page = MusicParser.parsePage(client.browse("FEmusic_liked_playlists"))
    suspend fun libraryAlbums(): Page = MusicParser.parsePage(client.browse("FEmusic_liked_albums"))
    suspend fun libraryArtists(): Page = MusicParser.parsePage(client.browse("FEmusic_library_corpus_track_artists"))
    suspend fun history(): Page = MusicParser.parsePage(client.browse("FEmusic_history"))

    /** Display name and avatar of the signed-in account, or null. */
    suspend fun account(): Pair<String, String?>? {
        val h = client.accountMenu().find("activeAccountHeaderRenderer") ?: return null
        val name = h.at("accountName").runsText().ifBlank { return null }
        return name to h.at("accountPhoto").lastThumbnail()
    }

    // ---- NewPipe -> app models ----

    private fun mapItem(i: InfoItem): BrowseItem? {
        val thumb = i.thumbnails.maxByOrNull { it.height }?.url
        return when (i) {
            is StreamInfoItem -> {
                val id = videoId(i.url) ?: return null
                val artist = i.uploaderName.orEmpty().removeSuffix(" - Topic")
                BrowseItem(ItemKind.SONG, id, i.name, artist, thumb, track = Track(id, i.name, artist, null, i.duration, thumb))
            }
            is PlaylistInfoItem -> {
                val list = Regex("[?&]list=([^&]+)").find(i.url)?.groupValues?.get(1) ?: return null
                val kind = if (list.startsWith("OLAK5uy")) ItemKind.ALBUM else ItemKind.PLAYLIST
                BrowseItem(kind, "VL$list", i.name, i.uploaderName.orEmpty(), thumb)
            }
            is ChannelInfoItem -> BrowseItem(ItemKind.ARTIST, i.url.substringAfterLast('/'), i.name, "", thumb)
            else -> null
        }
    }

    private fun videoId(url: String) = Regex("[?&]v=([\\w-]{11})").find(url)?.groupValues?.get(1)
}

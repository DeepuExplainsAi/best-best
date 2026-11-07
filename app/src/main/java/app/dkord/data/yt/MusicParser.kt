package app.dkord.data.yt

import app.dkord.core.BrowseItem
import app.dkord.core.Chip
import app.dkord.core.ItemKind
import app.dkord.core.Page
import app.dkord.core.Shelf
import app.dkord.core.Track
import kotlinx.serialization.json.JsonElement

/** Turns YouTube Music (WEB_REMIX) JSON into app models. Pure Kotlin, covered by fixture tests. */
object MusicParser {

    private val HEADER_KEYS = listOf(
        "musicResponsiveHeaderRenderer", "musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer",
        "musicDetailHeaderRenderer", "musicHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer",
    )

    /** [albumPage]: tracks on album pages carry no album column, so the page title is the album. */
    fun parsePage(root: JsonElement, albumPage: Boolean = false): Page = build(sectionContents(root), root, albumPage)

    private fun build(sections: List<JsonElement>, root: JsonElement, albumPage: Boolean = false): Page {
        val shelves = ArrayList<Shelf>()
        val tracks = ArrayList<Track>()
        for (s in sections) {
            val (name, body) = s.entries().entries.firstOrNull() ?: continue
            when (name) {
                "musicPlaylistShelfRenderer", "musicShelfRenderer" -> {
                    val items = body.at("contents").list().mapNotNull { parseListItem(it.at("musicResponsiveListItemRenderer")) }
                    val shelfTitle = body.at("title").runsText()
                    // Playlist/album pages are one big shelf of tracks; search-like pages are titled shelves.
                    if (name == "musicPlaylistShelfRenderer" || shelfTitle.isEmpty()) tracks += items.mapNotNull { it.track }
                    else shelves += Shelf(shelfTitle, items)
                }
                "musicCarouselShelfRenderer" -> {
                    val title = body.at("header", "musicCarouselShelfBasicHeaderRenderer", "title").runsText()
                    val items = body.at("contents").list().mapNotNull(::parseCarouselItem)
                    if (items.isNotEmpty()) shelves += Shelf(title, items)
                }
                "gridRenderer" -> {
                    val title = body.at("header", "gridHeaderRenderer", "title").runsText()
                    val items = body.at("items").list().mapNotNull(::parseCarouselItem)
                    if (items.isNotEmpty()) shelves += Shelf(title, items)
                }
            }
        }
        val header = HEADER_KEYS.firstNotNullOfOrNull { root.find(it) }
        val title = header.at("title").runsText()
        val subtitle = listOf(header.at("subtitle"), header.at("straplineTextOne"))
            .map { it.runsText() }.filter { it.isNotBlank() }.joinToString(" · ")
        val thumb = header?.let { it.at("thumbnail").lastThumbnail() ?: it.at("foregroundThumbnail").lastThumbnail() }
        val artist = header.at("straplineTextOne").runsText().ifBlank { title }
        val filled = tracks.map { t ->
            t.copy(
                artist = t.artist.ifBlank { artist },
                thumbnail = t.thumbnail ?: thumb,
                album = t.album ?: title.takeIf { albumPage && it.isNotBlank() },
            )
        }
        return Page(
            title = title,
            subtitle = subtitle,
            thumbnail = thumb,
            chips = parseChips(root),
            shelves = shelves,
            tracks = filled,
            continuation = continuationToken(root),
        )
    }

    /** Continuation responses: items live in different containers depending on the page type. */
    fun parseContinuation(root: JsonElement): Page {
        val bare = root.find("musicPlaylistShelfContinuation").at("contents").list()
            .ifEmpty { root.find("musicShelfContinuation").at("contents").list() }
        val page = if (bare.isNotEmpty()) {
            // Rows without a shelf wrapper: parse them directly as tracks.
            val items = bare.mapNotNull { parseListItem(it.at("musicResponsiveListItemRenderer")) }
            Page(tracks = items.mapNotNull { it.track })
        } else {
            val sections = root.find("sectionListContinuation").at("contents").list()
                .ifEmpty { root.find("onResponseReceivedActions").list().firstNotNullOfOrNull { it.find("continuationItems") }.list() }
            build(sections, root)
        }
        return page.copy(continuation = continuationToken(root))
    }

    fun continuationToken(root: JsonElement): String? =
        root.find("continuationItemRenderer").at("continuationEndpoint", "continuationCommand", "token").str()
            ?: root.find("nextContinuationData").at("continuation").str()

    private fun sectionContents(root: JsonElement): List<JsonElement> {
        val c = root.at("contents")
        return (c.at("singleColumnBrowseResultsRenderer", "tabs", 0, "tabRenderer", "content", "sectionListRenderer", "contents").list()
            .ifEmpty { c.at("twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer", "contents").list() }
            .ifEmpty { c.at("sectionListRenderer", "contents").list() })
    }

    private fun parseChips(root: JsonElement): List<Chip> =
        root.find("chipCloudRenderer").at("chips").list().mapNotNull {
            val c = it.at("chipCloudChipRenderer") ?: return@mapNotNull null
            val title = c.at("text").runsText().ifBlank { c.at("text").str().orEmpty() }
            val be = c.at("navigationEndpoint", "browseEndpoint")
            val id = be.at("browseId").str() ?: return@mapNotNull null
            Chip(title, id, be.at("params").str())
        }

    private fun parseCarouselItem(e: JsonElement): BrowseItem? =
        parseTwoRow(e.at("musicTwoRowItemRenderer"))
            ?: parseNavButton(e.at("musicNavigationButtonRenderer"))
            ?: parseListItem(e.at("musicResponsiveListItemRenderer"))

    private fun parseNavButton(b: JsonElement?): BrowseItem? {
        b ?: return null
        val be = b.at("clickCommand", "browseEndpoint")
        return BrowseItem(
            kind = ItemKind.MOOD,
            id = be.at("browseId").str() ?: return null,
            title = b.at("buttonText").runsText(),
            params = be.at("params").str(),
        )
    }

    private fun parseTwoRow(r: JsonElement?): BrowseItem? {
        r ?: return null
        val title = r.at("title").runsText()
        val subtitle = r.at("subtitle").runsText()
        val thumb = r.at("thumbnailRenderer").lastThumbnail()
        val nav = r.at("navigationEndpoint")
        val watch = nav.at("watchEndpoint")
        if (watch != null) {
            val vid = watch.at("videoId").str() ?: return null
            return BrowseItem(ItemKind.SONG, vid, title, subtitle, thumb,
                track = Track(vid, title, subtitle.substringBefore(" • "), null, 0, thumb))
        }
        val be = nav.at("browseEndpoint") ?: return null
        val id = be.at("browseId").str() ?: return null
        val kind = when (be.at("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").str()) {
            "MUSIC_PAGE_TYPE_ALBUM" -> ItemKind.ALBUM
            "MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL" -> ItemKind.ARTIST
            "MUSIC_PAGE_TYPE_PLAYLIST" -> ItemKind.PLAYLIST
            else -> return null
        }
        return BrowseItem(kind, id, title, subtitle, thumb, be.at("params").str())
    }

    /** A song row (album/playlist/search/chart). Returns null for unavailable rows without a videoId. */
    fun parseListItem(r: JsonElement?): BrowseItem? {
        r ?: return null
        val cols = r.at("flexColumns").list().map { it.at("musicResponsiveListItemFlexColumnRenderer", "text") }
        val videoId = r.at("playlistItemData", "videoId").str()
            ?: r.find("playNavigationEndpoint").find("watchEndpoint").at("videoId").str()
            ?: cols.getOrNull(0).runs().firstNotNullOfOrNull { it.at("navigationEndpoint", "watchEndpoint", "videoId").str() }
        if (videoId == null) return parseBrowsableRow(r, cols)

        val title = cols.getOrNull(0).runsText()
        val meta = cols.getOrNull(1).runs()
        fun pageType(run: JsonElement) = run.at("navigationEndpoint", "browseEndpoint",
            "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").str()
        val artists = meta.filter { pageType(it) == "MUSIC_PAGE_TYPE_ARTIST" }.mapNotNull { it.at("text").str() }
        val album = meta.firstOrNull { pageType(it) == "MUSIC_PAGE_TYPE_ALBUM" }.at("text").str()
        val artist = artists.joinToString(", ").ifBlank {
            meta.mapNotNull { it.at("text").str() }.firstOrNull { it.trim() !in setOf("•", "·", "") } .orEmpty()
        }
        val durText = r.at("fixedColumns", 0, "musicResponsiveListItemFixedColumnRenderer", "text").runsText()
            .ifBlank { meta.mapNotNull { it.at("text").str() }.lastOrNull { DURATION.matches(it) }.orEmpty() }
        val thumb = r.at("thumbnail").lastThumbnail()
        val track = Track(videoId, title, artist, album, parseDuration(durText), thumb)
        return BrowseItem(ItemKind.SONG, videoId, title, artist, thumb, track = track)
    }

    /** Search-style rows for albums/artists/playlists inside musicResponsiveListItemRenderer. */
    private fun parseBrowsableRow(r: JsonElement, cols: List<JsonElement?>): BrowseItem? {
        val be = r.at("navigationEndpoint", "browseEndpoint") ?: return null
        val id = be.at("browseId").str() ?: return null
        val kind = when (be.at("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").str()) {
            "MUSIC_PAGE_TYPE_ALBUM" -> ItemKind.ALBUM
            "MUSIC_PAGE_TYPE_ARTIST" -> ItemKind.ARTIST
            "MUSIC_PAGE_TYPE_PLAYLIST" -> ItemKind.PLAYLIST
            else -> return null
        }
        return BrowseItem(kind, id, cols.getOrNull(0).runsText(), cols.getOrNull(1).runsText(), r.at("thumbnail").lastThumbnail())
    }

    private val DURATION = Regex("""\d{1,2}(:\d{2}){1,2}""")

    /**
     * Playable music rows from a `next` response, in radio order.
     * Audio-only playback and a playlist row alone do not prove a video is music: require YouTube
     * Music's audio-track / official-music-video classification. Unknown types fail closed so the
     * repository can fall back to the songs search instead of queueing an ordinary video.
     */
    fun parseWatchNext(root: JsonElement): List<Track> =
        root.find("playlistPanelRenderer").at("contents").list().mapNotNull { row ->
            val r = row.at("playlistPanelVideoRenderer")
                ?: row.at("playlistPanelVideoWrapperRenderer", "primaryRenderer", "playlistPanelVideoRenderer")
                ?: return@mapNotNull null
            if (r.at("unplayableText") != null || r.at("isPlayable")?.toString() == "false") return@mapNotNull null
            val videoType = r.at("navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs",
                "watchEndpointMusicConfig", "musicVideoType").str()
            if (videoType != "MUSIC_VIDEO_TYPE_ATV" && videoType != "MUSIC_VIDEO_TYPE_OMV") return@mapNotNull null
            val id = r.at("videoId").str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = r.at("title").runsText()
                .ifBlank { r.at("title", "simpleText").str().orEmpty() }
                .ifBlank { return@mapNotNull null }
            val meta = r.at("longBylineText").runs().mapNotNull { it.at("text").str() }
            val artist = meta.firstOrNull { it.trim() !in setOf("•", "·", "") }.orEmpty()
            val duration = r.at("lengthText").runsText()
                .ifBlank { r.at("lengthText", "simpleText").str().orEmpty() }
            Track(id, title, artist, null, parseDuration(duration), r.at("thumbnail").lastThumbnail())
        }

    fun parseDuration(s: String): Long {
        if (!DURATION.matches(s.trim())) return 0
        return s.trim().split(":").fold(0L) { acc, p -> acc * 60 + p.toLong() }
    }
}

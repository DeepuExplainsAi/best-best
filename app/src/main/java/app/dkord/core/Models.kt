package app.dkord.core

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSec: Long = 0,
    val thumbnail: String? = null,
)

enum class ItemKind { SONG, ALBUM, ARTIST, PLAYLIST, MOOD }

/** One tile/row on a browse page. [id] is a videoId for SONG, otherwise a browseId. */
data class BrowseItem(
    val kind: ItemKind,
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnail: String? = null,
    val params: String? = null,
    val track: Track? = null,
)

data class Shelf(val title: String, val items: List<BrowseItem>)

data class Chip(val title: String, val browseId: String, val params: String?)

data class Page(
    val title: String = "",
    val subtitle: String = "",
    val thumbnail: String? = null,
    val chips: List<Chip> = emptyList(),
    val shelves: List<Shelf> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val continuation: String? = null,
)

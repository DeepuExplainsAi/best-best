package app.dkord.data.library

import app.dkord.core.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@Serializable
data class PlaylistDoc(val name: String, val tracks: List<Track>)

/** Export file. [liked] is optional so a single-playlist export stays small. */
@Serializable
data class LibraryDoc(
    val app: String = "dkord",
    val version: Int = 1,
    val playlists: List<PlaylistDoc> = emptyList(),
    val liked: List<Track> = emptyList(),
)

object PlaylistJson {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(doc: LibraryDoc): String = json.encodeToString(LibraryDoc.serializer(), doc)

    /** Throws [IllegalArgumentException] with a readable message for files that are not ours. */
    fun decode(text: String): LibraryDoc {
        val doc = try {
            // "app" has a default for exporting; on import it must really be in the file.
            val root = json.parseToJsonElement(text).jsonObject
            require("app" in root) { "Not a Dkord export" }
            json.decodeFromJsonElement(LibraryDoc.serializer(), root)
        } catch (e: IllegalArgumentException) { throw e }
        catch (e: Exception) { throw IllegalArgumentException("Not a valid playlist file", e) }
        require(doc.app == "dkord") { "Not a Dkord export" }
        require(doc.version <= 1) { "File was made by a newer version of the app" }
        return doc.copy(
            playlists = doc.playlists.map { p -> p.copy(name = p.name.trim().ifBlank { "Imported" }, tracks = p.tracks.filter(::valid)) },
            liked = doc.liked.filter(::valid),
        )
    }

    private fun valid(t: Track) = t.id.length == 11 && t.title.isNotBlank()
}

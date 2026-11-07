package app.dkord.data.search

import app.dkord.data.yt.SearchFilter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.text.Normalizer
import java.util.Locale

const val MAX_SAVED_SEARCHES = 50
const val MAX_PINNED_SEARCHES = 10
const val MAX_SEARCH_LENGTH = 256
private val spaces = Regex("[\\s\\p{Z}]+")

/** The same query in Albums and Songs is a different shortcut. No account or playback data. */
data class SavedSearch(val query: String, val filter: SearchFilter, val pinned: Boolean = false) {
    val key: String get() = "${filter.name}:${searchIdentity(query)}"
}

fun limitSearchInput(text: String): String {
    var end = minOf(text.length, MAX_SEARCH_LENGTH)
    if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    return text.substring(0, end)
}

fun normalizeSearchQuery(text: String): String = limitSearchInput(text.replace(spaces, " ").trim()).trim()
internal fun searchIdentity(text: String): String =
    Normalizer.normalize(normalizeSearchQuery(text), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

/** Storage order is most-recent-first, not wall-clock sorting (device clock changes are harmless). */
internal fun sanitizeSavedSearches(entries: List<SavedSearch>): List<SavedSearch> {
    val seen = mutableSetOf<String>()
    var pins = 0
    val valid = entries.mapNotNull { entry ->
        val query = normalizeSearchQuery(entry.query)
        if (query.isEmpty()) return@mapNotNull null
        val normalized = entry.copy(query = query)
        if (!seen.add(normalized.key)) return@mapNotNull null
        normalized.copy(pinned = normalized.pinned && pins++ < MAX_PINNED_SEARCHES)
    }
    var recents = MAX_SAVED_SEARCHES - valid.count { it.pinned }
    return valid.filter { it.pinned || recents-- > 0 }
}

internal fun rememberSearch(entries: List<SavedSearch>, query: String, filter: SearchFilter): List<SavedSearch> {
    val text = normalizeSearchQuery(query)
    if (text.isEmpty()) return entries
    val entry = SavedSearch(text, filter)
    val previous = entries.firstOrNull { it.key == entry.key }
    return sanitizeSavedSearches(listOf(entry.copy(pinned = previous?.pinned == true)) + entries.filterNot { it.key == entry.key })
}

class SearchPinLimitException : IllegalStateException("Saved search pin limit reached")

internal fun toggleSearchPin(entries: List<SavedSearch>, key: String): List<SavedSearch> {
    val entry = entries.firstOrNull { it.key == key } ?: return entries
    if (!entry.pinned && entries.count { it.pinned } >= MAX_PINNED_SEARCHES) throw SearchPinLimitException()
    return entries.map { if (it.key == key) it.copy(pinned = !it.pinned) else it }
}

/** Local suggestions work offline. Prefixes rank first, then pins, then stored recency. */
fun matchingSavedSearches(entries: List<SavedSearch>, query: String, filter: SearchFilter): List<SavedSearch> {
    val needle = searchIdentity(query)
    if (needle.isBlank()) return emptyList()
    return entries.filter { it.filter == filter && needle in searchIdentity(it.query) }
        .sortedWith(compareBy<SavedSearch> { !searchIdentity(it.query).startsWith(needle) }.thenBy { !it.pinned })
        .take(5)
}

@Serializable
private data class StoredSearch(val query: String, val filter: String, val pinned: Boolean = false)
@Serializable
private data class SearchHistoryDocument(val version: Int = 1, val entries: List<StoredSearch>)

/** A separate versioned local document; malformed/future data is not silently overwritten. */
object SearchHistoryCodec {
    private const val MAX_DOCUMENT_CHARS = 128 * 1024
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(entries: List<SavedSearch>): String = json.encodeToString(SearchHistoryDocument.serializer(),
        SearchHistoryDocument(entries = sanitizeSavedSearches(entries).map { StoredSearch(it.query, it.filter.name, it.pinned) }))

    fun decode(text: String?): List<SavedSearch> {
        if (text == null) return emptyList()
        if (text.length > MAX_DOCUMENT_CHARS) throw IOException("Search history unavailable")
        val document = try { json.decodeFromString(SearchHistoryDocument.serializer(), text) }
        catch (_: Exception) { throw IOException("Search history unavailable") }
        if (document.version != 1) throw IOException("Unsupported search history version")
        return sanitizeSavedSearches(document.entries.mapNotNull { stored ->
            val filter = SearchFilter.entries.firstOrNull { it.name == stored.filter } ?: return@mapNotNull null
            SavedSearch(stored.query, filter, stored.pinned)
        })
    }
}

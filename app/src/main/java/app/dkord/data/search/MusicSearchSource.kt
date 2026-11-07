package app.dkord.data.search

import app.dkord.core.BrowseItem
import app.dkord.data.yt.MusicRepository
import app.dkord.data.yt.SearchCursor
import app.dkord.data.yt.SearchFilter
import app.dkord.data.yt.SearchResult

/** Tokens stay opaque to the screen/controller; the existing NewPipe search backend is untouched. */
interface SearchPageToken

data class SearchPage(val items: List<BrowseItem>, val next: SearchPageToken? = null)

interface SearchSource {
    suspend fun suggestions(query: String): List<String>
    suspend fun search(query: String, filter: SearchFilter): SearchPage
    suspend fun more(token: SearchPageToken): SearchPage
}

class MusicSearchSource(private val music: MusicRepository) : SearchSource {
    private class Token(val cursor: SearchCursor) : SearchPageToken
    private fun page(result: SearchResult) = SearchPage(result.items, result.next?.let(::Token))
    override suspend fun suggestions(query: String) = music.suggestions(query)
    override suspend fun search(query: String, filter: SearchFilter) = page(music.search(query, filter))
    override suspend fun more(token: SearchPageToken) = page(music.searchMore((token as Token).cursor))
}

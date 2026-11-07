package app.dkord.ui.search

import app.dkord.core.BrowseItem
import app.dkord.data.search.SavedSearch
import app.dkord.data.search.SearchHistoryRepository
import app.dkord.data.search.SearchPageToken
import app.dkord.data.search.SearchSource
import app.dkord.data.search.limitSearchInput
import app.dkord.data.search.normalizeSearchQuery
import app.dkord.data.search.searchIdentity
import app.dkord.data.yt.SearchFilter
import app.dkord.ui.common.Load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Screen-lifetime requests, app-lifetime saved queries. Late work cannot replace a newer query. */
class SearchController(
    private val source: SearchSource,
    private val history: SearchHistoryRepository,
    private val scope: CoroutineScope,
) {
    private val mutableQuery = MutableStateFlow("")
    private val mutableFilter = MutableStateFlow(SearchFilter.SONGS)
    private val mutableResults = MutableStateFlow<Load<List<BrowseItem>>?>(null)
    private val mutableSuggestions = MutableStateFlow<List<String>>(emptyList())
    private val mutableHasMore = MutableStateFlow(false)
    private val mutableMore = MutableStateFlow(false)
    private val mutableMoreFailed = MutableStateFlow(false)
    val query = mutableQuery.asStateFlow()
    val filter = mutableFilter.asStateFlow()
    val results = mutableResults.asStateFlow()
    val suggestions = mutableSuggestions.asStateFlow()
    val hasMore = mutableHasMore.asStateFlow()
    val loadingMore = mutableMore.asStateFlow()
    val moreFailed = mutableMoreFailed.asStateFlow()
    private var cursor: SearchPageToken? = null
    private var suggestJob: Job? = null
    private var searchJob: Job? = null
    private var moreJob: Job? = null
    private var request = 0L
    private var suggestionRequest = 0L

    fun typed(text: String) {
        val limited = limitSearchInput(text)
        if (limited == query.value) return
        beginTyping(limited, filter.value)
    }

    fun edit(entry: SavedSearch) = beginTyping(entry.query, entry.filter)

    private fun beginTyping(text: String, selected: SearchFilter) {
        invalidate()
        mutableQuery.value = text
        mutableFilter.value = selected
        mutableResults.value = null
        val normalized = normalizeSearchQuery(text)
        if (normalized.isBlank()) return
        val id = suggestionRequest
        suggestJob = scope.launch {
            delay(250)
            try {
                val values = source.suggestions(normalized).map(::normalizeSearchQuery).filter { it.isNotBlank() }
                    .distinctBy(::searchIdentity).take(8)
                currentCoroutineContext().ensureActive()
                if (id == suggestionRequest && results.value == null) mutableSuggestions.value = values
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (id == suggestionRequest) mutableSuggestions.value = emptyList()
            }
        }
    }

    fun setFilter(selected: SearchFilter) {
        if (selected == filter.value) return
        val submitted = results.value != null
        if (submitted) search(query.value, selected) else beginTyping(query.value, selected)
    }

    fun repeat(entry: SavedSearch) = search(entry.query, entry.filter)

    fun search(text: String = query.value, selected: SearchFilter = filter.value) {
        val normalized = normalizeSearchQuery(text)
        if (normalized.isBlank()) return
        if (results.value == Load.Loading && normalized == query.value && selected == filter.value) return
        invalidate()
        val id = request
        mutableQuery.value = normalized
        mutableFilter.value = selected
        mutableResults.value = Load.Loading
        history.record(normalized, selected) // Only explicit submits, never typing, page loads or late results.
        searchJob = scope.launch {
            try {
                val result = source.search(normalized, selected)
                currentCoroutineContext().ensureActive()
                if (id == request) {
                    setCursor(result.next)
                    mutableResults.value = Load.Ok(result.items.distinctBy { it.kind to it.id })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (id == request) mutableResults.value = Load.Failed(e)
            }
        }
    }

    fun loadMore(retry: Boolean = false) {
        val token = cursor ?: return
        val current = (results.value as? Load.Ok)?.value ?: return
        if (loadingMore.value || (moreFailed.value && !retry)) return
        val id = request
        mutableMore.value = true
        mutableMoreFailed.value = false
        moreJob = scope.launch {
            try {
                val result = source.more(token)
                currentCoroutineContext().ensureActive()
                if (id == request) {
                    setCursor(result.next.takeUnless { it === token })
                    mutableResults.value = Load.Ok((current + result.items).distinctBy { it.kind to it.id })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (id == request) mutableMoreFailed.value = true // Keep the cursor and existing results for retry.
            } finally {
                if (id == request) mutableMore.value = false
            }
        }
    }

    fun clear() {
        invalidate()
        mutableQuery.value = ""
        mutableResults.value = null
    }

    private fun setCursor(next: SearchPageToken?) {
        cursor = next
        mutableHasMore.value = next != null
    }

    private fun invalidate() {
        request++; suggestionRequest++
        searchJob?.cancel(); moreJob?.cancel(); suggestJob?.cancel()
        setCursor(null)
        mutableMore.value = false
        mutableMoreFailed.value = false
        mutableSuggestions.value = emptyList()
    }
}

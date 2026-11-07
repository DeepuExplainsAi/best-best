package app.dkord.data.search

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.searchHistoryStore by preferencesDataStore(name = "search-history")

/** Private app storage, separate from settings/database. No network or query logging. */
class PreferencesSearchHistoryStorage(context: Context) : SearchHistoryStorage {
    private val store = context.applicationContext.searchHistoryStore
    private val key = stringPreferencesKey("saved-searches")
    override val entries = store.data.map { SearchHistoryCodec.decode(it[key]) }
    override suspend fun update(transform: (List<SavedSearch>) -> List<SavedSearch>) {
        store.edit { preferences -> preferences[key] = SearchHistoryCodec.encode(transform(SearchHistoryCodec.decode(preferences[key]))) }
    }
    override suspend fun clear() { store.edit { it.remove(key) } }
}

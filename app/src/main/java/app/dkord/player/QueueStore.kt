package app.dkord.player

import android.content.Context

class QueueStore(context: Context) {
    private val prefs = context.getSharedPreferences("dkord_player", Context.MODE_PRIVATE)
    fun save(q: SavedQueue) = prefs.edit().putString(KEY, q.encode()).apply()
    fun load(): SavedQueue? = SavedQueue.decode(prefs.getString(KEY, null))
    fun clear() = prefs.edit().remove(KEY).apply()
    private companion object { const val KEY = "queue" }
}

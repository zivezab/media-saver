package com.mediasaver

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class SavedItem(
    val id: String,
    val title: String,
    val displayName: String,
    val uri: String,
    val mimeType: String,
    val location: String,
    val sizeBytes: Long,
    val savedAt: Long,
) {
    val isVideo get() = mimeType.startsWith("video/")
    val isAudio get() = mimeType.startsWith("audio/")
}

/**
 * Remembers what the app has saved, so a download is still one tap from playing
 * long after the download itself has finished. Without this the finished job
 * disappears when the app is closed and the only way back to the file is hunting
 * through a file manager.
 *
 * SharedPreferences is enough: this is a short list of small records.
 */
object DownloadHistory {

    private const val PREFS = "history"
    private const val KEY = "items"
    private const val LIMIT = 100

    private val _items = MutableStateFlow<List<SavedItem>>(emptyList())
    val items: StateFlow<List<SavedItem>> = _items.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        val raw = prefs(context).getString(KEY, null) ?: return
        val parsed = runCatching { parse(raw) }.getOrDefault(emptyList())
        // Entries the user has since deleted from the gallery should not linger.
        val alive = parsed.filter { MediaStoreSaver.exists(context, it.uri) }
        _items.value = alive
        if (alive.size != parsed.size) persist(context, alive)
    }

    fun add(context: Context, item: SavedItem) {
        val next = (listOf(item) + _items.value.filter { it.uri != item.uri }).take(LIMIT)
        _items.value = next
        persist(context, next)
    }

    fun remove(context: Context, id: String) {
        val next = _items.value.filterNot { it.id == id }
        _items.value = next
        persist(context, next)
    }

    fun clear(context: Context) {
        _items.value = emptyList()
        persist(context, emptyList())
    }

    private fun persist(context: Context, items: List<SavedItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("displayName", item.displayName)
                    put("uri", item.uri)
                    put("mimeType", item.mimeType)
                    put("location", item.location)
                    put("sizeBytes", item.sizeBytes)
                    put("savedAt", item.savedAt)
                }
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    private fun parse(raw: String): List<SavedItem> {
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val uri = o.optString("uri").ifBlank { return@mapNotNull null }
            SavedItem(
                id = o.optString("id", uri),
                title = o.optString("title"),
                displayName = o.optString("displayName"),
                uri = uri,
                mimeType = o.optString("mimeType", "*/*"),
                location = o.optString("location"),
                sizeBytes = o.optLong("sizeBytes", 0L),
                savedAt = o.optLong("savedAt", 0L),
            )
        }
    }
}

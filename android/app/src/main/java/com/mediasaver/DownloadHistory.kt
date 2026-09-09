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
    /** Host the media came from, used to group the library. */
    val sourceDomain: String = "",
) {
    val isVideo get() = mimeType.startsWith("video/")
    val isAudio get() = mimeType.startsWith("audio/")
    val fullPath get() = "$location/$displayName"
}

/**
 * Remembers what the app has saved, so a download is still one tap from playing
 * long after the download itself has finished.
 */
object DownloadHistory {

    private const val PREFS = "history"
    private const val KEY = "items"
    private const val LIMIT = 500

    private val _items = MutableStateFlow<List<SavedItem>>(emptyList())
    val items: StateFlow<List<SavedItem>> = _items.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        val raw = prefs(context).getString(KEY, null) ?: return
        val parsed = runCatching { parse(raw) }.getOrDefault(emptyList())
        // Entries the user has since deleted elsewhere should not linger.
        val alive = parsed.filter { MediaStoreSaver.exists(context, it.uri) }
        _items.value = alive
        if (alive.size != parsed.size) persist(context, alive)
    }

    fun add(context: Context, item: SavedItem) {
        val next = (listOf(item) + _items.value.filter { it.uri != item.uri }).take(LIMIT)
        _items.value = next
        persist(context, next)
    }

    /** Forget the entry but leave the file alone. */
    fun forget(context: Context, id: String) {
        val next = _items.value.filterNot { it.id == id }
        _items.value = next
        persist(context, next)
    }

    /** Delete the file from the phone and drop the entry. */
    fun delete(context: Context, id: String): Boolean {
        val item = _items.value.firstOrNull { it.id == id } ?: return false
        val removed = MediaStoreSaver.delete(context, item.uri)
        forget(context, id)
        return removed
    }

    fun deleteAll(context: Context, ids: Collection<String>): Int =
        ids.count { delete(context, it) }

    fun clear(context: Context) {
        _items.value = emptyList()
        persist(context, emptyList())
    }

    /**
     * Groups of entries that are the same media saved more than once.
     *
     * Matched on size plus name rather than by hashing: these files run to
     * hundreds of megabytes, and re-reading every one to compare digests would
     * cost far more than the problem is worth. Two downloads of the same format
     * of the same video are byte-identical in length, which is a strong enough
     * signal when the name agrees too.
     */
    fun duplicateGroups(items: List<SavedItem> = _items.value): List<List<SavedItem>> =
        items.filter { it.sizeBytes > 0 }
            .groupBy { it.sizeBytes to normalisedName(it.displayName) }
            .values
            .filter { it.size > 1 }
            .map { group -> group.sortedByDescending { it.savedAt } }

    /** "Clip (1).mp4" and "Clip.mp4" are the same download saved twice. */
    private fun normalisedName(displayName: String): String =
        displayName
            .substringBeforeLast('.')
            .replace(Regex("""\s*\(\d+\)$"""), "")
            .lowercase()
            .trim()

    /**
     * Wildcard-aware name search. `*` and `?` behave as they do in a shell; a
     * query with neither is treated as a substring, which is what people expect
     * from a search box.
     */
    fun matches(item: SavedItem, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val haystacks = listOf(item.displayName, item.title, item.sourceDomain)
        return if (q.contains('*') || q.contains('?')) {
            val pattern = buildString {
                append("^.*")
                q.forEach { c ->
                    when (c) {
                        '*' -> append(".*")
                        '?' -> append('.')
                        else -> append(Regex.escape(c.toString()))
                    }
                }
                append(".*$")
            }
            val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                ?: return haystacks.any { it.contains(q, ignoreCase = true) }
            haystacks.any { regex.matches(it) }
        } else {
            haystacks.any { it.contains(q, ignoreCase = true) }
        }
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
                    put("sourceDomain", item.sourceDomain)
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
                sourceDomain = o.optString("sourceDomain"),
            )
        }
    }

    /** Host of a URL, without "www.", for grouping. */
    fun domainOf(url: String): String = runCatching {
        java.net.URI(url).host.orEmpty().removePrefix("www.")
    }.getOrDefault("")
}

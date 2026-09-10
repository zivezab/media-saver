package com.mediasaver

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User preferences, persisted and observable. */
object Settings {

    enum class ThemeMode { SYSTEM, LIGHT, DARK }
    enum class Accent { DYNAMIC, BLUE, GREEN, PURPLE, ORANGE }
    enum class SortBy { DATE, SIZE, NAME }
    enum class Layout { LIST, GRID }

    data class State(
        val theme: ThemeMode = ThemeMode.SYSTEM,
        val accent: Accent = Accent.DYNAMIC,
        val sortBy: SortBy = SortBy.DATE,
        val sortDescending: Boolean = true,
        val layout: Layout = Layout.LIST,
        val groupByDomain: Boolean = true,
        val showThumbnails: Boolean = true,
        val showFullPath: Boolean = false,
        /** Subfolder used inside Movies/Music/Pictures/Download. */
        val folder: String = "Media Saver",
        /** Start the best-quality download as soon as a link resolves. */
        val autoDownloadBest: Boolean = false,
        /** Repeat a video when it reaches the end. */
        val loopPlayback: Boolean = false,
        /** Pause playback when the app goes to the background. */
        val pauseOnLeave: Boolean = true,
    )

    private const val PREFS = "settings"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        val p = prefs(context)
        _state.value = State(
            theme = enumOf(p.getString("theme", null), ThemeMode.SYSTEM),
            accent = enumOf(p.getString("accent", null), Accent.DYNAMIC),
            sortBy = enumOf(p.getString("sortBy", null), SortBy.DATE),
            sortDescending = p.getBoolean("sortDescending", true),
            layout = enumOf(p.getString("layout", null), Layout.LIST),
            groupByDomain = p.getBoolean("groupByDomain", true),
            showThumbnails = p.getBoolean("showThumbnails", true),
            showFullPath = p.getBoolean("showFullPath", false),
            folder = p.getString("folder", null)?.takeIf { it.isNotBlank() } ?: "Media Saver",
            autoDownloadBest = p.getBoolean("autoDownloadBest", false),
            loopPlayback = p.getBoolean("loopPlayback", false),
            pauseOnLeave = p.getBoolean("pauseOnLeave", true),
        )
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    fun update(context: Context, transform: (State) -> State) {
        val next = transform(_state.value)
        _state.value = next
        prefs(context).edit()
            .putString("theme", next.theme.name)
            .putString("accent", next.accent.name)
            .putString("sortBy", next.sortBy.name)
            .putBoolean("sortDescending", next.sortDescending)
            .putString("layout", next.layout.name)
            .putBoolean("groupByDomain", next.groupByDomain)
            .putBoolean("showThumbnails", next.showThumbnails)
            .putBoolean("showFullPath", next.showFullPath)
            .putString("folder", next.folder)
            .putBoolean("autoDownloadBest", next.autoDownloadBest)
            .putBoolean("loopPlayback", next.loopPlayback)
            .putBoolean("pauseOnLeave", next.pauseOnLeave)
            .apply()
    }

    /** The folder name, reduced to something safe to put in a storage path. */
    fun safeFolder(name: String): String {
        val cleaned = name
            .filter { it.code >= 0x20 }
            .map { if (it in "\\/:*?\"<>|") '_' else it }
            .joinToString("")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '.')
        return cleaned.ifBlank { "Media Saver" }.take(60)
    }
}

package com.mediasaver

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MediaInfo(
    val url: String,
    val title: String,
    val uploader: String,
    val durationSec: Int,
    val thumbnail: String?,
    val source: String,
    val options: List<Formats.Option>,
    val allFormats: List<Formats.Row>,
)

/**
 * Wraps the bundled yt-dlp. The first launch has to unpack a Python runtime out
 * of the APK, which takes a few seconds, so initialisation is exposed as state
 * the UI can wait on rather than blocking.
 */
object Extractor {

    private const val TAG = "Extractor"

    sealed interface Init {
        data object Loading : Init
        data object Updating : Init
        data object Ready : Init
        data class Failed(val message: String) : Init
    }

    private val _init = MutableStateFlow<Init>(Init.Loading)
    val init: StateFlow<Init> = _init.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Held so a lookup can find the cookies file without the caller passing one. */
    private var appContext: Context? = null

    private const val PREFS = "extractor"
    private const val KEY_LAST_UPDATE = "last_ytdlp_update"
    private val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000

    private val _version = MutableStateFlow<String?>(null)
    val version: StateFlow<String?> = _version.asStateFlow()

    fun start(context: Context) {
        if (_init.value is Init.Ready) return
        val app = context.applicationContext
        appContext = app
        scope.launch {
            _init.value = try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                refreshYoutubeDL(app)
                _version.value = runCatching { YoutubeDL.getInstance().version(app) }.getOrNull()
                Init.Ready
            } catch (t: Throwable) {
                Log.e(TAG, "init failed", t)
                Init.Failed(t.message ?: "Could not start the downloader.")
            }
        }
    }

    /**
     * The yt-dlp shipped inside the APK goes stale quickly, and a stale one does
     * not merely lose formats - YouTube answers 403 to its download requests
     * while still allowing metadata. So it is refreshed from the stable channel
     * on first run and once a day after that.
     *
     * A failure here is not fatal: extraction still works with what is bundled,
     * so the app carries on rather than refusing to start when offline.
     */
    private fun refreshYoutubeDL(app: Context) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_UPDATE, 0L)
        if (System.currentTimeMillis() - last < UPDATE_INTERVAL_MS) return

        _init.value = Init.Updating
        try {
            YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE)
            prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "yt-dlp update failed; continuing with the bundled copy", t)
        }
    }

    /** Pull the first http(s) URL out of shared text, which usually also carries a title. */
    fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return Regex("""https?://\S+""").find(text)?.value?.trimEnd('.', ',', ')', ']')
    }

    suspend fun probe(rawUrl: String): MediaInfo = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        require(url.isNotEmpty()) { "Paste a link first." }

        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--socket-timeout", "20")
            applyCookies(this)
        }
        val info = YoutubeDL.getInstance().getInfo(request)
        val (options, rows) = Formats.build(info)

        val thumb = info.thumbnail ?: info.thumbnails?.lastOrNull()?.url

        MediaInfo(
            url = info.webpageUrl ?: url,
            title = info.title?.takeIf { it.isNotBlank() } ?: "Untitled",
            uploader = info.uploader ?: info.uploaderId ?: "",
            durationSec = info.duration,
            thumbnail = thumb,
            source = info.extractorKey ?: "",
            options = options,
            allFormats = rows,
        )
    }

    /**
     * Hand yt-dlp the user's saved logins, when there are any. Sites like X gate
     * sensitive posts on a session rather than on anything technical.
     */
    fun applyCookies(request: YoutubeDLRequest) {
        val context = appContext ?: return
        val file = CookieStore.file(context)
        if (file.isFile && file.length() > 0) {
            request.addOption("--cookies", file.absolutePath)
        }
    }

    /** Turn a yt-dlp failure into something a person can act on. */
    fun humanError(t: Throwable): String {
        val raw = t.message ?: ""
        // yt-dlp writes warnings to the same stream as errors, and the version
        // warning comes first. Taking the whole message would report a warning
        // as the reason the download failed.
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val errorLine = lines.lastOrNull { it.startsWith("ERROR:") }
            ?: lines.lastOrNull { !it.startsWith("WARNING:") }
            ?: raw

        val msg = errorLine
            .substringBefore("Use --cookies")
            .substringBefore("See  https://github.com/yt-dlp")
            .replace(Regex("""^ERROR:\s*"""), "")
            .trim()
        val low = msg.lowercase()
        return when {
            // yt-dlp's X, Bluesky and Tumblr extractors drop photo media before
            // building formats, so a picture-only post reports "no video" - which
            // reads as a bug when you are looking straight at an image.
            "no video could be found" in low ->
                "That post has no video in it. Photos can't be saved from X, " +
                    "Bluesky or Tumblr - those extractors handle video and GIFs only."
            "unsupported url" in low ->
                "This site isn't supported. Try the direct link to the post itself."
            "nsfw" in low || "age" in low && "restrict" in low ->
                "This post is marked sensitive, so X only shows it to a signed-in " +
                    "account. Sign in from the account button at the top, then try again."
            "only works when logged-in" in low || "sign in" in low || "authentication" in low ||
                "not authorized" in low || "private" in low || "members-only" in low ||
                "cookies" in low || "login" in low ->
                "This needs an account. Sign in from the account button at the top, " +
                    "then try again."
            "not available" in low || "removed" in low || "unavailable" in low ->
                "The media at that link is unavailable, deleted, or region-blocked."
            "http error 404" in low -> "That link returned 404 - check it is complete and still live."
            "http error 401" in low || "http error 403" in low ->
                "The site refused the request. It may be geo-blocked or need a login."
            "nodename nor servname" in low || "unable to resolve" in low ->
                "That domain could not be found. Check the link for typos."
            "timed out" in low || "timeout" in low -> "The site took too long to respond. Try again."
            msg.isBlank() -> "Could not read that link."
            else -> msg.take(300)
        }
    }
}

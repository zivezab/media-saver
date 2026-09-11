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
import kotlinx.coroutines.flow.first
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
    /** Set when the post was resolved by gallery-dl: photos, not a video. */
    val photos: List<GalleryDl.Photo> = emptyList(),
    /** Something worth telling the user even though the lookup worked. */
    val warning: String? = null,
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
                // Off the critical path: the first lookup should not wait on
                // a PyPI round trip for the photo extractor.
                scope.launch { GalleryDl.refresh(app) }
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

    /**
     * Suspend until the bundled runtime is unpacked and refreshed. Lets callers
     * submit a link the moment it arrives - from a share, or from the service
     * after a cold start - instead of each one having to wait on the UI.
     */
    suspend fun awaitReady() {
        val state = init.first { it is Init.Ready || it is Init.Failed }
        if (state is Init.Failed) error(state.message)
    }

    suspend fun probe(rawUrl: String): MediaInfo = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        require(url.isNotEmpty()) { "Paste a link first." }
        awaitReady()

        // A saved session that the site has since expired does not just fail
        // to help - it breaks public posts too: yt-dlp gets "Could not
        // authenticate you" and gallery-dl a 404, for a post anyone can see.
        // So a failure with cookies is retried once without them, and the
        // cookies are only blamed if that retry works. A valid session on a
        // post that is genuinely unavailable fails both ways and is left alone.
        val withCookies = cookiesUsableFor(url)
        try {
            resolve(url, withCookies)
        } catch (first: Throwable) {
            if (!withCookies) throw first
            val retry = runCatching { resolve(url, useCookies = false) }.getOrNull() ?: throw first
            markCookiesRejected(url)
            retry.copy(
                warning = "Your ${DownloadHistory.domainOf(url)} sign-in was turned down, so it has " +
                    "probably expired. Public posts still work; sign in again for restricted ones.",
            )
        }
    }

    private suspend fun resolve(url: String, useCookies: Boolean): MediaInfo {
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--socket-timeout", "20")
            if (useCookies) cookieFile()?.let { addOption("--cookies", it.absolutePath) }
        }
        val info = try {
            YoutubeDL.getInstance().getInfo(request)
        } catch (t: Throwable) {
            // yt-dlp is kept first because it is the better video tool. Only
            // when it reports a post it cannot treat as video is gallery-dl
            // asked, and if that finds nothing either the original error is the
            // one reported - it is the more specific of the two.
            val context = appContext
            if (context != null && worthTryingPhotos(t)) {
                val post = runCatching { GalleryDl.probe(context, url, useCookies) }
                    .onFailure { Log.i(TAG, "gallery-dl found nothing either: ${it.message}") }
                    .getOrNull()
                if (post != null) return photoInfo(post)
            }
            throw t
        }
        val (options, rows) = Formats.build(info)

        val thumb = info.thumbnail ?: info.thumbnails?.lastOrNull()?.url

        return MediaInfo(
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

    /** Hosts whose saved session was proven bad this run, so downloads skip it too. */
    private val cookiesRejected = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun cookiesUsableFor(url: String): Boolean =
        cookieFile() != null && DownloadHistory.domainOf(url) !in cookiesRejected

    private fun markCookiesRejected(url: String) {
        cookiesRejected += DownloadHistory.domainOf(url)
    }

    /** Called when the user saves new cookies: give every host another chance. */
    fun resetCookieRejections() = cookiesRejected.clear()

    /** yt-dlp failures that mean "this is not a video", rather than "this is broken". */
    private fun worthTryingPhotos(t: Throwable): Boolean {
        val message = (t.message ?: "").lowercase()
        return listOf(
            "no video could be found",   // X, Bluesky, Tumblr photo posts
            "no video in this post",     // Instagram image posts
            "there is no video",
            "no video formats found",
            "unsupported url",           // sites only gallery-dl knows
            "no media found",
        ).any { it in message }
    }

    private fun photoInfo(post: GalleryDl.Post): MediaInfo {
        val images = post.photos.count { !it.isVideo }
        val videos = post.photos.size - images
        val counts = listOfNotNull(
            images.takeIf { it > 0 }?.let { if (it == 1) "1 photo" else "$it photos" },
            videos.takeIf { it > 0 }?.let { if (it == 1) "1 video" else "$it videos" },
        ).joinToString(", ")
        val first = post.photos.first()
        val detail = if (post.photos.size == 1 && first.width > 0) {
            "${first.ext.uppercase()} · ${first.width}×${first.height}"
        } else {
            "Original size"
        }
        return MediaInfo(
            url = post.url,
            title = post.title,
            uploader = post.author,
            durationSec = 0,
            thumbnail = post.photos.firstOrNull { !it.isVideo }?.url ?: first.url,
            source = "Photos",
            options = listOf(
                Formats.Option(
                    selector = GALLERY_SELECTOR,
                    title = if (post.photos.size == 1 && images == 1) "Save photo" else "Save $counts",
                    subtitle = detail,
                    kind = Formats.Kind.IMAGE,
                    recommended = true,
                )
            ),
            allFormats = emptyList(),
            photos = post.photos,
        )
    }

    /** Marks an option as "download this post with gallery-dl". */
    const val GALLERY_SELECTOR = "gallery-dl"

    fun cookieFile(): java.io.File? {
        val context = appContext ?: return null
        return CookieStore.file(context).takeIf { it.isFile && it.length() > 0 }
    }

    /**
     * Hand yt-dlp the user's saved logins, when there are any. Sites like X gate
     * sensitive posts on a session rather than on anything technical.
     */
    fun applyCookies(request: YoutubeDLRequest, url: String) {
        if (!cookiesUsableFor(url)) return
        cookieFile()?.let { request.addOption("--cookies", it.absolutePath) }
    }

    /** Turn a yt-dlp failure into something a person can act on. */
    private fun isXLink(url: String?): Boolean =
        url != null && Regex("""https?://([\w-]+\.)*(x|twitter)\.com/""").containsMatchIn(url)

    private fun signedInToX(): Boolean {
        val context = appContext ?: return false
        return "x" in CookieStore.sitesInFile(context)
    }

    fun humanError(t: Throwable, url: String? = null): String {
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
            .substringBefore("; please report this issue")
            .substringBefore("please report this issue")
            .replace(Regex("""^ERROR:\s*"""), "")
            .replace(Regex("""^\[[\w:.-]+\]\s*(?:[\w-]+:\s+)?"""), "")
            .trim()
        val low = msg.lowercase()
        return when {
            // Two very different situations reach this one message. yt-dlp's X,
            // Bluesky and Tumblr extractors drop photo media before building
            // formats, so a picture-only post reports "no video". But X also
            // answers a signed-out client with a bare TweetTombstone for
            // restricted posts, and yt-dlp only recognises a tombstone that
            // carries explanatory text - so a gated post lands here too, saying
            // nothing about the sign-in that would actually fix it.
            "could not authenticate" in low || "bad authentication" in low ->
                "The site rejected your saved sign-in - it has probably expired. " +
                    "Sign in again from the account button."
            "no video could be found" in low -> when {
                !isXLink(url) ->
                    "That post has no video in it. Photos can't be saved from X, " +
                        "Bluesky or Tumblr - those extractors handle video and GIFs only."
                signedInToX() ->
                    "X returned nothing for this post even though you are signed in. " +
                        "Either it holds only photos, which can't be saved from X, or " +
                        "your X session has expired - sign in again from the account button."
                else ->
                    "X hides restricted and sensitive posts from signed-out apps, and " +
                        "reports it only as \"no video\". Sign in to X from the account " +
                        "button at the top, then try again. If the post holds only photos, " +
                        "it can't be saved either way."
            }
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

package com.mediasaver

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Photos, via gallery-dl.
 *
 * yt-dlp is a video downloader and deliberately so: its X extractor filters out
 * `type == 'photo'` media before building formats, and Instagram's gives up on
 * image posts. gallery-dl is the established tool for images, and it is pure
 * Python - so it runs on the Python 3.12 that youtubedl-android already ships
 * on the phone, with no second runtime.
 *
 * It is launched exactly the way youtubedl-android launches yt-dlp: the
 * library's libpython.so, a zipapp as the first argument, and the same six
 * environment variables. Those were read off a live yt-dlp process rather than
 * guessed, and they depend on that library's on-disk layout - pinned at 0.18.1.
 */
object GalleryDl {

    private const val TAG = "GalleryDl"
    private const val ASSET = "gallery-dl.pyz"
    private const val VERSION_ASSET = "gallery-dl.version"
    private const val WHEEL = "gallery_dl-update.whl"
    private const val PREFS = "gallerydl"
    private const val KEY_INSTALLED = "installed_pyz_version"
    private const val KEY_WHEEL_VERSION = "wheel_version"
    private const val KEY_LAST_CHECK = "last_update_check"
    private val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000

    data class Photo(val url: String, val ext: String, val width: Int, val height: Int) {
        val isVideo get() = ext in setOf("mp4", "webm", "mov", "m4v", "mkv")
    }

    data class Post(val url: String, val title: String, val author: String, val photos: List<Photo>)

    private val running = ConcurrentHashMap<String, Process>()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pythonUsr(context: Context) =
        File(context.noBackupFilesDir, "youtubedl-android/packages/python/usr")

    private fun pythonBin(context: Context) =
        File(context.applicationInfo.nativeLibraryDir, "libpython.so")

    private fun pyz(context: Context) = File(context.filesDir, ASSET)

    private fun bundledVersion(context: Context): String = runCatching {
        context.assets.open(VERSION_ASSET).bufferedReader().readText().trim()
    }.getOrDefault("0")

    /** Copy the bundled archive out of the APK, again whenever the app ships a new one. */
    private fun ensureInstalled(context: Context): File {
        val target = pyz(context)
        val bundled = bundledVersion(context)
        if (!target.isFile || prefs(context).getString(KEY_INSTALLED, null) != bundled) {
            context.assets.open(ASSET).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            prefs(context).edit().putString(KEY_INSTALLED, bundled).apply()
        }
        return target
    }

    /**
     * A downloaded wheel is used only when it is newer than the bundled copy.
     * Otherwise an app update that ships a fresher gallery-dl would be shadowed
     * by an older wheel fetched months earlier.
     */
    private fun overrideWheel(context: Context): File? {
        val wheel = File(context.filesDir, WHEEL)
        val version = prefs(context).getString(KEY_WHEEL_VERSION, null) ?: return null
        return wheel.takeIf { it.isFile && isNewer(version, bundledVersion(context)) }
    }

    fun installedVersion(context: Context): String {
        val wheelVersion = prefs(context).getString(KEY_WHEEL_VERSION, null)
        val bundled = bundledVersion(context)
        return if (wheelVersion != null && overrideWheel(context) != null) wheelVersion else bundled
    }

    private class Result(val exit: Int, val out: String, val err: String)

    private fun exec(
        context: Context,
        args: List<String>,
        jobId: String? = null,
        onLine: ((String) -> Unit)? = null,
    ): Result {
        val usr = pythonUsr(context)
        check(pythonBin(context).isFile && usr.isDirectory) { "The Python runtime is not ready yet." }

        val builder = ProcessBuilder(
            listOf(pythonBin(context).absolutePath, ensureInstalled(context).absolutePath) + args
        ).directory(context.cacheDir)

        builder.environment().apply {
            put("LD_LIBRARY_PATH", File(usr, "lib").absolutePath)
            put("PYTHONHOME", usr.absolutePath)
            put("SSL_CERT_FILE", File(usr, "etc/tls/cert.pem").absolutePath)
            put("HOME", usr.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            put("PATH", (get("PATH") ?: "/system/bin") + ":" + context.applicationInfo.nativeLibraryDir)
            overrideWheel(context)?.let { put("GDL_OVERRIDE", it.absolutePath) }
        }

        val process = builder.start()
        jobId?.let { running[it] = process }
        try {
            // stderr is drained on its own thread: a full pipe on one stream
            // while reading the other would deadlock the child.
            val err = StringBuilder()
            val errReader = thread(name = "gallery-dl-stderr") {
                process.errorStream.bufferedReader().forEachLine { err.appendLine(it) }
            }
            val out = StringBuilder()
            process.inputStream.bufferedReader().forEachLine { line ->
                out.appendLine(line)
                onLine?.invoke(line)
            }
            val code = process.waitFor()
            errReader.join()
            return Result(code, out.toString(), err.toString())
        } finally {
            jobId?.let { running.remove(it) }
        }
    }

    fun cancel(jobId: String) {
        running.remove(jobId)?.destroy()
    }

    private fun cookieArgs(url: String, use: Boolean = Extractor.cookiesUsableFor(url)): List<String> {
        if (!use) return emptyList()
        val file = Extractor.cookieFile() ?: return emptyList()
        return listOf("--cookies", file.absolutePath)
    }

    /** List the media in a post without downloading anything. */
    suspend fun probe(
        context: Context,
        url: String,
        useCookies: Boolean = Extractor.cookiesUsableFor(url),
    ): Post = withContext(Dispatchers.IO) {
        Extractor.awaitReady()
        val result = exec(context, listOf("-j") + cookieArgs(url, useCookies) + url)

        val start = result.out.indexOf('[')
        check(start >= 0) { errorMessage(result) }
        val messages = runCatching { JSONArray(result.out.substring(start)) }
            .getOrElse { error(errorMessage(result)) }

        val photos = mutableListOf<Photo>()
        var meta: JSONObject? = null
        var failure: String? = null
        for (i in 0 until messages.length()) {
            val message = messages.optJSONArray(i) ?: continue
            when (message.optInt(0)) {
                2 -> meta = meta ?: message.optJSONObject(1)
                3 -> {
                    val fileMeta = message.optJSONObject(2) ?: JSONObject()
                    meta = meta ?: fileMeta
                    photos += Photo(
                        url = message.optString(1),
                        ext = fileMeta.optString("extension", "jpg").lowercase(),
                        width = fileMeta.optInt("width"),
                        height = fileMeta.optInt("height"),
                    )
                }
                -1 -> failure = message.optJSONObject(1)?.optString("message")
            }
        }
        if (photos.isEmpty()) error(failure ?: errorMessage(result))

        val author = meta?.optJSONObject("author")?.optString("name")
            ?: meta?.optJSONObject("user")?.optString("name")
            ?: ""
        // Posts with media usually end in a shortened link to that same media
        // (X appends a t.co URL). It adds nothing to a title and turns into
        // "http___t.co_..." once made safe for a file name, so links are dropped.
        val text = listOf("content", "description", "title", "caption")
            .firstNotNullOfOrNull { key -> meta?.optString(key)?.takeIf { it.isNotBlank() } }
            ?.replace(Regex("""https?://\S+"""), "")
            ?.lines()?.map { it.trim() }?.firstOrNull { it.isNotBlank() }
            ?.replace(Regex("""\s+"""), " ")?.take(80)

        Post(
            url = url,
            title = text ?: author.ifBlank { DownloadHistory.domainOf(url) },
            author = author,
            photos = photos,
        )
    }

    /**
     * Download every item in the post into [dir]. gallery-dl prints the path of
     * each file as it finishes, which is what drives the progress count.
     */
    fun download(context: Context, jobId: String, url: String, dir: File, onFile: (Int) -> Unit): List<File> {
        var done = 0
        val result = exec(
            context,
            listOf("-D", dir.absolutePath, "--no-mtime") + cookieArgs(url) + url,
            jobId = jobId,
            onLine = { line ->
                // Skipped files are reported with a leading "#".
                if (line.isNotBlank() && !line.startsWith("#")) onFile(++done)
            },
        )
        val files = dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sortedBy { it.name }
            .orEmpty()
        check(files.isNotEmpty()) { errorMessage(result) }
        return files
    }

    private fun errorMessage(result: Result): String {
        val line = result.err.lines().lastOrNull { "error" in it.lowercase() }
            ?: result.err.lines().lastOrNull { it.isNotBlank() }
        return line?.replace(Regex("""^\[[^\]]+\]\[error\]\s*"""), "")?.trim()
            ?.ifBlank { null } ?: "No photos could be found at that link."
    }

    /**
     * Fetch a newer gallery-dl from PyPI, at most daily. Photo extraction breaks
     * as sites change exactly the way video extraction does, and the bundled
     * copy only moves when the app is rebuilt.
     */
    fun refresh(context: Context) {
        val prefs = prefs(context)
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L) < UPDATE_INTERVAL_MS) return
        try {
            val json = JSONObject(httpGet("https://pypi.org/pypi/gallery-dl/json"))
            val latest = json.getJSONObject("info").getString("version")
            if (isNewer(latest, installedVersion(context))) {
                val urls = json.getJSONArray("urls")
                val wheelUrl = (0 until urls.length()).map { urls.getJSONObject(it) }
                    .firstOrNull { it.optString("packagetype") == "bdist_wheel" && it.optString("filename").endsWith("py3-none-any.whl") }
                    ?.optString("url")
                if (wheelUrl != null) {
                    val tmp = File(context.filesDir, "$WHEEL.part")
                    download(wheelUrl, tmp)
                    tmp.renameTo(File(context.filesDir, WHEEL))
                    prefs.edit().putString(KEY_WHEEL_VERSION, latest).apply()
                    Log.i(TAG, "gallery-dl updated to $latest")
                }
            }
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        } catch (t: Throwable) {
            // Not fatal: the bundled copy still works, and the check retries.
            Log.w(TAG, "gallery-dl update check failed", t)
        }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        return conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }

    private fun download(url: String, target: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
    }

    fun isNewer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}

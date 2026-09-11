package com.mediasaver

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class DownloadJob(
    val id: String,
    val url: String,
    val selector: String,
    val label: String,
    val kind: Formats.Kind,
    val status: Status = Status.RUNNING,
    val progress: Float = 0f,
    val etaSeconds: Long = -1,
    val detail: String = "Starting",
    val savedAs: String? = null,
    val savedUri: String? = null,
    val mimeType: String? = null,
    val savedLocation: String? = null,
    val error: String? = null,
) {
    enum class Status { RUNNING, SAVING, DONE, FAILED, CANCELLED }
    val active: Boolean get() = status == Status.RUNNING || status == Status.SAVING
}

/**
 * Runs downloads and holds their state. Deliberately a singleton rather than
 * something the Activity owns: a download has to keep running when the user
 * leaves the screen, and the foreground service reads the same state.
 */
object DownloadRepository {

    private const val TAG = "DownloadRepository"

    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<String, Job>()

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun update(id: String, transform: (DownloadJob) -> DownloadJob) {
        _jobs.value = _jobs.value.map { if (it.id == id) transform(it) else it }
    }

    /**
     * Record a download that could not even be started, so the user sees why
     * when they come back instead of the link silently vanishing.
     */
    fun failToStart(id: String, url: String, message: String) {
        val label = DownloadHistory.domainOf(url).ifBlank { url }
        _jobs.value = listOf(
            DownloadJob(id, url, "", label, Formats.Kind.VIDEO,
                status = DownloadJob.Status.FAILED, error = message, detail = "Failed")
        ) + _jobs.value
    }

    fun clearFinished() {
        _jobs.value = _jobs.value.filter { it.active }
    }

    fun cancel(id: String) {
        YoutubeDL.getInstance().destroyProcessById(id)
        GalleryDl.cancel(id)
        running.remove(id)?.cancel()
        update(id) { it.copy(status = DownloadJob.Status.CANCELLED, detail = "Stopped") }
    }

    /**
     * Look the link up and download its best quality, entirely inside the
     * foreground service.
     *
     * Auto-download used to resolve the link in the UI and queue the download
     * from there. Compose does not recompose while the app is in the
     * background, so switching away mid-lookup left the download waiting until
     * the user came back. Doing both steps here means leaving the app changes
     * nothing: the service holds the process for the lookup as well as the
     * download.
     */
    fun startAuto(context: Context, id: String, url: String) {
        if (_jobs.value.any { it.id == id }) return
        val app = context.applicationContext
        val placeholder = DownloadHistory.domainOf(url).ifBlank { url }
        _jobs.value = listOf(
            DownloadJob(id, url, "best", placeholder, Formats.Kind.VIDEO, detail = "Reading link")
        ) + _jobs.value

        running[id] = scope.launch {
            val info = try {
                Extractor.probe(url)
            } catch (t: Throwable) {
                Log.e(TAG, "lookup failed", t)
                update(id) {
                    it.copy(status = DownloadJob.Status.FAILED, error = Extractor.humanError(t, url), detail = "Failed")
                }
                running.remove(id)
                return@launch
            }

            // The user may have cancelled while the lookup was running.
            if (_jobs.value.firstOrNull { it.id == id }?.status == DownloadJob.Status.CANCELLED) {
                running.remove(id)
                return@launch
            }

            val best = info.options.firstOrNull { it.recommended } ?: info.options.firstOrNull()
            if (best == null) {
                update(id) { it.copy(status = DownloadJob.Status.FAILED, error = "Nothing downloadable was found.", detail = "Failed") }
                running.remove(id)
                return@launch
            }

            update(id) {
                it.copy(
                    url = info.url,
                    selector = best.selector,
                    kind = best.kind,
                    label = "${info.title} - ${best.title}",
                    detail = "Starting",
                )
            }
            run(app, id, info.url, best.selector, best.kind)
        }
    }

    fun start(context: Context, id: String, url: String, selector: String, kind: Formats.Kind, label: String) {
        if (_jobs.value.any { it.id == id }) return
        val app = context.applicationContext
        // Newest first: appending buried each new download under the finished
        // ones, which is the opposite of what you want to look at.
        _jobs.value = listOf(DownloadJob(id, url, selector, label, kind)) + _jobs.value
        running[id] = scope.launch { run(app, id, url, selector, kind) }
    }

    /**
     * Download a post's photos with gallery-dl and save each one, so every
     * photo is its own entry in the gallery and in the library.
     */
    private fun runPhotos(app: Context, id: String, url: String) {
        val workDir = File(app.cacheDir, "downloads/$id").apply { mkdirs() }
        try {
            update(id) { it.copy(detail = "Fetching photos") }
            val files = GalleryDl.download(app, id, url, workDir) { done ->
                update(id) { it.copy(detail = if (done == 1) "Fetched 1" else "Fetched $done") }
            }

            if (_jobs.value.firstOrNull { it.id == id }?.status == DownloadJob.Status.CANCELLED) return

            update(id) { it.copy(status = DownloadJob.Status.SAVING, progress = 0.99f, detail = "Saving to your phone") }

            val job = _jobs.value.firstOrNull { it.id == id }
            val base = MediaStoreSaver.sanitize(job?.label?.substringBeforeLast(" - ") ?: "photo", "photo")
            val saved = files.mapIndexed { index, file ->
                val ext = file.extension.lowercase().ifBlank { "jpg" }
                val name = if (files.size == 1) "$base.$ext" else "$base ${index + 1}.$ext"
                val result = MediaStoreSaver.save(app, file, MediaStoreSaver.sanitize(name))
                DownloadHistory.add(
                    app,
                    SavedItem(
                        id = "$id-$index",
                        title = job?.label ?: result.displayName,
                        displayName = result.displayName,
                        uri = result.uri,
                        mimeType = result.mimeType,
                        location = result.location,
                        sizeBytes = file.length(),
                        savedAt = System.currentTimeMillis() + index,
                        sourceDomain = DownloadHistory.domainOf(url),
                    ),
                )
                result
            }

            val first = saved.first()
            update(id) {
                it.copy(
                    status = DownloadJob.Status.DONE,
                    progress = 1f,
                    savedAs = if (saved.size == 1) first.displayName else "${saved.size} files",
                    savedUri = first.uri,
                    mimeType = first.mimeType,
                    savedLocation = first.location,
                    detail = "Saved",
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "photo download failed", t)
            if (_jobs.value.firstOrNull { it.id == id }?.status != DownloadJob.Status.CANCELLED) {
                update(id) {
                    it.copy(status = DownloadJob.Status.FAILED, error = Extractor.humanError(t, url), detail = "Failed")
                }
            }
        } finally {
            running.remove(id)
            workDir.deleteRecursively()
        }
    }

    private fun run(app: Context, id: String, url: String, selector: String, kind: Formats.Kind) {
        if (selector == Extractor.GALLERY_SELECTOR) {
            runPhotos(app, id, url)
            return
        }
        val workDir = File(app.cacheDir, "downloads/$id").apply { mkdirs() }
        try {
            val formatString = Formats.toFormatString(selector, kind)

            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("-f", formatString)
                addOption("-o", File(workDir, "%(title).80B.%(ext)s").absolutePath)
                Extractor.applyCookies(this, url)
                if (kind != Formats.Kind.AUDIO) addOption("--merge-output-format", "mp4")
            }

            // A merged format downloads as two streams, each reporting its own
            // 0-100%. The library only surfaces a single percentage, so a large
            // backwards jump is read as "the next stream started" and the bar is
            // spread across both. Without this the progress runs backwards.
            val expectedStreams = if (formatString.substringBefore("/").contains("+")) 2 else 1
            var streamsDone = 0
            var lastFraction = 0f

            YoutubeDL.getInstance().execute(request, id) { percent, etaSeconds, line ->
                val fraction = (percent / 100f).coerceIn(0f, 1f)
                if (fraction + 0.15f < lastFraction) {
                    streamsDone = (streamsDone + 1).coerceAtMost(expectedStreams - 1)
                }
                lastFraction = fraction
                val span = maxOf(expectedStreams, streamsDone + 1).toFloat()
                val overall = ((streamsDone + fraction) / span).coerceIn(0f, 0.99f)
                update(id) {
                    it.copy(
                        progress = maxOf(it.progress, overall),
                        etaSeconds = etaSeconds,
                        detail = line.trim().ifBlank { it.detail },
                    )
                }
            }

            val produced = workDir.walkTopDown()
                .filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                .toList()
            check(produced.isNotEmpty()) { "Nothing was downloaded." }
            val file = produced.maxByOrNull { it.length() }!!

            update(id) { it.copy(status = DownloadJob.Status.SAVING, progress = 0.99f, detail = "Saving to your phone") }

            val name = MediaStoreSaver.sanitize(file.nameWithoutExtension) + "." + file.extension
            val sizeBytes = file.length()
            val saved = MediaStoreSaver.save(app, file, name)

            val job = _jobs.value.firstOrNull { it.id == id }
            DownloadHistory.add(
                app,
                SavedItem(
                    id = id,
                    title = job?.label ?: saved.displayName,
                    displayName = saved.displayName,
                    uri = saved.uri,
                    mimeType = saved.mimeType,
                    location = saved.location,
                    sizeBytes = sizeBytes,
                    savedAt = System.currentTimeMillis(),
                    sourceDomain = DownloadHistory.domainOf(url),
                ),
            )

            update(id) {
                it.copy(
                    status = DownloadJob.Status.DONE,
                    progress = 1f,
                    savedAs = saved.displayName,
                    savedUri = saved.uri,
                    mimeType = saved.mimeType,
                    savedLocation = saved.location,
                    detail = "Saved",
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "download failed", t)
            val cancelled = _jobs.value.firstOrNull { it.id == id }?.status == DownloadJob.Status.CANCELLED
            if (!cancelled) {
                update(id) {
                    it.copy(status = DownloadJob.Status.FAILED, error = Extractor.humanError(t, url), detail = "Failed")
                }
            }
        } finally {
            running.remove(id)
            workDir.deleteRecursively()
        }
    }
}

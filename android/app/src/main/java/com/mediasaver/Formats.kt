package com.mediasaver

import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo

/**
 * Turns yt-dlp's raw format list into the short list of choices the user sees.
 *
 * This mirrors the logic in ../../server/server.py, which was arrived at by
 * testing against real sites. The rules that matter:
 *
 *  - Resolutions are read from the formats a source actually offers. Matching
 *    against a fixed list of common heights makes sources with non-standard
 *    ladders (100p/350p/750p) offer no video at all.
 *  - HLS variants are ranked below direct streams. They advertise a much higher
 *    bitrate than the equivalent direct stream but frequently answer HTTP 401 to
 *    logged-out clients, so ranking on bitrate alone reliably picks a format
 *    that fails part-way through the download.
 *  - H.264 and AAC are preferred, because VP9 or Opus remuxed into MP4 will not
 *    play in many Android video players.
 */
object Formats {

    enum class Kind { VIDEO, AUDIO, IMAGE, FILE }

    data class Option(
        val selector: String,
        val title: String,
        val subtitle: String,
        val kind: Kind,
        val recommended: Boolean = false,
    )

    data class Row(
        val formatId: String,
        val ext: String,
        val label: String,
        val kind: Kind,
        val height: Int,
        val sizeBytes: Long,
        val sizeHuman: String?,
        val note: String,
        val tbr: Int,
        val vcodec: String,
        val isHls: Boolean,
    )

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "avif")

    fun humanSize(bytes: Long): String? {
        if (bytes <= 0) return null
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024 && i < units.lastIndex) {
            value /= 1024.0
            i++
        }
        return if (value >= 100 || i == 0) "%.0f %s".format(value, units[i])
        else "%.1f %s".format(value, units[i])
    }

    fun humanDuration(seconds: Int): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /**
     * The library's VideoFormat carries no protocol field, unlike yt-dlp's own
     * JSON. An HLS format is recognised instead by its manifest URL, or by an
     * .m3u8 media URL.
     */
    private fun isHls(f: VideoFormat): Boolean {
        if (!f.manifestUrl.isNullOrBlank()) return true
        return f.url?.contains(".m3u8", ignoreCase = true) == true
    }

    private fun isRealMedia(f: VideoFormat): Boolean {
        if (f.formatId.isNullOrBlank()) return false
        if (f.ext.equals("mhtml", ignoreCase = true)) return false
        if (f.vcodec == "images") return false
        if (f.formatNote?.contains("storyboard", ignoreCase = true) == true) return false
        return true
    }

    private fun describe(f: VideoFormat): Row {
        val vcodec = f.vcodec
        val acodec = f.acodec
        // yt-dlp writes the literal "none" for an absent codec and leaves the
        // field unset when it does not know. Conflating those makes a direct
        // link to a video file look like audio.
        val codecsKnown = !vcodec.isNullOrBlank() || !acodec.isNullOrBlank()
        val hasVideo = !vcodec.isNullOrBlank() && vcodec != "none"
        val hasAudio = !acodec.isNullOrBlank() && acodec != "none"
        val ext = (f.ext ?: "").lowercase()

        val kind = when {
            !codecsKnown -> Kind.FILE
            hasVideo && hasAudio -> Kind.VIDEO
            hasVideo -> Kind.VIDEO          // video-only; paired with audio later
            hasAudio -> Kind.AUDIO
            else -> Kind.FILE
        }
        val videoOnly = hasVideo && !hasAudio

        val size = if (f.fileSize > 0) f.fileSize else f.fileSizeApproximate
        val label = when {
            hasVideo && f.height > 0 -> buildString {
                append("${f.height}p")
                if (f.fps >= 50) append(f.fps)
            }
            hasVideo -> "Video"
            hasAudio -> if (f.abr > 0) "Audio ${f.abr}k" else "Audio"
            ext in IMAGE_EXTS -> "Image"
            ext.isNotEmpty() -> "${ext.uppercase()} file"
            else -> "File"
        }

        return Row(
            formatId = f.formatId!!,
            ext = ext,
            label = label,
            kind = if (videoOnly) Kind.VIDEO else kind,
            height = f.height,
            sizeBytes = size,
            sizeHuman = humanSize(size),
            note = f.formatNote ?: "",
            tbr = f.tbr,
            vcodec = vcodec ?: "",
            isHls = isHls(f),
        ).let { row ->
            // Track muxed vs video-only separately from Kind, which the UI uses.
            if (videoOnly) row.copy(note = row.note) else row
        }
    }

    /** Preference between two formats offering the same resolution. */
    private fun rank(r: Row): Triple<Boolean, Boolean, Int> =
        Triple(!r.isHls, r.vcodec.startsWith("avc1") || r.vcodec.startsWith("h264"), r.tbr)

    private val rankComparator = compareBy<Row>({ rank(it).first }, { rank(it).second }, { rank(it).third })

    fun build(info: VideoInfo): Pair<List<Option>, List<Row>> {
        val raw = (info.formats ?: arrayListOf()).filter { isRealMedia(it) }
        val rows = raw.map { describe(it) }

        val muxed = raw.filter { f ->
            !f.vcodec.isNullOrBlank() && f.vcodec != "none" &&
                !f.acodec.isNullOrBlank() && f.acodec != "none" && f.height > 0
        }.map { describe(it) }

        val videoOnly = raw.filter { f ->
            !f.vcodec.isNullOrBlank() && f.vcodec != "none" &&
                (f.acodec.isNullOrBlank() || f.acodec == "none") && f.height > 0
        }.map { describe(it) }

        val audioOnly = raw.filter { f ->
            (f.vcodec.isNullOrBlank() || f.vcodec == "none") &&
                !f.acodec.isNullOrBlank() && f.acodec != "none"
        }.map { describe(it) }

        val plainFiles = rows.filter { it.kind == Kind.FILE }

        val options = LinkedHashMap<String, Option>()
        fun add(o: Option) { options.putIfAbsent(o.selector, o) }

        val isVideo = muxed.isNotEmpty() || videoOnly.isNotEmpty()

        if (isVideo) {
            add(Option("best", "Best quality", "Highest video + audio available", Kind.VIDEO, true))

            val heights = (muxed + videoOnly).map { it.height }.filter { it > 0 }
                .distinct().sortedDescending().take(8)

            for (h in heights) {
                val bestMuxed = muxed.filter { it.height == h }.maxWithOrNull(rankComparator)
                val bestSplit = videoOnly.filter { it.height == h }.maxWithOrNull(rankComparator)
                when {
                    bestMuxed != null -> add(
                        Option(
                            selector = bestMuxed.formatId,
                            title = "${h}p",
                            subtitle = listOfNotNull(
                                bestMuxed.ext.uppercase().ifEmpty { null },
                                bestMuxed.sizeHuman,
                                "video + audio",
                            ).joinToString(" · "),
                            kind = Kind.VIDEO,
                        )
                    )
                    bestSplit != null && audioOnly.isNotEmpty() -> add(
                        Option(
                            selector = "${bestSplit.formatId}+bestaudio",
                            title = "${h}p",
                            // The merge is written to MP4, so name the container
                            // the user actually receives, not the source stream's.
                            subtitle = listOfNotNull("MP4", bestSplit.sizeHuman, "merged with audio")
                                .joinToString(" · "),
                            kind = Kind.VIDEO,
                        )
                    )
                }
            }
        }

        if (audioOnly.isNotEmpty()) {
            val best = audioOnly.maxWithOrNull(rankComparator)
            add(
                Option(
                    selector = best?.formatId ?: "bestaudio",
                    title = "Audio only",
                    subtitle = listOfNotNull(
                        best?.ext?.uppercase()?.ifEmpty { null },
                        best?.sizeHuman,
                        "no video",
                    ).joinToString(" · "),
                    kind = Kind.AUDIO,
                    recommended = !isVideo,
                )
            )
        }

        if (options.isEmpty() && plainFiles.isNotEmpty()) {
            // A direct file link, an image, or a source publishing the same title
            // as several alternative files. mp4 plays everywhere; size is a poor
            // proxy for "best".
            val ranked = plainFiles.sortedWith(
                compareBy(
                    { if (it.ext == "mp4") 0 else if (it.ext in setOf("m4v", "mov", "webm")) 1 else 2 },
                    { -it.sizeBytes },
                )
            )
            ranked.take(8).forEachIndexed { i, row ->
                val isImage = row.ext in IMAGE_EXTS
                add(
                    Option(
                        selector = row.formatId,
                        title = when {
                            isImage -> "Save image"
                            ranked.size > 1 -> row.ext.uppercase().ifEmpty { "Download" }
                            else -> "Download"
                        },
                        subtitle = listOfNotNull(row.ext.uppercase().ifEmpty { null }, row.sizeHuman)
                            .joinToString(" · ").ifEmpty { "Single file" },
                        kind = if (isImage) Kind.IMAGE else Kind.FILE,
                        recommended = i == 0,
                    )
                )
            }
        }

        if (options.isEmpty()) {
            val ext = (info.ext ?: "").lowercase()
            add(
                Option(
                    selector = "best",
                    title = if (ext in IMAGE_EXTS) "Save image" else "Download",
                    subtitle = ext.uppercase().ifEmpty { "Single file" },
                    kind = if (ext in IMAGE_EXTS) Kind.IMAGE else Kind.FILE,
                    recommended = true,
                )
            )
        }

        return options.values.toList() to rows
    }

    /**
     * Expand a UI choice into a yt-dlp format string. Merged output goes to MP4,
     * so audio is steered towards AAC and video towards H.264; each preference
     * falls back to whatever exists so nothing becomes undownloadable.
     */
    fun toFormatString(selector: String, kind: Kind): String = when {
        selector == "best" && kind == Kind.AUDIO -> "bestaudio[ext=m4a]/bestaudio/best"
        selector == "best" ->
            "bestvideo[vcodec^=avc1]+bestaudio[ext=m4a]/" +
                "bestvideo*+bestaudio[ext=m4a]/" +
                "bestvideo*+bestaudio/best"
        selector == "bestaudio" || selector == "bestvideo" -> "$selector/best"
        selector.endsWith("+bestaudio") -> {
            val v = selector.removeSuffix("+bestaudio")
            "$v+bestaudio[ext=m4a]/$v+bestaudio/$v/best"
        }
        else -> "$selector/best"
    }
}

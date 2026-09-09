package com.mediasaver

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

/**
 * Copies a finished download into the phone's shared storage, so it appears in
 * Files, the gallery and music apps like anything else the user saved. Uses
 * MediaStore, which needs no storage permission on Android 10 and above.
 */
object MediaStoreSaver {


    /**
     * The content Uri matters as much as the bytes: it is what lets the app open
     * or share the file afterwards, instead of leaving the user to hunt for it.
     */
    data class Saved(
        val uri: String,
        val displayName: String,
        val mimeType: String,
        val location: String,
    )

    fun mimeTypeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "mp4", "m4v" -> "video/mp4"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                "m4a" -> "audio/mp4"
                "opus", "ogg" -> "audio/ogg"
                "mp3" -> "audio/mpeg"
                else -> "application/octet-stream"
            }
    }

    fun save(context: Context, source: File, displayName: String): Saved {
        val resolver = context.contentResolver
        val mime = mimeTypeFor(displayName)

        val collection = when {
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val folder = when {
            mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
            mime.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
            mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val relative = "$folder/" + Settings.safeFolder(Settings.state.value.folder)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: error("Could not create a file in shared storage.")

        try {
            resolver.openOutputStream(uri).use { out ->
                checkNotNull(out) { "Could not open the destination file." }
                source.inputStream().use { it.copyTo(out, 1 shl 16) }
            }
        } catch (t: Throwable) {
            // Leave no half-written entry behind for the gallery to show.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        return Saved(uri.toString(), displayName, mime, relative)
    }

    /** True if the saved entry is still there; the user may have deleted it. */
    fun exists(context: Context, uriString: String): Boolean = runCatching {
        context.contentResolver.query(Uri.parse(uriString), arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    /** Names that are illegal on Windows, which matters once a file is copied off the phone. */
    private val RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /**
     * Make a file name safe on every filesystem the file might reach - the
     * phone's, a Windows PC it gets copied to, a FAT SD card.
     *
     * Titles come from arbitrary web pages, so they routinely contain path
     * separators, colons, emoji, right-to-left marks and trailing dots.
     */
    fun sanitize(name: String, fallback: String = "media"): String {
        var cleaned = name
            // Control characters, and the bidi/zero-width marks that make a name
            // display differently from what it is.
            .filter { it.code >= 0x20 && it.code != 0x7F }
            .filterNot { it.code in 0x200B..0x200F || it.code in 0x202A..0x202E }
            .map { if (it in "\\/:*?\"<>|") '_' else it }
            .joinToString("")
            .replace(Regex("""\s+"""), " ")
            .trim()

        // A leading dot hides the file; trailing dots and spaces are silently
        // dropped by Windows, which then cannot find its own file.
        cleaned = cleaned.trim(' ', '.')

        if (cleaned.isBlank()) cleaned = fallback

        val extension = cleaned.substringAfterLast('.', "").takeIf {
            it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit)
        }
        var stem = if (extension != null) cleaned.substringBeforeLast('.') else cleaned
        stem = stem.trim(' ', '.').ifBlank { fallback }

        if (stem.uppercase() in RESERVED) stem = "_$stem"

        // Keep well inside the 255-byte limit once non-ASCII is encoded.
        while (stem.toByteArray().size > 180) stem = stem.dropLast(1)
        stem = stem.trim(' ', '.').ifBlank { fallback }

        return if (extension != null) "$stem.$extension" else stem
    }

    /** Remove a file this app saved. Files we created are ours to delete. */
    fun delete(context: Context, uriString: String): Boolean = runCatching {
        context.contentResolver.delete(Uri.parse(uriString), null, null) > 0
    }.getOrDefault(false)
}

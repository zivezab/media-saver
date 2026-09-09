package com.mediasaver

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Opening and sharing a file the app saved. MediaStore content Uris can be handed
 * to other apps as long as read permission travels with the intent.
 */
object SavedMedia {

    fun open(context: Context, uriString: String, mimeType: String?) {
        val uri = Uri.parse(uriString)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType ?: context.contentResolver.getType(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launch(context, intent, "No app on this phone can open that file.")
    }

    fun share(context: Context, uriString: String, mimeType: String?) {
        val uri = Uri.parse(uriString)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType ?: context.contentResolver.getType(uri) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(
            context,
            Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            "Nothing available to share with.",
        )
    }

    private fun launch(context: Context, intent: Intent, failureMessage: String) {
        try {
            context.startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(context, failureMessage, Toast.LENGTH_SHORT).show()
        }
    }
}

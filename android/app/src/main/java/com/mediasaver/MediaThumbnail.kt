package com.mediasaver

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Poster frame for a saved file. MediaStore generates these itself on Android 10
 * and above, so no decoding library is needed and nothing has to be cached by us.
 */
@Composable
fun MediaThumbnail(item: SavedItem, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val context = LocalContext.current
    var bitmap by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(item.uri) { mutableStateOf(false) }

    LaunchedEffect(item.uri, enabled) {
        if (!enabled) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(Uri.parse(item.uri), Size(400, 400), null)
            }.getOrNull()
        }
        if (loaded == null) failed = true else bitmap = loaded
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        if (enabled && image != null && !failed) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                when {
                    item.isAudio -> Icons.Filled.Audiotrack
                    item.isVideo -> Icons.Filled.Movie
                    else -> Icons.Filled.Image
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

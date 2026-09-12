package com.mediasaver

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Poster frame for a saved file. MediaStore generates these itself on Android 10
 * and above, so no decoding library is needed and nothing has to be cached by us.
 */
@Composable
fun MediaThumbnail(
    item: SavedItem,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * Draw a badge over the picture marking it as tappable. Only shown once a
     * real frame has loaded - over the placeholder icon it would just be two
     * icons on top of each other.
     */
    showPlayBadge: Boolean = false,
) {
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
                // Named once it is a control rather than decoration, so screen
                // readers announce what tapping it would open.
                contentDescription = if (showPlayBadge) item.displayName else null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
            if (showPlayBadge) {
                Icon(
                    // A play triangle on a photo would promise playback.
                    if (item.isImage) Icons.Filled.Fullscreen else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .background(Color(0x73000000), CircleShape)
                        .padding(4.dp)
                        .size(20.dp),
                )
            }
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

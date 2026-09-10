package com.mediasaver

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs

/**
 * Plays a saved file inside the app.
 *
 * Handing the file to another app worked, but it threw the user out of Media
 * Saver to do it. This keeps playback here, and keeps "open in another app" as
 * a way out for anything ExoPlayer cannot decode - a VP9-in-MP4 4K file, say.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerSheet(
    item: SavedItem,
    onOpenExternally: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val settings by Settings.state.collectAsState()
    var error by remember(item.uri) { mutableStateOf<String?>(null) }

    // Pinch state. Translation is stored in pixels and re-clamped whenever the
    // scale changes, so the picture can never be dragged off the screen.
    var scale by remember(item.uri) { mutableStateOf(1f) }
    var offset by remember(item.uri) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var cropToFill by remember(item.uri) { mutableStateOf(false) }
    var playerView by remember(item.uri) { mutableStateOf<PlayerView?>(null) }

    fun clampOffset(candidate: Offset, forScale: Float): Offset {
        if (forScale <= 1f) return Offset.Zero
        val maxX = viewport.width * (forScale - 1f) / 2f
        val maxY = viewport.height * (forScale - 1f) / 2f
        return Offset(
            candidate.x.coerceIn(-maxX, maxX),
            candidate.y.coerceIn(-maxY, maxY),
        )
    }

    fun resetZoom() {
        scale = 1f
        offset = Offset.Zero
    }

    val player = remember(item.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(item.uri)))
            prepare()
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                    error = "This file cannot be played here (${e.errorCodeName}). " +
                        "Try opening it in another app."
                }
            })
        }
    }

    // Applied as an effect rather than at construction so toggling the setting
    // takes hold on a video that is already playing.
    LaunchedEffect(player, settings.loopPlayback) {
        player.repeatMode =
            if (settings.loopPlayback) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    // Stop playing when the app goes to the background. Without this the audio
    // carries on over whatever the user switched to.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, settings.pauseOnLeave) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && settings.pauseOnLeave) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Releasing matters: an ExoPlayer left alive holds a codec and keeps audio
    // focus even after the dialog is gone.
    DisposableEffect(player) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            player.release()
            (context as? Activity)?.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BackHandler(enabled = true) { onDismiss() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { viewport = it }
                // PlayerView is a real Android View and consumes the touches it
                // gets, so a plain pointerInput on this Box never sees them.
                // Watching the Initial pass gets first look instead - and only
                // multi-finger events are taken, so single taps still reach the
                // player's own controller.
                .pointerInput(item.uri) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.size < 2) continue

                            val gestureZoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (gestureZoom == 1f && pan == Offset.Zero) continue

                            val next = (scale * gestureZoom).coerceIn(1f, 6f)
                            offset = if (next <= 1f) Offset.Zero
                            else clampOffset(offset + pan, next)
                            scale = next

                            // Claim the gesture so the controller does not also
                            // react to the fingers moving.
                            event.changes.forEach { it.consume() }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
                factory = { ctx ->
                    // Inflated rather than constructed: the TextureView surface
                    // type can only be set in XML, and without it the video
                    // would not follow the zoom.
                    (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).apply {
                        this.player = player
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        playerView = this
                    }
                },
                update = { view ->
                    view.resizeMode =
                        if (cropToFill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else AspectRatioFrameLayout.RESIZE_MODE_FIT
                },
            )

            if (abs(scale - 1f) > 0.01f) {
                // Doubles as the way back: single taps belong to the player's
                // controller now, so zoom needs its own reset affordance.
                Text(
                    "%.1f× · reset".format(scale),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(horizontal = 16.dp, vertical = 90.dp)
                        .background(Color(0xAA000000))
                        .clickable { resetZoom() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            error?.let { message ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xCC000000))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Controls sit above the video surface so they stay reachable.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
                Text(
                    item.displayName,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { scale = (scale - 0.5f).coerceAtLeast(1f).also { if (it <= 1f) offset = Offset.Zero } },
                    enabled = scale > 1f,
                ) {
                    Icon(
                        Icons.Filled.ZoomOut,
                        contentDescription = "Zoom out",
                        tint = if (scale > 1f) Color.White else Color(0x66FFFFFF),
                    )
                }
                IconButton(
                    onClick = {
                        val next = (scale + 0.5f).coerceAtMost(6f)
                        offset = clampOffset(offset, next)
                        scale = next
                    },
                    enabled = scale < 6f,
                ) {
                    Icon(
                        Icons.Filled.ZoomIn,
                        contentDescription = "Zoom in",
                        tint = if (scale < 6f) Color.White else Color(0x66FFFFFF),
                    )
                }
                IconButton(onClick = {
                    cropToFill = !cropToFill
                    resetZoom()
                }) {
                    Icon(
                        if (cropToFill) Icons.Filled.CropFree else Icons.Filled.Fullscreen,
                        contentDescription = if (cropToFill) "Fit the whole frame" else "Crop to fill the screen",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = {
                    val activity = context as? Activity
                    activity?.requestedOrientation =
                        if (activity?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        }
                }) {
                    Icon(Icons.Filled.ScreenRotation, contentDescription = "Rotate", tint = Color.White)
                }
                IconButton(onClick = onOpenExternally) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = "Open in another app", tint = Color.White)
                }
            }
        }
    }
}

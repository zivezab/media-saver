package com.mediasaver

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.content.ComponentName
import android.view.LayoutInflater
import android.view.View
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
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
import androidx.core.content.ContextCompat
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
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
    /** Everything the viewer was opened on, so the player can walk it too. */
    queue: List<SavedItem>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    onOpenExternally: () -> Unit,
    onDismiss: () -> Unit,
) {
    val item = queue.getOrNull(index) ?: return
    val position = index + 1
    val total = queue.size
    val onNext = if (index < queue.lastIndex) ({ onIndexChange(index + 1) }) else null
    val onPrevious = if (index > 0) ({ onIndexChange(index - 1) }) else null

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

    // The player lives in PlaybackService rather than here, so playback and its
    // notification survive this screen going away - see PlaybackService.
    var player by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val pending = MediaController.Builder(context, token).buildAsync()
        pending.addListener(
            { player = runCatching { pending.get() }.getOrNull() },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            // Only this connection goes; whatever is playing carries on, which
            // is what makes a swipe to a photo - or to another app - harmless.
            player = null
            MediaController.releaseFuture(pending)
        }
    }

    // Photos cannot be handed to the player, so the queue it gets is the
    // playable part of what the viewer is showing.
    val playable = remember(queue) { queue.filterNot { it.isImage } }

    LaunchedEffect(player, playable, item.uri) {
        val controller = player ?: return@LaunchedEffect
        val target = playable.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0)
        val loaded = controller.mediaItemCount == playable.size &&
            (0 until controller.mediaItemCount).all {
                controller.getMediaItemAt(it).mediaId == playable[it].uri
            }
        if (!loaded) {
            controller.setMediaItems(playable.map(::mediaItemFor), target, C.TIME_UNSET)
            controller.prepare()
            controller.play()
        } else if (controller.currentMediaItemIndex != target) {
            controller.seekTo(target, C.TIME_UNSET)
            controller.play()
        }
    }

    DisposableEffect(player, queue) {
        val controller = player ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            // Fires for "next" pressed in the notification as much as for a
            // swipe here, so the screen follows whichever was used.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId ?: return
                val where = queue.indexOfFirst { it.uri == id }
                if (where >= 0 && where != index) onIndexChange(where)
            }

            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                error = "This file cannot be played here (${e.errorCodeName}). " +
                    "Try opening it in another app."
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    // The always-on time. Polled rather than pushed: ExoPlayer reports position
    // only when asked, and four reads a second keeps the seconds honest.
    var positionMs by remember(item.uri) { mutableLongStateOf(0L) }
    var durationMs by remember(item.uri) { mutableLongStateOf(0L) }
    // The control bar has its own time; showing both at once would double up.
    var controlsShown by remember { mutableStateOf(false) }
    LaunchedEffect(player, settings.alwaysShowTime) {
        val controller = player ?: return@LaunchedEffect
        if (!settings.alwaysShowTime) return@LaunchedEffect
        while (true) {
            positionMs = controller.currentPosition
            durationMs = controller.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            delay(250)
        }
    }

    // Applied as an effect rather than at construction so toggling the setting
    // takes hold on a video that is already playing.
    LaunchedEffect(player, settings.loopPlayback) {
        player?.repeatMode =
            if (settings.loopPlayback) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    // Leaving the app pauses only when the user asked for that; otherwise the
    // service keeps it going and the notification holds the controls.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, settings.backgroundPlayback, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !settings.backgroundPlayback) {
                player?.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            (context as? Activity)?.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Closing the viewer ends playback: with the screen gone there is nothing
    // to say what is playing except the notification, and the user asked for
    // it to stop. Backgrounding the app is the case that keeps playing.
    val stopPlayback = {
        player?.run {
            stop()
            clearMediaItems()
        }
        Unit
    }
    val close = {
        stopPlayback()
        onDismiss()
    }
    // Another app is about to play the same file; two at once helps nobody.
    val openElsewhere = {
        stopPlayback()
        onOpenExternally()
    }

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BackHandler(enabled = true) { close() }

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
                    // A vertical flick has to travel this far before it counts,
                    // so ordinary taps and the seek bar are left alone.
                    val swipeThreshold = size.height * 0.12f
                    var dragging = false
                    var travelled = Offset.Zero
                    var handled = false

                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)

                            if (event.changes.size >= 2) {
                                // Two fingers: zoom and pan, never navigation.
                                dragging = false
                                handled = true

                                val gestureZoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (gestureZoom == 1f && pan == Offset.Zero) continue

                                val zoomed = (scale * gestureZoom).coerceIn(1f, 6f)
                                offset = if (zoomed <= 1f) Offset.Zero
                                else clampOffset(offset + pan, zoomed)
                                scale = zoomed

                                // Claim the gesture so the controller does not
                                // also react to the fingers moving.
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            val touch = event.changes.firstOrNull() ?: continue
                            if (!touch.pressed) {
                                dragging = false
                                continue
                            }
                            // Only a drag that starts here counts. Moving to the
                            // next video restarts this block while the finger is
                            // still on the glass, and that same drag must not
                            // carry on into a second skip.
                            if (!dragging) {
                                if (touch.previousPressed) continue
                                dragging = true
                                handled = false
                                travelled = Offset.Zero
                            }
                            travelled += touch.position - touch.previousPosition

                            // Only when the picture is not zoomed in - once it
                            // is, dragging means moving around the frame.
                            if (handled || scale > 1f) continue
                            val vertical = kotlin.math.abs(travelled.y)
                            if (vertical > swipeThreshold && vertical > kotlin.math.abs(travelled.x) * 1.5f) {
                                if (travelled.y < 0) onNext?.invoke() else onPrevious?.invoke()
                                handled = true
                                touch.consume()
                            }
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
                        setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { visibility ->
                                controlsShown = visibility == View.VISIBLE
                            }
                        )
                        playerView = this
                    }
                },
                update = { view ->
                    // The view outlives any one video: swiping to the next
                    // file builds a new player, and the view has to follow it
                    // rather than keep showing the released one.
                    if (view.player !== player) view.player = player
                    if (playerView !== view) playerView = view

                    // The buttons are hidden one by one, not as their shared
                    // container: Media3's layout manager makes that container
                    // visible again every time the controls animate in. Rewind
                    // and forward have an API; play/pause only ever has its icon
                    // changed, so hiding it directly sticks. The seek bar and
                    // time are a separate bar and stay.
                    val buttons = settings.playbackButtonsOnTap
                    view.setShowRewindButton(buttons)
                    view.setShowFastForwardButton(buttons)
                    view.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
                        ?.visibility = if (buttons) View.VISIBLE else View.GONE
                    // Media3 only ever positions this tint, never recolours it,
                    // so clearing its background here sticks.
                    view.findViewById<View>(androidx.media3.ui.R.id.exo_controls_background)
                        ?.setBackgroundColor(
                            if (settings.alwaysShowTime) android.graphics.Color.TRANSPARENT
                            else SCRIM
                        )

                    // Without the buttons a tap is the only way to pause. This
                    // listener fires from PlayerView's own click, so taps on the
                    // seek bar - which handles its own touches - do not reach it.
                    view.setOnClickListener(
                        if (buttons) null
                        else View.OnClickListener {
                            val p = view.player ?: return@OnClickListener
                            if (p.playWhenReady && p.playbackState != Player.STATE_ENDED) {
                                p.pause()
                            } else {
                                if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
                                p.play()
                            }
                        }
                    )
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

            if (settings.alwaysShowTime && !controlsShown && error == null) {
                // Plain shadowed text rather than a bar: nothing gray over the
                // picture is the point of the setting.
                Text(
                    "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge.copy(
                        shadow = Shadow(Color.Black, Offset(0f, 1f), blurRadius = 6f),
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
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
                IconButton(onClick = close) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        item.displayName,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (total > 1) {
                        Text(
                            "$position of $total",
                            color = Color(0xCCFFFFFF),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
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
                IconButton(onClick = openElsewhere) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = "Open in another app", tint = Color.White)
                }
            }
        }
    }
}

/** Media3's own tint, restored when the always-show-time setting is off. */
private const val SCRIM = 0x98000000.toInt()

/** 00:07, 12:34, 1:02:03 - the same style as the control bar's own time. */
private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * Titles and the site name travel with the file, so the notification and lock
 * screen say what is playing rather than showing a file path.
 */
private fun mediaItemFor(item: SavedItem): MediaItem = MediaItem.Builder()
    .setMediaId(item.uri)
    .setUri(Uri.parse(item.uri))
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(item.title.ifBlank { item.displayName })
            .setArtist(item.sourceDomain.ifBlank { null })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
    )
    .build()

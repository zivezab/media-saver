package com.mediasaver

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Holds the player, so a video keeps playing after the app goes to the
 * background - which is the point when what you are playing is music.
 *
 * Playing inside the Activity was enough while the app was in front, but
 * Android gives a backgrounded process no promises, and there was nowhere to
 * press pause without coming back to the app. A MediaSessionService is the
 * supported way to do both: the system keeps the service alive while it is
 * playing, and the session it publishes is what draws the controls in the
 * notification shade, on the lock screen, and on a watch or car screen.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                // Take audio focus, so this pauses for a call and ducks for a
                // navigation prompt rather than talking over them.
                true,
            )
            // Unplugging headphones pauses, instead of surprising the room.
            .setHandleAudioBecomingNoisy(true)
            .build()

        session = MediaSession.Builder(this, player)
            .setCallback(Callback())
            .setSessionActivity(openApp())
            .setCustomLayout(listOf(stopButton()))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    /**
     * Swiping the app off the recents screen ends playback, unless something is
     * still playing - a paused player has no business holding a notification
     * for an app that is gone.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Play/pause, next and previous come as standard; stopping does not. */
    private fun stopButton(): CommandButton = CommandButton.Builder()
        .setDisplayName("Stop")
        .setIconResId(androidx.media3.session.R.drawable.media3_icon_stop)
        .setSessionCommand(SessionCommand(ACTION_STOP, Bundle.EMPTY))
        .build()

    private inner class Callback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_STOP, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_STOP) {
                session.player.stop()
                session.player.clearMediaItems()
                stopSelf()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    companion object {
        private const val ACTION_STOP = "com.mediasaver.STOP"
    }
}

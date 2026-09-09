package com.mediasaver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while downloads run and shows their progress in the
 * notification shade. Android will otherwise freeze or kill the app as soon as
 * the user switches away, part-way through a large video.
 */
class DownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "downloads"
        const val DONE_CHANNEL_ID = "completed"
        private const val NOTIFICATION_ID = 1

        private const val EXTRA_ID = "id"
        private const val EXTRA_URL = "url"
        private const val EXTRA_SELECTOR = "selector"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_LABEL = "label"

        fun enqueue(
            context: Context,
            url: String,
            selector: String,
            kind: Formats.Kind,
            label: String,
        ): String {
            val id = DownloadRepository.newId()
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_SELECTOR, selector)
                putExtra(EXTRA_KIND, kind.name)
                putExtra(EXTRA_LABEL, label)
            }
            context.startForegroundService(intent)
            return id
        }

        fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Progress while media is being saved" }
            )
            // Finishing is the moment worth surfacing, so it gets its own channel
            // the user can leave audible while silencing progress.
            manager.createNotificationChannel(
                NotificationChannel(DONE_CHANNEL_ID, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "When a download is saved and ready to play" }
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Jobs already announced, so the collector does not re-post on every tick. */
    private val announced = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        startForeground(NOTIFICATION_ID, buildNotification("Preparing", null, 0, true))

        scope.launch {
            DownloadRepository.jobs.collectLatest { jobs ->
                val active = jobs.filter { it.active }
                if (active.isEmpty()) {
                    jobs.filter { it.id !in announced }.forEach { finished ->
                        announced += finished.id
                        announceFinished(finished)
                    }
                    // Everything finished; drop the foreground state so the
                    // ongoing notification does not linger.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                jobs.filter { it.id !in announced && !it.active }.forEach { finished ->
                    announced += finished.id
                    announceFinished(finished)
                }

                val head = active.first()
                val title = if (active.size == 1) head.label else "${active.size} downloads"
                val percent = (head.progress * 100).toInt()
                val indeterminate = head.status == DownloadJob.Status.SAVING || head.progress <= 0f
                notify(buildNotification(title, head.detail, percent, indeterminate))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        val url = intent?.getStringExtra(EXTRA_URL)
        val selector = intent?.getStringExtra(EXTRA_SELECTOR)
        val kindName = intent?.getStringExtra(EXTRA_KIND)
        val label = intent?.getStringExtra(EXTRA_LABEL)

        if (id != null && url != null && selector != null && kindName != null) {
            DownloadRepository.start(
                context = this,
                id = id,
                url = url,
                selector = selector,
                kind = runCatching { Formats.Kind.valueOf(kindName) }.getOrDefault(Formats.Kind.VIDEO),
                label = label ?: "Download",
            )
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * A tappable "it is ready" notification. Tapping opens the saved file in a
     * player, which is the whole point: the user should not have to go looking
     * for what they just downloaded.
     */
    private fun announceFinished(job: DownloadJob) {
        val manager = getSystemService(NotificationManager::class.java)
        val id = NOTIFICATION_ID + 1 + (job.id.hashCode() and 0xFFFF)

        val builder = NotificationCompat.Builder(this, DONE_CHANNEL_ID)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)

        when (job.status) {
            DownloadJob.Status.DONE -> {
                val uri = job.savedUri
                val open = if (uri != null) {
                    // Opens the app's own player rather than handing the file to
                    // another app, so tapping the notification stays in context.
                    PendingIntent.getActivity(
                        this,
                        id,
                        Intent(this, MainActivity::class.java).apply {
                            action = MainActivity.ACTION_PLAY
                            putExtra(MainActivity.EXTRA_PLAY_URI, uri)
                            putExtra(MainActivity.EXTRA_PLAY_MIME, job.mimeType)
                            putExtra(MainActivity.EXTRA_PLAY_NAME, job.savedAs ?: job.label)
                            putExtra(MainActivity.EXTRA_PLAY_LOCATION, job.savedLocation.orEmpty())
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                } else null

                builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Saved - tap to play")
                    .setContentText(job.savedAs ?: job.label)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(
                        listOfNotNull(job.savedAs, job.savedLocation).joinToString("\n")
                    ))
                if (open != null) builder.setContentIntent(open)
            }

            DownloadJob.Status.FAILED -> builder
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Download failed")
                .setContentText(job.error ?: job.label)
                .setStyle(NotificationCompat.BigTextStyle().bigText(job.error ?: job.label))
                .setContentIntent(
                    PendingIntent.getActivity(
                        this, id, Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )

            else -> return   // cancelled needs no announcement
        }

        manager.notify(id, builder.build())
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        title: String,
        text: String?,
        percent: Int,
        indeterminate: Boolean,
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(100, percent, indeterminate)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}

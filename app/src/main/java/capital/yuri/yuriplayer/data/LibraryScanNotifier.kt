package capital.yuri.yuriplayer.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import capital.yuri.yuriplayer.R
import capital.yuri.yuriplayer.activities.MainActivity

/**
 * Ongoing “live” notification while a library scan or remote sync is running.
 * Used by [LibraryScanService] (foreground) and as a plain progress notifier.
 */
class LibraryScanNotifier(
    context: Context
) {
    private val app = context.applicationContext
    private val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        ensureChannel()
    }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Library scan",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress while indexing local or remote libraries"
                setShowBadge(false)
            }
        )
    }

    fun build(
        title: String,
        text: String,
        progress: Int? = null,
        max: Int? = null,
        indeterminate: Boolean = progress == null
    ): Notification {
        val open = PendingIntent.getActivity(
            app,
            0,
            Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        when {
            progress != null && max != null && max > 0 ->
                builder.setProgress(max, progress.coerceIn(0, max), false)
            indeterminate ->
                builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    fun show(
        title: String,
        text: String,
        progress: Int? = null,
        max: Int? = null
    ) {
        nm.notify(NOTIFICATION_ID, build(title, text, progress, max))
    }

    fun update(
        title: String,
        text: String,
        progress: Int? = null,
        max: Int? = null
    ) {
        show(title, text, progress, max)
    }

    /** Brief completion toast-style notification, then clear ongoing state. */
    fun finish(title: String, text: String) {
        val done = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(4_000L)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(NOTIFICATION_ID, done)
    }

    fun cancel() {
        nm.cancel(NOTIFICATION_ID)
    }

    companion object {
        const val CHANNEL_ID = "yuri_library_scan"
        const val NOTIFICATION_ID = 4201
    }
}

package capital.yuri.yuriplayer.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import capital.yuri.yuriplayer.activities.MainActivity

/**
 * Ongoing progress notification while a library scan or remote sync is running.
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
        // Recreate if we previously used IMPORTANCE_LOW (invisible on many OEMs)
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null && existing.importance < NotificationManager.IMPORTANCE_DEFAULT) {
            nm.deleteNotificationChannel(CHANNEL_ID)
        }
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Library scan",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Progress while indexing local or remote libraries"
                setShowBadge(false)
                setSound(null, null)
            }
        )
    }

    fun areNotificationsEnabled(): Boolean =
        NotificationManagerCompat.from(app).areNotificationsEnabled()

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
            // System icon is always valid; adaptive launcher vectors often fail as smallIcon
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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
        try {
            nm.notify(NOTIFICATION_ID, build(title, text, progress, max))
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied — FGS may still hold the service notification
            android.util.Log.w(TAG, "notify blocked: ${e.message}")
        }
    }

    fun update(
        title: String,
        text: String,
        progress: Int? = null,
        max: Int? = null
    ) {
        show(title, text, progress, max)
    }

    fun finish(title: String, text: String) {
        try {
            val done = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setTimeoutAfter(6_000L)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(NOTIFICATION_ID, done)
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "finish notify blocked: ${e.message}")
        }
    }

    fun cancel() {
        nm.cancel(NOTIFICATION_ID)
    }

    companion object {
        private const val TAG = "LibraryScanNotifier"
        const val CHANNEL_ID = "yuri_library_scan"
        const val NOTIFICATION_ID = 4201
    }
}

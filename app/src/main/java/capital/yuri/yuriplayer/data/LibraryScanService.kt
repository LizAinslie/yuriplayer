package capital.yuri.yuriplayer.data

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.koin.android.ext.android.inject

/**
 * Short-lived foreground service that hosts library scans / remote syncs so they
 * keep running when the UI is backgrounded, with a live progress notification.
 *
 * Work runs at **lowest** background priority so touch / composition keep the CPU.
 * Notification updates go through [LibraryScanNotifier] only — never re-call
 * startForeground on every progress tick.
 */
class LibraryScanService : Service() {

    private val notifier: LibraryScanNotifier by inject()
    private val explore: ExploreSearchService by inject()
    private val library: LibraryIndex by inject()

    // Single worker — never compete with UI / playback threads for pool slots
    private val scanDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + scanDispatcher)
    private var work: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_REMOTE
        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) == true

        val title = when (action) {
            ACTION_LOCAL -> "Scanning library"
            else -> "Syncing libraries"
        }
        startAsForeground(title, "Starting…")

        work?.cancel()
        work = scope.launch {
            // Lowest priority: UI and audio always win scheduling
            Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
            try {
                when (action) {
                    ACTION_LOCAL -> {
                        notifier.update("Scanning library", "Reading local files…")
                        library.refreshAndAwait()
                        notifier.finish("Library scan", "Local library updated")
                    }
                    else -> {
                        notifier.update("Syncing libraries", "Connecting…")
                        explore.runRemoteScanBlocking(force)
                        val count = explore.indexedCount.value
                        notifier.finish(
                            "Sync complete",
                            if (count > 0) "$count remote tracks indexed" else "Remote libraries updated"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "scan service failed", e)
                notifier.finish("Scan failed", e.message ?: "Unknown error")
            } finally {
                stopForegroundCompat()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        work?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(title: String, text: String) {
        val notification = notifier.build(title, text, indeterminate = true)
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                LibraryScanNotifier.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(LibraryScanNotifier.NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    companion object {
        private const val TAG = "LibraryScanService"
        const val ACTION_REMOTE = "capital.yuri.yuriplayer.action.SCAN_REMOTE"
        const val ACTION_LOCAL = "capital.yuri.yuriplayer.action.SCAN_LOCAL"
        const val EXTRA_FORCE = "force"

        fun startRemote(context: Context, force: Boolean = false) {
            val i = Intent(context, LibraryScanService::class.java).apply {
                action = ACTION_REMOTE
                putExtra(EXTRA_FORCE, force)
            }
            start(context, i)
        }

        fun startLocal(context: Context) {
            val i = Intent(context, LibraryScanService::class.java).apply {
                action = ACTION_LOCAL
            }
            start(context, i)
        }

        private fun start(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

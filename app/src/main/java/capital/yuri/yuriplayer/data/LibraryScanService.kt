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
import org.koin.android.ext.android.inject

/**
 * Short-lived foreground service that hosts library scans / remote syncs so they
 * keep running when the UI is backgrounded, with a live progress notification.
 *
 * Re-entering Explore must **not** cancel an in-flight remote scan — only an
 * explicit stop / force restart does that.
 */
class LibraryScanService : Service() {

    private val notifier: LibraryScanNotifier by inject()
    private val explore: ExploreSearchService by inject()
    private val library: LibraryIndex by inject()

    private val scanDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + scanDispatcher)
    private var work: Job? = null
    @Volatile private var queuedForce = false
    @Volatile private var queuedSourceId: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_REMOTE
        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) == true
        val sourceId = intent?.getLongExtra(EXTRA_SOURCE_ID, -1L)?.takeIf { it >= 0 }

        when (action) {
            ACTION_STOP -> {
                Log.i(TAG, "stop requested sourceId=$sourceId")
                if (sourceId != null) explore.requestStopSource(sourceId)
                else explore.requestStopAll()
                // Let the worker finish cleanly via cooperative cancel flags
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                Log.i(TAG, "pause requested sourceId=$sourceId")
                if (sourceId != null) explore.requestPauseSource(sourceId)
                else explore.requestPauseAll()
                return START_NOT_STICKY
            }
        }

        // Already scanning — do not cancel unless this is an explicit full force-rescan.
        // A newly added source always queues so its first index still runs.
        if (action == ACTION_REMOTE && work?.isActive == true) {
            if (sourceId != null) {
                queuedForce = true
                queuedSourceId = sourceId
                Log.i(TAG, "queue initial index sourceId=$sourceId")
                startAsForeground("Syncing libraries", explore.scanProgress.value ?: "Working…")
                return START_NOT_STICKY
            }
            if (!force) {
                Log.i(TAG, "remote scan already active — ignore duplicate start")
                startAsForeground("Syncing libraries", explore.scanProgress.value ?: "Working…")
                return START_NOT_STICKY
            }
        }

        val title = when (action) {
            ACTION_LOCAL -> "Scanning library"
            else -> "Syncing libraries"
        }
        startAsForeground(title, "Starting…")

        if (force) {
            work?.cancel()
        }
        work = scope.launch {
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
                        explore.runRemoteScanBlocking(force, sourceId)
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
                val nextId = queuedSourceId
                val nextForce = queuedForce
                queuedSourceId = null
                queuedForce = false
                if (nextId != null) {
                    Log.i(TAG, "start queued index sourceId=$nextId")
                    startRemote(applicationContext, nextForce, nextId)
                } else {
                    stopForegroundCompat()
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Do not cancel work aggressively — process death is the only hard stop here.
        // Cooperative pause/stop is handled via ExploreSearchService flags.
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
        const val ACTION_STOP = "capital.yuri.yuriplayer.action.SCAN_STOP"
        const val ACTION_PAUSE = "capital.yuri.yuriplayer.action.SCAN_PAUSE"
        const val EXTRA_FORCE = "force"
        const val EXTRA_SOURCE_ID = "source_id"

        fun startRemote(context: Context, force: Boolean = false, sourceId: Long? = null) {
            val i = Intent(context, LibraryScanService::class.java).apply {
                action = ACTION_REMOTE
                putExtra(EXTRA_FORCE, force)
                if (sourceId != null) putExtra(EXTRA_SOURCE_ID, sourceId)
            }
            start(context, i)
        }

        fun startLocal(context: Context) {
            val i = Intent(context, LibraryScanService::class.java).apply {
                action = ACTION_LOCAL
            }
            start(context, i)
        }

        fun pause(context: Context, sourceId: Long? = null) {
            val i = Intent(context, LibraryScanService::class.java).apply {
                action = ACTION_PAUSE
                if (sourceId != null) putExtra(EXTRA_SOURCE_ID, sourceId)
            }
            start(context, i)
        }

        fun stop(context: Context, sourceId: Long? = null) {
            val i = Intent(context, LibraryScanService::class.java).apply {
                action = ACTION_STOP
                if (sourceId != null) putExtra(EXTRA_SOURCE_ID, sourceId)
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

package capital.yuri.yuriplayer.data

import android.content.Context
import android.util.Log
import capital.yuri.yuriplayer.data.source.RemotePlaylistService
import capital.yuri.yuriplayer.data.source.SourceInstanceRepository
import capital.yuri.yuriplayer.data.source.SourceType
import capital.yuri.yuriplayer.data.source.effectivePartialInterval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * While the process is alive (playback service keeps it warm), refresh
 * user-owned playlists and run incremental library scans on a schedule.
 */
class LibrarySyncScheduler(
    private val context: Context,
    private val settings: LibrarySettings,
    private val sources: SourceInstanceRepository,
    private val catalog: CatalogRepository,
    private val checkpoints: ScanCheckpointStore,
    private val playlists: RemotePlaylistService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scope.launch {
            delay(BOOT_DELAY_MS)
            while (isActive) {
                runCatching { tick() }
                    .onFailure { Log.w(TAG, "tick failed: ${it.message}") }
                delay(POLL_MS)
            }
        }
        Log.i(TAG, "started")
    }

    private suspend fun tick() = withContext(Dispatchers.IO) {
        if (!NetworkPolicy.allowsRemoteSync(context, settings)) return@withContext
        val now = System.currentTimeMillis()
        maybeProfile(now)
        maybePartial(now)
    }

    private suspend fun maybeProfile(now: Long) {
        if (!settings.isProfileSyncEnabled()) return
        val interval = settings.getProfileSyncInterval()
        if (!interval.isActive) return
        val last = settings.lastProfileSyncAt()
        if (last > 0L && now - last < interval.millis) return
        val n = playlists.syncOwnedToMyStuff()
        settings.markProfileSynced(now)
        Log.i(TAG, "profile sync imported=$n interval=${interval.id}")
    }

    private suspend fun maybePartial(now: Long) {
        if (!settings.isPartialSyncEnabled()) return
        val global = settings.getPartialSyncInterval()
        val rows = sources.getAll().filter { it.enabled }
        for (row in rows) {
            val interval = row.effectivePartialInterval(global)
            if (!interval.isActive) continue
            val last = settings.lastPartialSyncAt(row.id)
            if (last > 0L && now - last < interval.millis) continue
            val type = when (SourceType.from(row.type)) {
                SourceType.JELLYFIN -> "JELLYFIN"
                SourceType.SUBSONIC, SourceType.NAVIDROME -> "SUBSONIC"
                else -> continue
            }
            val indexed = catalog.countTracksForSource(type, row.id)
            val scanned = checkpoints.get(row.id)?.status == SourceScanStatus.DONE
            if (indexed <= 0 && !scanned) {
                Log.i(TAG, "skip partial ${row.name} — no index yet")
                continue
            }
            Log.i(TAG, "partial sync '${row.name}' interval=${interval.id} indexed=$indexed")
            LibraryScanService.startRemote(
                context.applicationContext,
                force = false,
                sourceId = row.id
            )
            settings.markPartialSynced(row.id, now)
            return
        }
    }

    companion object {
        private const val TAG = "LibrarySync"
        private const val BOOT_DELAY_MS = 20_000L
        private const val POLL_MS = 60_000L
    }
}

package capital.yuri.yuriplayer.core.network

import kotlinx.coroutines.flow.StateFlow

/**
 * Cross-platform internet reachability signal.
 *
 * Drives the "No internet" banner and, together with per-source availability,
 * gates external sources out of the queue while offline.
 */
interface NetworkMonitor {
    val isOnline: StateFlow<Boolean>
}

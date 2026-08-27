package capital.yuri.yuriplayer.desktop

import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.core.network.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Desktop has no ConnectivityManager, so "online" is a periodic TCP reachability
 * probe to a well-known anycast host. Retries every [PROBE_INTERVAL_MS].
 */
class DesktopNetworkMonitor : NetworkMonitor {
    private val log = yuriLog("Network")

    private val _isOnline = MutableStateFlow(true)
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            while (isActive) {
                _isOnline.value = probe()
                delay(PROBE_INTERVAL_MS)
            }
        }
    }

    private fun probe(): Boolean {
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(PROBE_HOST, PROBE_PORT), PROBE_TIMEOUT_MS)
            }
            true
        }.onFailure { t ->
            log.w { "offline probe failed: ${t.message}" }
        }.getOrDefault(false)
    }

    companion object {
        private const val PROBE_HOST = "1.1.1.1"
        private const val PROBE_PORT = 443
        private const val PROBE_TIMEOUT_MS = 3_000
        private const val PROBE_INTERVAL_MS = 15_000L
    }
}

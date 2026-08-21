package capital.yuri.yuriplayer.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * Whether background library sync is allowed on the current network.
 *
 * Defaults to **unmetered only** (Wi‑Fi / Ethernet). Users can opt in to
 * mobile-data sync via [LibrarySettings.isSyncOverMobileDataEnabled].
 */
object NetworkPolicy {

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        if (Build.VERSION.SDK_INT >= 23) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        @Suppress("DEPRECATION")
        val info = cm.activeNetworkInfo
        @Suppress("DEPRECATION")
        return info != null && info.isConnected
    }

    /** True when the active network is treated as unmetered (typically Wi‑Fi). */
    fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        if (Build.VERSION.SDK_INT >= 23) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
            // Respect user "metered Wi‑Fi" and always treat pure cellular as metered
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        @Suppress("DEPRECATION")
        val info = cm.activeNetworkInfo ?: return false
        @Suppress("DEPRECATION")
        return info.isConnected && info.type == ConnectivityManager.TYPE_WIFI
    }

    /**
     * Whether a **remote library sync** (Jellyfin / Subsonic index) may run now.
     * Local MediaStore / SAF scans never need this check.
     */
    fun allowsRemoteSync(context: Context, settings: LibrarySettings): Boolean {
        if (!isOnline(context)) return false
        if (settings.isSyncOverMobileDataEnabled()) return true
        return isUnmetered(context)
    }

    fun blockedReason(context: Context, settings: LibrarySettings): String? {
        if (!isOnline(context)) return "No network connection"
        if (settings.isSyncOverMobileDataEnabled()) return null
        if (isUnmetered(context)) return null
        return "Paused on mobile data — enable “Sync over mobile data” in Settings, or connect to Wi‑Fi"
    }
}

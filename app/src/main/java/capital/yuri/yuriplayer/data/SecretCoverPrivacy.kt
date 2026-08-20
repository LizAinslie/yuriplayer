package capital.yuri.yuriplayer.data

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Secret playlist covers are session-only privacy art.
 *
 * When the process leaves the foreground (home, app switcher, screen lock) or
 * the app starts cold, any playlist still showing a secret cover is reset to
 * its first public cover so the secret never sticks on the lock screen / recents
 * / next open.
 */
class SecretCoverPrivacy(
    private val playlists: PlaylistRepository
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun start() {
        // Cold start / process restore
        resetAsync()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        // Backgrounded, task switched away, or screen locked while we were on top
        resetAsync()
    }

    private fun resetAsync() {
        scope.launch {
            mutex.withLock {
                runCatching { playlists.resetSecretActiveCoversToPublic() }
            }
        }
    }
}

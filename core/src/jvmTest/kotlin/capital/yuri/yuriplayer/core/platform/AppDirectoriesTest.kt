package capital.yuri.yuriplayer.core.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class AppDirectoriesTest {
    @Test
    fun dirsAreCreatedAndDefaultMusicIsHomeMusicOrXdg() {
        val dirs = JvmAppDirectories()
        assertTrue(File(dirs.configDir).isDirectory)
        assertTrue(File(dirs.cacheDir).isDirectory)
        assertTrue(File(dirs.dataDir).isDirectory)
        dirs.defaultMusicRoots.forEach { root ->
            assertTrue(File(root).isDirectory, root)
        }
    }
}

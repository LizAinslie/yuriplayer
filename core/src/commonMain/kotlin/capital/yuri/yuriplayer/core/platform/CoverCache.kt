package capital.yuri.yuriplayer.core.platform

/** Coil / cover files live under cacheDir/covers. */
fun coverCacheDir(): String = appDirectories().cacheDir.trimEnd('/', '\\') + "/covers"

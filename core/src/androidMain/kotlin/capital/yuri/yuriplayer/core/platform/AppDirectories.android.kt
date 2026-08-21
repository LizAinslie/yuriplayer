package capital.yuri.yuriplayer.core.platform

import android.annotation.SuppressLint
import android.content.Context
import java.io.File

/**
 * Call [install] from Application.onCreate before anything reads dirs.
 */
object AndroidAppDirectories : AppDirectories {
    @SuppressLint("StaticFieldLeak")
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    private fun ctx(): Context =
        requireNotNull(appContext) { "AndroidAppDirectories.install(context) was not called" }

    override val configDir: String
        get() = File(ctx().filesDir, "config").apply { mkdirs() }.absolutePath
    override val cacheDir: String
        get() = ctx().cacheDir.absolutePath
    override val dataDir: String
        get() = File(ctx().filesDir, "data").apply { mkdirs() }.absolutePath
    override val defaultMusicRoots: List<String>
        get() = emptyList()
}

actual fun appDirectories(): AppDirectories = AndroidAppDirectories

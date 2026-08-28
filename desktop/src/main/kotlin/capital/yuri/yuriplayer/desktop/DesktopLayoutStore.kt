package capital.yuri.yuriplayer.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties

class DesktopLayoutStore(configDir: String) {
    private val file = File(configDir, "layout.properties")

    private val _left = MutableStateFlow(DEFAULT_LEFT)
    val leftFraction: StateFlow<Float> = _left.asStateFlow()

    private val _right = MutableStateFlow(DEFAULT_RIGHT)
    val rightFraction: StateFlow<Float> = _right.asStateFlow()

    init {
        load()
    }

    fun setLeft(fraction: Float, persist: Boolean = true) {
        _left.value = fraction.coerceIn(MIN_LEFT, MAX_LEFT)
        if (persist) persist()
    }

    fun setRight(fraction: Float, persist: Boolean = true) {
        _right.value = fraction.coerceIn(MIN_RIGHT, MAX_RIGHT)
        if (persist) persist()
    }

    fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            val p = Properties()
            p.setProperty("left", _left.value.toString())
            p.setProperty("right", _right.value.toString())
            file.outputStream().use { p.store(it, "Yuri Player layout") }
        }
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val p = Properties()
            file.inputStream().use { p.load(it) }
            p.getProperty("left")?.toFloatOrNull()?.let { _left.value = it.coerceIn(MIN_LEFT, MAX_LEFT) }
            p.getProperty("right")?.toFloatOrNull()?.let { _right.value = it.coerceIn(MIN_RIGHT, MAX_RIGHT) }
        }
    }

    companion object {
        const val DEFAULT_LEFT = 0.22f
        const val DEFAULT_RIGHT = 0.26f
        const val MIN_LEFT = 0.10f
        const val MAX_LEFT = 0.55f
        const val MIN_RIGHT = 0.12f
        const val MAX_RIGHT = 0.55f
        const val MIN_CENTER = 0.28f
    }
}

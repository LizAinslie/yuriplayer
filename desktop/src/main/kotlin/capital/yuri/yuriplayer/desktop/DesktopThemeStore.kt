package capital.yuri.yuriplayer.desktop

import capital.yuri.yuriplayer.components.theme.AccentCatalog
import capital.yuri.yuriplayer.components.theme.ThemeChoice
import capital.yuri.yuriplayer.components.theme.ThemeFamily
import capital.yuri.yuriplayer.components.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties

class DesktopThemeStore(configDir: String) {
    private val file = File(configDir, "theme.properties")
    private val _choice = MutableStateFlow(load())
    val choice: StateFlow<ThemeChoice> = _choice.asStateFlow()

    fun setMode(mode: ThemeMode) = update(_choice.value.copy(mode = mode))

    fun setAccent(id: String) = update(_choice.value.copy(accentId = AccentCatalog.byId(id).id))

    private fun update(next: ThemeChoice) {
        _choice.value = next
        persist(next)
    }

    private fun load(): ThemeChoice {
        if (!file.exists()) return ThemeChoice()
        return runCatching {
            val p = Properties()
            file.inputStream().use { p.load(it) }
            ThemeChoice(
                mode = ThemeMode.fromId(p.getProperty("mode")),
                accentId = p.getProperty("accent") ?: AccentCatalog.yuri.id,
                family = ThemeFamily.MATERIAL3
            )
        }.getOrElse { ThemeChoice() }
    }

    private fun persist(choice: ThemeChoice) {
        runCatching {
            file.parentFile?.mkdirs()
            val p = Properties()
            p.setProperty("mode", choice.mode.id)
            p.setProperty("accent", choice.accentId)
            p.setProperty("family", choice.family.id)
            file.outputStream().use { p.store(it, "Yuri Player theme") }
        }
    }
}

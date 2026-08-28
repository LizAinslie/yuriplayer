package capital.yuri.yuriplayer.core.source

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class LibrarySourceStore(configDir: String) {
    private val file = File(configDir, "sources.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private val _folders = MutableStateFlow<List<String>>(emptyList())
    val extraFolders: StateFlow<List<String>> = _folders.asStateFlow()

    private val _remotes = MutableStateFlow<List<RemoteAccount>>(emptyList())
    val remotes: StateFlow<List<RemoteAccount>> = _remotes.asStateFlow()

    var deviceId: String = UUID.randomUUID().toString()
        private set

    init {
        load()
    }

    fun addFolder(path: String) {
        val p = path.trim()
        if (p.isEmpty()) return
        if (_folders.value.any { it.equals(p, ignoreCase = true) }) return
        _folders.value = _folders.value + p
        persist()
    }

    fun removeFolder(path: String) {
        _folders.value = _folders.value.filterNot { it.equals(path, ignoreCase = true) }
        persist()
    }

    fun upsertRemote(account: RemoteAccount) {
        val cur = _remotes.value.toMutableList()
        val i = cur.indexOfFirst { it.id == account.id }
        if (i >= 0) cur[i] = account else cur += account
        _remotes.value = cur
        persist()
    }

    fun removeRemote(id: String) {
        _remotes.value = _remotes.value.filterNot { it.id == id }
        persist()
    }

    fun setRemoteEnabled(id: String, enabled: Boolean) {
        _remotes.value = _remotes.value.map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        persist()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val data = json.decodeFromString<LibrarySourcesFile>(file.readText())
            _folders.value = data.extraFolders
            _remotes.value = data.remotes
            if (data.deviceId.isNotBlank()) deviceId = data.deviceId
        }
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                json.encodeToString(
                    LibrarySourcesFile(
                        extraFolders = _folders.value,
                        remotes = _remotes.value,
                        deviceId = deviceId
                    )
                )
            )
        }
    }
}

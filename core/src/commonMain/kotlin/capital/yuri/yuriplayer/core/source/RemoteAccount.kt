package capital.yuri.yuriplayer.core.source

import kotlinx.serialization.Serializable

enum class SourceKind {
    LOCAL,
    JELLYFIN,
    SUBSONIC
}

@Serializable
data class RemoteAccount(
    val id: String,
    val kind: SourceKind,
    val name: String,
    val baseUrl: String,
    val username: String,
    val secret: String,
    val enabled: Boolean = true,
    val accessToken: String? = null,
    val userId: String? = null
)

@Serializable
data class LibrarySourcesFile(
    val extraFolders: List<String> = emptyList(),
    val remotes: List<RemoteAccount> = emptyList(),
    val deviceId: String = ""
)

package capital.yuri.yuriplayer.data.source

import capital.yuri.yuriplayer.data.db.SourceInstanceDao
import capital.yuri.yuriplayer.data.db.SourceInstanceEntity
import kotlinx.coroutines.flow.Flow

/**
 * Persisted remote library servers (Jellyfin, Subsonic/OpenSubsonic, …).
 * Local scan is not a [SourceInstanceEntity] — it is always present.
 */
class SourceInstanceRepository(
    private val dao: SourceInstanceDao
) {
    fun observeAll(): Flow<List<SourceInstanceEntity>> = dao.observeAll()

    suspend fun getAll(): List<SourceInstanceEntity> = dao.getAll()

    suspend fun get(id: Long): SourceInstanceEntity? =
        dao.getAll().firstOrNull { it.id == id }

    suspend fun upsert(entity: SourceInstanceEntity): Long = dao.upsert(entity)

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val row = get(id) ?: return
        dao.update(row.copy(enabled = enabled))
    }

    suspend fun addJellyfin(
        name: String,
        baseUrl: String,
        username: String,
        passwordOrToken: String
    ): Long = upsert(
        SourceInstanceEntity(
            type = SourceType.JELLYFIN.name,
            name = name.ifBlank { "Jellyfin" },
            baseUrl = normalizeBaseUrl(baseUrl),
            username = username,
            secret = passwordOrToken,
            enabled = true
        )
    )

    suspend fun addSubsonic(
        name: String,
        baseUrl: String,
        username: String,
        password: String
    ): Long = upsert(
        SourceInstanceEntity(
            type = SourceType.SUBSONIC.name,
            name = name.ifBlank { "Subsonic" },
            baseUrl = normalizeBaseUrl(baseUrl),
            username = username,
            secret = password,
            enabled = true
        )
    )

    companion object {
        fun normalizeBaseUrl(raw: String): String {
            var u = raw.trim().trimEnd('/')
            if (!u.startsWith("http://", ignoreCase = true) &&
                !u.startsWith("https://", ignoreCase = true)
            ) {
                u = "https://$u"
            }
            return u
        }
    }
}

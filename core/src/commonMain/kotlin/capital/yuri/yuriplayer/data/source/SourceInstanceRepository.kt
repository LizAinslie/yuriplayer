package capital.yuri.yuriplayer.data.source

import capital.yuri.yuriplayer.data.db.SourceInstanceDao
import capital.yuri.yuriplayer.data.db.SourceInstanceEntity
import capital.yuri.yuriplayer.core.http.normalizeBaseUrl
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
        /**
         * Delegates to the shared [capital.yuri.yuriplayer.core.http.normalizeBaseUrl].
         * Fully-qualified on purpose: the unqualified name would shadow the
         * top-level import and recurse into this companion member.
         */
        fun normalizeBaseUrl(raw: String): String =
            capital.yuri.yuriplayer.core.http.normalizeBaseUrl(raw)
    }
}

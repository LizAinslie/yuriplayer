package capital.yuri.yuriplayer.data.source

import capital.yuri.yuriplayer.data.SyncInterval
import capital.yuri.yuriplayer.data.db.SourceInstanceEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-library overlay stored in [SourceInstanceEntity.extraJson].
 * [partialIntervalId] null = inherit the global default; `"off"` disables
 * incremental sync for this library only.
 */
@Serializable
data class SourceSyncExtras(
    val partialIntervalId: String? = null
) {
    fun partialOverride(): SyncInterval? {
        val id = partialIntervalId ?: return null
        if (id.equals("default", ignoreCase = true)) return null
        return SyncInterval.fromId(id)
    }
}

object SourceSyncJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun read(raw: String?): SourceSyncExtras {
        if (raw.isNullOrBlank()) return SourceSyncExtras()
        return runCatching { json.decodeFromString(SourceSyncExtras.serializer(), raw) }
            .getOrDefault(SourceSyncExtras())
    }

    fun write(extras: SourceSyncExtras): String? {
        if (extras.partialIntervalId.isNullOrBlank()) return null
        return json.encodeToString(SourceSyncExtras.serializer(), extras)
    }
}

fun SourceInstanceEntity.syncExtras(): SourceSyncExtras = SourceSyncJson.read(extraJson)

fun SourceInstanceEntity.withPartialInterval(interval: SyncInterval?): SourceInstanceEntity {
    val extras = syncExtras().copy(
        partialIntervalId = interval?.id
    )
    return copy(extraJson = SourceSyncJson.write(extras))
}

fun SourceInstanceEntity.effectivePartialInterval(global: SyncInterval): SyncInterval =
    syncExtras().partialOverride() ?: global

package capital.yuri.yuriplayer.core.source

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks external sources that are temporarily unreachable so the UI and the
 * queue can exclude them until a later retry succeeds.
 *
 * Callers mark a source [markUnavailable] when a scan/fetch fails (logging the
 * error at the call site) and [markAvailable] once a periodic retry recovers.
 * [unavailable] is observable for UI ("source temporarily unavailable").
 */
class SourceAvailabilityStore {
    private val _unavailable = MutableStateFlow<Map<String, String>>(emptyMap())
    val unavailable: StateFlow<Map<String, String>> = _unavailable.asStateFlow()

    fun markUnavailable(sourceId: String, reason: String) {
        _unavailable.value = _unavailable.value + (sourceId to reason)
    }

    fun markAvailable(sourceId: String) {
        _unavailable.value = _unavailable.value - sourceId
    }

    fun isUnavailable(sourceId: String): Boolean = sourceId in _unavailable.value

    fun reason(sourceId: String): String? = _unavailable.value[sourceId]
}

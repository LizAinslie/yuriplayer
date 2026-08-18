package capital.yuri.yuriplayer.player.radio

/**
 * Per-kind + global recent release keys for hard cooldowns and soft weighting.
 * In-memory for now; RadioEngine can persist later with playback state.
 */
class RecentReleaseMemory(
    private val softCapacity: Int = 12
) {
    private val byKind = mutableMapOf<
        ReleaseKind,
        ArrayDeque<String>
    >(
        ReleaseKind.LP to ArrayDeque(),
        ReleaseKind.EP to ArrayDeque(),
        ReleaseKind.SINGLE to ArrayDeque(),
        ReleaseKind.UNKNOWN to ArrayDeque()
    )

    private val global = ArrayDeque<String>()

    fun note(key: String, kind: ReleaseKind) {
        if (key.isBlank() || key == "|") return
        push(byKind.getOrPut(kind) { ArrayDeque() }, key, max = 8)
        push(global, key, max = softCapacity.coerceAtLeast(8))
    }

    fun hardExclude(
        kind: ReleaseKind,
        perKind: Int,
        globalCount: Int
    ): Set<String> = buildSet {
        byKind[kind]?.takeLast(perKind.coerceAtLeast(0))?.let { addAll(it) }
        global.takeLast(globalCount.coerceAtLeast(0)).let { addAll(it) }
    }

    /** Index 0 = most recent. Used for soft weight decay. */
    fun softOrderNewestFirst(): List<String> = global.toList().asReversed()

    fun lastOf(kind: ReleaseKind): String? = byKind[kind]?.lastOrNull()

    private fun push(q: ArrayDeque<String>, key: String, max: Int) {
        q.removeAll { it.equals(key, ignoreCase = true) }
        q.addLast(key)
        while (q.size > max) q.removeFirst()
    }
}

package capital.yuri.yuriplayer.player.radio

import capital.yuri.yuriplayer.data.AlbumItem

/**
 * On-device catalog view for algorithms. Keep this free of Android / Room so the
 * radio package can move to a shared KMP module or a self-hosted worker.
 */
fun interface ReleaseCatalog {
    fun albums(): List<AlbumItem>
}

/**
 * Pluggable radio strategy. Implementations must be pure w.r.t. I/O except via
 * [ReleaseCatalog] (and future optional NetworkReleaseSource behind a privacy gate).
 */
interface RadioAlgorithm {
    val id: RadioAlgorithmId

    /**
     * @return next release to queue, or null if this strategy has nothing.
     * Algorithms must not mutate global player state — only their own memory
     * (via [RecentReleaseMemory] passed in context side-channels if needed).
     */
    fun pick(
        catalog: ReleaseCatalog,
        context: RadioContext,
        memory: RecentReleaseMemory
    ): RadioPick?
}

/**
 * Optional network extension point. Always no-op until the user opts in and a
 * concrete source (Jellyfin / Navidrome / …) is configured.
 */
interface ExternalReleaseSource {
    val isEnabled: Boolean
    /** Suspend-friendly later; sync stub for now. */
    fun fetchCandidates(context: RadioContext): List<AlbumItem> = emptyList()
}

object NoExternalReleaseSource : ExternalReleaseSource {
    override val isEnabled: Boolean = false
}

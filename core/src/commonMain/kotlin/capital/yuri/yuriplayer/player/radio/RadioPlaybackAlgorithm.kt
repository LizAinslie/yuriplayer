package capital.yuri.yuriplayer.player.radio

import capital.yuri.yuriplayer.core.log.yuriLog
import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.artistKey
import capital.yuri.yuriplayer.core.player.ColdSource
import capital.yuri.yuriplayer.core.player.ColdSourceType
import kotlin.math.pow
import kotlin.random.Random

/**
 * Continuous radio after a queue ends: prefer the seed artist, avoid repeating
 * the same LP / EP / Single too soon, weighted random among the rest.
 *
 * Entropy levers ([RadioPlaybackConfig]):
 * 1. **Hard per-kind cooldown** — last N LPs / EPs / Singles never candidates
 * 2. **Global cooldown** — last M releases of any kind excluded
 * 3. **Soft history penalty** — older plays still eligible but down-weighted
 * 4. **Type weights** — bias toward LPs vs singles
 * 5. **Weighted sample** — not uniform; uses (typeWeight × softPenalty)
 *
 * Future settings UI can expose these without changing the algorithm class.
 */
class RadioPlaybackAlgorithm(
    var config: RadioPlaybackConfig = RadioPlaybackConfig()
) : RadioAlgorithm {

    override val id: RadioAlgorithmId = RadioAlgorithmId.PLAYBACK

    override fun pick(
        catalog: ReleaseCatalog,
        context: RadioContext,
        memory: RecentReleaseMemory
    ): RadioPick? {
        val focus = context.focusArtistKeys
        if (focus.isEmpty()) {
            log.i { "no focus artists" }
            return null
        }

        val all = catalog.albums().filter { it.songs.isNotEmpty() }
        val sameArtist = all.filter { albumMatchesFocus(it, focus) }
        if (sameArtist.isEmpty()) {
            log.i { "no albums for focus=$focus" }
            return null
        }

        val softOrder = memory.softOrderNewestFirst()
        val softIndex = softOrder.mapIndexed { i, k -> k.lowercase() to i }.toMap()

        data class Cand(val album: AlbumItem, val key: String, val kind: ReleaseKind, val weight: Double)

        val candidates = sameArtist.mapNotNull { album ->
            val key = ReleaseClassifier.releaseKey(album)
            val kind = ReleaseClassifier.kindOf(album)
            val hard = memory.hardExclude(
                kind = kind,
                perKind = perKindLimit(kind),
                globalCount = config.globalRecentExclude
            )
            if (key in hard || hard.any { it.equals(key, true) }) return@mapNotNull null

            val typeW = typeWeight(kind).toDouble().coerceAtLeast(0.01)
            val softIdx = softIndex[key.lowercase()]
            val softW = if (softIdx == null) {
                1.0
            } else {
                config.softPenaltyFactor.toDouble().pow(softIdx + 1)
                    .coerceAtLeast(0.05)
            }
            Cand(album, key, kind, typeW * softW)
        }

        if (candidates.isEmpty()) {
            // Exhausted hard filters — fall back to anything same-artist except
            // the single most recent global key so radio never dead-ends.
            val last = memory.softOrderNewestFirst().firstOrNull()
            val fallback = sameArtist.filter {
                ReleaseClassifier.releaseKey(it) != last
            }.ifEmpty { sameArtist }
            val album = fallback[Random.nextInt(fallback.size)]
            return toPick(album, "fallback-after-cooldown")
        }

        val picked = weightedSample(candidates) { it.weight } ?: return null
        return toPick(picked.album, "weighted kind=${picked.kind} w=${"%.2f".format(picked.weight)}")
    }

    private fun perKindLimit(kind: ReleaseKind): Int = when (kind) {
        ReleaseKind.LP -> config.avoidRecentLps
        ReleaseKind.EP -> config.avoidRecentEps
        ReleaseKind.SINGLE -> config.avoidRecentSingles
        ReleaseKind.UNKNOWN -> config.globalRecentExclude
    }

    private fun typeWeight(kind: ReleaseKind): Float = when (kind) {
        ReleaseKind.LP -> config.weightLp
        ReleaseKind.EP -> config.weightEp
        ReleaseKind.SINGLE -> config.weightSingle
        ReleaseKind.UNKNOWN -> 0.5f
    }

    private fun albumMatchesFocus(album: AlbumItem, focus: Set<String>): Boolean {
        val a = artistKey(album.artist)
        if (a != null && a in focus) return true
        return album.songs.any { song ->
            artistKey(song.effectiveAlbumArtist)?.let { it in focus } == true ||
                artistKey(song.artist)?.let { it in focus } == true ||
                song.creditArtists.any { artistKey(it)?.let { k -> k in focus } == true }
        }
    }

    private fun toPick(album: AlbumItem, reason: String): RadioPick {
        val key = ReleaseClassifier.releaseKey(album)
        val kind = ReleaseClassifier.kindOf(album)
        return RadioPick(
            album = album,
            source = ColdSource(
                type = ColdSourceType.ALBUM,
                id = key,
                title = album.name
            ),
            kind = kind,
            algorithmId = id.name,
            reason = reason
        )
    }

    private fun <T> weightedSample(items: List<T>, weight: (T) -> Double): T? {
        if (items.isEmpty()) return null
        val total = items.sumOf { weight(it).coerceAtLeast(0.0) }
        if (total <= 0.0) return items[Random.nextInt(items.size)]
        var r = Random.nextDouble() * total
        for (item in items) {
            r -= weight(item).coerceAtLeast(0.0)
            if (r <= 0.0) return item
        }
        return items.last()
    }

    companion object {
        private val log = yuriLog("RadioPlay")
    }
}

package capital.yuri.yuriplayer.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlbumCollapseTest {
    private fun clancy(source: String, prefix: String) = (1..14).map { n ->
        Track(
            id = "$prefix-$n",
            uri = if (source == "local") "file:///Clancy/$n.flac" else "https://navi/stream/$n",
            title = titles[n - 1],
            artist = "twenty one pilots",
            albumArtist = "twenty one pilots",
            album = "Clancy",
            trackNumber = n,
            discNumber = 1,
            sourceId = source
        )
    }

    @Test
    fun localAndNavidromeCollapseToFourteen() {
        val mixed = clancy("local", "file") + clancy("navidrome", "subsonic")
        val collapsed = collapseAlbumTracks(mixed)
        assertEquals(14, collapsed.size)
        assertTrue(collapsed.all { it.multiSource })
        assertEquals("Overcompensate", collapsed.first().preferred.title)
        assertEquals(1, collapsed.first().preferred.trackNumber)
        assertEquals("Paladin Strait", collapsed.last().preferred.title)
        assertTrue(collapsed.first().preferred.uri.startsWith("file:"))
    }

    @Test
    fun preferredSourceWins() {
        val mixed = clancy("local", "file") + clancy("navidrome", "subsonic")
        val id = albumPageIdentity(mixed.first())
        val collapsed = collapseAlbumTracks(mixed, mapOf(id to "subsonic-1"))
        assertEquals("subsonic-1", collapsed.first().preferred.id)
    }

    @Test
    fun playlistKeepsSnapshotWhenKeyMissing() {
        val snap = Track(
            id = "subsonic:old",
            uri = "https://navi/stream/old",
            title = "BIRDBRAIN",
            artist = "Jamie Paige feat. OK Glass",
            album = "BIRDBRAIN",
            trackNumber = 1
        )
        val other = Track(
            id = "subsonic:new",
            uri = "https://navi/stream/new",
            title = "Sahara",
            artist = "Hoshimachi Suisei",
            album = "Still Still Stellar",
            trackNumber = 1
        )
        val byKey = HashMap<String, Track>()
        byKey[other.id] = other
        byKey.putIfAbsent(snap.id, snap)
        byKey.putIfAbsent(snap.catalogKey(), snap)
        val trackIds = listOf("k:missing", other.id)
        val snapshots = listOf(snap, other)
        val seen = HashSet<String>()
        val out = ArrayList<Track>()
        fun add(t: Track) {
            if (t.playlistKeys().any { it in seen }) return
            seen += t.playlistKeys()
            out += t
        }
        for (key in trackIds) byKey[key]?.let(::add)
        for (s in snapshots) add(byKey[s.id] ?: byKey[s.catalogKey()] ?: s)
        assertEquals(2, out.size)
        assertTrue(out.any { it.displayTitle == "BIRDBRAIN" })
        assertTrue(out.any { it.displayTitle == "Sahara" })
    }

    private val titles = listOf(
        "Overcompensate", "Next Semester", "Backslide", "Midwest Indigo",
        "Routines In The Night", "Vignette", "The Craving (Jenna's Version)",
        "Lavish", "Navigating", "Snap Back", "Oldies Station",
        "At The Risk Of Feeling Dumb", "The Craving (single)", "Paladin Strait"
    )
}

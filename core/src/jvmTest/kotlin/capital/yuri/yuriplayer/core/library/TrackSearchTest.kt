package capital.yuri.yuriplayer.core.library

import capital.yuri.yuriplayer.data.Song
import kotlin.test.Test
import kotlin.test.assertTrue

class TrackSearchTest {
    @Test
    fun matchesStylizedTwentyOnePilots() {
        val track = Song(
            id = 1L,
            contentUri = "file:///music/Twenty Øne Piløts/Clancy/01.flac",
            title = "Overcompensate",
            artist = "Twenty Øne Piløts",
            album = "Clancy",
            path = "/home/mey/Music/Twenty One Pilots/Clancy/01.flac"
        )
        assertTrue(track.matchesQuery("twenty one pilots"))
        assertTrue(track.matchesQuery("Twenty Øne Piløts"))
        assertTrue(track.matchesQuery("clancy"))
        assertTrue("Twenty Øne Piløts".matchesSearch("twenty one pilots"))
    }

    @Test
    fun matchesFolderWhenTagsMissing() {
        val track = Song(
            id = 2L,
            contentUri = "file:///data/Twenty One Pilots/Vessel/02.flac",
            title = "02",
            path = "/data/Twenty One Pilots/Vessel/02.flac"
        )
        assertTrue(track.matchesQuery("twenty one pilots"))
        assertTrue(track.matchesQuery("vessel"))
    }
}

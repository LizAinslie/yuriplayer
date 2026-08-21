package capital.yuri.yuriplayer.core.library

import kotlin.test.Test
import kotlin.test.assertTrue

class TrackSearchTest {
    @Test
    fun matchesStylizedTwentyOnePilots() {
        val track = Track(
            id = "1",
            uri = "file:///music/Twenty Øne Piløts/Clancy/01.flac",
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
        val track = Track(
            id = "2",
            uri = "file:///data/Twenty One Pilots/Vessel/02.flac",
            title = "02",
            path = "/data/Twenty One Pilots/Vessel/02.flac"
        )
        assertTrue(track.matchesQuery("twenty one pilots"))
        assertTrue(track.matchesQuery("vessel"))
    }
}

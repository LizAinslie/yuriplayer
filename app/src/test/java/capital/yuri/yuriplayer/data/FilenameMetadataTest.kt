package capital.yuri.yuriplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilenameMetadataTest {

    @Test
    fun dottedTrackPrefix() {
        val parsed = FilenameMetadataParser.parse(
            "/music/Twenty One Pilots/08. Oh, Ms. Believer.flac"
        )
        assertEquals(8, parsed.trackNumber)
        assertNull(parsed.discNumber)
        assertEquals("Oh, Ms. Believer", parsed.title)
    }

    @Test
    fun dashedTrackPrefix() {
        val parsed = FilenameMetadataParser.parse("12 - Taxi Cab.mp3")
        assertEquals(12, parsed.trackNumber)
        assertEquals("Taxi Cab", parsed.title)
    }

    @Test
    fun discDashTrack() {
        val parsed = FilenameMetadataParser.parse("1-08. Air Catcher.flac")
        assertEquals(1, parsed.discNumber)
        assertEquals(8, parsed.trackNumber)
        assertEquals("Air Catcher", parsed.title)
    }

    @Test
    fun cdPrefix() {
        val parsed = FilenameMetadataParser.parse("CD2 - 03 Title.flac")
        assertEquals(2, parsed.discNumber)
        assertEquals(3, parsed.trackNumber)
        assertEquals("Title", parsed.title)
    }

    @Test
    fun discFolder() {
        val parsed = FilenameMetadataParser.parse("/lib/Album/Disc 2/04. B-side.flac")
        assertEquals(2, parsed.discNumber)
        assertEquals(4, parsed.trackNumber)
        assertEquals("B-side", parsed.title)
    }

    @Test
    fun unnumberedFileHasNoTrack() {
        val parsed = FilenameMetadataParser.parse("/music/Single/Heathens.flac")
        assertNull(parsed.trackNumber)
        assertNull(parsed.title)
    }
}

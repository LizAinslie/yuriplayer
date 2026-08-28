package capital.yuri.yuriplayer.core.library

import capital.yuri.yuriplayer.data.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackCatalogKeyTest {
    @Test
    fun localAndSubsonicShareIdentity() {
        val local = Song(
            id = 1L,
            contentUri = "file:///music/BIRDBRAIN.flac",
            title = "BIRDBRAIN",
            artist = "Jamie Paige feat. OK Glass",
            album = "BIRDBRAIN",
            trackNumber = 1,
            path = "/music/BIRDBRAIN.flac",
            sourceId = "local"
        )
        val remote = Song(
            id = 2L,
            contentUri = "https://navi.example/stream/abc",
            title = "BIRDBRAIN",
            artist = "Jamie Paige feat. OK Glass",
            album = "BIRDBRAIN",
            trackNumber = 1,
            path = "subsonic:abc",
            sourceId = "navidrome-1"
        )
        assertEquals(local.catalogKey(), remote.catalogKey())
        assertTrue(local.catalogKey() in remote.playlistKeys())
    }

    @Test
    fun navidromeSearchAndScanShareLooseKey() {
        val search = Song(
            id = 1L,
            contentUri = "https://navi/stream/abc",
            title = "BIRDBRAIN",
            artist = "Jamie Paige feat. OK Glass",
            album = "BIRDBRAIN",
            trackNumber = 1,
            path = "subsonic:abc",
            sourceId = "navi-1"
        )
        val scanned = Song(
            id = 1L,
            contentUri = "https://navi/stream/abc",
            title = "BIRDBRAIN",
            artist = "Jamie Paige",
            albumArtist = "Jamie Paige",
            album = "BIRDBRAIN",
            trackNumber = 1,
            path = "subsonic:abc",
            sourceId = "navi-1"
        )
        assertEquals(search.looseKey(), scanned.looseKey())
        assertTrue(search.looseKey() in scanned.indexKeys())
        assertTrue("abc" in scanned.indexKeys())
    }
}

package capital.yuri.yuriplayer.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackCatalogKeyTest {
    @Test
    fun localAndSubsonicShareIdentity() {
        val local = Track(
            id = "/music/BIRDBRAIN.flac",
            uri = "file:///music/BIRDBRAIN.flac",
            title = "BIRDBRAIN",
            artist = "Jamie Paige feat. OK Glass",
            album = "BIRDBRAIN",
            trackNumber = 1,
            path = "/music/BIRDBRAIN.flac",
            sourceId = "local"
        )
        val remote = Track(
            id = "subsonic:abc",
            uri = "https://navi.example/stream/abc",
            title = "BIRDBRAIN",
            artist = "Jamie Paige feat. OK Glass",
            album = "BIRDBRAIN",
            trackNumber = 1,
            sourceId = "navidrome-1"
        )
        assertEquals(local.catalogKey(), remote.catalogKey())
        assertTrue(local.catalogKey() in remote.playlistKeys())
    }
}

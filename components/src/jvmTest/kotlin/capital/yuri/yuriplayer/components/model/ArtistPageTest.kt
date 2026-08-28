package capital.yuri.yuriplayer.components.model

import capital.yuri.yuriplayer.core.library.matchesQuery
import capital.yuri.yuriplayer.core.library.matchesSearch
import capital.yuri.yuriplayer.data.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtistPageTest {

    private fun song(
        title: String,
        album: String,
        artist: String = "Lemon Demon",
        albumArtist: String? = "Lemon Demon",
        tn: Int? = null,
        disc: Int? = null,
        path: String? = null,
        sourceId: String = "local"
    ) = Song(
        id = "$path-$tn".hashCode().toLong(),
        title = title,
        artist = artist,
        albumArtist = albumArtist,
        album = album,
        contentUri = if (sourceId == "local") "file:///$path" else "https://navi/stream/$tn",
        trackNumber = tn,
        discNumber = disc,
        path = path,
        sourceId = sourceId
    )

    private val spiritPhone = listOf(
        "Lifetime Achievement Award",
        "Touch-Tone Telephone",
        "Cabinet Man",
        "No Eyed Girl",
        "When He Died",
        "Sweet Bod",
        "Eighth Wonder",
        "Ancient Aliens",
        "Soft Fuzzy Man",
        "As Your Father I Expressly Forbid It",
        "I Earn My Life",
        "Reaganomics",
        "Man-Made Object",
        "Spiral of Ants"
    )

    @Test
    fun albumsAndArtistPageForSingleSource() {
        val tracks = spiritPhone.mapIndexed { i, title ->
            song(title, album = "Spirit Phone", tn = i + 1, path = "/music/Lemon Demon/Spirit Phone/${i + 1}. $title.flac")
        }
        val albums = tracks.albums()
        assertEquals(1, albums.size)
        assertEquals("Spirit Phone", albums.first().title)

        val page = tracks.artistPage("Lemon Demon")
        assertEquals(1, page.discography.size)
        assertTrue(page.stats.contains("14 tracks"), page.stats)
    }

    @Test
    fun artistPageAcrossSourcesStillFindsDiscography() {
        val local = spiritPhone.mapIndexed { i, title ->
            song(title, "Spirit Phone", tn = i + 1, path = "/music/Lemon Demon/Spirit Phone/${i + 1}. $title.flac", sourceId = "local")
        }
        val remote = spiritPhone.mapIndexed { i, title ->
            song(title, "Spirit Phone", albumArtist = "Lemon Demon", tn = i + 1, path = "subsonic:${i + 1}", sourceId = "navidrome-1")
        }
        val all = local + remote

        val albums = all.albums()
        assertEquals(1, albums.size, "expected one collapsed album, got ${albums.map { it.title }}")

        val page = all.artistPage("Lemon Demon")
        assertEquals(1, page.discography.size)
        assertTrue(page.stats.contains("14 tracks"), page.stats)
    }

    @Test
    fun searchMatchesLocalArtistAndAlbum() {
        val tracks = spiritPhone.mapIndexed { i, title ->
            song(title, "Spirit Phone", tn = i + 1, path = "/music/Lemon Demon/Spirit Phone/${i + 1}. $title.flac")
        }
        val local = tracks.first { it.displayArtist.equals("Lemon Demon", true) }
        assertTrue(local.matchesQuery("lemon demon"))
        assertTrue(local.matchesQuery("spirit phone"))
        assertTrue("Lemon Demon".matchesSearch("lemon demon"))
    }
}

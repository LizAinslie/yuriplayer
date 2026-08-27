package capital.yuri.yuriplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CatalogIdentityTest {

    @Before
    fun resetAliases() {
        ArtistAliasResolver.replace(emptyMap())
    }

    @Test
    fun titleFoldsCurlyApostrophe_clancyCraving() {
        val ascii = TrackIdentity.normalizeTitle("The Craving (Jenna's Version)")
        val curly = TrackIdentity.normalizeTitle("The Craving (Jenna’s Version)")
        assertEquals(ascii, curly)
        assertTrue(TrackIdentity.titlesMatch("The Craving (Jenna's Version)", "The Craving (Jenna’s Version)"))
    }

    @Test
    fun titleFoldsCommaAndPeriod_ohMsBeliever() {
        val local = TrackIdentity.normalizeTitle("Oh, Ms. Believer")
        val jelly = TrackIdentity.normalizeTitle("Oh Ms. Believer")
        assertEquals("oh ms believer", local)
        assertEquals(local, jelly)
    }

    @Test
    fun featSuffixDoesNotSplitTitleIdentity() {
        val a = TrackIdentity.normalizeTitle("Drumming Song")
        val b = TrackIdentity.normalizeTitle("Drumming Song (feat. Florence)")
        assertEquals(a, b)
    }

    @Test
    fun albumTypoFrayFratMerges() {
        val local = "Holding On to Strings Better Left to Fray"
        val jelly = "Holding On to Strings Better Left to Frat"
        assertTrue(TrackIdentity.albumsNearlyMatch(local, jelly))
        assertFalse(TrackIdentity.albumsMatch(local, jelly))
    }

    @Test
    fun shortAlbumNamesStayExact_trenchVsBreach() {
        assertFalse(TrackIdentity.albumsNearlyMatch("Trench", "Breach"))
        assertFalse(TrackIdentity.albumsNearlyMatch("Vessel", "Vessels"))
    }

    @Test
    fun albumKeyFoldsCaseAndStylizedLetters() {
        assertEquals(
            albumKey("Clancy", "Twenty One Pilots"),
            albumKey("clancy", "twenty one pilots")
        )
        assertEquals(
            albumKey("Clancy", "Twenty Øne Piløts"),
            albumKey("Clancy", "Twenty One Pilots")
        )
    }

    @Test
    fun artistCredits_semicolonPeersArePrimary() {
        val credits = parseArtistCreditList("Hatsune Miku; Megurine Luka")
        assertEquals(listOf("Hatsune Miku", "Megurine Luka"), credits.map { it.name })
        assertTrue(credits.all { it.role == ArtistRole.PRIMARY })
        assertTrue(isCombinedArtistName("Hatsune Miku; Megurine Luka"))
    }

    @Test
    fun artistCredits_featGoesToFeatured() {
        val credits = parseArtistCreditList("Camellia feat. nanahira")
        assertEquals("Camellia", primaryArtistName("Camellia feat. nanahira"))
        assertEquals(listOf("nanahira"), featuredArtistNames("Camellia feat. nanahira"))
        assertEquals(ArtistRole.PRIMARY, credits.first().role)
        assertEquals(ArtistRole.FEATURED, credits.last().role)
        assertTrue(isCombinedArtistName("Camellia feat. nanahira"))
    }

    @Test
    fun artistCredits_bandAmpersandStaysOnePrimary() {
        val credits = parseArtistCreditList("Earth, Wind & Fire")
        assertEquals(1, credits.size)
        assertEquals("Earth, Wind & Fire", credits.single().name)
        assertFalse(isCombinedArtistName("Earth, Wind & Fire"))
    }

    @Test
    fun artistAliasResolverFollowsChain() {
        ArtistAliasResolver.replace(
            mapOf("nightcord at 25:00" to "25時、ナイトコードで。")
        )
        assertEquals("25時、ナイトコードで。", ArtistAliasResolver.resolve("nightcord at 25:00"))
        assertTrue(ArtistAliasResolver.identityKeys("nightcord at 25:00").contains("25時、ナイトコードで。"))
    }
}

@RunWith(RobolectricTestRunner::class)
class AlbumTrackOrderTest {

    @Before
    fun resetAliases() {
        ArtistAliasResolver.replace(emptyMap())
    }

    @Test
    fun dedupeOrdersByDiscThenTrackNumber() {
        val scrambled = listOf(
            song("Isle of Flightless Birds", tn = 14),
            song("Implicit Demand for Proof", tn = 1),
            song("Taxi Cab", tn = 12),
            song("Fall Away", tn = 2),
            song("Johnny Boy", tn = 7)
        )
        val ordered = dedupeAlbumPageTracks(scrambled)
        assertEquals(listOf(1, 2, 7, 12, 14), ordered.map { it.trackNumber })
    }

    @Test
    fun multiSourceCopiesCollapseAndStayOrdered() {
        val local = (1..14).map { n ->
            song(SELF_TITLED[n - 1], tn = n, key = "local-$n")
        }
        val jelly = (1..14).map { n ->
            song(SELF_TITLED[n - 1], tn = n, disc = 1, key = "jelly-$n")
        }
        val ordered = dedupeAlbumPageTracks((local + jelly).shuffled())
        assertEquals(14, ordered.size)
        assertEquals((1..14).toList(), ordered.map { it.trackNumber })
    }

    @Test
    fun clancyCravingApostropheDoesNotDuplicate() {
        val local = song("The Craving (Jenna's Version)", album = "Clancy", tn = 7, key = "l")
        val jelly = song("The Craving (Jenna’s Version)", album = "Clancy", tn = 7, key = "j")
        val others = listOf(
            song("Overcompensate", album = "Clancy", tn = 1, key = "o")
        )
        val ordered = dedupeAlbumPageTracks(others + local + jelly)
        assertEquals(2, ordered.size)
        assertEquals(listOf(1, 7), ordered.map { it.trackNumber })
    }

    @Test
    fun ohMsBelieverCommaDoesNotDuplicate() {
        val local = song("Oh, Ms. Believer", album = "Twenty One Pilots", tn = 8, key = "l")
        val jelly = song("Oh Ms. Believer", album = "twenty one pilots", tn = 8, key = "j")
        val ordered = dedupeAlbumPageTracks(listOf(local, jelly, song("Fall Away", album = "Twenty One Pilots", tn = 2)))
        assertEquals(2, ordered.size)
        assertEquals(listOf(2, 8), ordered.map { it.trackNumber })
    }

    @Test
    fun discTwoSortsAfterDiscOne() {
        val tracks = listOf(
            song("B1", tn = 1, disc = 2, key = "b1"),
            song("A2", tn = 2, disc = 1, key = "a2"),
            song("A1", tn = 1, disc = 1, key = "a1")
        )
        assertEquals(
            listOf("A1", "A2", "B1"),
            dedupeAlbumPageTracks(tracks).map { it.title }
        )
    }

    @Test
    fun filenameTrackHintWhenTagMissing() {
        val untitled = song(
            "Oh, Ms. Believer",
            tn = null,
            path = "/music/Twenty One Pilots/08. Oh, Ms. Believer.flac",
            key = "file"
        )
        val numbered = song("Fall Away", tn = 2, key = "n")
        assertEquals(
            listOf(2, null),
            dedupeAlbumPageTracks(listOf(untitled, numbered)).map { it.trackNumber }
        )
        assertEquals(
            listOf("Fall Away", "Oh, Ms. Believer"),
            dedupeAlbumPageTracks(listOf(untitled, numbered)).map { it.title }
        )
    }
}

private val SELF_TITLED = listOf(
    "Implicit Demand for Proof",
    "Fall Away",
    "The Pantaloon",
    "Addict with a Pen",
    "Friend, Please",
    "March to the Sea",
    "Johnny Boy",
    "Oh, Ms. Believer",
    "Air Catcher",
    "Trapdoor",
    "A Car, A Torch, A Death",
    "Taxi Cab",
    "Before You Start Your Day",
    "Isle of Flightless Birds"
)

private fun song(
    title: String,
    album: String = "Twenty One Pilots",
    artist: String = "twenty one pilots",
    tn: Int? = null,
    disc: Int? = null,
    path: String? = null,
    key: String = title
): Song = Song(
    id = key.hashCode().toLong(),
    title = title,
    artist = artist,
    albumArtist = artist,
    album = album,
    contentUri = "file:///$key",
    trackNumber = tn,
    discNumber = disc,
    path = path ?: "/music/$key/$album/${tn ?: 0}. $title.flac"
)

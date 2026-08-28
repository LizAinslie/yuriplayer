package capital.yuri.yuriplayer.core.library

import capital.yuri.yuriplayer.data.TrackIdentity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtistIdentityTest {

    @Test
    fun prefixArtistDoesNotMatchLongerName() {
        // Regression: "Rav" must never collapse with "Ravenna Golden" just
        // because the folded shorter name is a substring of the longer one.
        assertFalse(TrackIdentity.albumArtistsMatch("Rav", "Ravenna Golden"))
        assertFalse("Rav".matchesArtistName("Ravenna Golden"))
        assertFalse("Ravenna Golden".matchesArtistName("Rav"))
        assertFalse("Rav".matchesCreditedArtist("Ravenna Golden"))
    }

    @Test
    fun samePrimaryArtistMatchesIgnoringCaseAndStylization() {
        assertTrue(TrackIdentity.albumArtistsMatch("Lemon Demon", "lemon demon"))
        assertTrue("Lemon Demon".matchesArtistName("LEMON DEMON"))
        assertTrue("Twenty Øne Piløts".matchesArtistName("twenty one pilots"))
    }

    @Test
    fun featCreditStillResolvesPrimary() {
        // "Camellia feat. nanahira" primary is "Camellia".
        assertTrue(TrackIdentity.albumArtistsMatch("Camellia", "Camellia feat. nanahira"))
        assertTrue("Camellia".matchesArtistName("Camellia feat. nanahira"))
    }

    @Test
    fun featuredCreditMatchesAppearsOn() {
        assertTrue("Lemon Demon feat. Rav".matchesCreditedArtist("Rav"))
        assertTrue("Rav".matchesCreditedArtist("Rav"))
    }
}

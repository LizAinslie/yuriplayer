package capital.yuri.yuriplayer.core.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YuriLogTest {
    @Test
    fun redactsStreamTokens() {
        val raw = "https://navidrome.example/rest/stream.view?u=lizzy&t=deadbeef&s=salt&id=abc"
        val out = redactSecrets(raw)
        assertFalse("deadbeef" in out)
        assertTrue("t=***" in out)
        assertTrue("id=abc" in out)
    }

    @Test
    fun normalizesTags() {
        assertEquals("Radio", normalizeLogTag("YuriPlayer.Radio"))
        assertEquals("Radio", normalizeLogTag("Radio"))
        assertEquals("App", normalizeLogTag("YuriPlayer"))
        assertEquals("App", normalizeLogTag("YuriPlayer."))
        assertEquals("Vlcj", yuriLog("YuriPlayer.Vlcj").tag)
    }
}

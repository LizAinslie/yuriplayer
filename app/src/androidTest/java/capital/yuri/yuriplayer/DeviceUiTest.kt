package capital.yuri.yuriplayer

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import capital.yuri.yuriplayer.activities.MainActivity
import capital.yuri.yuriplayer.data.LibrarySettings
import capital.yuri.yuriplayer.player.engine.PlaybackEngineId
import capital.yuri.yuriplayer.ui.TestTags
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device UI tests against **your real library** (Jellyfin / Navidrome / local).
 * Does not wipe app data.
 *
 *   ./gradlew :app:connectedDebugAndroidTest
 *
 * Skip tests that need a queue if nothing is loaded. Engine-switch restores
 * the engine you had selected when the test started.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class DeviceUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun tabsAndCatalogOpen() {
        rule.waitForTag(TestTags.TAB_HOME)
        rule.onNodeWithTag(TestTags.TAB_HOME).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.TAB_MY_STUFF).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.TAB_EXPLORE).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.SETTINGS).assertIsDisplayed()

        rule.onNodeWithTag(TestTags.TAB_MY_STUFF).performClick()
        rule.waitForTag(TestTags.CATALOG_TITLE)
        rule.onNodeWithTag(TestTags.CATALOG_TITLE).assertTextContains("My Stuff")

        rule.onNodeWithTag(TestTags.TAB_EXPLORE).performClick()
        rule.onNodeWithTag(TestTags.TAB_HOME).performClick()
        rule.onNodeWithTag(TestTags.MYSTUFF_PINS).assertIsDisplayed()
    }

    @Test
    fun switchPlaybackEngineAndRestore() {
        val settings = LibrarySettings(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = settings.getPlaybackEngineId()
        try {
            openPlaybackEngineSettings()
            rule.onNodeWithTag(TestTags.ENGINE_VLC).performClick()
            rule.waitUntil(5_000) {
                settings.getPlaybackEngineId() == PlaybackEngineId.VLC
            }
            rule.onNodeWithTag(TestTags.ENGINE_MEDIA3).performClick()
            rule.waitUntil(5_000) {
                settings.getPlaybackEngineId() == PlaybackEngineId.MEDIA3
            }
        } finally {
            settings.setPlaybackEngineId(original)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun skipNextThenPrevUpdatesNowPlaying() {
        rule.waitForTag(TestTags.MINI_PLAYER)
        val idle = runCatching {
            rule.onNodeWithTag(TestTags.MINI_TITLE, useUnmergedTree = true)
                .assertTextContains("Not playing")
            true
        }.getOrDefault(false)
        assumeTrue("Queue a song on-device first (play anything), then re-run.", !idle)

        val before = rule.onNodeWithTag(TestTags.MINI_TITLE, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString { it.text }

        rule.onNodeWithTag(TestTags.MINI_PLAYER).performClick()
        rule.waitForTag(TestTags.NOW_PLAYING)
        rule.onNodeWithTag(TestTags.NP_SKIP_NEXT).performClick()
        rule.waitUntil(8_000) {
            val now = runCatching {
                rule.onNodeWithTag(TestTags.NP_TITLE, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                    .joinToString { it.text }
            }.getOrDefault(before)
            now != before && now.isNotBlank()
        }
        val afterNext = rule.onNodeWithTag(TestTags.NP_TITLE, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString { it.text }

        rule.onNodeWithTag(TestTags.NP_SKIP_PREV).performClick()
        rule.waitUntil(8_000) {
            val now = runCatching {
                rule.onNodeWithTag(TestTags.NP_TITLE, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                    .joinToString { it.text }
            }.getOrDefault(afterNext)
            now == before
        }
        rule.onNodeWithTag(TestTags.NP_TITLE, useUnmergedTree = true)
            .assertTextContains(before)
    }

    private fun openPlaybackEngineSettings() {
        rule.waitForTag(TestTags.SETTINGS)
        rule.onNodeWithTag(TestTags.SETTINGS).performClick()
        rule.waitForTag(TestTags.SETTINGS_PLAYBACK_ENGINE)
        rule.onNodeWithTag(TestTags.SETTINGS_PLAYBACK_ENGINE).performClick()
        rule.waitForTag(TestTags.ENGINE_VLC)
        rule.onNodeWithTag(TestTags.ENGINE_MEDIA3).assertExists()
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitForTag(
    tag: String,
    timeoutMs: Long = 10_000
) {
    waitUntil(timeoutMs) {
        runCatching {
            onNodeWithTag(tag).assertExists()
            true
        }.getOrDefault(false)
    }
}

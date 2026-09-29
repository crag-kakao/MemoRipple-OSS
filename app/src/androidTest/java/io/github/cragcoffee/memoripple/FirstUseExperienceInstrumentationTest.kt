package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.domain.settings.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 1.0 usability closure: the static welcome page appears exactly once and makes no
 * data, and the system bars follow the app's own 外観 rather than the OS's. First-use
 * flags are device-local preferences; every one this class flips is restored.
 */
class FirstUseExperienceInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun application(): MemoRippleApplication =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as MemoRippleApplication

    @Before
    fun resetBeforeTest() {
        runBlocking {
            application().database.clearAllTables()
            application().settingsRepository.resetToDefaults()
            application().settingsRepository.setAutoPlayOnLaunch(false)
        }
    }

    @After
    fun restoreFirstUseFlags() {
        runBlocking {
            application().settingsRepository.setHasSeenWelcomeDemo(true)
            application().settingsRepository.setHasSeenOverlaySetup(true)
            application().settingsRepository.setHasSeenOutlineGuide(true)
            application().settingsRepository.setTheme(ThemeMode.SYSTEM)
        }
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theWelcomeShowsOnceMakesNoDataAndStartEndsIt() {
        // The runner marked it seen so every other test lands on the app; un-see it here.
        runBlocking { application().settingsRepository.setHasSeenWelcomeDemo(false) }
        awaitTag("welcome_demo")

        composeRule.onNodeWithTag("welcome_demo_start").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { application().settingsRepository.hasSeenWelcomeDemo.first() }
        }
        awaitTag("memo_top_menu")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("welcome_demo").fetchSemanticsNodes().isEmpty()
        }
        // The welcome is a page, not a producer: no memo, no comment, nothing persisted.
        val memos = runBlocking {
            application().database.memoDao().observeMemos("").first()
        }
        assertTrue(memos.isEmpty())
    }

    @Test
    fun aSeenWelcomeStaysSeenAcrossTheAppComposingAgain() {
        // The default path every other test walks: flag true, demo absent, app present.
        awaitTag("memo_top_menu")
        assertTrue(
            composeRule.onAllNodesWithTag("welcome_demo").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun theOutlineGuideRidesTheFirstChipTapOnceAndTheMarkerStillLands() {
        runBlocking { application().settingsRepository.setHasSeenOutlineGuide(false) }
        awaitTag("memo_top_menu")
        composeRule.onNodeWithTag("create_memo").performClick()
        awaitTag("memo_body")
        composeRule.onNodeWithTag("memo_body").performClick()
        awaitTag("outline_helper_0")

        composeRule.onNodeWithTag("outline_helper_0").performScrollTo().performClick()
        awaitTag("outline_first_use_guide")
        composeRule.onNodeWithTag("outline_guide_try").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { application().settingsRepository.hasSeenOutlineGuide.first() }
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("outline_first_use_guide")
                .fetchSemanticsNodes().isEmpty()
        }

        // The second tap is just a chip again.
        composeRule.onNodeWithTag("outline_helper_0").performScrollTo().performClick()
        assertTrue(
            composeRule.onAllNodesWithTag("outline_first_use_guide")
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun theSystemBarsFollowTheAppsOwnAppearance() {
        awaitTag("memo_top_menu")
        val window = composeRule.activity.window

        fun lightBars(): Pair<Boolean, Boolean> {
            var status = false
            var navigation = false
            composeRule.activityRule.scenario.onActivity {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                status = controller.isAppearanceLightStatusBars
                navigation = controller.isAppearanceLightNavigationBars
            }
            return status to navigation
        }

        runBlocking { application().settingsRepository.setTheme(ThemeMode.LIGHT) }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            lightBars() == (true to true)
        }
        val (lightStatus, lightNavigation) = lightBars()
        assertTrue(lightStatus)
        assertTrue(lightNavigation)

        runBlocking { application().settingsRepository.setTheme(ThemeMode.DARK) }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            lightBars() == (false to false)
        }
        val (darkStatus, darkNavigation) = lightBars()
        assertFalse(darkStatus)
        assertFalse(darkNavigation)
    }
}

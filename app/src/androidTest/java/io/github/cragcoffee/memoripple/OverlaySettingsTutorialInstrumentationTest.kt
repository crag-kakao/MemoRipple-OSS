package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The overlay permission, asked for from 設定 as well: switching 「メモホームのコメントをオーバーレイで
 * 流す」 on without the permission opens the same disclosure the editor's ▶ opens, now with a
 * short tutorial of what to do in Android's list, and 設定を開く leads there.
 */
@OptIn(ExperimentalTestApi::class)
class OverlaySettingsTutorialInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MemoRippleApplication

    @Before
    fun startFromDefaults() {
        runBlocking {
            application.database.clearAllTables()
            application.settingsRepository.resetToDefaults()
            application.settingsRepository.resetPlaybackStyle()
            application.settingsRepository.setAutoPlayOnLaunch(false)
        }
    }

    @Test
    fun switchingTheOverlayOnWithoutThePermissionShowsTheDisclosureWithItsTutorial() {
        // Only meaningful while the permission is not held; a device that already grants it
        // has nothing to disclose.
        assumeFalse(android.provider.Settings.canDrawOverlays(application))
        composeRule.onNodeWithContentDescription("メモの整理と設定").performClick()
        composeRule.onNodeWithText("設定").performClick()
        awaitTag("settings_list")
        composeRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("setting_wall_overlay_playback"))
        composeRule.onNodeWithTag("wall_overlay_playback_switch").performClick()
        awaitTag("settings_overlay_disclosure")
        composeRule.onNodeWithTag("overlay_permission_tutorial").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_open_overlay_permission").assertIsDisplayed()
        // The choice itself stands: cancelling the disclosure leaves the switch on.
        composeRule.onNodeWithTag("settings_cancel_overlay_disclosure").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("settings_overlay_disclosure").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("wall_overlay_playback_switch").assertIsOn()
        assertTrue(runBlocking { application.settingsRepository.wallOverlayPlayback.first() })
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.height
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The chip units have a stage of their own: from the bar's list, 見出し・項目などの記号 opens
 * its five chips as the bar shows them (glyph and word), a chip is hidden and carried up or
 * down, and the memo bar wears the arrangement.
 */
@OptIn(ExperimentalTestApi::class)
class ToolbarChipsInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MemoRippleApplication

    @Before
    fun startFromAnEmptyWall() {
        runBlocking {
            application.database.clearAllTables()
            application.settingsRepository.resetToDefaults()
            application.settingsRepository.resetPlaybackStyle()
            application.settingsRepository.setAutoPlayOnLaunch(false)
            application.settingsRepository.setToolbarChips("MEMO", "LABELS", "")
        }
    }

    @After
    fun releaseTheClock() {
        composeRule.mainClock.autoAdvance = true
        runBlocking { application.settingsRepository.setToolbarChips("MEMO", "LABELS", "") }
    }

    @Test
    fun theLabelChipsAreArrangedOnTheirStageAndTheMemoBarWearsIt() {
        composeRule.onNodeWithContentDescription("メモの整理と設定").performClick()
        composeRule.onNodeWithText("設定").performClick()
        awaitTag("settings_list")
        composeRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("setting_toolbar_order_memo"))
        composeRule.onNodeWithTag("setting_toolbar_order_memo").performClick()
        awaitTag("toolbar_order_row_outline_labels")
        composeRule.onNodeWithTag("toolbar_order_list").performScrollToNode(hasTestTag("toolbar_order_row_outline_labels"))
        composeRule.onNodeWithTag("toolbar_order_open_outline_labels").performClick()

        awaitTag("toolbar_chips_row_heading")
        composeRule.onNodeWithText("見出し・項目などの記号").assertIsDisplayed()
        composeRule.onNodeWithTag("toolbar_chips_row_question").assertIsDisplayed()
        // Hide 疑問; carry 見出し down one row.
        composeRule.onNodeWithTag("toolbar_chips_hide_question").performClick()
        val rowHeight = composeRule.onNodeWithTag("toolbar_chips_row_heading").getBoundsInRoot().height
        composeRule.onNodeWithTag("toolbar_chips_handle_heading", useUnmergedTree = true).performTouchInput {
            swipeDown(startY = centerY, endY = centerY + with(composeRule.density) { (rowHeight * 1.2f).toPx() })
        }
        val expected = "item|heading|note|important|-question"
        runCatching {
            composeRule.waitUntil(5_000) {
                runBlocking { application.settingsRepository.toolbarChips("MEMO", "LABELS").first() } == expected
            }
        }.onFailure {
            val stored = runBlocking { application.settingsRepository.toolbarChips("MEMO", "LABELS").first() }
            throw AssertionError("stage arrangement: expected $expected but stored '$stored'")
        }

        // The memo bar: 項目 first, 見出し second, no 疑問.
        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("create_memo")
        composeRule.onNodeWithTag("create_memo").performClick()
        awaitTag("memo_body")
        composeRule.onNodeWithTag("memo_body").performClick()
        awaitTag("outline_helper_1")
        composeRule.onAllNodesWithTag("outline_helper_4").assertCountEquals(0)
        // The bar scrolls sideways, so the chips are measured unclipped: a chip past the edge would
        // otherwise collapse onto it and the comparison would say nothing.
        val itemLeft = composeRule.onNodeWithTag("outline_helper_1").getUnclippedBoundsInRoot().left
        val headingLeft = composeRule.onNodeWithTag("outline_helper_0").getUnclippedBoundsInRoot().left
        assertTrue("項目 at $itemLeft should stand left of 見出し at $headingLeft", itemLeft < headingLeft)
        assertEquals(1, composeRule.onAllNodesWithTag("outline_helper_0").fetchSemanticsNodes().size)
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

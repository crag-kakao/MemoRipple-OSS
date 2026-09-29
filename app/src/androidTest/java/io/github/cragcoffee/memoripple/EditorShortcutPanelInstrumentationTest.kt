package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The memo editor's shortcut-bar panels (the user's remark of 2026-09-21): テンプレート then
 * メモへのリンク leaves one panel, not two; × leaves none; a swipe down on the handle closes a
 * panel like the 再生設定 sheet; and 「テンプレートを作成」 inside the テンプレート panel makes a
 * template the same panel then inserts — no settings list on the way.
 */
class EditorShortcutPanelInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    @Before
    fun startFromAnEmptyWall() {
        runBlocking {
            application.database.clearAllTables()
            application.settingsRepository.resetToDefaults()
            application.settingsRepository.setAutoPlayOnLaunch(false)
            application.templateRepository.replaceAll(emptyList())
        }
    }

    private fun openANewMemo(body: String) {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("memo_empty_state").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("create_memo").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("memo_body").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("memo_body").performTextInput(body)
        closeSoftKeyboard()
        composeRule.waitForIdle()
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitGone(tag: String) {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aSecondToolSwapsThePanelAndTheCloseLeavesNothingBehind() {
        openANewMemo("本文")
        composeRule.onNodeWithTag("toolbar_template").performScrollTo().performClick()
        awaitTag("memo_template_sheet")
        composeRule.onNodeWithTag("toolbar_link").performScrollTo().performClick()
        awaitTag("memo_link_sheet")
        awaitGone("memo_template_sheet")
        composeRule.onNodeWithTag("memo_link_sheet").assertIsDisplayed()

        composeRule.onNodeWithTag("memo_link_sheet_close").performClick()
        awaitGone("memo_link_sheet")
        awaitGone("memo_template_sheet")
        awaitGone("comment_link_picker_sheet")

        // the same tool again closes its own panel
        composeRule.onNodeWithTag("toolbar_comment_link").performScrollTo().performClick()
        awaitTag("comment_link_picker_sheet")
        composeRule.onNodeWithTag("toolbar_comment_link").performScrollTo().performClick()
        awaitGone("comment_link_picker_sheet")
    }

    @Test
    fun aSwipeDownOnTheHandleClosesThePanel() {
        openANewMemo("本文")
        composeRule.onNodeWithTag("toolbar_template").performScrollTo().performClick()
        awaitTag("memo_template_sheet")
        // a real swipe travels well past the handle (a nudge of the handle's own height springs back)
        composeRule.onNodeWithTag("memo_template_sheet_handle").performTouchInput {
            swipeDown(startY = top, endY = bottom + 900f, durationMillis = 120)
        }
        awaitGone("memo_template_sheet")
        composeRule.onNodeWithTag("memo_body").assertIsDisplayed()
    }

    @Test
    fun aTemplateIsMadeInsideThePanelAndInsertedByIt() {
        openANewMemo("")
        composeRule.onNodeWithTag("toolbar_template").performScrollTo().performClick()
        awaitTag("memo_template_sheet")
        composeRule.onNodeWithTag("memo_template_create").performClick()
        awaitTag("memo_template_new_name")
        composeRule.onNodeWithTag("memo_template_new_name").performTextInput("週報のかたち")
        composeRule.onNodeWithTag("memo_template_new_body").performTextInput("- 今週やったこと\n- 来週やること")
        composeRule.onNodeWithTag("memo_template_new_save").performClick()
        // the panel returns to its list with the new template on it, and it pastes as before
        awaitGone("memo_template_new_name")
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("memo_template_create").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("週報のかたち", substring = true).performClick()
        awaitGone("memo_template_sheet")
        composeRule.onNodeWithTag("memo_body").assertTextContains("- 今週やったこと", substring = true)
        runBlocking {
            val kept = application.templateRepository.current()
            check(kept.size == 1 && kept.single().name == "週報のかたち" && kept.single().fields.isEmpty()) { "kept: $kept" }
        }
    }

    @Test
    fun anEmptyTemplateIsRefusedInsideThePanel() {
        openANewMemo("この本文をかたちに")
        composeRule.onNodeWithTag("toolbar_template").performScrollTo().performClick()
        awaitTag("memo_template_sheet")
        composeRule.onNodeWithTag("memo_template_create").performClick()
        awaitTag("memo_template_new_save")
        composeRule.onNodeWithTag("memo_template_new_save").performClick()
        composeRule.onNodeWithTag("memo_template_new_error").assertIsDisplayed()
        // this memo's body fills the form in one tap
        composeRule.onNodeWithTag("memo_template_new_from_memo").performClick()
        composeRule.onNodeWithTag("memo_template_new_body").assertTextContains("この本文をかたちに", substring = true)
        composeRule.onNodeWithTag("memo_template_new_back").performClick()
        awaitGone("memo_template_new_name")
        composeRule.onNodeWithTag("memo_template_sheet").assertIsDisplayed()
        runBlocking { check(application.templateRepository.current().isEmpty()) }
    }
}

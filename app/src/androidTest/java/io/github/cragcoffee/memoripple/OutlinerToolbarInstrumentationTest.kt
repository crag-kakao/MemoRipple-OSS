package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * ショートカットバー（アウトライナー）: the outliner's bar is arranged in 設定 like the editors'
 * bars — hidden tools leave it — and its label chips and 流れ方 write the caret's line the way
 * the memo bar does.
 */
@OptIn(ExperimentalTestApi::class)
class OutlinerToolbarInstrumentationTest {
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
            application.settingsRepository.setEditorToolbarOutlinerOrder("")
        }
    }

    @After
    fun releaseTheClock() {
        composeRule.mainClock.autoAdvance = true
        // The two-row choice is device-local, outside resetToDefaults: hand it back.
        runBlocking {
            application.settingsRepository.setEditorToolbarOutlinerOrder("")
            application.settingsRepository.setEditorToolbarTwoRows(false)
        }
    }

    @Test
    fun theLabelChipsAndTheFlowMarksWriteTheCaretsLine() {
        val memoId = openOutliner("- 一\n- 二")

        composeRule.onNodeWithTag("outliner_node_2").performClick()
        composeRule.onNodeWithTag("outliner_label_0").performScrollTo().performClick()
        composeRule.onNodeWithTag("outliner_flow_right").performScrollTo().performClick()
        composeRule.onNodeWithTag("outliner_task").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("memo_view_outliner")
        composeRule.waitUntil(5_000) {
            runBlocking { application.database.memoDao().findById(memoId)?.body }?.endsWith("→") == true
        }
        val body = runBlocking { application.database.memoDao().findById(memoId)!!.body }
        val second = body.lines()[1]
        assertTrue("second line should carry a heading mark and end with the flow token: $second", !second.startsWith("- ") && second.endsWith("二 →"))
        assertEquals("- 一", body.lines()[0])
    }

    @Test
    fun theSettingsArrangeTheOutlinerBarAndAHiddenToolLeavesIt() {
        composeRule.onNodeWithContentDescription("メモの整理と設定").performClick()
        composeRule.onNodeWithText("設定").performClick()
        awaitTag("setting_toolbar_order_memo")
        composeRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("setting_toolbar_order_outliner"))
        composeRule.onNodeWithTag("setting_toolbar_order_outliner").performClick()
        awaitTag("toolbar_order_row_zoom")
        composeRule.onNodeWithText("ショートカットバー（アウトライナー）").assertIsDisplayed()
        composeRule.onNodeWithTag("toolbar_order_row_move_lines").assertIsDisplayed()
        // The memo's writing aids stand on this bar too since round 22; the prose marks never do.
        composeRule.onNodeWithTag("toolbar_order_list").performScrollToNode(hasTestTag("toolbar_order_row_photo"))
        composeRule.onNodeWithTag("toolbar_order_row_photo").assertIsDisplayed()
        composeRule.onAllNodesWithTag("toolbar_order_row_prose_marks").assertCountEquals(0)
        composeRule.onNodeWithTag("toolbar_order_hide_zoom").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { application.settingsRepository.editorToolbarOutlinerOrder.first() }.contains("-^zoom")
        }

        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.onNodeWithContentDescription("戻る").performClick()
        val memoId = openOutliner("- 一")
        composeRule.onNodeWithTag("outliner_node_1").performClick()
        composeRule.onAllNodesWithTag("outliner_zoom").assertCountEquals(0)
        composeRule.onNodeWithTag("outliner_fold").assertIsDisplayed()
        assertTrue(memoId > 0)
    }

    @Test
    fun theTwoRowSettingSplitsTheOutlinerBarAndItsList() {
        runBlocking { application.settingsRepository.setEditorToolbarTwoRows(true) }
        composeRule.onNodeWithContentDescription("メモの整理と設定").performClick()
        composeRule.onNodeWithText("設定").performClick()
        awaitTag("setting_toolbar_order_memo")
        composeRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("setting_toolbar_order_outliner"))
        composeRule.onNodeWithTag("setting_toolbar_order_outliner").performClick()
        awaitTag("toolbar_order_heading_upper")
        // 字下げ is a line tool and stands in the upper row; チェックボックス says what a line is and
        // stands in the lower one.
        // The list is long now (the writing aids stand in the upper row too), so each pair is
        // read where it is composed: 字下げ right under 上部の段, チェックボックス right under 下部の段.
        composeRule.onNodeWithTag("toolbar_order_list").performScrollToNode(hasTestTag("toolbar_order_row_indent"))
        val upperHeading = composeRule.onNodeWithTag("toolbar_order_heading_upper").getUnclippedBoundsInRoot().top
        val indent = composeRule.onNodeWithTag("toolbar_order_row_indent").getUnclippedBoundsInRoot().top
        assertTrue("upper $upperHeading < 字下げ $indent", upperHeading < indent)
        composeRule.onNodeWithTag("toolbar_order_list").performScrollToNode(hasTestTag("toolbar_order_row_task"))
        val lowerHeading = composeRule.onNodeWithTag("toolbar_order_heading_lower").getUnclippedBoundsInRoot().top
        val task = composeRule.onNodeWithTag("toolbar_order_row_task").getUnclippedBoundsInRoot().top
        assertTrue("lower $lowerHeading < チェックボックス $task", lowerHeading < task)

        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.onNodeWithContentDescription("戻る").performClick()
        openOutliner("- 一")
        composeRule.onNodeWithTag("outliner_node_1").performClick()
        awaitTag("outliner_task")
        // The bar itself stands in two rows: 字下げ above チェックボックス.
        composeRule.waitUntil(5_000) {
            composeRule.onNodeWithTag("outliner_indent").getUnclippedBoundsInRoot().top <
                composeRule.onNodeWithTag("outliner_task").getUnclippedBoundsInRoot().top
        }
        composeRule.onNodeWithTag("outliner_toolbar_lower").assertIsDisplayed()
    }

    @Test
    fun theWritingAidsActOnTheCaretsLine() {
        runBlocking { application.templateRepository.save(MemoTemplate(id = "t1", name = "段取り", body = "甲\n乙")) }
        val memoId = openOutliner("- 一二三\n- 二")
        composeRule.onNodeWithTag("outliner_node_1").performClick()
        listOf("toolbar_add_photo", "toolbar_undo", "toolbar_redo", "toolbar_bold", "toolbar_highlight", "toolbar_template", "toolbar_link", "toolbar_comment_link")
            .forEach { tag -> composeRule.onNodeWithTag(tag).assertExists() }

        // 太字 on the selected words of the live line; 元に戻す and やり直し walk the change.
        composeRule.onNodeWithTag("outliner_node_1").performTextInputSelection(TextRange(0, 2))
        composeRule.onNodeWithTag("toolbar_bold").performScrollTo().performClick()
        composeRule.onNodeWithTag("outliner_node_1").assertTextContains("**一二**三")
        composeRule.onNodeWithTag("toolbar_undo").performScrollTo().performClick()
        composeRule.onNodeWithTag("outliner_node_1").assertTextContains("一二三")
        composeRule.onNodeWithTag("toolbar_redo").performScrollTo().performClick()
        composeRule.onNodeWithTag("outliner_node_1").assertTextContains("**一二**三")

        // A two-line template on the second line: 甲 joins it, 乙 becomes the line after.
        composeRule.onNodeWithTag("outliner_node_2").performClick()
        composeRule.onNodeWithTag("toolbar_template").performScrollTo().performClick()
        awaitTag("memo_template_t1")
        composeRule.onNodeWithTag("memo_template_t1").performClick()
        awaitTag("outliner_node_3")
        composeRule.onNodeWithTag("outliner_node_2").assertTextContains("二甲")
        composeRule.onNodeWithTag("outliner_node_3").assertTextContains("乙")

        // メモへのリンク opens the memo's own picker.
        composeRule.onNodeWithTag("toolbar_link").performScrollTo().performClick()
        awaitTag("memo_link_sheet")
        // Back closes the keyboard first when it is up, then the panel — as the memo tests press it.
        repeat(2) {
            if (composeRule.onAllNodesWithTag("memo_link_sheet").fetchSemanticsNodes().isEmpty()) return@repeat
            androidx.test.espresso.Espresso.pressBack()
            runCatching { composeRule.waitUntil(3_000) { composeRule.onAllNodesWithTag("memo_link_sheet").fetchSemanticsNodes().isEmpty() } }
        }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("memo_link_sheet").fetchSemanticsNodes().isEmpty() }

        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { application.database.memoDao().findById(memoId)?.body } == "- **一二**三\n- 二甲\n- 乙"
        }
    }

    private fun openOutliner(body: String): Long {
        val memoId = runBlocking {
            application.database.memoDao().insert(
                MemoEntity(title = "", body = body, createdAt = 1, updatedAt = 1, kind = MemoKind.OUTLINE.storageId),
            )
        }
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$memoId")
        composeRule.onNodeWithTag("outline_card_$memoId").performClick()
        awaitTag("outliner_node_1")
        return memoId
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

package io.github.cragcoffee.memoripple

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.domain.outline.OutlineRows
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The outliner on stable rows, on the device (docs/OUTLINE_STABLE_ROWS.md, Stage 1): an outline
 * written before rows opens looking the same with its body untouched; a zoom whose anchor line is
 * joined away does not crash and stands on the parent; reorder, indent and outdent come back after
 * the activity is recreated with the same rows in the same order under the same ids; a ticked task,
 * a fold and a zoom survive the recreation.
 */
@OptIn(ExperimentalTestApi::class)
class OutlinerStableRowsInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    private val body = "- 一\n  - 二\n    - 三\n- 四\n- [ ] 五"

    @Before
    fun startEmpty() {
        runBlocking {
            app.database.clearAllTables()
            app.settingsRepository.setAutoPlayOnLaunch(false)
            app.settingsRepository.clearOutlinerFolds()
        }
    }

    @After
    fun forgetFolds() {
        runBlocking { app.settingsRepository.clearOutlinerFolds() }
    }

    /** An outline as the upgrade finds it: a body, no rows yet. */
    private fun openOutliner(): Long {
        val memoId = runBlocking {
            app.database.memoDao().insert(
                MemoEntity(title = "計画", body = body, createdAt = 1_783_000_000_000L, updatedAt = 1_783_000_000_000L, kind = MemoKind.OUTLINE.storageId),
            )
        }
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$memoId")
        composeRule.onNodeWithTag("outline_card_$memoId").performClick()
        awaitTag("outliner_node_5")
        return memoId
    }

    private fun rows(memoId: Long) = runBlocking { app.database.outlineRowDao().rows(memoId).map { OutlineRows.Row(it.rowId, it.text) } }
    private fun body(memoId: Long) = runBlocking { app.database.memoDao().findById(memoId)!!.body }
    private fun select(id: Int) { composeRule.onNodeWithTag("outliner_node_$id").performClick() }
    private fun awaitSaved(memoId: Long, check: (String) -> Boolean) = composeRule.waitUntil(5_000) { check(body(memoId)) }

    @Test
    fun anOutlineWrittenBeforeRowsOpensTheSameAndKeepsItsBody() {
        val memoId = openOutliner()
        (1..5).forEach { composeRule.onNodeWithTag("outliner_node_$it").assertIsDisplayed() }
        composeRule.onNodeWithTag("outliner_node_2").assertTextEquals("二")
        assertEquals("the body is untouched", body, body(memoId))
        assertEquals("each line has its row, 1..n", OutlineRows.fresh(body), rows(memoId))
    }

    @Test
    fun joiningTheZoomAnchorAwayDoesNotCrashAndStandsOnTheParent() {
        val memoId = openOutliner()
        select(2)
        composeRule.onNodeWithTag("outliner_zoom").performClick()
        awaitTag("outliner_breadcrumb")
        composeRule.onNodeWithTag("outliner_crumb_current").assertTextEquals("二")
        // Backspace at the very start of the zoomed line joins it to 一, outside the zoom.
        select(2)
        composeRule.onNodeWithTag("outliner_node_2").performTextInputSelection(TextRange(0))
        composeRule.onNodeWithTag("outliner_node_2").performKeyInput { pressKey(Key.Backspace) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("outliner_crumb_current").assertTextEquals("一二")
        composeRule.onNodeWithTag("outliner_node_3").assertIsDisplayed()
        composeRule.onAllNodesWithTag("outliner_node_2").assertCountEquals(0)
        awaitSaved(memoId) { it.startsWith("- 一二\n") }
        assertEquals(listOf(1, 3, 4, 5), rows(memoId).map { it.id })
    }

    @Test
    fun reorderComesBackAfterRecreationWithTheSameRowsAndIds() {
        val memoId = openOutliner()
        select(4)
        composeRule.onNodeWithTag("outliner_move_up").performClick()
        awaitSaved(memoId) { it.startsWith("- 四\n") }
        val saved = rows(memoId)
        assertEquals(listOf(4, 1, 2, 3, 5), saved.map { it.id })

        composeRule.activityRule.scenario.recreate()

        awaitTag("outliner_node_4")
        assertEquals(saved, rows(memoId))
        assertTrue("四 is drawn above 一", top("outliner_node_4") < top("outliner_node_1"))
    }

    @Test
    fun indentAndOutdentComeBackAfterRecreation() {
        val memoId = openOutliner()
        select(4)
        composeRule.onNodeWithTag("outliner_indent").performClick()
        awaitSaved(memoId) { "\n  - 四\n" in it }
        select(5)
        composeRule.onNodeWithTag("outliner_indent").performClick()
        composeRule.onNodeWithTag("outliner_outdent").performClick()
        awaitSaved(memoId) { it.endsWith("\n- [ ] 五") && "\n  - 四\n" in it }
        val saved = rows(memoId)

        composeRule.activityRule.scenario.recreate()

        awaitTag("outliner_node_4")
        assertEquals(saved, rows(memoId))
        assertEquals(listOf(1, 2, 3, 4, 5), saved.map { it.id })
        assertEquals("  - 四", saved.first { it.id == 4 }.line)
    }

    @Test
    fun aTaskAFoldAndAZoomSurviveRecreation() {
        val memoId = openOutliner()
        composeRule.onNodeWithTag("outliner_task_box_5").performClick()
        awaitSaved(memoId) { it.endsWith("- [x] 五") }
        select(2)
        composeRule.onNodeWithTag("outliner_fold").performClick()
        awaitTag("outliner_hidden_2")
        select(1)
        composeRule.onNodeWithTag("outliner_zoom").performClick()
        awaitTag("outliner_breadcrumb")

        composeRule.activityRule.scenario.recreate()

        awaitTag("outliner_breadcrumb")
        composeRule.onNodeWithTag("outliner_crumb_current").assertTextEquals("一")
        awaitTag("outliner_hidden_2")
        composeRule.onAllNodesWithTag("outliner_node_3").assertCountEquals(0)
        assertTrue(body(memoId).endsWith("- [x] 五"))
        assertEquals(listOf(1, 2, 3, 4, 5), rows(memoId).map { it.id })
    }

    private fun top(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

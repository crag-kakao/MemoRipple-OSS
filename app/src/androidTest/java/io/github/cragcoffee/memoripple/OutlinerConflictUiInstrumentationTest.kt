package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentWriteResult
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.domain.outline.OutlineEditing
import io.github.cragcoffee.memoripple.domain.outline.OutlineRows
import io.github.cragcoffee.memoripple.ui.memos.MemoEditorViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The outliner left open while the outline is written elsewhere (docs/OUTLINE_STABLE_ROWS.md §9):
 * the AI's append (through DocumentAccess, its version check and all) and a split-pane style write
 * (MemoRepository.save — exactly the pane's call) both move the version; the open outliner's next
 * save — typed, or the flush on leaving — is refused and says so; nothing it held is written; 再読み込み
 * brings the latest, which it then saves normally; and an editor that dies with words unsaved
 * leaves nothing stale behind.
 */
class OutlinerConflictUiInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    private val body = "- 一\n- 二\n- 三"

    @Before
    fun startEmpty() {
        runBlocking {
            app.database.clearAllTables()
            app.settingsRepository.setAutoPlayOnLaunch(false)
            app.settingsRepository.clearOutlinerFolds()
        }
    }

    private fun insertOutline(): Long = runBlocking {
        app.database.memoDao().insert(MemoEntity(title = "計画", body = body, createdAt = 1_783_000_000_000L, updatedAt = 1_783_000_000_000L, kind = MemoKind.OUTLINE.storageId))
    }

    private fun openOutliner(memoId: Long) {
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$memoId")
        composeRule.onNodeWithTag("outline_card_$memoId").performClick()
        awaitTag("outliner_node_3")
        composeRule.waitForIdle()
    }

    private fun memo(id: Long) = runBlocking { app.database.memoDao().findById(id)!! }
    private fun rows(id: Long) = runBlocking { app.database.outlineRowDao().rows(id).map { OutlineRows.Row(it.rowId, it.text) } }

    @Test
    fun anAiAppendWhileTheOutlinerIsOpenIsNeverOverwrittenAndReloadBringsItIn() {
        val id = insertOutline()
        openOutliner(id)
        // The AI appends through the safe pipeline's own door, version check and all.
        val append = runBlocking { app.documentAccess.append(DocumentRef(DocumentKind.OUTLINE, id), "AIの追記", memo(id).updatedAt) }
        assertTrue(append is DocumentWriteResult.Done)
        val latest = memo(id)
        val latestRows = rows(id)
        // The open outliner still holds the older outline; its writer types on.
        composeRule.onNodeWithTag("outliner_node_3").performClick()
        composeRule.onNodeWithTag("outliner_node_3").performTextInput("（古い画面）")
        awaitTag("outliner_conflict")
        composeRule.onNodeWithTag("outliner_conflict").assertIsDisplayed()
        assertEquals("the AI's words stand", latest.body, memo(id).body)
        assertEquals(latest.updatedAt, memo(id).updatedAt)
        assertEquals("no row written", latestRows, rows(id))
        assertFalse(memo(id).body.contains("古い画面"))

        // 再読み込み: the latest outline, and writing goes on from it.
        composeRule.onNodeWithTag("outliner_conflict_reload").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("outliner_conflict").fetchSemanticsNodes().isEmpty() }
        val aiRow = latestRows.last().id
        awaitTag("outliner_node_$aiRow")
        composeRule.onNodeWithTag("outliner_node_3").assertTextEquals("三")
        composeRule.onNodeWithTag("outliner_node_$aiRow").performClick()
        composeRule.onNodeWithTag("outliner_node_$aiRow").performTextInput("・続き")
        composeRule.waitUntil(5_000) { memo(id).body.endsWith("・続き") }
        assertEquals(latestRows.map { it.id }, rows(id).map { it.id })
    }

    @Test
    fun aSplitPaneWriteThenLeavingTheOutlinerFlushesNothingStale() {
        val id = insertOutline()
        openOutliner(id)
        composeRule.onNodeWithTag("outliner_node_2").performClick()
        composeRule.onNodeWithTag("outliner_node_2").performTextInput("（未保存）")
        // Within the autosave's pause, the split pane writes (its own call, MemoRepository.save).
        runBlocking { app.memoRepository.save(memo(id), "計画", "$body\n- 分割表示から", System.currentTimeMillis()) }
        val latest = memo(id)
        // The activity goes to the background: the flush on stop is refused, not written.
        composeRule.activityRule.scenario.recreate()
        awaitTag("outliner_conflict")
        assertEquals(latest.body, memo(id).body)
        assertEquals(latest.updatedAt, memo(id).updatedAt)
        assertFalse(memo(id).body.contains("未保存"))
    }

    @Test
    fun anEditorThatDiesWithWordsUnsavedLeavesNothingStaleAndANewOneReadsTheLatest() {
        val id = insertOutline()
        val factory = MemoEditorViewModel.factory(
            app.memoRepository, app.memoCommentRepository, id, app.speechController,
            app.tagRepository, app.attachmentRepository, app.templateRepository,
            outlineStore = app.outlineStore,
        )
        val storeA = ViewModelStore()
        val first = InstrumentationRegistry.getInstrumentation().let { instrumentation ->
            var vm: MemoEditorViewModel? = null
            instrumentation.runOnMainSync { vm = ViewModelProvider(storeA, factory)[MemoEditorViewModel::class.java] }
            vm!!
        }
        waitFor { first.uiState.value.outline != null }
        val opened = first.uiState.value.outline!!
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            first.updateOutline(OutlineEditing.updateText(opened, 1, "一（未保存のまま）"))
        }
        // Someone else writes; then the first editor's process is gone before its pause ends.
        runBlocking { app.memoRepository.save(memo(id), "計画", "$body\n- 外から", System.currentTimeMillis()) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { storeA.clear() }
        val latest = memo(id)
        val storeB = ViewModelStore()
        var second: MemoEditorViewModel? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { second = ViewModelProvider(storeB, factory)[MemoEditorViewModel::class.java] }
        waitFor { second!!.uiState.value.outline != null }
        assertEquals("the new editor reads the latest", latest.body, second!!.uiState.value.body)
        assertEquals(latest.body, memo(id).body)
        assertFalse(memo(id).body.contains("未保存のまま"))
        InstrumentationRegistry.getInstrumentation().runOnMainSync { storeB.clear() }
    }

    private fun waitFor(check: () -> Boolean) = composeRule.waitUntil(5_000, check)

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

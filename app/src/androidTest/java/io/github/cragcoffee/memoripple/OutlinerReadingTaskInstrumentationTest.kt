package io.github.cragcoffee.memoripple

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.AttachmentBlobEntity
import io.github.cragcoffee.memoripple.data.AttachmentBlobStore
import io.github.cragcoffee.memoripple.data.AttachmentKind
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoPhotoAttachmentEntity
import io.github.cragcoffee.memoripple.data.OutlineStore
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.domain.outline.OutlineRows
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * A task ticked on the outliner's reading page (閲覧モード) changes the task and nothing else
 * (2026-09-25): the folds, the zoom, the undo / redo history and the rows' ids and places — photo
 * rows included — are what they were, the tick is saved through the outliner's own version-checked
 * save, and it is an edit like any other: 元に戻す takes it back and やり直し gives it again. It used to
 * go round the outliner as a whole new body, which the screen then read as an outside write and
 * reset everything for.
 */
class OutlinerReadingTaskInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

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

    private suspend fun photo(memoId: Long): Long {
        val temp = File(app.cacheDir, "reading-task-${System.nanoTime()}.png")
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.MAGENTA)
            temp.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        val sha = AttachmentBlobStore.hash(temp)
        return app.attachmentRepository.installForRestore(listOf(Triple(temp, sha, temp.length()))) {
            app.database.withTransaction {
                app.database.attachmentDao().insertBlob(AttachmentBlobEntity(sha, AttachmentKind.IMAGE, "image/png", temp.length(), 1600, 900, 2L))
                app.database.attachmentDao().insertMemoRelation(MemoPhotoAttachmentEntity(memoId = memoId, blobSha256 = sha, sortOrder = 0, createdAt = 10L))
            }
        }
    }

    /**
     * 旅行 / [ ] 宿を取る / [photo] / 京都 / 帰る / [ ] 切符 — ids that are not 1..n; the reading
     * page's lines are the body's (photo rows are not lines): 0 旅行, 1 宿を取る, 2 京都, 3 帰る, 4 切符.
     */
    private val rows = listOf(
        OutlineRows.Row(21, "- 旅行"),
        OutlineRows.Row(4, "  - [ ] 宿を取る"),
        OutlineRows.Row(40, "  ", photo = 0L),
        OutlineRows.Row(9, "  - 京都"),
        OutlineRows.Row(30, "- 帰る"),
        OutlineRows.Row(12, "  - [ ] 切符"),
    )

    private fun seed(): Long = runBlocking {
        val body = OutlineRows.projection(rows)
        val id = app.database.memoDao().insert(MemoEntity(title = "旅行", body = body, createdAt = 1_783_000_000_000L, updatedAt = 1_783_000_000_000L, kind = MemoKind.OUTLINE.storageId))
        app.outlineStore.createFor(id, body)
        val p = photo(id)
        val placed = rows.map { if (it.photo != null) it.copy(photo = p) else it }
        val version = app.database.memoDao().findById(id)!!.updatedAt
        assertTrue(app.outlineStore.saveDocument(id, "旅行", OutlineRows.documentOf(placed), System.currentTimeMillis(), version) is OutlineStore.Save.Saved)
        id
    }

    private fun open(id: Long) {
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$id")
        composeRule.onNodeWithTag("outline_card_$id").performClick()
        awaitTag("outliner_node_21")
        composeRule.waitForIdle()
    }

    private fun toReading() {
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        awaitTag("memo_editor_reading_mode")
        composeRule.onNodeWithTag("memo_editor_reading_mode").performClick()
        awaitTag("reading_task_4")
    }

    private fun tickOnReadingPage(line: Int, id: Long, expectLine: String) {
        composeRule.onNodeWithTag("reading_task_$line").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { body(id).split("\n").contains(expectLine) }
    }

    private fun toEditing() {
        composeRule.onNodeWithTag("reading_edit").performClick()
        // 切符 is on the page folded, zoomed into 帰る, or neither.
        awaitTag("outliner_node_12")
        composeRule.waitForIdle()
    }

    private fun body(id: Long) = runBlocking { app.database.memoDao().findById(id)!!.body }
    private fun storedRows(id: Long) = runBlocking { app.database.outlineRowDao().rows(id).map { Triple(it.rowId, it.kind, it.text) } }
    private fun gone(tag: String) = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
    private fun awaitTag(tag: String) = composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aFoldStaysWhenATaskIsTickedOnTheReadingPage() {
        val id = seed()
        open(id)
        composeRule.onNodeWithTag("outliner_fold_21").performClick()
        composeRule.waitUntil(5_000) { gone("outliner_node_4") }
        toReading()
        tickOnReadingPage(4, id, "  - [x] 切符")
        toEditing()
        assertTrue("旅行 is still folded", gone("outliner_node_4") && gone("outliner_photo_40"))
        composeRule.onNodeWithTag("outliner_node_12").assertIsDisplayed()
    }

    @Test
    fun aZoomStaysWhenATaskIsTickedOnTheReadingPage() {
        val id = seed()
        open(id)
        composeRule.onNodeWithTag("outliner_node_30").performClick()
        composeRule.onNodeWithTag("outliner_zoom").performClick()
        awaitTag("outliner_breadcrumb")
        toReading()
        tickOnReadingPage(4, id, "  - [x] 切符")
        toEditing()
        awaitTag("outliner_breadcrumb")
        composeRule.onNodeWithTag("outliner_crumb_current").assertTextEquals("帰る")
        assertTrue("outside the zoom stays hidden", gone("outliner_node_21"))
    }

    @Test
    fun theUndoHistoryStaysAndTheTickIsItselfUndoableAndRedoable() {
        val id = seed()
        open(id)
        composeRule.onNodeWithTag("outliner_node_9").performClick()
        composeRule.onNodeWithTag("outliner_node_9").performTextInput("へ")
        composeRule.waitUntil(5_000) { body(id).contains("  - 京都へ") }
        toReading()
        tickOnReadingPage(1, id, "  - [x] 宿を取る")
        toEditing()
        composeRule.onNodeWithTag("outliner_node_21").performClick()
        // 元に戻す: the tick first, then the typing before it — the history was kept.
        composeRule.onNodeWithTag("toolbar_undo").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { body(id).contains("  - [ ] 宿を取る") }
        composeRule.onNodeWithTag("toolbar_undo").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { !body(id).contains("京都へ") }
        // やり直し gives both back, the tick last.
        composeRule.onNodeWithTag("toolbar_redo").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { body(id).contains("  - 京都へ") }
        composeRule.onNodeWithTag("toolbar_redo").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { body(id).contains("  - [x] 宿を取る") }
    }

    @Test
    fun aRedoWaitingBeforeTheTickIsStillThereAfterItsOwnUndo() {
        val id = seed()
        open(id)
        composeRule.onNodeWithTag("outliner_node_9").performClick()
        composeRule.onNodeWithTag("outliner_node_9").performTextInput("へ")
        composeRule.waitUntil(5_000) { body(id).contains("  - 京都へ") }
        composeRule.onNodeWithTag("toolbar_undo").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { !body(id).contains("京都へ") }
        toReading()
        tickOnReadingPage(4, id, "  - [x] 切符")
        toEditing()
        composeRule.onNodeWithTag("outliner_node_21").performClick()
        // The tick is an edit: 元に戻す takes it back, やり直し gives it again — never a reset history.
        composeRule.onNodeWithTag("toolbar_undo").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { body(id).contains("  - [ ] 切符") }
        composeRule.onNodeWithTag("toolbar_redo").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { body(id).contains("  - [x] 切符") }
    }

    @Test
    fun theTickKeepsEveryRowIdPlaceAndPhotoAndIsSavedAndSurvivesARestart() {
        val id = seed()
        val before = storedRows(id)
        open(id)
        toReading()
        tickOnReadingPage(1, id, "  - [x] 宿を取る")
        val after = storedRows(id)
        assertEquals("the same rows, ids and places", before.map { it.first to it.second }, after.map { it.first to it.second })
        assertEquals("only the ticked line changed", before.map { it.third }.mapIndexed { i, t -> if (i == 1) "  - [x] 宿を取る" else t }, after.map { it.third })
        assertEquals("the body is the rows' projection", OutlineRows.projection(runBlocking { app.database.outlineRowDao().rows(id) }.map { OutlineRows.Row(it.rowId, it.text, it.photoAttachmentId) }), body(id))
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(5_000) { body(id).contains("  - [x] 宿を取る") }
        assertEquals(after, storedRows(id))
    }

    @Test
    fun anOutsideWriteBeforeTheTickIsNeverOverwritten() {
        val id = seed()
        open(id)
        toReading()
        // The split pane writes while the reading page is open (its own call).
        runBlocking { app.memoRepository.save(app.database.memoDao().findById(id)!!, "旅行", body(id) + "\n- 外から", System.currentTimeMillis()) }
        val outside = body(id)
        composeRule.onNodeWithTag("reading_task_1").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { runBlocking { app.database.memoDao().findById(id)!!.body } == outside }
        toEditing()
        awaitTag("outliner_conflict")
        assertEquals("the outside write stands whole", outside, body(id))
        assertTrue(!body(id).contains("[x] 宿を取る"))
        composeRule.onAllNodesWithTag("outliner_conflict").assertCountEquals(1)
        composeRule.onNodeWithTag("outliner_node_21").assertTextContains("旅行")
    }
}

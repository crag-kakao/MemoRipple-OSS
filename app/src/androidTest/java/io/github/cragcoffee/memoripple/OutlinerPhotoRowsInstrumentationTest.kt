package io.github.cragcoffee.memoripple

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Photo rows in the outliner, on the device (docs/OUTLINE_PHOTO_ROWS.md, Stage 2): text → photo →
 * text shows in its order and the body holds no photo; a photo row moves, indents, folds away with
 * its parent and shows only inside a zoom that holds it; deleting one takes the row but keeps the
 * photo, and 元に戻す brings back the same row; the photo is let go only when the outliner is left
 * with everything saved — never while another writer's newer outline stands in conflict — and a
 * photo a row shows is never let go; the rows come back the same after the activity is recreated.
 */
class OutlinerPhotoRowsInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    private val body = "- 一\n  - 二\n- 三"

    @Before
    fun startEmpty() {
        runBlocking {
            app.database.clearAllTables()
            app.attachmentRepository.garbageCollect()
            app.settingsRepository.setAutoPlayOnLaunch(false)
            app.settingsRepository.clearOutlinerFolds()
        }
    }

    @After
    fun forgetFolds() {
        runBlocking { app.settingsRepository.clearOutlinerFolds() }
    }

    private suspend fun photo(memoId: Long, index: Int): Long {
        val temp = File(app.cacheDir, "outline-photo-$index-${System.nanoTime()}.png")
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.rgb(index * 9 % 256, 60 + index * 37 % 196, 255 - index * 11 % 256))
            temp.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        val size = temp.length()
        val sha = AttachmentBlobStore.hash(temp)
        return app.attachmentRepository.installForRestore(listOf(Triple(temp, sha, size))) {
            app.database.withTransaction {
                app.database.attachmentDao().insertBlob(AttachmentBlobEntity(sha, AttachmentKind.IMAGE, "image/png", size, 1600, 900, 2L + index))
                app.database.attachmentDao().insertMemoRelation(MemoPhotoAttachmentEntity(memoId = memoId, blobSha256 = sha, sortOrder = index, createdAt = 10L + index))
            }
        }
    }

    private var first = 0L
    private var second = 0L

    /** 一 / [photo 10, under 一] / 二 / 三 / [photo 11] — as the outliner writes them. */
    private fun seed(): Long = runBlocking {
        val id = app.database.memoDao().insert(
            MemoEntity(title = "旅行", body = body, createdAt = 1_783_000_000_000L, updatedAt = 1_783_000_000_000L, kind = MemoKind.OUTLINE.storageId),
        )
        app.outlineStore.createFor(id, body)
        first = photo(id, 0)
        second = photo(id, 1)
        val rows = listOf(
            OutlineRows.Row(1, "- 一"),
            OutlineRows.Row(10, "  ", photo = first),
            OutlineRows.Row(2, "  - 二"),
            OutlineRows.Row(3, "- 三"),
            OutlineRows.Row(11, "", photo = second),
        )
        val version = app.database.memoDao().findById(id)!!.updatedAt
        val saved = app.outlineStore.saveDocument(id, "旅行", OutlineRows.documentOf(rows), System.currentTimeMillis(), version)
        assertTrue(saved is OutlineStore.Save.Saved)
        id
    }

    private fun open(memoId: Long) {
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$memoId")
        composeRule.onNodeWithTag("outline_card_$memoId").performClick()
        awaitTag("outliner_photo_10")
        composeRule.waitForIdle()
    }

    private fun rows(memoId: Long) = runBlocking {
        app.database.outlineRowDao().rows(memoId).map { OutlineRows.Row(it.rowId, it.text, it.photoAttachmentId) }
    }
    private fun memo(memoId: Long) = runBlocking { app.database.memoDao().findById(memoId)!! }
    private fun relations(memoId: Long) = runBlocking { app.database.attachmentDao().memoRelations(memoId) }
    private fun fileOf(memoId: Long, attachmentId: Long): File? =
        relations(memoId).firstOrNull { it.id == attachmentId }?.let { app.attachmentBlobStore.blobFile(it.blobSha256) }
    private fun top(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y
    private fun menu(rowId: Int, item: String) {
        composeRule.onNodeWithTag("outliner_photo_menu_$rowId").performScrollTo().performClick()
        awaitTag("outliner_photo_$item")
        composeRule.onNodeWithTag("outliner_photo_$item").performClick()
        composeRule.waitForIdle()
    }
    private fun waitFor(check: () -> Boolean) = composeRule.waitUntil(5_000, check)

    @Test
    fun textPhotoTextShowsInItsOrderAndTheBodyHoldsNoPhoto() {
        val id = seed()
        open(id)
        assertTrue(top("outliner_node_1") < top("outliner_photo_10"))
        assertTrue(top("outliner_photo_10") < top("outliner_node_2"))
        assertTrue(top("outliner_node_2") < top("outliner_node_3"))
        composeRule.onNodeWithTag("outliner_photo_11").performScrollTo()
        assertTrue(top("outliner_node_3") < top("outliner_photo_11"))
        assertEquals("the body is the lines of words alone", body, memo(id).body)
        // No strip, no count on the page.
        composeRule.onAllNodesWithTag("memo_photo_strip").assertCountEquals(0)
        composeRule.onAllNodesWithText("枚", substring = true).assertCountEquals(0)
        // Writing a line goes on as ever, and the photo rows stay where they are.
        composeRule.onNodeWithTag("outliner_node_3").performClick()
        composeRule.onNodeWithTag("outliner_node_3").performTextInput("へ")
        waitFor { memo(id).body == "- 一\n  - 二\n- 三へ" }
        assertEquals(listOf(1, 10, 2, 3, 11), rows(id).map { it.id })
    }

    @Test
    fun aPhotoRowMovesIndentsFoldsAndShowsOnlyInsideItsZoom() {
        val id = seed()
        open(id)
        // The first child of 一 cannot go up; down swaps it with 二.
        composeRule.onNodeWithTag("outliner_photo_menu_10").performClick()
        awaitTag("outliner_photo_up")
        composeRule.onNodeWithTag("outliner_photo_up").assertIsNotEnabled()
        composeRule.onNodeWithTag("outliner_photo_down").performClick()
        waitFor { rows(id).map { it.id } == listOf(1, 2, 10, 3, 11) }
        assertTrue(top("outliner_node_2") < top("outliner_photo_10"))
        // 字下げ: photo 11 goes under 三, and back.
        menu(11, "indent")
        waitFor { rows(id).first { it.id == 11 }.line == "  " }
        menu(11, "outdent")
        waitFor { rows(id).first { it.id == 11 }.line == "" }
        assertEquals("nothing moved the words", body, memo(id).body)

        // Folding 一 hides the photo under it; unfolding shows it again.
        composeRule.onNodeWithTag("outliner_fold_1").performClick()
        waitFor { composeRule.onAllNodesWithTag("outliner_photo_10").fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag("outliner_fold_1").performClick()
        awaitTag("outliner_photo_10")

        // A zoom on 一 shows its photo and not the one outside.
        composeRule.onNodeWithTag("outliner_node_1").performClick()
        composeRule.onNodeWithTag("outliner_zoom").performClick()
        awaitTag("outliner_breadcrumb")
        composeRule.onAllNodesWithTag("outliner_photo_10").assertCountEquals(1)
        composeRule.onAllNodesWithTag("outliner_photo_11").assertCountEquals(0)
    }

    @Test
    fun deletingKeepsThePhotoForUndoAndLeavingSavedLetsOnlyItGo() {
        val id = seed()
        val secondFile = fileOf(id, second)!!
        val firstFile = fileOf(id, first)!!
        open(id)
        menu(11, "delete")
        composeRule.onAllNodesWithTag("outliner_photo_11").assertCountEquals(0)
        waitFor { rows(id).none { it.id == 11 } }
        assertTrue("the photo is kept while the outliner is open", relations(id).any { it.id == second })
        assertTrue(secondFile.exists())

        // 元に戻す brings back the same row, the same photo, in the same place.
        composeRule.onNodeWithTag("outliner_node_1").performClick()
        composeRule.onNodeWithTag("toolbar_undo").performScrollTo().performClick()
        awaitTag("outliner_photo_11")
        waitFor { rows(id).map { it.id } == listOf(1, 10, 2, 3, 11) }
        assertEquals(second, rows(id).first { it.id == 11 }.photo)
        composeRule.onNodeWithTag("toolbar_redo").performScrollTo().performClick()
        waitFor { rows(id).none { it.id == 11 } }

        // Leaving with everything saved: the photo no row shows is let go; the other stays.
        composeRule.onNodeWithContentDescription("戻る").performClick()
        waitFor { relations(id).none { it.id == second } }
        waitFor { !secondFile.exists() }
        assertTrue("a photo a row shows is never let go", relations(id).any { it.id == first })
        assertTrue(firstFile.exists())
        assertEquals(listOf(1, 10, 2, 3), rows(id).map { it.id })
        assertEquals(body, memo(id).body)
    }

    @Test
    fun anOutlinerInConflictLetsNoPhotoGoAndWritesNoRow() {
        val id = seed()
        val secondFile = fileOf(id, second)!!
        open(id)
        menu(11, "delete")
        // Within the autosave's pause, another writer (the split pane's call) writes first.
        runBlocking { app.memoRepository.save(memo(id), "旅行", "$body\n- 外から", System.currentTimeMillis()) }
        val latest = memo(id)
        awaitTag("outliner_conflict")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.waitForIdle()
        assertTrue("nothing is let go in a conflict", relations(id).any { it.id == second })
        assertTrue(secondFile.exists())
        assertTrue("the other writer's rows stand, the photo row included", rows(id).any { it.id == 11 && it.photo == second })
        assertEquals(latest.body, memo(id).body)
        assertEquals(latest.updatedAt, memo(id).updatedAt)
    }

    @Test
    fun theRowsComeBackTheSameAfterTheActivityIsRecreated() {
        val id = seed()
        open(id)
        menu(10, "down")
        waitFor { rows(id).map { it.id } == listOf(1, 2, 10, 3, 11) }
        val before = rows(id)
        composeRule.activityRule.scenario.recreate()
        awaitTag("outliner_photo_10")
        assertEquals(before, rows(id))
        assertTrue(top("outliner_node_2") < top("outliner_photo_10"))
        assertTrue(top("outliner_photo_10") < top("outliner_node_3"))
        assertFalse(memo(id).body.contains("photo"))
    }

    @Test
    fun anUndoThatWouldShowMoreThanTwentyPhotosIsRefusedAndSaysSo() {
        // 21 shown — as after a photo came back from a process death on top of 20.
        val id = runBlocking {
            val lines = "- 一\n- 三"
            val memoId = app.database.memoDao().insert(
                MemoEntity(title = "写真", body = lines, createdAt = 1_783_000_000_000L, updatedAt = 1_783_000_000_000L, kind = MemoKind.OUTLINE.storageId),
            )
            app.outlineStore.createFor(memoId, lines)
            val shown = (0 until 21).map { photo(memoId, it) }
            val rows = listOf(OutlineRows.Row(1, "- 一")) + shown.mapIndexed { i, p -> OutlineRows.Row(10 + i, "", photo = p) } + OutlineRows.Row(2, "- 三")
            val version = app.database.memoDao().findById(memoId)!!.updatedAt
            assertTrue(app.outlineStore.saveDocument(memoId, "写真", OutlineRows.documentOf(rows), System.currentTimeMillis(), version) is OutlineStore.Save.Saved)
            memoId
        }
        open(id)
        menu(10, "delete")
        waitFor { rows(id).none { it.id == 10 } }
        composeRule.onNodeWithTag("outliner_node_1").performClick()
        composeRule.onNodeWithTag("toolbar_undo").performScrollTo().performClick()
        val message = "写真は最大20枚までです。追加した写真を減らしてから元に戻してください。"
        waitFor { composeRule.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.onAllNodesWithTag("outliner_photo_10").assertCountEquals(0)
        assertTrue("nothing written", rows(id).none { it.id == 10 })
        assertEquals(20, rows(id).count { it.photo != null })
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

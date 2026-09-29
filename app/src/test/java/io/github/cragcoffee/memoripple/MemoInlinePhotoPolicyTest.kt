package io.github.cragcoffee.memoripple

import io.github.cragcoffee.memoripple.ui.attachments.InlinePhotoGeometry
import io.github.cragcoffee.memoripple.ui.memos.CaretReport
import io.github.cragcoffee.memoripple.ui.memos.NotePageScroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A memo's text and photos as one note (docs/MEMO_CONTENT_BLOCKS.md, human decisions 2026-09-24):
 * photos are blocks of the content, drawn whole at the content width; the page says nothing about
 * them; the column follows the caret; only the memo editor writes in blocks — the outliner, the
 * diary and the reference panes keep the strip.
 */
class MemoInlinePhotoPolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val main = "src/main/java/io/github/cragcoffee/memoripple"

    @Test
    fun aPhotoKeepsItsOwnProportionsAndOnlyTheExtremesAreFramed() {
        assertEquals(16f / 9f, InlinePhotoGeometry.frameRatio(1600, 900), 0.0001f)
        assertEquals(1f, InlinePhotoGeometry.frameRatio(1000, 1000), 0.0001f)
        assertEquals("a portrait photo is drawn whole at 3:4", 3f / 4f, InlinePhotoGeometry.frameRatio(900, 1200), 0.0001f)
        assertEquals("a screenshot is framed at 3:4, not a wall", 3f / 4f, InlinePhotoGeometry.frameRatio(1080, 2400), 0.0001f)
        assertEquals("a panorama is framed at 3:1, not a line", 3f, InlinePhotoGeometry.frameRatio(4000, 1000), 0.0001f)
        assertEquals("an unknown size waits in 4:3", 4f / 3f, InlinePhotoGeometry.frameRatio(0, 0), 0.0001f)
    }

    @Test
    fun theColumnFollowsTheCaretAndMovesAsLittleAsItCan() {
        // A text at 1000 px in the column, a 1000 px viewport, a 24 px margin.
        val onScreen = CaretReport(caretBottomPx = 100f, textHeightPx = 300, fieldHeightPx = 300, caretTopPx = 60f)
        assertNull("a caret on screen moves nothing", NotePageScroll.follow(1000, onScreen, scrollPx = 900, viewportPx = 1000, marginPx = 24))
        val below = CaretReport(caretBottomPx = 700f, textHeightPx = 720, fieldHeightPx = 720, caretTopPx = 660f)
        assertEquals("just enough to show the line and its margin", 724, NotePageScroll.follow(1000, below, scrollPx = 0, viewportPx = 1000, marginPx = 24))
        val above = CaretReport(caretBottomPx = 40f, textHeightPx = 300, fieldHeightPx = 300, caretTopPx = 0f)
        assertEquals("a caret above the viewport comes down into it", 976, NotePageScroll.follow(1000, above, scrollPx = 1500, viewportPx = 1000, marginPx = 24))
        val unmeasured = CaretReport(caretBottomPx = 300f, textHeightPx = 372, fieldHeightPx = 0)
        assertNull("an unmeasured field moves nothing", NotePageScroll.follow(1000, unmeasured, scrollPx = 0, viewportPx = 1000, marginPx = 24))
    }

    @Test
    fun theMemoEditorAndTheDiaryWriteInBlocksAndTheStripStaysForEveryoneElse() {
        val uiFiles = File("$main/ui").let { if (it.isDirectory) it else File("app/$main/ui") }.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val blockUsers = uiFiles.filter { it.readText().contains("MemoBlockColumn(") }.map { it.name }.toSet()
        // The diary joined on 2026-09-25 (Room 27 / Backup 21, docs/MEMO_CONTENT_BLOCKS.md §11).
        assertEquals(setOf("MemoBlockColumn.kt", "MemoEditorScreen.kt", "DiaryEditorScreen.kt"), blockUsers)
        listOf("ui/memos/SplitWorkspace.kt", "ui/memos/MemoLifecycleScreens.kt").forEach {
            assertTrue("$it keeps the strip", text("$main/$it").contains("PhotoAttachmentStrip("))
        }
        val diary = text("$main/ui/diary/DiaryEditorScreen.kt")
        assertTrue("the diary's words stay plain", diary.contains("plain = true,"))
        assertTrue("the diary's strip only for an entry without blocks", diary.contains("if (state.blocks.isEmpty() && !keyboardVisible) {\n                    PhotoAttachmentStrip("))
        assertTrue("未来の自分へ follows the last block", diary.contains("footer = {\n                        FutureDiaryCommentSection("))
        assertTrue("a photo goes where the caret is", diary.contains("onPhotoCaret(if (bodyFocused) activeField.selection.min else null)"))
        val editor = text("$main/ui/memos/MemoEditorScreen.kt")
        assertTrue("the editor falls back to the strip only for a record without blocks", editor.contains("if (state.blocks.isEmpty() && !keyboardVisible) {\n                        PhotoAttachmentStrip("))
        val outliner = text("$main/ui/outline/OutlinerScreen.kt")
        assertFalse("the outliner is given no block store", outliner.contains("contentStore ="))
    }

    @Test
    fun aPhotoIsAPictureAndThePageSaysNothingAboutIt() {
        val photo = text("$main/ui/attachments/InlinePhoto.kt")
        assertTrue("whole, never cropped", photo.contains("ContentScale.Fit") && !photo.contains("ContentScale.Crop"))
        assertTrue("the design system's small corner", photo.contains("MaterialTheme.shapes.small"))
        val column = text("$main/ui/memos/MemoBlockColumn.kt")
        assertFalse("no count on the page", column.contains("枚\""))
        // S26 review, 2026-09-24: the photo's round ⋮ carries フルスクリーン / 写真一覧 / 削除; the
        // picture itself answers no tap and no long press on the writing page; 上へ / 下へ are gone.
        assertTrue("the photo's own menu", column.contains("Icons.Outlined.MoreVert") &&
            column.contains("\"フルスクリーン\"") && column.contains("\"写真一覧\"") && column.contains("\"削除\""))
        assertFalse("no step moves", column.contains("\"上へ\"") || column.contains("\"下へ\""))
        assertTrue("no tap on the picture while writing", column.contains("onClick = null,"))
        assertFalse("no long press", text("$main/ui/attachments/InlinePhoto.kt").contains("onLongClick"))
        assertTrue("写真一覧 is the row of thumbnails", column.contains("PhotoAttachmentStrip("))
        assertTrue("deleting asks first", column.contains("写真を削除しますか？"))
        assertTrue("the same viewer", column.contains("PhotoViewer("))
        val editor = text("$main/ui/memos/MemoEditorScreen.kt")
        assertTrue("写真を並べ替え is in the editor's menu", editor.contains("Text(\"写真を並べ替え\")") && editor.contains("showPhotoReorder = true"))
        assertTrue("the reading page draws the photos where they are", editor.contains("MemoContent.photoBreaks(state.blocks)"))
    }

    @Test
    fun theUiNeverTouchesStorageAndTheBodyIsNeverWrittenBesideTheBlocks() {
        val column = text("$main/ui/memos/MemoBlockColumn.kt")
        listOf("Dao", "Entity", "Room", "withTransaction").forEach { assertFalse("the column touches no $it", column.contains(it)) }
        val store = text("$main/data/MemoContentStore.kt")
        assertTrue("the store writes the body as the projection", store.contains("MemoContent.projection("))
        val repository = text("$main/data/MemoRepository.kt")
        assertTrue("a whole body from elsewhere is laid onto the blocks", repository.contains("content?.saveBody(existing.id, title, body, now)"))
    }

    @Test
    fun theReorderSitsAtTheRowsEndAndItsSheetCarriesRowsLikeTheShortcutBar() {
        // S26 review, 2026-09-24: 並べ替え at the right end; the sheet's rows move as 設定 → ショートカットバー's do.
        val strip = text("$main/ui/attachments/PhotoAttachmentUi.kt")
        assertTrue(strip.contains("Spacer(Modifier.weight(1f))\n                TextButton(\n                    onClick = { showReorderSheet = true },\n                    modifier = Modifier.testTag(\"open_photo_reorder\"),"))
        val sheet = text("$main/ui/attachments/PhotoReorderSheet.kt")
        listOf("CardDragController(", "onCrossed =", "Modifier.animateItem()", ".carriedCard(drag, photo.id, liftPx)", "cardDragHandle(drag, photo.id").forEach {
            assertTrue("the sheet uses $it", sheet.contains(it))
        }
        assertFalse("no step-swap threshold drag", sheet.contains("accumulatedDrag"))
        val toolbar = text("$main/ui/settings/ToolbarOrderScreen.kt")
        assertTrue("the same controller as the shortcut bar", toolbar.contains("CardDragController(") && toolbar.contains("onCrossed ="))
    }
}

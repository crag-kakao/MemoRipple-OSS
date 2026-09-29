package io.github.cragcoffee.memoripple.ui.outline

import io.github.cragcoffee.memoripple.domain.outline.OutlineDocument
import io.github.cragcoffee.memoripple.domain.outline.OutlineEditing
import io.github.cragcoffee.memoripple.domain.outline.OutlineFoldKeys
import io.github.cragcoffee.memoripple.domain.outline.OutlineNode
import io.github.cragcoffee.memoripple.domain.outline.OutlineRows
import io.github.cragcoffee.memoripple.domain.outline.OutlineText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Photo rows (Room 29, docs/OUTLINE_PHOTO_ROWS.md): a photo is a line of the outline of its own —
 * a lasting id, a place in the one ordered stream, a depth — never words in the body. It is put
 * where the writing is, moves, indents, folds and zooms like a line, is taken out and put back by
 * 元に戻す / やり直し, and the body every reader sees stays the lines of words alone.
 */
class OutlinerPhotoRowsTest {
    private val rows = listOf(
        OutlineRows.Row(21, "- 旅行計画"),
        OutlineRows.Row(4, "  - 京都へ行く"),
        OutlineRows.Row(40, "  ", photo = 7L),
        OutlineRows.Row(9, "  - [ ] 寺院を回る"),
        OutlineRows.Row(30, "- 帰る"),
    )
    private val stored = OutlineRows.documentOf(rows)
    private val body = OutlineRows.projection(rows)
    private var written: OutlineDocument? = null
    private val write: (OutlineDocument) -> Unit = { written = it }
    private fun session() = OutlinerSession().apply { acceptBody(body, stored) }
    private fun OutlineDocument.ids() = entries.map { it.id }
    private fun OutlineDocument.photos() = entries.mapNotNull { (it as? OutlineNode)?.photoAttachmentId }
    private fun OutlineDocument.node(id: Int) = entries.first { it.id == id } as OutlineNode
    private fun OutlineDocument.lines() = entries.map { (it as? OutlineNode)?.photoAttachmentId?.let { p -> "photo:$p@${(it as OutlineNode).depth}" } ?: it.toLine() }

    // --- the body and the rows -------------------------------------------------------------

    @Test
    fun theBodyIsTheLinesOfWordsAloneAndAPhotoRowKeepsItsIdAndDepth() {
        assertEquals("- 旅行計画\n  - 京都へ行く\n  - [ ] 寺院を回る\n- 帰る", body)
        assertEquals(body, OutlineText.serialize(stored))
        val photo = stored.node(40)
        assertTrue(photo.isPhoto)
        assertEquals(1, photo.depth)
        assertEquals("", photo.text)
        assertEquals("", photo.marker)
        assertEquals(rows, OutlineRows.rowsOf(stored))
        assertEquals(41, stored.nextId)
    }

    @Test
    fun anOutlineWrittenBeforePhotoRowsGetsItsPhotosAtTheTopWithItsLinesUntouched() {
        val lines = listOf(OutlineRows.Row(3, "- 一"), OutlineRows.Row(8, "  - 二"))
        val moved = OutlineRows.withPhotosOnTop(lines, listOf(12L, 5L))
        assertEquals(listOf(9, 10, 3, 8), moved.map { it.id })
        assertEquals(listOf(12L, 5L, null, null), moved.map { it.photo })
        assertEquals("the lines and their ids stay", lines, moved.drop(2))
        assertEquals(OutlineRows.projection(lines), OutlineRows.projection(moved))
        assertEquals("depth 0", listOf("", ""), moved.take(2).map { it.line })
        assertEquals("a photo already shown is not added again", moved, OutlineRows.withPhotosOnTop(moved, listOf(12L, 5L)))
        assertEquals("no photos, nothing changes", lines, OutlineRows.withPhotosOnTop(lines, emptyList()))
    }

    @Test
    fun aPlainWriterKeepsEveryPhotoRowAfterTheLineItFollowed() {
        // The AI's append / a task ticked on the reading page: a whole body laid onto the rows.
        val appended = OutlineRows.reconcile(rows, "$body\n- 追記", nextId = 41)
        assertEquals(listOf(21, 4, 40, 9, 30, 41), appended.map { it.id })
        assertEquals(7L, appended.first { it.id == 40 }.photo)
        val ticked = OutlineRows.reconcile(rows, body.replace("[ ]", "[x]"), nextId = 41)
        assertEquals(listOf(21, 4, 40, 9, 30), ticked.map { it.id })
        // The line a photo followed is gone: the photo stays after the nearest line before it.
        val cut = OutlineRows.reconcile(rows, "- 旅行計画\n  - [ ] 寺院を回る\n- 帰る", nextId = 41)
        assertEquals(listOf(21, 40, 9, 30), cut.map { it.id })
        // Nothing before it survives: it goes to the top.
        val top = listOf(OutlineRows.Row(1, "- 一"), OutlineRows.Row(2, "", photo = 3L), OutlineRows.Row(3, "- 二"))
        assertEquals(listOf(2, 3), OutlineRows.reconcile(top, "- 二", nextId = 4).map { it.id })
    }

    // --- adding photos where the writing is ------------------------------------------------

    @Test
    fun photosGoAfterTheLineBeingWrittenAndANewLineFollowsThem() {
        val placed = OutlineEditing.insertPhotos(stored, afterId = 30, attachmentIds = listOf(8L), caret = 4)
        assertEquals(listOf("- 旅行計画", "  - 京都へ行く", "photo:7@1", "  - [ ] 寺院を回る", "- 帰る", "photo:8@0", "- "), placed.document.lines())
        assertEquals("writing goes on below the photo", placed.document.entries.last().id, placed.focusId)
        assertEquals(0, placed.caret)
        assertEquals("the body only gains the empty line", "$body\n- ", OutlineText.serialize(placed.document))
    }

    @Test
    fun aCaretInsideTheWordsSplitsTheLineAndThePhotoGoesBetween() {
        val doc = OutlineText.parse("- 前半後半")
        val placed = OutlineEditing.insertPhotos(doc, afterId = doc.entries[0].id, attachmentIds = listOf(5L, 6L), caret = 2)
        assertEquals(listOf("- 前半", "photo:5@0", "photo:6@0", "- 後半"), placed.document.lines())
        assertEquals(placed.document.entries.last().id, placed.focusId)
        assertEquals("the photos keep the order picked", listOf(5L, 6L), placed.document.photos())
        assertEquals(doc.entries[0].id, placed.document.entries[0].id)
    }

    @Test
    fun aCaretAtTheStartPutsThePhotoBeforeTheLineAtItsDepth() {
        val placed = OutlineEditing.insertPhotos(stored, afterId = 9, attachmentIds = listOf(8L), caret = 0)
        assertEquals(listOf("- 旅行計画", "  - 京都へ行く", "photo:7@1", "photo:8@1", "  - [ ] 寺院を回る", "- 帰る"), placed.document.lines())
        assertEquals(9, placed.focusId)
    }

    @Test
    fun aLineWithChildrenTakesThePhotoAsItsFirstChild() {
        val placed = OutlineEditing.insertPhotos(stored, afterId = 21, attachmentIds = listOf(8L), caret = null)
        assertEquals("photo:8@1", placed.document.lines()[1])
        assertEquals("  - ", placed.document.lines()[2])
    }

    @Test
    fun textPhotoTextPhotoTextKeepsItsOrderAndNeverGathersAtTheTopOrEnd() {
        var doc = OutlineText.parse("- 一\n- 二\n- 三")
        val (one, two) = doc.entries.map { it.id }
        doc = OutlineEditing.insertPhotos(doc, one, listOf(1L), caret = null).document
        doc = OutlineEditing.insertPhotos(doc, two, listOf(2L), caret = null).document
        assertEquals(listOf("- 一", "photo:1@0", "- ", "- 二", "photo:2@0", "- ", "- 三"), doc.lines())
    }

    @Test
    fun aPhotoAlreadyShownIsNotShownTwiceAndAnEmptyOutlineGetsAPhotoAndALine() {
        val again = OutlineEditing.insertPhotos(stored, afterId = 30, attachmentIds = listOf(7L, 7L), caret = null)
        assertSame(stored, again.document)
        val empty = OutlineEditing.insertPhotos(OutlineDocument(emptyList(), 1), afterId = null, attachmentIds = listOf(3L), caret = null)
        assertEquals(listOf("photo:3@0", "- "), empty.document.lines())
        assertEquals(listOf(1, 2), empty.document.ids())
    }

    // --- a photo row is not words ------------------------------------------------------------

    @Test
    fun aPhotoRowTakesNoWordsNoSplitAndIsNeverJoinedAcross() {
        assertSame(stored, OutlineEditing.updateText(stored, 40, "文字"))
        assertEquals(stored, OutlineEditing.split(stored, 40, 0).document)
        assertEquals(stored, OutlineEditing.replaceText(stored, 40, "文字", 1).document)
        // Backspace at the start of the line under a photo does not join it into the photo or past it.
        assertEquals(stored, OutlineEditing.deleteBackward(stored, 9).document)
        assertEquals(stored, OutlineEditing.deleteBackward(stored, 40).document)
    }

    @Test
    fun aFoldIsNeverKeyedOnAPhotoAndAPhotoIsNeverAParent() {
        assertNull(OutlineFoldKeys.keyFor(stored, 40))
        val doc = OutlineRows.documentOf(
            listOf(OutlineRows.Row(1, "- 一"), OutlineRows.Row(2, "", photo = 1L), OutlineRows.Row(3, "  - 二")),
        )
        assertFalse(2 in OutlineEditing.nodesWithChildren(doc))
        assertEquals(emptySet<Int>(), OutlineEditing.toggleFold(doc, emptySet(), 2))
        assertEquals("a photo is never a breadcrumb: the line above it is", listOf(1), OutlineEditing.ancestors(doc, 3).map { it.id })
    }

    // --- moving, indenting, folding and zooming like a line ------------------------------------

    @Test
    fun upDownIndentAndOutdentMoveThePhotoRowWithItsIdKept() {
        val s = session()
        s.edit(write) { OutlineEditing.moveUp(it, 40) }
        assertEquals(listOf(21, 40, 4, 9, 30), s.document.ids())
        s.edit(write) { OutlineEditing.moveDown(it, 40) }
        s.edit(write) { OutlineEditing.moveDown(it, 40) }
        assertEquals(listOf(21, 4, 9, 40, 30), s.document.ids())
        s.edit(write) { OutlineEditing.indent(it, 40) }
        assertEquals(2, s.document.node(40).depth)
        s.edit(write) { OutlineEditing.outdent(it, 40) }
        s.edit(write) { OutlineEditing.outdent(it, 40) }
        assertEquals(0, s.document.node(40).depth)
        assertEquals("the body never moved", body, OutlineText.serialize(s.document))
        assertEquals(s.document, written)
        assertEquals(7L, s.document.node(40).photoAttachmentId)
    }

    @Test
    fun aMovedLineTakesTheNestedPhotoAlong() {
        val s = session()
        s.edit(write) { OutlineEditing.moveDown(it, 21) }
        assertEquals(listOf(30, 21, 4, 40, 9), s.document.ids())
    }

    @Test
    fun foldingHidesThePhotoAndUnfoldingShowsItAgain() {
        val s = session()
        s.toggleFold(21)
        assertFalse(40 in OutlineEditing.visible(s.document, s.collapsed).map { it.id })
        assertEquals("the photo is counted in what the fold hides", 3, OutlineEditing.hiddenCount(s.document, 21))
        s.toggleFold(21)
        assertTrue(40 in OutlineEditing.visible(s.document, s.collapsed).map { it.id })
    }

    @Test
    fun aZoomShowsThePhotoInsideItAndNoneOutsideAndNeverStandsOnAPhoto() {
        val s = session()
        s.zoomInto(21)
        assertTrue(40 in OutlineEditing.visible(s.document, s.collapsed, s.zoomId).map { it.id })
        s.zoomInto(30)
        assertFalse(40 in OutlineEditing.visible(s.document, s.collapsed, s.zoomId).map { it.id })
        s.zoomInto(40)
        assertEquals("a zoom never stands on a photo", 30, s.zoomId)
        s.zoomInto(4)
        s.zoomOut()
        assertEquals(21, s.zoomId)
    }

    // --- taking a photo out, and 元に戻す / やり直し ---------------------------------------

    @Test
    fun deletingAPhotoRowTakesOnlyThatRow() {
        val s = session()
        s.edit(write) { OutlineEditing.removePhoto(it, 40) }
        assertEquals(listOf(21, 4, 9, 30), s.document.ids())
        assertEquals(body, OutlineText.serialize(s.document))
        assertSame("a line of words is not a photo to remove", s.document, OutlineEditing.removePhoto(s.document, 4))
    }

    @Test
    fun undoAndRedoTakeBackAndGiveAgainEveryPhotoChangeWithTheSameIds() {
        val s = session()
        s.edit(write) { OutlineEditing.removePhoto(it, 40) }
        s.undo(write)
        assertEquals(stored.entries, s.document.entries)
        s.redo(write)
        assertEquals(listOf(21, 4, 9, 30), s.document.ids())
        s.undo(write)

        s.edit(write) { OutlineEditing.insertPhotos(it, 30, listOf(8L), caret = null).document }
        val inserted = s.document
        s.undo(write)
        assertEquals(stored.entries, s.document.entries)
        s.redo(write)
        assertEquals(inserted.entries, s.document.entries)

        s.edit(write) { OutlineEditing.moveDown(it, 40) }
        s.edit(write) { OutlineEditing.indent(it, 40) }
        assertEquals(2, s.document.node(40).depth)
        s.undo(write)
        s.undo(write)
        assertEquals(inserted.entries, s.document.entries)
        assertEquals(s.document, written)
    }

    @Test
    fun anIdTakenByAPhotoIsNeverHandedOutAgain() {
        val s = session()
        s.edit(write) { OutlineEditing.insertPhotos(it, 30, listOf(8L), caret = null).document }
        val taken = s.document.ids().toSet()
        s.undo(write)
        s.edit(write) { OutlineEditing.insertAfter(it, 30).document }
        val fresh = s.document.ids().toSet() - stored.ids().toSet()
        assertTrue("no id reused after an undo", fresh.none { it in taken })
        assertEquals(s.document.ids().size, s.document.ids().toSet().size)
    }

    // --- the stored outline read again -----------------------------------------------------

    @Test
    fun theSameWordsWithOtherPhotoRowsAreADifferentOutlineAndTheEchoIsNot() {
        val s = session()
        s.edit(write) { OutlineEditing.moveUp(it, 40) }
        s.toggleFold(21)
        // The session's own echo changes nothing (the fold stays).
        s.acceptBody(OutlineText.serialize(s.document), s.document)
        assertEquals(setOf(21), s.collapsed)
        // The stored outline read again (after a conflict): same words, the photo elsewhere.
        s.acceptBody(body, stored)
        assertEquals(stored.entries, s.document.entries)
        assertFalse(s.canUndo)
    }

    @Test
    fun anEditorInConflictMovesNoPhoto() {
        val s = session()
        s.writable = false
        s.edit(write) { OutlineEditing.removePhoto(it, 40) }
        s.edit(write) { OutlineEditing.insertPhotos(it, 30, listOf(8L), caret = null).document }
        assertEquals(stored.entries, s.document.entries)
        assertNull(written)
    }
}

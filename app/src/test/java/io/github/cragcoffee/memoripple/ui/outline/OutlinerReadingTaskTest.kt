package io.github.cragcoffee.memoripple.ui.outline

import io.github.cragcoffee.memoripple.domain.OutlineSymbolSelection
import io.github.cragcoffee.memoripple.domain.WorkOutlineEditing
import io.github.cragcoffee.memoripple.domain.outline.OutlineDocument
import io.github.cragcoffee.memoripple.domain.outline.OutlineEditing
import io.github.cragcoffee.memoripple.domain.outline.OutlineRows
import io.github.cragcoffee.memoripple.domain.outline.OutlineText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reading page's task tick as an outliner edit (2026-09-25): the reading page's line is the
 * body's line (photo rows are not lines); the tick writes exactly what the reading page always
 * wrote ([WorkOutlineEditing.toggleTaskAt] on the whole body), keeps every row id and photo row,
 * and — through the session — leaves the folds, the zoom and the undo / redo history as they were.
 */
class OutlinerReadingTaskTest {
    private val rows = listOf(
        OutlineRows.Row(21, "- 旅行"),
        OutlineRows.Row(4, "  - [ ] 宿を取る"),
        OutlineRows.Row(40, "  ", photo = 7L),
        OutlineRows.Row(9, "  - 京都"),
        OutlineRows.Row(30, "- 帰る"),
        OutlineRows.Row(12, "  - [x] 切符"),
        OutlineRows.Row(13, "* 普通の行"),
    )
    private val stored = OutlineRows.documentOf(rows)
    private val body = OutlineText.serialize(stored)
    private val symbols = OutlineSymbolSelection.Default
    private fun tick(doc: OutlineDocument, line: Int): OutlineDocument {
        val id = OutlineEditing.entryIdAtBodyLine(doc, line) ?: return doc
        return OutlineEditing.rewriteLine(doc, id) { raw -> WorkOutlineEditing.toggleTaskAt(raw, 0, symbols) }
    }
    private var written: OutlineDocument? = null
    private val write: (OutlineDocument) -> Unit = { written = it }

    @Test
    fun theBodysLinesAreTheEntriesWithoutThePhotoRows() {
        assertEquals(listOf(21, 4, 9, 30, 12, 13), (0..5).map { OutlineEditing.entryIdAtBodyLine(stored, it) })
        assertNull(OutlineEditing.entryIdAtBodyLine(stored, 6))
    }

    @Test
    fun theTickWritesExactlyWhatTheReadingPageAlwaysWroteAndKeepsEveryRow() {
        (0 until body.split("\n").size).forEach { line ->
            val ticked = tick(stored, line)
            assertEquals("line $line", WorkOutlineEditing.toggleTaskAt(body, line, symbols), OutlineText.serialize(ticked))
            assertEquals("line $line: every id and photo row in place", OutlineRows.rowsOf(stored).map { it.id to it.photo }, OutlineRows.rowsOf(ticked).map { it.id to it.photo })
        }
    }

    @Test
    fun throughTheSessionTheFoldTheZoomAndTheHistoryStay() {
        val s = OutlinerSession().apply { acceptBody(body, stored) }
        s.edit(write) { OutlineEditing.updateText(it, 9, "京都へ") }
        s.edit(write) { OutlineEditing.updateText(it, 9, "京都へ行く") }
        s.undo(write)
        assertTrue("a redo is waiting", s.canRedo)
        s.toggleFold(21)
        s.zoomInto(30)
        val zoomBefore = s.zoomId
        val foldsBefore = s.collapsed
        // The reading page's tick, as the route sends it.
        s.edit(write) { tick(it, 4) }
        assertEquals("  - [ ] 切符", s.document.entries.first { it.id == 12 }.toLine())
        // The screen then hears its own echo back; nothing is reset.
        s.acceptBody(OutlineText.serialize(s.document), s.document)
        assertEquals(foldsBefore, s.collapsed)
        assertEquals(zoomBefore, s.zoomId)
        assertTrue(s.canUndo)
        // The tick is an edit: undone, redone — and the history under it is still there.
        s.undo(write)
        assertEquals("  - [x] 切符", s.document.entries.first { it.id == 12 }.toLine())
        assertTrue(s.canRedo)
        s.redo(write)
        assertEquals("  - [ ] 切符", s.document.entries.first { it.id == 12 }.toLine())
        s.undo(write)
        s.undo(write)
        assertEquals("the typing before it is still undoable", "  - 京都", s.document.entries.first { it.id == 9 }.toLine())
    }

    @Test
    fun theOldWayAWholeNewBodyResetEverything() {
        // What the reading page used to do: a whole new body laid on by lines, then read back by
        // the session as a changed body — it cleared the folds, the zoom and the history.
        val s = OutlinerSession().apply { acceptBody(body, stored) }
        s.edit(write) { OutlineEditing.updateText(it, 9, "京都へ") }
        s.toggleFold(21)
        s.zoomInto(30)
        val newBody = WorkOutlineEditing.toggleTaskAt(OutlineText.serialize(s.document), 4, symbols)
        val laid = OutlineRows.documentOf(OutlineRows.reconcile(OutlineRows.rowsOf(s.document), newBody, s.document.nextId))
        s.acceptBody(newBody, laid)
        assertTrue("the old path reset the folds", s.collapsed.isEmpty())
        assertNull("the old path reset the zoom", s.zoomId)
        assertTrue("the old path reset the history", !s.canUndo)
    }
}

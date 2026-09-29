package io.github.cragcoffee.memoripple.ui.outline

import io.github.cragcoffee.memoripple.domain.outline.OutlineDocument
import io.github.cragcoffee.memoripple.domain.outline.OutlineEditing
import io.github.cragcoffee.memoripple.domain.outline.OutlineNode
import io.github.cragcoffee.memoripple.domain.outline.OutlineRows
import io.github.cragcoffee.memoripple.domain.OutlineSymbolRole
import io.github.cragcoffee.memoripple.domain.outline.OutlineText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outliner on stable rows (Room 28, docs/OUTLINE_STABLE_ROWS.md): the session takes the stored
 * ids and every operation keeps the ids of the lines it does not replace — a move, an indent, an
 * edit elsewhere, a fold; a split makes one new line with the next id; a join keeps the line above;
 * an undo gives back the same ids; and an id once handed out is never handed out again.
 */
class OutlinerStableIdsTest {
    /** Stored rows with ids that are not 1..n — as after a life of edits. */
    private val stored = OutlineRows.documentOf(
        listOf(
            OutlineRows.Row(21, "- 旅行計画"),
            OutlineRows.Row(4, "  - 京都へ行く"),
            OutlineRows.Row(9, "  - [ ] 寺院を回る"),
            OutlineRows.Row(30, "- 帰る"),
        ),
    )
    private val body = OutlineText.serialize(stored)
    private var written: OutlineDocument? = null
    private val write: (OutlineDocument) -> Unit = { written = it }
    private fun session() = OutlinerSession().apply { acceptBody(body, stored) }
    private fun OutlinerSession.ids() = document.entries.map { it.id }
    private fun OutlinerSession.textOf(id: Int) = (document.entries.first { it.id == id } as OutlineNode).text

    @Test
    fun theSessionTakesTheStoredIds() {
        val s = session()
        assertEquals(listOf(21, 4, 9, 30), s.ids())
        assertEquals(31, s.document.nextId)
        val stale = OutlinerSession().apply { acceptBody(body, stored.copy(entries = stored.entries.dropLast(1))) }
        assertEquals("rows that do not say the body are not taken", listOf(1, 2, 3, 4), stale.ids())
    }

    @Test
    fun reorderIndentAndOutdentKeepEveryId() {
        val s = session()
        s.edit(write) { OutlineEditing.moveDown(it, 4) }
        s.edit(write) { OutlineEditing.moveUp(it, 30) }
        s.edit(write) { OutlineEditing.indent(it, 30) }
        s.edit(write) { OutlineEditing.outdent(it, 30) }
        assertEquals(setOf(21, 4, 9, 30), s.ids().toSet())
        assertEquals(s.document, written)
    }

    @Test
    fun anEditKeepsEveryIdAndChangesOnlyItsLine() {
        val s = session()
        s.edit(write) { OutlineEditing.updateText(it, 4, "奈良へ行く") }
        assertEquals(listOf(21, 4, 9, 30), s.ids())
        assertEquals("奈良へ行く", s.textOf(4))
        assertEquals("寺院を回る", s.textOf(9))
    }

    @Test
    fun aSplitMakesOneNewLineWithTheNextIdAndAJoinKeepsTheLineAbove() {
        val s = session()
        s.edit(write) { OutlineEditing.split(it, 4, caret = 2).document }
        assertEquals("the words before the caret keep the line", listOf(21, 4, 31, 9, 30), s.ids())
        assertEquals("京都", s.textOf(4))
        s.edit(write) { OutlineEditing.deleteBackward(it, 31).document }
        assertEquals("the join keeps the line above", listOf(21, 4, 9, 30), s.ids())
        assertEquals("京都へ行く", s.textOf(4))
    }

    @Test
    fun aDeletedLineTakesOnlyItselfAndItsChildrenFollowTheirNewParent() {
        val s = session()
        s.edit(write) { OutlineEditing.deleteBackward(it, 4).document }
        assertEquals(listOf(21, 9, 30), s.ids())
        assertEquals("旅行計画京都へ行く", s.textOf(21))
    }

    @Test
    fun taskAndFoldKeepTheirLine() {
        val s = session()
        s.edit(write) { OutlineEditing.toggleTask(it, 9) }
        assertEquals(OutlineSymbolRole.TASK_DONE, (s.document.entries.first { it.id == 9 } as OutlineNode).role)
        s.toggleFold(21)
        assertTrue(21 in s.collapsed)
        s.edit(write) { OutlineEditing.updateText(it, 30, "帰宅") }
        assertTrue("a fold stands on its line through edits elsewhere", 21 in s.collapsed)
    }

    @Test
    fun undoGivesBackTheSameIdsAndRedoGoesForwardAgain() {
        val s = session()
        s.edit(write) { OutlineEditing.split(it, 30, caret = 1).document }
        val afterSplit = s.ids()
        s.undo(write)
        assertEquals(listOf(21, 4, 9, 30), s.ids())
        s.redo(write)
        assertEquals(afterSplit, s.ids())
    }

    @Test
    fun anIdTakenAwayByAnUndoIsNotHandedOutAgain() {
        val s = session()
        s.edit(write) { OutlineEditing.split(it, 30, caret = 1).document }
        val created = s.ids().first { it !in setOf(21, 4, 9, 30) }
        s.undo(write)
        s.edit(write) { OutlineEditing.split(it, 4, caret = 1).document }
        val second = s.ids().first { it !in setOf(21, 4, 9, 30) }
        assertFalse("a new line never reuses $created", second == created)
        assertEquals(s.ids().size, s.ids().toSet().size)
    }

    @Test
    fun whatTheSessionWritesIsTheBodyByteForByte() {
        val s = session()
        s.edit(write) { OutlineEditing.updateText(it, 9, "寺院を回る（東山）") }
        val rows = OutlineRows.rowsOf(written!!)
        assertEquals(OutlineText.serialize(s.document), OutlineRows.projection(rows))
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
    }
}

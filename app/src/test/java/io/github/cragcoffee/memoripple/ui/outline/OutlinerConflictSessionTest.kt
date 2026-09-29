package io.github.cragcoffee.memoripple.ui.outline

import io.github.cragcoffee.memoripple.domain.outline.OutlineDocument
import io.github.cragcoffee.memoripple.domain.outline.OutlineEditing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An outline in conflict with a newer one (docs/OUTLINE_STABLE_ROWS.md §9): the session makes no
 * change at all — no edit, no undo, no redo — so nothing reaches the save; a fold or a zoom is a
 * way of looking and never writes, conflict or not.
 */
class OutlinerConflictSessionTest {
    private val written = mutableListOf<OutlineDocument>()
    private val write: (OutlineDocument) -> Unit = { written += it }
    private fun session() = OutlinerSession().apply { acceptBody("- 一\n  - 二\n- 三") }

    @Test
    fun aSessionThatMayNotWriteMakesNoEditUndoOrRedo() {
        val s = session()
        s.edit(write) { OutlineEditing.updateText(it, 1, "一（編集）") }
        s.undo(write)
        assertEquals(2, written.size)
        val before = s.document
        s.writable = false
        s.edit(write) { OutlineEditing.updateText(it, 3, "三（古い画面から）") }
        s.redo(write)
        s.undo(write)
        assertEquals("nothing reached the save", 2, written.size)
        assertEquals("nothing changed on screen either", before, s.document)
    }

    @Test
    fun foldingAndZoomingNeverWrite() {
        val s = session()
        s.toggleFold(1)
        s.zoomInto(1)
        s.zoomOut()
        s.writable = false
        s.toggleFold(1)
        s.zoomInto(1)
        assertTrue("a way of looking writes nothing", written.isEmpty())
    }
}

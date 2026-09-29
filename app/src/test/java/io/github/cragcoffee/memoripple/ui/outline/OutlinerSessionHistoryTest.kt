package io.github.cragcoffee.memoripple.ui.outline

import io.github.cragcoffee.memoripple.domain.outline.OutlineEditing
import io.github.cragcoffee.memoripple.domain.outline.OutlineText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 元に戻す・やり直し on the outline: whole documents, pushed by every edit, undone in order. */
class OutlinerSessionHistoryTest {
    private fun session(body: String) = OutlinerSession().apply { acceptBody(body) }

    @Test
    fun editsCanBeUndoneAndRedoneInOrderAndAnUndoneChangeReachesTheBody() {
        val session = session("- 一")
        val written = mutableListOf<String>()
        val write: (io.github.cragcoffee.memoripple.domain.outline.OutlineDocument) -> Unit = { written += OutlineText.serialize(it) }
        assertFalse(session.canUndo)
        session.edit(write) { OutlineEditing.updateText(it, 1, "一二") }
        session.edit(write) { OutlineEditing.updateText(it, 1, "一二三") }
        assertTrue(session.canUndo)
        assertFalse(session.canRedo)

        session.undo(write)
        assertEquals("- 一二", OutlineText.serialize(session.document))
        assertTrue(session.canRedo)
        session.undo(write)
        assertEquals("- 一", OutlineText.serialize(session.document))
        assertFalse(session.canUndo)
        session.redo(write)
        assertEquals("- 一二", OutlineText.serialize(session.document))
        assertEquals(listOf("- 一二", "- 一二三", "- 一二", "- 一", "- 一二"), written)
    }

    @Test
    fun aNewEditForgetsWhatCouldHaveBeenRedoneAndAnOutsideBodyForgetsEverything() {
        val session = session("- 一")
        session.edit({}) { OutlineEditing.updateText(it, 1, "一二") }
        session.undo({})
        assertTrue(session.canRedo)
        session.edit({}) { OutlineEditing.updateText(it, 1, "一三") }
        assertFalse(session.canRedo)
        session.acceptBody("- 別の本文")
        assertFalse(session.canUndo)
        assertFalse(session.canRedo)
    }
}

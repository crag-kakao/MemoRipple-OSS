package io.github.cragcoffee.memoripple.ui.outline

import io.github.cragcoffee.memoripple.domain.outline.OutlineDocument
import io.github.cragcoffee.memoripple.domain.outline.OutlineEditing
import io.github.cragcoffee.memoripple.domain.outline.OutlineNode
import io.github.cragcoffee.memoripple.domain.outline.OutlineText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zoom's anchor line removed from under it (human decision 2026-09-25, before stable rows):
 * a Backspace at the start of the zoomed line joins it to the line above — outside the zoom — and
 * an undo can take away the line the zoom stands on. The outliner must not crash, must not keep a
 * zoom on a line that is gone, and falls back to the nearest line the anchor was nested under that
 * still exists (the parent a 一つ上へ would reach), or to the root — never to some other line.
 */
class OutlinerZoomAnchorTest {
    private val none: (OutlineDocument) -> Unit = {}
    private fun session(body: String) = OutlinerSession().apply { acceptBody(body) }
    private fun OutlinerSession.id(text: String) = document.entries.first { (it as? OutlineNode)?.text == text }.id
    private fun OutlinerSession.visibleText() = OutlineEditing.visible(document, collapsed, zoomId).map { OutlineText.serialize(OutlineDocument(listOf(it), 0)).trim() }

    @Test
    fun joiningTheZoomedLineIntoItsParentZoomsOutToTheParent() {
        val s = session("- 親\n  - 子\n    - 孫")
        val child = s.id("子")
        s.zoomInto(child)
        s.edit(none) { OutlineEditing.deleteBackward(it, child).document }
        assertEquals("the nearest surviving line it was nested under", s.id("親子"), s.zoomId)
        assertEquals(listOf("- 親子", "- 孫"), s.visibleText())
    }

    @Test
    fun joiningATopLevelZoomedLineGoesBackToTheRoot() {
        val s = session("- 一\n- 二\n  - 下")
        val two = s.id("二")
        s.zoomInto(two)
        s.edit(none) { OutlineEditing.deleteBackward(it, two).document }
        assertNull("no line it was nested under: the root", s.zoomId)
        assertTrue(s.visibleText().isNotEmpty())
    }

    @Test
    fun undoingTheLineTheZoomStandsOnZoomsOutToItsParent() {
        val s = session("- 親\n  - 子")
        val child = s.id("子")
        var created = 0
        s.edit(none) { OutlineEditing.insertAfter(it, child).also { insertion -> created = insertion.newId }.document }
        s.zoomInto(created)
        s.undo(none)
        assertEquals(s.id("親"), s.zoomId)
        s.visibleText()
    }

    @Test
    fun aZoomThatStillStandsIsLeftAlone() {
        val s = session("- 親\n  - 子\n  - 次")
        val child = s.id("子")
        s.zoomInto(child)
        s.edit(none) { OutlineEditing.updateText(it, child, "子（直した）") }
        assertEquals(child, s.zoomId)
        val next = s.id("次")
        s.edit(none) { OutlineEditing.deleteBackward(it, next).document }
        assertEquals("a line under the zoom joining upward keeps the zoom", child, s.zoomId)
    }

    @Test
    fun foldsOfLinesThatAreGoneAreForgotten() {
        val s = session("- 親\n  - 子\n    - 孫\n- 次")
        val child = s.id("子")
        s.toggleFold(child)
        assertTrue(child in s.collapsed)
        s.edit(none) { OutlineEditing.deleteBackward(it, child).document }
        assertTrue("no fold names a line that is gone", s.collapsed.all { id -> s.document.entries.any { it.id == id } })
    }
}

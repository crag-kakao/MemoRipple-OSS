package io.github.cragcoffee.memoripple.domain.outline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** What the S26 trial asked of a line: how many lines a fold hides, a task ticked from its bullet, a drag that moves it. */
class OutlineInteractionsTest {
    @Test
    fun aFoldedLineKnowsHowManyLinesItHides() {
        val document = OutlineText.parse("- 一\n  - 二\n    - 三\n- 四")
        assertEquals(2, OutlineEditing.hiddenCount(document, 1))
        assertEquals(1, OutlineEditing.hiddenCount(document, 2))
        assertEquals(0, OutlineEditing.hiddenCount(document, 4))
    }

    @Test
    fun aTaskIsTickedAndUntickedInItsOwnSymbolSet() {
        val standard = OutlineText.parse("- [ ] 買い物")
        val ticked = OutlineEditing.toggleTask(standard, 1)
        assertEquals("- [x] 買い物", OutlineText.serialize(ticked))
        assertEquals("- [ ] 買い物", OutlineText.serialize(OutlineEditing.toggleTask(ticked, 1)))
        val japanese = OutlineText.parse("☐ 掃除")
        assertEquals("☑ 掃除", OutlineText.serialize(OutlineEditing.toggleTask(japanese, 1)))
    }

    @Test
    fun aLineThatIsNotATaskIsLeftAlone() {
        val document = OutlineText.parse("- 買い物")
        assertSame(document, OutlineEditing.toggleTask(document, 1))
    }

    @Test
    fun aLineIsAppendedAtTheEndOfTheDocumentOrOfTheZoomedBranch() {
        val document = OutlineText.parse("- 一\n  - 二\n- 三\n  - 四")
        val atEnd = OutlineEditing.appendLine(document, zoomId = null)
        assertEquals("- 一\n  - 二\n- 三\n  - 四\n- ", OutlineText.serialize(atEnd.document))
        assertEquals(5, atEnd.newId)
        // Zoomed into 一: the new line closes 一's branch as its last child, before 三.
        val inZoom = OutlineEditing.appendLine(document, zoomId = 1)
        assertEquals("- 一\n  - 二\n  - \n- 三\n  - 四", OutlineText.serialize(inZoom.document))
    }
    @Test
    fun aBlockIsCarriedAnywhereAndTakesTheDepthItIsGiven() {
        val document = OutlineText.parse("- 一\n  - 二\n- 三\n  - 四\n- 五")
        // 三 (with 四) to the very top.
        assertEquals("- 三\n  - 四\n- 一\n  - 二\n- 五", OutlineText.serialize(OutlineEditing.relocate(document, 3, afterId = null, depth = 0)))
        // 三 (with 四) right after 一 as its first child: 四 steps in with it.
        assertEquals("- 一\n  - 三\n    - 四\n  - 二\n- 五", OutlineText.serialize(OutlineEditing.relocate(document, 3, afterId = 1, depth = 1)))
        // 五 after 二 as 二's sibling (depth 1): it lands after 二, before 三.
        assertEquals("- 一\n  - 二\n  - 五\n- 三\n  - 四", OutlineText.serialize(OutlineEditing.relocate(document, 5, afterId = 2, depth = 1)))
        // 五 after 一 at depth 0 goes after 一's whole block, as its sibling.
        assertEquals("- 一\n  - 二\n- 五\n- 三\n  - 四", OutlineText.serialize(OutlineEditing.relocate(document, 5, afterId = 1, depth = 0)))
        // A depth deeper than one step under the line above is pulled back to that step: 二's child.
        assertEquals("- 一\n  - 二\n    - 五\n- 三\n  - 四", OutlineText.serialize(OutlineEditing.relocate(document, 5, afterId = 2, depth = 4)))
    }

    @Test
    fun aBlockNeverLandsInsideItself() {
        val document = OutlineText.parse("- 一\n  - 二\n- 三")
        assertSame(document, OutlineEditing.relocate(document, 1, afterId = 2, depth = 2))
        assertEquals(setOf(1, 2), OutlineEditing.blockIds(document, 1))
    }
    @Test
    fun theLineADragLandsUnderDecidesHowDeepItMayGo() {
        val document = OutlineText.parse("- 一\n  - 二\n- 三\n- 四")
        val none = emptySet<Int>()
        // Under 一, whose child shows: only as its first child.
        assertEquals(1, OutlineEditing.landingDepth(document, 4, afterId = 1, wanted = 0, none, null))
        // Under 二, a leaf: the margin, its sibling, or its child — as asked, within that.
        assertEquals(0, OutlineEditing.landingDepth(document, 4, afterId = 2, wanted = 0, none, null))
        assertEquals(2, OutlineEditing.landingDepth(document, 4, afterId = 2, wanted = 7, none, null))
        // Under 一 while 一 is folded: its children are out of sight, so a sibling is allowed.
        assertEquals(0, OutlineEditing.landingDepth(document, 4, afterId = 1, wanted = 0, setOf(1), null))
        // The top of the document is the margin; the top of a zoom is one step under its line.
        assertEquals(0, OutlineEditing.landingDepth(document, 4, afterId = null, wanted = 3, none, null))
        assertEquals(1, OutlineEditing.landingDepth(document, 2, afterId = null, wanted = 0, none, zoomId = 1))
    }
    @Test
    fun aLineIsRewrittenByTheEditorsLineToolsAndKeepsItsId() {
        val document = OutlineText.parse("- 一\n  - 二")
        val edit = { line: String -> io.github.cragcoffee.memoripple.domain.OutlineEdit(line, line.length, line.length) }
        val heading = OutlineEditing.rewriteLine(document, 2) { line ->
            io.github.cragcoffee.memoripple.domain.WorkOutlineEditing.toggleMarker(edit(line), io.github.cragcoffee.memoripple.domain.OutlineSymbolRole.HEADING).text
        }
        val second = heading.entries[1] as OutlineNode
        assertEquals(2, second.id)
        assertEquals(io.github.cragcoffee.memoripple.domain.OutlineSymbolRole.HEADING, second.role)
        assertEquals("二", second.text)
        val flown = OutlineEditing.rewriteLine(document, 1) { line ->
            io.github.cragcoffee.memoripple.domain.WorkOutlineEditing.applyFlowModifier(edit(line), "→").text
        }
        assertEquals("- 一 →\n  - 二", OutlineText.serialize(flown))
        val task = OutlineEditing.rewriteLine(document, 1) { line ->
            io.github.cragcoffee.memoripple.domain.WorkOutlineEditing.cycleTask(edit(line)).text
        }
        assertEquals(io.github.cragcoffee.memoripple.domain.OutlineSymbolRole.TASK, (task.entries[0] as OutlineNode).role)
        assertSame(document, OutlineEditing.rewriteLine(document, 1) { it })
    }
}

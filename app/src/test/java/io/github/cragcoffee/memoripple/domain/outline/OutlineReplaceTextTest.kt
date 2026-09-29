package io.github.cragcoffee.memoripple.domain.outline

import org.junit.Assert.assertEquals
import org.junit.Test

/** A pasted template or an inserted link may bring newlines: each becomes a line of its own. */
class OutlineReplaceTextTest {
    @Test
    fun oneLineIsAPlainTextChangeWithTheCaretWhereItWasAsked() {
        val document = OutlineText.parse("- 一\n- 二")
        val change = OutlineEditing.replaceText(document, id = 1, text = "一[[旅]]", caret = 6)
        assertEquals("- 一[[旅]]\n- 二", OutlineText.serialize(change.document))
        assertEquals(1, change.focusId)
        assertEquals(6, change.caret)
    }

    @Test
    fun newlinesBecomeFollowingLinesAndTheCaretLandsOnTheLineItFallsIn() {
        val document = OutlineText.parse("- 一\n- 二")
        // 甲 joins the first line; 乙 and 丙 follow it as lines of their own; the caret sits after 丙.
        val change = OutlineEditing.replaceText(document, id = 1, text = "一甲\n乙\n丙", caret = 7)
        assertEquals("- 一甲\n- 乙\n- 丙\n- 二", OutlineText.serialize(change.document))
        val ids = change.document.entries.map { it.id }
        assertEquals(ids[2], change.focusId)
        assertEquals(1, change.caret)
    }

    @Test
    fun aLineWithChildrenTakesTheNewLinesAsItsFirstChildren() {
        val document = OutlineText.parse("- 親\n  - 子")
        val change = OutlineEditing.replaceText(document, id = 1, text = "親\n弟", caret = 3)
        assertEquals("- 親\n  - 弟\n  - 子", OutlineText.serialize(change.document))
    }
}

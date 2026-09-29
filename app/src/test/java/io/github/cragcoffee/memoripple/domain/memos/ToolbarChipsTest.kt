package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.domain.OutlineSymbolRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The chips of the two chip units, arranged and hidden on their own stage, kept as a device preference. */
class ToolbarChipsTest {
    @Test
    fun theLabelsAreTheBarsRolesAndTheFlowChipsItsTokens() {
        assertEquals(OutlineSymbolRole.HEADING, ToolbarChips.roleOf("heading"))
        assertEquals(OutlineSymbolRole.QUESTION, ToolbarChips.roleOf("question"))
        assertNull(ToolbarChips.roleOf("left"))
        assertEquals("+", ToolbarChips.flowToken("large"))
        assertEquals("↑↑", ToolbarChips.flowToken("pin_top"))
        assertEquals(listOf("left", "right", "top", "bottom", "pin_top", "loop", "large", "small"), ToolbarChips.defaultArrangement(ToolbarChipGroup.FLOW).order)
    }

    @Test
    fun anArrangementHidesMovesAndRoundTripsAndForgivesTheUnknown() {
        val arranged = ToolbarChips.defaultArrangement(ToolbarChipGroup.LABELS)
            .withHidden("question", true)
            .moved("important", -1)
            .moved("heading", -1)
        // 重要 steps over 補足; 見出し is first already and stays.
        assertEquals(listOf("heading", "item", "important", "note", "question"), arranged.order)
        assertEquals(listOf("heading", "item", "important", "note"), arranged.visible)
        val stored = ToolbarChips.encode(arranged)
        assertEquals("heading|item|important|note|-question", stored)
        assertEquals(arranged, ToolbarChips.decode(stored, ToolbarChipGroup.LABELS))
        // Unknown ids are dropped; chips this build knows and the string does not join at the end.
        assertEquals(listOf("note", "heading", "item", "important", "question"), ToolbarChips.decode("note|bogus|heading", ToolbarChipGroup.LABELS).order)
        assertEquals(ToolbarChips.defaultArrangement(ToolbarChipGroup.FLOW), ToolbarChips.decode("", ToolbarChipGroup.FLOW))
        val row = ToolbarChips.defaultArrangement(ToolbarChipGroup.FLOW)
        assertSame(row, row.moved("left", -1))
    }
}

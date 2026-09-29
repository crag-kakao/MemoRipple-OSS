package io.github.cragcoffee.memoripple.ui.outline

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The guide lines beside an outline: a line hangs beside every row of a parent's subtree —
 * from the bottom of the parent's own row to the bottom of its last descendant's — so the
 * eye can follow which line a row belongs to, and the line stands clear of both bullets.
 */
class OutlineGuidesTest {
    @Test
    fun aRowCarriesOneGuidePerAncestorLevelAndNoneOfItsOwn() {
        assertEquals(listOf(0, 1), OutlineGuides.levels(indentSteps = 2))
        assertEquals(emptyList<Int>(), OutlineGuides.levels(indentSteps = 0))
    }
}

package io.github.cragcoffee.memoripple.domain.outline

import io.github.cragcoffee.memoripple.domain.WorkCommentParser
import io.github.cragcoffee.memoripple.domain.outline.OutlineEditing as Advanced
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2A editing: Backspace at the start of a line deletes an empty line or joins the
 * words onto the line above; a line moves up and down with its whole subtree; a node can
 * be zoomed into. None of it may orphan a descendant, create a depth jump, or write
 * anything but the same plain body text.
 */
class OutlineEditingAdvancedTest {

    private fun doc(text: String) = OutlineText.parse(text)
    private fun text(document: OutlineDocument) = OutlineText.serialize(document)
    private fun id(document: OutlineDocument, line: Int) = document.entries[line].id
    // The comment parser leaves a space where it lifts a [R n] marker out; the words are what matter here.
    private fun flown(document: OutlineDocument) = WorkCommentParser().parseOutline(text(document)).map { it.text.trim() }

    // --- Backspace: empty line ---

    @Test
    fun backspaceOnAnEmptyLineRemovesItAndPutsTheCaretAtTheEndOfTheLineAbove() {
        val document = doc("- A\n- ")

        val result = Advanced.deleteBackward(document, id(document, 1))

        assertEquals("- A", text(result.document))
        assertEquals(id(document, 0), result.focusId)
        assertEquals(1, result.caret)
    }

    @Test
    fun backspaceOnAnEmptyFirstLineDoesNothing() {
        val document = doc("- \n- B")

        val result = Advanced.deleteBackward(document, id(document, 0))

        assertSame(document, result.document)
        assertEquals(id(document, 0), result.focusId)
        assertEquals(0, result.caret)
    }

    @Test
    fun backspaceOnAnEmptyParentHandsItsChildrenToTheLineAboveInsteadOfOrphaningThem() {
        // The same rule as joining words: what was under the deleted line now sits under
        // the line it joined — nobody is left hanging two levels below a stranger.
        val document = doc("- A\n- \n  - 子一\n  - 子二\n- B")

        val result = Advanced.deleteBackward(document, id(document, 1))

        assertEquals("- A\n  - 子一\n  - 子二\n- B", text(result.document))
        assertEquals(id(document, 0), result.focusId)
        assertTrue(Advanced.hasChildren(result.document, id(document, 0)))
    }

    @Test
    fun backspaceOnAnEmptyLineAfterABlankKeepsTheBlank() {
        val document = doc("- A\n\n- ")

        val result = Advanced.deleteBackward(document, id(document, 2))

        assertEquals("- A\n", text(result.document))
        assertEquals(id(document, 0), result.focusId)
    }

    // --- Backspace: join with the line above ---

    @Test
    fun backspaceAtTheStartOfWordsJoinsThemOntoTheLineAboveWithTheCaretAtTheSeam() {
        val document = doc("- ABC\n- DEF")

        val result = Advanced.deleteBackward(document, id(document, 1))

        assertEquals("- ABCDEF", text(result.document))
        assertEquals(id(document, 0), result.focusId)
        assertEquals(3, result.caret)
        assertEquals(listOf("ABCDEF"), flown(result.document))
    }

    @Test
    fun joiningKeepsTheUpperLineMarkerAndDropsTheJoinedLineMarker() {
        val document = doc("■ 見出し\n・ 項目")

        val result = Advanced.deleteBackward(document, id(document, 1))

        assertEquals("■ 見出し項目", text(result.document))
    }

    @Test
    fun joiningAChildOntoItsParentLiftsTheGrandchildrenOneLevel() {
        val document = doc("- A\n  - B\n    - C\n    - D\n- E")

        val result = Advanced.deleteBackward(document, id(document, 1))

        assertEquals("- AB\n  - C\n  - D\n- E", text(result.document))
        assertEquals(1, result.caret)
    }

    @Test
    fun joiningOntoADeeperLineAboveTakesTheChildrenUnderThatLine() {
        val document = doc("- A\n  - B\n- C\n  - C1")

        val result = Advanced.deleteBackward(document, id(document, 2))

        assertEquals("- A\n  - BC\n    - C1", text(result.document))
    }

    @Test
    fun joiningTheLastSiblingUnderAParentKeepsItsChildrenUnderTheJoinedLine() {
        val document = doc("- A\n  - B\n  - C\n    - C1")

        val result = Advanced.deleteBackward(document, id(document, 2))

        assertEquals("- A\n  - BC\n    - C1", text(result.document))
    }

    @Test
    fun joiningKeepsMarkersModifiersAndLinksOfBothLines() {
        val document = doc("- 前 [R1] ←←\n- 後 [[相手]] [R2] →")

        val result = Advanced.deleteBackward(document, id(document, 1))

        assertEquals("- 前 [R1] ←←後 [[相手]] [R2] →", text(result.document))
        assertEquals("前 [R1] ←←".length, result.caret)
    }

    @Test
    fun backspaceAtTheStartOfTheFirstLineDoesNothing() {
        val document = doc("- A\n- B")

        val result = Advanced.deleteBackward(document, id(document, 0))

        assertSame(document, result.document)
    }

    @Test
    fun backspaceAcrossABlankJoinsOntoTheLineAboveTheBlankAndKeepsTheBlank() {
        val document = doc("- A\n\n- B")

        val result = Advanced.deleteBackward(document, id(document, 2))

        assertEquals("- AB\n", text(result.document))
    }

    // --- move ---

    @Test
    fun moveDownSwapsTheSubtreeWithTheNextSibling() {
        val document = doc("- A\n  - A1\n  - A2\n- B")

        val moved = Advanced.moveDown(document, id(document, 0))

        assertEquals("- B\n- A\n  - A1\n  - A2", text(moved))
        assertEquals(listOf(id(document, 3), id(document, 0), id(document, 1), id(document, 2)), moved.entries.map { it.id })
    }

    @Test
    fun moveUpSwapsTheSubtreeWithThePreviousSibling() {
        val document = doc("- A\n  - A1\n- B\n  - B1\n    - B11")

        val moved = Advanced.moveUp(document, id(document, 2))

        assertEquals("- B\n  - B1\n    - B11\n- A\n  - A1", text(moved))
    }

    @Test
    fun theTopAndBottomSiblingsCannotMoveFurther() {
        val document = doc("- A\n- B")

        assertFalse(Advanced.canMoveUp(document, id(document, 0)))
        assertFalse(Advanced.canMoveDown(document, id(document, 1)))
        assertSame(document, Advanced.moveUp(document, id(document, 0)))
        assertSame(document, Advanced.moveDown(document, id(document, 1)))
    }

    @Test
    fun aChildMovesOnlyAmongItsSiblings() {
        val document = doc("- A\n  - A1\n  - A2\n- B")

        assertFalse(Advanced.canMoveUp(document, id(document, 1)))
        assertFalse(Advanced.canMoveDown(document, id(document, 2)))
        assertEquals("- A\n  - A2\n  - A1\n- B", text(Advanced.moveDown(document, id(document, 1))))
    }

    @Test
    fun blankLinesBetweenSiblingsStayBetweenThem() {
        val document = doc("- A\n\n- B")

        assertEquals("- B\n\n- A", text(Advanced.moveDown(document, id(document, 0))))
    }

    @Test
    fun proseMovesLikeAnyOtherLine() {
        val document = doc("一行目\n二行目\n  - 二の子")

        assertEquals("二行目\n  - 二の子\n一行目", text(Advanced.moveDown(document, id(document, 0))))
    }

    @Test
    fun aHeadingMovesWithItsWholeSection() {
        val document = doc("■ 一\n- 項目\n本文\n■ 二\n- 別")

        assertEquals("■ 二\n- 別\n■ 一\n- 項目\n本文", text(Advanced.moveDown(document, id(document, 0))))
    }

    @Test
    fun movingNeverChangesTheFlownCommentsOnlyTheirOrder() {
        val document = doc("- A [R1] ←\n  - A1 {赤}\n- B ↺")

        val moved = Advanced.moveDown(document, id(document, 0))

        assertEquals(listOf("B", "A", "A1"), flown(moved))
        assertEquals("- B ↺\n- A [R1] ←\n  - A1 {赤}", text(moved))
    }

    // --- zoom ---

    @Test
    fun zoomShowsTheNodeAndItsSubtreeOnly() {
        val document = doc("- 企画\n  - MemoRipple\n    - UI\n    - アウトライナー\n      - Enter\n      - Fold\n  - Launcher")
        val outliner = id(document, 3)

        assertEquals(3..5, Advanced.zoomRange(document, outliner))
        assertEquals(
            listOf(3, 4, 5),
            Advanced.visible(document, emptySet(), outliner).map { document.entries.indexOf(it) },
        )
    }

    @Test
    fun ancestorsRunFromTheRootDownToTheParent() {
        val document = doc("- 企画\n  - MemoRipple\n    - UI\n    - アウトライナー\n      - Enter")

        val path = Advanced.ancestors(document, id(document, 3)).map { it.text }

        assertEquals(listOf("企画", "MemoRipple"), path)
        assertEquals(emptyList<String>(), Advanced.ancestors(document, id(document, 0)).map { it.text })
    }

    @Test
    fun aZoomedRootShowsItsChildrenEvenWhenItIsFolded() {
        val document = doc("- A\n  - A1\n  - A2\n- B")
        val collapsed = setOf(id(document, 0))

        assertEquals(listOf(0, 3), Advanced.visible(document, collapsed, zoomId = null).map { document.entries.indexOf(it) })
        assertEquals(listOf(0, 1, 2), Advanced.visible(document, collapsed, zoomId = id(document, 0)).map { document.entries.indexOf(it) })
    }

    @Test
    fun foldingInsideAZoomHidesOnlyThatSubtree() {
        val document = doc("- A\n  - A1\n    - A11\n  - A2\n- B")
        val collapsed = setOf(id(document, 1))

        assertEquals(listOf(0, 1, 3), Advanced.visible(document, collapsed, zoomId = id(document, 0)).map { document.entries.indexOf(it) })
        // Leaving the zoom keeps the fold.
        assertEquals(listOf(0, 1, 3, 4), Advanced.visible(document, collapsed, zoomId = null).map { document.entries.indexOf(it) })
    }

    @Test
    fun aDirectChildOfTheZoomRootCannotBeOutdentedOutOfTheZoom() {
        val document = doc("- A\n  - A1\n    - A11")

        assertTrue(Advanced.canOutdent(document, id(document, 1)))
        assertFalse(Advanced.canOutdent(document, id(document, 1), zoomId = id(document, 0)))
        assertTrue(Advanced.canOutdent(document, id(document, 2), zoomId = id(document, 0)))
    }

    @Test
    fun theOnePassParentSetAgreesWithHasChildrenOnEveryLine() {
        val document = doc("■ 一\n- 項目\n  - 子\n\n    - 孫\n本文\n  - 続き\n- 二\n# 三\n## 四")

        val expected = document.entries.filterIsInstance<OutlineNode>()
            .filter { Advanced.hasChildren(document, it.id) }.map { it.id }.toSet()

        assertEquals(expected, Advanced.nodesWithChildren(document))
    }

    @Test
    fun zoomIsOnlyAViewAndTheBodyIsUnchanged() {
        val document = doc("- A\n  - A1\n- B")

        Advanced.visible(document, emptySet(), zoomId = id(document, 0))

        assertEquals("- A\n  - A1\n- B", text(document))
        assertNull(document.entries.firstOrNull { it is OutlineBlank })
    }
}

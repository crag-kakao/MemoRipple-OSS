package io.github.cragcoffee.memoripple.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineMarkupEditingTest {

    /** The field after pressing backspace with a collapsed caret at [caret]. */
    private fun backspace(text: String, caret: Int): OutlineEdit {
        val after = OutlineEdit(text.removeRange(caret - 1, caret), caret - 1, caret - 1)
        return InlineMarkupEditing.guard(OutlineEdit(text, caret, caret), after) ?: after
    }

    /** The field after pressing forward delete with a collapsed caret at [caret]. */
    private fun forwardDelete(text: String, caret: Int): OutlineEdit {
        val after = OutlineEdit(text.removeRange(caret, caret + 1), caret, caret)
        return InlineMarkupEditing.guard(OutlineEdit(text, caret, caret), after) ?: after
    }

    /** The field after deleting the selection [start, end). */
    private fun deleteSelection(text: String, start: Int, end: Int): OutlineEdit {
        val after = OutlineEdit(text.removeRange(start, end), start, start)
        return InlineMarkupEditing.guard(OutlineEdit(text, start, end), after) ?: after
    }

    // ------------------------------------------------------------------ shrinking to nothing

    @Test
    fun boldShrinksFromItsVisibleEndDownToNothing() {
        var edit = backspace("**あああ**", 7)
        assertEquals("**ああ**", edit.text)
        assertEquals(4, edit.selectionStart)

        edit = backspace(edit.text, edit.selectionStart)
        assertEquals("**あ**", edit.text)
        assertEquals(3, edit.selectionStart)

        edit = backspace(edit.text, edit.selectionStart)
        assertEquals("", edit.text)
        assertEquals(0, edit.selectionStart)
    }

    @Test
    fun highlightShrinksFromItsVisibleEndDownToNothing() {
        var edit = backspace("==あああ==", 7)
        assertEquals("==ああ==", edit.text)

        edit = backspace(edit.text, edit.selectionStart)
        assertEquals("==あ==", edit.text)

        edit = backspace(edit.text, edit.selectionStart)
        assertEquals("", edit.text)
    }

    @Test
    fun colouredHighlightKeepsItsLongOpeningMarkerWhole() {
        var edit = backspace("==red:あああ==", 11)
        assertEquals("==red:ああ==", edit.text)

        edit = backspace(edit.text, edit.selectionStart)
        assertEquals("==red:あ==", edit.text)

        edit = backspace(edit.text, edit.selectionStart)
        assertEquals("", edit.text)
    }

    @Test
    fun backspaceInsideTheClosingMarkerStillMeansTheLastVisibleCharacter() {
        // The caret can sit between the two closing stars without the writer knowing.
        val edit = backspace("**あああ**", 6)
        assertEquals("**ああ**", edit.text)
    }

    // ------------------------------------------------------------------ head of the span

    @Test
    fun backspaceAtTheVisibleHeadDeletesThePlainCharacterBeforeTheSpan() {
        // Caret inside the opening marker: the visible character before it is the plain x.
        val edit = backspace("x**ああ**", 2)
        assertEquals("**ああ**", edit.text)
        assertEquals(0, edit.selectionStart)
    }

    @Test
    fun backspaceAtTheHeadOfADocumentStartingSpanDeletesNothing() {
        val edit = backspace("**ああ**", 1)
        assertEquals("**ああ**", edit.text)
    }

    @Test
    fun forwardDeleteAtTheSpanStartDeletesTheFirstVisibleCharacter() {
        val edit = forwardDelete("**あああ**", 0)
        assertEquals("**ああ**", edit.text)
        assertEquals(2, edit.selectionStart)
    }

    @Test
    fun forwardDeleteAtTheVisibleEndDeletesThePlainCharacterAfterTheSpan() {
        // Caret between the closing stars; the next visible character is the y beyond them.
        val edit = forwardDelete("**ああ**y", 5)
        assertEquals("**ああ**", edit.text)
    }

    // ------------------------------------------------------------------ middle and selection

    @Test
    fun middleDeleteKeepsTheStyle() {
        val edit = backspace("**あいう**", 4)
        assertEquals("**あう**", edit.text)
    }

    @Test
    fun selectionDeleteInsideTheContentKeepsTheStyle() {
        val edit = deleteSelection("**あいうえお**", 3, 5)
        assertEquals("**あえお**", edit.text)
    }

    @Test
    fun deletingAllContentTakesTheMarkersWithIt() {
        val edit = deleteSelection("**あいう**", 2, 5)
        assertEquals("", edit.text)
        assertEquals(0, edit.selectionStart)
    }

    @Test
    fun selectionReachingPastTheSpanKeepsItsMarkersAroundWhatSurvives() {
        // From the middle of the bold word out into the plain text after it.
        val edit = deleteSelection("**あいう**xyz", 3, 8)
        assertEquals("**あ**yz", edit.text)
        assertEquals(3, edit.selectionStart)
    }

    @Test
    fun selectionCoveringTheWholeSpanRemovesItCleanly() {
        val edit = deleteSelection("a**いう**b", 1, 7)
        assertEquals("ab", edit.text)
    }

    // ------------------------------------------------------------------ adjacent formatting

    @Test
    fun backspaceBetweenAdjacentSpansShrinksTheFirstOnly() {
        // Caret inside the highlight's opening marker, right after the bold span.
        val edit = backspace("**太字**==光==", 8)
        assertEquals("**太**==光==", edit.text)
    }

    @Test
    fun backspaceAtTheEndDropsAOneCharacterSpanLeavingItsNeighbour() {
        val edit = backspace("==光==**太**", 10)
        assertEquals("==光==", edit.text)
    }

    @Test
    fun plainTextAroundASpanIsEditedWithoutTouchingIt() {
        val edit = backspace("これは**大事**です", 9)
        assertEquals("これは**大**です", edit.text)
        assertEquals("これはです", backspace(edit.text, edit.selectionStart).text)
    }

    // ------------------------------------------------------------------ unicode

    @Test
    fun emojiContentIsDeletedAWholeGraphemeAtATime() {
        val edit = backspace("**あ😀**", 7)
        assertEquals("**あ**", edit.text)
    }

    @Test
    fun emojiOnlyContentDisappearsWithItsMarkers() {
        val edit = backspace("**😀**", 6)
        assertEquals("", edit.text)
    }

    // ------------------------------------------------------------------ typing

    @Test
    fun typingInsideTheContentIsLeftAlone() {
        assertNull(
            InlineMarkupEditing.guard(
                OutlineEdit("**ああ**", 3, 3),
                OutlineEdit("**あxあ**", 4, 4),
            ),
        )
    }

    @Test
    fun typingAMarkerByHandIsLeftAlone() {
        assertNull(
            InlineMarkupEditing.guard(
                OutlineEdit("**ああ** x", 8, 8),
                OutlineEdit("**ああ** x*", 9, 9),
            ),
        )
    }

    @Test
    fun aSpaceTypedAtTheVisibleEndLandsOutsideTheMarkers() {
        val guarded = InlineMarkupEditing.guard(
            OutlineEdit("**ああ**", 4, 4),
            OutlineEdit("**ああ **", 5, 5),
        )
        assertEquals("**ああ** ", guarded?.text)
        assertEquals(7, guarded?.selectionStart)
    }

    // ------------------------------------------------------------------ caret movement

    @Test
    fun aTapInsideAMarkerLandsOnTheContentEdge() {
        val snapped = InlineMarkupEditing.guard(
            OutlineEdit("x**ああ**", 0, 0),
            OutlineEdit("x**ああ**", 6, 6),
        )
        assertEquals(5, snapped?.selectionStart)
    }

    @Test
    fun arrowKeysWalkThroughAMarkerInsteadOfSticking() {
        // Rightward from the content edge steps past the closing marker in one move.
        val right = InlineMarkupEditing.guard(
            OutlineEdit("**ああ**", 4, 4),
            OutlineEdit("**ああ**", 5, 5),
        )
        assertEquals(6, right?.selectionStart)
        // Leftward from beyond it steps back onto the content edge.
        val left = InlineMarkupEditing.guard(
            OutlineEdit("**ああ**", 6, 6),
            OutlineEdit("**ああ**", 5, 5),
        )
        assertEquals(4, left?.selectionStart)
    }

    @Test
    fun ordinaryCaretMovesAreLeftAlone() {
        assertNull(
            InlineMarkupEditing.guard(
                OutlineEdit("**ああ**x", 6, 6),
                OutlineEdit("**ああ**x", 7, 7),
            ),
        )
    }

    // ------------------------------------------------------------------ nothing breaks, ever

    @Test
    fun noBackspaceSequenceLeavesABrokenMarker() {
        // Backspace from every caret position, over and over: whatever markers remain must all
        // belong to spans that still match.
        var texts = setOf("あ**いう**え==red:おか==き")
        repeat(8) {
            texts = texts.flatMap { text ->
                (1..text.length).map { caret -> backspace(text, caret).text }
            }.toSet()
            texts.forEach { text ->
                var stripped = text
                InlineTextMarkup.spans(text).sortedByDescending { it.start }.forEach { span ->
                    stripped = stripped.removeRange(span.start, span.end)
                }
                assertTrue(
                    "broken marker in \"$text\"",
                    !stripped.contains("**") && !stripped.contains("=="),
                )
            }
        }
    }
}

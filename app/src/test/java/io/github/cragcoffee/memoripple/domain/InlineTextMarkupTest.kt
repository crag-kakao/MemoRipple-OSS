package io.github.cragcoffee.memoripple.domain

import io.github.cragcoffee.memoripple.domain.comments.CommentColorRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineTextMarkupTest {

    private fun edit(text: String, start: Int, end: Int) = OutlineEdit(text, start, end)

    @Test
    fun boldIsRecognisedAndStripped() {
        val spans = InlineTextMarkup.spans("これは**太字**です")

        assertEquals(1, spans.size)
        assertEquals(InlineStyle.BOLD, spans.single().style)
        assertEquals("これは太字です", InlineTextMarkup.strip("これは**太字**です"))
    }

    @Test
    fun highlightWithoutColourUsesTheDefaultRole() {
        val span = InlineTextMarkup.spans("==目印==").single()

        assertEquals(InlineStyle.HIGHLIGHT, span.style)
        assertEquals(CommentColorRole.DEFAULT, span.color)
    }

    @Test
    fun colouredHighlightReusesThePersistedCommentColourIds() {
        CommentColorRole.entries
            .filter { it != CommentColorRole.DEFAULT }
            .forEach { role ->
                val span = InlineTextMarkup.spans("==${role.storageId}:文字==").single()

                assertEquals(InlineStyle.HIGHLIGHT, span.style)
                assertEquals(role, span.color)
                assertEquals("文字", InlineTextMarkup.strip("==${role.storageId}:文字=="))
            }
    }

    @Test
    fun anUnknownColourIdFallsBackInsteadOfBreakingTheLine() {
        val span = InlineTextMarkup.spans("==lavender:文字==").single()

        assertEquals(CommentColorRole.DEFAULT, span.color)
    }

    @Test
    fun markersAreOnlyMarkersWhenTheyActuallyWrapSomething() {
        assertTrue(InlineTextMarkup.spans("** **").isEmpty())
        assertTrue(InlineTextMarkup.spans("2 ** 3").isEmpty())
        assertEquals("2 ** 3", InlineTextMarkup.strip("2 ** 3"))
    }

    @Test
    fun severalSpansOnOneLineAreAllFound() {
        val spans = InlineTextMarkup.spans("**あ**と==red:い==と**う**")

        assertEquals(3, spans.size)
        assertEquals(listOf(InlineStyle.BOLD, InlineStyle.HIGHLIGHT, InlineStyle.BOLD), spans.map { it.style })
        assertEquals("あといとう", InlineTextMarkup.strip("**あ**と==red:い==と**う**"))
    }

    @Test
    fun togglingWrapsTheSelectionAndTogglingAgainUnwrapsIt() {
        val wrapped = InlineTextMarkup.toggle(edit("太字にする", 0, 2), InlineStyle.BOLD)
        assertEquals("**太字**にする", wrapped.text)

        val unwrapped = InlineTextMarkup.toggle(wrapped, InlineStyle.BOLD)
        assertEquals("太字にする", unwrapped.text)
    }

    @Test
    fun togglingAColouredHighlightCarriesTheColourIntoTheMarker() {
        val result = InlineTextMarkup.toggle(
            edit("目印", 0, 2),
            InlineStyle.HIGHLIGHT,
            CommentColorRole.RED,
        )

        assertEquals("==red:目印==", result.text)
        assertEquals(CommentColorRole.RED, InlineTextMarkup.spans(result.text).single().color)
    }

    @Test
    fun anEmptySelectionIsLeftAloneSoNoBareMarkerIsWritten() {
        val result = InlineTextMarkup.toggle(edit("本文", 2, 2), InlineStyle.BOLD)

        assertEquals("本文", result.text)
    }

    @Test
    fun changingTheStyleOfAnAlreadyDecoratedSelectionReplacesTheMarkers() {
        val bold = InlineTextMarkup.toggle(edit("文字", 0, 2), InlineStyle.BOLD)
        val highlighted = InlineTextMarkup.toggle(bold, InlineStyle.HIGHLIGHT)

        assertEquals("==文字==", highlighted.text)
    }

    @Test
    fun activeStateOnlyReportsAnExactSelectionMatch() {
        val bold = InlineTextMarkup.toggle(edit("文字", 0, 2), InlineStyle.BOLD)

        assertTrue(InlineTextMarkup.isActive(bold, InlineStyle.BOLD))
        assertFalse(InlineTextMarkup.isActive(bold, InlineStyle.HIGHLIGHT))
    }

    @Test
    fun decorationSurvivesAlongsideTheLineStartSyntax() {
        val line = "# **重要**な見出し"

        assertEquals("**重要**な見出し", WorkCommentSyntax.recognize(line)?.text)
        assertEquals("重要な見出し", InlineTextMarkup.strip(WorkCommentSyntax.recognize(line)!!.text))
    }

    @Test
    fun markerOffsetsPointAtTheMarkersThemselves() {
        val span = InlineTextMarkup.spans("ab**cd**").single()

        assertEquals(2 until 4, span.openMarker)
        assertEquals(4 until 6, span.contentRange)
        assertEquals(6 until 8, span.closeMarker)
    }
}

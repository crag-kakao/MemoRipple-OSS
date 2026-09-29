package io.github.cragcoffee.memoripple

import io.github.cragcoffee.memoripple.domain.BodyReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The caret lands where the writing continues (the user's remark of 2026-09-22 00:35): a double
 * tap on a written line puts the caret at the *end* of that line, not its head; the title's tap
 * puts it at the end of the title.
 */
class ReadingCaretPolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val main = "src/main/java/io/github/cragcoffee/memoripple"

    @Test
    fun theEndOfASourceLineIsFoundLikeTheReadingViewCountsLines() {
        val body = "一行目\n\n三行目の本文\n最後"
        assertEquals(3, BodyReading.lineEndOffset(body, 0))
        assertEquals("an empty line ends where it starts", 4, BodyReading.lineEndOffset(body, 1))
        assertEquals(11, BodyReading.lineEndOffset(body, 2))
        assertEquals("the last line ends at the text's end", body.length, BodyReading.lineEndOffset(body, 3))
        assertEquals("past the end lands at the end", body.length, BodyReading.lineEndOffset(body, 9))
        assertEquals(0, BodyReading.lineEndOffset("", 0))
    }

    @Test
    fun theScreenPutsTheCaretAtTheEndOfTheTappedLineAndOfTheTitle() {
        val editor = text("$main/ui/memos/MemoEditorScreen.kt")
        val handoff = editor.substringAfter("is WritingFocus.Body ->").substringBefore("keyboardController?.show()")
        assertTrue("the body's caret goes to the line's end", handoff.contains("BodyReading.lineEndOffset("))
        assertTrue("never to its head", !editor.contains("BodyReading.lineStartOffset("))
        val overload = editor.substringAfter("internal fun EditorTextField(\n    value: String,").substringBefore("\n}")
        assertTrue("the title field can be asked to put its caret at the end", overload.contains("caretToEndKey") && overload.contains("selection = TextRange(field.text.length)"))
        assertTrue("and the title's tap asks for it", editor.contains("titleCaretToEnd"))
    }
}

package io.github.cragcoffee.memoripple

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The S20 review's three small fixes (2026-09-21 night): a user bubble as wide as its words, やめる on the first question only, a caret that shows in the dark. */
class ChatBubbleCancelCursorPolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val screen = text("src/main/java/io/github/cragcoffee/memoripple/ui/chat/ChatScreen.kt")

    @Test
    fun aUserBubbleIsAsWideAsItsWordsAndSitsOnTheRight() {
        val row = screen.substringAfter("private fun TranscriptRow").substringBefore("\n}\n")
        assertFalse("the bubble no longer fills 84 % of the row", row.contains("fillMaxWidth(0.84f)"))
        assertTrue("it wraps its words, capped, on the right", row.contains("wrapContentWidth(Alignment.End)") && row.contains("widthIn(max"))
    }

    @Test
    fun cancelIsOfferedOnTheFirstQuestionOnly() {
        val options = screen.substringAfter("private fun AnswerOptions").substringBefore("\n}\n")
        assertTrue("やめる only at the first question", options.contains("index == 0") && options.contains("chat_answer_cancel"))
        assertTrue("スキップ stays on an optional question", options.contains("chat_answer_skip"))
    }

    @Test
    fun theInputCaretAndSelectionFollowTheTheme() {
        val bar = screen.substringAfter("private fun InputBar").substringBefore("\n}\n")
        assertTrue("the caret is the theme's foreground, not black", bar.contains("cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface)"))
        assertTrue("the selection colours are said too", bar.contains("LocalTextSelectionColors provides"))
    }
}

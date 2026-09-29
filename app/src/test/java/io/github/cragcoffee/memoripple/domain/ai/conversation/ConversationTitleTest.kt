package io.github.cragcoffee.memoripple.domain.ai.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 8 RED 3: a conversation's title is the first user message, shortened deterministically — never a model's words. */
class ConversationTitleTest {
    @Test
    fun theFirstMessageBecomesTheTitleTrimmedAndOnOneLine() {
        assertEquals("昨日の日記を探して", ConversationTitle.from("  昨日の日記を探して \n"))
        assertEquals("MemoRipple開発に『Folder対応完了』を追記して", ConversationTitle.from("MemoRipple開発に『Folder対応完了』を追記して\n二行目は無視"))
        assertEquals("会議 メモを開いて", ConversationTitle.from("会議　　メモを開いて"))
    }

    @Test
    fun aLongMessageIsCutAtTheLimitWithAnEllipsis() {
        val long = "あ".repeat(100)
        val title = ConversationTitle.from(long)
        assertEquals(ConversationTitle.MAX_CHARS + 1, title.length)
        assertTrue(title.endsWith("…"))
        assertEquals("あ".repeat(ConversationTitle.MAX_CHARS), title.dropLast(1))
        assertEquals("a".repeat(ConversationTitle.MAX_CHARS), ConversationTitle.from("a".repeat(ConversationTitle.MAX_CHARS)))
    }

    @Test
    fun anEmptyMessageGetsTheDefaultTitle() {
        assertEquals(ConversationTitle.DEFAULT, ConversationTitle.from(""))
        assertEquals(ConversationTitle.DEFAULT, ConversationTitle.from(" \n\t "))
        assertEquals("新しいチャット", ConversationTitle.DEFAULT)
    }

    @Test
    fun theSameInputAlwaysGivesTheSameTitle() {
        repeat(3) { assertEquals(ConversationTitle.from("散歩を探して"), ConversationTitle.from("散歩を探して")) }
        assertTrue(ConversationTitle.MAX_CHARS in 30..40)
    }
}

package io.github.cragcoffee.memoripple.domain.ai.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「メモを選択」 (docs/CHAT_MEMO_CONTEXT.md): the selection's wire form and the picker's one line. */
class ChatMemoContextTest {

    @Test
    fun selectionsTravelAsConversationToMemoPairs() {
        val map = mapOf(3L to 41L, 7L to 12L)
        assertEquals(setOf("3:41", "7:12"), ChatMemoSelectionCodec.encode(map))
        assertEquals(map, ChatMemoSelectionCodec.decode(setOf("3:41", "7:12")))
    }

    @Test
    fun anythingThatIsNotAPairOfIdsIsSkipped() {
        assertEquals(mapOf(3L to 41L), ChatMemoSelectionCodec.decode(setOf("3:41", "x:1", "4", "5:", ":6", "7:8:9", "-1:2", "8:-3", "")))
        assertTrue(ChatMemoSelectionCodec.decode(emptySet()).isEmpty())
    }

    @Test
    fun thePreviewIsTheFirstLineWithWordsCutShort() {
        assertEquals("牛乳を買う", MemoChoicePreview.of("\n  \n  牛乳を買う  \n卵"))
        assertEquals("", MemoChoicePreview.of("   \n\n"))
        val long = "あ".repeat(200)
        assertEquals(MemoChoicePreview.MAX, MemoChoicePreview.of(long).length)
    }
}

package io.github.cragcoffee.memoripple.domain.ai.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8 RED 35–37, 40: the model sees a bounded recent window of the transcript — at most
 * [ActiveContextBudget.MAX_MESSAGES] messages and [ActiveContextBudget.MAX_CHARS] characters, the
 * newest kept — never the whole history, never a summary of it.
 */
class ActiveContextBudgetTest {
    private fun msg(id: Long, role: ChatRole, text: String) = ChatMessage(id, 1L, role, ChatMessageKind.TEXT, text, id)

    @Test
    fun aShortTranscriptIsPassedWholeAndInOrder() {
        val all = listOf(msg(1, ChatRole.USER, "昨日の日記を探して"), msg(2, ChatRole.ASSISTANT, "3件見つかりました。"))
        val w = ActiveContextBudget.window(all)
        assertEquals(listOf("昨日の日記を探して", "3件見つかりました。"), w.messages.map { it.text })
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), w.messages.map { it.role })
        assertFalse(w.truncated)
    }

    @Test
    fun onlyTheNewestMessagesSurviveTheMessageLimit() {
        val all = (1..20).map { msg(it.toLong(), if (it % 2 == 1) ChatRole.USER else ChatRole.ASSISTANT, "m$it") }
        val w = ActiveContextBudget.window(all)
        assertEquals(ActiveContextBudget.MAX_MESSAGES, w.messages.size)
        assertEquals("m20", w.messages.last().text)
        assertEquals("m${20 - ActiveContextBudget.MAX_MESSAGES + 1}", w.messages.first().text)
        assertTrue(w.truncated)
    }

    @Test
    fun theCharacterBudgetDropsOldMessagesBeforeItClipsTheNewestOne() {
        val big = "あ".repeat(ActiveContextBudget.MAX_CHARS)
        val all = listOf(msg(1, ChatRole.USER, big), msg(2, ChatRole.ASSISTANT, "短い"), msg(3, ChatRole.USER, "最新"))
        val w = ActiveContextBudget.window(all)
        assertEquals(listOf("短い", "最新"), w.messages.map { it.text })
        assertTrue(w.truncated)
        assertTrue(w.messages.sumOf { it.text.length } <= ActiveContextBudget.MAX_CHARS)
    }

    @Test
    fun aSingleOversizedNewestMessageIsClippedNotDropped() {
        val huge = "い".repeat(ActiveContextBudget.MAX_CHARS * 3)
        val w = ActiveContextBudget.window(listOf(msg(1, ChatRole.USER, huge)))
        assertEquals(1, w.messages.size)
        assertTrue(w.messages.single().text.length <= ActiveContextBudget.MAX_MESSAGE_CHARS)
        assertTrue(w.truncated)
    }

    @Test
    fun theBudgetFitsTheModelsContextWithPromptV1AndAGenerationReserve() {
        // prompt v1 is 2,021 chars (≈ 1,400 tokens), the shown lines up to 99 × ~40 chars, the input ≤ 200 chars,
        // the generation reserve 256 tokens (GenerationRequest.DEFAULT_MAX_TOKENS): the window must leave room in 4096.
        assertTrue(ActiveContextBudget.MAX_CHARS in 800..1600)
        assertTrue(ActiveContextBudget.MAX_MESSAGES in 4..8)
        assertTrue(ActiveContextBudget.MAX_MESSAGE_CHARS in 200..400)
        assertEquals(4096, ActiveContextBudget.MODEL_CONTEXT_TOKENS)
    }

    @Test
    fun anEmptyTranscriptIsAnEmptyWindow() {
        val w = ActiveContextBudget.window(emptyList())
        assertTrue(w.messages.isEmpty())
        assertFalse(w.truncated)
        assertEquals(ConversationWindow.EMPTY, w)
    }
}

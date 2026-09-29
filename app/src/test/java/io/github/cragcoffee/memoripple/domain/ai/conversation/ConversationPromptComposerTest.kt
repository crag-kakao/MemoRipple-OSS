package io.github.cragcoffee.memoripple.domain.ai.conversation

import io.github.cragcoffee.memoripple.domain.ai.AiResultContext
import io.github.cragcoffee.memoripple.domain.ai.runtime.StructuredIntentGenerator
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8 RED 19, 36, 38: the frozen prompt v1 stays the system prompt; the recent window is a plain
 * role-formatted section of the user turn, before the shown lines and the input; no id, ref or
 * hidden state ever reaches the model's text.
 */
class ConversationPromptComposerTest {
    private val results = AiResultContext.of(
        listOf(
            DocumentSummary(DocumentRef(DocumentKind.JOURNAL, 4181), "散歩の記録", 1, 1),
            DocumentSummary(DocumentRef(DocumentKind.MEMO, 77), "買い物", 1, 1),
        ),
    )
    private val window = ConversationWindow(
        listOf(ConversationLine(ChatRole.USER, "昨日の日記を探して"), ConversationLine(ChatRole.ASSISTANT, "2件見つかりました。")),
        truncated = false,
    )

    @Test
    fun theUserTurnCarriesTheWindowThenTheShownLinesThenTheInput() {
        val text = ConversationPromptComposer.userMessage("2番目を開いて", results, window)
        val history = text.indexOf("直前の会話:")
        val user = text.indexOf("USER: 昨日の日記を探して")
        val assistant = text.indexOf("ASSISTANT: 2件見つかりました。")
        val shown = text.indexOf("表示中の候補:")
        val input = text.indexOf("入力: 2番目を開いて")
        assertTrue(history in 0 until user && user < assistant && assistant < shown && shown < input)
        assertTrue(text.contains("result_1: 散歩の記録") && text.contains("result_2: 買い物"))
        assertTrue(text.endsWith("入力: 2番目を開いて"))
    }

    @Test
    fun withoutAWindowTheUserTurnIsExactlyPhaseZerosShape() {
        assertEquals(StructuredIntentGenerator.userMessage("散歩を探して", results), ConversationPromptComposer.userMessage("散歩を探して", results, ConversationWindow.EMPTY))
    }

    @Test
    fun noIdRefOrHiddenStateReachesTheModelText() {
        val text = ConversationPromptComposer.userMessage("それに追記して", results, window)
        listOf("4181", "77", "DocumentRef", "JOURNAL", "MEMO", "anchor", "Anchor", "conversationId", "id=").forEach { assertFalse("leaks $it", text.contains(it)) }
    }

    @Test
    fun aTruncatedWindowSaysSoInOneNeutralLineAndNothingElse() {
        val text = ConversationPromptComposer.userMessage("x", AiResultContext.EMPTY, window.copy(truncated = true))
        assertTrue(text.contains("（それより前の会話は省略）"))
        assertFalse(text.contains("要約"))
    }

    @Test
    fun theSystemPromptIsStillTheFrozenV1Asset() {
        val v1 = java.io.File("app/src/main/assets/ai/intent_system.v1.txt").let { if (it.isFile) it else java.io.File("src/main/assets/ai/intent_system.v1.txt") }.readText()
        assertEquals(2021, v1.toByteArray(Charsets.UTF_8).size)
        assertFalse("prompt v1 knows nothing of a conversation section — the composer adds it to the user turn only", v1.contains("直前の会話"))
    }
}

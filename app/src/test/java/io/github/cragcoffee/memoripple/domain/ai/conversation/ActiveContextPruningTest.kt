package io.github.cragcoffee.memoripple.domain.ai.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4B — safe context reduction (docs/GENERATION_EFFICIENCY.md): the bounded active context
 * carries what the model can use — the user's words, the assistant's plain text and its result
 * lines — and leaves out the UI-only events (write confirmations, failure cards) that add tokens
 * and no meaning. The rule is deterministic, the bounds are unchanged, the order is unchanged,
 * and a transcript with no UI-only lines gives exactly the window it gave before.
 */
class ActiveContextPruningTest {
    private var nextId = 1L
    private fun line(role: ChatRole, kind: ChatMessageKind, text: String) = ChatMessage(nextId++, 1L, role, kind, text, createdAt = nextId)

    @Test
    fun writeEventsAndFailureCardsAreLeftOutOfThePrompt() {
        val transcript = listOf(
            line(ChatRole.USER, ChatMessageKind.TEXT, "開発メモを開いて"),
            line(ChatRole.ASSISTANT, ChatMessageKind.RESULT, "メモ「開発メモ」を開きました。"),
            line(ChatRole.USER, ChatMessageKind.TEXT, "それに『完了』を追記して"),
            line(ChatRole.ASSISTANT, ChatMessageKind.WRITE_EVENT, "メモ「開発メモ」への追記内容を確認してください。"),
            line(ChatRole.ASSISTANT, ChatMessageKind.WRITE_EVENT, "追記しました。"),
            line(ChatRole.USER, ChatMessageKind.TEXT, "昨日の日記を開ける？"),
            line(ChatRole.ASSISTANT, ChatMessageKind.FAILURE, "自由な文章での操作にはLocal AIモデルが必要です。"),
        )
        val window = ActiveContextBudget.window(transcript)
        assertEquals(listOf("開発メモを開いて", "メモ「開発メモ」を開きました。", "それに『完了』を追記して", "昨日の日記を開ける？"), window.messages.map { it.text })
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER, ChatRole.USER), window.messages.map { it.role })
        assertTrue("dropping UI-only lines is not a truncation of the conversation", !window.truncated)
    }

    @Test
    fun theLatestResultLineStaysBecauseItNamesWhatWasOpened() {
        val transcript = listOf(
            line(ChatRole.USER, ChatMessageKind.TEXT, "散歩を探して"),
            line(ChatRole.ASSISTANT, ChatMessageKind.RESULT, "3件見つかりました。"),
            line(ChatRole.USER, ChatMessageKind.TEXT, "2番目を開いて"),
            line(ChatRole.ASSISTANT, ChatMessageKind.RESULT, "メモ「散歩の予定」を開きました。"),
        )
        val window = ActiveContextBudget.window(transcript)
        assertEquals(4, window.messages.size)
        assertEquals("メモ「散歩の予定」を開きました。", window.messages.last().text)
    }

    @Test
    fun aTranscriptWithoutUiEventsGivesExactlyTheWindowItGaveBefore() {
        val transcript = (1..8).map { i -> line(if (i % 2 == 1) ChatRole.USER else ChatRole.ASSISTANT, ChatMessageKind.TEXT, "行$i") }
        val window = ActiveContextBudget.window(transcript)
        assertEquals(ActiveContextBudget.MAX_MESSAGES, window.messages.size)
        assertEquals(listOf("行3", "行4", "行5", "行6", "行7", "行8"), window.messages.map { it.text })
        assertTrue(window.truncated)
    }

    @Test
    fun theBoundsStillApplyAfterPruning() {
        val transcript = buildList {
            repeat(4) { add(line(ChatRole.ASSISTANT, ChatMessageKind.WRITE_EVENT, "作成しました。")) }
            repeat(9) { i -> add(line(ChatRole.USER, ChatMessageKind.TEXT, "本文$i" + "あ".repeat(200))) }
        }
        val window = ActiveContextBudget.window(transcript)
        assertTrue(window.messages.size <= ActiveContextBudget.MAX_MESSAGES)
        assertTrue(window.messages.sumOf { it.text.length } <= ActiveContextBudget.MAX_CHARS)
        assertTrue(window.messages.none { it.text.contains("作成しました") })
        assertTrue(window.truncated)
    }

    @Test
    fun anAllUiTranscriptIsAnEmptyWindow() {
        val transcript = listOf(line(ChatRole.ASSISTANT, ChatMessageKind.FAILURE, "端末が熱くなっているため、AIを一時停止しています。"))
        assertTrue(ActiveContextBudget.window(transcript).isEmpty)
    }
}

package io.github.cragcoffee.memoripple.domain.ai.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Conversation → memo (Review Batch 2, human brief 2026-09-22): the transcript exported as a
 * memo body by rule — the title, then each line under 「あなた」 / 「MemoRipple」 — deterministic,
 * never summarised by a model, and carrying only what the user saw: no id, no result_N, no
 * key, no timing, no ticket.
 */
class ConversationExportTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 22)
    private fun line(id: Long, role: ChatRole, kind: ChatMessageKind, text: String) = ChatMessage(id, 7L, role, kind, text, 1_000L + id)

    @Test
    fun theTitleIsTheConversationsUnlessItIsEmptyOrGeneric() {
        assertEquals("散歩の記録", ConversationExport.title("散歩の記録", today))
        assertEquals("会話 - 2026-09-22", ConversationExport.title("", today))
        assertEquals("会話 - 2026-09-22", ConversationExport.title("  ", today))
        assertEquals("会話 - 2026-09-22", ConversationExport.title(ConversationTitle.DEFAULT, today))
        assertEquals("the same words every time", ConversationExport.title("x", today), ConversationExport.title("x", today))
    }

    @Test
    fun everyLineIsRenderedUnderItsSpeakerAndNothingElseIsThere() {
        val lines = listOf(
            line(1, ChatRole.USER, ChatMessageKind.TEXT, "散歩を探して"),
            line(2, ChatRole.ASSISTANT, ChatMessageKind.RESULT, "1件見つかりました。"),
            line(3, ChatRole.USER, ChatMessageKind.TEXT, "1番目を開いて"),
            line(4, ChatRole.ASSISTANT, ChatMessageKind.RESULT, "メモ「散歩のメモ」を開きました。"),
            line(5, ChatRole.ASSISTANT, ChatMessageKind.WRITE_EVENT, "キャンセルしました。"),
            line(6, ChatRole.ASSISTANT, ChatMessageKind.FAILURE, "端末が熱くなっているため、AIを一時停止しています。"),
        )
        val body = ConversationExport.body("散歩", lines, today)
        val expected = "# 散歩\n\n## あなた\n散歩を探して\n\n## MemoRipple\n1件見つかりました。\n\n## あなた\n1番目を開いて\n\n## MemoRipple\nメモ「散歩のメモ」を開きました。\n\n## MemoRipple\nキャンセルしました。\n\n## MemoRipple\n端末が熱くなっているため、AIを一時停止しています。\n"
        assertEquals(expected, body)
        assertEquals("deterministic", body, ConversationExport.body("散歩", lines, today))
        listOf("result_", "conversationId", "createdAt", "ms/", "トークン/秒", "TTFT", "PendingWrite", "{{", "USER", "ASSISTANT", "RESULT", "WRITE_EVENT", "FAILURE", "id=").forEach {
            assertFalse("the body carries $it", body.contains(it))
        }
    }

    @Test
    fun aThinkResultLineReadsAsItWasShownAndAnEmptyTranscriptIsRefused() {
        val think = line(1, ChatRole.ASSISTANT, ChatMessageKind.RESULT, "整理すると、こんな内容です。\n\n今日やること - 2026-09-22\n\n## やること\n買い物")
        val body = ConversationExport.body("今日やること整理", listOf(line(0, ChatRole.USER, ChatMessageKind.TEXT, "今日やること整理"), think), today)
        assertTrue(body.contains("## MemoRipple\n整理すると、こんな内容です。\n\n今日やること - 2026-09-22\n\n## やること\n買い物\n"))
        assertTrue("the title line is the first line", body.startsWith("# 今日やること整理\n"))
        assertEquals(null, ConversationExport.bodyOrNull("x", emptyList(), today))
    }
}

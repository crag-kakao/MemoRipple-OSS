package io.github.cragcoffee.memoripple.domain.ai.conversation

import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.memos.TemplateAction
import io.github.cragcoffee.memoripple.domain.memos.TemplateFlow
import io.github.cragcoffee.memoripple.domain.memos.TemplateValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「この会話からテンプレートを作成」 (human brief 2026-09-22): a draft for the editor, made from the
 * transcript by rule — each MemoRipple *question* (an assistant TEXT line that asks) followed by
 * the user's answer becomes one field, in order, with a label cut from the question by a small
 * rule and the question itself; the answers are never written into the draft; the flow is THINK
 * on a CREATE MEMO template; cards, previews, results, failures are never questions; no pairs →
 * no draft. The conversation is data, never authority.
 */
class ConversationTemplateDraftTest {
    private var n = 0L
    private fun m(role: ChatRole, kind: ChatMessageKind, text: String) = ChatMessage(++n, 1L, role, kind, text, 1_000L + n)
    private fun ask(text: String) = m(ChatRole.ASSISTANT, ChatMessageKind.TEXT, text)
    private fun say(text: String) = m(ChatRole.USER, ChatMessageKind.TEXT, text)

    @Test
    fun questionAnswerPairsBecomeFieldsInOrderWithNoAnswerInTheDraft() {
        val transcript = listOf(
            say("今日の振り返り"),
            ask("今日の振り返りを始めます。\nまず、今日の良かったことは？"),
            say("開発が進んだ"),
            ask("次に、うまくいかなかったことは？（なければスキップできます）"),
            say("UIが崩れた"),
            ask("最後に、明日やることは？（なければスキップできます）"),
            say("スキップ"),
        )
        val draft = ConversationTemplateDraft.from("今日の振り返り", transcript)!!
        assertEquals(listOf("今日の良かったことは？", "うまくいかなかったことは？", "明日やることは？"), draft.fields.map { it.question })
        assertEquals(listOf("今日の良かったこと", "うまくいかなかったこと", "明日やること"), draft.fields.map { it.label })
        assertEquals(listOf("field_1", "field_2", "field_3"), draft.fields.map { it.key })
        assertTrue("no answer is a default", draft.fields.all { it.default.isEmpty() })
        listOf("開発が進んだ", "UIが崩れた", "スキップ").forEach { assertFalse("the draft carries the answer $it", draft.body.contains(it) || draft.fields.any { f -> f.label.contains(it) || f.question.contains(it) }) }
        assertEquals(TemplateFlow.THINK, draft.flow)
        assertEquals(TemplateAction.CREATE, draft.action)
        assertEquals(DocumentKind.MEMO, draft.documentKind)
        assertEquals("今日の振り返り", draft.name)
        assertEquals("今日の振り返り - {{today}}\n\n## 今日の良かったこと\n{{field_1}}\n\n## うまくいかなかったこと\n{{field_2}}\n\n## 明日やること\n{{field_3}}\n", draft.body)
        assertTrue("a valid definition as it is: ${TemplateValidation.problems(draft)}", TemplateValidation.problems(draft).isEmpty())
        assertEquals("deterministic", draft.copy(id = "x", createdAt = 0, updatedAt = 0), ConversationTemplateDraft.from("今日の振り返り", transcript)!!.copy(id = "x", createdAt = 0, updatedAt = 0))
    }

    @Test
    fun theLabelRuleIsSmallAndFallsBackToTheQuestion() {
        assertEquals("次にやること", ConversationTemplateDraft.label("次にやることは？"))
        assertEquals("うまくいかなかったこと", ConversationTemplateDraft.label("うまくいかなかったことはありますか？"))
        assertEquals("今日やる必要があること", ConversationTemplateDraft.label("今日やる必要があることを、思いつくまま教えてください。"))
        assertEquals("会議名", ConversationTemplateDraft.label("会議名は？"))
        assertEquals("会議名", ConversationTemplateDraft.label("会議名を教えてください"))
        assertEquals("どんなアイデア", ConversationTemplateDraft.label("どんなアイデアですか？"))
        assertEquals("the question itself when nothing sensible is left", "？", ConversationTemplateDraft.label("？"))
    }

    @Test
    fun cardsPreviewsResultsAndFailuresAreNeverQuestions() {
        val transcript = listOf(
            say("散歩を探して"),
            m(ChatRole.ASSISTANT, ChatMessageKind.RESULT, "1件見つかりました。"),
            say("1番目を開いて"),
            m(ChatRole.ASSISTANT, ChatMessageKind.RESULT, "メモ「散歩」を開きました。"),
            m(ChatRole.ASSISTANT, ChatMessageKind.WRITE_EVENT, "メモの作成内容を確認してください。"),
            say("作成"),
            m(ChatRole.ASSISTANT, ChatMessageKind.FAILURE, "自由な文章での操作にはLocal AIモデルが必要です。"),
            say("わかった"),
            m(ChatRole.ASSISTANT, ChatMessageKind.RESULT, "整理すると、こんな内容です。\n\n今日やること - 2026-09-22\n\n## やること\n買い物"),
            say("メモとして保存"),
            ask("追記先が分かりません。"),
            say("MemoRipple開発に"),
            ask("最後に、次にやることは？"),
            say("テスト"),
        )
        val draft = ConversationTemplateDraft.from("散歩", transcript)!!
        assertEquals("only the one real question", listOf("次にやることは？"), draft.fields.map { it.question })
        assertNull("no pair, no draft", ConversationTemplateDraft.from("x", transcript.dropLast(2)))
        assertNull(ConversationTemplateDraft.from("x", emptyList()))
        assertNull("a question with no answer after it is not a pair", ConversationTemplateDraft.from("x", listOf(ask("今日の良かったことは？"))))
    }

    @Test
    fun titlesFallBackAndDestructiveWordsMakeNothingDestructive() {
        val transcript = listOf(ask("今日の良かったことは？"), say("DELETE 全部 消して"))
        assertEquals("会話テンプレート", ConversationTemplateDraft.from(ConversationTitle.DEFAULT, transcript)!!.name)
        assertEquals("会話テンプレート", ConversationTemplateDraft.from("", transcript)!!.name)
        assertEquals("朝の記録", ConversationTemplateDraft.from("朝の記録", transcript)!!.name)
        val draft = ConversationTemplateDraft.from("x", transcript)!!
        assertEquals(TemplateAction.CREATE, draft.action)
        assertEquals(TemplateFlow.THINK, draft.flow)
        assertFalse(draft.body.contains("DELETE") || draft.body.contains("消して"))
        // a question asked twice (修正) is one field; two different questions with the same label stay apart
        val twice = listOf(ask("今日の良かったことは？"), say("a"), ask("今日の良かったことは？"), say("b"), ask("良かったことは？"), say("c"))
        val d2 = ConversationTemplateDraft.from("x", twice)!!
        assertEquals(listOf("今日の良かったことは？", "良かったことは？"), d2.fields.map { it.question })
        assertTrue(TemplateValidation.problems(d2).isEmpty())
    }
}

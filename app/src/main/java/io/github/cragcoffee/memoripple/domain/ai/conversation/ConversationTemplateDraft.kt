package io.github.cragcoffee.memoripple.domain.ai.conversation

import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.TemplateAction
import io.github.cragcoffee.memoripple.domain.memos.TemplateBodyDisplay
import io.github.cragcoffee.memoripple.domain.memos.TemplateField
import io.github.cragcoffee.memoripple.domain.memos.TemplateFieldType
import io.github.cragcoffee.memoripple.domain.memos.TemplateFlow
import io.github.cragcoffee.memoripple.domain.memos.TemplateValues
import java.util.UUID

/**
 * 「この会話からテンプレートを作成」 (2026-09-22; docs/CHAT_UI_TEMPLATE_V2.md §19): a *draft* for the
 * template editor, made from the transcript by rule and nothing else. A conversation is data, never
 * authority: no model reads it, nothing in it can name an action, and the draft is saved only when
 * the user saves it in the editor.
 *
 * The rule: a MemoRipple line of kind TEXT (what the assistant *said* — never a RESULT card, a
 * WRITE_EVENT preview / confirmation, or a FAILURE) whose last sentence asks, followed by the user's
 * TEXT line, is one field: its question, a label cut from the question by [label], its place in
 * order. The user's words are never written into the draft — no default, no body text. The flow is
 * THINK on a CREATE MEMO template (the safest shape: questions and a result, a memo only on
 * request); the editor lets the user change any of it.
 */
object ConversationTemplateDraft {
    const val FALLBACK_NAME = "会話テンプレート"
    private const val MAX_LABEL_CHARS = 30
    private val LEADS = listOf("まず、", "次に、", "最後に、", "まず ", "次に ", "最後に ")
    private const val SKIP_HINT = "（なければスキップできます）"

    /** The draft, or null when the conversation has no question → answer pair. */
    fun from(conversationTitle: String, transcript: List<ChatMessage>): MemoTemplate? {
        val questions = LinkedHashSet<String>()
        transcript.forEachIndexed { i, m ->
            if (m.role != ChatRole.ASSISTANT || m.kind != ChatMessageKind.TEXT) return@forEachIndexed
            val next = transcript.getOrNull(i + 1) ?: return@forEachIndexed
            if (next.role != ChatRole.USER || next.kind != ChatMessageKind.TEXT || next.text.isBlank()) return@forEachIndexed
            questionOf(m.text)?.let { questions += it }
        }
        if (questions.isEmpty()) return null
        val labels = ArrayList<String>()
        val fields = questions.mapIndexed { i, q ->
            var base = label(q).take(MAX_LABEL_CHARS).ifBlank { q.take(MAX_LABEL_CHARS) }
            var n = 2
            while (base in labels) base = label(q).take(MAX_LABEL_CHARS - 2) + n++   // two questions may cut to one label; the editor refuses duplicates
            labels += base
            TemplateField(key = "field_${i + 1}", label = base, type = TemplateFieldType.MULTILINE, required = false, question = q)
        }
        val name = conversationTitle.trim().let { if (it.isEmpty() || it == ConversationTitle.DEFAULT) FALLBACK_NAME else it }
        val body = buildString {
            append(name).append(" - {{").append(TemplateValues.TODAY_KEY).append("}}\n")
            fields.forEach { f -> append("\n## ").append(f.label).append('\n').append("{{").append(f.key).append("}}\n") }
        }
        return MemoTemplate(
            id = UUID.randomUUID().toString(), name = name, body = body, description = "",
            action = TemplateAction.CREATE, documentKind = DocumentKind.MEMO, flow = TemplateFlow.THINK, fields = fields,
        )
    }

    /** The question an assistant line asks — its last line without the script's lead-in and skip hint — or null when it asks nothing. */
    fun questionOf(assistantText: String): String? {
        var q = assistantText.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return null
        q = q.removeSuffix(SKIP_HINT).trim()
        LEADS.forEach { lead -> if (q.startsWith(lead)) q = q.removePrefix(lead).trim() }
        val asks = q.endsWith("？") || q.endsWith("?") || q.removeSuffix("。").endsWith("ください")
        return q.takeIf { asks && it.isNotEmpty() }
    }

    /**
     * A label cut from a question by a small rule — 「今日の良かったことは？」→「今日の良かったこと」,
     * 「〜はありますか？」→「〜」, 「〜を…教えてください」→ the part before を — and the question itself
     * when nothing sensible is left. No language model; the editor is where a person renames it.
     */
    fun label(question: String): String {
        var s = question.trim().removeSuffix("？").removeSuffix("?").removeSuffix("。").trim()
        if (s.endsWith("教えてください")) {
            s = s.removeSuffix("教えてください").trim()
            val wo = s.indexOf('を')
            if (wo > 0) s = s.substring(0, wo)
            s = s.trimEnd('、', ' ', '　')
        }
        listOf("ありますか", "でしたか", "ましたか", "ですか", "ますか").forEach { suf -> if (s.endsWith(suf)) s = s.removeSuffix(suf) }
        listOf("はどれ", "は何", "はどこ", "は誰", "はいつ", "は").forEach { suf -> if (s.endsWith(suf)) s = s.removeSuffix(suf) }
        s = s.trim()
        return s.ifEmpty { question.trim() }
    }
}

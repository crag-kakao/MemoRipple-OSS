package io.github.cragcoffee.memoripple.domain.ai.conversation

import java.time.LocalDate

/**
 * Conversation → memo (Review Batch 2, 2026-09-22; docs/AI_CONVERSATION_HISTORY.md §export): the
 * transcript as a memo body, by rule. The persistent transcript already holds only what the user
 * typed and what the screen showed — no raw answer, no speed figure, no ref, ticket or key ever reaches it —
 * so the export is those lines, each under its speaker, after the title. Deterministic: no model
 * summarises, no model titles; the same conversation gives the same words. One-way: the
 * conversation itself is untouched, and the memo carries no link back.
 */
object ConversationExport {
    const val USER_HEADING = "## あなた"
    const val ASSISTANT_HEADING = "## MemoRipple"

    /** The conversation's own title, or 「会話 - yyyy-MM-dd」 when it has none worth keeping. */
    fun title(conversationTitle: String, today: LocalDate): String {
        val t = conversationTitle.trim()
        return if (t.isEmpty() || t == ConversationTitle.DEFAULT) "会話 - $today" else t
    }

    fun body(conversationTitle: String, transcript: List<ChatMessage>, today: LocalDate): String = buildString {
        append("# ").append(title(conversationTitle, today)).append('\n')
        transcript.forEach { m ->
            append('\n').append(if (m.role == ChatRole.USER) USER_HEADING else ASSISTANT_HEADING).append('\n')
            append(m.text.trimEnd()).append('\n')
        }
    }

    /** Null when there is nothing to save. */
    fun bodyOrNull(conversationTitle: String, transcript: List<ChatMessage>, today: LocalDate): String? =
        if (transcript.isEmpty()) null else body(conversationTitle, transcript, today)
}

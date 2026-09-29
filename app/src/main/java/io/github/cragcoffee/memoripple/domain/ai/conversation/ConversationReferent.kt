package io.github.cragcoffee.memoripple.domain.ai.conversation

import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import java.text.Normalizer

/**
 * What the conversation lends to one ask: the last document anchor (a real reference the model
 * never sees; re-validated before use) and the recent window for the prompt.
 */
data class ConversationReferents(
    val anchor: DocumentRef? = null,
    val window: ConversationWindow = ConversationWindow.EMPTY,
    /** Phase 4B: which conversation this ask belongs to — the generation cache identity; null = no reuse. */
    val conversationId: Long? = null,
) {
    companion object {
        val NONE = ConversationReferents()
    }
}

/**
 * The deterministic reading of a referent in the user's own words — a rule, never a guess, used
 * only when the model gave neither a shown ref nor a name for OPEN / APPEND:
 *
 * - [Ordinal] — 「N番目」 / 「N件目」 / 「最初の」: a position in the latest shown results;
 * - [Last] — 「最後の」;
 * - [Anchor] — 「それ」 / 「これ」 / 「その…」 / 「この…」 (with its kind word) / 「さっきの…」 / 「前のやつ」: the last document anchor, with the
 *   kind word (メモ / 日記 / アウトライン) that must agree with it.
 *
 * Only the head of the sentence is read (a quoted append text is removed first); anything else —
 * a name, a date phrase, a bare kind word, a count — is no referent and stays with the extractor.
 */
sealed interface ConversationReferent {
    data class Ordinal(val index: Int) : ConversationReferent
    data object Last : ConversationReferent
    data class Anchor(val kind: DocumentKind?) : ConversationReferent

    companion object {
        private val QUOTED = Regex("[『「\"][^』」\"]*[』」\"]")
        private val ORDINAL = Regex("^(\\d{1,2})(?:番目|件目|つ目)")
        private val FIRST = Regex("^(最初の|一番目の?|1番目の?)")
        private val LAST = Regex("^最後の")
        // 「この〜」 only with its kind word (2026-09-26, docs/CHAT_MEMO_CONTEXT.md): 「このメモ」 is the selected memo, 「このまちの記録」 stays a title
        private val DEMONSTRATIVE = Regex("""^(それ|これ|そっち|さっきの|その|前のやつ|さっきのやつ|この(?=メモ|日記|アウトライン))(メモ|日記|アウトライン)?(?=[にをへはの]|$)""")
        private val WHOLE_PHRASE = Regex("""^(?:(?:それ|これ|そっち|その|さっきの|前のやつ|さっきのやつ|さっき)(?:メモ|日記|アウトライン|の)?|この(?:メモ|日記|アウトライン)|\d{1,2}(?:番目|件目|つ目)(?:の)?|最初の?|最後の?|一番目の?)(?:に|を|へ)?$""")

        /** A model may write the demonstrative itself into `targetName` (「それ」, 「さっきのメモ」, 「2番目」): that is a referent, never a title to search. */
        fun isReferentPhrase(name: String): Boolean = WHOLE_PHRASE.matches(Normalizer.normalize(name, Normalizer.Form.NFKC).trim())

        fun parse(userText: String): ConversationReferent? {
            val head = Normalizer.normalize(userText, Normalizer.Form.NFKC).replace(QUOTED, "").trim()
            if (head.isEmpty()) return null
            ORDINAL.find(head)?.let { m -> return m.groupValues[1].toIntOrNull()?.takeIf { it in 1..99 }?.let { Ordinal(it) } }
            if (FIRST.containsMatchIn(head)) return Ordinal(1)
            if (LAST.containsMatchIn(head)) return Last
            DEMONSTRATIVE.find(head)?.let { m ->
                val kind = when (m.groupValues[2]) {
                    "メモ" -> DocumentKind.MEMO
                    "日記" -> DocumentKind.JOURNAL
                    "アウトライン" -> DocumentKind.OUTLINE
                    else -> null
                }
                return Anchor(kind)
            }
            return null
        }
    }
}

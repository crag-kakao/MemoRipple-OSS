package io.github.cragcoffee.memoripple.domain.ai.fast

import io.github.cragcoffee.memoripple.domain.ai.AiIntent
import io.github.cragcoffee.memoripple.domain.ai.DateToken
import io.github.cragcoffee.memoripple.domain.ai.IntentProposal
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import java.text.Normalizer

/**
 * The chat's Fast Path, Phase 1 (docs/CHAT_FAST_PATH.md, 2026-09-22): a handful of unmistakable
 * Japanese shapes become an [IntentProposal] by rule, so a clear ask never wakes the model. What
 * is shortened is only the LLM's proposal generation — the proposal then walks the same
 * SemanticValidator → Resolver → ExecutionPolicy → preview / Human Confirmation pipeline as a
 * model's. Anything the rules are not sure of is NO_MATCH and goes to the AI route: a false
 * negative costs a model call, a false positive would cost trust, so the rules stay narrow.
 *
 * Pure: no clock (dates stay [DateToken]s for the Resolver), no store, no runtime, no network.
 */
/** A recognized sentence: the proposal, and whether it leans on the conversation's anchor (それに…). */
data class FastIntent(val proposal: IntentProposal, val needsAnchor: Boolean = false)

object FastIntentRecognizer {

    private val QUOTED = Regex("[『「\"“]([^』」\"”]*)[』」\"”]")
    /** One operation per message: these, counted outside quotes, must appear exactly once. */
    private val OPERATION = Regex("開いて|開く|見せて|表示して|探して|検索して|作って|作成して|追記して|追加して|書き足して")
    /** A negated ask is never an ask. */
    private val NEGATION = Regex("(ないで|なくて(いい|良い)|ないでください|ない方|ません)")
    /** A word that names nothing on its own — never a title, never a query. */
    private val GENERIC = setOf("メモ", "日記", "アウトライン", "ノート", "フォルダ", "フォルダー", "テンプレート", "記録", "ファイル", "文書", "ドキュメント", "やつ", "もの", "これ", "それ", "あれ")
    /** A referent head the anchor must settle — the parser itself never decides what それ means. */
    private val REFERENT_HEAD = Regex("^(それ|これ)に")
    /** A plain token: no space and none of the particles that would make it a clause, not a title. */
    private val PLAIN_TOKEN = Regex("^[^\\s、。，．…にをはがでへとも]{1,32}$")

    private val OPEN_DATED = Regex("^(今日|きょう|昨日|きのう)の日記を(開いて|開く|見せて|表示して)(ください)?$")
    private val OPEN_NAMED = Regex("^(.+?)を(開いて|開く|見せて|表示して)(ください)?$")
    private val SEARCH_DATED = Regex("^(今日|きょう|昨日|きのう)の日記を(探して|検索して)(ください)?$")
    private val SEARCH_TITLED = Regex("^『?「?(.+?)』?」?という(メモ|文章|記録)?を?(探して|検索して)(ください)?$")
    private val SEARCH_PLAIN = Regex("^(.+?)を(探して|検索して)(ください)?$")
    private val CREATE_MEMO = Regex("^(新しい)?メモを?(1つ|一つ)?(作って|作成して)(ください)?$")
    private val APPEND_SHAPE = Regex("^(.+?)に(.+?)を(追記して|追加して|書き足して)(ください)?$")

    fun recognize(userText: String): FastIntent? {
        val text = normalize(userText)
        if (text.isEmpty()) return null
        // the guards, in the order they refuse: a question, a negation, more or less than one operation outside quotes
        if (text.contains('?') || text.contains('？')) return null
        if (NEGATION.containsMatchIn(text)) return null
        if (OPERATION.findAll(text.replace(QUOTED, "〈〉")).count() != 1) return null

        OPEN_DATED.matchEntire(text)?.let { m ->
            return FastIntent(IntentProposal(AiIntent.OPEN, documentKind = DocumentKind.JOURNAL, dateToken = dateOf(m.groupValues[1])))
        }
        SEARCH_DATED.matchEntire(text)?.let { m ->
            return FastIntent(IntentProposal(AiIntent.SEARCH, documentKind = DocumentKind.JOURNAL, dateToken = dateOf(m.groupValues[1])))
        }
        CREATE_MEMO.matchEntire(text)?.let {
            return FastIntent(IntentProposal(AiIntent.CREATE, documentKind = DocumentKind.MEMO))
        }
        SEARCH_TITLED.matchEntire(text)?.let { m ->
            val query = m.groupValues[1].trim()
            val kind = if (m.groupValues[2] == "メモ") DocumentKind.MEMO else null
            if (query.isNotEmpty() && query.length <= 64) return FastIntent(IntentProposal(AiIntent.SEARCH, query = query, documentKind = kind))
        }
        APPEND_SHAPE.matchEntire(text)?.let { m ->
            val body = titleOrToken(m.groupValues[2]) ?: return@let
            if (REFERENT_HEAD.containsMatchIn(text)) {
                // それに… — the *existing* anchor rule settles the target, or the route declines; never this parser
                return FastIntent(IntentProposal(AiIntent.APPEND, text = body), needsAnchor = true)
            }
            val target = titleOrToken(m.groupValues[1]) ?: return@let
            return FastIntent(IntentProposal(AiIntent.APPEND, targetName = target, text = body))
        }
        OPEN_NAMED.matchEntire(text)?.let { m ->
            val name = titleOrToken(m.groupValues[1]) ?: return@let
            return FastIntent(IntentProposal(AiIntent.OPEN, targetName = name))
        }
        SEARCH_PLAIN.matchEntire(text)?.let { m ->
            val query = titleOrToken(m.groupValues[1]) ?: return@let
            return FastIntent(IntentProposal(AiIntent.SEARCH, query = query))
        }
        return null
    }

    /** A quoted span as it is, or a plain particle-free token; a generic word or a date word alone is neither. */
    private fun titleOrToken(raw: String): String? {
        val s = raw.trim()
        QUOTED.matchEntire(s)?.let { m -> return m.groupValues[1].trim().takeIf { it.isNotEmpty() && it.length <= 64 } }
        if (!PLAIN_TOKEN.matches(s)) return null
        if (s in GENERIC) return null
        // a date word heading the token makes it a date phrase, not a title (Phase 6's standing
        // rule; gate-found 2026-09-22: 「昨日の記録」 must not become a query) — a prefix check
        if (DATE_HEAD.containsMatchIn(s)) return null
        // an ordinal is the conversation's referent (Phase 8: the stored result), never a name
        if (ORDINAL.matches(s)) return null
        return s
    }

    private val DATE_HEAD = Regex("^(今日|きょう|昨日|きのう|明日|一昨日|今週|先週|来週)")
    private val ORDINAL = Regex("^[0-9０-９一二三四五六七八九十]+(番目|つ目|個目|件目)$")

    private fun dateOf(word: String): DateToken = if (word == "今日" || word == "きょう") DateToken.TODAY else DateToken.YESTERDAY

    /** NFKC (what the referent parser already uses), trimmed, trailing 。/！ dropped; nothing that changes meaning. */
    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).trim().trimEnd('。', '！', '!').trim()
}

/** Where one message goes: straight into the safe pipeline, or to the AI as before. */
sealed interface ChatRoute {
    data class Fast(val intent: FastIntent) : ChatRoute
    data object RequiresAi : ChatRoute
}

/** The router: today one deterministic recognizer; a DecisionEngine may join it in a later round. */
interface ChatRouter {
    fun route(userText: String): ChatRoute
}

object DeterministicChatRouter : ChatRouter {
    override fun route(userText: String): ChatRoute =
        FastIntentRecognizer.recognize(userText)?.let { ChatRoute.Fast(it) } ?: ChatRoute.RequiresAi
}

package io.github.cragcoffee.memoripple.domain.ai.decision

import io.github.cragcoffee.memoripple.domain.ai.AiIntent
import io.github.cragcoffee.memoripple.domain.ai.AiResultRef
import io.github.cragcoffee.memoripple.domain.ai.DateToken
import io.github.cragcoffee.memoripple.domain.ai.IntentProposal
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import java.text.Normalizer

/**
 * DecisionEngine — Phase 3 (docs/DECISION_ENGINE.md, human brief 2026-09-23). A **pure,
 * deterministic** layer between the Fast Path's NO_MATCH and the generation model. Its whole
 * authority is routing: a CERTAIN operation the safe pipeline can take as an ordinary
 * [IntentProposal], a fixed clarification question when exactly one slot is missing, or
 * NotApplicable for the model. It reads only what the conversation lends it ([DecisionInput] —
 * the shown results and the one anchor of *this* conversation), invents no id, no name and no
 * numeric certainty, and can neither search, write nor execute: the resolver stage stays the authority
 * for existence and ambiguity, the preview and the Human Confirmation stay where they are.
 */
interface DecisionEngine {
    fun decide(input: DecisionInput): DecisionResult
}

/**
 * What one decision may see — assembled by the caller from the conversation's safe context,
 * never wider: the latest shown results (their count and user-visible titles, positional) and
 * the last document anchor (its presence, kind and title). Another conversation's refs simply
 * are not here, which is the whole cross-conversation isolation.
 */
data class DecisionInput(
    val rawText: String,
    val shownCount: Int,
    val shownTitles: List<String>,
    val hasAnchor: Boolean,
    val anchorKind: DocumentKind?,
    val anchorTitle: String?,
)

/** Never a number: an operation is CERTAIN or it is not an operation. AMBIGUOUS exists for future engines and is refused by the door. */
enum class DecisionCertainty { CERTAIN, AMBIGUOUS }

/**
 * The one slot an open question fills in a draft proposal. TARGET and BODY are this engine's;
 * QUERY is opened by the chat home's launcher (2026-09-23) — the deterministic engine never asks
 * for it, and an answer to any of the three completes through the same settle pipeline.
 */
enum class ClarificationSlot { TARGET, BODY, QUERY }

/** One offered answer: a position in the shown results, or (null) the last opened document. The label is what the user already saw. */
data class ClarificationChoice(val label: String, val ordinal: Int?)

sealed interface DecisionResult {
    /** A complete proposal for the unchanged validator → resolver → policy → preview pipeline. [anchorTarget]: the target is the conversation's anchor, re-read by the door. */
    data class Operation(
        val proposal: IntentProposal,
        val certainty: DecisionCertainty,
        val anchorTarget: Boolean = false,
    ) : DecisionResult

    /** Exactly one slot is missing and the question is fixed; [draft] is the proposal the answer completes. */
    data class NeedsClarification(
        val slot: ClarificationSlot,
        val question: String,
        val choices: List<ClarificationChoice>,
        val draft: IntentProposal,
        val anchorTarget: Boolean = false,
    ) : DecisionResult

    data object NotApplicable : DecisionResult
}

/**
 * The one Phase 3 implementation: narrow shapes, rules only. Everything unsure is NotApplicable —
 * a false negative costs one model call, a false positive would cost trust (the Fast Path's
 * standing policy, applied one layer up). The golden corpus in `DecisionEngineTest` is the contract.
 */
object DeterministicDecisionEngine : DecisionEngine {

    private val QUOTED = Regex("[『「\"“]([^』」\"”]*)[』」\"”]")
    private val OPEN_VERB = Regex("(開いて|開く|見せて|表示して)(ください)?$")
    private val SEARCH_VERB = Regex("(探して|検索して)(ください)?$")
    private val APPEND_VERB = Regex("(追記して|追加して|書き足して)(ください)?$")
    private val ANY_OP = Regex("開いて|開く|見せて|表示して|探して|検索して|追記して|追加して|書き足して")
    /** A negated ask is never an ask (the Fast Path's rule, shared spirit). */
    private val NEGATION = Regex("(ないで|なくて(いい|良い)|ないでください|ない方|ません)")
    /** A question about ability or intent is never an operation: ？, or a question ending. */
    private val QUESTION_TAIL = Regex("(か|かな|っけ|かしら)$")
    /** A word that names nothing on its own — never a body either. */
    private val GENERIC = setOf("メモ", "日記", "アウトライン", "ノート", "フォルダ", "フォルダー", "テンプレート", "記録", "ファイル", "文書", "ドキュメント", "やつ", "もの", "これ", "それ", "あれ")
    private val PLAIN_TOKEN = Regex("^[^\\s、。，．…にをはがでへとも]{1,32}$")

    private val ORDINAL_HEAD = Regex("^(?:(\\d{1,2})(?:番目|件目|つ目)|(最初)|(最後)|(一番目))の?")
    private val DEMONSTRATIVE_HEAD = Regex("^(?:それ|これ|そっち|前のやつ|さっきのやつ|さっきの(メモ|日記|アウトライン)|その(メモ|日記|アウトライン)|この(メモ|日記|アウトライン))")
    private val DATED = Regex("^(今日|きょう|昨日|きのう)の(メモ|日記|アウトライン|記録)を?$")
    private val DATED_VAGUE = Regex("^(今日|きょう|昨日|きのう)のやつ$")

    private const val ASK_BODY = "何を追記しますか？"
    private const val ASK_APPEND_TARGET = "どの記録に追記しますか？"
    private const val ASK_OPEN_TARGET = "どれを開きますか？"
    private const val MAX_CHOICES = 5

    override fun decide(input: DecisionInput): DecisionResult {
        val text = normalize(input.rawText)
        if (text.isEmpty()) return DecisionResult.NotApplicable
        // the guards, in the order they refuse: a question, a negation, anything but exactly one operation ending the sentence
        if (text.contains('?') || text.contains('？')) return DecisionResult.NotApplicable
        val unquoted = text.replace(QUOTED, "〈〉")
        if (QUESTION_TAIL.containsMatchIn(unquoted)) return DecisionResult.NotApplicable
        if (NEGATION.containsMatchIn(unquoted)) return DecisionResult.NotApplicable
        if (ANY_OP.findAll(unquoted).count() != 1) return DecisionResult.NotApplicable

        val open = OPEN_VERB.find(text)
        val search = SEARCH_VERB.find(text)
        val append = APPEND_VERB.find(text)
        // the one operation must end the sentence — 「開いて要約して」 keeps its verb mid-sentence and stays the model's
        val verb = open ?: search ?: append ?: return DecisionResult.NotApplicable
        val head = text.substring(0, verb.range.first).trim()

        return when {
            open != null -> decideOpen(head, input)
            search != null -> decideSearch(head, input)
            else -> decideAppend(head, input)
        }
    }

    // --- OPEN: an ordinal, the anchor, a dated kind, or nothing ---

    private fun decideOpen(head: String, input: DecisionInput): DecisionResult {
        val h = head.removeSuffix("を").trim()
        ordinalIndex(h, input.shownCount)?.let { index ->
            return certain(IntentProposal(AiIntent.OPEN, targetRef = AiResultRef(index)))
        }
        if (isBareDemonstrative(h)) {
            return if (anchorAgrees(h, input)) certain(IntentProposal(AiIntent.OPEN), anchorTarget = true) else DecisionResult.NotApplicable
        }
        DATED.matchEntire(h + "を")?.let { m ->
            val kind = kindOf(m.groupValues[2]) ?: return DecisionResult.NotApplicable   // 記録 names no dated OPEN target
            return certain(IntentProposal(AiIntent.OPEN, documentKind = kind, dateToken = dateOf(m.groupValues[1])))
        }
        if (DATED_VAGUE.matches(h)) return vagueTargetClarification(input, ASK_OPEN_TARGET, IntentProposal(AiIntent.OPEN))
        if (h.isEmpty()) {
            return if (input.hasAnchor) certain(IntentProposal(AiIntent.OPEN), anchorTarget = true) else DecisionResult.NotApplicable
        }
        return DecisionResult.NotApplicable
    }

    // --- SEARCH: only the dated shapes; anything else stayed with the Fast Path or the model ---

    private fun decideSearch(head: String, input: DecisionInput): DecisionResult {
        val h = head.trim()
        DATED.matchEntire(if (h.endsWith("を")) h else "${h}を")?.let { m ->
            val kind = kindOf(m.groupValues[2])   // 記録 = every kind, a bounded dated search
            return certain(IntentProposal(AiIntent.SEARCH, documentKind = kind, dateToken = dateOf(m.groupValues[1])))
        }
        return DecisionResult.NotApplicable
    }

    // --- APPEND: target and body, each an exact reading or a fixed question ---

    private fun decideAppend(head: String, input: DecisionInput): DecisionResult {
        val h = head.trim()
        // Xに(bodyを)? — X an ordinal or a demonstrative
        val ni = splitTargetAndBody(h)
        if (ni != null) {
            val (targetPart, bodyPart) = ni
            ordinalIndex(targetPart, input.shownCount)?.let { index ->
                val draft = IntentProposal(AiIntent.APPEND, targetRef = AiResultRef(index))
                return withBody(draft, bodyPart, anchorTarget = false)
            }
            if (isBareDemonstrative(targetPart)) {
                if (!anchorAgrees(targetPart, input)) return DecisionResult.NotApplicable
                return withBody(IntentProposal(AiIntent.APPEND), bodyPart, anchorTarget = true)
            }
            if (DATED_VAGUE.matches(targetPart)) {
                val body = bodyPart?.let { bodyToken(it) ?: return DecisionResult.NotApplicable }
                return vagueTargetClarification(input, ASK_APPEND_TARGET, IntentProposal(AiIntent.APPEND, text = body))
            }
            return DecisionResult.NotApplicable
        }
        // 『body』を追記して — a body with no target at all
        if (h.endsWith("を")) {
            val body = bodyToken(h.removeSuffix("を").trim()) ?: return DecisionResult.NotApplicable
            val draft = IntentProposal(AiIntent.APPEND, text = body)
            if (input.hasAnchor && input.shownCount == 0) return certain(draft, anchorTarget = true)
            return vagueTargetClarification(input, ASK_APPEND_TARGET, draft)
        }
        // 追記して alone: the anchor is the only safe target, and the body is the question
        if (h.isEmpty()) {
            return if (input.hasAnchor) {
                DecisionResult.NeedsClarification(ClarificationSlot.BODY, ASK_BODY, emptyList(), IntentProposal(AiIntent.APPEND), anchorTarget = true)
            } else {
                DecisionResult.NotApplicable
            }
        }
        return DecisionResult.NotApplicable
    }

    private fun withBody(draft: IntentProposal, bodyPart: String?, anchorTarget: Boolean): DecisionResult {
        if (bodyPart == null) return DecisionResult.NeedsClarification(ClarificationSlot.BODY, ASK_BODY, emptyList(), draft, anchorTarget)
        val body = bodyToken(bodyPart) ?: return DecisionResult.NotApplicable
        return certain(draft.copy(text = body), anchorTarget)
    }

    /** The enumerable candidates — the shown titles and the anchor — or nothing to offer. Never more than [MAX_CHOICES], never a pick. */
    private fun vagueTargetClarification(input: DecisionInput, question: String, draft: IntentProposal): DecisionResult {
        if (input.shownCount != input.shownTitles.size) return DecisionResult.NotApplicable
        val shown = input.shownTitles.mapIndexed { i, title -> ClarificationChoice(title, i + 1) }
        val anchor = input.anchorTitle?.takeIf { input.hasAnchor }?.let { listOf(ClarificationChoice(it, null)) }.orEmpty()
        val choices = shown + anchor
        if (choices.isEmpty() || choices.size > MAX_CHOICES) return DecisionResult.NotApplicable
        return DecisionResult.NeedsClarification(ClarificationSlot.TARGET, question, choices, draft, anchorTarget = false)
    }

    // --- the small readings ---

    /** 「Xに『B』を」 / 「Xに」 → X and the body part (null = no body). Null result = no に-shape. */
    private fun splitTargetAndBody(head: String): Pair<String, String?>? {
        val quoted = QUOTED.find(head)
        // the に that separates target from body is the first one outside a quote
        val searchIn = if (quoted != null) head.substring(0, quoted.range.first) else head
        val ni = searchIn.indexOf('に')
        if (ni < 0) return null
        val target = head.substring(0, ni).trim()
        if (target.isEmpty()) return null
        val rest = head.substring(ni + 1).trim()
        if (rest.isEmpty()) return target to null
        if (!rest.endsWith("を")) return null
        val body = rest.removeSuffix("を").trim()
        return target to body.ifEmpty { null }
    }

    /** A body is a quoted span or one particle-free token — never a generic word, never a clause. */
    private fun bodyToken(raw: String): String? {
        val s = raw.trim()
        QUOTED.matchEntire(s)?.let { m -> return m.groupValues[1].trim().takeIf { it.isNotEmpty() && it.length <= 64 } }
        if (!PLAIN_TOKEN.matches(s)) return null
        if (s in GENERIC) return null
        return s
    }

    /** The position an ordinal names inside the shown results, or null (no ordinal, nothing shown, out of range). */
    private fun ordinalIndex(part: String, shownCount: Int): Int? {
        val m = ORDINAL_HEAD.matchEntire(part.removeSuffix("の").trim() + "の") ?: ORDINAL_HEAD.matchEntire(part.trim()) ?: return null
        if (shownCount <= 0) return null
        val index = when {
            m.groupValues[1].isNotEmpty() -> m.groupValues[1].toIntOrNull() ?: return null
            m.groupValues[2].isNotEmpty() || m.groupValues[4].isNotEmpty() -> 1
            else -> shownCount   // 最後
        }
        return index.takeIf { it in 1..shownCount }
    }

    private fun isBareDemonstrative(part: String): Boolean = DEMONSTRATIVE_HEAD.matchEntire(part.trim()) != null

    /** それ / これ agree with any anchor; さっきのメモ / このメモ must agree in kind; no anchor never agrees. */
    private fun anchorAgrees(part: String, input: DecisionInput): Boolean {
        if (!input.hasAnchor) return false
        val m = DEMONSTRATIVE_HEAD.matchEntire(part.trim()) ?: return false
        val named = kindOf(m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] })
        return named == null || named == input.anchorKind
    }

    private fun kindOf(word: String): DocumentKind? = when (word) {
        "メモ" -> DocumentKind.MEMO
        "日記" -> DocumentKind.JOURNAL
        "アウトライン" -> DocumentKind.OUTLINE
        else -> null
    }

    private fun dateOf(word: String): DateToken = if (word == "今日" || word == "きょう") DateToken.TODAY else DateToken.YESTERDAY

    private fun certain(proposal: IntentProposal, anchorTarget: Boolean = false): DecisionResult =
        DecisionResult.Operation(proposal, DecisionCertainty.CERTAIN, anchorTarget)

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).trim().trimEnd('。', '！', '!', '、').trim()
}

/** What the orchestrator's decide door hands the screen: a settled result of the ordinary pipeline, or the fixed question to ask. */
sealed interface DecisionOutcome {
    data class Settled(val result: io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult) : DecisionOutcome
    data class Clarify(
        val slot: ClarificationSlot,
        val question: String,
        val choices: List<ClarificationChoice>,
        val draft: IntentProposal,
        val anchorTarget: Boolean,
    ) : DecisionOutcome
}

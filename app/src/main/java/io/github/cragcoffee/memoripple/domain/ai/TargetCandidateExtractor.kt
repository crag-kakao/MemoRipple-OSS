package io.github.cragcoffee.memoripple.domain.ai

import java.text.Normalizer

/** Where a candidate came from — evidence, never a score. */
enum class CandidateSource {
    /** APPEND: the segment before the particle に, with any quoted append text left out. */
    BEFORE_PARTICLE_NI,
    /** OPEN: the segment before を開いて / を見せて / を表示して / を開く. */
    BEFORE_OPEN_VERB,
}

/** A possible document *name* taken from the user's own words. Not a document: the resolution step searches for it. */
data class TargetCandidate(val text: String, val source: CandidateSource)

/**
 * The deterministic target-candidate layer (docs/AI_TARGET_RESOLUTION.md). When the model gave
 * neither a shown ref nor a name for OPEN / APPEND, a few fixed Japanese shapes yield at most one
 * candidate name from the user's text — nothing more: no search, no ref, no fuzzy guess, no score.
 * The candidate is handed to the unchanged resolution step, which finds the
 * document (exact title first, then partial; one resolves, several ask, none stops), and the
 * write still ends at the preview and the Human Confirmation.
 *
 * Refused on purpose: demonstratives and references to earlier turns (これ / それ / 前のやつ …),
 * bare document and folder words (メモ / 日記 / アウトライン / ノート / フォルダ), date phrases
 * (今日の日記 …), and clauses carrying sentence particles (…は / …ので / …から …).
 */
object TargetCandidateExtractor {
    private const val MAX_CANDIDATE_CHARS = 64

    private val QUOTED = Regex("[『「\"“]([^』」\"”]*)[』」\"”]")
    private val APPEND_SHAPE = Regex("^(.+?)に(?:.*?)を?(?:追記|追加|書き足)(?:して|する|してください|して下さい|お願い)?[。!！]?$")
    private val OPEN_SHAPE = Regex("^(.+?)を(?:開いて|開く|開いてください|見せて|見せる|表示して|出して)[。!！]?$")

    private val DEMONSTRATIVES = Regex("^(これ|それ|あれ|こっち|そっち|さっきの(やつ|もの)?|前の(やつ|もの)?|上の(やつ|もの)?|最初の(やつ|もの)?|最後の(やつ|もの)?|[0-9０-９]+番目(の(やつ|もの)?)?|例の(やつ|もの)?)$")
    private val GENERIC_WORDS = setOf("メモ", "日記", "アウトライン", "ノート", "フォルダ", "フォルダー", "テンプレート", "記録", "ファイル", "文書", "ドキュメント", "やつ", "もの")
    private val DATE_WORDS = Regex("^(今日|きょう|昨日|きのう|明日|あした|一昨日|今週|先週|来週|今月|先月|来月|今年|去年|[0-9０-９]+月[0-9０-９]+日|[0-9０-９]+日)")
    private val WHITESPACE = Regex("[\\s\u3000]+")
    private val SENTENCE_PARTICLES = Regex("(は|が|ので|から|けど|けれど|ため|たら|ながら|して|、|。|,|\\.)")

    /** The proposal with a candidate filled in as `targetName` when the rules find one; otherwise the proposal as it is. */
    fun assist(userText: String, proposal: IntentProposal): IntentProposal {
        val candidate = extract(userText, proposal) ?: return proposal
        return proposal.copy(
            targetName = candidate.text,
            missingFields = proposal.missingFields - ProposalField.TARGET_NAME - ProposalField.TARGET_REF,
        )
    }

    /** At most one candidate, only for OPEN / APPEND without a target; null means "no safe name here". */
    fun extract(userText: String, proposal: IntentProposal): TargetCandidate? {
        if (proposal.targetRef != null || !proposal.targetName.isNullOrBlank()) return null
        val normalized = normalize(userText)
        return when (proposal.intent) {
            AiIntent.APPEND -> {
                val withoutQuotes = collapse(normalized.split(QUOTED).joinToString(""))
                APPEND_SHAPE.find(withoutQuotes)?.groupValues?.get(1)?.let { accept(it, CandidateSource.BEFORE_PARTICLE_NI) }
            }
            AiIntent.OPEN -> OPEN_SHAPE.find(normalized)?.groupValues?.get(1)?.let { accept(it, CandidateSource.BEFORE_OPEN_VERB) }
            else -> null
        }
    }

    private fun accept(raw: String, source: CandidateSource): TargetCandidate? {
        val text = collapse(raw)
        if (text.isEmpty() || text.length > MAX_CANDIDATE_CHARS) return null
        if (DEMONSTRATIVES.matches(text)) return null
        if (text in GENERIC_WORDS) return null
        if (SENTENCE_PARTICLES.containsMatchIn(text)) return null
        // a date word followed by a generic document word is a day, not a name (今日の日記, 明日のメモ, 9月19日の日記)
        val dateThenGeneric = DATE_WORDS.find(text)?.let { m -> text.substring(m.range.last + 1).removePrefix("の").trim() in GENERIC_WORDS + "予定" } ?: false
        if (dateThenGeneric) return null
        return TargetCandidate(text, source)
    }

    /** NFKC (full-width letters and spaces become their plain forms), trimmed, inner whitespace collapsed. */
    private fun normalize(s: String): String = collapse(Normalizer.normalize(s, Normalizer.Form.NFKC))

    private fun collapse(s: String): String = s.split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
}

package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import io.github.cragcoffee.memoripple.domain.documents.DocumentDateRange
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentSearchScope
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

/**
 * The product's AI safety boundary (docs/AI_SAFE_INTENT_PIPELINE.md). A model's answer becomes an
 * [IntentProposal]; nothing here can reach a document until the [SemanticValidator], the
 * [Resolver] and the [ExecutionPolicy] have each had their say:
 *
 *     LLM → IntentProposal → SemanticValidator → Resolver → Preview / Confirmation → DocumentAccess
 *
 * Phase 0 showed that a grammar can fix the *shape* of an answer and nothing else, so no type in
 * this package trusts a proposal: refs are opaque, dates are tokens, ids do not exist.
 */

/** The six v1 intents. Nothing destructive exists here, and [fromModel] cannot invent one. */
enum class AiIntent {
    SEARCH,
    OPEN,
    CREATE,
    APPEND,
    USE_TEMPLATE,
    UNKNOWN,
    ;

    companion object {
        /** The model's word for an intent; anything but the five executable names — DELETE, UPDATE, MOVE, a typo, nothing — is UNKNOWN. */
        fun fromModel(raw: String?): AiIntent {
            val name = raw?.trim()?.uppercase() ?: return UNKNOWN
            return entries.firstOrNull { it.name == name } ?: UNKNOWN
        }
    }
}

/**
 * The only reference a model may make: "result_N", the N-th line of what it was shown. It is a
 * number, not a document — [AiResultContext] turns it into a [io.github.cragcoffee.memoripple.domain.documents.DocumentRef]
 * for the current request only.
 */
@JvmInline
value class AiResultRef(val index: Int) {
    init { require(index in 1..99) { "a result ref is result_1 … result_99" } }

    val label: String get() = "result_$index"

    companion object {
        private val SHAPE = Regex("result_([1-9][0-9]?)")

        /** `result_N` for N in 1..99; anything else — a bare number, an id-like string — is nothing. */
        fun parse(raw: String?): AiResultRef? = raw?.trim()?.let { SHAPE.matchEntire(it) }?.let { AiResultRef(it.groupValues[1].toInt()) }
    }
}

/** A relative day the model may name; the app's clock, never the model, turns it into days. */
enum class DateToken {
    TODAY,
    YESTERDAY,
    THIS_WEEK,
    LAST_WEEK,
    ;

    companion object {
        /** The model's token, or null for anything that is not one — including an actual date. */
        fun fromModel(raw: String?): DateToken? = raw?.trim()?.uppercase()?.let { name -> entries.firstOrNull { it.name == name } }
    }
}

object DateTokens {
    /** Weeks run Monday..Sunday, the main calendar's rule (CLAUDE §4, チャット v0). */
    fun resolve(token: DateToken, time: TimeProvider): DocumentDateRange {
        val today = time.currentLocalDate()
        return when (token) {
            DateToken.TODAY -> DocumentDateRange.day(today)
            DateToken.YESTERDAY -> DocumentDateRange.day(today.minusDays(1))
            DateToken.THIS_WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { DocumentDateRange.of(it, it.plusDays(6)) }
            DateToken.LAST_WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1).let { DocumentDateRange.of(it, it.plusDays(6)) }
        }
    }
}

/** The fields a proposal can lack; a model may name them, the validator always checks them. */
enum class ProposalField { QUERY, TARGET_REF, TARGET_NAME, DOCUMENT_KIND, TEXT, TEMPLATE_ID }

/**
 * What a model proposes. Every value is the user's words or a token: [targetRef] is opaque,
 * [dateToken] is relative, there is no id and no date. `null` means "not said".
 */
data class IntentProposal(
    val intent: AiIntent,
    val query: String? = null,
    val targetRef: AiResultRef? = null,
    val targetName: String? = null,
    val documentKind: DocumentKind? = null,
    val text: String? = null,
    val templateId: String? = null,
    val dateToken: DateToken? = null,
    val missingFields: Set<ProposalField> = emptySet(),
) {
    companion object {
        val UNKNOWN = IntentProposal(AiIntent.UNKNOWN)
    }
}

/** What the AI path may reach: exactly the v1 document scope. Future Diary and everything outside it stay out. */
object AiDocumentScope {
    val kinds: Set<DocumentKind> = DocumentSearchScope.V1
}

package io.github.cragcoffee.memoripple.domain.ai

/** Why a proposal or a resolution cannot run. Shared by the validator and the resolver. */
enum class AiRejection {
    UNKNOWN_INTENT,
    UNBOUNDED_SEARCH,
    REF_NOT_IN_CONTEXT,
    BLANK_TEXT,
    KIND_MISMATCH,
    TARGET_READ_ONLY,
    TARGET_NOT_FOUND,
    FUTURE_DATE,
}

sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val reasons: List<AiRejection>) : ValidationResult
    data class NeedsInformation(val fields: Set<ProposalField>) : ValidationResult
}

/**
 * Judges a proposal against what the user said and what was shown — the semantic check a
 * grammar cannot do. It fills nothing in: a missing required field is a question for the user,
 * an impossible one is a rejection.
 */
object SemanticValidator {
    fun validate(proposal: IntentProposal, context: AiResultContext): ValidationResult {
        val reasons = ArrayList<AiRejection>()
        val missing = LinkedHashSet<ProposalField>()
        val ref = proposal.targetRef
        if (ref != null && ref !in context) reasons += AiRejection.REF_NOT_IN_CONTEXT
        val hasTarget = (ref != null && ref in context) || !proposal.targetName.isNullOrBlank()
        // Fast Path (docs/CHAT_FAST_PATH.md): 「昨日の日記を開いて」 — a day and a kind name a document as
        // well as a title does; the Resolver searches that day and still opens only a unique hit
        val hasDatedTarget = proposal.dateToken != null && proposal.documentKind != null
        val hasText = !proposal.text.isNullOrBlank()

        when (proposal.intent) {
            AiIntent.UNKNOWN -> reasons += AiRejection.UNKNOWN_INTENT
            AiIntent.SEARCH -> {
                val bounded = !proposal.query.isNullOrBlank() || proposal.dateToken != null || proposal.documentKind != null
                if (!bounded) reasons += AiRejection.UNBOUNDED_SEARCH
            }
            AiIntent.OPEN -> if (!hasTarget && ref == null && !hasDatedTarget) missing += ProposalField.TARGET_NAME
            AiIntent.CREATE -> if (proposal.documentKind == null) missing += ProposalField.DOCUMENT_KIND
            AiIntent.APPEND -> {
                if (!hasTarget && ref == null) missing += ProposalField.TARGET_NAME
                if (proposal.text == null) missing += ProposalField.TEXT
                else if (!hasText) reasons += AiRejection.BLANK_TEXT
            }
            AiIntent.USE_TEMPLATE -> if (proposal.templateId.isNullOrBlank()) missing += ProposalField.TEMPLATE_ID
        }
        // the model's own admission of a gap counts as one (never as a licence to guess) — unless the model gave the value
        // in the same answer (Phase 8, seen on the S20: a name in targetName and "targetName" in missingFields); a value
        // that is there is not a question, and nothing is guessed
        if (proposal.intent != AiIntent.UNKNOWN) missing += proposal.missingFields.filter { it.appliesTo(proposal.intent) && !provided(proposal, it, context) }

        return when {
            reasons.isNotEmpty() -> ValidationResult.Invalid(reasons)
            missing.isNotEmpty() -> ValidationResult.NeedsInformation(missing)
            else -> ValidationResult.Valid
        }
    }

    /** Whether the answer itself carries the field the model calls missing. */
    private fun provided(p: IntentProposal, field: ProposalField, context: AiResultContext): Boolean = when (field) {
        ProposalField.QUERY -> !p.query.isNullOrBlank()
        ProposalField.TARGET_REF -> p.targetRef != null && p.targetRef in context
        ProposalField.TARGET_NAME -> !p.targetName.isNullOrBlank()
        ProposalField.DOCUMENT_KIND -> p.documentKind != null
        ProposalField.TEXT -> !p.text.isNullOrBlank()
        ProposalField.TEMPLATE_ID -> !p.templateId.isNullOrBlank()
    }

    private fun ProposalField.appliesTo(intent: AiIntent): Boolean = when (this) {
        ProposalField.QUERY -> intent == AiIntent.SEARCH
        ProposalField.TARGET_REF, ProposalField.TARGET_NAME -> intent == AiIntent.OPEN || intent == AiIntent.APPEND
        ProposalField.DOCUMENT_KIND -> intent == AiIntent.CREATE
        ProposalField.TEXT -> intent == AiIntent.APPEND
        ProposalField.TEMPLATE_ID -> intent == AiIntent.USE_TEMPLATE
    }
}

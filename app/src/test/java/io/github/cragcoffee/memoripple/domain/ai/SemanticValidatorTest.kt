package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RED 10–20, 22: the validator judges the proposal against what was shown; it never guesses. */
class SemanticValidatorTest {
    private val shown = AiResultContext.of(
        listOf(
            DocumentSummary(DocumentRef(DocumentKind.MEMO, 7), "会議のメモ", 1, 1),
            DocumentSummary(DocumentRef(DocumentKind.MEMO, 8), "買い物リスト", 2, 2),
        ),
    )
    private val nothingShown = AiResultContext.EMPTY

    private fun valid(p: IntentProposal, ctx: AiResultContext = nothingShown) = assertEquals(ValidationResult.Valid, SemanticValidator.validate(p, ctx))
    private fun invalid(p: IntentProposal, reason: AiRejection, ctx: AiResultContext = nothingShown) {
        val r = SemanticValidator.validate(p, ctx)
        assertTrue("expected Invalid($reason) got $r", r is ValidationResult.Invalid && reason in r.reasons)
    }
    private fun needs(p: IntentProposal, field: ProposalField, ctx: AiResultContext = nothingShown) {
        val r = SemanticValidator.validate(p, ctx)
        assertTrue("expected NeedsInformation($field) got $r", r is ValidationResult.NeedsInformation && field in r.fields)
    }

    // 10, 11 SEARCH must be bounded
    @Test fun searchWithWordsIsValid() = valid(IntentProposal(AiIntent.SEARCH, query = "会議"))
    @Test fun searchWithOnlyADateTokenIsValid() = valid(IntentProposal(AiIntent.SEARCH, dateToken = DateToken.YESTERDAY, documentKind = DocumentKind.JOURNAL))
    @Test fun searchWithOnlyAKindIsValid() = valid(IntentProposal(AiIntent.SEARCH, documentKind = DocumentKind.MEMO))
    @Test fun emptySearchIsRejected() {
        invalid(IntentProposal(AiIntent.SEARCH), AiRejection.UNBOUNDED_SEARCH)
        invalid(IntentProposal(AiIntent.SEARCH, query = "   "), AiRejection.UNBOUNDED_SEARCH)
    }

    // 12–14 OPEN needs a target
    @Test fun openByShownRefIsValid() = valid(IntentProposal(AiIntent.OPEN, targetRef = AiResultRef(2)), shown)
    @Test fun openByNameIsValid() = valid(IntentProposal(AiIntent.OPEN, targetName = "会議のメモ"))
    @Test fun openWithoutAnyTargetNeedsInformation() = needs(IntentProposal(AiIntent.OPEN), ProposalField.TARGET_NAME)

    // 15 CREATE needs a kind
    @Test fun createNeedsAKind() {
        needs(IntentProposal(AiIntent.CREATE, text = "買い物 牛乳 卵"), ProposalField.DOCUMENT_KIND)
        valid(IntentProposal(AiIntent.CREATE, documentKind = DocumentKind.MEMO, text = "買い物 牛乳 卵"))
        valid(IntentProposal(AiIntent.CREATE, documentKind = DocumentKind.MEMO)) // an empty memo is a valid create (editor rule)
    }

    // 16–18 APPEND needs a target and a non-blank text
    @Test fun appendNeedsATarget() = needs(IntentProposal(AiIntent.APPEND, text = "Folder対応完了"), ProposalField.TARGET_NAME)
    @Test fun appendNeedsText() = needs(IntentProposal(AiIntent.APPEND, targetName = "MemoRipple開発"), ProposalField.TEXT)
    @Test fun blankAppendIsRejected() = invalid(IntentProposal(AiIntent.APPEND, targetName = "MemoRipple開発", text = "  \n "), AiRejection.BLANK_TEXT)
    @Test fun appendWithShownRefAndTextIsValid() = valid(IntentProposal(AiIntent.APPEND, targetRef = AiResultRef(2), text = "あとで電話"), shown)

    // 19 USE_TEMPLATE needs a template
    @Test fun useTemplateNeedsATemplateId() {
        needs(IntentProposal(AiIntent.USE_TEMPLATE), ProposalField.TEMPLATE_ID)
        valid(IntentProposal(AiIntent.USE_TEMPLATE, templateId = "daily_journal"))
    }

    // 20 UNKNOWN is never executable
    @Test fun unknownIsBlocked() {
        invalid(IntentProposal.UNKNOWN, AiRejection.UNKNOWN_INTENT)
        invalid(IntentProposal(AiIntent.UNKNOWN, query = "このメモを削除して", text = "削除"), AiRejection.UNKNOWN_INTENT)
    }

    // 22 a ref outside the shown list is rejected, not guessed
    @Test fun refNotInTheShownContextIsRejected() {
        invalid(IntentProposal(AiIntent.OPEN, targetRef = AiResultRef(3)), AiRejection.REF_NOT_IN_CONTEXT, shown)
        invalid(IntentProposal(AiIntent.OPEN, targetRef = AiResultRef(1)), AiRejection.REF_NOT_IN_CONTEXT, nothingShown)
        invalid(IntentProposal(AiIntent.APPEND, targetRef = AiResultRef(2), text = "x"), AiRejection.REF_NOT_IN_CONTEXT, nothingShown)
    }

    // a model that declares its own missing fields is taken at its word
    @Test fun declaredMissingFieldsBecomeNeedsInformation() =
        needs(IntentProposal(AiIntent.APPEND, text = "Folder対応完了", missingFields = setOf(ProposalField.TARGET_NAME)), ProposalField.TARGET_NAME)
}

/**
 * Phase 8 (seen on the S20): a model may give a value and, in the same answer, call that field
 * missing. The value is there, so it is not a question — and nothing is guessed: a field that is
 * really absent stays a question, and an unknown ref is still invalid.
 */
class SemanticValidatorSelfContradictionTest {
    @org.junit.Test
    fun aNameGivenAndDeclaredMissingIsAName() {
        val p = IntentProposal(AiIntent.OPEN, targetName = "MemoRipple開発", missingFields = setOf(ProposalField.TARGET_NAME))
        org.junit.Assert.assertEquals(ValidationResult.Valid, SemanticValidator.validate(p, AiResultContext.EMPTY))
        val append = IntentProposal(AiIntent.APPEND, targetName = "MemoRipple開発", text = "完了", missingFields = setOf(ProposalField.TARGET_NAME, ProposalField.TEXT))
        org.junit.Assert.assertEquals(ValidationResult.Valid, SemanticValidator.validate(append, AiResultContext.EMPTY))
    }

    @org.junit.Test
    fun aFieldReallyAbsentStaysAQuestionAndABlankValueIsNotAValue() {
        val p = IntentProposal(AiIntent.OPEN, targetName = "  ", missingFields = setOf(ProposalField.TARGET_NAME))
        org.junit.Assert.assertEquals(ValidationResult.NeedsInformation(setOf(ProposalField.TARGET_NAME)), SemanticValidator.validate(p, AiResultContext.EMPTY))
        val append = IntentProposal(AiIntent.APPEND, targetName = "MemoRipple開発", text = null, missingFields = setOf(ProposalField.TEXT))
        org.junit.Assert.assertEquals(ValidationResult.NeedsInformation(setOf(ProposalField.TEXT)), SemanticValidator.validate(append, AiResultContext.EMPTY))
    }

    @org.junit.Test
    fun aRefOutsideTheShownListIsStillInvalidEvenIfDeclaredMissing() {
        val p = IntentProposal(AiIntent.OPEN, targetRef = AiResultRef(3), missingFields = setOf(ProposalField.TARGET_REF))
        org.junit.Assert.assertTrue(SemanticValidator.validate(p, AiResultContext.EMPTY) is ValidationResult.Invalid)
    }
}

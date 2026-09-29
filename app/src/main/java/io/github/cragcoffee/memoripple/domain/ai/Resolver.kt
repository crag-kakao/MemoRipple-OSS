package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import io.github.cragcoffee.memoripple.domain.documents.DocumentAccess
import io.github.cragcoffee.memoripple.domain.documents.DocumentCreate
import io.github.cragcoffee.memoripple.domain.documents.CreateDestination
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentMetadata
import io.github.cragcoffee.memoripple.domain.documents.DocumentQuery
import io.github.cragcoffee.memoripple.domain.documents.DocumentReadResult
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import java.text.Normalizer

/** Where templates come from; the data layer adapts TemplateRepository to it. */
interface TemplateLookup {
    /** By id first, then by name; null when there is no such template. */
    suspend fun find(idOrName: String): MemoTemplate?
}

/**
 * The version a write must find in place. Today it is backed by `updatedAt`; the API treats it
 * as an opaque token so a revision column can replace it without touching the pipeline.
 */
@JvmInline
value class DocumentVersion(val token: Long)

/** A command with real references — the resolver's output, the first place a DocumentRef appears. */
sealed interface ResolvedCommand {
    data class Search(val query: DocumentQuery) : ResolvedCommand
    data class Open(val target: DocumentSummary) : ResolvedCommand
    data class Create(val kind: DocumentKind, val request: DocumentCreate, val initialText: String?, val folderName: String? = null) : ResolvedCommand
    data class Append(val target: DocumentSummary, val text: String, val expectedVersion: DocumentVersion, val currentBody: String) : ResolvedCommand
    /** A template run: the body already rendered (Template v2) and the document it makes; a legacy template renders to its body as it is. */
    data class UseTemplate(val template: MemoTemplate, val renderedBody: String, val request: DocumentCreate, val folderName: String? = null) : ResolvedCommand {
        constructor(template: MemoTemplate) : this(template, template.body, DocumentCreate.Memo())

        val kind: DocumentKind
            get() = when (request) {
                is DocumentCreate.Memo -> DocumentKind.MEMO
                is DocumentCreate.Outline -> DocumentKind.OUTLINE
                is DocumentCreate.Journal -> DocumentKind.JOURNAL
            }
    }
}

sealed interface ResolutionResult {
    data class Resolved(val command: ResolvedCommand) : ResolutionResult
    /** Several documents fit the name; none is picked — the user chooses. */
    data class Ambiguous(val candidates: List<DocumentSummary>) : ResolutionResult
    data class NotFound(val field: ProposalField) : ResolutionResult
    data class NeedsInformation(val fields: Set<ProposalField>) : ResolutionResult
    data class Blocked(val reasons: List<AiRejection>) : ResolutionResult
}

/**
 * Turns a valid proposal into a [ResolvedCommand] through the Document boundary only: a shown
 * `result_N` through the context, a name through [DocumentAccess.search], a token through the
 * app's clock, a template through [TemplateLookup]. It never writes and never picks among equals.
 */
class Resolver(
    private val documents: DocumentAccess,
    /** Exposed for the template runner (Template v2): the same lookup and the same clock, one source of truth. */
    val templates: TemplateLookup,
    val time: TimeProvider,
) {
    /** [destination]: the user's chosen folder for a CREATE of a memo or an outline (2026-09-22); a journal ignores it; nothing else reads it. */
    suspend fun resolve(proposal: IntentProposal, context: AiResultContext, destination: CreateDestination? = null): ResolutionResult {
        when (val v = SemanticValidator.validate(proposal, context)) {
            is ValidationResult.Invalid -> return ResolutionResult.Blocked(v.reasons)
            is ValidationResult.NeedsInformation -> return ResolutionResult.NeedsInformation(v.fields)
            ValidationResult.Valid -> Unit
        }
        return when (proposal.intent) {
            AiIntent.SEARCH -> ResolutionResult.Resolved(ResolvedCommand.Search(searchQuery(proposal)))
            AiIntent.OPEN -> target(proposal, context) { ResolutionResult.Resolved(ResolvedCommand.Open(it)) }
            AiIntent.APPEND -> target(proposal, context) { append(it, proposal.text!!.trim()) }
            AiIntent.CREATE -> create(proposal, destination)
            AiIntent.USE_TEMPLATE -> templates.find(proposal.templateId!!.trim())
                ?.let { ResolutionResult.Resolved(ResolvedCommand.UseTemplate(it)) }
                ?: ResolutionResult.NotFound(ProposalField.TEMPLATE_ID)
            AiIntent.UNKNOWN -> ResolutionResult.Blocked(listOf(AiRejection.UNKNOWN_INTENT))
        }
    }

    private fun searchQuery(p: IntentProposal): DocumentQuery = DocumentQuery(
        text = p.query?.trim().orEmpty(),
        kinds = p.documentKind?.let { setOf(it) } ?: AiDocumentScope.kinds,
        dateRange = p.dateToken?.let { DateTokens.resolve(it, time) },
    )

    /** The target by shown ref, else by name, else by a day and a kind (Fast Path): one match resolves, several ask, none is not found; the kind must agree. */
    private suspend inline fun target(p: IntentProposal, context: AiResultContext, then: (DocumentSummary) -> ResolutionResult): ResolutionResult {
        if (p.targetRef == null && p.targetName == null && p.dateToken != null && p.documentKind != null) {
            // 「昨日の日記を開いて」: the day's documents of that kind — the same 0 / 1 / many rule as a name
            val hits = documents.search(DocumentQuery(text = "", kinds = setOf(p.documentKind), dateRange = DateTokens.resolve(p.dateToken, time), limit = 20))
            return when (hits.size) {
                0 -> ResolutionResult.NotFound(ProposalField.TARGET_NAME)
                1 -> then(hits.single())
                else -> ResolutionResult.Ambiguous(hits)
            }
        }
        val summary: DocumentSummary = if (p.targetRef != null) {
            val shown = context.summary(p.targetRef) ?: return ResolutionResult.Blocked(listOf(AiRejection.REF_NOT_IN_CONTEXT))
            // Phase 8: a shown result may have been shown a while ago (the conversation's stored results) — the
            // document is re-read through the boundary, so a deleted one is NotFound, never opened or appended to
            when (val read = documents.get(shown.ref)) {
                is DocumentReadResult.Found -> read.content.summary
                DocumentReadResult.NotFound -> return ResolutionResult.NotFound(ProposalField.TARGET_REF)
            }
        } else {
            val name = p.targetName!!.trim()
            // The name is looked up across the whole AI scope so that a document of the wrong kind is
            // reported as a mismatch, not silently "not found".
            val hits = documents.search(DocumentQuery(text = name, kinds = AiDocumentScope.kinds, limit = 20))
            val exact = hits.filter { fold(it.title) == fold(name) }
            val named = if (exact.isNotEmpty()) exact else hits
            val ofKind = p.documentKind?.let { k -> named.filter { it.ref.kind == k } }
            if (ofKind != null && ofKind.isEmpty() && named.isNotEmpty()) return ResolutionResult.Blocked(listOf(AiRejection.KIND_MISMATCH))
            val candidates = ofKind ?: named
            when (candidates.size) {
                0 -> return ResolutionResult.NotFound(ProposalField.TARGET_NAME)
                1 -> candidates.single()
                else -> return ResolutionResult.Ambiguous(candidates)
            }
        }
        if (p.documentKind != null && p.documentKind != summary.ref.kind) return ResolutionResult.Blocked(listOf(AiRejection.KIND_MISMATCH))
        return then(summary)
    }

    private suspend fun append(target: DocumentSummary, text: String): ResolutionResult {
        val content = when (val r = documents.get(target.ref)) {
            is DocumentReadResult.Found -> r.content
            DocumentReadResult.NotFound -> return ResolutionResult.Blocked(listOf(AiRejection.TARGET_NOT_FOUND))
        }
        val metadata = content.metadata
        if (metadata is DocumentMetadata.Journal && !metadata.editable) return ResolutionResult.Blocked(listOf(AiRejection.TARGET_READ_ONLY))
        return ResolutionResult.Resolved(
            ResolvedCommand.Append(content.summary, text, DocumentVersion(content.summary.updatedAt), content.body),
        )
    }

    /**
     * The end of [ref] as the place for [text] (「メモを選択」, 2026-09-27): the memo re-read through the boundary
     * and its version taken — the same append as a named target's. Gone → NotFound; nothing to add → a question.
     */
    suspend fun appendTo(ref: DocumentRef, text: String?): ResolutionResult {
        val words = text?.trim().orEmpty()
        if (words.isEmpty()) return ResolutionResult.NeedsInformation(setOf(ProposalField.TEXT))
        val summary = when (val read = documents.get(ref)) {
            is DocumentReadResult.Found -> read.content.summary
            DocumentReadResult.NotFound -> return ResolutionResult.NotFound(ProposalField.TARGET_REF)
        }
        return append(summary, words)
    }

    /**
     * A journal is made for today, or yesterday when the token says so — never for a day the clock has not reached.
     * A memo, while one is selected with 「メモを選択」, is not made: its words go to the end of the selected memo.
     */
    private suspend fun create(p: IntentProposal, destination: CreateDestination?): ResolutionResult {
        val kind = p.documentKind!!
        destination?.selectedMemo?.let { selected -> if (kind == DocumentKind.MEMO) return appendTo(selected, p.text) }
        val request = when (kind) {
            DocumentKind.MEMO -> DocumentCreate.Memo(destination?.folderId)
            DocumentKind.OUTLINE -> DocumentCreate.Outline(destination?.folderId)
            DocumentKind.JOURNAL -> DocumentCreate.Journal(
                when (p.dateToken) {
                    DateToken.YESTERDAY -> time.currentLocalDate().minusDays(1)
                    else -> time.currentLocalDate()
                },
            )
        }
        return ResolutionResult.Resolved(ResolvedCommand.Create(kind, request, p.text?.trim()?.takeIf { it.isNotEmpty() }, destination?.name?.takeIf { kind != DocumentKind.JOURNAL }))
    }

    private fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC).trim().lowercase()
}

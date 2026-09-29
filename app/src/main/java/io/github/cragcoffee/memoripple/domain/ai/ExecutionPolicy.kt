package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.documents.DocumentCreate
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import java.time.LocalDate

/** What the user is shown before a write. UI-free; a screen renders it, a test reads it. */
sealed interface CommandPreview {
    /** [folderName]: the chat's chosen folder, shown as 「保存先」 (2026-09-22); null = the root, as before. */
    data class Create(val kind: DocumentKind, val initialText: String?, val journalDate: LocalDate?, val folderName: String? = null) : CommandPreview
    data class Append(val target: DocumentSummary, val text: String, val expectedVersion: DocumentVersion, val currentBody: String) : CommandPreview
    /** The template's body as it is: no field is filled in, because the user filled none. */
    data class Template(val template: MemoTemplate, val renderedBody: String, val kind: DocumentKind = DocumentKind.MEMO, val journalDate: LocalDate? = null, val folderName: String? = null) : CommandPreview
}

/** Anything a model says about itself. The policy accepts it so that it can be seen to ignore it. */
data class ModelSignal(val confidence: Double)

sealed interface ExecutionDecision {
    /** A read: runs at once. */
    data class Direct(val command: ResolvedCommand) : ExecutionDecision

    /** A write: runs only as the [ConfirmedCommand] the user's confirmation produces. */
    data class RequiresConfirmation(val command: ResolvedCommand, val preview: CommandPreview) : ExecutionDecision {
        /** Called by the confirmation step after the user said yes — the only way to a write. */
        fun confirm(): ConfirmedCommand = ConfirmedCommand(command, preview)
    }

    /** Nothing runs. */
    sealed interface Blocked : ExecutionDecision {
        data class Rejected(val reasons: List<AiRejection>) : Blocked
        data class NeedsInformation(val fields: Set<ProposalField>) : Blocked
        data class Ambiguous(val candidates: List<DocumentSummary>) : Blocked
        data class NotFound(val field: ProposalField) : Blocked
    }
}

/** A write the user has confirmed, still carrying the version the preview was built from. */
class ConfirmedCommand internal constructor(val command: ResolvedCommand, val preview: CommandPreview)

/**
 * SEARCH and OPEN run directly; CREATE, APPEND and USE_TEMPLATE always go through a preview and
 * the user's confirmation; UNKNOWN and everything unresolved is blocked. A model's confidence is
 * accepted and ignored: it never shortens the path.
 */
object ExecutionPolicy {
    @Suppress("UNUSED_PARAMETER")
    fun decide(resolution: ResolutionResult, signal: ModelSignal? = null): ExecutionDecision = when (resolution) {
        is ResolutionResult.Blocked -> ExecutionDecision.Blocked.Rejected(resolution.reasons)
        is ResolutionResult.NeedsInformation -> ExecutionDecision.Blocked.NeedsInformation(resolution.fields)
        is ResolutionResult.Ambiguous -> ExecutionDecision.Blocked.Ambiguous(resolution.candidates)
        is ResolutionResult.NotFound -> ExecutionDecision.Blocked.NotFound(resolution.field)
        is ResolutionResult.Resolved -> when (val c = resolution.command) {
            is ResolvedCommand.Search, is ResolvedCommand.Open -> ExecutionDecision.Direct(c)
            is ResolvedCommand.Create -> ExecutionDecision.RequiresConfirmation(
                c, CommandPreview.Create(c.kind, c.initialText, (c.request as? DocumentCreate.Journal)?.date, c.folderName),
            )
            is ResolvedCommand.Append -> ExecutionDecision.RequiresConfirmation(
                c, CommandPreview.Append(c.target, c.text, c.expectedVersion, c.currentBody),
            )
            is ResolvedCommand.UseTemplate -> ExecutionDecision.RequiresConfirmation(
                c, CommandPreview.Template(c.template, c.renderedBody, c.kind, (c.request as? DocumentCreate.Journal)?.date, c.folderName),
            )
        }
    }
}

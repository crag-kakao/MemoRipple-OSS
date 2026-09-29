package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.documents.DocumentAccess
import io.github.cragcoffee.memoripple.domain.documents.DocumentCreate
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary
import io.github.cragcoffee.memoripple.domain.documents.DocumentWriteResult

sealed interface ExecutionResult {
    data class Searched(val results: List<DocumentSummary>) : ExecutionResult
    /** The document to open; the UI's navigator takes it from here. */
    data class Opened(val ref: DocumentRef) : ExecutionResult
    data class Written(val ref: DocumentRef, val version: DocumentVersion) : ExecutionResult
    /** The version moved between preview and confirmation; nothing was written. */
    data object Conflict : ExecutionResult
    data object NotFound : ExecutionResult
    data object ReadOnly : ExecutionResult
    data class Rejected(val reason: String) : ExecutionResult
}

/**
 * The last stage: runs a [ExecutionDecision.Direct] read, or a [ConfirmedCommand] write, through
 * [DocumentAccess] — `create` and `append` are the only calls it knows. There is no method for an
 * unconfirmed write.
 */
class CommandExecutor(private val documents: DocumentAccess) {
    suspend fun execute(direct: ExecutionDecision.Direct): ExecutionResult = when (val c = direct.command) {
        is ResolvedCommand.Search -> ExecutionResult.Searched(if (c.query.isBounded) documents.search(c.query) else emptyList())
        is ResolvedCommand.Open -> ExecutionResult.Opened(c.target.ref)
        else -> error("a write is never Direct")
    }

    suspend fun execute(confirmed: ConfirmedCommand): ExecutionResult = when (val c = confirmed.command) {
        is ResolvedCommand.Append -> documents.append(c.target.ref, c.text, c.expectedVersion.token).toResult()
        is ResolvedCommand.Create -> createThenFill(c.request, c.initialText)
        is ResolvedCommand.UseTemplate -> createThenFill(c.request, c.renderedBody)
        is ResolvedCommand.Search, is ResolvedCommand.Open -> error("a read is never confirmed")
    }

    /** The boundary makes an empty document; the first text goes in as an append against the fresh version. */
    private suspend fun createThenFill(request: DocumentCreate, text: String?): ExecutionResult {
        val created = documents.create(request)
        if (created !is DocumentWriteResult.Done) return created.toResult()
        if (text.isNullOrBlank()) return ExecutionResult.Written(created.ref, DocumentVersion(created.updatedAt))
        return documents.append(created.ref, text, created.updatedAt).toResult()
    }

    private fun DocumentWriteResult.toResult(): ExecutionResult = when (this) {
        is DocumentWriteResult.Done -> ExecutionResult.Written(ref, DocumentVersion(updatedAt))
        DocumentWriteResult.Conflict -> ExecutionResult.Conflict
        DocumentWriteResult.NotFound -> ExecutionResult.NotFound
        DocumentWriteResult.ReadOnly -> ExecutionResult.ReadOnly
        is DocumentWriteResult.Rejected -> ExecutionResult.Rejected(reason)
    }
}

package io.github.cragcoffee.memoripple.domain.documents

import java.time.LocalDate

/**
 * The boundary Search, Calendar and Chat use to read and write documents. It sits on the
 * existing repositories (memo, diary) and hands out domain types only — no entity, no DAO
 * crosses it, so nothing above it can reach Room. Chat's path is
 * Chat UI → IntentProposal → Resolver → this → repository → Room, never shorter.
 */
interface DocumentAccess : DocumentReader, DocumentWriter, DocumentSearch

interface DocumentReader {
    /** The document [ref] names, or [DocumentReadResult.NotFound] — a ref whose kind disagrees with the row is not found either. */
    suspend fun get(ref: DocumentRef): DocumentReadResult
}

sealed interface DocumentReadResult {
    data class Found(val content: DocumentContent) : DocumentReadResult
    data object NotFound : DocumentReadResult
}

interface DocumentWriter {
    /** A new, empty document of the kind, made by the kind's own repository call. */
    suspend fun create(request: DocumentCreate): DocumentWriteResult

    /**
     * Adds [text] at the end of the document — the only write Chat v1 may do. A memo or a
     * journal gets the text as new trailing lines; an outline gets exactly one new root node
     * (a newline in [text] becomes a space). Nothing is inserted elsewhere, replaced or removed.
     *
     * [expectedUpdatedAt] is the `updatedAt` the caller last read: the write happens only if the
     * row still carries it, inside one repository transaction; otherwise [DocumentWriteResult.Conflict]
     * and the body is untouched. A LOCKED journal answers [DocumentWriteResult.ReadOnly].
     */
    suspend fun append(ref: DocumentRef, text: String, expectedUpdatedAt: Long): DocumentWriteResult
}

/**
 * Where a document the chat creates goes (2026-09-22): one of the wall's folders, chosen by the user above
 * the input — never by a model, never by a template. A journal has no folder and ignores it. [name] is for
 * the preview's 「保存先」 line; the id is resolved again at the boundary (a vanished folder → the root).
 *
 * [selectedMemo] (2026-09-27, docs/CHAT_MEMO_CONTEXT.md §7): the memo chosen with 「メモを選択」 — while one is
 * chosen, a memo the chat would create is appended to the end of that memo instead (the Resolver's own append —
 * the memo re-read, its version taken, the Append preview, the one confirmation); the folder is not used then.
 * An outline or a journal is still made. The model never sees it.
 */
data class CreateDestination(val folderId: Long? = null, val name: String? = null, val selectedMemo: DocumentRef? = null)

sealed interface DocumentCreate {
    data class Memo(val folderId: Long? = null) : DocumentCreate
    data class Outline(val folderId: Long? = null) : DocumentCreate
    /** A journal for [date]; several may share a day; a future day is refused. */
    data class Journal(val date: LocalDate) : DocumentCreate
}

sealed interface DocumentWriteResult {
    data class Done(val ref: DocumentRef, val updatedAt: Long) : DocumentWriteResult
    data object NotFound : DocumentWriteResult
    data object ReadOnly : DocumentWriteResult
    data object Conflict : DocumentWriteResult
    data class Rejected(val reason: String) : DocumentWriteResult
}

/**
 * Search over documents. **v1 scope: standalone memos, outlines and journals** — [searchableKinds]
 * says so in code; note episodes, archived and trashed rows and future comments are not reached
 * (deliberate exclusions, docs/JOURNAL_SEARCH_AUDIT.md). The matching is
 * [io.github.cragcoffee.memoripple.domain.memos.MemoSearch]'s, in memory; no FTS.
 */
interface DocumentSearch {
    /** What this implementation reaches; v1 is [DocumentSearchScope.V1]. */
    val searchableKinds: Set<DocumentKind>

    /** Summaries of the documents matching [query], newest updated first, at most [DocumentQuery.limit]. */
    suspend fun search(query: DocumentQuery): List<DocumentSummary>
}

/**
 * [text] uses MemoSearch's syntax (terms, "phrase", #tag, -not). [kinds] narrows within the
 * searchable kinds; a kind outside them contributes nothing. [folderId] narrows memos and
 * outlines to one folder (null = anywhere) and leaves journals out — they are not in folders.
 * [dateRange] selects journals by their diary day and memos / outlines by the local day they
 * were made or last written; the caller turns "yesterday" or "last week" into days with
 * TimeProvider — never the LLM.
 *
 * A query with blank [text] and no [dateRange] finds nothing: there is no unbounded "everything"
 * search ([isBounded]).
 */
data class DocumentQuery(
    val text: String = "",
    val kinds: Set<DocumentKind> = DocumentSearchScope.V1,
    val folderId: Long? = null,
    val dateRange: DocumentDateRange? = null,
    val limit: Int = 50,
) {
    /** Whether the query asks for something: words, or at least a date range. */
    val isBounded: Boolean get() = text.isNotBlank() || dateRange != null
}

/** Inclusive epoch days, as `LocalDate.toEpochDay()` gives them; zone-free like a diary day. */
data class DocumentDateRange(val startEpochDay: Long, val endEpochDayInclusive: Long) {
    init { require(startEpochDay <= endEpochDayInclusive) { "a range ends after it starts" } }
    operator fun contains(epochDay: Long): Boolean = epochDay in startEpochDay..endEpochDayInclusive

    companion object {
        fun day(date: LocalDate): DocumentDateRange = DocumentDateRange(date.toEpochDay(), date.toEpochDay())
        fun of(start: LocalDate, endInclusive: LocalDate): DocumentDateRange = DocumentDateRange(start.toEpochDay(), endInclusive.toEpochDay())
    }
}

/** The search scope by version, written down so a reader never assumes more than is there. */
object DocumentSearchScope {
    /** v1: standalone memos, outlines and journals. Note episodes, archive, trash and future comments stay out. */
    val V1: Set<DocumentKind> = setOf(DocumentKind.MEMO, DocumentKind.OUTLINE, DocumentKind.JOURNAL)
}

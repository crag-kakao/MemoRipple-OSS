package io.github.cragcoffee.memoripple.domain.documents

import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.memos.MemoDestination
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.domain.memos.destination
import java.time.LocalDate

/**
 * The document vocabulary that Calendar, Folder, Search and Chat share (docs/DOCUMENT_BOUNDARY_AUDIT.md).
 *
 * Three kinds, no more: a memo and an outline are `memos` rows told apart by [MemoKind] — the
 * table's own discriminator, which stays where it is — and a journal is a `diary_entries` row.
 * A note is a container of memos, a template is a starting point, a future comment is a child
 * of a journal with its own lifecycle: none of them is a document kind.
 */
enum class DocumentKind {
    MEMO,
    OUTLINE,
    JOURNAL,
    ;

    companion object {
        /** The one bridge from the memo table's own kind; journals never come this way. */
        fun of(kind: MemoKind): DocumentKind = when (kind) {
            MemoKind.MEMO -> MEMO
            MemoKind.OUTLINE -> OUTLINE
        }
    }
}

/** The memo kind a document kind stands for on the `memos` table, or null for a journal. */
val DocumentKind.memoKind: MemoKind?
    get() = when (this) {
        DocumentKind.MEMO -> MemoKind.MEMO
        DocumentKind.OUTLINE -> MemoKind.OUTLINE
        DocumentKind.JOURNAL -> null
    }

/**
 * Names one document: the kind says which table and which screen, the id is that table's own.
 * No global id exists or is needed — `(MEMO, 5)` and `(JOURNAL, 5)` are different documents, and
 * `(OUTLINE, 5)` names row 5 of `memos` only if that row's kind agrees.
 */
data class DocumentRef(val kind: DocumentKind, val id: Long)

/** What a list, a search result or a calendar row needs: enough to show and to open. */
data class DocumentSummary(
    val ref: DocumentRef,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Per-kind facts a reader may need; deliberately small. */
sealed interface DocumentMetadata {
    data class Memo(val folderId: Long?, val noteId: Long?) : DocumentMetadata
    data class Outline(val folderId: Long?) : DocumentMetadata
    /** [editable] is [io.github.cragcoffee.memoripple.domain.diary.DiaryStatePolicy.canEdit] of [state]. */
    data class Journal(val date: LocalDate, val state: DiaryState, val editable: Boolean) : DocumentMetadata
}

/** A whole document as the boundary hands it out — never a Room entity. */
data class DocumentContent(
    val summary: DocumentSummary,
    val body: String,
    val metadata: DocumentMetadata,
) {
    val ref: DocumentRef get() = summary.ref
    val title: String get() = summary.title
}

/**
 * Where a document opens — the kind of screen, never a route string. The UI's navigator turns
 * this into a route; the domain does not know that routes exist.
 */
sealed interface DocumentDestination {
    data class MemoEditor(val memoId: Long) : DocumentDestination
    data class Outliner(val memoId: Long) : DocumentDestination
    data class Journal(val entryId: Long) : DocumentDestination
}

fun DocumentRef.destination(): DocumentDestination = when (kind) {
    DocumentKind.MEMO, DocumentKind.OUTLINE -> when (requireNotNull(kind.memoKind).destination) {
        MemoDestination.EDITOR -> DocumentDestination.MemoEditor(id)
        MemoDestination.OUTLINER -> DocumentDestination.Outliner(id)
    }
    DocumentKind.JOURNAL -> DocumentDestination.Journal(id)
}

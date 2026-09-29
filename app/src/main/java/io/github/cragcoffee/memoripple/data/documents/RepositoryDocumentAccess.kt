package io.github.cragcoffee.memoripple.data.documents

import androidx.room.withTransaction
import io.github.cragcoffee.memoripple.data.AppDatabase
import io.github.cragcoffee.memoripple.data.DiaryAppendResult
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.DiaryRepository
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoRepository
import io.github.cragcoffee.memoripple.data.TagRepository
import io.github.cragcoffee.memoripple.domain.diary.DiaryStatePolicy
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import io.github.cragcoffee.memoripple.domain.documents.DocumentAccess
import io.github.cragcoffee.memoripple.domain.documents.DocumentContent
import io.github.cragcoffee.memoripple.domain.documents.DocumentCreate
import io.github.cragcoffee.memoripple.domain.documents.DocumentDateRange
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentMetadata
import io.github.cragcoffee.memoripple.domain.documents.DocumentQuery
import io.github.cragcoffee.memoripple.domain.documents.DocumentReadResult
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSearchScope
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary
import io.github.cragcoffee.memoripple.domain.documents.DocumentTitles
import io.github.cragcoffee.memoripple.domain.documents.DocumentWriteResult
import io.github.cragcoffee.memoripple.domain.memos.MemoSearch
import io.github.cragcoffee.memoripple.domain.memos.memoKind
import io.github.cragcoffee.memoripple.domain.outline.OutlineEditing
import io.github.cragcoffee.memoripple.domain.outline.OutlineText
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * [DocumentAccess] over the existing repositories. Memos and outlines are [MemoRepository]'s,
 * journals [DiaryRepository]'s; this class adds no write of its own — it chooses the call, checks
 * the expected `updatedAt` inside the database transaction, and translates rows into domain types.
 * Nothing here is reachable from a screen yet; it is the door Chat's Resolver will get.
 */
class RepositoryDocumentAccess(
    private val database: AppDatabase,
    private val memos: MemoRepository,
    private val diary: DiaryRepository,
    private val tags: TagRepository,
    private val timeProvider: TimeProvider,
) : DocumentAccess {
    override val searchableKinds: Set<DocumentKind> = DocumentSearchScope.V1

    // --- read ---

    override suspend fun get(ref: DocumentRef): DocumentReadResult = when (ref.kind) {
        DocumentKind.MEMO, DocumentKind.OUTLINE -> memoRow(ref)?.let { DocumentReadResult.Found(content(it)) }
            ?: DocumentReadResult.NotFound
        DocumentKind.JOURNAL -> diary.findById(ref.id)?.let { DocumentReadResult.Found(content(it)) }
            ?: DocumentReadResult.NotFound
    }

    /** The memos row [ref] names — only if its stored kind is the kind the ref claims. */
    private suspend fun memoRow(ref: DocumentRef): MemoEntity? =
        memos.findById(ref.id)?.takeIf { DocumentKind.of(it.memoKind) == ref.kind }

    private fun summary(memo: MemoEntity): DocumentSummary {
        val kind = DocumentKind.of(memo.memoKind)
        return DocumentSummary(
            ref = DocumentRef(kind, memo.id),
            title = DocumentTitles.resolve(memo.title, memo.body, DocumentTitles.placeholder(kind)),
            createdAt = memo.createdAt,
            updatedAt = memo.updatedAt,
        )
    }

    private fun content(memo: MemoEntity): DocumentContent {
        val summary = summary(memo)
        val metadata = when (summary.ref.kind) {
            DocumentKind.OUTLINE -> DocumentMetadata.Outline(folderId = memo.folderId)
            else -> DocumentMetadata.Memo(folderId = memo.folderId, noteId = memo.noteId)
        }
        return DocumentContent(summary, memo.body, metadata)
    }

    private fun summary(entry: DiaryEntryEntity) = DocumentSummary(
        ref = DocumentRef(DocumentKind.JOURNAL, entry.id),
        title = DocumentTitles.resolve("", entry.body, DocumentTitles.EMPTY_JOURNAL),
        createdAt = entry.createdAt,
        updatedAt = entry.updatedAt,
    )

    // The journal's future comments are a separate lifecycle with hidden text: they are not
    // part of the document and never appear here, not even as a count.
    private fun content(entry: DiaryEntryEntity) = DocumentContent(
        summary = summary(entry),
        body = entry.body,
        metadata = DocumentMetadata.Journal(
            date = LocalDate.ofEpochDay(entry.diaryDateEpochDay),
            state = entry.state,
            editable = DiaryStatePolicy.canEdit(entry.state),
        ),
    )

    // --- write ---

    /** A folder that no longer exists is no folder: the document goes to the root rather than into a dangling id (the chat's chosen folder, 2026-09-22). */
    private suspend fun existingFolder(folderId: Long?): Long? = folderId?.takeIf { database.folderDao().findById(it) != null }

    override suspend fun create(request: DocumentCreate): DocumentWriteResult = when (request) {
        is DocumentCreate.Memo -> memos.createEmpty(timeProvider.nowMillis(), existingFolder(request.folderId))
            .let { DocumentWriteResult.Done(DocumentRef(DocumentKind.MEMO, it.id), it.updatedAt) }
        is DocumentCreate.Outline -> memos.createOutline(timeProvider.nowMillis(), existingFolder(request.folderId))
            .let { DocumentWriteResult.Done(DocumentRef(DocumentKind.OUTLINE, it.id), it.updatedAt) }
        is DocumentCreate.Journal -> {
            // The same rule as every screen: a journal is written on its day or later, never ahead.
            if (request.date.isAfter(diary.currentDate())) {
                DocumentWriteResult.Rejected("a journal cannot be written for a future day")
            } else {
                diary.createEntry(request.date.toEpochDay())
                    .let { DocumentWriteResult.Done(DocumentRef(DocumentKind.JOURNAL, it.id), it.updatedAt) }
            }
        }
    }

    override suspend fun append(ref: DocumentRef, text: String, expectedUpdatedAt: Long): DocumentWriteResult =
        when (ref.kind) {
            DocumentKind.MEMO -> appendToMemo(ref, expectedUpdatedAt) { body -> joinLines(body, text) }
            DocumentKind.OUTLINE -> appendToMemo(ref, expectedUpdatedAt) { body -> appendRootNode(body, text) }
            DocumentKind.JOURNAL -> appendToJournal(ref, text, expectedUpdatedAt)
        }

    /**
     * Read → compare → write inside one Room transaction, so a save that lands in between is
     * seen as a conflict and nothing is overwritten. The memo repository's own `save` is the write.
     */
    private suspend fun appendToMemo(ref: DocumentRef, expectedUpdatedAt: Long, newBody: (String) -> String): DocumentWriteResult =
        database.withTransaction {
            val existing = memoRow(ref) ?: return@withTransaction DocumentWriteResult.NotFound
            if (existing.updatedAt != expectedUpdatedAt) return@withTransaction DocumentWriteResult.Conflict
            val saved = memos.save(existing, existing.title, newBody(existing.body), timeProvider.nowMillis())
                ?: return@withTransaction DocumentWriteResult.NotFound
            DocumentWriteResult.Done(ref, saved.updatedAt)
        }

    /**
     * A journal's read → compare → write is the diary repository's one atomic step — its lock
     * first, then one transaction (docs/DIARY_LOCK_ORDER.md). No transaction is opened here: a
     * transaction around it would take T before M_diary, the reverse of restore and the autosave.
     */
    private suspend fun appendToJournal(ref: DocumentRef, text: String, expectedUpdatedAt: Long): DocumentWriteResult =
        when (val result = diary.appendIfUnchanged(ref.id, expectedUpdatedAt) { body -> joinLines(body, text) }) {
            is DiaryAppendResult.Done -> DocumentWriteResult.Done(ref, result.entry.updatedAt)
            DiaryAppendResult.Conflict -> DocumentWriteResult.Conflict
            DiaryAppendResult.ReadOnly -> DocumentWriteResult.ReadOnly
            DiaryAppendResult.NotFound -> DocumentWriteResult.NotFound
        }

    /** New trailing lines; an empty body takes the text as its first line. */
    private fun joinLines(body: String, text: String): String =
        if (body.isEmpty()) text else body + "\n" + text

    /**
     * Exactly one new root node at the end — the outliner's own `+` row, done in the domain
     * (`OutlineEditing.appendLine` with no zoom), so the body round-trips as an outline. A newline
     * in [text] would be a split into several nodes, which v1 does not do; it becomes a space.
     */
    private fun appendRootNode(body: String, text: String): String {
        val inserted = OutlineEditing.appendLine(OutlineText.parse(body), zoomId = null)
        val oneLine = text.replace('\r', ' ').replace('\n', ' ').trim()
        return OutlineText.serialize(OutlineEditing.updateText(inserted.document, inserted.newId, oneLine))
    }

    // --- search (v1: standalone memos, outlines and journals; MemoSearch in memory; no FTS) ---

    /**
     * Two searches, one list: memos and outlines through the wall's own flows, journals through the
     * diary repository (the date range narrows that read to the existing range query), each row
     * matched by [MemoSearch], merged and ordered by `updatedAt` newest first, then kind, then id.
     * An unbounded query — no words, no date — finds nothing (docs/JOURNAL_SEARCH_AUDIT.md).
     */
    override suspend fun search(query: DocumentQuery): List<DocumentSummary> {
        if (!query.isBounded) return emptyList()
        val kinds = query.kinds.intersect(searchableKinds)
        if (kinds.isEmpty()) return emptyList()
        val parsed = MemoSearch.parse(query.text)
        val zone = timeProvider.currentZoneId()
        val range = query.dateRange
        val memoHits: List<DocumentSummary> = if (kinds.any { it != DocumentKind.JOURNAL }) {
            val tagsByMemo = tags.observeAllRelations().first().groupBy({ it.memoId }, { it.tagId })
            val tagNames = tags.observeAllTags().first().associate { it.id to it.name }
            val rows = buildList {
                if (DocumentKind.MEMO in kinds) addAll(memos.observeStandaloneMemos("").first())
                if (DocumentKind.OUTLINE in kinds) addAll(memos.observeOutlineDocuments("").first())
            }
            rows.asSequence()
                .filter { query.folderId == null || it.folderId == query.folderId }
                .filter { range == null || touchedWithin(it, range, zone) }
                .filter { memo ->
                    val names = tagsByMemo[memo.id].orEmpty().mapNotNull(tagNames::get)
                    MemoSearch.matches(parsed, memo.title, memo.body, names)
                }
                .map(::summary)
                .toList()
        } else {
            emptyList()
        }
        // Journals live on days, not in folders: a folder filter means "not journals".
        val journalHits: List<DocumentSummary> = if (DocumentKind.JOURNAL in kinds && query.folderId == null) {
            val entries = if (range == null) {
                diary.observeEntries().first()
            } else {
                diary.observeEntriesBetween(LocalDate.ofEpochDay(range.startEpochDay), LocalDate.ofEpochDay(range.endEpochDayInclusive)).first()
            }
            entries.asSequence()
                // The first line is the journal's title-equivalent and is already inside the body.
                .filter { MemoSearch.matches(parsed, title = "", body = it.body) }
                .map(::summary)
                .toList()
        } else {
            emptyList()
        }
        return (memoHits + journalHits)
            .sortedWith(compareByDescending<DocumentSummary> { it.updatedAt }.thenBy { it.ref.kind.ordinal }.thenBy { it.ref.id })
            .take(query.limit)
    }

    /** The calendar's rule: a memo is on the local day it was made and, if different, the day it was last written. */
    private fun touchedWithin(memo: MemoEntity, range: DocumentDateRange, zone: java.time.ZoneId): Boolean {
        fun day(millis: Long): Long = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()
        return day(memo.createdAt) in range || day(memo.updatedAt) in range
    }
}

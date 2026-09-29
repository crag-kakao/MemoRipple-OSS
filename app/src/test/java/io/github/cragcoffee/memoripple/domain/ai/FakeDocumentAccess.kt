package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.diary.DiaryStatePolicy
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import io.github.cragcoffee.memoripple.domain.documents.DocumentAccess
import io.github.cragcoffee.memoripple.domain.documents.DocumentContent
import io.github.cragcoffee.memoripple.domain.documents.DocumentCreate
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentMetadata
import io.github.cragcoffee.memoripple.domain.documents.DocumentQuery
import io.github.cragcoffee.memoripple.domain.documents.DocumentReadResult
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSearchScope
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary
import io.github.cragcoffee.memoripple.domain.documents.DocumentWriteResult
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** A fixed clock: 2026-09-19 (Saturday) 10:00 JST — the week is Mon 14 … Sun 20. */
class FixedTime(val today: LocalDate = LocalDate.of(2026, 9, 19)) : TimeProvider {
    override fun nowMillis(): Long = toEpochMillis(today.atTime(10, 0))
    override fun currentLocalDate(): LocalDate = today
    override fun currentZoneId(): ZoneId = ZoneId.of("Asia/Tokyo")
}

/**
 * An in-memory [DocumentAccess] with the boundary's contract: kind-checked reads, title / body
 * word search, create-empty, append with the version check, LOCKED journals read-only, future
 * journal days refused. It records every write so a test can assert that none happened.
 */
class FakeDocumentAccess(private val time: TimeProvider = FixedTime()) : DocumentAccess {
    data class Doc(
        val ref: DocumentRef,
        var title: String,
        var body: String,
        var updatedAt: Long,
        val createdAt: Long = updatedAt,
        val journalDate: LocalDate? = null,
        var journalState: DiaryState = DiaryState.DRAFT,
    )

    val docs = LinkedHashMap<DocumentRef, Doc>()
    val writes = ArrayList<String>()
    var searches = 0
    private var nextId = 100L

    fun memo(id: Long, title: String, body: String = "", updatedAt: Long = 1_000L + id): DocumentRef =
        put(Doc(DocumentRef(DocumentKind.MEMO, id), title, body, updatedAt))

    fun outline(id: Long, title: String, body: String = "", updatedAt: Long = 1_000L + id): DocumentRef =
        put(Doc(DocumentRef(DocumentKind.OUTLINE, id), title, body, updatedAt))

    fun journal(id: Long, date: LocalDate, body: String = "", state: DiaryState = DiaryState.DRAFT, updatedAt: Long = 1_000L + id): DocumentRef =
        put(Doc(DocumentRef(DocumentKind.JOURNAL, id), body.lineSequence().firstOrNull { it.isNotBlank() } ?: "（本文なし）", body, updatedAt, journalDate = date, journalState = state))

    private fun put(doc: Doc): DocumentRef { docs[doc.ref] = doc; return doc.ref }

    fun summary(ref: DocumentRef): DocumentSummary = docs.getValue(ref).let { DocumentSummary(it.ref, it.title, it.createdAt, it.updatedAt) }

    override val searchableKinds: Set<DocumentKind> = DocumentSearchScope.V1

    override suspend fun get(ref: DocumentRef): DocumentReadResult {
        val d = docs[ref] ?: return DocumentReadResult.NotFound
        val metadata = when (d.ref.kind) {
            DocumentKind.MEMO -> DocumentMetadata.Memo(null, null)
            DocumentKind.OUTLINE -> DocumentMetadata.Outline(null)
            DocumentKind.JOURNAL -> DocumentMetadata.Journal(d.journalDate!!, d.journalState, DiaryStatePolicy.canEdit(d.journalState))
        }
        return DocumentReadResult.Found(DocumentContent(summary(ref), d.body, metadata))
    }

    override suspend fun search(query: DocumentQuery): List<DocumentSummary> {
        searches++
        if (!query.isBounded) return emptyList()
        val terms = query.text.split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.lowercase() }
        return docs.values
            .filter { it.ref.kind in query.kinds }
            .filter { d -> terms.all { t -> d.title.lowercase().contains(t) || d.body.lowercase().contains(t) } }
            .filter { d -> query.dateRange?.let { r -> (d.journalDate?.toEpochDay() ?: dayOf(d.createdAt)) in r } ?: true }
            .map { summary(it.ref) }
            .sortedWith(compareByDescending<DocumentSummary> { it.updatedAt }.thenBy { it.ref.kind.ordinal }.thenBy { it.ref.id })
            .take(query.limit)
    }

    private fun dayOf(millis: Long): Long = java.time.Instant.ofEpochMilli(millis).atZone(time.currentZoneId()).toLocalDate().toEpochDay()

    override suspend fun create(request: DocumentCreate): DocumentWriteResult {
        writes += "create:$request"
        val now = time.nowMillis()
        val ref = when (request) {
            is DocumentCreate.Memo -> put(Doc(DocumentRef(DocumentKind.MEMO, nextId++), "無題のメモ", "", now))
            is DocumentCreate.Outline -> put(Doc(DocumentRef(DocumentKind.OUTLINE, nextId++), "無題のアウトライナー", "", now))
            is DocumentCreate.Journal -> {
                if (request.date.isAfter(time.currentLocalDate())) return DocumentWriteResult.Rejected("a journal cannot be written for a future day")
                put(Doc(DocumentRef(DocumentKind.JOURNAL, nextId++), "（本文なし）", "", now, journalDate = request.date))
            }
        }
        return DocumentWriteResult.Done(ref, now)
    }

    override suspend fun append(ref: DocumentRef, text: String, expectedUpdatedAt: Long): DocumentWriteResult {
        writes += "append:$ref:$text@$expectedUpdatedAt"
        val d = docs[ref] ?: return DocumentWriteResult.NotFound
        if (d.ref.kind == DocumentKind.JOURNAL && !DiaryStatePolicy.canEdit(d.journalState)) return DocumentWriteResult.ReadOnly
        if (d.updatedAt != expectedUpdatedAt) return DocumentWriteResult.Conflict
        d.body = if (d.body.isBlank()) text else d.body + "\n" + text
        d.updatedAt = time.nowMillis() + 1
        return DocumentWriteResult.Done(ref, d.updatedAt)
    }
}

class FakeTemplates(private val templates: List<MemoTemplate>) : TemplateLookup {
    override suspend fun find(idOrName: String): MemoTemplate? =
        templates.firstOrNull { it.id == idOrName } ?: templates.firstOrNull { it.name == idOrName }
}

fun LocalDate.atTenJst(): LocalDateTime = atTime(10, 0)

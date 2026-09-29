package io.github.cragcoffee.memoripple.data

import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.diary.DiaryStatePolicy
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface DiaryResult {
    data class Success(val entry: DiaryEntryEntity?) : DiaryResult
    data class Rejected(val reason: DiaryRejection, val entry: DiaryEntryEntity? = null) : DiaryResult
}

enum class DiaryRejection {
    INVALID_STATE,
    NOT_FOUND,
}

/**
 * Journal entries: dated documents with an id. A day may hold any number; the date groups them
 * (calendar, search, the day's list) and never identifies one. Nothing here locks an entry —
 * LOCKED is a state old rows carry from the retired lifecycle, and the only one that refuses
 * edits. Every write goes through one mutex, as before, and restore takes the same lock first.
 */
/** What the AI's append to a journal came to (the document boundary maps it one to one). */
sealed interface DiaryAppendResult {
    data class Done(val entry: DiaryEntryEntity) : DiaryAppendResult

    /** The journal was written since the version the append was shown: nothing written. */
    data object Conflict : DiaryAppendResult

    /** LOCKED (or otherwise not editable): nothing written. */
    data object ReadOnly : DiaryAppendResult

    data object NotFound : DiaryAppendResult
}

/**
 * The diary's writes (docs/DIARY_LOCK_ORDER.md).
 *
 * Allowed lock order, for every path in the app: **M_file → M_diary → M_future → T**
 * (`AttachmentRepository`'s file lock → this repository's [operationMutex] →
 * `FutureDiaryCommentRepository`'s lock → a Room write transaction). A method here takes
 * [operationMutex] first and only then a transaction, so **no caller may call a method here from
 * inside a Room transaction** — that is T → M_diary, the reverse, and it can deadlock against
 * restore or the editor's autosave (a lock-order policy test holds this).
 */
class DiaryRepository(
    private val dao: DiaryDao,
    private val timeProvider: TimeProvider,
    private val attachmentDao: AttachmentDao? = null,
    /** The entry's text and photos as ordered blocks (Room 27); the body is their projection. */
    private val content: DiaryContentStore? = null,
) {
    private val operationMutex = Mutex()

    fun observeEntries(): Flow<List<DiaryEntryEntity>> = dao.observeAll()

    fun observeEntriesBetween(
        startInclusive: LocalDate,
        endInclusive: LocalDate,
    ): Flow<List<DiaryEntryEntity>> = dao.observeBetween(
        startEpochDay = startInclusive.toEpochDay(),
        endEpochDay = endInclusive.toEpochDay(),
    )

    fun observeForDate(epochDay: Long): Flow<List<DiaryEntryEntity>> = dao.observeForDate(epochDay)

    suspend fun entriesForDate(epochDay: Long): List<DiaryEntryEntity> = dao.entriesForDate(epochDay)

    suspend fun findById(entryId: Long): DiaryEntryEntity? = dao.findById(entryId)

    fun currentDate(): LocalDate = timeProvider.currentLocalDate()

    /**
     * Restore writes its Room snapshot under this lock — the same lock-then-write order every
     * other caller here uses, so a diary write cannot interleave with the replacement.
     */
    suspend fun <T> withOperationLock(block: suspend () -> T): T =
        operationMutex.withLock { block() }

    /** A new, empty, editable entry on [diaryDateEpochDay] — the only way an entry comes to be. */
    suspend fun createEntry(diaryDateEpochDay: Long): DiaryEntryEntity = operationMutex.withLock {
        val now = timeProvider.nowMillis()
        val draft = DiaryEntryEntity(
            diaryDateEpochDay = diaryDateEpochDay,
            body = "",
            state = DiaryState.DRAFT,
            createdAt = now,
            updatedAt = now,
        )
        val created = draft.copy(id = dao.insert(draft))
        content?.createFor(created.id, "")
        created
    }

    /**
     * Writes [body] to the entry. An unchanged body writes nothing and moves nothing — so
     * opening and leaving an entry never counts as an update. A blank body on a DRAFT that has
     * no photos removes the entry when [releaseIfBlank] (leaving the editor); while writing,
     * the row stays so the editor keeps its identity. LOCKED refuses.
     */
    suspend fun saveBody(
        entryId: Long,
        body: String,
        releaseIfBlank: Boolean = true,
    ): DiaryResult = operationMutex.withLock {
        val existing = dao.findById(entryId)
            ?: return@withLock DiaryResult.Rejected(DiaryRejection.NOT_FOUND)
        if (!DiaryStatePolicy.canEdit(existing.state)) {
            return@withLock DiaryResult.Rejected(DiaryRejection.INVALID_STATE, existing)
        }
        if (body.isBlank() && releaseIfBlank && existing.state == DiaryState.DRAFT) {
            val hasPhotos = (attachmentDao?.diaryPhotoCount(existing.id) ?: 0) > 0
            if (!hasPhotos) {
                dao.deleteDraft(existing.id)
                return@withLock DiaryResult.Success(null)
            }
        }
        if (body == existing.body) return@withLock DiaryResult.Success(existing)
        if (content != null) {
            // A whole plain body (the AI's append) laid onto the blocks; the body is their projection.
            content.saveBody(entryId, body)
                ?: return@withLock DiaryResult.Rejected(DiaryRejection.INVALID_STATE, existing)
            return@withLock DiaryResult.Success(dao.findById(entryId))
        }
        val updated = existing.copy(body = body, updatedAt = timeProvider.nowMillis())
        dao.update(updated)
        DiaryResult.Success(updated)
    }

    /**
     * The editor's words, each text by its block (Room 27). The same rules as [saveBody]: LOCKED
     * refuses; unchanged words write nothing; on leaving, a DRAFT whose words are blank and that
     * holds no photo is removed.
     */
    suspend fun saveBlocks(
        entryId: Long,
        texts: Map<Long, String>,
        releaseIfBlank: Boolean = true,
    ): DiaryResult = operationMutex.withLock {
        val store = content ?: return@withLock DiaryResult.Rejected(DiaryRejection.NOT_FOUND)
        val existing = dao.findById(entryId)
            ?: return@withLock DiaryResult.Rejected(DiaryRejection.NOT_FOUND)
        if (!DiaryStatePolicy.canEdit(existing.state)) {
            return@withLock DiaryResult.Rejected(DiaryRejection.INVALID_STATE, existing)
        }
        val projection = store.saveTexts(entryId, texts)
            ?: return@withLock DiaryResult.Rejected(DiaryRejection.INVALID_STATE, existing)
        if (projection.isBlank() && releaseIfBlank && existing.state == DiaryState.DRAFT) {
            val hasPhotos = (attachmentDao?.diaryPhotoCount(existing.id) ?: 0) > 0
            if (!hasPhotos) {
                dao.deleteDraft(existing.id)
                return@withLock DiaryResult.Success(null)
            }
        }
        DiaryResult.Success(dao.findById(entryId))
    }

    /**
     * The AI's append to a journal, as one atomic step in the allowed order — [operationMutex]
     * (M_diary) first, then one Room transaction (T) in which the entry is read, its state and
     * version checked and the new body written. The version check and the write are never apart,
     * so another writer cannot land in between (no TOCTOU); a moved version is [DiaryAppendResult.Conflict]
     * with nothing written — no retry, never the last writer winning; LOCKED is
     * [DiaryAppendResult.ReadOnly] with nothing written. [newBody] turns the current body into the
     * appended one.
     */
    suspend fun appendIfUnchanged(
        entryId: Long,
        expectedUpdatedAt: Long,
        newBody: (String) -> String,
    ): DiaryAppendResult = operationMutex.withLock {
        val store = content
        if (store == null) appendLockHeld(entryId, expectedUpdatedAt, newBody) else store.inTransaction { appendLockHeld(entryId, expectedUpdatedAt, newBody) }
    }

    /** [appendIfUnchanged]'s read → compare → write; the caller holds [operationMutex] (and the transaction). */
    private suspend fun appendLockHeld(entryId: Long, expectedUpdatedAt: Long, newBody: (String) -> String): DiaryAppendResult {
        val existing = dao.findById(entryId) ?: return DiaryAppendResult.NotFound
        if (!DiaryStatePolicy.canEdit(existing.state)) return DiaryAppendResult.ReadOnly
        if (existing.updatedAt != expectedUpdatedAt) return DiaryAppendResult.Conflict
        val body = newBody(existing.body)
        if (body == existing.body) return DiaryAppendResult.Done(existing)
        if (content != null) {
            content.saveBody(entryId, body) ?: return DiaryAppendResult.ReadOnly
            return dao.findById(entryId)?.let { DiaryAppendResult.Done(it) } ?: DiaryAppendResult.NotFound
        }
        val updated = existing.copy(body = body, updatedAt = timeProvider.nowMillis())
        dao.update(updated)
        return DiaryAppendResult.Done(updated)
    }

    /** Backspace at the start of a text whose previous block is text: the two become one. */
    suspend fun mergeTextBlocks(entryId: Long, textBlockId: Long, texts: Map<Long, String>) = operationMutex.withLock {
        val existing = dao.findById(entryId) ?: return@withLock null
        if (!DiaryStatePolicy.canEdit(existing.state)) return@withLock null
        content?.mergeWithPrevious(entryId, textBlockId, texts)
    }
}

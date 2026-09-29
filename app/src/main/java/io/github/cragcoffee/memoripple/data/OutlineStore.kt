package io.github.cragcoffee.memoripple.data

import androidx.room.withTransaction
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.domain.outline.OutlineDocument
import io.github.cragcoffee.memoripple.domain.outline.OutlineNode
import io.github.cragcoffee.memoripple.domain.outline.OutlineRows

/**
 * The one place an outline's rows are written (Room 28, docs/OUTLINE_STABLE_ROWS.md). Every write
 * happens in one transaction that also writes `memos.body` as the rows' projection — so the body
 * is never an independent copy and every reader of it (search, AI, speech, previews, export)
 * reads exactly what it read before. Only memos of kind `outline` have rows; for anything else
 * every call is a no-op.
 */
class OutlineStore(
    private val database: AppDatabase,
    private val rows: OutlineRowDao = database.outlineRowDao(),
    private val memos: MemoDao = database.memoDao(),
    private val attachments: AttachmentDao = database.attachmentDao(),
) {
    suspend fun isOutline(memoId: Long): Boolean = rows.memoKind(memoId) == MemoKind.OUTLINE.storageId

    /**
     * The outline with its lasting ids, as stored. Tolerant of what storage holds: an outline
     * with no rows gets them from its body (ids 1..n); rows whose projection is not the body
     * (a body written past this store) have that body laid onto them — the body always wins,
     * since it is what every reader saw. A photo of the outline that no photo row shows — added
     * or taken out, then the process ended before the editor could save or let it go — comes
     * back as a photo row at the top (docs/OUTLINE_PHOTO_ROWS.md §3): kept rather than lost, and
     * never let go unseen. Null for a memo that is not an outline.
     */
    suspend fun materialize(memoId: Long): OutlineDocument? = database.withTransaction {
        if (!isOutline(memoId)) return@withTransaction null
        val body = rows.memoBody(memoId).orEmpty()
        val stored = rows.rows(memoId).map(::toRow)
        val laid = when {
            stored.isEmpty() -> OutlineRows.fresh(body)
            OutlineRows.projection(stored) != body -> OutlineRows.reconcile(stored, body, nextId = 1)
            else -> stored
        }
        val resolved = OutlineRows.withPhotosOnTop(laid, attachments.memoRelations(memoId).map { it.id })
        if (resolved != stored) write(memoId, resolved)
        OutlineRows.documentOf(resolved)
    }

    /** A new outline's lines: its body, one row per line. */
    suspend fun createFor(memoId: Long, body: String) = database.withTransaction {
        if (isOutline(memoId)) write(memoId, OutlineRows.fresh(body))
    }

    /** What an outliner's save came to. */
    sealed interface Save {
        /** Written; [updatedAt] is the outline's version now. */
        data class Saved(val updatedAt: Long) : Save

        /** The outline was written elsewhere since [expectedUpdatedAt]: nothing was written. */
        data object Conflict : Save

        data object NotOutline : Save
    }

    /**
     * The outliner's save: the document's lines with their ids, then the title and the
     * projection as the memo's content — only if the outline is still at [expectedUpdatedAt], the
     * version the editor last read or wrote (docs/OUTLINE_STABLE_ROWS.md §9). A version that moved
     * (the AI's append, the split pane, any other writer) is [Save.Conflict] and nothing at all is
     * written — no row, no body: an editor holding an older outline never overwrites a newer one.
     */
    suspend fun saveDocument(memoId: Long, title: String, document: OutlineDocument, now: Long, expectedUpdatedAt: Long): Save =
        database.withTransaction {
            if (!isOutline(memoId)) return@withTransaction Save.NotOutline
            val current = memos.findById(memoId) ?: return@withTransaction Save.NotOutline
            if (current.updatedAt != expectedUpdatedAt) return@withTransaction Save.Conflict
            val list = OutlineRows.rowsOf(document)
            write(memoId, list)
            val version = nextVersion(current.updatedAt, now)
            memos.updateContent(memoId, title, OutlineRows.projection(list), version)
            Save.Saved(version)
        }

    /**
     * [MemoRepository.save] for an outline: a whole plain body laid onto the rows by line (the
     * lines that stay keep their ids), then the title and the projection. Returns false for a
     * memo that is not an outline.
     */
    suspend fun saveBody(memoId: Long, title: String, newBody: String, now: Long): Boolean =
        database.withTransaction {
            if (!isOutline(memoId)) return@withTransaction false
            val current = materialize(memoId) ?: return@withTransaction false
            val list = OutlineRows.reconcile(OutlineRows.rowsOf(current), newBody, current.nextId)
            write(memoId, list)
            val version = memos.findById(memoId)?.updatedAt?.let { nextVersion(it, now) } ?: now
            memos.updateContent(memoId, title, OutlineRows.projection(list), version)
            true
        }

    /**
     * An outline's `updatedAt` is its version: every write moves it forward, even two writes in
     * the same millisecond or under a clock set back — so a version an editor holds names exactly
     * one state of the outline.
     */
    private fun nextVersion(previous: Long, now: Long): Long = maxOf(now, previous + 1)

    /**
     * A new outline left with nothing in it is taken back as if it had never been made, as a
     * memo never written is never saved (docs/OUTLINE_PHOTO_ROWS.md §8). Everything is checked
     * again here, in one transaction: an outline (active, not archived or trashed) with no title,
     * no words on any line, no photo, no comment and no tag. Anything at all in it and nothing is
     * removed. Only the outliner that made it (from ＋, in the same visit) asks this. True when
     * it was removed.
     */
    suspend fun discardIfEmpty(memoId: Long): Boolean = database.withTransaction {
        if (!isOutline(memoId)) return@withTransaction false
        val memo = memos.findById(memoId) ?: return@withTransaction false
        if (memo.archivedAt != null || memo.trashedAt != null || memo.noteId != null) return@withTransaction false
        if (memo.title.isNotBlank()) return@withTransaction false
        val document = materialize(memoId) ?: return@withTransaction false
        val words = document.entries.any { it is OutlineNode && (it.isPhoto || it.text.isNotBlank()) }
        if (words) return@withTransaction false
        if (rows.photoCount(memoId) > 0 || rows.commentCount(memoId) > 0 || rows.tagCount(memoId) > 0) return@withTransaction false
        rows.deleteOutline(memoId) == 1
    }

    /**
     * The photos no photo row shows any more (photo rows taken out and saved), for the editor to
     * let go of when its session ends (docs/OUTLINE_PHOTO_ROWS.md §3).
     */
    suspend fun unshownPhotos(memoId: Long): List<Long> = rows.unshownPhotos(memoId)

    /**
     * Stores [list] as the outline's rows, in order; each row keeps its id. The photos' sortOrder
     * follows their rows (the ones no row shows come after), so the backup's 0..n-1 holds.
     */
    private suspend fun write(memoId: Long, list: List<OutlineRows.Row>) {
        require(list.map { it.id }.toSet().size == list.size) { "an outline row id is used twice" }
        val shown = list.mapNotNull { it.photo }
        require(shown.toSet().size == shown.size) { "a photo is shown by two rows" }
        val own = attachments.memoRelations(memoId).map { it.id }.toSet()
        // A photo row only ever shows one of this outline's own photos.
        val kept = list.filter { it.photo == null || it.photo in own }
        rows.deleteForMemo(memoId)
        rows.insertAll(
            kept.mapIndexed { position, row ->
                if (row.photo != null) {
                    OutlineRowEntity(memoId, row.id, position, OutlineRowEntity.KIND_PHOTO, row.line, row.photo)
                } else {
                    OutlineRowEntity(memoId, row.id, position, OutlineRowEntity.KIND_TEXT, row.line, null)
                }
            },
        )
        val order = kept.mapNotNull { it.photo } + (own - shown.toSet()).sorted()
        order.forEachIndexed { index, id -> attachments.updateMemoSortOrder(memoId, id, index) }
    }

    private fun toRow(entity: OutlineRowEntity): OutlineRows.Row =
        OutlineRows.Row(entity.rowId, entity.text, entity.photoAttachmentId.takeIf { entity.kind == OutlineRowEntity.KIND_PHOTO })
}

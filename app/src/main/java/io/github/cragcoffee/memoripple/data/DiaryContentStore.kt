package io.github.cragcoffee.memoripple.data

import androidx.room.withTransaction
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.memos.MemoBlock
import io.github.cragcoffee.memoripple.domain.memos.MemoContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The one place a journal entry's blocks are written (Room 27, docs/MEMO_CONTENT_BLOCKS.md §11) —
 * [MemoContentStore]'s rules on the diary's own rows. Every write happens in one transaction that
 * also writes `diary_entries.body` as the blocks' projection (and moves `updatedAt` when that
 * changes, so the AI's version check still sees every change of words) and keeps the photos'
 * `sortOrder` in the order of their blocks. A journal's photos that no block names sit at the
 * end, under the words, as a journal's photos always sat.
 *
 * A LOCKED entry is read here and never written: every write below refuses it.
 */
class DiaryContentStore(
    private val database: AppDatabase,
    private val nowMillis: () -> Long,
    private val blocks: DiaryContentBlockDao = database.diaryContentBlockDao(),
    private val attachments: AttachmentDao = database.attachmentDao(),
) {
    /** The entry's blocks as the reader and the editor see them; tolerant of what storage holds. */
    fun observe(entryId: Long): Flow<List<MemoBlock>> = combine(
        blocks.observeBlocks(entryId),
        attachments.observeDiaryPhotos(entryId),
        blocks.observeEntryBody(entryId),
    ) { stored, photos, body ->
        if (body == null) emptyList() else resolve(stored.map(::toBlock), body, photos.map { it.id })
    }.distinctUntilChanged()

    /**
     * One Room transaction for a caller that already holds the diary's lock (M_diary → T, the
     * allowed order — docs/DIARY_LOCK_ORDER.md); writes here inside it join the same transaction.
     */
    suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction { block() }

    suspend fun isLocked(entryId: Long): Boolean = blocks.entryState(entryId) == DiaryState.LOCKED.name

    private suspend fun writable(entryId: Long): Boolean {
        val state = blocks.entryState(entryId) ?: return false
        return state != DiaryState.LOCKED.name
    }

    /**
     * The blocks, stored as they are read — so every block has an id the editor can hold. A
     * LOCKED entry is only read: its blocks may have no ids yet, and nothing is written for it.
     */
    suspend fun materialize(entryId: Long): List<MemoBlock> = database.withTransaction {
        if (blocks.entryState(entryId) == null) return@withTransaction emptyList()
        val stored = blocks.blocks(entryId)
        val resolved = load(entryId)
        when {
            stored.map(::toBlock) == resolved && resolved.none { it.id == MemoContent.NEW } -> resolved
            !writable(entryId) -> resolved
            else -> write(entryId, resolved)
        }
    }

    /** A new entry's words: one text. */
    suspend fun createFor(entryId: Long, body: String) = database.withTransaction {
        if (writable(entryId)) write(entryId, listOf(MemoBlock.Text(MemoContent.NEW, body)))
    }

    /**
     * The editor's words, each text by its block. Returns the projection now stored as the body,
     * or null when the entry is gone or LOCKED. Unchanged words write nothing.
     */
    suspend fun saveTexts(entryId: Long, texts: Map<Long, String>): String? = database.withTransaction {
        if (!writable(entryId)) return@withTransaction null
        val current = load(entryId)
        val next = current.map { block ->
            if (block is MemoBlock.Text) texts[block.id]?.let { MemoBlock.Text(block.id, it) } ?: block else block
        }
        if (next == current && current.none { it.id == MemoContent.NEW }) return@withTransaction MemoContent.projection(current)
        MemoContent.projection(write(entryId, next))
    }

    /** A whole plain body from a writer that knows nothing of blocks (the AI's append), laid onto them. */
    suspend fun saveBody(entryId: Long, newBody: String): String? = database.withTransaction {
        if (!writable(entryId)) return@withTransaction null
        MemoContent.projection(write(entryId, MemoContent.reconcile(load(entryId), newBody)))
    }

    /**
     * One photo just stored for the entry, placed at [caret] in [writingTextId] (see
     * [MemoContent.insertPhotos]); with no writing place it joins the photos at the end. Returns
     * the id of the text to go on writing in.
     */
    suspend fun placePhoto(entryId: Long, attachmentId: Long, writingTextId: Long?, caret: Int? = null): Long? {
        if (!writable(entryId)) return null
        // Read as it was before this photo: the new row is not yet a block, and reading it as one
        // would put it (with a place to write) at the end before it is placed.
        val current = load(entryId, without = attachmentId)
        if (writingTextId == null) {
            // The new photo is the one no block names: it joins the closing photos.
            val ids = current.filterIsInstance<MemoBlock.Photo>().map { it.attachmentId } + attachmentId
            write(entryId, resolve(current, "", ids))
            return null
        }
        val insertion = MemoContent.insertPhotos(current, writingTextId, listOf(attachmentId), caret)
        val stored = write(entryId, insertion.blocks)
        return stored.getOrNull(insertion.writingIndex)?.id
    }

    /** After a photo row is deleted (its block went with it): the shape every write leaves. */
    suspend fun afterPhotoDeleted(entryId: Long) {
        if (blocks.entryState(entryId) == null) return
        write(entryId, MemoContent.normalize(load(entryId)))
    }

    /** Backspace at the start of a text: joined to the text before, after [texts] are stored. */
    suspend fun mergeWithPrevious(entryId: Long, textBlockId: Long, texts: Map<Long, String>): MemoContent.Merge? =
        database.withTransaction {
            if (!writable(entryId)) return@withTransaction null
            val current = load(entryId).map { block ->
                if (block is MemoBlock.Text) texts[block.id]?.let { MemoBlock.Text(block.id, it) } ?: block else block
            }
            val merge = MemoContent.mergeWithPrevious(current, textBlockId) ?: return@withTransaction null
            write(entryId, merge.blocks)
            merge
        }

    /** The photos' new order from the reorder sheet: the slots stay, the pictures change places. */
    suspend fun reassignPhotos(entryId: Long, orderedAttachmentIds: List<Long>) {
        if (blocks.entryState(entryId) == null) return
        write(entryId, MemoContent.reassignPhotos(load(entryId), orderedAttachmentIds))
    }

    private fun resolve(stored: List<MemoBlock>, body: String, attachmentIds: List<Long>): List<MemoBlock> =
        MemoContent.resolve(stored, body, attachmentIds, photosFirst = false)

    private suspend fun load(entryId: Long, without: Long? = null): List<MemoBlock> = resolve(
        blocks.blocks(entryId).map(::toBlock).filterNot { it is MemoBlock.Photo && it.attachmentId == without },
        blocks.entryBody(entryId).orEmpty(),
        attachments.diaryRelations(entryId).map { it.id }.filterNot { it == without },
    )

    /**
     * Stores [list] as the entry's blocks: positions 0..n-1, a stored block keeps its id, the body
     * becomes the projection (with `updatedAt` when it changed), and the photos' sortOrder follows
     * their blocks. Returns what is stored, with ids.
     */
    private suspend fun write(entryId: Long, list: List<MemoBlock>): List<MemoBlock> {
        blocks.deleteForEntry(entryId)
        list.forEachIndexed { position, block ->
            blocks.insert(
                when (block) {
                    is MemoBlock.Text -> DiaryContentBlockEntity(block.id, entryId, position, MemoContentBlockEntity.TYPE_TEXT, block.text, null)
                    is MemoBlock.Photo -> DiaryContentBlockEntity(block.id, entryId, position, MemoContentBlockEntity.TYPE_PHOTO, null, block.attachmentId)
                },
            )
        }
        val stored = blocks.blocks(entryId).map(::toBlock)
        val projection = MemoContent.projection(stored)
        if (blocks.entryBody(entryId) != projection) blocks.setBody(entryId, projection, nowMillis())
        stored.filterIsInstance<MemoBlock.Photo>().forEachIndexed { index, photo ->
            attachments.updateDiarySortOrder(entryId, photo.attachmentId, index)
        }
        return stored
    }

    private fun toBlock(entity: DiaryContentBlockEntity): MemoBlock =
        if (entity.type == MemoContentBlockEntity.TYPE_PHOTO && entity.photoAttachmentId != null) {
            MemoBlock.Photo(entity.id, entity.photoAttachmentId)
        } else {
            MemoBlock.Text(entity.id, entity.text.orEmpty())
        }
}

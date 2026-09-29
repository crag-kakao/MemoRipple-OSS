package io.github.cragcoffee.memoripple.data

import androidx.room.withTransaction
import io.github.cragcoffee.memoripple.domain.memos.MemoBlock
import io.github.cragcoffee.memoripple.domain.memos.MemoContent
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The one place a memo's blocks are written (docs/MEMO_CONTENT_BLOCKS.md). Every write happens in
 * one transaction that also writes `memos.body` as the blocks' projection and keeps the photos'
 * `sortOrder` in the order of their blocks — so the body is never an independent copy, and the
 * backup's 0..n-1 photo order still holds. Only memos of kind `memo` have blocks; for an outline
 * every call is a no-op.
 */
class MemoContentStore(
    private val database: AppDatabase,
    private val blocks: MemoContentBlockDao = database.memoContentBlockDao(),
    private val attachments: AttachmentDao = database.attachmentDao(),
    private val memos: MemoDao = database.memoDao(),
) {
    /** The memo's blocks as the reader and the editor see them; tolerant of what storage holds. */
    fun observe(memoId: Long): Flow<List<MemoBlock>> = combine(
        blocks.observeBlocks(memoId),
        attachments.observeMemoPhotos(memoId),
        blocks.observeMemoBody(memoId),
    ) { stored, photos, body ->
        MemoContent.resolve(stored.map(::toBlock), body.orEmpty(), photos.map { it.id })
    }.distinctUntilChanged()

    suspend fun isBlockMemo(memoId: Long): Boolean = blocks.memoKind(memoId) == MemoKind.MEMO.storageId

    /** The blocks, stored as they are read — so every block has an id the editor can hold. */
    suspend fun materialize(memoId: Long): List<MemoBlock> = database.withTransaction {
        if (!isBlockMemo(memoId)) return@withTransaction emptyList()
        val stored = blocks.blocks(memoId)
        val resolved = load(memoId)
        if (stored.map(::toBlock) == resolved && resolved.none { it.id == MemoContent.NEW }) resolved else write(memoId, resolved)
    }

    /** A new memo's words: one text. */
    suspend fun createFor(memoId: Long, body: String) = database.withTransaction {
        if (isBlockMemo(memoId)) write(memoId, listOf(MemoBlock.Text(MemoContent.NEW, body)))
    }

    /**
     * The editor's words: each text by its block. Returns the projection now stored as the body,
     * or null for a memo that has no blocks (an outline).
     */
    suspend fun saveTexts(memoId: Long, texts: Map<Long, String>): String? = database.withTransaction {
        if (!isBlockMemo(memoId)) return@withTransaction null
        val current = load(memoId)
        val next = current.map { block ->
            if (block is MemoBlock.Text) texts[block.id]?.let { MemoBlock.Text(block.id, it) } ?: block else block
        }
        MemoContent.projection(write(memoId, next))
    }

    /** The editor's save: the texts, then the title and the projection as the memo's content. */
    suspend fun saveBlockTexts(memoId: Long, title: String, texts: Map<Long, String>, now: Long): Boolean =
        database.withTransaction {
            val projection = saveTexts(memoId, texts) ?: return@withTransaction false
            memos.updateContent(memoId, title, projection, now) == 1
        }

    /**
     * [MemoRepository.save] for a memo with blocks: the plain body laid onto them, then the title
     * and the projection. Returns false for a memo without blocks (the caller writes the body).
     */
    suspend fun saveBody(memoId: Long, title: String, newBody: String, now: Long): Boolean =
        database.withTransaction {
            val projection = reconcile(memoId, newBody) ?: return@withTransaction false
            memos.updateContent(memoId, title, projection, now)
            true
        }

    /** A whole plain body from a writer that knows nothing of blocks, laid onto them. */
    suspend fun reconcile(memoId: Long, newBody: String): String? = database.withTransaction {
        if (!isBlockMemo(memoId)) return@withTransaction null
        MemoContent.projection(write(memoId, MemoContent.reconcile(load(memoId), newBody)))
    }

    /**
     * One photo just stored for the memo, placed: at [caret] in [writingTextId] (see
     * [MemoContent.insertPhotos]), or — with no writing place, as an import does — after the
     * photos already at the top. Returns the id of the text to go on writing in.
     */
    suspend fun placePhoto(memoId: Long, attachmentId: Long, writingTextId: Long?, caret: Int? = null): Long? {
        if (!isBlockMemo(memoId)) return null
        val current = load(memoId).filterNot { it is MemoBlock.Photo && it.attachmentId == attachmentId }
        if (writingTextId == null) {
            val lead = current.takeWhile { it is MemoBlock.Photo }
            write(memoId, lead + MemoBlock.Photo(MemoContent.NEW, attachmentId) + current.drop(lead.size))
            return null
        }
        val insertion = MemoContent.insertPhotos(current, writingTextId, listOf(attachmentId), caret)
        val stored = write(memoId, insertion.blocks)
        return stored.getOrNull(insertion.writingIndex)?.id
    }

    /** After a photo row is deleted (its block went with it): the shape every write leaves. */
    suspend fun afterPhotoDeleted(memoId: Long) {
        if (!isBlockMemo(memoId)) return
        write(memoId, MemoContent.normalize(load(memoId)))
    }

    /** Backspace at the start of a text: joined to the text before, after [texts] are stored. */
    suspend fun mergeWithPrevious(memoId: Long, textBlockId: Long, texts: Map<Long, String>): MemoContent.Merge? =
        database.withTransaction {
            if (!isBlockMemo(memoId)) return@withTransaction null
            val current = load(memoId).map { block ->
                if (block is MemoBlock.Text) texts[block.id]?.let { MemoBlock.Text(block.id, it) } ?: block else block
            }
            val merge = MemoContent.mergeWithPrevious(current, textBlockId) ?: return@withTransaction null
            write(memoId, merge.blocks)
            merge
        }

    /** The photos' new order from the reorder sheet: the slots stay, the pictures change places. */
    suspend fun reassignPhotos(memoId: Long, orderedAttachmentIds: List<Long>) {
        if (!isBlockMemo(memoId)) return
        write(memoId, MemoContent.reassignPhotos(load(memoId), orderedAttachmentIds))
    }

    private suspend fun load(memoId: Long): List<MemoBlock> = MemoContent.resolve(
        blocks.blocks(memoId).map(::toBlock),
        blocks.memoBody(memoId).orEmpty(),
        attachments.memoRelations(memoId).map { it.id },
    )

    /**
     * Stores [list] as the memo's blocks: positions 0..n-1, a stored block keeps its id, the body
     * becomes the projection, and the photos' sortOrder follows their blocks. Returns what is
     * stored, with ids.
     */
    private suspend fun write(memoId: Long, list: List<MemoBlock>): List<MemoBlock> {
        blocks.deleteForMemo(memoId)
        list.forEachIndexed { position, block ->
            blocks.insert(
                when (block) {
                    is MemoBlock.Text -> MemoContentBlockEntity(block.id, memoId, position, MemoContentBlockEntity.TYPE_TEXT, block.text, null)
                    is MemoBlock.Photo -> MemoContentBlockEntity(block.id, memoId, position, MemoContentBlockEntity.TYPE_PHOTO, null, block.attachmentId)
                },
            )
        }
        val stored = blocks.blocks(memoId).map(::toBlock)
        val projection = MemoContent.projection(stored)
        if (blocks.memoBody(memoId) != projection) blocks.setBody(memoId, projection)
        stored.filterIsInstance<MemoBlock.Photo>().forEachIndexed { index, photo ->
            attachments.updateMemoSortOrder(memoId, photo.attachmentId, index)
        }
        return stored
    }

    private fun toBlock(entity: MemoContentBlockEntity): MemoBlock =
        if (entity.type == MemoContentBlockEntity.TYPE_PHOTO && entity.photoAttachmentId != null) {
            MemoBlock.Photo(entity.id, entity.photoAttachmentId)
        } else {
            MemoBlock.Text(entity.id, entity.text.orEmpty())
        }
}

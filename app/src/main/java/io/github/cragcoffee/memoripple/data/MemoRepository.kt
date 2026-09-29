package io.github.cragcoffee.memoripple.data

import io.github.cragcoffee.memoripple.domain.outline.OutlineDocument
import kotlinx.coroutines.flow.Flow
import io.github.cragcoffee.memoripple.domain.diary.SystemTimeProvider
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import io.github.cragcoffee.memoripple.domain.memos.ImportedMemo
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.domain.memos.MemoLifecycleState
import io.github.cragcoffee.memoripple.domain.memos.lifecycleState

enum class TrashRestoreDestination { ACTIVE, ARCHIVED }

data class BulkMemoMutation(val memoIds: Set<Long>) {
    val count: Int get() = memoIds.size
}

data class BulkLifecycleMutation(
    val memoIds: Set<Long>,
    val changedAt: Long,
) {
    val count: Int get() = memoIds.size
}

class MemoRepository(
    private val memoDao: MemoDao,
    private val timeProvider: TimeProvider = SystemTimeProvider(),
    /**
     * A memo's blocks (docs/MEMO_CONTENT_BLOCKS.md). Every body written here goes through them for
     * a memo, so the stored body stays their projection. Null only where a test builds the
     * repository over a bare DAO; a memo without blocks is then read as a legacy memo.
     */
    private val content: MemoContentStore? = null,
    /**
     * An outline's rows (Room 28, docs/OUTLINE_STABLE_ROWS.md): every body written here for an
     * outline is laid onto them, so the stored body stays their projection and each line keeps its
     * lasting id.
     */
    private val outlines: OutlineStore? = null,
) {
    private var lastLifecycleTimestamp = Long.MIN_VALUE

    @Synchronized
    private fun nextLifecycleTimestamp(): Long {
        val now = timeProvider.nowMillis()
        val next = if (now <= lastLifecycleTimestamp) lastLifecycleTimestamp + 1 else now
        lastLifecycleTimestamp = next
        return next
    }

    fun observeMemos(query: String): Flow<List<MemoEntity>> =
        memoDao.observeMemos(query.trim())

    fun observeStandaloneMemos(query: String): Flow<List<MemoEntity>> =
        memoDao.observeStandaloneMemos(query.trim())

    /** The outlines made in the outliner, newest first — by kind, never by how a body reads. */
    fun observeOutlineDocuments(query: String): Flow<List<MemoEntity>> =
        memoDao.observeOutlineDocuments(query.trim())

    fun observeArchivedMemos(query: String): Flow<List<MemoEntity>> =
        memoDao.observeArchivedMemos(query.trim())

    fun observeTrashedMemos(query: String): Flow<List<MemoEntity>> =
        memoDao.observeTrashedMemos(query.trim())

    suspend fun findById(id: Long): MemoEntity? = memoDao.findById(id)

    /** One memo, watched — the reference pane's view of somebody else's page. */
    fun observeMemo(id: Long): Flow<MemoEntity?> = memoDao.observeMemo(id)

    /** The calendar's month: active memos and outlines created or written inside the window. */
    fun observeTouchedBetween(startInclusiveMillis: Long, endExclusiveMillis: Long): Flow<List<MemoEntity>> =
        memoDao.observeTouchedBetween(startInclusiveMillis, endExclusiveMillis)

    suspend fun createEmpty(now: Long, folderId: Long? = null): MemoEntity {
        val memo = MemoEntity(title = "", body = "", createdAt = now, updatedAt = now, folderId = folderId)
        return memo.copy(id = memoDao.insert(memo)).also { content?.createFor(it.id, "") }
    }

    /**
     * A new outline, empty. Unlike a memo it exists the moment it is asked for: the outliner
     * opens on a row, and the kind is set here once, at birth — no screen changes it later.
     */
    suspend fun createOutline(now: Long, folderId: Long? = null): MemoEntity {
        val outline = MemoEntity(
            title = "",
            body = "",
            createdAt = now,
            updatedAt = now,
            kind = MemoKind.OUTLINE.storageId,
            folderId = folderId,
        )
        return outline.copy(id = memoDao.insert(outline)).also { outlines?.createFor(it.id, "") }
    }

    /**
     * Writes memos read out of a Markdown file. Each becomes a new memo; nothing existing is
     * touched, so an import that was a mistake is undone by trashing what it added.
     */
    suspend fun createFromImport(memos: List<ImportedMemo>): List<Pair<Long, ImportedMemo>> {
        val now = timeProvider.nowMillis()
        return memos.mapNotNull { imported ->
            if (imported.title.isBlank() && imported.body.isBlank()) return@mapNotNull null
            // A file that said it is an outline comes back as one, with its lines as rows.
            val kind = if (imported.outline) MemoKind.OUTLINE else MemoKind.MEMO
            val id = memoDao.insert(
                MemoEntity(
                    title = imported.title,
                    body = imported.body,
                    createdAt = now,
                    updatedAt = now,
                    kind = kind.storageId,
                ),
            )
            if (imported.outline) outlines?.createFor(id, imported.body) else content?.createFor(id, imported.body)
            id to imported
        }
    }

    /**
     * Writes the words. [kind] only matters for a memo that does not exist yet; an existing row
     * keeps the kind it was born with, since `updateContent` touches title, body and time alone.
     */
    suspend fun save(
        existing: MemoEntity?,
        title: String,
        body: String,
        now: Long,
        kind: MemoKind = MemoKind.MEMO,
        folderId: Long? = null,
    ): MemoEntity? {
        if (title.isBlank() && body.isBlank()) {
            if (existing == null) return null
        }

        return if (existing == null) {
            val memo = MemoEntity(
                title = title,
                body = body,
                createdAt = now,
                updatedAt = now,
                kind = kind.storageId,
                folderId = folderId,
            )
            memo.copy(id = memoDao.insert(memo)).also {
                content?.createFor(it.id, body)
                outlines?.createFor(it.id, body)
            }
        } else {
            // A memo's words live in its blocks: a whole new body is laid onto them, and the
            // stored body becomes their projection. An outline keeps its body as it always has.
            // An outline's lines live in its rows: the body is laid onto them line by line.
            if (content?.saveBody(existing.id, title, body, now) != true &&
                outlines?.saveBody(existing.id, title, body, now) != true
            ) {
                memoDao.updateContent(existing.id, title, body, now)
            }
            memoDao.findById(existing.id)
        }
    }

    /**
     * The block editor's save: each text by its block, the title, and the projection. Returns the
     * stored memo, or null when the memo is gone or has no blocks.
     */
    suspend fun saveBlocks(existing: MemoEntity, title: String, texts: Map<Long, String>, now: Long): MemoEntity? {
        val store = content ?: return null
        if (!store.saveBlockTexts(existing.id, title, texts, now)) return null
        return memoDao.findById(existing.id)
    }

    /**
     * The outliner's save: each line with its lasting id, the title, and the projection — only if
     * the outline is still the version [existing] carries (its `updatedAt`); otherwise
     * [OutlineStore.Save.Conflict] and nothing is written.
     */
    suspend fun saveOutline(existing: MemoEntity, title: String, document: OutlineDocument, now: Long): OutlineStore.Save {
        val store = outlines ?: return OutlineStore.Save.NotOutline
        return store.saveDocument(existing.id, title, document, now, expectedUpdatedAt = existing.updatedAt)
    }

    suspend fun setFavorite(memoId: Long, favorite: Boolean): Boolean =
        memoDao.setFavorite(memoId, favorite) == 1

    /** 並べた順: the cards of a page in the order the hand left them; only their places change. */
    suspend fun reorder(orderedIds: List<Long>) = memoDao.reorder(orderedIds)

    suspend fun setPinned(memoId: Long, pinned: Boolean): Boolean =
        memoDao.setPinned(memoId, pinned) == 1

    suspend fun setFavoriteForMemos(memoIds: Set<Long>, favorite: Boolean): BulkMemoMutation =
        if (memoIds.isEmpty()) BulkMemoMutation(emptySet()) else {
            BulkMemoMutation(memoDao.setFavoriteForMemos(memoIds.toList(), favorite).toSet())
        }

    suspend fun setPinnedForMemos(memoIds: Set<Long>, pinned: Boolean): BulkMemoMutation =
        if (memoIds.isEmpty()) BulkMemoMutation(emptySet()) else {
            BulkMemoMutation(memoDao.setPinnedForMemos(memoIds.toList(), pinned).toSet())
        }

    suspend fun archiveMemos(memoIds: Set<Long>): BulkLifecycleMutation {
        val changedAt = nextLifecycleTimestamp()
        if (memoIds.isEmpty()) return BulkLifecycleMutation(emptySet(), changedAt)
        return BulkLifecycleMutation(
            memoIds = memoDao.archiveMemos(memoIds.toList(), changedAt).toSet(),
            changedAt = changedAt,
        )
    }

    suspend fun undoArchiveMemos(change: BulkLifecycleMutation): Int =
        if (change.memoIds.isEmpty()) 0 else {
            memoDao.undoArchiveMemos(change.memoIds.toList(), change.changedAt)
        }

    suspend fun moveMemosToTrash(memoIds: Set<Long>): BulkLifecycleMutation {
        val changedAt = nextLifecycleTimestamp()
        if (memoIds.isEmpty()) return BulkLifecycleMutation(emptySet(), changedAt)
        return BulkLifecycleMutation(
            memoIds = memoDao.moveMemosToTrash(memoIds.toList(), changedAt).toSet(),
            changedAt = changedAt,
        )
    }

    suspend fun undoMoveMemosToTrash(change: BulkLifecycleMutation): Int =
        if (change.memoIds.isEmpty()) 0 else {
            memoDao.undoMoveMemosToTrash(change.memoIds.toList(), change.changedAt)
        }

    suspend fun archive(memoId: Long): Boolean {
        val memo = memoDao.findById(memoId) ?: return false
        if (memo.lifecycleState != MemoLifecycleState.ACTIVE) return false
        return memoDao.archive(memoId, nextLifecycleTimestamp()) == 1
    }

    suspend fun unarchive(memoId: Long): Boolean {
        val memo = memoDao.findById(memoId) ?: return false
        if (memo.lifecycleState != MemoLifecycleState.ARCHIVED) return false
        return memoDao.unarchive(memoId) == 1
    }

    suspend fun moveToTrash(memoId: Long): Boolean {
        val memo = memoDao.findById(memoId) ?: return false
        if (memo.lifecycleState == MemoLifecycleState.TRASHED) return false
        return memoDao.moveToTrash(memoId, nextLifecycleTimestamp()) == 1
    }

    suspend fun restoreFromTrash(memoId: Long): TrashRestoreDestination? {
        val memo = memoDao.findById(memoId) ?: return null
        if (memo.lifecycleState != MemoLifecycleState.TRASHED) return null
        val destination = if (memo.archivedAt == null) {
            TrashRestoreDestination.ACTIVE
        } else {
            TrashRestoreDestination.ARCHIVED
        }
        return destination.takeIf { memoDao.restoreFromTrash(memoId) == 1 }
    }

    suspend fun deletePermanently(memoId: Long): Boolean {
        val memo = memoDao.findById(memoId) ?: return false
        if (memo.lifecycleState != MemoLifecycleState.TRASHED) return false
        return memoDao.deletePermanently(memoId) == 1
    }

    suspend fun emptyTrash(): Int = memoDao.emptyTrash()
}

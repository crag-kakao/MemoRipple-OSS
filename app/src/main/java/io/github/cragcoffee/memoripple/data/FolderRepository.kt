package io.github.cragcoffee.memoripple.data

import androidx.room.withTransaction
import io.github.cragcoffee.memoripple.domain.diary.SystemTimeProvider
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import io.github.cragcoffee.memoripple.domain.folders.FolderName
import io.github.cragcoffee.memoripple.domain.folders.FolderTree
import kotlinx.coroutines.flow.Flow

/** What a folder operation came to. */
sealed interface FolderResult {
    data class Done(val id: Long) : FolderResult
    /** The name was empty or only spaces. */
    data object InvalidName : FolderResult
    /** The folder (or the document) does not exist. */
    data object NotFound : FolderResult
    /** The target folder does not exist. */
    data object MissingTarget : FolderResult
    /** The move would put a folder under itself or under one of its descendants. */
    data object WouldCycle : FolderResult
}

/**
 * The folder tree as the app changes it. Every rule of [FolderTree] is checked here, inside
 * the transaction that applies the change, so the table can never hold a cycle, a missing
 * parent or a document pointing at a folder that is gone. Deleting a folder never deletes a
 * document. Nothing here reads or writes a document's body: a folder is where a document is
 * kept, not what it contains.
 */
class FolderRepository(
    private val database: AppDatabase,
    private val folderDao: FolderDao,
    private val memoDao: MemoDao,
    private val timeProvider: TimeProvider = SystemTimeProvider(),
) {
    fun observeFolders(): Flow<List<FolderEntity>> = folderDao.observeAll()

    suspend fun findById(id: Long): FolderEntity? = folderDao.findById(id)

    /** A new folder under [parentId] (null = the root). */
    suspend fun create(rawName: String, parentId: Long?): FolderResult {
        val name = FolderName.normalize(rawName) ?: return FolderResult.InvalidName
        return database.withTransaction {
            if (parentId != null && folderDao.findById(parentId) == null) {
                return@withTransaction FolderResult.MissingTarget
            }
            val now = timeProvider.nowMillis()
            FolderResult.Done(
                folderDao.insert(
                    FolderEntity(name = name, parentFolderId = parentId, createdAt = now, updatedAt = now),
                ),
            )
        }
    }

    /** Only the folder's own name changes; nothing it holds is touched. */
    suspend fun rename(id: Long, rawName: String): FolderResult {
        val name = FolderName.normalize(rawName) ?: return FolderResult.InvalidName
        return database.withTransaction {
            if (folderDao.rename(id, name, timeProvider.nowMillis()) == 1) FolderResult.Done(id) else FolderResult.NotFound
        }
    }

    /** Re-parents [id] under [newParentId] (null = the root), never under itself or below. */
    suspend fun move(id: Long, newParentId: Long?): FolderResult = database.withTransaction {
        val tree = folderDao.all().map(FolderEntity::toNode)
        when {
            tree.none { it.id == id } -> FolderResult.NotFound
            newParentId != null && tree.none { it.id == newParentId } -> FolderResult.MissingTarget
            !FolderTree.canMoveTo(tree, id, newParentId) -> FolderResult.WouldCycle
            else -> {
                folderDao.setParent(id, newParentId, timeProvider.nowMillis())
                FolderResult.Done(id)
            }
        }
    }

    /**
     * Deletes the folder only: its documents (active, archived and trashed) and its child
     * folders move up to its parent, or to the root. One transaction — never a half-moved
     * state, never a dangling reference.
     */
    suspend fun delete(id: Long): FolderResult = database.withTransaction {
        val tree = folderDao.all().map(FolderEntity::toNode)
        if (tree.none { it.id == id }) return@withTransaction FolderResult.NotFound
        val target = FolderTree.promotionTarget(tree, id)
        memoDao.moveAllFromFolder(id, target)
        folderDao.reparentChildren(id, target)
        folderDao.delete(id)
        FolderResult.Done(id)
    }

    /** Puts a memo or an outline in [folderId] (null = the root). */
    suspend fun assignMemo(memoId: Long, folderId: Long?): FolderResult = database.withTransaction {
        if (memoDao.findById(memoId) == null) return@withTransaction FolderResult.NotFound
        if (folderId != null && folderDao.findById(folderId) == null) {
            return@withTransaction FolderResult.MissingTarget
        }
        memoDao.setFolder(memoId, folderId)
        FolderResult.Done(memoId)
    }
}

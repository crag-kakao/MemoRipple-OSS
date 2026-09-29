package io.github.cragcoffee.memoripple.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY id ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY id ASC")
    suspend fun all(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): FolderEntity?

    @Insert
    suspend fun insert(folder: FolderEntity): Long

    @Query("UPDATE folders SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, name: String, updatedAt: Long): Int

    @Query("UPDATE folders SET parentFolderId = :parentId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setParent(id: Long, parentId: Long?, updatedAt: Long): Int

    /** Every child of [from] becomes a child of [to] — how a deleted folder's children move up. */
    @Query("UPDATE folders SET parentFolderId = :to WHERE parentFolderId = :from")
    suspend fun reparentChildren(from: Long, to: Long?): Int

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long): Int
}

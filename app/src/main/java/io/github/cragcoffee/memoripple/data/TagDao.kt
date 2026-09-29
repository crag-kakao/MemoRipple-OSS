package io.github.cragcoffee.memoripple.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags")
    fun observeAllTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM memo_tag_cross_refs")
    fun observeAllRelations(): Flow<List<MemoTagCrossRef>>

    @Query(
        "SELECT tags.* FROM tags INNER JOIN memo_tag_cross_refs " +
            "ON tags.id = memo_tag_cross_refs.tagId WHERE memo_tag_cross_refs.memoId = :memoId",
    )
    fun observeTagsForMemo(memoId: Long): Flow<List<TagEntity>>

    @Query(
        "SELECT tags.id, tags.name, tags.normalizedName, tags.createdAt, " +
            "COUNT(memo_tag_cross_refs.memoId) AS memoCount FROM tags " +
            "LEFT JOIN memo_tag_cross_refs ON tags.id = memo_tag_cross_refs.tagId " +
            "GROUP BY tags.id",
    )
    fun observeTagSummaries(): Flow<List<TagSummary>>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun findById(id: Long): TagEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tag: TagEntity): Long

    @Query("UPDATE tags SET name = :name, normalizedName = :normalizedName WHERE id = :tagId")
    suspend fun rename(tagId: Long, name: String, normalizedName: String): Int

    @Delete
    suspend fun delete(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun attach(relation: MemoTagCrossRef): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun attachAll(relations: List<MemoTagCrossRef>): List<Long>

    @Query("DELETE FROM memo_tag_cross_refs WHERE memoId = :memoId AND tagId = :tagId")
    suspend fun detach(memoId: Long, tagId: Long): Int

    @Query(
        "SELECT id FROM memos WHERE id IN (:memoIds) " +
            "AND archivedAt IS NULL AND trashedAt IS NULL",
    )
    suspend fun findActiveMemoIds(memoIds: List<Long>): List<Long>

    @Query("SELECT id FROM tags WHERE id IN (:tagIds)")
    suspend fun findExistingTagIds(tagIds: List<Long>): List<Long>

    @Query(
        "DELETE FROM memo_tag_cross_refs WHERE memoId IN (:memoIds) AND tagId IN (:tagIds)",
    )
    suspend fun detachAll(memoIds: List<Long>, tagIds: List<Long>): Int

    @Transaction
    suspend fun addTagsToMemos(memoIds: List<Long>, tagIds: List<Long>): List<Long> {
        val activeIds = findActiveMemoIds(memoIds.distinct())
        val existingTagIds = findExistingTagIds(tagIds.distinct())
        if (activeIds.isNotEmpty() && existingTagIds.isNotEmpty()) {
            attachAll(activeIds.flatMap { memoId ->
                existingTagIds.map { tagId -> MemoTagCrossRef(memoId, tagId) }
            })
        }
        return activeIds.takeIf { existingTagIds.isNotEmpty() }.orEmpty()
    }

    @Transaction
    suspend fun removeTagsFromMemos(memoIds: List<Long>, tagIds: List<Long>): List<Long> {
        val activeIds = findActiveMemoIds(memoIds.distinct())
        val existingTagIds = findExistingTagIds(tagIds.distinct())
        if (activeIds.isNotEmpty() && existingTagIds.isNotEmpty()) {
            detachAll(activeIds, existingTagIds)
        }
        return activeIds.takeIf { existingTagIds.isNotEmpty() }.orEmpty()
    }
}

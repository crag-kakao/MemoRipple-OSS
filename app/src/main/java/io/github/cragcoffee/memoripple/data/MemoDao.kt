package io.github.cragcoffee.memoripple.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoDao {
    @Query(
        """
        SELECT * FROM memos
        WHERE archivedAt IS NULL AND trashedAt IS NULL
          AND (:query = ''
           OR title LIKE '%' || :query || '%' COLLATE NOCASE
           OR body LIKE '%' || :query || '%' COLLATE NOCASE)
        ORDER BY updatedAt DESC
        """,
    )
    fun observeMemos(query: String): Flow<List<MemoEntity>>

    /**
     * The memos that stand alone.
     *
     * A memo that is an episode is read as part of its note, so it is not also loose on the memo
     * wall — the same piece of writing appearing in two places makes it unclear which one owns it.
     * It is the same memo: releasing it from the note brings it straight back here. An outline
     * is listed on its own page, so the wall holds memos by kind as well.
     */
    @Query(
        """
        SELECT * FROM memos
        WHERE archivedAt IS NULL AND trashedAt IS NULL AND noteId IS NULL AND kind = 'memo'
          AND (:query = ''
           OR title LIKE '%' || :query || '%' COLLATE NOCASE
           OR body LIKE '%' || :query || '%' COLLATE NOCASE)
        ORDER BY updatedAt DESC
        """,
    )
    fun observeStandaloneMemos(query: String): Flow<List<MemoEntity>>

    /**
     * The outlines: what was made in the outliner, told apart by kind alone. A memo that is
     * merely written with outline marks is not here — the body is never read to decide.
     */
    @Query(
        """
        SELECT * FROM memos
        WHERE archivedAt IS NULL AND trashedAt IS NULL AND kind = 'outline'
          AND (:query = ''
           OR title LIKE '%' || :query || '%' COLLATE NOCASE
           OR body LIKE '%' || :query || '%' COLLATE NOCASE)
        ORDER BY updatedAt DESC
        """,
    )
    fun observeOutlineDocuments(query: String): Flow<List<MemoEntity>>

    @Query(
        """
        SELECT * FROM memos
        WHERE archivedAt IS NOT NULL AND trashedAt IS NULL
          AND (:query = ''
           OR title LIKE '%' || :query || '%' COLLATE NOCASE
           OR body LIKE '%' || :query || '%' COLLATE NOCASE)
        ORDER BY archivedAt DESC, id DESC
        """,
    )
    fun observeArchivedMemos(query: String): Flow<List<MemoEntity>>

    @Query(
        """
        SELECT * FROM memos
        WHERE trashedAt IS NOT NULL
          AND (:query = ''
           OR title LIKE '%' || :query || '%' COLLATE NOCASE
           OR body LIKE '%' || :query || '%' COLLATE NOCASE)
        ORDER BY trashedAt DESC, id DESC
        """,
    )
    fun observeTrashedMemos(query: String): Flow<List<MemoEntity>>

    @Query("SELECT * FROM memos WHERE id = :id LIMIT 1")
    fun observeMemo(id: Long): kotlinx.coroutines.flow.Flow<MemoEntity?>

    @Query("SELECT * FROM memos WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): MemoEntity?

    @Insert
    suspend fun insert(memo: MemoEntity): Long

    @Query(
        "UPDATE memos SET title = :title, body = :body, updatedAt = :updatedAt " +
            "WHERE id = :memoId",
    )
    suspend fun updateContent(memoId: Long, title: String, body: String, updatedAt: Long): Int

    @Query("UPDATE memos SET isFavorite = :favorite WHERE id = :memoId")
    suspend fun setFavorite(memoId: Long, favorite: Boolean): Int

    @Query("UPDATE memos SET isPinned = :pinned WHERE id = :memoId")
    suspend fun setPinned(memoId: Long, pinned: Boolean): Int

    /**
     * The active memos and outlines that were born or written between two instants (start
     * inclusive, end exclusive) — the calendar's month, served by the createdAt / updatedAt
     * indexes rather than by reading the wall and filtering.
     */
    @Query(
        """
        SELECT * FROM memos
        WHERE archivedAt IS NULL AND trashedAt IS NULL
          AND ((createdAt >= :startInclusive AND createdAt < :endExclusive)
            OR (updatedAt >= :startInclusive AND updatedAt < :endExclusive))
        ORDER BY updatedAt DESC, id DESC
        """,
    )
    fun observeTouchedBetween(startInclusive: Long, endExclusive: Long): Flow<List<MemoEntity>>

    /** Every memo row there is — active, archived or trashed — for reconciling per-memo view state. */
    @Query("SELECT id FROM memos")
    suspend fun allIds(): List<Long>

    @Query(
        "SELECT id FROM memos WHERE id IN (:memoIds) " +
            "AND archivedAt IS NULL AND trashedAt IS NULL",
    )
    suspend fun findActiveIds(memoIds: List<Long>): List<Long>

    @Query(
        "UPDATE memos SET isFavorite = :favorite WHERE id IN (:memoIds) " +
            "AND archivedAt IS NULL AND trashedAt IS NULL",
    )
    suspend fun setFavoriteForActiveMemos(memoIds: List<Long>, favorite: Boolean): Int

    @Query(
        "UPDATE memos SET isPinned = :pinned WHERE id IN (:memoIds) " +
            "AND archivedAt IS NULL AND trashedAt IS NULL",
    )
    suspend fun setPinnedForActiveMemos(memoIds: List<Long>, pinned: Boolean): Int

    @Transaction
    suspend fun setFavoriteForMemos(memoIds: List<Long>, favorite: Boolean): List<Long> {
        val activeIds = findActiveIds(memoIds.distinct())
        if (activeIds.isNotEmpty()) setFavoriteForActiveMemos(activeIds, favorite)
        return activeIds
    }

    @Transaction
    suspend fun setPinnedForMemos(memoIds: List<Long>, pinned: Boolean): List<Long> {
        val activeIds = findActiveIds(memoIds.distinct())
        if (activeIds.isNotEmpty()) setPinnedForActiveMemos(activeIds, pinned)
        return activeIds
    }

    @Query(
        "UPDATE memos SET archivedAt = :archivedAt, trashedAt = NULL " +
            "WHERE id = :memoId AND archivedAt IS NULL AND trashedAt IS NULL",
    )
    suspend fun archive(memoId: Long, archivedAt: Long): Int

    @Query(
        "UPDATE memos SET archivedAt = :archivedAt, trashedAt = NULL " +
            "WHERE id IN (:memoIds) AND archivedAt IS NULL AND trashedAt IS NULL",
    )
    suspend fun archiveActiveMemos(memoIds: List<Long>, archivedAt: Long): Int

    @Transaction
    suspend fun archiveMemos(memoIds: List<Long>, archivedAt: Long): List<Long> {
        val activeIds = findActiveIds(memoIds.distinct())
        if (activeIds.isNotEmpty()) archiveActiveMemos(activeIds, archivedAt)
        return activeIds
    }

    @Query(
        "UPDATE memos SET archivedAt = NULL, trashedAt = NULL " +
            "WHERE id IN (:memoIds) AND archivedAt = :expectedArchivedAt AND trashedAt IS NULL",
    )
    suspend fun undoArchiveMemos(
        memoIds: List<Long>,
        expectedArchivedAt: Long,
    ): Int

    @Query(
        "UPDATE memos SET archivedAt = NULL, trashedAt = NULL " +
            "WHERE id = :memoId AND archivedAt IS NOT NULL AND trashedAt IS NULL",
    )
    suspend fun unarchive(memoId: Long): Int

    @Query(
        "UPDATE memos SET trashedAt = :trashedAt WHERE id = :memoId AND trashedAt IS NULL",
    )
    suspend fun moveToTrash(memoId: Long, trashedAt: Long): Int

    @Query(
        "UPDATE memos SET trashedAt = :trashedAt WHERE id IN (:memoIds) " +
            "AND archivedAt IS NULL AND trashedAt IS NULL",
    )
    suspend fun moveActiveMemosToTrash(memoIds: List<Long>, trashedAt: Long): Int

    @Transaction
    suspend fun moveMemosToTrash(memoIds: List<Long>, trashedAt: Long): List<Long> {
        val activeIds = findActiveIds(memoIds.distinct())
        if (activeIds.isNotEmpty()) moveActiveMemosToTrash(activeIds, trashedAt)
        return activeIds
    }

    @Query(
        "UPDATE memos SET trashedAt = NULL WHERE id IN (:memoIds) " +
            "AND archivedAt IS NULL AND trashedAt = :expectedTrashedAt",
    )
    suspend fun undoMoveMemosToTrash(
        memoIds: List<Long>,
        expectedTrashedAt: Long,
    ): Int

    @Query("UPDATE memos SET trashedAt = NULL WHERE id = :memoId AND trashedAt IS NOT NULL")
    suspend fun restoreFromTrash(memoId: Long): Int

    @Query("DELETE FROM memos WHERE id = :memoId AND trashedAt IS NOT NULL")
    suspend fun deletePermanently(memoId: Long): Int

    /** Puts one memo in [folderId] (null = the root). Nothing else about it changes. */
    @Query("UPDATE memos SET folderId = :folderId WHERE id = :memoId")
    suspend fun setFolder(memoId: Long, folderId: Long?): Int

    /**
     * Moves every memo held by [from] to [to] — active, archived and trashed alike, so a
     * deleted folder leaves no dangling reference behind in any state.
     */
    @Query("UPDATE memos SET folderId = :to WHERE folderId = :from")
    suspend fun moveAllFromFolder(from: Long, to: Long?): Int

    /** One card's place in 並べた順. Nothing else about it changes — not even updatedAt. */
    @Query("UPDATE memos SET sortIndex = :index WHERE id = :memoId")
    suspend fun setSortIndex(memoId: Long, index: Int): Int

    /** The whole order of a page as the hand left it, written as one change. */
    @Transaction
    suspend fun reorder(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id -> setSortIndex(id, index) }
    }

    @Query("DELETE FROM memos WHERE trashedAt IS NOT NULL")
    suspend fun deleteAllTrashed(): Int

    @Transaction
    suspend fun emptyTrash(): Int = deleteAllTrashed()
}

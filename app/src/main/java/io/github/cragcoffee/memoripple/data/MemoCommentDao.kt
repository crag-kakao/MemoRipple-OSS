package io.github.cragcoffee.memoripple.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoCommentDao {
    @Query(
        """
        SELECT * FROM memo_comments
        WHERE memoId = :memoId
        ORDER BY playbackOrder ASC, id ASC
        """,
    )
    fun observeForMemo(memoId: Long): Flow<List<MemoCommentEntity>>

    @Query(
        """
        SELECT * FROM memo_comments
        WHERE memoId = :memoId
        ORDER BY playbackOrder ASC, id ASC
        """,
    )
    suspend fun findForMemo(memoId: Long): List<MemoCommentEntity>

    @Query("SELECT MAX(linkNo) FROM memo_comments WHERE memoId = :memoId")
    suspend fun maxLinkNo(memoId: Long): Int?

    @Query("UPDATE memo_comments SET linkNo = :linkNo WHERE id = :commentId")
    suspend fun setLinkNo(commentId: Long, linkNo: Int?): Int

    @Query(
        """
        SELECT COALESCE(MAX(playbackOrder), -1) + 1
        FROM memo_comments
        WHERE memoId = :memoId
        """,
    )
    suspend fun nextPlaybackOrder(memoId: Long): Int

    @Query(
        """
        SELECT id FROM memo_comments
        WHERE memoId = :memoId
        ORDER BY playbackOrder ASC, id ASC
        """,
    )
    suspend fun orderedIds(memoId: Long): List<Long>

    @Query(
        """
        SELECT id FROM memo_comments
        WHERE memoId = :memoId
        ORDER BY createdAt ASC, id ASC
        """,
    )
    suspend fun creationOrderedIds(memoId: Long): List<Long>

    @Query(
        """
        UPDATE memo_comments
        SET playbackOrder = :playbackOrder
        WHERE id = :commentId AND memoId = :memoId
        """,
    )
    suspend fun updatePlaybackOrder(
        memoId: Long,
        commentId: Long,
        playbackOrder: Int,
    ): Int

    @Query(
        """
        UPDATE memo_comments
        SET appearanceColor = :color,
            appearanceSize = :size,
            appearanceEmphasis = :emphasis
        WHERE id = :commentId AND memoId = :memoId
        """,
    )
    suspend fun updateAppearance(
        memoId: Long,
        commentId: Long,
        color: String,
        size: String,
        emphasis: String,
    ): Int

    @Query(
        """
        UPDATE memo_comments
        SET motionMode = :mode,
            motionSpeed = :speed,
            motionPlacement = :placement,
            flowDirection = :direction,
            flowEffect = :effect
        WHERE id = :commentId AND memoId = :memoId
        """,
    )
    suspend fun updateMotion(
        memoId: Long,
        commentId: Long,
        mode: String,
        speed: String,
        placement: String,
        direction: String,
        effect: String,
    ): Int

    @Query(
        """
        UPDATE memo_comments
        SET appearanceColor = :color,
            appearanceSize = :size,
            appearanceEmphasis = :emphasis,
            motionMode = :mode,
            motionSpeed = :speed,
            motionPlacement = :placement,
            flowDirection = :direction,
            flowEffect = :effect
        WHERE id = :commentId AND memoId = :memoId
        """,
    )
    suspend fun updateExpression(
        memoId: Long,
        commentId: Long,
        color: String,
        size: String,
        emphasis: String,
        mode: String,
        speed: String,
        placement: String,
        direction: String,
        effect: String,
    ): Int

    @Insert
    suspend fun insert(comment: MemoCommentEntity): Long

    @Delete
    suspend fun deleteEntity(comment: MemoCommentEntity)

    @Transaction
    suspend fun insertAtEnd(comment: MemoCommentEntity): MemoCommentEntity {
        val ordered = comment.copy(playbackOrder = nextPlaybackOrder(comment.memoId))
        return ordered.copy(id = insert(ordered))
    }

    @Transaction
    suspend fun replacePlaybackOrder(memoId: Long, orderedCommentIds: List<Long>): Boolean {
        val existingIds = orderedIds(memoId)
        if (orderedCommentIds.size != existingIds.size ||
            orderedCommentIds.toSet() != existingIds.toSet()
        ) {
            return false
        }
        orderedCommentIds.forEachIndexed { index, commentId ->
            updatePlaybackOrder(memoId, commentId, index)
        }
        return true
    }

    @Transaction
    suspend fun deleteAndNormalize(comment: MemoCommentEntity) {
        deleteEntity(comment)
        orderedIds(comment.memoId).forEachIndexed { index, commentId ->
            updatePlaybackOrder(comment.memoId, commentId, index)
        }
    }

    @Transaction
    suspend fun resetToCreationOrder(memoId: Long) {
        creationOrderedIds(memoId).forEachIndexed { index, commentId ->
            updatePlaybackOrder(memoId, commentId, index)
        }
    }
}

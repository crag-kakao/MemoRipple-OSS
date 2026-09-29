package io.github.cragcoffee.memoripple.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import io.github.cragcoffee.memoripple.domain.diary.RevealedFutureDiaryComment
import io.github.cragcoffee.memoripple.domain.diary.FutureCommentExpression
import kotlinx.coroutines.flow.Flow

@Dao
interface FutureDiaryCommentDao {
    @Query(
        """
        SELECT id, diaryEntryId, revealAt, deliveredAt, revealedAt, firstPresentedAt,
               CASE WHEN firstPresentedAt IS NOT NULL THEN text ELSE NULL END AS revealedText,
               CASE WHEN firstPresentedAt IS NOT NULL THEN appearanceColor ELSE NULL END
                   AS revealedAppearanceColor,
               CASE WHEN firstPresentedAt IS NOT NULL THEN appearanceSize ELSE NULL END
                   AS revealedAppearanceSize,
               CASE WHEN firstPresentedAt IS NOT NULL THEN appearanceEmphasis ELSE NULL END
                   AS revealedAppearanceEmphasis,
               CASE WHEN firstPresentedAt IS NOT NULL THEN motionMode ELSE NULL END
                   AS revealedMotionMode
        FROM future_diary_comments
        WHERE diaryEntryId = :diaryEntryId
        ORDER BY revealAt DESC, id DESC
        """,
    )
    fun observeForDiary(diaryEntryId: Long): Flow<List<FutureDiaryCommentOverviewRecord>>

    @Query(
        "SELECT COUNT(*) FROM future_diary_comments " +
            "WHERE deliveredAt IS NOT NULL AND revealedAt IS NULL",
    )
    fun observeUndeliveredRevealCount(): Flow<Int>

    @Insert
    suspend fun insert(comment: FutureDiaryCommentEntity): Long

    @Query(
        """
        UPDATE future_diary_comments
        SET deliveredAt = :deliveredAt
        WHERE deliveredAt IS NULL AND revealAt <= :now
        """,
    )
    suspend fun markDueDelivered(now: Long, deliveredAt: Long = now): Int

    @Query("DELETE FROM future_diary_comments WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT * FROM future_diary_comments WHERE id = :id LIMIT 1")
    suspend fun findEntityById(id: Long): FutureDiaryCommentEntity?

    @Query(
        """
        SELECT * FROM future_diary_comments
        WHERE deliveredAt IS NOT NULL AND revealedAt IS NULL
        ORDER BY deliveredAt ASC, revealAt ASC, id ASC
        LIMIT 1
        """,
    )
    suspend fun findNextDeliveredEntity(): FutureDiaryCommentEntity?

    @Query(
        """
        UPDATE future_diary_comments SET revealedAt = :revealedAt
        WHERE id = :id AND deliveredAt IS NOT NULL AND revealedAt IS NULL
        """,
    )
    suspend fun markRevealed(id: Long, revealedAt: Long): Int

    @Query(
        """
        SELECT *
        FROM future_diary_comments
        WHERE id = :id AND revealedAt IS NOT NULL
        LIMIT 1
        """,
    )
    suspend fun findRevealed(id: Long): FutureDiaryCommentEntity?

    @Query(
        """
        UPDATE future_diary_comments SET firstPresentedAt = :completedAt
        WHERE id = :id AND revealedAt IS NOT NULL AND firstPresentedAt IS NULL
        """,
    )
    suspend fun markFirstPresented(id: Long, completedAt: Long): Int

    @Transaction
    suspend fun revealNext(now: Long): RevealedFutureDiaryComment? {
        val comment = findNextDeliveredEntity() ?: return null
        if (markRevealed(comment.id, now) != 1) return null
        return RevealedFutureDiaryComment(
            id = comment.id,
            diaryEntryId = comment.diaryEntryId,
            text = comment.text,
            revealAt = comment.revealAt,
            revealedAt = now,
            firstPresentedAt = null,
            expression = comment.toExpression(),
        )
    }

    @Transaction
    suspend fun revealById(id: Long, now: Long): RevealedFutureDiaryComment? {
        val comment = findEntityById(id) ?: return null
        if (comment.deliveredAt == null || comment.revealedAt != null) return null
        if (markRevealed(id, now) != 1) return null
        return RevealedFutureDiaryComment(
            id = comment.id,
            diaryEntryId = comment.diaryEntryId,
            text = comment.text,
            revealAt = comment.revealAt,
            revealedAt = now,
            firstPresentedAt = null,
            expression = comment.toExpression(),
        )
    }
}

private fun FutureDiaryCommentEntity.toExpression(): FutureCommentExpression =
    FutureCommentExpression.fromStorageIds(
        color = appearanceColor,
        size = appearanceSize,
        emphasis = appearanceEmphasis,
        motionMode = motionMode,
    )

package io.github.cragcoffee.memoripple.data

import io.github.cragcoffee.memoripple.domain.comments.CommentAppearance
import io.github.cragcoffee.memoripple.domain.comments.CommentMotion
import kotlinx.coroutines.flow.Flow

class MemoCommentRepository(private val memoCommentDao: MemoCommentDao) {
    fun observeForMemo(memoId: Long): Flow<List<MemoCommentEntity>> =
        memoCommentDao.observeForMemo(memoId)

    suspend fun findForMemo(memoId: Long): List<MemoCommentEntity> =
        memoCommentDao.findForMemo(memoId)

    /**
     * コメントリンク: gives the comment the memo's next number (max+1, gaps never refilled)
     * and returns it. A comment that already carries one keeps it.
     */
    suspend fun assignLink(memoId: Long, commentId: Long): Int? {
        val existing = memoCommentDao.findForMemo(memoId).firstOrNull { it.id == commentId }
            ?: return null
        existing.linkNo?.let { return it }
        val next = (memoCommentDao.maxLinkNo(memoId) ?: 0) + 1
        return if (memoCommentDao.setLinkNo(commentId, next) == 1) next else null
    }

    /** リンクを解除 — the number comes off; body markers pointing at it go quiet. */
    suspend fun clearLink(commentId: Long): Boolean =
        memoCommentDao.setLinkNo(commentId, null) == 1

    suspend fun add(
        memoId: Long,
        text: String,
        now: Long,
        appearance: CommentAppearance = CommentAppearance.Default,
        motion: CommentMotion = CommentMotion.Default,
    ): MemoCommentEntity? {
        val normalizedText = text.trim()
        if (normalizedText.isEmpty()) return null

        val comment = MemoCommentEntity(
            memoId = memoId,
            text = normalizedText,
            createdAt = now,
            appearanceColor = appearance.colorRole.storageId,
            appearanceSize = appearance.sizeRole.storageId,
            appearanceEmphasis = appearance.emphasisRole.storageId,
            motionMode = motion.mode.storageId,
            motionSpeed = motion.speedRole.storageId,
            motionPlacement = motion.placementRole.storageId,
            flowDirection = motion.direction.storageId,
            flowEffect = motion.flowEffect.storageId,
        )
        return memoCommentDao.insertAtEnd(comment)
    }

    suspend fun reorder(memoId: Long, orderedCommentIds: List<Long>): Boolean =
        memoCommentDao.replacePlaybackOrder(memoId, orderedCommentIds)

    suspend fun resetToCreationOrder(memoId: Long) =
        memoCommentDao.resetToCreationOrder(memoId)

    suspend fun updateAppearance(
        comment: MemoCommentEntity,
        appearance: CommentAppearance,
    ): Boolean = memoCommentDao.updateAppearance(
        memoId = comment.memoId,
        commentId = comment.id,
        color = appearance.colorRole.storageId,
        size = appearance.sizeRole.storageId,
        emphasis = appearance.emphasisRole.storageId,
    ) == 1

    suspend fun updateMotion(
        comment: MemoCommentEntity,
        motion: CommentMotion,
    ): Boolean = memoCommentDao.updateMotion(
        memoId = comment.memoId,
        commentId = comment.id,
        mode = motion.mode.storageId,
        speed = motion.speedRole.storageId,
        placement = motion.placementRole.storageId,
        direction = motion.direction.storageId,
        effect = motion.flowEffect.storageId,
    ) == 1

    suspend fun updateExpression(
        comment: MemoCommentEntity,
        appearance: CommentAppearance,
        motion: CommentMotion,
    ): Boolean = memoCommentDao.updateExpression(
        memoId = comment.memoId,
        commentId = comment.id,
        color = appearance.colorRole.storageId,
        size = appearance.sizeRole.storageId,
        emphasis = appearance.emphasisRole.storageId,
        mode = motion.mode.storageId,
        speed = motion.speedRole.storageId,
        placement = motion.placementRole.storageId,
        direction = motion.direction.storageId,
        effect = motion.flowEffect.storageId,
    ) == 1

    suspend fun delete(comment: MemoCommentEntity) = memoCommentDao.deleteAndNormalize(comment)
}

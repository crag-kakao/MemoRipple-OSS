package io.github.cragcoffee.memoripple.domain.diary

import io.github.cragcoffee.memoripple.domain.comments.CommentAppearance
import io.github.cragcoffee.memoripple.domain.comments.CommentColorRole
import io.github.cragcoffee.memoripple.domain.comments.CommentEmphasisRole
import io.github.cragcoffee.memoripple.domain.comments.CommentMotionMode
import io.github.cragcoffee.memoripple.domain.comments.CommentSizeRole

data class FutureCommentExpression(
    val appearance: CommentAppearance = CommentAppearance.Default,
    val motionMode: CommentMotionMode = CommentMotionMode.FLOW,
) {
    val isDefault: Boolean
        get() = appearance.isDefault && motionMode == CommentMotionMode.FLOW

    companion object {
        val Default = FutureCommentExpression()

        fun fromStorageIds(
            color: String,
            size: String,
            emphasis: String,
            motionMode: String,
        ): FutureCommentExpression = FutureCommentExpression(
            appearance = CommentAppearance(
                colorRole = CommentColorRole.fromStorageId(color),
                sizeRole = CommentSizeRole.fromStorageId(size),
                emphasisRole = CommentEmphasisRole.fromStorageId(emphasis),
            ),
            motionMode = CommentMotionMode.fromStorageId(motionMode),
        )
    }
}

sealed interface FutureDiaryCommentItem {
    val id: Long
    val diaryEntryId: Long
    val revealAt: Long

    data class Sealed(
        override val id: Long,
        override val diaryEntryId: Long,
        override val revealAt: Long,
    ) : FutureDiaryCommentItem

    data class Delivered(
        override val id: Long,
        override val diaryEntryId: Long,
        override val revealAt: Long,
    ) : FutureDiaryCommentItem

    data class AwaitingPresentation(
        override val id: Long,
        override val diaryEntryId: Long,
        override val revealAt: Long,
    ) : FutureDiaryCommentItem

    data class Revealed(
        override val id: Long,
        override val diaryEntryId: Long,
        override val revealAt: Long,
        val text: String,
        val revealedAt: Long,
        val firstPresentedAt: Long,
        val expression: FutureCommentExpression = FutureCommentExpression.Default,
    ) : FutureDiaryCommentItem
}

data class RevealedFutureDiaryComment(
    val id: Long,
    val diaryEntryId: Long,
    val text: String,
    val revealAt: Long,
    val revealedAt: Long,
    val firstPresentedAt: Long? = null,
    val expression: FutureCommentExpression = FutureCommentExpression.Default,
)

data class SourceDiaryContext(
    val diaryEntryId: Long,
    val diaryDateEpochDay: Long,
    val body: String,
)

data class FutureCommentRevealContext(
    val sourceDiary: SourceDiaryContext,
    val comment: RevealedFutureDiaryComment,
)

val RevealedFutureDiaryComment.requiresFirstPresentation: Boolean
    get() = firstPresentedAt == null

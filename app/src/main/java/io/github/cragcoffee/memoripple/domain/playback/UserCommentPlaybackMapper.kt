package io.github.cragcoffee.memoripple.domain.playback

import io.github.cragcoffee.memoripple.domain.comments.CommentAppearance
import io.github.cragcoffee.memoripple.domain.comments.CommentEmphasisRole
import io.github.cragcoffee.memoripple.domain.comments.CommentMotion
import io.github.cragcoffee.memoripple.domain.comments.CommentMotionMode
import io.github.cragcoffee.memoripple.domain.comments.CommentFlowDirection
import io.github.cragcoffee.memoripple.domain.comments.CommentFlowEffect
import kotlin.math.roundToLong

data class UserCommentPlaybackSource(
    val id: Long,
    val text: String,
    val createdAt: Long,
    val playbackOrder: Int,
    val appearance: CommentAppearance = CommentAppearance.Default,
    val motion: CommentMotion = CommentMotion.Default,
)

/** Converts a persisted user comment into the same source-agnostic model used by Work Comments. */
class UserCommentPlaybackMapper {
    fun orderForPlayback(sources: List<UserCommentPlaybackSource>): List<UserCommentPlaybackSource> =
        sources.sortedWith(
            compareBy(UserCommentPlaybackSource::playbackOrder, UserCommentPlaybackSource::id),
        )

    fun map(
        source: UserCommentPlaybackSource,
        playbackId: Int,
        startTimeMillis: Long,
        laneIndex: Int,
    ): PlaybackItem = PlaybackItem(
        id = playbackId,
        text = source.text,
        startTimeMillis = startTimeMillis,
        travelDurationMillis = when (source.motion.mode) {
            CommentMotionMode.FLOW -> (USER_COMMENT_DURATION_MILLIS /
                source.motion.speedRole.velocityMultiplier).roundToLong().coerceAtLeast(1L)
            CommentMotionMode.FIXED_TOP,
            CommentMotionMode.FIXED_BOTTOM,
            -> FIXED_COMMENT_DURATION_MILLIS
        },
        laneIndex = laneIndex,
        fontScale = source.appearance.sizeRole.scaleMultiplier,
        opacity = 1f,
        emphasis = if (source.appearance.emphasisRole == CommentEmphasisRole.STRONG) {
            PlaybackEmphasis.STRONG
        } else {
            PlaybackEmphasis.NORMAL
        },
        colorRole = source.appearance.colorRole,
        laneBand = when (source.motion.mode) {
            CommentMotionMode.FLOW -> CommentLaneBandResolver.bandFor(source.motion.placementRole)
            CommentMotionMode.FIXED_TOP -> PlaybackLaneBand.TOP
            CommentMotionMode.FIXED_BOTTOM -> PlaybackLaneBand.BOTTOM
        },
        behavior = when (source.motion.mode) {
            CommentMotionMode.FLOW -> ResolvedPlaybackBehavior.FLOW
            CommentMotionMode.FIXED_TOP,
            CommentMotionMode.FIXED_BOTTOM,
            -> ResolvedPlaybackBehavior.FIXED
        },
        laneOrder = when (source.motion.mode) {
            CommentMotionMode.FLOW -> PlaybackLaneOrder.PREFERRED
            CommentMotionMode.FIXED_TOP -> PlaybackLaneOrder.ASCENDING
            CommentMotionMode.FIXED_BOTTOM -> PlaybackLaneOrder.DESCENDING
        },
        flowDirection = when (source.motion.direction) {
            CommentFlowDirection.RIGHT_TO_LEFT -> ResolvedFlowDirection.RIGHT_TO_LEFT
            CommentFlowDirection.LEFT_TO_RIGHT -> ResolvedFlowDirection.LEFT_TO_RIGHT
        },
        flowEffect = when (source.motion.flowEffect) {
            CommentFlowEffect.STRAIGHT -> ResolvedFlowEffect.STRAIGHT
            CommentFlowEffect.WAVE -> ResolvedFlowEffect.WAVE
        },
    )

    private companion object {
        const val USER_COMMENT_DURATION_MILLIS = 4_000L
        const val FIXED_COMMENT_DURATION_MILLIS = 3_000L
    }
}

package io.github.cragcoffee.memoripple.domain.playback

import io.github.cragcoffee.memoripple.domain.comments.CommentEmphasisRole
import io.github.cragcoffee.memoripple.domain.comments.CommentMotionMode
import io.github.cragcoffee.memoripple.domain.diary.RevealedFutureDiaryComment
import kotlin.math.roundToLong

class FutureDiaryCommentPlaybackMapper {
    fun map(
        comment: RevealedFutureDiaryComment,
        startDelayMillis: Long = REPLAY_START_DELAY_MILLIS,
    ): PlaybackTimeline {
        val expression = comment.expression
        val isFixed = expression.motionMode != CommentMotionMode.FLOW
        val item = PlaybackItem(
            id = comment.id.hashCode(),
            text = comment.text,
            startTimeMillis = startDelayMillis.coerceAtLeast(0),
            travelDurationMillis = if (isFixed) {
                FIXED_DURATION_MILLIS
            } else {
                FLOW_DURATION_MILLIS
            },
            laneIndex = 0,
            fontScale = expression.appearance.sizeRole.scaleMultiplier,
            opacity = 1f,
            emphasis = if (
                expression.appearance.emphasisRole == CommentEmphasisRole.STRONG
            ) {
                PlaybackEmphasis.STRONG
            } else {
                PlaybackEmphasis.NORMAL
            },
            colorRole = expression.appearance.colorRole,
            laneBand = when (expression.motionMode) {
                CommentMotionMode.FLOW -> null
                CommentMotionMode.FIXED_TOP -> PlaybackLaneBand.TOP
                CommentMotionMode.FIXED_BOTTOM -> PlaybackLaneBand.BOTTOM
            },
            behavior = if (isFixed) {
                ResolvedPlaybackBehavior.FIXED
            } else {
                ResolvedPlaybackBehavior.FLOW
            },
            laneOrder = when (expression.motionMode) {
                CommentMotionMode.FLOW -> PlaybackLaneOrder.PREFERRED
                CommentMotionMode.FIXED_TOP -> PlaybackLaneOrder.ASCENDING
                CommentMotionMode.FIXED_BOTTOM -> PlaybackLaneOrder.DESCENDING
            },
        )
        return PlaybackTimeline(
            items = listOf(item),
            laneCount = if (isFixed) FIXED_LANE_COUNT else 1,
            totalDurationMillis = startDelayMillis.coerceAtLeast(0) +
                item.travelDurationMillis,
        )
    }

    /** Render-time fallback; the persisted expression remains untouched. */
    fun fallbackFixedToFlow(timeline: PlaybackTimeline): PlaybackTimeline {
        if (timeline.items.none { it.behavior == ResolvedPlaybackBehavior.FIXED }) return timeline
        val items = timeline.items.map { item ->
            if (item.behavior != ResolvedPlaybackBehavior.FIXED) {
                item
            } else {
                item.copy(
                    travelDurationMillis = (item.travelDurationMillis *
                        FLOW_DURATION_MILLIS.toDouble() / FIXED_DURATION_MILLIS)
                        .roundToLong()
                        .coerceAtLeast(1L),
                    laneIndex = 0,
                    laneBand = null,
                    allowedLaneIndices = emptyList(),
                    behavior = ResolvedPlaybackBehavior.FLOW,
                    laneOrder = PlaybackLaneOrder.PREFERRED,
                )
            }
        }
        return timeline.copy(
            items = items,
            laneCount = 1,
            totalDurationMillis = items.maxOf {
                it.startTimeMillis + it.travelDurationMillis
            },
            validationIssue = null,
        )
    }

    companion object {
        const val INITIAL_CONTEXT_DELAY_MILLIS = 700L
        const val REPLAY_START_DELAY_MILLIS = 250L
        const val FLOW_DURATION_MILLIS = 9_000L
        const val FIXED_DURATION_MILLIS = 4_000L
        private const val FIXED_LANE_COUNT = 6
    }
}

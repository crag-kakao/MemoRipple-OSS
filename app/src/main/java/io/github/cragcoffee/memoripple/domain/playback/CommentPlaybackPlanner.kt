package io.github.cragcoffee.memoripple.domain.playback

import io.github.cragcoffee.memoripple.domain.WorkLine
import io.github.cragcoffee.memoripple.domain.WorkLineType
import io.github.cragcoffee.memoripple.domain.comments.CommentColorRole
import io.github.cragcoffee.memoripple.domain.comments.CommentFlowDirection
import io.github.cragcoffee.memoripple.domain.comments.CommentLineModifiers
import io.github.cragcoffee.memoripple.domain.comments.CommentMotionMode
import io.github.cragcoffee.memoripple.domain.comments.CommentSizeRole
import kotlin.math.roundToLong
import kotlin.math.max

/** Builds an ephemeral app-time timeline from an outline. It has no video clock dependency. */
class CommentPlaybackPlanner {
    fun plan(lines: List<WorkLine>): PlaybackTimeline {
        if (lines.isEmpty()) return PlaybackTimeline.Empty

        var cursor = 0L
        var nextId = 0
        val items = lines.flatMap { line ->
            val start = cursor + preDelay(line.type) + line.depth * DEPTH_DELAY_MILLIS
            cursor = start + EMISSION_INTERVAL_MILLIS
            // ×N 弾幕: the same words fly N times, each copy a beat behind the last on the
            // next lane over. 溜め holds the whole line back by its one constant.
            val delay = if (line.modifiers.delayed) CommentLineModifiers.DELAY_MILLIS else 0L
            (0 until line.modifiers.repeat.coerceAtLeast(1)).map { copy ->
                line.toPlaybackItem(
                    id = nextId++,
                    startTimeMillis = start + delay + copy * REPEAT_STAGGER_MILLIS,
                    laneShift = copy,
                )
            }
        }
        return PlaybackTimeline(
            items = items,
            laneCount = LANE_COUNT,
            // What the finite comments need. An endless one is not counted — it has no
            // length to count — and the animator keeps the reading alive for it instead.
            totalDurationMillis = items.maxOf { it.startTimeMillis + it.travelDurationMillis },
        )
    }

    private fun WorkLine.toPlaybackItem(
        id: Int,
        startTimeMillis: Long,
        laneShift: Int = 0,
    ): PlaybackItem {
        val style = styleFor(type, depth)
        val mods = modifiers
        // 行末修飾子 sit on top of the line's own style: the type still chooses the base
        // scale and pacing, the modifiers say how this one line asked to differ.
        val speedMultiplier = when (mods.speed) {
            CommentLineModifiers.SpeedStep.FAST -> FAST_VELOCITY
            CommentLineModifiers.SpeedStep.FASTEST -> FASTEST_VELOCITY
            null -> 1f
        }
        val sizeMultiplier = when (mods.size) {
            CommentLineModifiers.SizeStep.SMALL -> CommentSizeRole.SMALL.scaleMultiplier
            CommentLineModifiers.SizeStep.LARGE -> CommentSizeRole.LARGE.scaleMultiplier
            CommentLineModifiers.SizeStep.X_LARGE -> X_LARGE_SCALE
            null -> 1f
        }
        val fixed = mods.mode == CommentMotionMode.FIXED_TOP ||
            mods.mode == CommentMotionMode.FIXED_BOTTOM
        return PlaybackItem(
            id = id,
            text = text,
            startTimeMillis = startTimeMillis,
            travelDurationMillis = if (fixed) {
                FIXED_DURATION_MILLIS
            } else {
                (style.travelDurationMillis / speedMultiplier).roundToLong().coerceAtLeast(1L)
            },
            laneIndex = ((id % BASE_LANE_CYCLE) + depth + laneShift).mod(LANE_COUNT),
            fontScale = style.fontScale * sizeMultiplier,
            opacity = style.opacity,
            emphasis = style.emphasis,
            colorRole = mods.colorRole ?: style.colorRole,
            behavior = if (fixed) {
                ResolvedPlaybackBehavior.FIXED
            } else {
                ResolvedPlaybackBehavior.FLOW
            },
            laneBand = when (mods.mode) {
                CommentMotionMode.FIXED_TOP -> PlaybackLaneBand.TOP
                CommentMotionMode.FIXED_BOTTOM -> PlaybackLaneBand.BOTTOM
                else -> null
            },
            laneOrder = when (mods.mode) {
                CommentMotionMode.FIXED_TOP -> PlaybackLaneOrder.ASCENDING
                CommentMotionMode.FIXED_BOTTOM -> PlaybackLaneOrder.DESCENDING
                else -> PlaybackLaneOrder.PREFERRED
            },
            flowDirection = when (mods.direction) {
                CommentFlowDirection.LEFT_TO_RIGHT -> ResolvedFlowDirection.LEFT_TO_RIGHT
                else -> ResolvedFlowDirection.RIGHT_TO_LEFT
            },
            flowEffect = when {
                mods.blink -> ResolvedFlowEffect.BLINK
                mods.wave -> ResolvedFlowEffect.WAVE
                else -> ResolvedFlowEffect.STRAIGHT
            },
            // 完全固定 holds where it was pinned; ループ crosses again the moment it has
            // crossed. A ループ on a pinned line is the same wish, so it holds too.
            endless = mods.loop || (fixed && mods.persistent),
            // ←← / ←←← is the writer saying how fast this line flies; the automatic
            // reading aid never overrides that.
            readabilityAdjustable = mods.speed == null,
        )
    }

    private fun styleFor(type: WorkLineType, depth: Int): PlaybackStyle = when (type) {
        WorkLineType.HEADING -> PlaybackStyle(
            fontScale = max(MIN_FONT_SCALE, HEADING_SCALE - depth * DEPTH_SCALE_STEP),
            travelDurationMillis = HEADING_DURATION_MILLIS,
        )
        WorkLineType.ITEM -> PlaybackStyle(
            fontScale = depthScale(depth),
            travelDurationMillis = STANDARD_DURATION_MILLIS,
        )
        WorkLineType.TASK -> PlaybackStyle(
            fontScale = depthScale(depth),
            travelDurationMillis = STANDARD_DURATION_MILLIS,
        )
        // A finished task still belongs to the memo, so it flies past quietly rather than not at all.
        WorkLineType.TASK_DONE -> PlaybackStyle(
            fontScale = max(MIN_FONT_SCALE, NOTE_SCALE - depth * DEPTH_SCALE_STEP),
            travelDurationMillis = STANDARD_DURATION_MILLIS,
            opacity = DONE_TASK_OPACITY,
        )
        WorkLineType.NOTE -> PlaybackStyle(
            fontScale = max(MIN_FONT_SCALE, NOTE_SCALE - depth * DEPTH_SCALE_STEP),
            travelDurationMillis = NOTE_DURATION_MILLIS,
            opacity = NOTE_OPACITY,
        )
        WorkLineType.IMPORTANT -> PlaybackStyle(
            fontScale = max(MIN_FONT_SCALE, IMPORTANT_SCALE - depth * DEPTH_SCALE_STEP),
            travelDurationMillis = STANDARD_DURATION_MILLIS,
            emphasis = PlaybackEmphasis.STRONG,
            colorRole = CommentColorRole.YELLOW,
        )
        WorkLineType.QUESTION -> PlaybackStyle(
            fontScale = max(MIN_FONT_SCALE, QUESTION_SCALE - depth * DEPTH_SCALE_STEP),
            travelDurationMillis = QUESTION_DURATION_MILLIS,
        )
        WorkLineType.PLAIN -> PlaybackStyle(
            fontScale = max(MIN_FONT_SCALE, PLAIN_SCALE - depth * DEPTH_SCALE_STEP),
            travelDurationMillis = STANDARD_DURATION_MILLIS,
        )
    }

    private fun depthScale(depth: Int): Float =
        max(MIN_FONT_SCALE, STANDARD_SCALE - depth * DEPTH_SCALE_STEP)

    private fun preDelay(type: WorkLineType): Long = when (type) {
        WorkLineType.HEADING -> HEADING_PRE_DELAY_MILLIS
        WorkLineType.QUESTION -> QUESTION_PRE_DELAY_MILLIS
        else -> STANDARD_PRE_DELAY_MILLIS
    }

    private data class PlaybackStyle(
        val fontScale: Float,
        val travelDurationMillis: Long,
        val opacity: Float = 1f,
        val emphasis: PlaybackEmphasis = PlaybackEmphasis.NORMAL,
        val colorRole: CommentColorRole = CommentColorRole.DEFAULT,
    )

    // ニコニコ動画の呼吸に合わせた定数 (2026-09): a flowing comment crosses in the video
    // site's own four seconds — speed is (width + screen) / 4s, so long comments overtake
    // short ones — fixed lines hold their three, the stage holds twice the lanes, and the
    // emission cadence is tight enough to feel like a stream rather than a parade. The
    // heading/note scale ratios follow the site's big (×1.56) and small (×0.62) rows.
    private companion object {
        const val LANE_COUNT = 12
        const val BASE_LANE_CYCLE = 7
        const val EMISSION_INTERVAL_MILLIS = 300L
        const val DEPTH_DELAY_MILLIS = 150L
        const val STANDARD_PRE_DELAY_MILLIS = 120L
        const val HEADING_PRE_DELAY_MILLIS = 500L
        const val QUESTION_PRE_DELAY_MILLIS = 400L
        const val STANDARD_DURATION_MILLIS = 4_000L
        const val NOTE_DURATION_MILLIS = 4_000L
        const val QUESTION_DURATION_MILLIS = 4_000L
        const val HEADING_DURATION_MILLIS = 4_000L
        const val STANDARD_SCALE = 1f
        const val HEADING_SCALE = 1.56f
        const val NOTE_SCALE = 0.78f
        const val IMPORTANT_SCALE = 1.1f
        const val QUESTION_SCALE = 1f
        const val PLAIN_SCALE = 1f
        const val DEPTH_SCALE_STEP = 0.08f
        const val MIN_FONT_SCALE = 0.62f
        const val NOTE_OPACITY = 0.68f
        const val DONE_TASK_OPACITY = 0.5f

        // 行末修飾子の定数: the two speed steps beyond a line's own pace, the one size step
        // beyond 大, the fixed line's stay, and the 弾幕 beat between copies.
        const val FAST_VELOCITY = 1.2f
        const val FASTEST_VELOCITY = 1.6f
        const val X_LARGE_SCALE = 1.85f
        const val FIXED_DURATION_MILLIS = 3_000L
        const val REPEAT_STAGGER_MILLIS = 300L
    }
}

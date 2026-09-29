package io.github.cragcoffee.memoripple.domain.playback

import kotlin.math.PI
import kotlin.math.sin

/** Pure playback geometry shared by inline, stage, and overlay rendering. */
object CommentFlowPath {
    fun progress(
        elapsedMillis: Long,
        startTimeMillis: Long,
        durationMillis: Long,
    ): Float {
        if (durationMillis <= 0L) return 1f
        val value = (elapsedMillis - startTimeMillis).toDouble() / durationMillis.toDouble()
        return value.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0)?.toFloat() ?: 0f
    }

    fun horizontalOffsetPx(
        direction: ResolvedFlowDirection,
        progress: Float,
        containerWidthPx: Float,
        commentWidthPx: Float,
    ): Float {
        val safeProgress = progress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val safeContainerWidth = containerWidthPx.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
        val safeCommentWidth = commentWidthPx.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
        val distance = safeContainerWidth + safeCommentWidth
        return when (direction) {
            ResolvedFlowDirection.RIGHT_TO_LEFT -> safeContainerWidth - safeProgress * distance
            ResolvedFlowDirection.LEFT_TO_RIGHT -> -safeCommentWidth + safeProgress * distance
        }
    }

    fun verticalOffsetPx(
        effect: ResolvedFlowEffect,
        progress: Float,
        amplitudePx: Float,
        cycles: Float = DEFAULT_WAVE_CYCLES,
    ): Float {
        if (effect != ResolvedFlowEffect.WAVE) return 0f
        val safeProgress = progress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val safeAmplitude = amplitudePx.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
        val safeCycles = cycles.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
        return (sin(safeProgress * safeCycles * 2.0 * PI) * safeAmplitude)
            .toFloat()
            .takeIf(Float::isFinite)
            ?: 0f
    }

    fun effectiveAmplitudePx(
        desiredAmplitudePx: Float,
        renderHeightPx: Float,
        laneHeightPx: Float,
        verticalSafetyGapPx: Float,
    ): Float {
        val desired = desiredAmplitudePx.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
        val renderHeight = renderHeightPx.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
        val laneHeight = laneHeightPx.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
        val gap = verticalSafetyGapPx.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
        val safeMaximum = ((laneHeight - renderHeight - gap) / 2f).coerceAtLeast(0f)
        return minOf(desired, safeMaximum)
    }
}

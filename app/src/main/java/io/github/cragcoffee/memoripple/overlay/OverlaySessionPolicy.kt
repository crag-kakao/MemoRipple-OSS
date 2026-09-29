package io.github.cragcoffee.memoripple.overlay

import io.github.cragcoffee.memoripple.domain.playback.CommentLaneAllocator
import io.github.cragcoffee.memoripple.domain.playback.PlaybackTimeline
import io.github.cragcoffee.memoripple.domain.playback.PlaybackValidationIssue
import io.github.cragcoffee.memoripple.ui.playback.CommentTextMetrics
import io.github.cragcoffee.memoripple.ui.playback.resolveCommentLaneLayout
import java.util.concurrent.atomic.AtomicBoolean

const val OVERLAY_MAX_SESSION_MS = 120_000L
const val OVERLAY_WATCHDOG_MS = 122_000L
const val DEFAULT_OVERLAY_ALPHA = 0.70f
private const val MAXIMUM_OPACITY_MARGIN = 0.90f

enum class OverlayRotationStrategy { STOP_SESSION }
val OVERLAY_ROTATION_STRATEGY = OverlayRotationStrategy.STOP_SESSION

fun notificationPermissionAllowsOverlay(@Suppress("UNUSED_PARAMETER") granted: Boolean): Boolean = true

fun overlayWatchdogExpired(elapsedMillis: Long): Boolean = elapsedMillis >= OVERLAY_WATCHDOG_MS

enum class OverlayPreflightResult { ALLOWED, EMPTY, TOO_LONG }

fun overlayPreflight(timeline: PlaybackTimeline): OverlayPreflightResult = when {
    timeline.items.isEmpty() -> OverlayPreflightResult.EMPTY
    timeline.totalDurationMillis > OVERLAY_MAX_SESSION_MS -> OverlayPreflightResult.TOO_LONG
    else -> OverlayPreflightResult.ALLOWED
}

fun calculateSafeOverlayAlpha(maximumObscuringOpacity: Float?): Float {
    val platformLimit = maximumObscuringOpacity
        ?.takeIf(Float::isFinite)
        ?.coerceIn(0f, 1f)
        ?: return DEFAULT_OVERLAY_ALPHA
    return minOf(DEFAULT_OVERLAY_ALPHA, platformLimit * MAXIMUM_OPACITY_MARGIN).coerceIn(0f, 1f)
}

sealed interface OverlayTimelineResolution {
    data class Ready(val timeline: PlaybackTimeline) : OverlayTimelineResolution
    data object Empty : OverlayTimelineResolution
    data class TooLong(val timeline: PlaybackTimeline) : OverlayTimelineResolution
    data class CannotRenderFixedComment(val timeline: PlaybackTimeline) : OverlayTimelineResolution
}

data class OverlayPlaybackPlan(
    val resolution: OverlayTimelineResolution,
    val itemCount: Int,
    val estimatedDurationMillis: Long,
    val laneCount: Int,
    val displayRegion: OverlayDisplayRegion,
    val density: OverlayDensity,
) {
    val canStart: Boolean get() = resolution is OverlayTimelineResolution.Ready
}

class OverlayPlaybackPlanFactory(
    private val resolver: OverlayTimelineResolver = OverlayTimelineResolver(),
) {
    fun create(
        sourceTimeline: PlaybackTimeline,
        options: OverlayPlaybackOptions,
        renderWidthsPx: Map<Int, Float>,
        textMetrics: Map<Int, CommentTextMetrics> = emptyMap(),
        containerWidthPx: Float,
        availableHeightPx: Float,
        minimumLaneHeightPx: Float,
        verticalSafetyGapPx: Float = 0f,
        desiredWaveAmplitudePx: Float = 0f,
        longCommentReadability: Boolean = false,
    ): OverlayPlaybackPlan {
        val resolution = resolver.resolve(
            timeline = sourceTimeline.withOverlayDensity(options.density),
            renderWidthsPx = renderWidthsPx,
            textMetrics = textMetrics,
            containerWidthPx = containerWidthPx,
            availableHeightPx = availableHeightPx,
            minimumLaneHeightPx = minimumLaneHeightPx,
            verticalSafetyGapPx = verticalSafetyGapPx,
            desiredWaveAmplitudePx = desiredWaveAmplitudePx,
            longCommentReadability = longCommentReadability,
        )
        val resolved = when (resolution) {
            is OverlayTimelineResolution.Ready -> resolution.timeline
            is OverlayTimelineResolution.TooLong -> resolution.timeline
            is OverlayTimelineResolution.CannotRenderFixedComment -> resolution.timeline
            OverlayTimelineResolution.Empty -> null
        }
        val duration = when (resolution) {
            is OverlayTimelineResolution.Ready -> resolution.timeline.totalDurationMillis
            OverlayTimelineResolution.Empty -> 0L
            is OverlayTimelineResolution.TooLong -> resolution.timeline.totalDurationMillis
            is OverlayTimelineResolution.CannotRenderFixedComment -> resolution.timeline.totalDurationMillis
        }
        return OverlayPlaybackPlan(
            resolution = resolution,
            itemCount = sourceTimeline.items.size,
            estimatedDurationMillis = duration,
            laneCount = resolved?.laneCount ?: 0,
            displayRegion = options.displayRegion,
            density = options.density,
        )
    }
}


enum class OverlayStartDecision { START, CONFIRM_REPLACEMENT }

fun overlayStartDecision(state: OverlayPlaybackState): OverlayStartDecision =
    if (state.isActive) OverlayStartDecision.CONFIRM_REPLACEMENT else OverlayStartDecision.START

class OverlayTimelineResolver(
    private val laneAllocator: CommentLaneAllocator = CommentLaneAllocator(),
) {
    fun resolve(
        timeline: PlaybackTimeline,
        renderWidthsPx: Map<Int, Float>,
        textMetrics: Map<Int, CommentTextMetrics> = emptyMap(),
        containerWidthPx: Float,
        availableHeightPx: Float,
        minimumLaneHeightPx: Float,
        verticalSafetyGapPx: Float = 0f,
        desiredWaveAmplitudePx: Float = 0f,
        longCommentReadability: Boolean = false,
    ): OverlayTimelineResolution {
        if (timeline.items.isEmpty() || containerWidthPx <= 0f || availableHeightPx <= 0f) {
            return OverlayTimelineResolution.Empty
        }
        val fitted = resolveCommentLaneLayout(
            timeline = timeline,
            textMetrics = textMetrics,
            availableHeightPx = availableHeightPx,
            minimumLaneHeightPx = minimumLaneHeightPx,
            verticalSafetyGapPx = verticalSafetyGapPx,
            availableWidthPx = containerWidthPx,
            desiredWaveAmplitudePx = desiredWaveAmplitudePx,
            longCommentReadability = longCommentReadability,
        )
        if (fitted.validationIssue == PlaybackValidationIssue.FIXED_COMMENT_DOES_NOT_FIT) {
            return OverlayTimelineResolution.CannotRenderFixedComment(fitted)
        }
        val resolved = laneAllocator.allocate(fitted, renderWidthsPx, containerWidthPx)
        return when (overlayPreflight(resolved)) {
            OverlayPreflightResult.ALLOWED -> OverlayTimelineResolution.Ready(resolved)
            OverlayPreflightResult.EMPTY -> OverlayTimelineResolution.Empty
            OverlayPreflightResult.TOO_LONG -> OverlayTimelineResolution.TooLong(resolved)
        }
    }
}

class OverlayCleanupGuard {
    private val cleaned = AtomicBoolean(false)

    fun runOnce(cleanup: () -> Unit): Boolean {
        if (!cleaned.compareAndSet(false, true)) return false
        cleanup()
        return true
    }
}

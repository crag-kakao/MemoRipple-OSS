package io.github.cragcoffee.memoripple.overlay

import io.github.cragcoffee.memoripple.domain.playback.PlaybackEmphasis
import io.github.cragcoffee.memoripple.domain.playback.PlaybackItem
import io.github.cragcoffee.memoripple.domain.playback.PlaybackTimeline
import io.github.cragcoffee.memoripple.domain.playback.ResolvedPlaybackBehavior
import io.github.cragcoffee.memoripple.ui.playback.CommentTextMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlaySessionPolicyTest {
    @Test
    fun emptyTimelineIsRejectedBeforeServiceStart() {
        assertEquals(OverlayPreflightResult.EMPTY, overlayPreflight(PlaybackTimeline.Empty))
    }

    @Test
    fun durationAtCapIsAllowedAndDurationOverCapIsRejected() {
        assertEquals(
            OverlayPreflightResult.ALLOWED,
            overlayPreflight(timeline(OVERLAY_MAX_SESSION_MS)),
        )
        assertEquals(
            OverlayPreflightResult.TOO_LONG,
            overlayPreflight(timeline(OVERLAY_MAX_SESSION_MS + 1)),
        )
    }

    @Test
    fun api31AlphaStaysBelowPlatformMaximumWithMargin() {
        assertEquals(0.45f, calculateSafeOverlayAlpha(0.5f), 0.0001f)
        assertTrue(calculateSafeOverlayAlpha(0.8f) <= 0.8f)
    }

    @Test
    fun pre31AlphaUsesFixedSafeFallback() {
        assertEquals(DEFAULT_OVERLAY_ALPHA, calculateSafeOverlayAlpha(null), 0.0001f)
    }

    @Test
    fun notificationPermissionDenialDoesNotBlockOverlay() {
        assertTrue(notificationPermissionAllowsOverlay(granted = true))
        assertTrue(notificationPermissionAllowsOverlay(granted = false))
    }

    @Test
    fun watchdogStopsOnlyAtSafetyDeadline() {
        assertFalse(overlayWatchdogExpired(OVERLAY_WATCHDOG_MS - 1))
        assertTrue(overlayWatchdogExpired(OVERLAY_WATCHDOG_MS))
    }

    @Test
    fun windowPolicyUsesOneNonFocusableNontouchableApplicationOverlay() {
        assertEquals(1, OverlayWindowPolicy.ROOT_WINDOW_COUNT)
        assertEquals(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, OverlayWindowPolicy.TYPE)
        assertTrue(
            OverlayWindowPolicy.FLAGS and
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0,
        )
        assertTrue(
            OverlayWindowPolicy.FLAGS and
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0,
        )
        assertEquals(OverlayRotationStrategy.STOP_SESSION, OVERLAY_ROTATION_STRATEGY)
    }

    @Test
    fun resolverUsesAvailableBoundsAndExistingLaneAllocator() {
        val source = PlaybackTimeline(
            items = listOf(item(1, 0, 0), item(2, 0, 200)),
            laneCount = 6,
            totalDurationMillis = 8_700,
        )

        val result = OverlayTimelineResolver().resolve(
            timeline = source,
            renderWidthsPx = mapOf(1 to 260f, 2 to 260f),
            containerWidthPx = 1_080f,
            availableHeightPx = 80f,
            minimumLaneHeightPx = 40f,
        )

        val resolved = (result as OverlayTimelineResolution.Ready).timeline
        assertEquals(2, resolved.laneCount)
        assertEquals(2, resolved.items.size)
        assertTrue(resolved.items.all { it.laneIndex in 0..1 })
    }

    @Test
    fun cleanupIsIdempotentWhenNaturalEndAndStopRace() {
        val guard = OverlayCleanupGuard()
        var calls = 0

        assertTrue(guard.runOnce { calls += 1 })
        assertFalse(guard.runOnce { calls += 1 })
        assertEquals(1, calls)
    }

    @Test
    fun fixedCommentTooTallIsRejectedBeforeOverlayServicePlayback() {
        val fixed = item(1, 0, 4_000).copy(behavior = ResolvedPlaybackBehavior.FIXED)
        val result = OverlayTimelineResolver().resolve(
            timeline = PlaybackTimeline(listOf(fixed), 6, 4_000),
            renderWidthsPx = mapOf(1 to 200f),
            textMetrics = mapOf(1 to CommentTextMetrics(180f, 200f, 200f, 206f)),
            containerWidthPx = 320f,
            availableHeightPx = 180f,
            minimumLaneHeightPx = 40f,
            verticalSafetyGapPx = 4f,
        )

        assertTrue(result is OverlayTimelineResolution.CannotRenderFixedComment)
    }

    private fun timeline(total: Long) = PlaybackTimeline(
        items = listOf(item(1, 0, total.coerceAtLeast(1))),
        laneCount = 1,
        totalDurationMillis = total,
    )

    private fun item(id: Int, lane: Int, duration: Long) = PlaybackItem(
        id = id,
        text = "comment-$id",
        startTimeMillis = 0,
        travelDurationMillis = duration.coerceAtLeast(1),
        laneIndex = lane,
        fontScale = 1f,
        opacity = 1f,
        emphasis = PlaybackEmphasis.NORMAL,
    )
}

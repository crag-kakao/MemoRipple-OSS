package io.github.cragcoffee.memoripple.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentAnimatorTest {
    private val item = PlaybackItem(
        id = 0,
        text = "test",
        startTimeMillis = 100,
        travelDurationMillis = 1_000,
        laneIndex = 0,
        fontScale = 1f,
        opacity = 1f,
        emphasis = PlaybackEmphasis.NORMAL,
    )
    private val timeline = PlaybackTimeline(listOf(item), laneCount = 2, totalDurationMillis = 1_100)

    @Test
    fun pauseFreezesTimeAndResumeContinues() {
        val animator = CommentAnimator()
        animator.play(timeline)
        animator.advanceBy(80)
        animator.pause()
        animator.advanceBy(80)

        assertEquals(80L, animator.state.value.elapsedMillis)
        assertEquals(PlaybackStatus.PAUSED, animator.state.value.status)

        animator.resume()
        animator.advanceBy(40)
        assertEquals(120L, animator.state.value.elapsedMillis)
        assertEquals(PlaybackStatus.PLAYING, animator.state.value.status)
    }

    @Test
    fun reachingTimelineEndCleansAllTransientState() {
        val animator = CommentAnimator()
        animator.play(timeline)
        repeat(11) { animator.advanceBy(100) }

        assertEquals(PlaybackStatus.IDLE, animator.state.value.status)
        assertEquals(0L, animator.state.value.elapsedMillis)
        assertNull(animator.state.value.timeline)
    }

    @Test
    fun horizontalMotionMovesFromRightEdgeToBeyondLeftEdge() {
        val animator = CommentAnimator()
        val start = animator.horizontalOffsetPx(item, 100, 500f, 100f)
        val middle = animator.horizontalOffsetPx(item, 600, 500f, 100f)
        val end = animator.horizontalOffsetPx(item, 1_100, 500f, 100f)

        assertEquals(500f, start)
        assertTrue(middle < start)
        assertEquals(-100f, end)
    }

    @Test
    fun leftToRightMotionMirrorsEndpointsWithoutChangingDuration() {
        val animator = CommentAnimator()
        val ltr = item.copy(flowDirection = ResolvedFlowDirection.LEFT_TO_RIGHT)

        assertEquals(-100f, animator.horizontalOffsetPx(ltr, 100, 500f, 100f))
        assertEquals(200f, animator.horizontalOffsetPx(ltr, 600, 500f, 100f))
        assertEquals(500f, animator.horizontalOffsetPx(ltr, 1_100, 500f, 100f))
        assertEquals(item.travelDurationMillis, ltr.travelDurationMillis)
        assertEquals(item.startTimeMillis, ltr.startTimeMillis)
    }

    @Test
    fun pausedWaveKeepsTheSameAppClockProgressUntilResume() {
        val animator = CommentAnimator()
        animator.play(timeline)
        animator.advanceBy(100)
        val beforePause = CommentFlowPath.progress(
            animator.state.value.elapsedMillis,
            item.startTimeMillis,
            item.travelDurationMillis,
        )
        animator.pause()
        animator.advanceBy(100)
        val whilePaused = CommentFlowPath.progress(
            animator.state.value.elapsedMillis,
            item.startTimeMillis,
            item.travelDurationMillis,
        )
        animator.resume()
        animator.advanceBy(100)
        val afterResume = CommentFlowPath.progress(
            animator.state.value.elapsedMillis,
            item.startTimeMillis,
            item.travelDurationMillis,
        )

        assertEquals(beforePause, whilePaused, 0f)
        assertTrue(afterResume > whilePaused)
    }
}

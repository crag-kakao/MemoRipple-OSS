package io.github.cragcoffee.memoripple.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Duration and frame rate are separate things: a comment's crossing is a function of the
 * clock alone, so a 60Hz device, a 120Hz device, and a stuttering one all see the same
 * comment at the same place at the same moment — smoother hardware only draws more of the
 * same journey, never a faster one.
 */
class CommentFramePacingTest {

    private fun flowItem(
        id: Int = 0,
        start: Long = 0,
        duration: Long = 4_000,
        endless: Boolean = false,
        effect: ResolvedFlowEffect = ResolvedFlowEffect.STRAIGHT,
    ) = PlaybackItem(
        id = id,
        text = "comment-$id",
        startTimeMillis = start,
        travelDurationMillis = duration,
        laneIndex = 0,
        fontScale = 1f,
        opacity = 1f,
        emphasis = PlaybackEmphasis.NORMAL,
        flowEffect = effect,
        endless = endless,
    )

    private fun timelineOf(vararg items: PlaybackItem) = PlaybackTimeline(
        items = items.toList(),
        laneCount = 12,
        totalDurationMillis = items.maxOf { it.startTimeMillis + it.travelDurationMillis },
    )

    /** Runs one animator to [untilMillis] delivering [stepMillis] frames; returns it. */
    private fun runClock(timeline: PlaybackTimeline, stepMillis: Long, untilMillis: Long): CommentAnimator {
        val animator = CommentAnimator()
        animator.play(timeline)
        var delivered = 0L
        while (delivered + stepMillis <= untilMillis) {
            animator.advanceBy(stepMillis)
            delivered += stepMillis
        }
        return animator
    }

    @Test
    fun sixtyAndOneTwentyHertzAgreeOnEveryPosition() {
        val item = flowItem()
        val timeline = timelineOf(item)
        // 3 seconds of playback delivered as 50 × 60ms and as 100 × 30ms (whole-millisecond
        // frames, the way CommentPlaybackFrameClock hands them over).
        val coarse = runClock(timeline, stepMillis = 60, untilMillis = 3_000)
        val fine = runClock(timeline, stepMillis = 30, untilMillis = 3_000)

        assertEquals(3_000L, coarse.state.value.elapsedMillis)
        assertEquals(coarse.state.value.elapsedMillis, fine.state.value.elapsedMillis)
        val atCoarse = coarse.horizontalOffsetPx(item, coarse.state.value.elapsedMillis, 1_000f, 300f)
        val atFine = fine.horizontalOffsetPx(item, fine.state.value.elapsedMillis, 1_000f, 300f)
        assertEquals(atCoarse, atFine, 0f)
    }

    @Test
    fun theCrossingTakesItsDurationOnAnyCadence() {
        // The comment leaves the stage at exactly its travel duration whether frames come
        // every 16ms or every 96ms: elapsed time, not frame count, decides.
        val item = flowItem(duration = 4_000)
        val timeline = timelineOf(item)
        for (step in listOf(16L, 32L, 96L)) {
            val animator = CommentAnimator()
            animator.play(timeline)
            var delivered = 0L
            while (animator.state.value.status == PlaybackStatus.PLAYING) {
                animator.advanceBy(step)
                delivered += step
            }
            // The stream ends on the first frame at or past the total duration.
            assertTrue("step=$step ended after ${delivered}ms", delivered >= 4_000)
            assertTrue("step=$step overshot a whole frame", delivered < 4_000 + step)
        }
    }

    @Test
    fun aHitchNeverTeleportsACommentMoreThanTheClampAllows(){
        // One 500ms hitch delivers at most the 100ms clamp of motion: the comment slows
        // rather than jumping, and duration stays time-based from there on.
        val item = flowItem()
        val animator = CommentAnimator()
        animator.play(timelineOf(item))
        animator.advanceBy(1_000)
        assertEquals(100L, animator.state.value.elapsedMillis)
        animator.advanceBy(500)
        assertEquals(200L, animator.state.value.elapsedMillis)
    }

    @Test
    fun pauseAndResumeLandOnTheSamePathRegardlessOfCadence() {
        val item = flowItem()
        val timeline = timelineOf(item)
        val paused = CommentAnimator()
        paused.play(timeline)
        var delivered = 0L
        while (delivered < 1_500) { paused.advanceBy(30); delivered += 30 }
        paused.pause()
        repeat(40) { paused.advanceBy(16) } // frames while paused move nothing
        paused.resume()
        while (delivered < 3_000) { paused.advanceBy(30); delivered += 30 }

        val straight = runClock(timeline, stepMillis = 60, untilMillis = 3_000)
        assertEquals(straight.state.value.elapsedMillis, paused.state.value.elapsedMillis)
        assertEquals(
            straight.horizontalOffsetPx(item, 3_000, 1_000f, 300f),
            paused.horizontalOffsetPx(item, 3_000, 1_000f, 300f),
            0f,
        )
    }

    @Test
    fun loopWaveAndEndlessStayCadenceIndependentToo() {
        // A looping line read at the same elapsed moment sits at the same offset whichever
        // cadence carried the clock there — the modulo works on time, not on frames.
        val loop = flowItem(id = 1, duration = 4_000, endless = true, effect = ResolvedFlowEffect.WAVE)
        val timeline = timelineOf(loop, flowItem(id = 2, start = 0, duration = 9_000))
        val coarse = runClock(timeline, stepMillis = 96, untilMillis = 8_640)
        val fine = runClock(timeline, stepMillis = 16, untilMillis = 8_640)
        assertEquals(coarse.state.value.elapsedMillis, fine.state.value.elapsedMillis)
        val elapsed = coarse.state.value.elapsedMillis
        assertEquals(
            coarse.horizontalOffsetPx(loop, elapsed, 1_000f, 300f),
            fine.horizontalOffsetPx(loop, elapsed, 1_000f, 300f),
            0f,
        )
        // And the second crossing is the first crossing again.
        assertEquals(
            coarse.horizontalOffsetPx(loop, 1_000, 1_000f, 300f),
            coarse.horizontalOffsetPx(loop, 5_000, 1_000f, 300f),
            0f,
        )
    }
}

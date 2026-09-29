package io.github.cragcoffee.memoripple.ui.playback

import io.github.cragcoffee.memoripple.domain.playback.PlaybackEmphasis
import io.github.cragcoffee.memoripple.domain.playback.PlaybackLaneBand
import io.github.cragcoffee.memoripple.domain.playback.PlaybackItem
import io.github.cragcoffee.memoripple.domain.playback.PlaybackTimeline
import io.github.cragcoffee.memoripple.domain.playback.PlaybackValidationIssue
import io.github.cragcoffee.memoripple.domain.playback.ResolvedPlaybackBehavior
import io.github.cragcoffee.memoripple.domain.playback.ResolvedFlowEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentLaneLayoutTest {
    @Test
    fun maximumRenderedHeightDefinesEveryLaneAndPreventsVerticalOverlap() {
        val timeline = PlaybackTimeline(
            items = listOf(item(1), item(2), item(3)),
            laneCount = 6,
            totalDurationMillis = 9_000,
        )
        val metrics = mapOf(
            1 to metric(24f),
            2 to metric(36f),
            3 to metric(48f),
        )

        val resolved = resolveCommentLaneLayout(
            timeline = timeline,
            textMetrics = metrics,
            availableHeightPx = 180f,
            minimumLaneHeightPx = 40f,
            verticalSafetyGapPx = 4f,
        )

        assertEquals(52f, resolved.laneHeightPx, 0f)
        assertEquals(3, resolved.laneCount)
        assertTrue(resolved.laneHeightPx >= resolved.items.maxOf { it.renderHeightPx } + 4f)
        assertTrue(resolved.items.all { it.laneIndex in 0 until resolved.laneCount })
    }

    @Test
    fun placementBandsAreResolvedAgainstTheCurrentSurfaceLaneCount() {
        val timeline = PlaybackTimeline(
            items = listOf(
                item(1).copy(laneBand = PlaybackLaneBand.TOP),
                item(2).copy(laneBand = PlaybackLaneBand.MIDDLE),
                item(3).copy(laneBand = PlaybackLaneBand.BOTTOM),
            ),
            laneCount = 6,
            totalDurationMillis = 9_000,
        )

        val compact = resolveCommentLaneLayout(timeline, emptyMap(), 90f, 40f, 4f)
        val tall = resolveCommentLaneLayout(timeline, emptyMap(), 300f, 40f, 4f)

        assertEquals(2, compact.laneCount)
        assertEquals(listOf(0), compact.items[0].allowedLaneIndices)
        assertTrue(compact.items[1].allowedLaneIndices.single() in 0..1)
        assertEquals(listOf(1), compact.items[2].allowedLaneIndices)
        assertEquals(6, tall.laneCount)
        assertEquals(listOf(0, 1), tall.items[0].allowedLaneIndices)
        assertEquals(listOf(2, 3), tall.items[1].allowedLaneIndices)
        assertEquals(listOf(4, 5), tall.items[2].allowedLaneIndices)
    }

    @Test
    fun fixedCommentThatCannotFitSurfaceProducesSafeValidationIssue() {
        val fixed = item(1).copy(behavior = ResolvedPlaybackBehavior.FIXED)
        val timeline = PlaybackTimeline(listOf(fixed), 6, 4_000)
        val resolved = resolveCommentLaneLayout(
            timeline,
            mapOf(1 to CommentTextMetrics(80f, 196f, 100f, 202f)),
            availableHeightPx = 180f,
            minimumLaneHeightPx = 40f,
            verticalSafetyGapPx = 4f,
            availableWidthPx = 320f,
        )

        assertEquals(
            PlaybackValidationIssue.FIXED_COMMENT_DOES_NOT_FIT,
            resolved.validationIssue,
        )
    }

    @Test
    fun waveReservesActualHeightAndClampsAmplitudeInsideSmallSurfaceLane() {
        val wave = item(1).copy(flowEffect = ResolvedFlowEffect.WAVE)
        val roomy = resolveCommentLaneLayout(
            PlaybackTimeline(listOf(wave), 6, 9_000),
            mapOf(1 to metric(24f)),
            availableHeightPx = 120f,
            minimumLaneHeightPx = 20f,
            verticalSafetyGapPx = 4f,
            desiredWaveAmplitudePx = 6f,
        )
        assertEquals(40f, roomy.laneHeightPx, 0f)
        assertEquals(6f, roomy.items.single().waveAmplitudePx, 0f)

        val compact = resolveCommentLaneLayout(
            PlaybackTimeline(listOf(wave), 1, 9_000),
            mapOf(1 to metric(36f)),
            availableHeightPx = 42f,
            minimumLaneHeightPx = 20f,
            verticalSafetyGapPx = 4f,
            desiredWaveAmplitudePx = 6f,
        )
        assertEquals(1f, compact.items.single().waveAmplitudePx, 0f)
        assertTrue(
            compact.items.single().renderHeightPx +
                2 * compact.items.single().waveAmplitudePx + 4f <= compact.laneHeightPx,
        )
    }

    @Test
    fun fixedCommentNeverReservesOrAppliesSavedWaveAmplitude() {
        val fixedWave = item(1).copy(
            behavior = ResolvedPlaybackBehavior.FIXED,
            flowEffect = ResolvedFlowEffect.WAVE,
        )
        val resolved = resolveCommentLaneLayout(
            PlaybackTimeline(listOf(fixedWave), 6, 4_000),
            mapOf(1 to metric(24f)),
            availableHeightPx = 120f,
            minimumLaneHeightPx = 20f,
            verticalSafetyGapPx = 4f,
            desiredWaveAmplitudePx = 6f,
        )

        assertEquals(28f, resolved.laneHeightPx, 0f)
        assertEquals(0f, resolved.items.single().waveAmplitudePx, 0f)
    }

    private fun item(id: Int) = PlaybackItem(
        id,
        "comment $id",
        0,
        9_000,
        id - 1,
        1f,
        1f,
        PlaybackEmphasis.NORMAL,
    )

    private fun metric(height: Float) = CommentTextMetrics(100f, height - 6f, 118f, height)
}

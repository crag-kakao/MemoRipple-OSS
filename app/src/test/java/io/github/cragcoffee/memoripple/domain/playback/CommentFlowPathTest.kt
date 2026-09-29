package io.github.cragcoffee.memoripple.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentFlowPathTest {
    @Test
    fun twoCycleWaveIsFinitePeriodicAndClamped() {
        val offsets = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { progress ->
            CommentFlowPath.verticalOffsetPx(ResolvedFlowEffect.WAVE, progress, 6f, 2f)
        }

        offsets.forEach { offset ->
            assertTrue(offset.isFinite())
            assertTrue(offset in -6f..6f)
            assertEquals(0f, offset, 0.0001f)
        }
        assertEquals(
            CommentFlowPath.verticalOffsetPx(ResolvedFlowEffect.WAVE, 0f, 6f),
            CommentFlowPath.verticalOffsetPx(ResolvedFlowEffect.WAVE, -0.001f, 6f),
            0f,
        )
        assertEquals(
            CommentFlowPath.verticalOffsetPx(ResolvedFlowEffect.WAVE, 1f, 6f),
            CommentFlowPath.verticalOffsetPx(ResolvedFlowEffect.WAVE, 1.001f, 6f),
            0f,
        )
    }

    @Test
    fun straightAndInvalidInputsAlwaysResolveSafely() {
        assertEquals(
            0f,
            CommentFlowPath.verticalOffsetPx(
                ResolvedFlowEffect.STRAIGHT,
                Float.NaN,
                Float.POSITIVE_INFINITY,
            ),
            0f,
        )
        assertTrue(
            CommentFlowPath.horizontalOffsetPx(
                ResolvedFlowDirection.LEFT_TO_RIGHT,
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NaN,
            ).isFinite(),
        )
    }

    @Test
    fun amplitudeIsClampedByActualLaneGeometry() {
        assertEquals(6f, CommentFlowPath.effectiveAmplitudePx(6f, 24f, 40f, 4f), 0f)
        assertEquals(2f, CommentFlowPath.effectiveAmplitudePx(6f, 32f, 40f, 4f), 0f)
        assertEquals(0f, CommentFlowPath.effectiveAmplitudePx(6f, 42f, 40f, 4f), 0f)
    }
}

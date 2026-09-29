package io.github.cragcoffee.memoripple.domain.playback

import io.github.cragcoffee.memoripple.domain.comments.CommentPlacementRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentLaneBandResolverTest {
    private val resolver = CommentLaneBandResolver()

    @Test
    fun everyPlacementIsSafeForLaneCountsOneThroughEight() {
        (1..8).forEach { laneCount ->
            CommentPlacementRole.entries.forEach { placement ->
                val candidates = resolver.candidates(placement, laneCount)
                assertTrue("$placement at $laneCount", candidates.isNotEmpty())
                assertTrue(candidates.all { it in 0 until laneCount })
            }
        }
    }

    @Test
    fun oneLaneAlwaysResolvesToZero() {
        CommentPlacementRole.entries.forEach { placement ->
            assertEquals(listOf(0), resolver.candidates(placement, 1))
        }
    }

    @Test
    fun sixLanesPartitionByLaneCenters() {
        assertEquals(listOf(0, 1), resolver.candidates(CommentPlacementRole.TOP, 6))
        assertEquals(listOf(2, 3), resolver.candidates(CommentPlacementRole.MIDDLE, 6))
        assertEquals(listOf(4, 5), resolver.candidates(CommentPlacementRole.BOTTOM, 6))
        assertEquals((0..5).toList(), resolver.candidates(CommentPlacementRole.AUTO, 6))
    }

    @Test
    fun twoLaneMiddleUsesNearestCenterFallback() {
        val middle = resolver.candidates(CommentPlacementRole.MIDDLE, 2)
        assertEquals(1, middle.size)
        assertTrue(middle.single() in 0..1)
    }

    @Test
    fun twoThroughFiveLanesFollowCenterPartitionAndFallback() {
        assertEquals(listOf(0), resolver.candidates(CommentPlacementRole.TOP, 2))
        assertEquals(listOf(0), resolver.candidates(CommentPlacementRole.MIDDLE, 2))
        assertEquals(listOf(1), resolver.candidates(CommentPlacementRole.BOTTOM, 2))

        assertEquals(listOf(0), resolver.candidates(CommentPlacementRole.TOP, 3))
        assertEquals(listOf(1), resolver.candidates(CommentPlacementRole.MIDDLE, 3))
        assertEquals(listOf(2), resolver.candidates(CommentPlacementRole.BOTTOM, 3))

        assertEquals(listOf(0), resolver.candidates(CommentPlacementRole.TOP, 4))
        assertEquals(listOf(1, 2), resolver.candidates(CommentPlacementRole.MIDDLE, 4))
        assertEquals(listOf(3), resolver.candidates(CommentPlacementRole.BOTTOM, 4))

        assertEquals(listOf(0, 1), resolver.candidates(CommentPlacementRole.TOP, 5))
        assertEquals(listOf(2), resolver.candidates(CommentPlacementRole.MIDDLE, 5))
        assertEquals(listOf(3, 4), resolver.candidates(CommentPlacementRole.BOTTOM, 5))
    }
}

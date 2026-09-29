package io.github.cragcoffee.memoripple.overlay

import io.github.cragcoffee.memoripple.domain.playback.CommentLaneAllocator
import io.github.cragcoffee.memoripple.domain.playback.PlaybackEmphasis
import io.github.cragcoffee.memoripple.domain.playback.PlaybackItem
import io.github.cragcoffee.memoripple.domain.playback.PlaybackLaneBand
import io.github.cragcoffee.memoripple.domain.playback.PlaybackTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPlaybackOptionsTest {
    @Test
    fun storageIdsDecodeWithSafePhase5aFallbacks() {
        assertEquals(OverlayDisplayRegion.TOP_HALF, OverlayDisplayRegion.fromStorageId("top_half"))
        assertEquals(OverlayDisplayRegion.FULL, OverlayDisplayRegion.fromStorageId("unknown"))
        assertEquals(OverlayDensity.DENSE, OverlayDensity.fromStorageId("dense"))
        assertEquals(OverlayDensity.STANDARD, OverlayDensity.fromStorageId(null))
    }

    @Test
    fun allRegionsUseUsableBoundsAndExpectedVerticalFractions() {
        val usable = OverlayUsableBounds(width = 1_080, height = 2_000, x = 10, y = 80)

        assertEquals(usable, OverlayWindowPolicy.regionBounds(usable, OverlayDisplayRegion.FULL))
        assertEquals(
            OverlayUsableBounds(1_080, 1_000, 10, 80),
            OverlayWindowPolicy.regionBounds(usable, OverlayDisplayRegion.TOP_HALF),
        )
        assertEquals(
            OverlayUsableBounds(1_080, 1_000, 10, 580),
            OverlayWindowPolicy.regionBounds(usable, OverlayDisplayRegion.CENTER),
        )
        assertEquals(
            OverlayUsableBounds(1_080, 1_000, 10, 1_080),
            OverlayWindowPolicy.regionBounds(usable, OverlayDisplayRegion.BOTTOM_HALF),
        )
    }

    @Test
    fun densityChangesOnlyStartTimesAndPreservesOrderMotionAndTypography() {
        val source = timeline(
            item(1, start = 100, duration = 4_000, lane = 0, fontScale = 1.25f),
            item(2, start = 1_000, duration = 5_000, lane = 1, fontScale = 0.9f),
        )

        val sparse = source.withOverlayDensity(OverlayDensity.SPARSE)
        val standard = source.withOverlayDensity(OverlayDensity.STANDARD)
        val dense = source.withOverlayDensity(OverlayDensity.DENSE)

        assertEquals(listOf(130L, 1_300L), sparse.items.map { it.startTimeMillis })
        assertEquals(source, standard)
        assertEquals(listOf(80L, 800L), dense.items.map { it.startTimeMillis })
        assertEquals(source.items.map { it.id }, dense.items.map { it.id })
        assertEquals(source.items.map { it.travelDurationMillis }, dense.items.map { it.travelDurationMillis })
        assertEquals(source.items.map { it.fontScale }, dense.items.map { it.fontScale })
    }

    @Test
    fun sharedPlanFactoryOrdersSparseStandardDenseWithoutAllowingCollision() {
        val source = timeline(
            item(1, start = 0, duration = 5_000, lane = 0),
            item(2, start = 2_000, duration = 5_000, lane = 0),
            item(3, start = 4_000, duration = 5_000, lane = 0),
        )
        val widths = source.items.associate { it.id to 180f }
        val factory = OverlayPlaybackPlanFactory()
        fun plan(density: OverlayDensity) = factory.create(
            sourceTimeline = source,
            options = OverlayPlaybackOptions(density = density),
            renderWidthsPx = widths,
            containerWidthPx = 1_080f,
            availableHeightPx = 40f,
            minimumLaneHeightPx = 40f,
        )

        val sparse = plan(OverlayDensity.SPARSE)
        val standard = plan(OverlayDensity.STANDARD)
        val dense = plan(OverlayDensity.DENSE)
        assertTrue(sparse.estimatedDurationMillis >= standard.estimatedDurationMillis)
        assertTrue(standard.estimatedDurationMillis >= dense.estimatedDurationMillis)

        val allocator = CommentLaneAllocator()
        listOf(sparse, standard, dense).forEach { candidate ->
            val resolved = (candidate.resolution as OverlayTimelineResolution.Ready).timeline
            resolved.items.zipWithNext().forEach { (first, second) ->
                assertFalse(
                    allocator.collides(first, 180f, second, 180f, 1_080f),
                )
            }
        }
    }

    @Test
    fun planUsesRegionHeightForLanesAndFinalResolvedDurationForCap() {
        // ニコ式 allocation never delays a comment, so the cap is exceeded by the
        // timeline's own length rather than by queueing.
        val source = timeline(
            item(1, start = 0, duration = 125_000, lane = 0),
            item(2, start = 1, duration = 125_000, lane = 1),
        ).copy(laneCount = 6)
        val factory = OverlayPlaybackPlanFactory()

        val plan = factory.create(
            sourceTimeline = source,
            options = OverlayPlaybackOptions(displayRegion = OverlayDisplayRegion.TOP_HALF),
            renderWidthsPx = mapOf(1 to 600f, 2 to 600f),
            containerWidthPx = 1_080f,
            availableHeightPx = 40f,
            minimumLaneHeightPx = 40f,
        )

        assertEquals(1, plan.laneCount)
        assertTrue(plan.estimatedDurationMillis > OVERLAY_MAX_SESSION_MS)
        assertTrue(plan.resolution is OverlayTimelineResolution.TooLong)
        assertFalse(plan.canStart)
    }

    @Test
    fun emptySharedPlanCannotStart() {
        val plan = OverlayPlaybackPlanFactory().create(
            sourceTimeline = PlaybackTimeline.Empty,
            options = OverlayPlaybackOptions(),
            renderWidthsPx = emptyMap(),
            containerWidthPx = 1_080f,
            availableHeightPx = 2_000f,
            minimumLaneHeightPx = 40f,
        )

        assertEquals(0, plan.itemCount)
        assertEquals(OverlayTimelineResolution.Empty, plan.resolution)
        assertFalse(plan.canStart)
    }

    @Test
    fun previewAndServiceInputsProduceStableEquivalentPlans() {
        val source = timeline(
            item(1, start = 0, duration = 4_000, lane = 0),
            item(2, start = 600, duration = 4_500, lane = 1),
        )
        val options = OverlayPlaybackOptions(
            displayRegion = OverlayDisplayRegion.CENTER,
            density = OverlayDensity.DENSE,
        )
        val factory = OverlayPlaybackPlanFactory()
        fun create() = factory.create(
            sourceTimeline = source,
            options = options,
            renderWidthsPx = mapOf(1 to 120f, 2 to 160f),
            containerWidthPx = 1_080f,
            availableHeightPx = 900f,
            minimumLaneHeightPx = 40f,
        )

        assertEquals(create(), create())
    }

    @Test
    fun placementIsRelativeToTheSelectedOverlayRegionSurface() {
        val source = timeline(
            item(1, start = 0, duration = 8_500, lane = 0).copy(
                laneBand = PlaybackLaneBand.BOTTOM,
            ),
        ).copy(laneCount = 6)

        val plan = OverlayPlaybackPlanFactory().create(
            sourceTimeline = source,
            options = OverlayPlaybackOptions(displayRegion = OverlayDisplayRegion.TOP_HALF),
            renderWidthsPx = mapOf(1 to 180f),
            containerWidthPx = 1_080f,
            availableHeightPx = 120f,
            minimumLaneHeightPx = 40f,
        )
        val resolved = (plan.resolution as OverlayTimelineResolution.Ready).timeline

        assertEquals(3, resolved.laneCount)
        assertEquals(2, resolved.items.single().laneIndex)
        assertEquals(listOf(2), resolved.items.single().allowedLaneIndices)
    }

    private fun timeline(vararg items: PlaybackItem) = PlaybackTimeline(
        items = items.toList(),
        laneCount = maxOf(1, items.maxOfOrNull { it.laneIndex + 1 } ?: 1),
        totalDurationMillis = items.maxOfOrNull { it.startTimeMillis + it.travelDurationMillis } ?: 0L,
    )

    private fun item(
        id: Int,
        start: Long,
        duration: Long,
        lane: Int,
        fontScale: Float = 1f,
    ) = PlaybackItem(
        id = id,
        text = "comment-$id",
        startTimeMillis = start,
        travelDurationMillis = duration,
        laneIndex = lane,
        fontScale = fontScale,
        opacity = 1f,
        emphasis = PlaybackEmphasis.NORMAL,
    )
}

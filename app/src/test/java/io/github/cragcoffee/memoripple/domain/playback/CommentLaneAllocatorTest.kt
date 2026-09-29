package io.github.cragcoffee.memoripple.domain.playback

import io.github.cragcoffee.memoripple.domain.WorkCommentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ニコニコ式の契約: a comment is never delayed — its moment is its own — lanes fill from the
 * top using the catch-up-aware collision mathematics, and when every lane is taken the
 * comment overlaps on a deterministic pseudo-random lane, the way the video site crowds into
 * 弾幕 rather than queueing politely.
 */
class CommentLaneAllocatorTest {
    private val allocator = CommentLaneAllocator(safetyGapPx = 12f)
    private val containerWidth = 400f

    @Test
    fun aCommentIsNeverDelayedWhateverTheCrowd() {
        val items = (0 until 40).map { index -> item(id = index, start = index * 100L) }
        val widths = items.associate { it.id to 300f }

        val result = allocator.allocate(
            PlaybackTimeline(items, laneCount = 3, totalDurationMillis = 20_000),
            widths,
            containerWidth,
        )

        result.items.forEachIndexed { index, resolved ->
            assertEquals(items[index].startTimeMillis, resolved.startTimeMillis)
        }
    }

    @Test
    fun twoCommentsShareTheTopWhenOneLaneIsEnough() {
        // The second enters well after the first's tail has cleared the right edge.
        val first = item(id = 0, start = 0)
        val second = item(id = 1, start = 3_000)
        val widths = mapOf(0 to 80f, 1 to 80f)

        val result = allocator.allocate(
            PlaybackTimeline(listOf(first, second), 6, 8_000),
            widths,
            containerWidth,
        )

        assertEquals(listOf(0, 0), result.items.map(PlaybackItem::laneIndex))
        assertEquals(80f, result.items[1].renderWidthPx, 0f)
    }

    @Test
    fun aCloseFollowerStepsDownALaneInsteadOfWaiting() {
        val first = item(id = 0, start = 0)
        val second = item(id = 1, start = 300)
        val widths = mapOf(0 to 80f, 1 to 80f)

        val result = allocator.allocate(
            PlaybackTimeline(listOf(first, second), 6, 5_000),
            widths,
            containerWidth,
        )

        assertEquals(0, result.items[0].laneIndex)
        assertEquals(1, result.items[1].laneIndex)
        assertEquals(300L, result.items[1].startTimeMillis)
    }

    @Test
    fun aWideFasterCommentAvoidsTheLaneItWouldOvertake() {
        // Same crossing time means the wide one moves faster and would catch the narrow one.
        val narrow = item(id = 0, start = 0, duration = 4_000)
        val wide = item(id = 1, start = 900, duration = 4_000)
        val widths = mapOf(0 to 80f, 1 to 720f)

        val result = allocator.allocate(
            PlaybackTimeline(listOf(narrow, wide), 6, 5_000),
            widths,
            containerWidth,
        )

        assertTrue(result.items[1].laneIndex != result.items[0].laneIndex)
        assertNoCollisionsWhereLanesDiffer(result, widths)
    }

    @Test
    fun overflowLandsOnADeterministicLaneAndOverlaps() {
        val occupying = (0 until 3).map { lane ->
            item(id = lane, start = 0, duration = 10_000)
        }
        val latecomer = item(id = 7, start = 200, duration = 4_000)
        val widths = (listOf(0, 1, 2, 7)).associateWith { 240f }

        val once = allocator.allocate(
            PlaybackTimeline(occupying + latecomer, 3, 10_000),
            widths,
            containerWidth,
        )
        val twice = allocator.allocate(
            PlaybackTimeline(occupying + latecomer, 3, 10_000),
            widths,
            containerWidth,
        )

        // No delay, a lane inside the stage, and the same lane every time it is laid out.
        assertEquals(200L, once.items.last().startTimeMillis)
        assertTrue(once.items.last().laneIndex in 0 until 3)
        assertEquals(once.items.last().laneIndex, twice.items.last().laneIndex)
    }

    @Test
    fun aHardTopBandOverflowStaysInsideItsBand() {
        val occupying = listOf(0, 1).map { lane ->
            item(id = lane, start = 0, lane = lane, duration = 12_000).copy(
                laneBand = PlaybackLaneBand.TOP,
            )
        }
        val latecomer = item(id = 2, start = 200, duration = 6_000).copy(
            laneBand = PlaybackLaneBand.TOP,
        )
        val widths = mapOf(0 to 220f, 1 to 220f, 2 to 220f)

        val result = allocator.allocate(
            PlaybackTimeline(occupying + latecomer, 6, 12_000),
            widths,
            containerWidth,
        )

        assertTrue(result.items.last().laneIndex in listOf(0, 1))
        assertEquals(200L, result.items.last().startTimeMillis)
    }

    @Test
    fun oppositeDirectionsNeverShareALaneWhileBothAreVisible() {
        val rtl = item(0, 0, 4_000)
        val ltr = item(1, 500, 4_000).copy(
            flowDirection = ResolvedFlowDirection.LEFT_TO_RIGHT,
        )
        val widths = mapOf(0 to 180f, 1 to 180f)

        val result = allocator.allocate(
            PlaybackTimeline(listOf(rtl, ltr), 6, 5_000),
            widths,
            containerWidth,
        )

        assertTrue(result.items[0].laneIndex != result.items[1].laneIndex)
        assertEquals(500L, result.items[1].startTimeMillis)
    }

    @Test
    fun reportedOutlineSequenceKeepsEveryMomentAndEveryLaneInside() {
        val lines = WorkCommentParser().parse(
            """
            # 主人公
            - 性格
              - 明るい
              - 楽天家
              - 少し飽きっぽい
            - 好きなもの
              - コーヒー
              - アニメ
              - 迷宮探索
            """.trimIndent(),
        )
        val source = CommentPlaybackPlanner().plan(lines)
        val widths = source.items.associate { item ->
            item.id to (item.text.length * 32f + 28f)
        }

        val result = allocator.allocate(source, widths, containerWidth)

        result.items.forEachIndexed { index, resolved ->
            assertEquals(source.items[index].startTimeMillis, resolved.startTimeMillis)
            assertTrue(resolved.laneIndex in 0 until source.laneCount)
        }
    }

    @Test
    fun thousandMixedFlowCommentsProduceACompleteFinitePlan() {
        val items = (0 until 1_000).map { index ->
            item(
                id = index,
                start = index * 120L,
                duration = if (index % 3 == 0) 4_000L else 3_300L,
                lane = index % 12,
            ).copy(
                flowDirection = if (index % 2 == 0) {
                    ResolvedFlowDirection.RIGHT_TO_LEFT
                } else {
                    ResolvedFlowDirection.LEFT_TO_RIGHT
                },
                flowEffect = if (index % 4 < 2) {
                    ResolvedFlowEffect.STRAIGHT
                } else {
                    ResolvedFlowEffect.WAVE
                },
            )
        }
        val widths = items.associate { it.id to (80f + it.id % 7 * 20f) }

        val result = allocator.allocate(
            PlaybackTimeline(items, 12, items.maxOf { it.startTimeMillis + it.travelDurationMillis }),
            widths,
            containerWidth,
        )

        assertEquals(1_000, result.items.size)
        assertTrue(result.totalDurationMillis > 0L)
        result.items.forEachIndexed { index, resolved ->
            assertEquals(items[index].startTimeMillis, resolved.startTimeMillis)
            assertTrue(resolved.laneIndex in 0 until 12)
        }
    }

    @Test
    fun fixedItemsStackFromTheirEdgeAndKeepTheirMoments() {
        val first = item(0, 0, duration = 3_000, lane = 0).copy(
            behavior = ResolvedPlaybackBehavior.FIXED,
            laneBand = PlaybackLaneBand.TOP,
            laneOrder = PlaybackLaneOrder.ASCENDING,
        )
        val second = item(1, 300, duration = 3_000, lane = 4).copy(
            behavior = ResolvedPlaybackBehavior.FIXED,
            laneBand = PlaybackLaneBand.TOP,
            laneOrder = PlaybackLaneOrder.ASCENDING,
        )
        val result = allocator.allocate(
            PlaybackTimeline(listOf(first, second), 6, 3_300),
            mapOf(0 to 200f, 1 to 200f),
            containerWidth,
        )

        assertEquals(listOf(0, 1), result.items.map(PlaybackItem::laneIndex))
        assertEquals(300L, result.items[1].startTimeMillis)
    }

    @Test
    fun fixedTopAndBottomCanStartAtTheSameTime() {
        val top = item(0, 200, duration = 3_000).copy(
            behavior = ResolvedPlaybackBehavior.FIXED,
            laneBand = PlaybackLaneBand.TOP,
            laneOrder = PlaybackLaneOrder.ASCENDING,
        )
        val bottom = item(1, 200, duration = 3_000).copy(
            behavior = ResolvedPlaybackBehavior.FIXED,
            laneBand = PlaybackLaneBand.BOTTOM,
            laneOrder = PlaybackLaneOrder.DESCENDING,
        )
        val result = allocator.allocate(
            PlaybackTimeline(listOf(top, bottom), 6, 3_200),
            mapOf(0 to 180f, 1 to 180f),
            containerWidth,
        )

        assertEquals(listOf(200L, 200L), result.items.map(PlaybackItem::startTimeMillis))
        assertTrue(result.items.first().laneIndex < result.items.last().laneIndex)
    }

    @Test
    fun collisionMathematicsStillKnowsACatchUpWhenItSeesOne() {
        // Same lane, same width-speed law: the wide follower reaches the narrow leader.
        val narrow = item(0, 0, duration = 4_000)
        val wide = item(1, 900, duration = 4_000)
        assertTrue(
            allocator.collides(
                first = narrow,
                firstWidthPx = 80f,
                second = wide.copy(laneIndex = narrow.laneIndex),
                secondWidthPx = 720f,
                containerWidthPx = containerWidth,
            ),
        )
        // With a whole crossing between them there is nothing to catch.
        assertFalse(
            allocator.collides(
                first = narrow,
                firstWidthPx = 80f,
                second = wide.copy(laneIndex = narrow.laneIndex, startTimeMillis = 4_500),
                secondWidthPx = 720f,
                containerWidthPx = containerWidth,
            ),
        )
    }

    private fun assertNoCollisionsWhereLanesDiffer(
        timeline: PlaybackTimeline,
        widths: Map<Int, Float>,
    ) {
        timeline.items.groupBy(PlaybackItem::laneIndex).values.forEach { laneItems ->
            laneItems.forEachIndexed { index, first ->
                laneItems.drop(index + 1).forEach { second ->
                    assertFalse(
                        allocator.collides(
                            first = first,
                            firstWidthPx = widths.getValue(first.id),
                            second = second,
                            secondWidthPx = widths.getValue(second.id),
                            containerWidthPx = containerWidth,
                        ),
                    )
                }
            }
        }
    }

    private fun item(
        id: Int,
        start: Long,
        duration: Long = 4_000,
        lane: Int = 0,
        text: String = "comment-$id",
    ) = PlaybackItem(
        id = id,
        text = text,
        startTimeMillis = start,
        travelDurationMillis = duration,
        laneIndex = lane,
        fontScale = 1f,
        opacity = 1f,
        emphasis = PlaybackEmphasis.NORMAL,
    )
}

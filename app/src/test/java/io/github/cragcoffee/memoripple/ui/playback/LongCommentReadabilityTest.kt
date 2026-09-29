package io.github.cragcoffee.memoripple.ui.playback

import io.github.cragcoffee.memoripple.domain.WorkCommentParser
import io.github.cragcoffee.memoripple.domain.playback.CommentLaneAllocator
import io.github.cragcoffee.memoripple.domain.playback.CommentPlaybackComposer
import io.github.cragcoffee.memoripple.domain.playback.CommentPlaybackPlanner
import io.github.cragcoffee.memoripple.domain.playback.PlaybackContentMode
import io.github.cragcoffee.memoripple.domain.playback.PlaybackEmphasis
import io.github.cragcoffee.memoripple.domain.playback.PlaybackItem
import io.github.cragcoffee.memoripple.domain.playback.PlaybackTimeline
import io.github.cragcoffee.memoripple.domain.playback.ResolvedPlaybackBehavior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 長いコメントを読みやすくする: off is the ニコニコ準拠 4-second standard, untouched; on
 * stretches only wide flowing comments, by measured width, never past 1.5×.
 */
class LongCommentReadabilityTest {

    private val container = 1_000f

    private fun metricsFor(item: PlaybackItem, widthPx: Float) = mapOf(
        item.id to CommentTextMetrics(
            measuredTextWidthPx = widthPx,
            measuredTextHeightPx = 40f,
            renderWidthPx = widthPx,
            renderHeightPx = 44f,
        ),
    )

    private fun flowItem(
        id: Int = 0,
        duration: Long = 4_000,
        adjustable: Boolean = true,
        behavior: ResolvedPlaybackBehavior = ResolvedPlaybackBehavior.FLOW,
    ) = PlaybackItem(
        id = id,
        text = "comment-$id",
        startTimeMillis = 0,
        travelDurationMillis = duration,
        laneIndex = 0,
        fontScale = 1f,
        opacity = 1f,
        emphasis = PlaybackEmphasis.NORMAL,
        behavior = behavior,
        readabilityAdjustable = adjustable,
    )

    private fun resolved(
        item: PlaybackItem,
        widthPx: Float,
        enabled: Boolean,
    ): PlaybackItem = resolveCommentLaneLayout(
        timeline = PlaybackTimeline(listOf(item), laneCount = 12, totalDurationMillis = 10_000),
        textMetrics = metricsFor(item, widthPx),
        availableHeightPx = 1_200f,
        minimumLaneHeightPx = 40f,
        verticalSafetyGapPx = 4f,
        availableWidthPx = container,
        longCommentReadability = enabled,
    ).items.single()

    @Test
    fun offKeepsTheFourSecondStandardForEveryWidth() {
        assertEquals(4_000, resolved(flowItem(), widthPx = 200f, enabled = false).travelDurationMillis)
        assertEquals(4_000, resolved(flowItem(), widthPx = 3_000f, enabled = false).travelDurationMillis)
    }

    @Test
    fun onLeavesShortCommentsAtFourSecondsExactly() {
        // At or under the stage's own width nothing changes: the aid is for overshoot only.
        assertEquals(4_000, resolved(flowItem(), widthPx = 300f, enabled = true).travelDurationMillis)
        assertEquals(4_000, resolved(flowItem(), widthPx = 1_000f, enabled = true).travelDurationMillis)
    }

    @Test
    fun onStretchesGentlyAndStopsAtTheCap() {
        // ratio 1.5 → factor 1.25 → 5 seconds.
        assertEquals(5_000, resolved(flowItem(), widthPx = 1_500f, enabled = true).travelDurationMillis)
        // ratio 2.0 → factor 1.5 → the 6-second ceiling.
        assertEquals(6_000, resolved(flowItem(), widthPx = 2_000f, enabled = true).travelDurationMillis)
        // And no further, however long the line runs.
        assertEquals(6_000, resolved(flowItem(), widthPx = 9_000f, enabled = true).travelDurationMillis)
    }

    @Test
    fun measuredWidthDecidesNotCharacterCount() {
        // The same judgement applied to two widths: only the wide one stretches — the
        // factor never looks at the text at all.
        val narrow = resolved(flowItem(id = 1), widthPx = 900f, enabled = true)
        val wide = resolved(flowItem(id = 2), widthPx = 1_800f, enabled = true)
        assertEquals(4_000, narrow.travelDurationMillis)
        assertTrue(wide.travelDurationMillis > 4_000)
    }

    @Test
    fun anExplicitSpeedModifierOutranksTheAid() {
        // ←← / ←←← lines leave the planner marked non-adjustable; a very wide such line
        // keeps its writer-chosen crossing even with the aid on.
        val fast = resolved(
            flowItem(duration = 3_333, adjustable = false),
            widthPx = 3_000f,
            enabled = true,
        )
        assertEquals(3_333, fast.travelDurationMillis)

        val planned = CommentPlaybackPlanner().plan(
            WorkCommentParser().parse("- 長い長い長い一行 ←←\n- ふつうの行"),
        )
        assertFalse(planned.items.first { it.text.contains("長い") }.readabilityAdjustable)
        assertTrue(planned.items.first { it.text == "ふつうの行" }.readabilityAdjustable)
    }

    @Test
    fun fixedCommentsAreNeverStretched() {
        val fixed = resolved(
            flowItem(duration = 3_000, behavior = ResolvedPlaybackBehavior.FIXED),
            widthPx = 3_000f,
            enabled = true,
        )
        assertEquals(3_000, fixed.travelDurationMillis)
    }

    @Test
    fun speedMultipliersRideOnTopOfTheStretchedBase() {
        // The aid is a factor on whatever duration the item already carries, so a slow or
        // fast per-comment role (a longer or shorter base) keeps its meaning: ratio 2.0
        // stretches both by exactly 1.5×.
        assertEquals(
            7_500,
            resolved(flowItem(duration = 5_000), widthPx = 2_000f, enabled = true)
                .travelDurationMillis,
        )
        assertEquals(
            4_500,
            resolved(flowItem(duration = 3_000), widthPx = 2_000f, enabled = true)
                .travelDurationMillis,
        )
    }

    @Test
    fun b01RegressionTheStretchedPipeStaysChronologicalAndAllocatable() {
        // 溜め and ×N with the aid on: compose sorts, resolve stretches, allocate holds.
        val planned = CommentPlaybackPlanner().plan(
            WorkCommentParser().parse("- 遅い ...\n- 早い\n- 弾幕 ×3\n- 次の行"),
        )
        val composed = CommentPlaybackComposer()
            .compose(planned, emptyList(), PlaybackContentMode.WORK_ONLY)
        val metrics = composed.items.associate { item ->
            item.id to CommentTextMetrics(2_400f, 40f, 2_400f, 44f)
        }
        val fitted = resolveCommentLaneLayout(
            timeline = composed,
            textMetrics = metrics,
            availableHeightPx = 1_200f,
            minimumLaneHeightPx = 40f,
            verticalSafetyGapPx = 4f,
            availableWidthPx = container,
            longCommentReadability = true,
        )
        assertEquals(
            fitted.items.map(PlaybackItem::startTimeMillis),
            fitted.items.map(PlaybackItem::startTimeMillis).sorted(),
        )
        // Every stretched crossing reaches the allocator, which must accept the plan whole.
        assertTrue(fitted.items.all { it.travelDurationMillis == 6_000L })
        val allocated = CommentLaneAllocator(safetyGapPx = 8f).allocate(
            fitted,
            fitted.items.associate { it.id to 2_400f },
            containerWidthPx = container,
        )
        assertEquals(composed.items.size, allocated.items.size)
        // The allocator's total rides the stretched durations, not the old four seconds.
        assertEquals(
            allocated.items.maxOf { it.startTimeMillis + it.travelDurationMillis },
            allocated.totalDurationMillis,
        )
    }
}

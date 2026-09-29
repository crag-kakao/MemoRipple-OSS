package io.github.cragcoffee.memoripple.domain.playback

import io.github.cragcoffee.memoripple.domain.WorkCommentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentPlaybackPlannerTest {
    private val parser = WorkCommentParser()
    private val planner = CommentPlaybackPlanner()

    @Test
    fun mapsOutlineSemanticsToFinalPlaybackStyles() {
        val timeline = planner.plan(
            parser.parse(
                """
                # 見出し
                - 親
                  - 子
                > 補足
                ! 重要
                ? 疑問
                """.trimIndent(),
            ),
        )

        val heading = timeline.items[0]
        val parent = timeline.items[1]
        val child = timeline.items[2]
        val note = timeline.items[3]
        val important = timeline.items[4]

        assertTrue(heading.fontScale > parent.fontScale)
        // ニコニコの呼吸: every flowing line crosses in the same four seconds, headings too
        // — a heading reads bigger, not slower.
        assertEquals(parent.travelDurationMillis, heading.travelDurationMillis)
        assertTrue(child.fontScale < parent.fontScale)
        assertTrue(child.startTimeMillis - parent.startTimeMillis > 300L)
        assertTrue(child.laneIndex != parent.laneIndex)
        assertTrue(note.opacity < parent.opacity)
        assertEquals(PlaybackEmphasis.STRONG, important.emphasis)
        assertEquals(12, timeline.laneCount)
        assertEquals(
            timeline.items.maxOf { it.startTimeMillis + it.travelDurationMillis },
            timeline.totalDurationMillis,
        )
    }

    @Test
    fun headingAndQuestionInsertMoreTimeThanStandardItems() {
        val items = planner.plan(parser.parse("- A\n- B\n? C\n# D")).items
        val standardGap = items[1].startTimeMillis - items[0].startTimeMillis
        val questionGap = items[2].startTimeMillis - items[1].startTimeMillis
        val headingGap = items[3].startTimeMillis - items[2].startTimeMillis

        assertTrue(questionGap > standardGap)
        assertTrue(headingGap > standardGap)
    }

    @Test
    fun emptyOutlineCreatesNoTimelineWork() {
        assertEquals(PlaybackTimeline.Empty, planner.plan(emptyList()))
    }
    @Test
    fun lineEndModifiersShapeTheFlyingItem() {
        val items = planner.plan(
            parser.parseOutline("! 大事な話 → ++ {赤} ~"),
        ).items
        val item = items.single()
        assertEquals("大事な話", item.text)
        assertEquals(ResolvedFlowDirection.LEFT_TO_RIGHT, item.flowDirection)
        assertEquals(ResolvedFlowEffect.WAVE, item.flowEffect)
        assertEquals(
            io.github.cragcoffee.memoripple.domain.comments.CommentColorRole.RED,
            item.colorRole,
        )
        // 特大 multiplies the line's own IMPORTANT scale.
        assertEquals(1.1f * 1.85f, item.fontScale, 0.001f)
    }

    @Test
    fun fixedModifiersPinTheLineToItsBand() {
        val top = planner.plan(parser.parseOutline("- 上のことば ↑")).items.single()
        assertEquals(ResolvedPlaybackBehavior.FIXED, top.behavior)
        assertEquals(PlaybackLaneBand.TOP, top.laneBand)
        val bottom = planner.plan(parser.parseOutline("- 下のことば ↓")).items.single()
        assertEquals(ResolvedPlaybackBehavior.FIXED, bottom.behavior)
        assertEquals(PlaybackLaneBand.BOTTOM, bottom.laneBand)
    }

    @Test
    fun speedStepsShortenTheFlightAndBlinkRidesTheEffect() {
        val plain = planner.plan(parser.parseOutline("- ことば")).items.single()
        val fast = planner.plan(parser.parseOutline("- ことば ←←")).items.single()
        val fastest = planner.plan(parser.parseOutline("- ことば ←←←")).items.single()
        assertTrue(fast.travelDurationMillis < plain.travelDurationMillis)
        assertTrue(fastest.travelDurationMillis < fast.travelDurationMillis)
        val blink = planner.plan(parser.parseOutline("- ことば *")).items.single()
        assertEquals(ResolvedFlowEffect.BLINK, blink.flowEffect)
    }

    @Test
    fun repeatFliesCopiesAndDelayHoldsTheLineBack() {
        val volley = planner.plan(parser.parseOutline("- 弾幕 ×3")).items
        assertEquals(3, volley.size)
        assertEquals(volley.map { it.text }.toSet(), setOf("弾幕"))
        assertEquals(3, volley.map { it.id }.distinct().size)
        assertTrue(volley[1].startTimeMillis > volley[0].startTimeMillis)

        val prompt = planner.plan(parser.parseOutline("- 早い\n- 遅い ...")).items
        val held = planner.plan(parser.parseOutline("- 早い\n- 遅い")).items
        assertEquals(
            held[1].startTimeMillis +
                io.github.cragcoffee.memoripple.domain.comments.CommentLineModifiers.DELAY_MILLIS,
            prompt[1].startTimeMillis,
        )
    }

    @Test
    fun theEndlessTokensReachThePlaybackItemAndKeepTheReadingAlive() {
        val timeline = planner.plan(
            parser.parse(
                """
                - ずっと流れる ↺
                ! 置いておく ↑↑
                - 普通に流れる
                """.trimIndent(),
            ),
        )
        val looping = timeline.items.first { it.text == "ずっと流れる" }
        val pinned = timeline.items.first { it.text == "置いておく" }
        val ordinary = timeline.items.first { it.text == "普通に流れる" }

        assertTrue(looping.endless)
        assertTrue(pinned.endless)
        assertEquals(ResolvedPlaybackBehavior.FIXED, pinned.behavior)
        assertEquals(ResolvedPlaybackBehavior.FLOW, looping.behavior)
        assertFalse(ordinary.endless)
        assertTrue(timeline.hasEndless)
        // One crossing (or one holding) is still what the duration describes.
        // 3 seconds is one holding — the pin simply repeats it until 停止.
        assertEquals(3_000L, pinned.travelDurationMillis)
    }

    @Test
    fun aReadingWithoutEndlessCommentsSaysSo() {
        val timeline = planner.plan(parser.parse("- ただ流れる"))
        assertFalse(timeline.hasEndless)
    }
}

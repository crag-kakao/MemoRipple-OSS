package io.github.cragcoffee.memoripple.domain.playback

import io.github.cragcoffee.memoripple.domain.WorkCommentScope
import io.github.cragcoffee.memoripple.domain.WorkLine
import io.github.cragcoffee.memoripple.domain.WorkLineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoWallTimelineFactoryTest {

    private val factory = MemoWallTimelineFactory()

    private fun lines(vararg texts: String) =
        texts.mapIndexed { index, text -> WorkLine(text, WorkLineType.ITEM, 0, index) }

    @Test
    fun theMemosSpeakInTurnRatherThanOneAfterAnother() {
        val merged = factory.interleave(
            listOf(lines("A1", "A2", "A3"), lines("B1", "B2"), lines("C1")),
        )

        assertEquals(
            listOf("A1", "B1", "C1", "A2", "B2", "A3"),
            merged.map(WorkLine::text),
        )
    }

    @Test
    fun aWallWithNothingOnItHasNothingToSay() {
        assertTrue(factory.interleave(emptyList()).isEmpty())
        assertTrue(factory.interleave(listOf(emptyList(), emptyList())).isEmpty())
        assertEquals(
            PlaybackTimeline.Empty,
            factory.create(listOf("", "   "), WorkCommentScope.OUTLINE),
        )
    }

    @Test
    fun theStreamStopsRatherThanRunningOnForever() {
        val many = List(40) { memo -> lines(*Array(20) { "memo$memo-$it" }) }

        val merged = factory.interleave(many)

        assertEquals(MemoWallTimelineFactory.MAX_ITEMS, merged.size)
        // It stops mid-round, so the early memos are not the only ones heard from.
        assertTrue(merged.map { it.text.substringBefore('-') }.distinct().size > 1)
    }

    @Test
    fun theOutlineScopeCarriesOnlyTheBonesAndTheBodyScopeCarriesTheProse() {
        val bodies = listOf("■ 見出し\n散文です。", "- 項目")

        val outline = factory.create(bodies, WorkCommentScope.OUTLINE)
        val body = factory.create(bodies, WorkCommentScope.BODY)

        assertEquals(listOf("見出し", "項目"), outline.items.map(PlaybackItem::text))
        assertEquals(listOf("見出し", "項目", "散文です。"), body.items.map(PlaybackItem::text))
    }

    @Test
    fun theStreamIsPlannedAsOnePieceSoNothingArrivesAtTheSameMoment() {
        val timeline = factory.create(
            listOf("- 一\n- 二", "- 三\n- 四"),
            WorkCommentScope.OUTLINE,
        )

        assertEquals(4, timeline.items.size)
        val starts = timeline.items.map(PlaybackItem::startTimeMillis)
        assertEquals(starts.sorted(), starts)
        assertEquals(starts.distinct().size, starts.size)
    }
}

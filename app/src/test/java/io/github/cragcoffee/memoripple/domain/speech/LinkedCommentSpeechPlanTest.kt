package io.github.cragcoffee.memoripple.domain.speech

import io.github.cragcoffee.memoripple.domain.comments.CommentLinkMarkers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkedCommentSpeechPlanTest {

    private fun stripped(raw: String) = CommentLinkMarkers.strip(raw)

    @Test
    fun rangeModeFiresAtTheReportedPositionExactlyOnce() {
        val plan = LinkedCommentSpeechPlan.plan(
            LinkedCommentSpeechPlan.Mode.RANGE,
            preambleTexts = listOf("題名"),
            body = stripped("序盤[R1]中盤[R2]終盤"),
            maxInputLength = 100,
        )
        assertEquals(listOf("題名", "序盤中盤終盤"), plan.segments)
        // The preamble neither fires nor advances anything.
        assertTrue(plan.onSegmentStart(0).isEmpty())
        assertTrue(plan.onSegmentStart(1).isEmpty())
        assertTrue(plan.onSegmentRange(1, 1).isEmpty())
        assertEquals(listOf(1), plan.onSegmentRange(1, 2))
        assertEquals(emptyList<Int>(), plan.onSegmentRange(1, 2))
        assertEquals(listOf(2), plan.onSegmentRange(1, 4))
        assertTrue(plan.sawRange)
        assertTrue(plan.onFinished().isEmpty())
    }

    @Test
    fun rangeModeCatchesUpAcrossChunkBoundariesAndAtTheEnd() {
        // A tiny engine limit forces the body into several chunks with exact bases.
        val body = stripped("あいう[R1]えおかき[R2]くけこ")
        val plan = LinkedCommentSpeechPlan.plan(
            LinkedCommentSpeechPlan.Mode.RANGE,
            preambleTexts = emptyList(),
            body = body,
            maxInputLength = 60,
        )
        assertEquals(body.text, plan.segments.joinToString(""))
        // A device that never reports ranges still fires everything by the end.
        plan.segments.indices.forEach { plan.onSegmentStart(it) }
        assertEquals(listOf(1, 2), plan.onFinished())
    }

    @Test
    fun piecesModeFiresWhenTheMarkersPieceStarts() {
        val plan = LinkedCommentSpeechPlan.plan(
            LinkedCommentSpeechPlan.Mode.PIECES,
            preambleTexts = listOf("題名"),
            body = stripped("導入[R1]本論[R2][R3]結び"),
            maxInputLength = 100,
        )
        assertEquals(listOf("題名", "導入", "本論", "結び"), plan.segments)
        assertTrue(plan.onSegmentStart(0).isEmpty())
        assertTrue(plan.onSegmentStart(1).isEmpty())
        assertEquals(listOf(1), plan.onSegmentStart(2))
        assertEquals(listOf(2, 3), plan.onSegmentStart(3))
        assertTrue(plan.onFinished().isEmpty())
    }

    @Test
    fun piecesModeHandsTrailingMarkersToTheFinish() {
        val plan = LinkedCommentSpeechPlan.plan(
            LinkedCommentSpeechPlan.Mode.PIECES,
            preambleTexts = emptyList(),
            body = stripped("[R1]全部[R2]"),
            maxInputLength = 100,
        )
        assertEquals(listOf("全部"), plan.segments)
        assertEquals(listOf(1), plan.onSegmentStart(0))
        assertEquals(listOf(2), plan.onFinished())
    }

    @Test
    fun bodyOffsetsServeTheFollowAlongCursorInBothModes() {
        val range = LinkedCommentSpeechPlan.plan(
            LinkedCommentSpeechPlan.Mode.RANGE,
            preambleTexts = listOf("題名"),
            body = stripped("序盤[R1]終盤"),
            maxInputLength = 100,
        )
        // The preamble has no place on the page; the body maps through its chunk base.
        assertEquals(null, range.bodyOffsetOf(0))
        assertEquals(0, range.bodyOffsetOf(1))
        assertEquals(3, range.bodyOffsetOf(1, 3))

        val pieces = LinkedCommentSpeechPlan.plan(
            LinkedCommentSpeechPlan.Mode.PIECES,
            preambleTexts = listOf("題名"),
            body = stripped("導入[R1]本論"),
            maxInputLength = 100,
        )
        assertEquals(listOf("題名", "導入", "本論"), pieces.segments)
        assertEquals(null, pieces.bodyOffsetOf(0))
        assertEquals(0, pieces.bodyOffsetOf(1))
        // 本論 begins where the marker stood — offset 2 in the stripped body.
        assertEquals(2, pieces.bodyOffsetOf(2))
    }

    @Test
    fun aBodyWithoutMarkersIsJustAReading() {
        val plan = LinkedCommentSpeechPlan.plan(
            LinkedCommentSpeechPlan.Mode.PIECES,
            preambleTexts = listOf("題名"),
            body = stripped("そのままの本文。"),
            maxInputLength = 100,
        )
        assertEquals(listOf("題名", "そのままの本文。"), plan.segments)
        plan.segments.indices.forEach { assertTrue(plan.onSegmentStart(it).isEmpty()) }
        assertTrue(plan.onFinished().isEmpty())
    }
}

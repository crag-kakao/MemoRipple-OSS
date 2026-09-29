package io.github.cragcoffee.memoripple.domain.comments

import io.github.cragcoffee.memoripple.domain.ReadableMarkup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentLinkMarkersTest {

    @Test
    fun markersAreFoundWithTheirNumbersAndSpans() {
        val text = "はじめ[R1]なか[R12]おわり"
        val markers = CommentLinkMarkers.markersIn(text)
        assertEquals(listOf(1, 12), markers.map { it.number })
        assertEquals("[R1]", text.substring(markers[0].start, markers[0].endExclusive))
        assertEquals("[R12]", text.substring(markers[1].start, markers[1].endExclusive))
    }

    @Test
    fun strippingRemovesMarkersAndKeepsTheirPlaces() {
        val stripped = CommentLinkMarkers.strip("はじめ[R1]なか[R2]おわり")
        assertEquals("はじめなかおわり", stripped.text)
        assertEquals(listOf(1, 2), stripped.markers.map { it.number })
        // [R1] stood right after はじめ (offset 3), [R2] after なか (offset 5).
        assertEquals(listOf(3, 5), stripped.markers.map { it.offset })
    }

    @Test
    fun edgesAndRunsOfMarkersKeepTheirOffsets() {
        // At the very start, back to back in the middle, and at the very end.
        val stripped = CommentLinkMarkers.strip("[R1]あ[R2][R3]い[R4]")
        assertEquals("あい", stripped.text)
        assertEquals(listOf(1, 2, 3, 4), stripped.markers.map { it.number })
        assertEquals(listOf(0, 1, 1, 2), stripped.markers.map { it.offset })
    }

    @Test
    fun gapsInNumberingAreJustNumbers() {
        // Deleted comments never renumber, so [R2] may be all that remains.
        val stripped = CommentLinkMarkers.strip("残った[R2]だけ")
        assertEquals(listOf(2), stripped.markers.map { it.number })
    }

    @Test
    fun whatIsNotAMarkerStaysText() {
        val text = "これは[R]でも[Rx]でも[R 1]でも[r1]でもない。[R99999]も長すぎる。"
        assertTrue(CommentLinkMarkers.markersIn(text).isEmpty())
        assertEquals(text, CommentLinkMarkers.strip(text).text)
    }

    @Test
    fun occurrenceCountingServesTheDeleteWarning() {
        val body = "一つ[R1]、二つ[R1]、別[R2]"
        assertEquals(2, CommentLinkMarkers.occurrenceCount(body, 1))
        assertEquals(1, CommentLinkMarkers.occurrenceCount(body, 2))
        assertEquals(0, CommentLinkMarkers.occurrenceCount(body, 3))
    }

    @Test
    fun speechPiecesCutExactlyAtTheMarkers() {
        val pieces = CommentLinkMarkers.speechPieces(
            CommentLinkMarkers.strip("導入[R1]本論[R2][R3]結び"),
        )
        assertEquals(listOf("導入", "本論", "結び"), pieces.map { it.text })
        assertEquals(listOf(emptyList(), listOf(1), listOf(2, 3)), pieces.map { it.leadingNumbers })
    }

    @Test
    fun speechPiecesHandleMarkersAtBothEnds() {
        val pieces = CommentLinkMarkers.speechPieces(
            CommentLinkMarkers.strip("[R1]全部[R2]"),
        )
        // Leading marker heads the spoken piece; the trailing one gets an empty piece of its own.
        assertEquals(listOf("全部", ""), pieces.map { it.text })
        assertEquals(listOf(listOf(1), listOf(2)), pieces.map { it.leadingNumbers })
    }

    @Test
    fun aBodyWithoutMarkersIsOnePlainPiece() {
        val pieces = CommentLinkMarkers.speechPieces(CommentLinkMarkers.strip("そのまま。"))
        assertEquals(1, pieces.size)
        assertEquals("そのまま。", pieces.single().text)
        assertTrue(pieces.single().leadingNumbers.isEmpty())
    }

    @Test
    fun thePassTrackerFiresEachMarkerOncePerPass() {
        val stripped = CommentLinkMarkers.strip("あ[R1]いう[R2]え[R2]お")
        val tracker = CommentLinkMarkers.PassTracker(stripped.markers)
        assertEquals(emptyList<Int>(), tracker.advanceTo(0))
        assertEquals(listOf(1), tracker.advanceTo(1))
        // Reporting the same position again fires nothing more.
        assertEquals(emptyList<Int>(), tracker.advanceTo(1))
        // Jumping far fires everything passed, in order — the duplicate number both times.
        assertEquals(listOf(2, 2), tracker.advanceTo(99))
        assertEquals(emptyList<Int>(), tracker.finish())
    }

    @Test
    fun theTrackerFinishFiresWhatTheEngineNeverReported() {
        val stripped = CommentLinkMarkers.strip("あ[R1]い[R2]")
        val tracker = CommentLinkMarkers.PassTracker(stripped.markers)
        assertEquals(listOf(1, 2), tracker.finish())
    }

    @Test
    fun readingTextDropsMarkersAndAnchorsTheirDots() {
        val readable = ReadableMarkup.of("前[R1]の続き[R7]")
        assertEquals("前の続き", readable.text)
        assertEquals(listOf(1, 7), readable.linkMarkers.map { it.number })
        // The dot rides the character just before where the marker stood.
        assertEquals(listOf(1, 4), readable.linkMarkers.map { it.offset })
        assertEquals(0, readable.linkMarkers[0].anchorStart)
        assertEquals(3, readable.linkMarkers[1].anchorStart)
    }

    @Test
    fun aMarkerOpeningTheLineStandsAlone() {
        val readable = ReadableMarkup.of("[R3]行のはじまり")
        assertEquals("行のはじまり", readable.text)
        assertTrue(readable.linkMarkers.single().standsAlone)
    }

    @Test
    fun markersCoexistWithTheOtherInlineMarks() {
        val readable = ReadableMarkup.of("**強い**[R1]と[[構成案]][R2]")
        assertEquals("強いと構成案", readable.text)
        assertEquals(listOf(1, 2), readable.linkMarkers.map { it.number })
        // [R1] stood right after 強い (rendered offset 2), [R2] at the rendered end.
        assertEquals(listOf(2, 6), readable.linkMarkers.map { it.offset })
    }
}

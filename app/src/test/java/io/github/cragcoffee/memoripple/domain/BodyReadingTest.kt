package io.github.cragcoffee.memoripple.domain

import io.github.cragcoffee.memoripple.domain.comments.CommentColorRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyReadingTest {

    private val body = """
        # 見出し一
        - 項目
        本文
          # 子見出し
        - 子項目
        # 見出し二
        あと
    """.trimIndent()

    private val lines = BodyReading.lines(body)

    @Test
    fun aMemoBecomesAnOutlineWhenAMarkIsPutOnIt() {
        assertFalse(BodyReading.hasStructure("ただの本文です。\n二行目も本文。"))
        assertFalse(BodyReading.hasStructure(""))

        assertTrue(BodyReading.hasStructure("■ 見出し"))
        assertTrue(BodyReading.hasStructure("本文\n- 項目"))
        assertTrue(BodyReading.hasStructure("本文\n- [ ] やること"))
        assertTrue(BodyReading.hasStructure("> 補足"))
        assertTrue(BodyReading.hasStructure("## 取り込んだ見出し"))
    }

    @Test
    fun aMarkNeedsItsSpaceToCountAsOne() {
        // The same characters without the space are just words, and words are not structure.
        assertFalse(BodyReading.hasStructure("#仕事のこと"))
        assertFalse(BodyReading.hasStructure("-1℃だった"))
    }

    @Test
    fun blankLinesAreSpacingRatherThanContent() {
        val read = BodyReading.lines("一行目\n\n\n二行目")

        assertEquals(listOf("一行目", "二行目"), read.map(ReadingLine::text))
        // The place in the source survives, so a tap can still be written back.
        assertEquals(listOf(0, 3), read.map(ReadingLine::sourceLine))
    }

    @Test
    fun aHeadingOwnsEverythingUntilTheNextHeadingAtItsLevelOrAbove() {
        val owned = BodyReading.ownedLines(lines, 0)!!

        assertEquals(1..4, owned)
        assertEquals(4, BodyReading.hiddenCount(lines, 0))
    }

    @Test
    fun aDeeperHeadingOwnsOnlyItsOwnLines() {
        assertEquals(4..4, BodyReading.ownedLines(lines, 3))
    }

    @Test
    fun aHeadingWithNothingUnderItIsNotFoldable() {
        val trailing = BodyReading.lines("本文\n# 最後の見出し")

        assertFalse(BodyReading.isFoldable(trailing, 1))
        assertNull(BodyReading.ownedLines(trailing, 1))
    }

    @Test
    fun aLineThatIsNotAHeadingOwnsNothing() {
        assertNull(BodyReading.ownedLines(lines, 1))
    }

    @Test
    fun foldingAHeadingHidesWhatItOwnsAndNothingElse() {
        val visible = BodyReading.visible(lines, setOf(0))

        assertEquals(listOf("見出し一", "見出し二", "あと"), visible.map(ReadingLine::text))
    }

    @Test
    fun foldingAParentTakesItsNestedHeadingWithIt() {
        val visible = BodyReading.visible(lines, setOf(0, 3))

        assertEquals(listOf("見出し一", "見出し二", "あと"), visible.map(ReadingLine::text))
    }

    @Test
    fun foldingOnlyTheChildLeavesTheParentOpen() {
        val visible = BodyReading.visible(lines, setOf(3))

        assertEquals(
            listOf("見出し一", "項目", "本文", "子見出し", "見出し二", "あと"),
            visible.map(ReadingLine::text),
        )
    }

    @Test
    fun nothingFoldedShowsEverything() {
        assertEquals(lines, BodyReading.visible(lines, emptySet()))
    }

    @Test
    fun everyFoldableHeadingIsListedForFoldingThemAtOnce() {
        // 見出し二 owns the line after it too, so it folds like the other two.
        assertEquals(setOf(0, 3, 5), BodyReading.foldableHeadings(lines))
    }

    @Test
    fun tappingACheckboxFlipsOnlyThatLine() {
        val tasks = "- [ ] 一\n- [ ] 二"

        assertEquals("- [x] 一\n- [ ] 二", WorkOutlineEditing.toggleTaskAt(tasks, 0))
        assertEquals("- [ ] 一\n- [x] 二", WorkOutlineEditing.toggleTaskAt(tasks, 1))
    }

    @Test
    fun tappingSomethingThatIsNotATaskChangesNothing() {
        assertEquals("本文", WorkOutlineEditing.toggleTaskAt("本文", 0))
        assertEquals("本文", WorkOutlineEditing.toggleTaskAt("本文", 9))
    }

    @Test
    fun anIndentedTaskKeepsItsIndentWhenTapped() {
        assertEquals("    - [x] 子", WorkOutlineEditing.toggleTaskAt("    - [ ] 子", 0))
    }

    @Test
    fun readingDropsTheMarkersAndKeepsWhatTheyDid() {
        val readable = ReadableMarkup.of("これは**太字**と==red:色==です")

        assertEquals("これは太字と色です", readable.text)
        assertEquals(
            listOf(RenderedKind.BOLD, RenderedKind.HIGHLIGHT),
            readable.spans.map(RenderedSpan::kind),
        )
        assertEquals("太字", readable.text.substring(readable.spans[0].start, readable.spans[0].end))
        assertEquals("色", readable.text.substring(readable.spans[1].start, readable.spans[1].end))
        assertEquals(CommentColorRole.RED, readable.spans[1].color)
    }

    @Test
    fun aReferenceLosesItsBracketsAndStaysReachable() {
        val readable = ReadableMarkup.of("see [[Other Note]] later")

        assertEquals("see Other Note later", readable.text)
        assertEquals("Other Note", readable.linkAt(readable.text.indexOf("Other")))
        assertNull(readable.linkAt(0))
    }

    @Test
    fun aLineWithoutMarkersIsLeftExactlyAsItIs() {
        val readable = ReadableMarkup.of("ただの本文")

        assertEquals("ただの本文", readable.text)
        assertTrue(readable.spans.isEmpty())
    }

    @Test
    fun decorationAndAReferenceOnTheSameLineBothLand() {
        val readable = ReadableMarkup.of("**太字**と[[リンク]]")

        assertEquals("太字とリンク", readable.text)
        assertEquals("リンク", readable.linkAt(readable.text.indexOf("リンク")))
        assertEquals(
            "太字",
            readable.text.substring(readable.spans.first().start, readable.spans.first().end),
        )
    }
}

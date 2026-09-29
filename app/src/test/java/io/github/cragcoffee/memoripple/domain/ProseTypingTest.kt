package io.github.cragcoffee.memoripple.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProseTypingTest {

    @Test
    fun aBracketOpenedByHandIsClosedForYou() {
        val closed = ProseTyping.closeBracket("彼は", OutlineEdit("彼は「", 3, 3))
        assertEquals(OutlineEdit("彼は「」", 3, 3), closed)
    }

    @Test
    fun aPastedParagraphIsNotSomeoneOpeningAQuote() {
        assertNull(ProseTyping.closeBracket("", OutlineEdit("「長い引用が丸ごと貼られた」", 14, 14)))
    }

    @Test
    fun aBracketThatAlreadyHasItsPartnerIsLeftAlone() {
        assertNull(ProseTyping.closeBracket("」", OutlineEdit("「」", 1, 1)))
    }

    @Test
    fun typingAnythingElseAddsNothing() {
        assertNull(ProseTyping.closeBracket("あ", OutlineEdit("あい", 2, 2)))
    }

    @Test
    fun bracketsGoAroundWhatWasSelectedAndKeepItSelected() {
        val wrapped = ProseTyping.wrap(OutlineEdit("行くぞと言った", 0, 3), "「", "」")
        assertEquals("「行くぞ」と言った", wrapped.text)
        assertEquals(1, wrapped.selectionStart)
        assertEquals(4, wrapped.selectionEnd)
    }

    @Test
    fun anEmptyPairPutsTheCaretBetweenTheBrackets() {
        val wrapped = ProseTyping.wrap(OutlineEdit("彼は", 2, 2), "「", "」")
        assertEquals("彼は「」", wrapped.text)
        assertEquals(3, wrapped.selectionStart)
        assertEquals(3, wrapped.selectionEnd)
    }

    @Test
    fun rubyRunsCarryTheReadingAndLeaveTheProseWhole() {
        val runs = ProseTyping.rubyRuns("朝、｜言葉《ことば》が来た。")
        org.junit.Assert.assertEquals(
            listOf(
                ProseTyping.RubyRun("朝、"),
                ProseTyping.RubyRun("言葉", "ことば"),
                ProseTyping.RubyRun("が来た。"),
            ),
            runs,
        )
    }

    @org.junit.Test
    fun anEmptyReadingCollapsesToPlainWords() {
        org.junit.Assert.assertEquals(
            listOf(ProseTyping.RubyRun("言葉", null)),
            ProseTyping.rubyRuns("｜言葉《》"),
        )
    }

    @org.junit.Test
    fun proseWithoutRubyIsOneRun() {
        org.junit.Assert.assertEquals(
            listOf(ProseTyping.RubyRun("ただの文。")),
            ProseTyping.rubyRuns("ただの文。"),
        )
    }

    @org.junit.Test
    fun rubyTakesTheSelectionAsItsBaseAndWaitsForTheReading() {
        val ruby = ProseTyping.insertRuby(OutlineEdit("燈台守がいた", 0, 3))
        assertEquals("｜燈台守《》がいた", ruby.text)
        // The caret sits inside the brackets, where the reading is typed.
        assertEquals(5, ruby.selectionStart)
    }

    @Test
    fun rubyWithNothingSelectedWaitsForTheWordFirst() {
        val ruby = ProseTyping.insertRuby(OutlineEdit("", 0, 0))
        assertEquals("｜《》", ruby.text)
        assertEquals(1, ruby.selectionStart)
    }

    @Test
    fun readingAWorkLeavesTheWordsAndTakesTheReadings() {
        assertEquals("燈台守はもういない", ProseTyping.strip("｜燈台守《とうだいもり》はもういない"))
        assertEquals(listOf("とうだいもり"), ProseTyping.readings("｜燈台守《とうだいもり》はもういない"))
    }

    @Test
    fun anEmptyReadingStillReadsAsItsWord() {
        assertEquals("燈台守", ProseTyping.strip("｜燈台守《》"))
        assertEquals(emptyList<String>(), ProseTyping.readings("｜燈台守《》"))
    }

    @Test
    fun aBodyCarryingRubyIsCountedAndSearchedByItsWords() {
        assertEquals("港に着いた", BodyText.readable("｜港《みなと》に着いた"))
    }

    @Test
    fun theVoiceMayTakeTheReadingsInsteadOfTheWords() {
        assertEquals(
            "とうだいもりはもういない",
            ProseTyping.stripToReadings("｜燈台守《とうだいもり》はもういない"),
        )
        // A form with no reading falls back to its own words.
        assertEquals("燈台守", ProseTyping.stripToReadings("｜燈台守《》"))
        // Plain prose passes through untouched.
        assertEquals("ただの文章", ProseTyping.stripToReadings("ただの文章"))
    }

    @Test
    fun spokenTextFollowsTheRubyChoice() {
        val raw = "｜戦《いくさ》では負けた"
        assertEquals("戦では負けた", BodyText.spoken(raw, readRubyReadings = false))
        assertEquals("いくさでは負けた", BodyText.spoken(raw, readRubyReadings = true))
        // Markers stay for the offset table either way.
        assertEquals(
            "いくさ [R1] のこと",
            BodyText.spokenKeepingMarkers("｜戦《いくさ》 [R1] のこと", readRubyReadings = true),
        )
    }
}

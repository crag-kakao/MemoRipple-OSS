package io.github.cragcoffee.memoripple.domain.comments

import io.github.cragcoffee.memoripple.domain.comments.CommentLineModifiers.LineModifiers
import io.github.cragcoffee.memoripple.domain.comments.CommentLineModifiers.SizeStep
import io.github.cragcoffee.memoripple.domain.comments.CommentLineModifiers.SpeedStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentLineModifiersTest {

    private fun strip(line: String) = CommentLineModifiers.strip(line)

    @Test
    fun everyTokenIsRecognisedAndRemovedInBothSpellings() {
        val cases: List<Pair<String, (LineModifiers) -> Boolean>> = listOf(
            "←" to { it.direction == CommentFlowDirection.RIGHT_TO_LEFT },
            "<-" to { it.direction == CommentFlowDirection.RIGHT_TO_LEFT },
            "→" to { it.direction == CommentFlowDirection.LEFT_TO_RIGHT },
            "->" to { it.direction == CommentFlowDirection.LEFT_TO_RIGHT },
            "↑" to { it.mode == CommentMotionMode.FIXED_TOP },
            "^" to { it.mode == CommentMotionMode.FIXED_TOP },
            "↓" to { it.mode == CommentMotionMode.FIXED_BOTTOM },
            "v" to { it.mode == CommentMotionMode.FIXED_BOTTOM },
            "←←" to { it.speed == SpeedStep.FAST },
            "<<-" to { it.speed == SpeedStep.FAST },
            "→→" to {
                it.speed == SpeedStep.FAST &&
                    it.direction == CommentFlowDirection.LEFT_TO_RIGHT
            },
            "->>" to { it.speed == SpeedStep.FAST },
            "←←←" to { it.speed == SpeedStep.FASTEST },
            "<<<-" to { it.speed == SpeedStep.FASTEST },
            "→→→" to { it.speed == SpeedStep.FASTEST },
            "->>>" to { it.speed == SpeedStep.FASTEST },
            "+" to { it.size == SizeStep.LARGE },
            "++" to { it.size == SizeStep.X_LARGE },
            "-" to { it.size == SizeStep.SMALL },
            "~" to { it.wave },
            "*" to { it.blink },
            "..." to { it.delayed },
            "×3" to { it.repeat == 3 },
            "x4" to { it.repeat == 4 },
            "{赤}" to { it.colorRole == CommentColorRole.RED },
            "{BLUE}" to { it.colorRole == CommentColorRole.BLUE },
            "{灰}" to { it.colorRole == CommentColorRole.GRAY },
            "{white}" to { it.colorRole == CommentColorRole.DEFAULT },
        )
        cases.forEach { (token, check) ->
            val stripped = strip("本文のことば $token")
            assertEquals("$token should leave the text", "本文のことば", stripped.text)
            assertTrue("$token should be read", check(stripped.modifiers))
        }
    }

    @Test
    fun severalTokensCombineAndTheSpecExampleReads() {
        val stripped = strip("大事な話 ↑ ++ {赤}")
        assertEquals("大事な話", stripped.text)
        assertEquals(CommentMotionMode.FIXED_TOP, stripped.modifiers.mode)
        assertEquals(SizeStep.X_LARGE, stripped.modifiers.size)
        assertEquals(CommentColorRole.RED, stripped.modifiers.colorRole)
    }

    @Test
    fun tokensAwayFromTheLineEndAreProse() {
        val stripped = strip("本文 ↑ の続き")
        assertEquals("本文 ↑ の続き", stripped.text)
        assertTrue(stripped.modifiers.isEmpty)
        // A lone token line has no words to modify; it stays as written.
        assertEquals("↑", strip("↑").text)
        // And the trailing * or - never survives into the text when it IS a modifier.
        assertEquals("箇条書き", strip("箇条書き -").text)
        assertEquals("光る行", strip("光る行 *").text)
    }

    @Test
    fun inlineBoldAndHyphensAreLeftAlone() {
        assertEquals("**強い** ことば", strip("**強い** ことば").text)
        val stripped = strip("range 1-5 まで ←")
        assertEquals("range 1-5 まで", stripped.text)
        assertEquals(CommentFlowDirection.RIGHT_TO_LEFT, stripped.modifiers.direction)
    }

    @Test
    fun repeatOutsideTheRangeRoundsToTheTop() {
        assertEquals(5, strip("弾幕 ×9").modifiers.repeat)
        assertEquals(5, strip("弾幕 x1").modifiers.repeat)
        assertEquals(2, strip("弾幕 ×2").modifiers.repeat)
        // Not a number at all: the token is prose.
        assertEquals("弾幕 ×abc", strip("弾幕 ×abc").text)
    }

    @Test
    fun unknownColourNamesAreConsumedButChangeNothing() {
        val stripped = strip("色つき {ないいろ}")
        assertEquals("色つき", stripped.text)
        assertEquals(null, stripped.modifiers.colorRole)
    }

    @Test
    fun hexColoursSnapToTheNearestPaletteRole() {
        assertEquals(CommentColorRole.RED, strip("字 {#ff6b6b}").modifiers.colorRole)
        assertEquals(CommentColorRole.RED, strip("字 {#f00}").modifiers.colorRole)
        assertEquals(CommentColorRole.BLACK, strip("字 {#000000}").modifiers.colorRole)
        assertEquals(CommentColorRole.GRAY, strip("字 {#9a9a9a}").modifiers.colorRole)
        assertEquals(CommentColorRole.DEFAULT, strip("字 {#ffffff}").modifiers.colorRole)
    }

    @Test
    fun linkMarkersStandAmongTheTokensInAnyOrder() {
        val after = strip("本文 [R1] ↑ ++")
        assertEquals("本文 [R1]", after.text)
        assertEquals(CommentMotionMode.FIXED_TOP, after.modifiers.mode)
        val between = strip("本文 ↑ [R2] ++")
        assertEquals("本文 [R2]", between.text)
        assertEquals(CommentMotionMode.FIXED_TOP, between.modifiers.mode)
        assertEquals(SizeStep.X_LARGE, between.modifiers.size)
    }

    @Test
    fun theLaterOfTwoRivalTokensWins() {
        assertEquals(
            CommentFlowDirection.LEFT_TO_RIGHT,
            strip("本文 ← →").modifiers.direction,
        )
        assertEquals(SizeStep.SMALL, strip("本文 + -").modifiers.size)
    }

    @Test
    fun aVariationSelectorOnAnArrowMakesNoDifference() {
        // Some keyboards write ← as ←️ (U+FE0F attached); the mark means the same.
        val stripped = strip("本文 →️")
        assertEquals("本文", stripped.text)
        assertEquals(CommentFlowDirection.LEFT_TO_RIGHT, stripped.modifiers.direction)
    }

    @Test
    fun chipTokensReplaceTheirOwnKindAndKeepTheRest() {
        assertEquals("本文 →", CommentLineModifiers.applyChipToken("本文 ←", "→"))
        assertEquals("本文 ↓", CommentLineModifiers.applyChipToken("本文 ↑", "↓"))
        assertEquals("本文 {赤} +", CommentLineModifiers.applyChipToken("本文 {赤} -", "+"))
        // A direction chip leaves a size token standing, and vice versa.
        assertEquals("本文 ++ →", CommentLineModifiers.applyChipToken("本文 ++", "→"))
        assertEquals("本文 [R1] ↑", CommentLineModifiers.applyChipToken("本文 [R1]", "↑"))
        assertEquals("↑", CommentLineModifiers.applyChipToken("", "↑"))
    }

    @Test
    fun stripTextClearsEveryLineForTheReadingSurfaces() {
        val body = "一行目 ↑\n二行目はそのまま\n三行目 {青} ×3"
        assertEquals(
            "一行目\n二行目はそのまま\n三行目",
            CommentLineModifiers.stripText(body),
        )
    }

    @Test
    fun theDoubledArrowPinsForeverAndTheCircleLoops() {
        val pinned = strip("ここに置いておく ↑↑")
        assertEquals("ここに置いておく", pinned.text)
        assertEquals(CommentMotionMode.FIXED_TOP, pinned.modifiers.mode)
        assertTrue(pinned.modifiers.persistent)
        assertEquals(CommentMotionMode.FIXED_BOTTOM, strip("下に ↓↓").modifiers.mode)
        assertTrue(strip("下に ↓↓").modifiers.persistent)
        assertTrue(strip("下に vv").modifiers.persistent)
        assertTrue(strip("上に ^^").modifiers.persistent)

        val looping = strip("ずっと流れる ↺")
        assertEquals("ずっと流れる", looping.text)
        assertTrue(looping.modifiers.loop)
        assertTrue(strip("時計回り ↻").modifiers.loop)
        assertTrue(strip("ASCIIでも oo").modifiers.loop)
    }

    @Test
    fun aSingleArrowAfterADoubleOneTakesTheTimeLimitBack() {
        // Later token wins, as everywhere else: ↑ is the three-second pin again.
        val stripped = strip("考え直した ↑↑ ↑")
        assertEquals(CommentMotionMode.FIXED_TOP, stripped.modifiers.mode)
        assertFalse(stripped.modifiers.persistent)
    }

    @Test
    fun loopAndVolleyReplaceEachOtherBecauseTheyAnswerTheSameQuestion() {
        val loopWins = strip("弾幕 ×3 ↺")
        assertTrue(loopWins.modifiers.loop)
        assertEquals(1, loopWins.modifiers.repeat)

        val volleyWins = strip("弾幕 ↺ ×3")
        assertEquals(3, volleyWins.modifiers.repeat)
        assertFalse(volleyWins.modifiers.loop)
    }

    @Test
    fun theEndlessTokensStandBesideTheOthers() {
        val stripped = strip("大事な知らせ ↑↑ ++ {赤}")
        assertEquals("大事な知らせ", stripped.text)
        assertTrue(stripped.modifiers.persistent)
        assertEquals(SizeStep.X_LARGE, stripped.modifiers.size)
        assertEquals(CommentColorRole.RED, stripped.modifiers.colorRole)

        // And the chips replace their own kind: a pin replaces a pin, a loop a loop.
        assertEquals("本文 ↑↑", CommentLineModifiers.applyChipToken("本文 ↑", "↑↑"))
        assertEquals("本文 ↓", CommentLineModifiers.applyChipToken("本文 ↑↑", "↓"))
        assertEquals("本文 ++ ↺", CommentLineModifiers.applyChipToken("本文 ++", "↺"))
        assertEquals("本文 ↺", CommentLineModifiers.applyChipToken("本文 ↻", "↺"))
    }

    @Test
    fun aLineWithoutModifiersIsUntouched() {
        val stripped = strip("ただの文章です。")
        assertEquals("ただの文章です。", stripped.text)
        assertTrue(stripped.modifiers.isEmpty)
        assertFalse(strip("終わりに矢印←").modifiers.direction != null)
    }
}

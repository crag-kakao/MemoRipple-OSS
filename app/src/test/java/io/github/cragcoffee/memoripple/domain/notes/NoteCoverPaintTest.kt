package io.github.cragcoffee.memoripple.domain.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cover is painted a preset or a colour of the writer's own. The writer's colour is kept in the
 * same column the preset name is, as `custom:#RRGGBB`, so an older build that does not know the
 * form falls back to the default cover rather than to a crash.
 */
class NoteCoverPaintTest {
    @Test
    fun aPresetAndACustomColourRoundTripThroughStorage() {
        assertEquals(
            NoteCoverPaint.Preset(NoteCoverColor.INDIGO),
            NoteCoverPaint.fromStorageId("indigo"),
        )
        val own = NoteCoverPaint.Custom(0xFF3A7BD5.toInt())
        assertEquals("custom:#3A7BD5", own.storageId)
        assertEquals(own, NoteCoverPaint.fromStorageId(own.storageId))
        assertEquals(own, NoteCoverPaint.fromStorageId("custom:#3a7bd5"))
        // The alpha byte is not the writer's to set; a colour read back is always opaque.
        assertEquals(NoteCoverPaint.Custom(0xFF3A7BD5.toInt()), NoteCoverPaint.Custom(0x003A7BD5))
    }

    @Test
    fun anUnknownOrBrokenValueFallsBackToTheDefaultCover() {
        listOf(null, "", "custom:", "custom:#12", "custom:#GGGGGG", "知らない色").forEach {
            assertEquals(
                "for $it",
                NoteCoverPaint.Preset(NoteCoverColor.Default),
                NoteCoverPaint.fromStorageId(it),
            )
        }
        // The older reader sees a preset name and shrugs the same way.
        assertEquals(NoteCoverColor.Default, NoteCoverColor.fromStorageId("custom:#3A7BD5"))
    }

    @Test
    fun aColourIsMadeFromHueSaturationAndLightnessAndReadBack() {
        assertEquals(0xFFFF0000.toInt(), NoteCoverPaint.fromHsl(0f, 1f, 0.5f))
        assertEquals(0xFF00FF00.toInt(), NoteCoverPaint.fromHsl(120f, 1f, 0.5f))
        assertEquals(0xFF808080.toInt(), NoteCoverPaint.fromHsl(200f, 0f, 0.5f))
        val (h, s, l) = NoteCoverPaint.toHsl(NoteCoverPaint.fromHsl(210f, 0.6f, 0.4f))
        assertEquals(210f, h, 1f)
        assertEquals(0.6f, s, 0.02f)
        assertEquals(0.4f, l, 0.02f)
    }

    @Test
    fun theGradientOfACustomColourIsTheColourAndADarkerShadeOfItself() {
        val paint = NoteCoverPaint.Custom(NoteCoverPaint.fromHsl(30f, 0.7f, 0.6f))
        val (start, end) = paint.gradientEnds()
        assertEquals(paint.argb, start)
        val (h, s, l) = NoteCoverPaint.toHsl(end)
        assertEquals(30f, h, 2f)
        assertEquals(0.7f, s, 0.05f)
        assertTrue("end $l is darker than 0.6", l < 0.6f)
        assertTrue("but still the colour, not black", l > 0.3f)
        // A light colour stays light: the shade is a step, not a plunge.
        val pale = NoteCoverPaint.Custom(NoteCoverPaint.fromHsl(340f, 0.5f, 0.85f))
        assertTrue(NoteCoverPaint.toHsl(pale.gradientEnds().second)[2] > 0.55f)
    }

    @Test
    fun myColoursKeepTheNewestFirstWithoutRepeatsAndAtMostSix() {
        var mine = emptyList<Int>()
        (1..7).forEach { mine = NoteCoverPalette.remembered(mine, it) }
        assertEquals(listOf(7, 6, 5, 4, 3, 2), mine)
        // Saving a colour again moves it to the front instead of doubling it.
        assertEquals(listOf(3, 7, 6, 5, 4, 2), NoteCoverPalette.remembered(mine, 3))
        assertEquals(listOf(7, 6, 5, 4, 2), NoteCoverPalette.forgotten(mine, 3))
        assertEquals(6, NoteCoverPalette.MAXIMUM)
    }
}

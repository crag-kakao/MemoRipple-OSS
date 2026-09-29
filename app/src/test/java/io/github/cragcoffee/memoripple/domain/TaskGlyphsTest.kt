package io.github.cragcoffee.memoripple.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskGlyphsTest {
    @Test
    fun drawsEachNotationAsItsBox() {
        val raw = "- [ ] 牛乳\n本文\n  - [x] 卵"
        assertEquals("☐ 牛乳\n本文\n  ☑ 卵", TaskGlyphs.drawn(raw))
    }

    @Test
    fun leavesProseAndMidLineNotationAlone() {
        assertEquals("これは - [ ] ではない", TaskGlyphs.drawn("これは - [ ] ではない"))
        assertEquals("", TaskGlyphs.drawn(""))
    }

    @Test
    fun mappingsAreMonotonicMutualAndTotal() {
        val raw = "- [ ] 牛乳\n- [x] 卵"
        val reps = TaskGlyphs.replacements(raw)
        val drawn = TaskGlyphs.drawn(raw, reps)

        var previous = 0
        for (offset in 0..raw.length) {
            val mapped = TaskGlyphs.rawToDrawn(offset, reps)
            assertTrue("monotonic at $offset", mapped >= previous)
            assertTrue("in drawn range at $offset", mapped in 0..drawn.length)
            previous = mapped
        }
        previous = 0
        for (offset in 0..drawn.length) {
            val mapped = TaskGlyphs.drawnToRaw(offset, reps)
            assertTrue("monotonic back at $offset", mapped >= previous)
            assertTrue("in raw range at $offset", mapped in 0..raw.length)
            previous = mapped
        }
        // Text after the prefix round-trips exactly: typing happens where typing looks.
        val bodyOffset = raw.indexOf("牛")
        assertEquals(bodyOffset, TaskGlyphs.drawnToRaw(TaskGlyphs.rawToDrawn(bodyOffset, reps), reps))
        assertEquals(raw.length, TaskGlyphs.drawnToRaw(TaskGlyphs.rawToDrawn(raw.length, reps), reps))
    }
}

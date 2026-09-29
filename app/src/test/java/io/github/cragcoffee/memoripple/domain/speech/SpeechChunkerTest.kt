package io.github.cragcoffee.memoripple.domain.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunkerTest {
    private val subject = SpeechChunker()

    @Test
    fun `long input is split without losing content`() {
        val input = "第一段落です。\n\n第二段落は少し長いです！\n最後の行です？"
        val chunks = subject.chunk(input, maxInputLength = 10)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= 10 })
        assertEquals(input, chunks.joinToString("") { it.text })
    }

    @Test
    fun `never splits a surrogate pair`() {
        val input = "あいう😀えおかきくけこ"
        val chunks = subject.chunk(input, maxInputLength = 4)

        assertEquals(input, chunks.joinToString("") { it.text })
        chunks.forEach { segment ->
            assertFalse(segment.text.last().isHighSurrogate())
            assertFalse(segment.text.first().isLowSurrogate())
        }
    }

    @Test
    fun `blank content creates no segments`() {
        assertTrue(subject.chunk(" \n ", maxInputLength = 4).isEmpty())
    }
}

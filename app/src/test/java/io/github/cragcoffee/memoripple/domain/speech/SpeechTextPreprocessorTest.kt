package io.github.cragcoffee.memoripple.domain.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextPreprocessorTest {
    private val subject = SpeechTextPreprocessor()

    @Test
    fun `removes only recognized line-start Work Comment markers`() {
        val input = """
            # 主人公
            - 性格
              - 明るい
            > 補足
            ! 重要
            ? 疑問
        """.trimIndent()

        assertEquals(
            "主人公\n性格\n明るい\n補足\n重要\n疑問",
            subject.preprocessBody(input),
        )
    }

    @Test
    fun `keeps normal text and inline symbols unchanged`() {
        val input = "abc@example.com\nこれは!大切\n本当に?"

        assertEquals(input, subject.preprocessBody(input))
    }

    @Test
    fun `skips a marker whose recognized text is empty`() {
        assertEquals("本文", subject.preprocessBody("# \n本文\n  - "))
    }

    @Test
    fun `reads ruby readings only when asked`() {
        val body = "｜巌《いわお》のごとき｜智慧《ちえ》"

        assertEquals("巌のごとき智慧", subject.preprocessBody(body))
        assertEquals(
            "いわおのごときちえ",
            subject.preprocessBody(body, readRubyReadings = true),
        )
    }
}

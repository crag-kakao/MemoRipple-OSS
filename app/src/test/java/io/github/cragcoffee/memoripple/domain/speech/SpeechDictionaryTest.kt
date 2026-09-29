package io.github.cragcoffee.memoripple.domain.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechDictionaryTest {
    private fun entry(surface: String, reading: String, enabled: Boolean = true) =
        SpeechDictionaryEntry(id = surface, surface = surface, reading = reading, enabled = enabled)

    @Test
    fun aTaughtReadingReplacesEveryAppearance() {
        val entries = listOf(entry("兎にも角にも", "とにもかくにも"))
        assertEquals(
            "とにもかくにも朝。とにもかくにも夜。",
            SpeechDictionary.apply("兎にも角にも朝。兎にも角にも夜。", entries),
        )
    }

    @Test
    fun theLongerSurfaceSpeaksFirstSoItIsNotCutApartByAShorterOne() {
        val entries = listOf(
            entry("兎", "うさぎ"),
            entry("兎にも角にも", "とにもかくにも"),
        )
        assertEquals(
            "とにもかくにも、うさぎが居た。",
            SpeechDictionary.apply("兎にも角にも、兎が居た。", entries),
        )
    }

    @Test
    fun aRestingEntryChangesNothing() {
        val entries = listOf(entry("真面目", "まじめ", enabled = false))
        assertEquals("真面目", SpeechDictionary.apply("真面目", entries))
    }

    @Test
    fun anEmptySurfaceIsIgnoredInsteadOfLooping() {
        val entries = listOf(entry("", "なにか"))
        assertEquals("そのまま", SpeechDictionary.apply("そのまま", entries))
    }
}

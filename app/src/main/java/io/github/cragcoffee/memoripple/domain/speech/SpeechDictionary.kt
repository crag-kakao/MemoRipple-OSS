package io.github.cragcoffee.memoripple.domain.speech

import kotlinx.serialization.Serializable

/**
 * One reading the writer taught the reader: whenever [surface] appears in spoken text, say
 * [reading] instead. Plain text on both sides — the entry is a correction, not a program.
 */
@Serializable
data class SpeechDictionaryEntry(
    val id: String,
    val surface: String,
    val reading: String,
    val enabled: Boolean = true,
)

object SpeechDictionary {
    /** Entries a device may hold; far above what a person maintains by hand. */
    const val MAXIMUM = 500

    /**
     * Applies the dictionary to text about to be spoken. Longer surfaces first, so 「兎にも角にも」
     * wins over an entry for 「兎」 instead of being cut apart by it.
     */
    fun apply(text: String, entries: List<SpeechDictionaryEntry>): String {
        var result = text
        entries.asSequence()
            .filter { it.enabled && it.surface.isNotEmpty() }
            .sortedByDescending { it.surface.length }
            .forEach { entry -> result = result.replace(entry.surface, entry.reading) }
        return result
    }
}

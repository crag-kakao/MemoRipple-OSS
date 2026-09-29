package io.github.cragcoffee.memoripple.domain

/**
 * Splits prose into the pieces that fly across the screen.
 *
 * A comment has to be read while it moves, so it has to be short. Prose is not written that way, so
 * a paragraph is cut where it already pauses: at a full stop first, then at a comma, and only as a
 * last resort in the middle of a run of words that has none.
 */
object CommentSentences {

    /** Roughly what fits across a phone at the standard comment size. */
    const val MAX_CHARACTERS = 42

    private val sentenceEnd = setOf('。', '！', '？', '!', '?', '．')
    private val softBreak = setOf('、', '，', ',', '・', '；', ';', '：', ':')

    fun split(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        return sentences(trimmed).flatMap(::shorten).filter(String::isNotBlank)
    }

    /** Whole sentences, each keeping the mark it ends with. */
    private fun sentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        text.forEach { character ->
            current.append(character)
            if (character in sentenceEnd) {
                result += current.toString()
                current.clear()
            }
        }
        if (current.isNotBlank()) result += current.toString()
        return result.map(String::trim).filter(String::isNotEmpty)
    }

    /** A sentence still too long to read is cut at its own pauses, keeping them at the end. */
    private fun shorten(sentence: String): List<String> {
        if (sentence.length <= MAX_CHARACTERS) return listOf(sentence)
        val result = mutableListOf<String>()
        var rest = sentence
        while (rest.length > MAX_CHARACTERS) {
            val window = rest.take(MAX_CHARACTERS)
            val cut = window.indexOfLast { it in softBreak }
                .takeIf { it > MAX_CHARACTERS / 3 }
                ?: (MAX_CHARACTERS - 1)
            result += rest.take(cut + 1).trim()
            rest = rest.drop(cut + 1).trimStart()
        }
        if (rest.isNotBlank()) result += rest.trim()
        return result
    }
}

package io.github.cragcoffee.memoripple.domain.speech

data class SpeechSegment(val index: Int, val text: String)

class SpeechChunker {
    fun chunk(text: String, maxInputLength: Int): List<SpeechSegment> {
        require(maxInputLength >= MIN_MAX_INPUT_LENGTH)
        if (text.isBlank()) return emptyList()
        val result = mutableListOf<SpeechSegment>()
        var start = 0
        while (start < text.length) {
            var end = (start + maxInputLength).coerceAtMost(text.length)
            if (end < text.length && end > start &&
                Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])
            ) {
                end -= 1
            }
            if (end < text.length) end = naturalBoundary(text, start, end)
            if (end <= start) end = safeFallbackEnd(text, start, maxInputLength)
            result += SpeechSegment(result.size, text.substring(start, end))
            start = end
        }
        return result
    }

    private fun naturalBoundary(text: String, start: Int, limit: Int): Int {
        val paragraph = text.lastIndexOf(
            "\n\n",
            startIndex = (limit - 2).coerceAtLeast(start),
        ).takeIf { it >= start }?.plus(2)
        if (paragraph != null) return paragraph

        for (index in limit - 1 downTo start) {
            if (text[index] in SENTENCE_ENDINGS) return index + 1
        }
        for (index in limit - 1 downTo start) {
            if (text[index] == '\n') return index + 1
        }
        for (index in limit - 1 downTo start) {
            if (text[index].isWhitespace()) return index + 1
        }
        return limit
    }

    private fun safeFallbackEnd(text: String, start: Int, maxInputLength: Int): Int {
        var end = (start + maxInputLength).coerceAtMost(text.length)
        if (end < text.length && end > start &&
            Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])
        ) {
            end -= 1
        }
        return if (end > start) end else Character.offsetByCodePoints(text, start, 1)
    }

    private companion object {
        const val MIN_MAX_INPUT_LENGTH = 2
        val SENTENCE_ENDINGS = setOf('。', '！', '？', '!', '?')
    }
}

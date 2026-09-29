package io.github.cragcoffee.memoripple.domain.ai.conversation

/**
 * A conversation's title is its first user message, shortened by rule — never a model's words,
 * never a second inference: trimmed, on one line, whitespace collapsed, at most [MAX_CHARS]
 * characters with 「…」 when cut; an empty message gets [DEFAULT].
 */
object ConversationTitle {
    const val MAX_CHARS = 36
    const val DEFAULT = "新しいチャット"

    fun from(firstUserText: String): String {
        val line = firstUserText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return DEFAULT
        val collapsed = line.split(Regex("[\\s\\u3000]+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (collapsed.isEmpty()) return DEFAULT
        return if (collapsed.length > MAX_CHARS) collapsed.take(MAX_CHARS) + "…" else collapsed
    }
}

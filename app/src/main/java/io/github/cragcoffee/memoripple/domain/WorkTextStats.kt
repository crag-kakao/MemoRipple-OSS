package io.github.cragcoffee.memoripple.domain

/** What a writer wants to know about a body at a glance. */
data class TextStats(
    val characters: Int,
    val charactersWithoutWhitespace: Int,
    val lines: Int,
    val doneTasks: Int,
    val totalTasks: Int,
) {
    val hasTasks: Boolean get() = totalTasks > 0
}

/**
 * Counts what the reader reads. Markers are the writer's tools rather than the words themselves, so
 * both the inline decoration and the line-start syntax are removed before counting.
 */
object WorkTextStats {

    fun of(body: String): TextStats {
        var done = 0
        var total = 0
        var characters = 0
        var withoutWhitespace = 0
        var filledLines = 0

        body.lineSequence().forEach { rawLine ->
            val syntax = WorkCommentSyntax.recognize(rawLine)
            when (syntax?.type) {
                WorkLineType.TASK -> total++
                WorkLineType.TASK_DONE -> { total++; done++ }
                else -> Unit
            }
            val words = BodyText.readable(syntax?.text ?: rawLine.trimStart())
            characters += words.length
            withoutWhitespace += words.count { !it.isWhitespace() }
            if (words.isNotBlank()) filledLines++
        }

        return TextStats(
            characters = characters,
            charactersWithoutWhitespace = withoutWhitespace,
            lines = filledLines,
            doneTasks = done,
            totalTasks = total,
        )
    }
}

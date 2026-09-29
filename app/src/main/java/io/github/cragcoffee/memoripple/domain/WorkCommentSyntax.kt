package io.github.cragcoffee.memoripple.domain

data class RecognizedWorkSyntax(
    val type: WorkLineType,
    val text: String,
    val depth: Int,
)

/** Shared recognition rule for outline playback and speech preprocessing. */
object WorkCommentSyntax {

    /**
     * What the toolbar writes for a heading. A single mark like the other line starts, and unlike a
     * hash it means one thing here: `#` is how the rest of the world writes a tag as well.
     */
    const val HEADING_MARKER = "■ "

    /**
     * Task markers follow the CommonMark task list shape, so a checked line stays a checked line in
     * an export and in any other editor that reads Markdown. They are recognised before the plain
     * item marker they start with.
     */
    const val TASK_MARKER = "- [ ] "
    const val TASK_DONE_MARKER = "- [x] "

    /**
     * Markdown's own heading, kept readable so a file written elsewhere folds and reads here the way
     * it does there. The number of hashes is the depth, the same way indenting is.
     */
    val markdownHeading = Regex("""^(#{1,6}) """)

    fun recognize(rawLine: String): RecognizedWorkSyntax? {
        if (rawLine.isBlank()) return null
        val leadingSpaces = rawLine.takeWhile { it == ' ' }.length
        val content = rawLine.trimStart()
        val indentDepth = leadingSpaces / INDENT_WIDTH

        markdownHeading.find(content)?.let { match ->
            return RecognizedWorkSyntax(
                type = WorkLineType.HEADING,
                text = content.drop(match.value.length),
                depth = indentDepth + match.groupValues[1].length - 1,
            )
        }

        // Every set's symbols are always recognised — the chosen set only decides what the
        // toolbar inserts — so switching sets never breaks a memo already written.
        val match = OutlineSymbols.match(content) ?: return null
        return RecognizedWorkSyntax(
            type = match.role.lineType(),
            text = content.drop(match.consumed),
            depth = indentDepth,
        )
    }

    private fun OutlineSymbolRole.lineType(): WorkLineType = when (this) {
        OutlineSymbolRole.HEADING -> WorkLineType.HEADING
        OutlineSymbolRole.ITEM -> WorkLineType.ITEM
        OutlineSymbolRole.TASK -> WorkLineType.TASK
        OutlineSymbolRole.TASK_DONE -> WorkLineType.TASK_DONE
        OutlineSymbolRole.NOTE -> WorkLineType.NOTE
        OutlineSymbolRole.IMPORTANT -> WorkLineType.IMPORTANT
        OutlineSymbolRole.QUESTION -> WorkLineType.QUESTION
    }

    private const val INDENT_WIDTH = 2
}

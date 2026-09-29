package io.github.cragcoffee.memoripple.domain

import io.github.cragcoffee.memoripple.domain.comments.CommentLineModifiers

enum class WorkLineType { HEADING, ITEM, TASK, TASK_DONE, NOTE, IMPORTANT, QUESTION, PLAIN }

data class WorkLine(
    val text: String,
    val type: WorkLineType,
    val depth: Int,
    val sourceLine: Int,
    /** 行末修飾子: how this line asked to fly; sentences cut from one line share them. */
    val modifiers: CommentLineModifiers.LineModifiers = CommentLineModifiers.LineModifiers.None,
)

/** How much of a body becomes comments. */
enum class WorkCommentScope(val storageId: String) {
    /** Only the lines the writer marked. The skeleton of the memo. */
    OUTLINE("outline"),

    /** Everything written, prose included. */
    BODY("body"),
    ;

    companion object {
        fun fromStorageId(value: String?): WorkCommentScope =
            entries.firstOrNull { it.storageId == value } ?: OUTLINE
    }
}

/** Reads structure from a memo without changing or duplicating its source text. */
class WorkCommentParser {
    fun parse(body: String): List<WorkLine> = body.lineSequence()
        .mapIndexedNotNull { index, rawLine -> parseLine(index, rawLine) }
        .toList()

    fun parseOutline(body: String): List<WorkLine> = parse(WorkCommentScope.OUTLINE, body)

    /**
     * The lines that fly across the screen.
     *
     * A marked line is left whole: marking it is the writer saying this is one thing. Prose has no
     * such mark, so it is cut into sentences, which is how it would be read aloud anyway.
     */
    fun parse(scope: WorkCommentScope, body: String): List<WorkLine> = parse(body).flatMap { line ->
        when {
            line.type != WorkLineType.PLAIN -> listOf(line)
            scope == WorkCommentScope.OUTLINE -> emptyList()
            else -> CommentSentences.split(line.text).map { line.copy(text = it) }
        }
    }

    private fun parseLine(index: Int, rawLine: String): WorkLine? {
        if (rawLine.isBlank()) return null
        val syntax = WorkCommentSyntax.recognize(rawLine)
        val type = syntax?.type ?: WorkLineType.PLAIN
        // 行末修飾子 come off first, carrying the flying instructions; then the inline
        // decoration — which is for reading the body, not for the comment that flies across
        // the screen: the renderer receives the words only.
        val stripped = CommentLineModifiers.strip(syntax?.text ?: rawLine.trimStart())
        val text = BodyText.readable(stripped.text)
        val depth = syntax?.depth ?: 0
        return text.takeIf(String::isNotBlank)?.let {
            WorkLine(it, type, depth, index, stripped.modifiers)
        }
    }
}

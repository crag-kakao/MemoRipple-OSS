package io.github.cragcoffee.memoripple.domain

/** One line of a body as it is read, keeping its place in the source so it can be written back. */
data class ReadingLine(
    val sourceLine: Int,
    val type: WorkLineType,
    val text: String,
    val depth: Int,
)

/**
 * The body seen as a document that can be folded.
 *
 * A heading owns everything after it until the next heading at the same or a shallower level, which
 * is what folding it hides. Blank lines are spacing rather than content and are left out; the
 * reading view puts the space back itself.
 */
object BodyReading {

    fun lines(body: String): List<ReadingLine> = body.lineSequence()
        .mapIndexedNotNull { index, rawLine ->
            if (rawLine.isBlank()) return@mapIndexedNotNull null
            val syntax = WorkCommentSyntax.recognize(rawLine)
            ReadingLine(
                sourceLine = index,
                type = syntax?.type ?: WorkLineType.PLAIN,
                // 行末修飾子 are flight instructions, not reading matter — the page hides
                // them the way it hides [R1]; the editor still shows the plain characters.
                text = io.github.cragcoffee.memoripple.domain.comments.CommentLineModifiers
                    .strip(syntax?.text ?: rawLine.trimStart()).text,
                depth = syntax?.depth ?: 0,
            )
        }
        .toList()

    /**
     * Where source line [line] ends in [body] — the caret's place when a written line is
     * double-tapped into the editor: where its writing continues. Lines are counted as [lines]
     * counts them (`lineSequence`); a line past the end lands at the end.
     */
    fun lineEndOffset(body: String, line: Int): Int {
        var start = 0
        var seen = 0
        while (seen < line) {
            val next = body.indexOf('\n', start)
            if (next < 0) return body.length
            start = next + 1
            seen += 1
        }
        val end = body.indexOf('\n', start)
        return if (end < 0) body.length else end
    }

    /**
     * True when the writer marked any line: a heading, an item, an aside, a task, a note, a query.
     *
     * This is how the wall reads a body's shape — whether it carries marks — for the display split
     * between plain and outlined memos. It is not what makes a document an outline: `memos.kind`
     * (MemoKind) records that, set once when the document is made in the outliner.
     */
    fun hasStructure(body: String): Boolean = body.lineSequence().any { line ->
        WorkCommentSyntax.recognize(line) != null
    }

    /** Positions in [lines] that the heading at [position] owns, or null when it owns nothing. */
    fun ownedLines(lines: List<ReadingLine>, position: Int): IntRange? {
        val heading = lines.getOrNull(position)?.takeIf { it.type == WorkLineType.HEADING }
            ?: return null
        var last = position
        for (index in (position + 1) until lines.size) {
            val line = lines[index]
            if (line.type == WorkLineType.HEADING && line.depth <= heading.depth) break
            last = index
        }
        return if (last > position) (position + 1)..last else null
    }

    /** True when folding this heading would actually hide something. */
    fun isFoldable(lines: List<ReadingLine>, position: Int): Boolean =
        ownedLines(lines, position) != null

    /** How many lines the heading at [position] hides when folded. */
    fun hiddenCount(lines: List<ReadingLine>, position: Int): Int =
        ownedLines(lines, position)?.count() ?: 0

    /**
     * The lines still on screen once the headings whose source line is in [collapsed] are folded.
     * A folded heading takes its nested headings with it, folded or not.
     */
    fun visible(lines: List<ReadingLine>, collapsed: Set<Int>): List<ReadingLine> {
        val result = mutableListOf<ReadingLine>()
        var position = 0
        while (position < lines.size) {
            val line = lines[position]
            result += line
            val owned = if (line.type == WorkLineType.HEADING && line.sourceLine in collapsed) {
                ownedLines(lines, position)
            } else {
                null
            }
            position = owned?.let { it.last + 1 } ?: (position + 1)
        }
        return result
    }

    /** Source lines of every heading that hides something, for folding or unfolding them at once. */
    fun foldableHeadings(lines: List<ReadingLine>): Set<Int> = lines.indices
        .filter { isFoldable(lines, it) }
        .mapTo(linkedSetOf()) { lines[it].sourceLine }
}

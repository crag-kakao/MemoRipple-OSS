package io.github.cragcoffee.memoripple.domain.notes

import io.github.cragcoffee.memoripple.domain.WorkCommentSyntax
import io.github.cragcoffee.memoripple.domain.WorkLineType

/**
 * Reading a memo's outline as the episodes of a note.
 *
 * A memo written as `■ 発端 / ■ 中盤 / ■ 結末` is already the shape of a note; making one should not
 * mean typing those words a second time. Every heading becomes an episode and the lines under it
 * become the beginning of its body.
 *
 * The bridge runs one way. What is made here is a set of ordinary memos, and the outline they came
 * from is left exactly as it was, so editing either afterwards cannot reach into the other. Two-way
 * agreement would have to answer what happens to three thousand written words when the heading over
 * them is renamed, and there is no answer to that which is not a rule someone has to remember.
 *
 * Depth is not read. A nested heading is an episode like any other, because the indent in an
 * outline says how closely lines belong together and has never meant more than that; giving it a
 * second meaning would make writing one a decision about chapters.
 */
object OutlineEpisodes {

    /** An episode about to exist: what the heading said, and what was written under it. */
    data class Draft(val title: String, val body: String)

    /** True when [body] holds a heading, which is the only thing this can make an episode out of. */
    fun canMake(body: String): Boolean = body.lineSequence().any(::isHeading)

    /**
     * The episodes [body] describes, in the order its headings appear.
     *
     * Anything written before the first heading belongs to no episode and stays behind. It is still
     * in the outline, which is not going anywhere.
     */
    fun of(body: String): List<Draft> {
        val drafts = mutableListOf<Draft>()
        var title: String? = null
        var lines = mutableListOf<String>()

        fun close() {
            title?.let { drafts += Draft(it, dedent(lines).joinToString("\n").trim('\n')) }
        }

        body.lineSequence().forEach { line ->
            val heading = WorkCommentSyntax.recognize(line)?.takeIf { it.type == WorkLineType.HEADING }
            if (heading == null) {
                if (title != null) lines += line
            } else {
                close()
                title = heading.text.trim()
                lines = mutableListOf()
            }
        }
        close()
        return drafts
    }

    private fun isHeading(line: String): Boolean =
        WorkCommentSyntax.recognize(line)?.type == WorkLineType.HEADING

    /**
     * Removes the indentation the whole block shares.
     *
     * Lines under a nested heading were written inside something. Standing on their own they should
     * start at the left, the way they would have been written if they had started there.
     */
    private fun dedent(lines: List<String>): List<String> {
        val common = lines
            .filter(String::isNotBlank)
            .minOfOrNull { line -> line.length - line.trimStart(' ').length }
            ?: return lines
        return lines.map { if (it.isBlank()) it else it.drop(common) }
    }
}

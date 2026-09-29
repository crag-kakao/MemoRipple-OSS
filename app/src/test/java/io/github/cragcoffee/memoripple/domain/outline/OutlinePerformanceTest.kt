package io.github.cragcoffee.memoripple.domain.outline

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureNanoTime

/**
 * What one edit costs on a large outline, measured rather than guessed: every keystroke
 * today re-serializes the whole document, so this records parse, serialize, a one-line text
 * change, indent, move, the visible set and a zoom at 100 / 500 / 1000 lines. The bounds are
 * deliberately loose sanity limits for a developer JVM — the numbers printed below are the
 * evidence; a regression of an order of magnitude is what would trip them.
 */
class OutlinePerformanceTest {

    @Test
    fun editingCostStaysFlatEnoughUpToAThousandLines() {
        listOf(100, 500, 1000).forEach { lines ->
            val text = syntheticOutline(lines)
            val document = OutlineText.parse(text)
            val middle = document.entries.filterIsInstance<OutlineNode>()[lines / 2]
            val indentable = document.entries.filterIsInstance<OutlineNode>()
                .first { OutlineEditing.canIndent(document, it.id) && it.depth == 0 }
            val movable = document.entries.filterIsInstance<OutlineNode>()
                .first { OutlineEditing.canMoveDown(document, it.id) && it.depth == 0 }
            val zoomTarget = document.entries.filterIsInstance<OutlineNode>()
                .first { it.depth == 0 && OutlineEditing.hasChildren(document, it.id) }

            val costs = linkedMapOf(
                "parse" to timeMicros { OutlineText.parse(text) },
                "serialize" to timeMicros { OutlineText.serialize(document) },
                "updateText" to timeMicros { OutlineEditing.updateText(document, middle.id, middle.text + "x") },
                "indent" to timeMicros { OutlineEditing.indent(document, indentable.id) },
                "moveDown" to timeMicros { OutlineEditing.moveDown(document, movable.id) },
                "deleteBackward" to timeMicros { OutlineEditing.deleteBackward(document, middle.id) },
                "visible" to timeMicros { OutlineEditing.visible(document, emptySet()) },
                "visible+zoom" to timeMicros { OutlineEditing.visible(document, emptySet(), zoomTarget.id) },
                "foldKeys" to timeMicros { OutlineFoldKeys.keysFor(document, setOf(zoomTarget.id)) },
            )
            println("outline $lines lines: " + costs.entries.joinToString { "${it.key}=${it.value}µs" })
            costs.forEach { (name, micros) ->
                assertTrue("$name at $lines lines took ${micros}µs", micros < 250_000)
            }
        }
    }

    /** Median of a few runs after a warm-up, in microseconds. */
    private fun timeMicros(block: () -> Any?): Long {
        repeat(3) { block() }
        val runs = List(7) { measureNanoTime { block() } }.sorted()
        return runs[runs.size / 2] / 1_000
    }

    /** A believable outline: headings, nested items with markers and modifiers, prose, blanks. */
    private fun syntheticOutline(lines: Int): String = buildString {
        var written = 0
        var block = 0
        while (written < lines) {
            when (written % 10) {
                0 -> append("■ 見出し$block\n").also { block += 1 }
                1, 2 -> append("- 項目${written} [R1] ←←\n")
                3, 4 -> append("  - 子項目${written} {赤}\n")
                5 -> append("    - 孫項目${written} ↺\n")
                6 -> append("本文の一行 [[別のメモ]] ${written}\n")
                7 -> append("\n")
                8 -> append("- [ ] やること${written}\n")
                else -> append("  > 補足${written}\n")
            }
            written += 1
        }
    }.trimEnd('\n')
}

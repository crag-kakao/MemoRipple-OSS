package io.github.cragcoffee.memoripple.ui.memos

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Where the caret sits in a body field, as the field last laid it out: the caret's edges in the
 * field's own coordinates, the height of all its text and the height of the field.
 */
data class CaretReport(
    val caretBottomPx: Float,
    val textHeightPx: Int,
    val fieldHeightPx: Int,
    val caretTopPx: Float = caretBottomPx,
)

/**
 * A memo written block by block (docs/MEMO_CONTENT_BLOCKS.md) is one column that scrolls, text and
 * photos in their order, each text as tall as its words. A text field brings its caret into view
 * only when it gains focus, never while it is typed in, so the column keeps the line being written
 * on screen itself, by this rule.
 */
object NotePageScroll {
    /**
     * The scroll that keeps the caret of the text at [fieldTopPx] (in the column) on screen with
     * [marginPx] around it, or null when it already is. Moves as little as possible.
     */
    fun follow(fieldTopPx: Int, report: CaretReport, scrollPx: Int, viewportPx: Int, marginPx: Int): Int? {
        if (report.fieldHeightPx <= 0 || viewportPx <= 0) return null
        val caretTop = fieldTopPx + floor(report.caretTopPx).toInt()
        val caretBottom = fieldTopPx + ceil(report.caretBottomPx).toInt()
        return when {
            caretBottom + marginPx > scrollPx + viewportPx -> caretBottom + marginPx - viewportPx
            caretTop - marginPx < scrollPx -> (caretTop - marginPx).coerceAtLeast(0)
            else -> null
        }
    }
}

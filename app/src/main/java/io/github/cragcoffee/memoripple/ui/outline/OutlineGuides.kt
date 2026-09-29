package io.github.cragcoffee.memoripple.ui.outline

/**
 * The rail beside an outline, the way WorkFlowy draws it: a hairline runs beside every row
 * of a parent's subtree — from the bottom of the parent's own row to the bottom of its last
 * descendant's — so the eye can follow which line a row belongs to, and the line stands
 * clear of the bullets at both ends. Pure arithmetic on a row's indent.
 */
internal object OutlineGuides {
    /** The indent levels whose guide crosses this row: one for every ancestor level. */
    fun levels(indentSteps: Int): List<Int> = (0 until indentSteps).toList()
}

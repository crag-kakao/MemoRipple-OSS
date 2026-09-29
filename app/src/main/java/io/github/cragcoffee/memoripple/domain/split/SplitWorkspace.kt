package io.github.cragcoffee.memoripple.domain.split

/**
 * 分割表示 — one MemoRipple screen holding two things at once: the memo being written above,
 * and something to look at below. The reference below never edits; 「こちらを編集」moves it up
 * instead. These are the pure rules of that arrangement — ratios, orientation, and what may be
 * referenced — kept away from Compose so they can be tested as arithmetic.
 */
enum class SplitReferenceKind { MEMO, NOTE, OUTLINE }

/** What the lower pane is looking at: a memo, a note, or a memo's outline. */
data class SplitReference(val kind: SplitReferenceKind, val id: Long)

object SplitWorkspacePolicy {

    /** The writing pane starts with a little more than half the room. */
    const val DEFAULT_RATIO = 0.60f

    /** How much of the screen the writing pane may take — never all, never a sliver. */
    const val MIN_RATIO = 0.35f
    const val MAX_RATIO = 0.75f

    fun clampRatio(value: Float): Float = value.coerceIn(MIN_RATIO, MAX_RATIO)

    /**
     * Portrait stacks the panes; landscape (or anything wider than tall) sets them side by
     * side, because a phone on its side has no height to spare. Window size decides — never
     * a device name.
     */
    /**
     * Side-by-side when the window is wider than tall — and also whenever it is at least
     * [SIDE_BY_SIDE_MIN_WIDTH_DP] wide, so an unfolded foldable or a tablet held upright
     * still puts the reference beside the writing instead of squeezing it underneath.
     */
    fun isHorizontal(widthDp: Int, heightDp: Int): Boolean =
        widthDp > heightDp || widthDp >= SIDE_BY_SIDE_MIN_WIDTH_DP

    /** The medium width breakpoint: past it, two panes earn a column each. */
    const val SIDE_BY_SIDE_MIN_WIDTH_DP: Int = 600

    /** Where the divider lands after a drag of [deltaPx] along the split axis. */
    fun ratioAfterDrag(current: Float, deltaPx: Float, totalPx: Float): Float {
        if (totalPx <= 0f) return clampRatio(current)
        return clampRatio(current + deltaPx / totalPx)
    }

    /**
     * Whether [reference] may sit under the memo open above. The same memo twice is two views
     * of one caret — confusing, and pointless — so a memo (or its outline) never references
     * itself.
     */
    fun canReference(primaryMemoId: Long, reference: SplitReference): Boolean = when (reference.kind) {
        SplitReferenceKind.MEMO, SplitReferenceKind.OUTLINE -> reference.id != primaryMemoId
        SplitReferenceKind.NOTE -> true
    }
}

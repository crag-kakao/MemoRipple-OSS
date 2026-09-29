package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.data.MemoEntity

/**
 * Where a memo is opened, by its kind: a memo in the editor, an outline in the outliner.
 *
 * This is the one place that knows. Every door that opens a memo by id — the wall, the
 * outliner page, a `[[title]]` link, a backlink, the archive, a note's episode, the split
 * pane's 「こちらを編集」 — asks here, so a new kind is routed by adding one branch, not by
 * finding every `if` in every screen.
 */
enum class MemoDestination { EDITOR, OUTLINER }

val MemoKind.destination: MemoDestination
    get() = when (this) {
        MemoKind.MEMO -> MemoDestination.EDITOR
        MemoKind.OUTLINE -> MemoDestination.OUTLINER
    }

/** A kind the app does not know reads as a memo, so it opens where a memo opens. */
val MemoEntity.destination: MemoDestination
    get() = memoKind.destination

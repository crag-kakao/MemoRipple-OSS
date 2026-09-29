package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.data.MemoEntity

enum class MemoLifecycleState { ACTIVE, ARCHIVED, TRASHED }

fun memoLifecycleState(archivedAt: Long?, trashedAt: Long?): MemoLifecycleState = when {
    trashedAt != null -> MemoLifecycleState.TRASHED
    archivedAt != null -> MemoLifecycleState.ARCHIVED
    else -> MemoLifecycleState.ACTIVE
}

val MemoEntity.lifecycleState: MemoLifecycleState
    get() = memoLifecycleState(archivedAt, trashedAt)

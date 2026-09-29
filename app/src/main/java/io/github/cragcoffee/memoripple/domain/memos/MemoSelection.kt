package io.github.cragcoffee.memoripple.domain.memos

/** Ephemeral selection for the ACTIVE memo list. It is never serialized or persisted. */
data class MemoSelectionState(
    val selectedIds: Set<Long> = emptySet(),
    val explicitlyRequested: Boolean = false,
) {
    val isSelectionMode: Boolean get() = explicitlyRequested || selectedIds.isNotEmpty()

    fun request(): MemoSelectionState = copy(explicitlyRequested = true)

    fun toggle(memoId: Long): MemoSelectionState {
        val next = if (memoId in selectedIds) selectedIds - memoId else selectedIds + memoId
        return MemoSelectionState(
            selectedIds = next,
            explicitlyRequested = explicitlyRequested && next.isNotEmpty(),
        )
    }

    fun selectOnly(memoId: Long): MemoSelectionState =
        MemoSelectionState(selectedIds = setOf(memoId))

    fun selectAllVisible(visibleIds: Collection<Long>): MemoSelectionState =
        MemoSelectionState(selectedIds = visibleIds.toSet())

    fun reconcile(activeIds: Set<Long>): MemoSelectionState {
        val remaining = selectedIds intersect activeIds
        return if (selectedIds.isNotEmpty() && remaining.isEmpty()) {
            MemoSelectionState()
        } else {
            copy(selectedIds = remaining)
        }
    }

    fun clear(): MemoSelectionState = MemoSelectionState()
}

package io.github.cragcoffee.memoripple.domain.split

/**
 * The split arrangement as a working session: which memo is being written, what stands under
 * it, and where the divider rests. Held in memory only — it survives rotation and the swap
 * navigation that replaces the editor screen, and it dies with the process. Nothing here
 * touches Room, the backup, or DataStore.
 */
class SplitWorkspaceSession {
    @Volatile var primaryMemoId: Long? = null
        private set

    @Volatile var reference: SplitReference? = null
        private set

    @Volatile var ratio: Float = SplitWorkspacePolicy.DEFAULT_RATIO
        private set

    fun open(primaryMemoId: Long, reference: SplitReference, ratio: Float) {
        this.primaryMemoId = primaryMemoId
        this.reference = reference
        this.ratio = SplitWorkspacePolicy.clampRatio(ratio)
    }

    fun updateRatio(value: Float) {
        ratio = SplitWorkspacePolicy.clampRatio(value)
    }

    /** The session an editor for [memoId] should resume, or null when there is none. */
    fun resumeFor(memoId: Long): SplitReference? =
        reference?.takeIf { primaryMemoId == memoId }

    fun close() {
        primaryMemoId = null
        reference = null
        ratio = SplitWorkspacePolicy.DEFAULT_RATIO
    }
}

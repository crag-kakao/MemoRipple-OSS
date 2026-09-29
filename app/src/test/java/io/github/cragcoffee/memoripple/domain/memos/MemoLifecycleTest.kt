package io.github.cragcoffee.memoripple.domain.memos

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoLifecycleTest {
    @Test fun activeHasNoLifecycleTimestamp() =
        assertEquals(MemoLifecycleState.ACTIVE, memoLifecycleState(null, null))

    @Test fun archivedHasOnlyArchiveTimestamp() =
        assertEquals(MemoLifecycleState.ARCHIVED, memoLifecycleState(1, null))

    @Test fun trashTakesPriorityWhenBothTimestampsExist() =
        assertEquals(MemoLifecycleState.TRASHED, memoLifecycleState(1, 2))
}

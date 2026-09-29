package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.data.MemoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The document kind is a stored string with two known values. The values are a wire contract
 * (Room column, backup field), so they are pinned here by their spelling, not by the enum.
 */
class MemoKindTest {

    @Test
    fun theStoredIdsAreMemoAndOutlineAndNothingElse() {
        assertEquals(listOf("memo", "outline"), MemoKind.entries.map(MemoKind::storageId))
        assertEquals(MemoKind.MEMO, MemoKind.fromStorageId("memo"))
        assertEquals(MemoKind.OUTLINE, MemoKind.fromStorageId("outline"))
        assertNull(MemoKind.fromStorageId("folder"))
        assertNull(MemoKind.fromStorageId("MEMO"))
    }

    @Test
    fun aMemoIsAMemoUnlessItSaysOtherwise() {
        val memo = MemoEntity(title = "t", body = "- 項目\n  - 子", createdAt = 1, updatedAt = 1)

        // Written like an outline, still a memo: the kind is never read off the body.
        assertEquals("memo", memo.kind)
        assertEquals(MemoKind.MEMO, memo.memoKind)
        assertFalse(memo.isOutline)
        assertTrue(memo.copy(kind = "outline").isOutline)
    }

    @Test
    fun aValueTheAppDoesNotKnowReadsAsAMemo() {
        val memo = MemoEntity(title = "t", body = "", createdAt = 1, updatedAt = 1, kind = "canvas")

        assertEquals(MemoKind.MEMO, memo.memoKind)
        assertFalse(memo.isOutline)
    }
}

package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.data.MemoEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** One rule for every door: a memo opens in the editor, an outline in the outliner. */
class MemoDestinationTest {

    @Test
    fun aMemoOpensInTheEditorAndAnOutlineInTheOutliner() {
        assertEquals(MemoDestination.EDITOR, MemoKind.MEMO.destination)
        assertEquals(MemoDestination.OUTLINER, MemoKind.OUTLINE.destination)
        assertEquals(
            MemoDestination.OUTLINER,
            MemoEntity(title = "", body = "- 一", createdAt = 1, updatedAt = 1, kind = "outline").destination,
        )
    }

    @Test
    fun theBodyNeverDecidesAndAnUnknownKindOpensAsAMemo() {
        val outlined = MemoEntity(title = "", body = "- 項目\n  - 子", createdAt = 1, updatedAt = 1)
        val unknown = outlined.copy(kind = "canvas")

        assertEquals(MemoDestination.EDITOR, outlined.destination)
        assertEquals(MemoDestination.EDITOR, unknown.destination)
    }
}

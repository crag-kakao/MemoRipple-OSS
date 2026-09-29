package io.github.cragcoffee.memoripple.domain.memos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoSelectionStateTest {
    @Test
    fun longPressStyleEntrySelectsOneAndTapTogglesWithoutDuplicates() {
        var state = MemoSelectionState().selectOnly(10)
        assertTrue(state.isSelectionMode)
        assertEquals(setOf(10L), state.selectedIds)

        state = state.toggle(20).toggle(10)

        assertEquals(setOf(20L), state.selectedIds)
        assertTrue(state.isSelectionMode)
    }

    @Test
    fun explicitEntryMayStartAtZeroAndZeroAfterToggleExits() {
        var state = MemoSelectionState().request()
        assertTrue(state.isSelectionMode)
        assertTrue(state.selectedIds.isEmpty())

        state = state.toggle(1).toggle(1)

        assertFalse(state.isSelectionMode)
    }

    @Test
    fun selectAllUsesOnlyTheFinalVisibleIdsAndHandlesFiveHundred() {
        val ids = (1L..500L).filter { it % 3L == 0L }

        val state = MemoSelectionState().selectAllVisible(ids)

        assertEquals(ids.toSet(), state.selectedIds)
        assertEquals(ids.size, state.selectedIds.size)
    }

    @Test
    fun reconciliationDropsInactiveIdsAndExitsWhenNothingRemains() {
        val state = MemoSelectionState(setOf(1, 2, 3))

        assertEquals(setOf(2L), state.reconcile(setOf(2, 9)).selectedIds)
        assertFalse(state.reconcile(setOf(9)).isSelectionMode)
    }

    @Test
    fun clearModelsBackCloseAndNavigationExit() {
        val state = MemoSelectionState(setOf(1, 2)).clear()

        assertFalse(state.isSelectionMode)
        assertTrue(state.selectedIds.isEmpty())
    }
}

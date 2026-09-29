package io.github.cragcoffee.memoripple.ui.memos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

class CardDragControllerTest {
    private val rows = listOf(
        CardSlot(1, 0f, 0f, 100f, 129f),
        CardSlot(2, 0f, 129f, 100f, 129f),
        CardSlot(3, 0f, 258f, 100f, 129f),
    )

    private fun controller(crossings: MutableList<Pair<Long, Long>>) = CardDragController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        slots = { rows },
        onReorder = {},
        onCrossed = { id, target -> crossings += id to target },
    )

    @Test
    fun aRowCrossesWhenTheRowItselfIsOverItsNeighbourNotWhereTheFingerHoldsIt() {
        // Held near its bottom edge and carried just over half a row: the row's middle is past
        // the neighbour's, so it crosses — although the finger is still inside its own row.
        val crossings = mutableListOf<Pair<Long, Long>>()
        val drag = controller(crossings)
        drag.grab(3, 50f, 120f, listOf(1, 2, 3))
        drag.carry(0f, -70f)
        assertEquals(listOf(3L to 2L), crossings)
    }

    @Test
    fun aRowHeldNearItsTopDoesNotCrossJustBecauseTheFingerHasLeftIt() {
        val crossings = mutableListOf<Pair<Long, Long>>()
        val drag = controller(crossings)
        drag.grab(3, 50f, 10f, listOf(1, 2, 3))
        drag.carry(0f, -40f)
        assertEquals(emptyList<Pair<Long, Long>>(), crossings)
    }

    @Test
    fun aCrossingIsReportedOncePerRowUntilTheRowIsBackOverItsOwnPlace() {
        val crossings = mutableListOf<Pair<Long, Long>>()
        val drag = controller(crossings)
        drag.grab(3, 50f, 64f, listOf(1, 2, 3))
        drag.carry(0f, -100f)
        drag.carry(0f, -20f)
        assertEquals(listOf(3L to 2L), crossings)
        drag.carry(0f, 120f)
        drag.carry(0f, -100f)
        assertEquals(listOf(3L to 2L, 3L to 2L), crossings)
    }
}

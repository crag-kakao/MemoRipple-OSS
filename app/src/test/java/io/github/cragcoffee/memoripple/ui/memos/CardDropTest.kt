package io.github.cragcoffee.memoripple.ui.memos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** A card carried across its page: the slot under the finger, and the order that follows. */
class CardDropTest {
    private val slots = listOf(
        CardSlot(1, 0f, 0f, 100f, 80f),
        CardSlot(2, 110f, 0f, 100f, 120f),
        CardSlot(3, 0f, 90f, 100f, 80f),
    )

    @Test
    fun theSlotUnderTheFingerIsFoundAndAGapIsNoSlot() {
        assertEquals(1, CardDrop.indexUnder(slots, 150f, 60f))
        assertEquals(2, CardDrop.indexUnder(slots, 10f, 100f))
        assertEquals(-1, CardDrop.indexUnder(slots, 105f, 10f))
    }

    @Test
    fun aCardMovesToTheSlotItIsOverAndTheOthersCloseTheGap() {
        assertEquals(listOf(2L, 1L, 3L), CardDrop.reorder(listOf(1, 2, 3), 1, 1))
        assertEquals(listOf(3L, 1L, 2L), CardDrop.reorder(listOf(1, 2, 3), 3, 0))
        val order = listOf(1L, 2L, 3L)
        assertSame(order, CardDrop.reorder(order, 2, 1))
        assertSame(order, CardDrop.reorder(order, 9, 0))
    }
}

class CardDropNearestTest {
    @Test
    fun theNearestRowCountsAcrossAHeadingOrAGap() {
        val rows = listOf(CardSlot(1, 0f, 0f, 100f, 72f), CardSlot(2, 0f, 116f, 100f, 72f))
        assertEquals(0, CardDrop.indexNearest(rows, 30f))
        assertEquals(1, CardDrop.indexNearest(rows, 100f))
        assertEquals(-1, CardDrop.indexNearest(emptyList(), 10f))
    }
}

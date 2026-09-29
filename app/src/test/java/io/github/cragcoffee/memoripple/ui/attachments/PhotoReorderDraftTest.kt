package io.github.cragcoffee.memoripple.ui.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PhotoReorderDraftTest {
    @Test
    fun movesForwardBackwardFirstAndLast() {
        assertEquals(listOf("b", "c", "a"), movePhotoItem(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), movePhotoItem(listOf("a", "b", "c"), 2, 0))
        assertEquals(listOf("b", "a", "c"), movePhotoItem(listOf("a", "b", "c"), 0, 1))
        assertEquals(listOf("a", "c", "b"), movePhotoItem(listOf("a", "b", "c"), 2, 1))
    }

    @Test
    fun sameOrInvalidIndexReturnsOriginalList() {
        val source = listOf("a", "b", "c")
        assertSame(source, movePhotoItem(source, 1, 1))
        assertSame(source, movePhotoItem(source, -1, 1))
        assertSame(source, movePhotoItem(source, 1, 3))
    }

    @Test
    fun twentyItemsMoveFromFirstToLastAndBack() {
        val source = (0 until 20).toList()
        val last = movePhotoItem(source, 0, 19)
        assertEquals((1 until 20).toList() + 0, last)
        assertEquals(source, movePhotoItem(last, 19, 0))
    }
}

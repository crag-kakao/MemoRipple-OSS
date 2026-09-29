package io.github.cragcoffee.memoripple.domain.comments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentAppearanceTest {
    @Test
    fun stableIdsRoundTripAndUnknownLocalValuesFallBackSafely() {
        CommentColorRole.entries.forEach {
            assertEquals(it, CommentColorRole.fromStorageId(it.storageId))
        }
        CommentSizeRole.entries.forEach {
            assertEquals(it, CommentSizeRole.fromStorageId(it.storageId))
        }
        CommentEmphasisRole.entries.forEach {
            assertEquals(it, CommentEmphasisRole.fromStorageId(it.storageId))
        }
        assertEquals(CommentColorRole.DEFAULT, CommentColorRole.fromStorageId("future-color"))
        assertEquals(CommentSizeRole.STANDARD, CommentSizeRole.fromStorageId("huge"))
        assertEquals(CommentEmphasisRole.NORMAL, CommentEmphasisRole.fromStorageId("flash"))
    }

    @Test
    fun sizeMultipliersRemainOrderedAndDefaultsAreCompatible() {
        assertTrue(CommentSizeRole.SMALL.scaleMultiplier < CommentSizeRole.STANDARD.scaleMultiplier)
        assertTrue(CommentSizeRole.STANDARD.scaleMultiplier < CommentSizeRole.LARGE.scaleMultiplier)
        assertTrue(CommentAppearance.Default.isDefault)
    }
}

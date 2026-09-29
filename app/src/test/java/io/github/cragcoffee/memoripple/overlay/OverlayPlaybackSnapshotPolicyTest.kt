package io.github.cragcoffee.memoripple.overlay

import io.github.cragcoffee.memoripple.data.MemoEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPlaybackSnapshotPolicyTest {
    private fun memo(archivedAt: Long? = null, trashedAt: Long? = null) = MemoEntity(
        id = 1,
        title = "memo",
        body = "body",
        createdAt = 1,
        updatedAt = 1,
        archivedAt = archivedAt,
        trashedAt = trashedAt,
    )

    @Test fun activeAndArchivedMemosAreEligible() {
        assertTrue(memo().isOverlayPlaybackEligible())
        assertTrue(memo(archivedAt = 2).isOverlayPlaybackEligible())
    }

    @Test fun trashedMemoIsRejectedEvenWhenItHasArchiveOrigin() {
        assertFalse(memo(archivedAt = 2, trashedAt = 3).isOverlayPlaybackEligible())
    }
}

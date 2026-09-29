package io.github.cragcoffee.memoripple.domain.comments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentMotionTest {
    @Test
    fun stableIdsAndVelocityOrderingAreExplicit() {
        assertEquals(
            listOf("flow", "fixed_top", "fixed_bottom"),
            CommentMotionMode.entries.map { it.storageId },
        )
        assertEquals(listOf("slow", "standard", "fast"), CommentSpeedRole.entries.map { it.storageId })
        assertTrue(CommentSpeedRole.SLOW.velocityMultiplier < CommentSpeedRole.STANDARD.velocityMultiplier)
        assertTrue(CommentSpeedRole.STANDARD.velocityMultiplier < CommentSpeedRole.FAST.velocityMultiplier)
        assertEquals(
            listOf("auto", "top", "middle", "bottom"),
            CommentPlacementRole.entries.map { it.storageId },
        )
        assertEquals(listOf("rtl", "ltr"), CommentFlowDirection.entries.map { it.storageId })
        assertEquals(listOf("straight", "wave"), CommentFlowEffect.entries.map { it.storageId })
    }

    @Test
    fun unknownLocalValuesFallBackSafely() {
        assertEquals(CommentMotionMode.FLOW, CommentMotionMode.fromStorageId("floating"))
        assertEquals(CommentSpeedRole.STANDARD, CommentSpeedRole.fromStorageId("warp"))
        assertEquals(CommentPlacementRole.AUTO, CommentPlacementRole.fromStorageId("ceiling"))
        assertEquals(CommentFlowDirection.RIGHT_TO_LEFT, CommentFlowDirection.fromStorageId("up"))
        assertEquals(CommentFlowEffect.STRAIGHT, CommentFlowEffect.fromStorageId("bounce"))
        assertTrue(CommentMotion.Default.isDefault)
        assertFalse(CommentMotion(speedRole = CommentSpeedRole.FAST).isDefault)
        assertFalse(CommentMotion(mode = CommentMotionMode.FIXED_TOP).isDefault)
        assertFalse(CommentMotion(direction = CommentFlowDirection.LEFT_TO_RIGHT).isDefault)
        assertFalse(CommentMotion(flowEffect = CommentFlowEffect.WAVE).isDefault)
    }
}

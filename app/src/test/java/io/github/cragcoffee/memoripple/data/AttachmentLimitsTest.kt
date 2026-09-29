package io.github.cragcoffee.memoripple.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentLimitsTest {
    @Test
    fun byteLimitIncludesFiftyMiBAndRejectsOutsideBoundary() {
        assertTrue(AttachmentLimits.acceptsByteCount(AttachmentLimits.MAX_IMAGE_BYTES - 1))
        assertTrue(AttachmentLimits.acceptsByteCount(AttachmentLimits.MAX_IMAGE_BYTES))
        assertFalse(AttachmentLimits.acceptsByteCount(AttachmentLimits.MAX_IMAGE_BYTES + 1))
        assertFalse(AttachmentLimits.acceptsByteCount(0))
    }

    @Test
    fun dimensionLimitIncludesFortyThousandAndRejectsLarger() {
        assertTrue(AttachmentLimits.acceptsDimensions(39_999, 1))
        assertTrue(AttachmentLimits.acceptsDimensions(40_000, 1))
        assertFalse(AttachmentLimits.acceptsDimensions(40_001, 1))
    }

    @Test
    fun pixelLimitUsesLongAndRejectsMaliciousDimensionsWithoutOverflow() {
        assertTrue(AttachmentLimits.acceptsDimensions(25_000, 9_999))
        assertTrue(AttachmentLimits.acceptsDimensions(25_000, 10_000))
        assertFalse(AttachmentLimits.acceptsDimensions(25_000, 10_001))
        assertFalse(AttachmentLimits.acceptsDimensions(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun availableSlotsStopsAtTwenty() {
        assertEquals(1, AttachmentLimits.availablePhotoSlots(19))
        assertEquals(0, AttachmentLimits.availablePhotoSlots(20))
        assertEquals(0, AttachmentLimits.availablePhotoSlots(21))
    }

    @Test
    fun targetSamplingRoundsUpSoDecodedEdgeDoesNotExceedBucket() {
        assertEquals(1, AttachmentImageSampling.targetSampleSize(320, 320))
        assertEquals(2, AttachmentImageSampling.targetSampleSize(321, 320))
        assertEquals(2, AttachmentImageSampling.targetSampleSize(4_000, 2_048))
        assertEquals(313, AttachmentImageSampling.targetSampleSize(40_000, 128))
        assertEquals(1, AttachmentImageSampling.targetSampleSize(Int.MAX_VALUE, 0))
    }
}

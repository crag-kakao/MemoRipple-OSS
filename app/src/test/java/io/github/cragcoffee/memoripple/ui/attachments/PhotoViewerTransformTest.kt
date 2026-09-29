package io.github.cragcoffee.memoripple.ui.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoViewerTransformTest {
    @Test
    fun scaleClampsAndInvalidValuesFallBackToIdentity() {
        assertEquals(1f, PhotoViewerTransformMath.sanitizeScale(-1f), 0f)
        assertEquals(4f, PhotoViewerTransformMath.sanitizeScale(8f), 0f)
        assertEquals(1f, PhotoViewerTransformMath.sanitizeScale(Float.NaN), 0f)
        assertEquals(1f, PhotoViewerTransformMath.sanitizeScale(Float.POSITIVE_INFINITY), 0f)
        assertFalse(PhotoViewerTransformMath.isZoomed(1.009f))
        assertTrue(PhotoViewerTransformMath.isZoomed(1.02f))
    }

    @Test
    fun fitCenterPanBoundsRespectPortraitLandscapeAndSquareImages() {
        assertEquals(
            PhotoViewerPanBounds(maxX = 0f, maxY = 500f),
            PhotoViewerTransformMath.panBounds(1_000f, 1_000f, 500f, 1_000f, 2f),
        )
        assertEquals(
            PhotoViewerPanBounds(maxX = 500f, maxY = 0f),
            PhotoViewerTransformMath.panBounds(1_000f, 1_000f, 1_000f, 500f, 2f),
        )
        assertEquals(
            PhotoViewerPanBounds(maxX = 500f, maxY = 500f),
            PhotoViewerTransformMath.panBounds(1_000f, 1_000f, 800f, 800f, 2f),
        )
    }

    @Test
    fun panIsClampedAndIdentityAlwaysHasZeroTranslation() {
        val clamped = PhotoViewerTransformMath.clamp(
            transform = PhotoViewerTransform(2f, 5_000f, -5_000f),
            viewportWidth = 1_000f,
            viewportHeight = 1_000f,
            imageWidth = 1_000f,
            imageHeight = 500f,
        )
        assertEquals(500f, clamped.translationX, 0f)
        assertEquals(0f, clamped.translationY, 0f)

        assertEquals(
            PhotoViewerTransform(),
            PhotoViewerTransformMath.clamp(
                transform = PhotoViewerTransform(0.5f, 200f, 200f),
                viewportWidth = 1_000f,
                viewportHeight = 1_000f,
                imageWidth = 1_000f,
                imageHeight = 1_000f,
            ),
        )
    }

    @Test
    fun doubleTapUsesTapFocusThenResetsToIdentity() {
        val zoomed = PhotoViewerTransformMath.doubleTap(
            current = PhotoViewerTransform(),
            tapX = 750f,
            tapY = 500f,
            viewportWidth = 1_000f,
            viewportHeight = 1_000f,
            imageWidth = 1_000f,
            imageHeight = 1_000f,
        )
        assertEquals(2f, zoomed.scale, 0f)
        assertEquals(-250f, zoomed.translationX, 0f)
        assertEquals(0f, zoomed.translationY, 0f)
        assertEquals(
            PhotoViewerTransform(),
            PhotoViewerTransformMath.doubleTap(
                current = zoomed,
                tapX = 750f,
                tapY = 500f,
                viewportWidth = 1_000f,
                viewportHeight = 1_000f,
                imageWidth = 1_000f,
                imageHeight = 1_000f,
            ),
        )
    }

    @Test
    fun tapFramesCannotReapplyZoomAfterDoubleTapReset() {
        assertFalse(PhotoViewerTransformMath.shouldHandleTransform(0, 2f, 1f, 1f, 0f, false))
        assertFalse(PhotoViewerTransformMath.shouldHandleTransform(1, 2f, 1f, 0f, 0f, false))
        assertFalse(PhotoViewerTransformMath.shouldHandleTransform(1, 2f, 1f, 4f, 0f, false))
        assertFalse(PhotoViewerTransformMath.shouldHandleTransform(1, 1f, 1f, 12f, 0f, true))
        assertTrue(PhotoViewerTransformMath.shouldHandleTransform(1, 2f, 1f, 12f, 0f, true))
        assertTrue(PhotoViewerTransformMath.shouldHandleTransform(2, 1f, 1.2f, 0f, 0f, false))
        assertTrue(PhotoViewerTransformMath.shouldHandleTransform(2, 1f, 1f, 0f, 12f, false))
    }

    @Test
    fun panStartsOnlyAfterAccumulatedMovementExceedsPlatformTouchSlop() {
        assertFalse(PhotoViewerTransformMath.exceedsTouchSlop(3f, 4f, 5f))
        assertTrue(PhotoViewerTransformMath.exceedsTouchSlop(4f, 4f, 5f))
        assertFalse(PhotoViewerTransformMath.exceedsTouchSlop(Float.NaN, Float.NaN, 5f))
        assertFalse(PhotoViewerTransformMath.exceedsTouchSlop(20f, 0f, Float.NaN))
    }

    @Test
    fun transformRejectsNonFiniteInputAndZeroGeometryWithoutCrashing() {
        val value = PhotoViewerTransformMath.transformBy(
            current = PhotoViewerTransform(2f, Float.NaN, Float.NEGATIVE_INFINITY),
            zoomChange = Float.NaN,
            panX = Float.POSITIVE_INFINITY,
            panY = Float.NaN,
            focusX = Float.NaN,
            focusY = Float.POSITIVE_INFINITY,
            viewportWidth = 0f,
            viewportHeight = 0f,
            imageWidth = 0f,
            imageHeight = 0f,
        )
        assertTrue(value.scale.isFinite())
        assertTrue(value.translationX.isFinite())
        assertTrue(value.translationY.isFinite())
        assertEquals(0f, value.translationX, 0f)
        assertEquals(0f, value.translationY, 0f)
    }

    @Test
    fun viewerDecodeTargetUsesTwoViewportEdgesWithoutExceedingSource() {
        assertEquals(
            2_000,
            PhotoViewerTransformMath.viewerDecodeTargetPx(1_000, 600, 8_000, 4_000),
        )
        assertEquals(
            1_200,
            PhotoViewerTransformMath.viewerDecodeTargetPx(1_000, 600, 1_200, 800),
        )
        assertEquals(0, PhotoViewerTransformMath.viewerDecodeTargetPx(0, 600, 1_200, 800))
    }

    @Test
    fun decodeSamplingHonorsEdgeAndArgbMemoryBudgets() {
        assertEquals(
            4,
            PhotoViewerTransformMath.safeDecodeSampleSize(
                sourceWidthPx = 8_000,
                sourceHeightPx = 6_000,
                targetLongestEdgePx = 4_800,
            ),
        )
        assertEquals(
            8,
            PhotoViewerTransformMath.safeDecodeSampleSize(
                sourceWidthPx = 8_000,
                sourceHeightPx = 6_000,
                targetLongestEdgePx = 1_000,
            ),
        )
        assertEquals(1, PhotoViewerTransformMath.safeDecodeSampleSize(0, 0, 0))
    }

    @Test
    fun deletionClampsFirstMiddleLastAndClosesAfterSinglePhoto() {
        assertEquals(0, PhotoViewerTransformMath.pageAfterRemoval(currentPage = 0, remainingCount = 2))
        assertEquals(1, PhotoViewerTransformMath.pageAfterRemoval(currentPage = 1, remainingCount = 2))
        assertEquals(1, PhotoViewerTransformMath.pageAfterRemoval(currentPage = 2, remainingCount = 2))
        assertEquals(null, PhotoViewerTransformMath.pageAfterRemoval(currentPage = 0, remainingCount = 0))
    }
}

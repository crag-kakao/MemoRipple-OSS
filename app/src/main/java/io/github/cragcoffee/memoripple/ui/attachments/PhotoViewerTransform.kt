package io.github.cragcoffee.memoripple.ui.attachments

import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.sqrt

internal const val PHOTO_VIEWER_MIN_SCALE = 1f
internal const val PHOTO_VIEWER_MAX_SCALE = 4f
internal const val PHOTO_VIEWER_DOUBLE_TAP_SCALE = 2f
internal const val PHOTO_VIEWER_SCALE_EPSILON = 0.01f
internal const val PHOTO_VIEWER_ACCESSIBILITY_SCALE_STEP = 0.5f
internal const val PHOTO_VIEWER_DECODE_MULTIPLIER = 2
internal const val PHOTO_VIEWER_MAX_DECODE_PIXELS = 4_000_000L

internal data class PhotoViewerTransform(
    val scale: Float = PHOTO_VIEWER_MIN_SCALE,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
)

internal data class PhotoViewerPanBounds(
    val maxX: Float,
    val maxY: Float,
)

internal object PhotoViewerTransformMath {
    fun sanitizeScale(value: Float): Float = when {
        !value.isFinite() -> PHOTO_VIEWER_MIN_SCALE
        else -> value.coerceIn(PHOTO_VIEWER_MIN_SCALE, PHOTO_VIEWER_MAX_SCALE)
    }

    fun isZoomed(scale: Float): Boolean =
        sanitizeScale(scale) > PHOTO_VIEWER_MIN_SCALE + PHOTO_VIEWER_SCALE_EPSILON

    fun shouldHandleTransform(
        pressedPointerCount: Int,
        scale: Float,
        zoomChange: Float,
        panX: Float,
        panY: Float,
        panGestureStarted: Boolean,
    ): Boolean {
        if (pressedPointerCount <= 0) return false
        val hasPan = panX.finiteOrZero() != 0f || panY.finiteOrZero() != 0f
        val hasZoom = pressedPointerCount >= 2 &&
            zoomChange.isFinite() &&
            zoomChange > 0f &&
            zoomChange != 1f
        if (!hasPan && !hasZoom) return false
        return pressedPointerCount >= 2 || (panGestureStarted && isZoomed(scale))
    }

    fun exceedsTouchSlop(panX: Float, panY: Float, touchSlop: Float): Boolean {
        if (!touchSlop.isFinite() || touchSlop < 0f) return false
        val safeX = panX.finiteOrZero()
        val safeY = panY.finiteOrZero()
        return safeX * safeX + safeY * safeY > touchSlop * touchSlop
    }

    fun panBounds(
        viewportWidth: Float,
        viewportHeight: Float,
        imageWidth: Float,
        imageHeight: Float,
        scale: Float,
    ): PhotoViewerPanBounds {
        if (!viewportWidth.isFinite() || !viewportHeight.isFinite() ||
            !imageWidth.isFinite() || !imageHeight.isFinite() ||
            viewportWidth <= 0f || viewportHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f
        ) {
            return PhotoViewerPanBounds(0f, 0f)
        }
        val safeScale = sanitizeScale(scale)
        val fitScale = min(viewportWidth / imageWidth, viewportHeight / imageHeight)
        val fittedWidth = imageWidth * fitScale
        val fittedHeight = imageHeight * fitScale
        return PhotoViewerPanBounds(
            maxX = max(0f, (fittedWidth * safeScale - viewportWidth) / 2f),
            maxY = max(0f, (fittedHeight * safeScale - viewportHeight) / 2f),
        )
    }

    fun clamp(
        transform: PhotoViewerTransform,
        viewportWidth: Float,
        viewportHeight: Float,
        imageWidth: Float,
        imageHeight: Float,
    ): PhotoViewerTransform {
        val scale = sanitizeScale(transform.scale)
        if (!isZoomed(scale)) return PhotoViewerTransform()
        val bounds = panBounds(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            scale = scale,
        )
        return PhotoViewerTransform(
            scale = scale,
            translationX = transform.translationX.finiteOrZero().coerceIn(-bounds.maxX, bounds.maxX),
            translationY = transform.translationY.finiteOrZero().coerceIn(-bounds.maxY, bounds.maxY),
        )
    }

    fun transformBy(
        current: PhotoViewerTransform,
        zoomChange: Float,
        panX: Float,
        panY: Float,
        focusX: Float,
        focusY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        imageWidth: Float,
        imageHeight: Float,
    ): PhotoViewerTransform {
        val oldScale = sanitizeScale(current.scale)
        val safeZoom = if (zoomChange.isFinite() && zoomChange > 0f) zoomChange else 1f
        val newScale = sanitizeScale(oldScale * safeZoom)
        if (!isZoomed(newScale)) return PhotoViewerTransform()
        val ratio = newScale / oldScale
        val centerX = viewportWidth.finiteOrZero() / 2f
        val centerY = viewportHeight.finiteOrZero() / 2f
        val safeFocusX = if (focusX.isFinite()) focusX else centerX
        val safeFocusY = if (focusY.isFinite()) focusY else centerY
        val translatedX = (safeFocusX - centerX) * (1f - ratio) +
            current.translationX.finiteOrZero() * ratio + panX.finiteOrZero()
        val translatedY = (safeFocusY - centerY) * (1f - ratio) +
            current.translationY.finiteOrZero() * ratio + panY.finiteOrZero()
        return clamp(
            transform = PhotoViewerTransform(newScale, translatedX, translatedY),
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
    }

    fun setScale(
        current: PhotoViewerTransform,
        requestedScale: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        imageWidth: Float,
        imageHeight: Float,
        focusX: Float = viewportWidth / 2f,
        focusY: Float = viewportHeight / 2f,
    ): PhotoViewerTransform {
        val target = sanitizeScale(requestedScale)
        if (!isZoomed(target)) return PhotoViewerTransform()
        val oldScale = sanitizeScale(current.scale)
        return transformBy(
            current = current,
            zoomChange = target / oldScale,
            panX = 0f,
            panY = 0f,
            focusX = focusX,
            focusY = focusY,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
    }

    fun doubleTap(
        current: PhotoViewerTransform,
        tapX: Float,
        tapY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        imageWidth: Float,
        imageHeight: Float,
    ): PhotoViewerTransform = if (isZoomed(current.scale)) {
        PhotoViewerTransform()
    } else {
        setScale(
            current = current,
            requestedScale = PHOTO_VIEWER_DOUBLE_TAP_SCALE,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            focusX = tapX,
            focusY = tapY,
        )
    }

    fun viewerDecodeTargetPx(
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        sourceWidthPx: Int,
        sourceHeightPx: Int,
    ): Int {
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0 ||
            sourceWidthPx <= 0 || sourceHeightPx <= 0
        ) {
            return 0
        }
        val viewportLongest = max(viewportWidthPx, viewportHeightPx).toLong()
        val sourceLongest = max(sourceWidthPx, sourceHeightPx).toLong()
        return min(sourceLongest, viewportLongest * PHOTO_VIEWER_DECODE_MULTIPLIER)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun safeDecodeSampleSize(
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        targetLongestEdgePx: Int,
        maxDecodedPixels: Long = PHOTO_VIEWER_MAX_DECODE_PIXELS,
    ): Int {
        if (sourceWidthPx <= 0 || sourceHeightPx <= 0 || targetLongestEdgePx <= 0 ||
            maxDecodedPixels <= 0
        ) {
            return 1
        }
        val sourceLongest = max(sourceWidthPx, sourceHeightPx).toLong()
        val edgeSample = ((sourceLongest + targetLongestEdgePx - 1L) / targetLongestEdgePx)
            .coerceAtLeast(1L)
        val sourcePixels = sourceWidthPx.toLong() * sourceHeightPx.toLong()
        val pixelSample = ceil(sqrt(sourcePixels.toDouble() / maxDecodedPixels.toDouble()))
            .toLong()
            .coerceAtLeast(1L)
        return max(edgeSample, pixelSample).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun pageAfterRemoval(currentPage: Int, remainingCount: Int): Int? =
        if (remainingCount <= 0) null else currentPage.coerceIn(0, remainingCount - 1)

    private fun Float.finiteOrZero(): Float = if (isFinite()) this else 0f
}

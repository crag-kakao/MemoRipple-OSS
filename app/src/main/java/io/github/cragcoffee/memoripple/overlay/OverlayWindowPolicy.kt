package io.github.cragcoffee.memoripple.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager

data class OverlayUsableBounds(
    val width: Int,
    val height: Int,
    val x: Int,
    val y: Int,
)

object OverlayWindowPolicy {
    const val ROOT_WINDOW_COUNT = 1
    const val TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    // FLAG_HARDWARE_ACCELERATED because a service-added window does not inherit the
    // application's hardware renderer: without it every stroked glyph is rasterised in
    // software on the UI thread, the one place frame profiling showed playback pays most.
    // Focus, touch-through, and the tapjacking alpha are contracts and stay exactly as-is.
    const val FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    fun safeAlpha(context: Context): Float {
        val maximum = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(InputManager::class.java)
                ?.maximumObscuringOpacityForTouch
        } else {
            null
        }
        return calculateSafeOverlayAlpha(maximum)
    }

    fun usableBounds(context: Context, windowManager: WindowManager): OverlayUsableBounds {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            val bounds = metrics.bounds
            return OverlayUsableBounds(
                width = (bounds.width() - insets.left - insets.right).coerceAtLeast(1),
                height = (bounds.height() - insets.top - insets.bottom).coerceAtLeast(1),
                x = insets.left,
                y = insets.top,
            )
        }
        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics().also(windowManager.defaultDisplay::getMetrics)
        return OverlayUsableBounds(metrics.widthPixels, metrics.heightPixels, 0, 0)
    }

    fun regionBounds(
        usableBounds: OverlayUsableBounds,
        region: OverlayDisplayRegion,
    ): OverlayUsableBounds {
        val halfHeight = (usableBounds.height / 2).coerceAtLeast(1)
        return when (region) {
            OverlayDisplayRegion.FULL -> usableBounds
            OverlayDisplayRegion.TOP_HALF -> usableBounds.copy(height = halfHeight)
            OverlayDisplayRegion.CENTER -> usableBounds.copy(
                height = halfHeight,
                y = usableBounds.y + usableBounds.height / 4,
            )
            OverlayDisplayRegion.BOTTOM_HALF -> usableBounds.copy(
                height = halfHeight,
                y = usableBounds.y + usableBounds.height - halfHeight,
            )
        }
    }

    fun layoutParams(
        context: Context,
        windowManager: WindowManager,
        region: OverlayDisplayRegion = OverlayDisplayRegion.FULL,
    ): WindowManager.LayoutParams {
        val bounds = regionBounds(usableBounds(context, windowManager), region)
        return WindowManager.LayoutParams(
            bounds.width,
            bounds.height,
            TYPE,
            FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.x
            y = bounds.y
            alpha = safeAlpha(context)
        }
    }
}

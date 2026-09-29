package io.github.cragcoffee.memoripple.overlay

import io.github.cragcoffee.memoripple.domain.playback.PlaybackContentMode
import io.github.cragcoffee.memoripple.domain.playback.PlaybackTimeline
import kotlin.math.roundToLong

enum class OverlayDisplayRegion(val storageId: String) {
    FULL("full"),
    TOP_HALF("top_half"),
    CENTER("center"),
    BOTTOM_HALF("bottom_half"),
    ;

    companion object {
        fun fromStorageId(value: String?): OverlayDisplayRegion =
            entries.firstOrNull { it.storageId == value } ?: FULL
    }
}

enum class OverlayDensity(val storageId: String, val startTimeMultiplier: Double) {
    SPARSE("sparse", 1.30),
    STANDARD("standard", 1.00),
    DENSE("dense", 0.80),
    ;

    companion object {
        fun fromStorageId(value: String?): OverlayDensity =
            entries.firstOrNull { it.storageId == value } ?: STANDARD
    }
}

data class OverlayPlaybackOptions(
    val contentMode: PlaybackContentMode = PlaybackContentMode.BOTH,
    val displayRegion: OverlayDisplayRegion = OverlayDisplayRegion.FULL,
    val density: OverlayDensity = OverlayDensity.STANDARD,
)

/** Density changes desired start times only. Motion and typography remain source-owned. */
fun PlaybackTimeline.withOverlayDensity(density: OverlayDensity): PlaybackTimeline {
    if (items.isEmpty() || density == OverlayDensity.STANDARD) return this
    val adjustedItems = items.map { item ->
        item.copy(
            startTimeMillis = (item.startTimeMillis * density.startTimeMultiplier)
                .roundToLong()
                .coerceAtLeast(0L),
        )
    }
    return copy(
        items = adjustedItems,
        totalDurationMillis = adjustedItems.maxOf {
            it.startTimeMillis + it.travelDurationMillis
        },
    )
}

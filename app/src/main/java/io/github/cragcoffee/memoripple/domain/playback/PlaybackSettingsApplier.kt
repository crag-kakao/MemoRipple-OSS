package io.github.cragcoffee.memoripple.domain.playback

import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import kotlin.math.roundToLong

/** Applies global settings before text measurement and lane allocation. */
class PlaybackSettingsApplier {
    fun apply(timeline: PlaybackTimeline, settings: AppSettings): PlaybackTimeline {
        if (timeline.items.isEmpty()) return timeline
        val items = timeline.items.map { item ->
            item.copy(
                travelDurationMillis = (item.travelDurationMillis /
                    settings.playbackSpeedScale)
                    .roundToLong()
                    .coerceAtLeast(1L),
                fontScale = item.fontScale * settings.commentSizeScale,
            )
        }
        return timeline.copy(
            items = items,
            totalDurationMillis = items.maxOf {
                it.startTimeMillis + it.travelDurationMillis
            },
        )
    }
}

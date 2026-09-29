package io.github.cragcoffee.memoripple.domain.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlaybackStatus { IDLE, PLAYING, PAUSED }

data class CommentAnimationState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val elapsedMillis: Long = 0,
    val timeline: PlaybackTimeline? = null,
)

/** Owns app playback time, pause/resume, completion cleanup, and linear motion math. */
class CommentAnimator {
    private val _state = MutableStateFlow(CommentAnimationState())
    val state: StateFlow<CommentAnimationState> = _state.asStateFlow()

    fun play(timeline: PlaybackTimeline) {
        _state.value = if (timeline.items.isEmpty()) {
            CommentAnimationState()
        } else {
            CommentAnimationState(status = PlaybackStatus.PLAYING, timeline = timeline)
        }
    }

    fun pause() {
        if (_state.value.status == PlaybackStatus.PLAYING) {
            _state.value = _state.value.copy(status = PlaybackStatus.PAUSED)
        }
    }

    fun resume() {
        if (_state.value.status == PlaybackStatus.PAUSED) {
            _state.value = _state.value.copy(status = PlaybackStatus.PLAYING)
        }
    }

    fun advanceBy(deltaMillis: Long) {
        val current = _state.value
        val timeline = current.timeline ?: return
        if (current.status != PlaybackStatus.PLAYING || deltaMillis <= 0) return

        val elapsed = current.elapsedMillis + deltaMillis.coerceAtMost(MAX_FRAME_STEP_MILLIS)
        // A reading ends when its last comment has gone. Where something is 完全固定 or
        // ループ, nothing goes on its own, so the clock keeps running until 停止.
        _state.value = if (elapsed >= timeline.totalDurationMillis && !timeline.hasEndless) {
            CommentAnimationState()
        } else {
            current.copy(elapsedMillis = elapsed)
        }
    }

    fun stop() {
        _state.value = CommentAnimationState()
    }

    fun horizontalOffsetPx(
        item: PlaybackItem,
        elapsedMillis: Long,
        containerWidthPx: Float,
        commentWidthPx: Float,
    ): Float {
        // ループ: each crossing is the same crossing, so the clock is read modulo one of them.
        val progress = CommentFlowPath.progress(
            elapsedMillis = if (item.endless) {
                item.startTimeMillis +
                    (elapsedMillis - item.startTimeMillis).mod(item.travelDurationMillis)
            } else {
                elapsedMillis
            },
            startTimeMillis = item.startTimeMillis,
            durationMillis = item.travelDurationMillis,
        )
        return CommentFlowPath.horizontalOffsetPx(
            direction = item.flowDirection,
            progress = progress,
            containerWidthPx = containerWidthPx,
            commentWidthPx = commentWidthPx,
        )
    }

    companion object {
        private const val MAX_FRAME_STEP_MILLIS = 100L
    }
}

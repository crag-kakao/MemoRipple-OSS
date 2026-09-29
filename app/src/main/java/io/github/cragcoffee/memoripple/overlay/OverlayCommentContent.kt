package io.github.cragcoffee.memoripple.overlay

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.domain.playback.CommentAnimator
import io.github.cragcoffee.memoripple.domain.playback.PlaybackStatus
import io.github.cragcoffee.memoripple.domain.playback.PlaybackTimeline
import io.github.cragcoffee.memoripple.ui.playback.CommentPlaybackFrameClock
import io.github.cragcoffee.memoripple.ui.playback.CommentRenderer
import io.github.cragcoffee.memoripple.ui.playback.COMMENT_WAVE_DESIRED_AMPLITUDE
import io.github.cragcoffee.memoripple.ui.playback.LocalCommentFontFamily
import io.github.cragcoffee.memoripple.ui.playback.measureCommentTextMetricsPx
import io.github.cragcoffee.memoripple.ui.playback.overlayCommentRendererColors

@Composable
fun OverlayCommentContent(
    sourceTimeline: PlaybackTimeline,
    options: OverlayPlaybackOptions,
    animator: CommentAnimator,
    planFactory: OverlayPlaybackPlanFactory,
    longCommentReadability: Boolean = false,
    onPlaying: (OverlayPlaybackPlan) -> Unit,
    onNaturalCompletion: () -> Unit,
    onRejected: (String) -> Unit,
) {
    MaterialTheme {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val textMeasurer = rememberTextMeasurer()
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()
            val minimumLaneHeightPx = with(density) { 26.dp.toPx() }
            val fontFamily = LocalCommentFontFamily.current
            val textMetrics = remember(sourceTimeline, textMeasurer, density, widthPx, fontFamily) {
                measureCommentTextMetricsPx(sourceTimeline, textMeasurer, density, widthPx, fontFamily)
            }
            val plan = remember(
                sourceTimeline,
                options,
                textMetrics,
                widthPx,
                heightPx,
                minimumLaneHeightPx,
                longCommentReadability,
            ) {
                planFactory.create(
                    sourceTimeline = sourceTimeline,
                    options = options,
                    renderWidthsPx = textMetrics.mapValues { it.value.renderWidthPx },
                    textMetrics = textMetrics,
                    containerWidthPx = widthPx,
                    availableHeightPx = heightPx,
                    minimumLaneHeightPx = minimumLaneHeightPx,
                    verticalSafetyGapPx = with(density) { 4.dp.toPx() },
                    desiredWaveAmplitudePx = with(density) {
                        COMMENT_WAVE_DESIRED_AMPLITUDE.toPx()
                    },
                    longCommentReadability = longCommentReadability,
                )
            }
            var started by remember(sourceTimeline) { mutableStateOf(false) }
            // Collected as a State, read here only through the derived status: the clock's
            // per-frame ticks stay inside the renderer's placement lambdas.
            val animationState = animator.state.collectAsState()
            val playbackStatus by remember(animationState) {
                derivedStateOf { animationState.value.status }
            }

            LaunchedEffect(plan) {
                when (val resolution = plan.resolution) {
                    is OverlayTimelineResolution.Ready -> {
                        animator.play(resolution.timeline)
                        started = true
                        onPlaying(plan)
                    }
                    OverlayTimelineResolution.Empty -> onRejected(
                        "再生できるコメントがありません",
                    )
                    is OverlayTimelineResolution.TooLong -> onRejected(OVERLAY_TOO_LONG_MESSAGE)
                    is OverlayTimelineResolution.CannotRenderFixedComment -> onRejected(
                        FIXED_COMMENT_TOO_LARGE_MESSAGE,
                    )
                }
            }

            CommentPlaybackFrameClock(
                status = playbackStatus,
                onFrame = animator::advanceBy,
            )
            if (started && playbackStatus == PlaybackStatus.IDLE) {
                LaunchedEffect(Unit) { onNaturalCompletion() }
            }
            CommentRenderer(
                animationState = animationState,
                offsetProvider = animator::horizontalOffsetPx,
                colors = overlayCommentRendererColors(),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

const val OVERLAY_TOO_LONG_MESSAGE =
    "コメント数が多いため、一度にオーバーレイ再生できません。再生内容を絞ってもう一度お試しください。"

const val FIXED_COMMENT_TOO_LARGE_MESSAGE =
    "固定表示するにはコメントが長すぎます。文字を小さくするか、流れる表示に変更してください。"

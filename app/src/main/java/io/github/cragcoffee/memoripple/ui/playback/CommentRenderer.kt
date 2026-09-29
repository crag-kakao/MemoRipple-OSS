package io.github.cragcoffee.memoripple.ui.playback

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.cragcoffee.memoripple.domain.playback.CommentAnimationState
import io.github.cragcoffee.memoripple.domain.playback.CommentFlowPath
import io.github.cragcoffee.memoripple.domain.playback.CommentLaneBandResolver
import io.github.cragcoffee.memoripple.domain.comments.CommentColorRole
import io.github.cragcoffee.memoripple.domain.playback.PlaybackEmphasis
import io.github.cragcoffee.memoripple.domain.playback.PlaybackItem
import io.github.cragcoffee.memoripple.domain.playback.PlaybackStatus
import io.github.cragcoffee.memoripple.domain.playback.PlaybackTimeline
import io.github.cragcoffee.memoripple.domain.playback.PlaybackValidationIssue
import io.github.cragcoffee.memoripple.domain.playback.ResolvedPlaybackBehavior
import io.github.cragcoffee.memoripple.domain.playback.ResolvedFlowEffect
import io.github.cragcoffee.memoripple.domain.settings.StageBackground
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

typealias CommentOffsetProvider = (PlaybackItem, Long, Float, Float) -> Float

@Immutable
data class CommentRendererColors(
    val text: Color,
    val commentBackground: Color,
    val shadow: Color,
    val red: Color,
    val blue: Color,
    val green: Color,
    val yellow: Color,
    val cyan: Color,
    val pink: Color,
    val orange: Color,
    val purple: Color,
    val gray: Color,
    val black: Color,
)

@Immutable
data class CommentStagePalette(
    val background: Color,
    val placeholderText: Color,
    val commentColors: CommentRendererColors,
)

data class CommentTextMetrics(
    val measuredTextWidthPx: Float,
    val measuredTextHeightPx: Float,
    val renderWidthPx: Float,
    val renderHeightPx: Float,
)

@Composable
fun inlineCommentRendererColors(): CommentRendererColors {
    val darkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return CommentRendererColors(
        text = MaterialTheme.colorScheme.onSurface,
        commentBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        shadow = MaterialTheme.colorScheme.surface,
        red = if (darkSurface) Color(0xFFFF6B6B) else Color(0xFFD32F2F),
        blue = if (darkSurface) Color(0xFF64B5F6) else Color(0xFF1565C0),
        green = if (darkSurface) Color(0xFF81C784) else Color(0xFF2E7D32),
        yellow = if (darkSurface) Color(0xFFFFD54F) else Color(0xFF806000),
        cyan = if (darkSurface) Color(0xFF4DD0E1) else Color(0xFF007C91),
        pink = if (darkSurface) Color(0xFFF48FB1) else Color(0xFFC2185B),
        orange = if (darkSurface) Color(0xFFFFB74D) else Color(0xFF9A4C00),
        purple = if (darkSurface) Color(0xFFCE93D8) else Color(0xFF7B1FA2),
        gray = if (darkSurface) Color(0xFF9E9E9E) else Color(0xFF616161),
        black = Color(0xFF111111),
    )
}

fun stageCommentRendererColors() = CommentRendererColors(
    text = Color.White,
    commentBackground = Color(0xFF242424).copy(alpha = 0.88f),
    shadow = Color.Black,
    red = Color(0xFFFF6B6B),
    blue = Color(0xFF64B5F6),
    green = Color(0xFF81C784),
    yellow = Color(0xFFFFD54F),
    cyan = Color(0xFF4DD0E1),
    pink = Color(0xFFF48FB1),
    orange = Color(0xFFFFB74D),
    purple = Color(0xFFCE93D8),
    gray = Color(0xFF9E9E9E),
    black = Color(0xFF111111),
)

fun overlayCommentRendererColors() = CommentRendererColors(
    text = Color.White,
    commentBackground = Color(0xFF202124).copy(alpha = 0.82f),
    shadow = Color.Black,
    red = Color(0xFFFF6B6B),
    blue = Color(0xFF64B5F6),
    green = Color(0xFF81C784),
    yellow = Color(0xFFFFD54F),
    cyan = Color(0xFF4DD0E1),
    pink = Color(0xFFF48FB1),
    orange = Color(0xFFFFB74D),
    purple = Color(0xFFCE93D8),
    gray = Color(0xFF9E9E9E),
    black = Color(0xFF111111),
)

@Composable
fun memoCommentStagePalette(background: StageBackground): CommentStagePalette = when (background) {
    StageBackground.BLACK -> CommentStagePalette(
        background = Color.Black,
        placeholderText = Color.White.copy(alpha = 0.45f),
        commentColors = stageCommentRendererColors(),
    )
    StageBackground.DARK_GRAY -> CommentStagePalette(
        background = Color(0xFF303030),
        placeholderText = Color.White.copy(alpha = 0.55f),
        commentColors = CommentRendererColors(
            text = Color.White,
            commentBackground = Color(0xFF181818).copy(alpha = 0.90f),
            shadow = Color.Black,
            red = Color(0xFFFF6B6B),
            blue = Color(0xFF64B5F6),
            green = Color(0xFF81C784),
            yellow = Color(0xFFFFD166),
            cyan = Color(0xFF4DD0E1),
            pink = Color(0xFFF48FB1),
            orange = Color(0xFFFFB74D),
            purple = Color(0xFFCE93D8),
            gray = Color(0xFF9E9E9E),
            black = Color(0xFF111111),
        ),
    )
    StageBackground.LIGHT -> CommentStagePalette(
        background = Color(0xFFF4F5F2),
        placeholderText = Color(0xFF343834).copy(alpha = 0.55f),
        commentColors = CommentRendererColors(
            text = Color(0xFF1C211E),
            commentBackground = Color.White.copy(alpha = 0.94f),
            shadow = Color(0xFFB6BAB6),
            red = Color(0xFFB3261E),
            blue = Color(0xFF0D47A1),
            green = Color(0xFF1B5E20),
            yellow = Color(0xFF795700),
            cyan = Color(0xFF006064),
            pink = Color(0xFFAD1457),
            orange = Color(0xFF9A4C00),
            purple = Color(0xFF6A1B9A),
            gray = Color(0xFF616161),
            black = Color(0xFF111111),
        ),
    )
    StageBackground.THEME -> CommentStagePalette(
        background = MaterialTheme.colorScheme.surface,
        placeholderText = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
        commentColors = inlineCommentRendererColors().copy(
            commentBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
            shadow = MaterialTheme.colorScheme.background,
        ),
    )
}

/**
 * The typeface flowing comments wear, chosen in 設定. `null` is the default gothic (the system
 * sans). Provided once at each Compose root — the activity and the overlay window — and read at
 * the two places letters actually meet a face: the moving comment and the pre-flight measure.
 */
val LocalCommentFontFamily = staticCompositionLocalOf<FontFamily?> { null }

/**
 * Whether flowing comments wear their translucent backdrop plate, chosen in 設定. Off means the
 * video site's look: bare letters with a stroked edge, nothing between them and the page.
 */
val LocalCommentBackdropEnabled = staticCompositionLocalOf { false }

/**
 * The video site's コメント透過, chosen in 設定: 0 is solid, 0.80 is its 強. One alpha over the
 * whole comment — letters, edge, and plate together — so the page shows through evenly.
 */
val LocalCommentTransparency = staticCompositionLocalOf { 0f }

fun playbackTextStyle(
    item: PlaybackItem,
    shadowColor: Color = Color.Transparent,
    fontFamily: FontFamily? = null,
    baseFontSize: TextUnit = COMMENT_BASE_FONT_SIZE,
): TextStyle =
    TextStyle(
        fontFamily = fontFamily,
        fontSize = baseFontSize * item.fontScale,
        // The video site letters every comment bold; 強調 pushes one step heavier.
        fontWeight = if (item.emphasis == PlaybackEmphasis.STRONG) {
            FontWeight.ExtraBold
        } else {
            FontWeight.Bold
        },
        shadow = Shadow(
            color = shadowColor,
            offset = Offset(1f, 1f),
            blurRadius = 2f,
        ),
    )

fun CommentRendererColors.textFor(role: CommentColorRole): Color = when (role) {
    CommentColorRole.DEFAULT -> text
    CommentColorRole.RED -> red
    CommentColorRole.BLUE -> blue
    CommentColorRole.GREEN -> green
    CommentColorRole.YELLOW -> yellow
    CommentColorRole.CYAN -> cyan
    CommentColorRole.PINK -> pink
    CommentColorRole.ORANGE -> orange
    CommentColorRole.PURPLE -> purple
    CommentColorRole.GRAY -> gray
    CommentColorRole.BLACK -> black
}

/**
 * The edge a comment's letters wear. Uniform for every colour but one: 黒 on a dark stage would
 * sink without a light edge, which is exactly how the video site draws its black comments.
 */
fun CommentRendererColors.shadowFor(role: CommentColorRole): Color =
    if (role == CommentColorRole.BLACK && text.luminance() > 0.5f) {
        Color.White.copy(alpha = 0.85f)
    } else {
        shadow
    }

fun measureCommentTextMetricsPx(
    timeline: PlaybackTimeline,
    textMeasurer: TextMeasurer,
    density: Density,
    availableWidthPx: Float = Float.POSITIVE_INFINITY,
    fontFamily: FontFamily? = null,
    baseFontSize: TextUnit = COMMENT_BASE_FONT_SIZE,
): Map<Int, CommentTextMetrics> = timeline.items.associate { item ->
    val contentPadding = with(density) { COMMENT_CONTENT_HORIZONTAL_PADDING.toPx() }
    val glyphSafetyPadding = with(density) { GLYPH_SAFETY_HORIZONTAL_PADDING.toPx() }
    val verticalPadding = with(density) { COMMENT_CONTENT_VERTICAL_PADDING.toPx() }
    val fixedHorizontalMargin = with(density) { FIXED_COMMENT_HORIZONTAL_MARGIN.toPx() }
    val maximumFixedTextWidth = if (availableWidthPx.isFinite()) {
        availableWidthPx - 2 * fixedHorizontalMargin -
            2 * contentPadding - 2 * glyphSafetyPadding
    } else {
        Float.POSITIVE_INFINITY
    }
    val isFixed = item.behavior == ResolvedPlaybackBehavior.FIXED
    val textLayout = textMeasurer.measure(
        text = item.text,
        style = playbackTextStyle(item, fontFamily = fontFamily, baseFontSize = baseFontSize),
        overflow = TextOverflow.Clip,
        softWrap = isFixed,
        maxLines = if (isFixed) Int.MAX_VALUE else 1,
        constraints = if (isFixed && maximumFixedTextWidth.isFinite()) {
            Constraints(maxWidth = ceil(maximumFixedTextWidth.coerceAtLeast(1f)).toInt())
        } else {
            Constraints()
        },
    )
    val measuredTextWidth = textLayout.multiParagraph.width
    val measuredTextHeight = textLayout.multiParagraph.height
    item.id to CommentTextMetrics(
        measuredTextWidthPx = measuredTextWidth,
        measuredTextHeightPx = measuredTextHeight,
        renderWidthPx = calculateCommentRenderWidthPx(
            measuredTextWidthPx = measuredTextWidth,
            contentHorizontalPaddingPx = contentPadding,
            glyphSafetyPaddingPx = glyphSafetyPadding,
        ),
        renderHeightPx = ceil(measuredTextHeight.coerceAtLeast(0f)) + 2 * verticalPadding,
    )
}

fun calculateCommentRenderWidthPx(
    measuredTextWidthPx: Float,
    contentHorizontalPaddingPx: Float,
    glyphSafetyPaddingPx: Float,
): Float = ceil(measuredTextWidthPx.coerceAtLeast(0f)) +
    2 * contentHorizontalPaddingPx.coerceAtLeast(0f) +
    2 * glyphSafetyPaddingPx.coerceAtLeast(0f)

/**
 * 長いコメントを読みやすくする: how much one flowing comment's crossing stretches when the
 * aid is on. The standard is the ニコニコ準拠 fixed crossing — every comment takes the same
 * time, so a wide one simply moves faster. This optional aid bends that for comments wider
 * than the stage: the factor grows from 1.0 at exactly stage width, by half the overshoot
 * ratio, capped at 1.5 (a base 4-second crossing never exceeds ~6 seconds). Measured render
 * width against the surface's own width — never a character count — so 「WWWW」 and
 * 「iiii」, or 全角 and half-width, are judged by the space they actually occupy.
 */
fun longCommentReadabilityFactor(renderWidthPx: Float, containerWidthPx: Float): Float {
    if (renderWidthPx <= 0f || !containerWidthPx.isFinite() || containerWidthPx <= 0f) return 1f
    val ratio = renderWidthPx / containerWidthPx
    return (1f + (ratio - 1f) * READABILITY_SLOPE)
        .coerceIn(1f, READABILITY_MAX_FACTOR)
}

private const val READABILITY_SLOPE = 0.5f
private const val READABILITY_MAX_FACTOR = 1.5f

fun resolveCommentLaneLayout(
    timeline: PlaybackTimeline,
    textMetrics: Map<Int, CommentTextMetrics>,
    availableHeightPx: Float,
    minimumLaneHeightPx: Float,
    verticalSafetyGapPx: Float,
    availableWidthPx: Float = Float.POSITIVE_INFINITY,
    desiredWaveAmplitudePx: Float = 0f,
    longCommentReadability: Boolean = false,
): PlaybackTimeline {
    if (timeline.items.isEmpty() || availableHeightPx <= 0f) return timeline
    val desiredWaveAmplitude = desiredWaveAmplitudePx
        .takeIf(Float::isFinite)
        ?.coerceAtLeast(0f)
        ?: 0f
    val maximumRequiredVerticalExtent = timeline.items.maxOf { item ->
        val renderHeight = textMetrics[item.id]?.renderHeightPx ?: 0f
        if (item.behavior == ResolvedPlaybackBehavior.FLOW &&
            item.flowEffect == ResolvedFlowEffect.WAVE
        ) {
            renderHeight + 2f * desiredWaveAmplitude
        } else {
            renderHeight
        }
    }
    val requestedLaneHeight = maxOf(
        minimumLaneHeightPx.coerceAtLeast(1f),
        maximumRequiredVerticalExtent + verticalSafetyGapPx.coerceAtLeast(0f),
    )
    val laneCount = (availableHeightPx / requestedLaneHeight)
        .toInt()
        .coerceIn(1, timeline.laneCount)
    val laneHeight = minOf(requestedLaneHeight, availableHeightPx).coerceAtLeast(1f)
    val fixedDoesNotFit = timeline.items.any { item ->
        item.behavior == ResolvedPlaybackBehavior.FIXED &&
            ((textMetrics[item.id]?.renderHeightPx ?: 0f) + verticalSafetyGapPx >
                availableHeightPx ||
                (availableWidthPx.isFinite() &&
                    (textMetrics[item.id]?.renderWidthPx ?: 0f) > availableWidthPx))
    }
    val bandResolver = CommentLaneBandResolver()
    return timeline.copy(
        laneCount = laneCount,
        laneHeightPx = laneHeight,
        validationIssue = if (fixedDoesNotFit) {
            PlaybackValidationIssue.FIXED_COMMENT_DOES_NOT_FIT
        } else {
            timeline.validationIssue
        },
        items = timeline.items.map { item ->
            val allowedLanes = item.laneBand?.let { bandResolver.candidates(it, laneCount) }
                ?: emptyList()
            // The reading aid stretches only what it may: flowing comments whose writer
            // did not pin their speed. It rides the measured width, computed once here —
            // the allocator then sees the stretched duration, so lane occupancy and the
            // catch-up mathematics stay honest.
            val readabilityFactor = if (
                longCommentReadability &&
                item.behavior == ResolvedPlaybackBehavior.FLOW &&
                item.readabilityAdjustable
            ) {
                longCommentReadabilityFactor(
                    renderWidthPx = textMetrics[item.id]?.renderWidthPx ?: 0f,
                    containerWidthPx = availableWidthPx,
                )
            } else {
                1f
            }
            item.copy(
                travelDurationMillis = if (readabilityFactor == 1f) {
                    item.travelDurationMillis
                } else {
                    (item.travelDurationMillis * readabilityFactor)
                        .roundToLong()
                        .coerceAtLeast(1L)
                },
                laneIndex = item.laneIndex.mod(laneCount),
                renderHeightPx = textMetrics[item.id]?.renderHeightPx ?: item.renderHeightPx,
                allowedLaneIndices = allowedLanes,
                waveAmplitudePx = if (
                    item.behavior == ResolvedPlaybackBehavior.FLOW &&
                    item.flowEffect == ResolvedFlowEffect.WAVE
                ) {
                    CommentFlowPath.effectiveAmplitudePx(
                        desiredAmplitudePx = desiredWaveAmplitude,
                        renderHeightPx = textMetrics[item.id]?.renderHeightPx
                            ?: item.renderHeightPx,
                        laneHeightPx = laneHeight,
                        verticalSafetyGapPx = verticalSafetyGapPx,
                    )
                } else {
                    0f
                },
            )
        },
    )
}

/**
 * Draws only the transient comment layer. It installs no pointer input handlers.
 *
 * [dimBehind] is the video site's darkened room: while comments are engaged the page behind
 * them fades toward black, so white stroked lettering owns the light the way it does over a
 * video. Pass it on bright reading surfaces together with [stageCommentRendererColors];
 * surfaces that are already dark (stage, reveal) or transparent (overlay) leave it off.
 */
@Composable
fun CommentRenderer(
    animationState: State<CommentAnimationState>,
    offsetProvider: CommentOffsetProvider,
    colors: CommentRendererColors,
    modifier: Modifier = Modifier,
    dimBehind: Boolean = false,
    baseFontSize: TextUnit = COMMENT_BASE_FONT_SIZE,
) {
    if (dimBehind) {
        val engaged by remember(animationState) {
            derivedStateOf {
                animationState.value.status != PlaybackStatus.IDLE &&
                    animationState.value.timeline != null
            }
        }
        val scrimAlpha by animateFloatAsState(
            targetValue = if (engaged) COMMENT_FLIGHT_SCRIM_ALPHA else 0f,
            animationSpec = tween(durationMillis = 300),
            label = "commentFlightScrim",
        )
        if (scrimAlpha > 0.005f) {
            Box(
                modifier = modifier
                    .graphicsLayer { alpha = scrimAlpha }
                    .background(Color.Black)
                    .clearAndSetSemantics { },
            )
        }
    }
    // The timeline changes only when playback starts or stops; deriving it keeps every clock
    // tick from reaching this composition at all.
    val timeline by remember(animationState) {
        derivedStateOf { animationState.value.timeline }
    }
    val currentTimeline = timeline ?: return
    // The clock hand: written each frame, read only while placing, so a tick moves every comment
    // without recomposing or remeasuring any of them.
    val elapsedMillis = remember(animationState) {
        derivedStateOf { animationState.value.elapsedMillis }
    }
    // Membership changes only when a comment sets off or retires. Between those moments each
    // tick recomputes an equal list and structural equality swallows it, so the flight deck
    // below recomposes a handful of times per stream instead of sixty times a second.
    val activeItems by remember(animationState) {
        derivedStateOf(structuralEqualityPolicy()) {
            val state = animationState.value
            val items = state.timeline?.items ?: emptyList()
            val elapsed = state.elapsedMillis
            items.filter { item ->
                elapsed >= item.startTimeMillis &&
                    // 完全固定 and ループ have no last frame: once they have set off they stay.
                    (
                        item.endless ||
                            elapsed < item.startTimeMillis + item.travelDurationMillis
                        )
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .clearAndSetSemantics { },
    ) {
        val containerWidth = constraints.maxWidth.toFloat()
        val laneHeight = currentTimeline.laneHeightPx.takeIf { it > 0f }
            ?: constraints.maxHeight.toFloat() / currentTimeline.laneCount
        activeItems.forEach { item ->
            // Keyed by the comment, not its position in the active list: one comment retiring
            // must not tear down and remount every comment after it mid-flight.
            key(item.id) {
                MovingComment(
                    item = item,
                    elapsedMillis = elapsedMillis,
                    containerWidthPx = containerWidth,
                    laneTopPx = laneHeight * item.laneIndex,
                    offsetProvider = offsetProvider,
                    colors = colors,
                    baseFontSize = baseFontSize,
                )
            }
        }
    }
}

@Composable
private fun MovingComment(
    item: PlaybackItem,
    elapsedMillis: State<Long>,
    containerWidthPx: Float,
    laneTopPx: Float,
    offsetProvider: CommentOffsetProvider,
    colors: CommentRendererColors,
    baseFontSize: TextUnit,
) {
    var commentWidth by remember(item.id) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val isStrong = item.emphasis == PlaybackEmphasis.STRONG
    val surfaceWidth = with(density) { item.renderWidthPx.toDp() }

    Box(
        modifier = Modifier
            // 点滅: like position, alpha is a function of the clock alone, applied at draw
            // time so a blinking comment never recomposes per frame.
            .graphicsLayer {
                alpha = if (item.flowEffect == ResolvedFlowEffect.BLINK) {
                    blinkAlpha(elapsedMillis.value - item.startTimeMillis)
                } else {
                    1f
                }
            }
            // Position is a function of the clock alone, evaluated during placement: each frame
            // only moves this box, it never rebuilds or remeasures the text inside it.
            .offset {
                val elapsed = elapsedMillis.value
                val motionWidth = item.renderWidthPx.takeIf { it > 0f } ?: commentWidth.toFloat()
                val x = if (item.behavior == ResolvedPlaybackBehavior.FIXED) {
                    (containerWidthPx - motionWidth) / 2f
                } else {
                    offsetProvider(item, elapsed, containerWidthPx, motionWidth)
                }
                val waveBaseOffset: Float
                val waveOffset: Float
                if (item.behavior == ResolvedPlaybackBehavior.FLOW) {
                    waveBaseOffset = if (item.flowEffect == ResolvedFlowEffect.WAVE) {
                        item.waveAmplitudePx
                    } else {
                        0f
                    }
                    waveOffset = CommentFlowPath.verticalOffsetPx(
                        effect = item.flowEffect,
                        progress = CommentFlowPath.progress(
                            // A looping line waves through the same crossing each time.
                            elapsedMillis = if (item.endless) {
                                item.startTimeMillis +
                                    (elapsed - item.startTimeMillis)
                                        .mod(item.travelDurationMillis)
                            } else {
                                elapsed
                            },
                            startTimeMillis = item.startTimeMillis,
                            durationMillis = item.travelDurationMillis,
                        ),
                        amplitudePx = item.waveAmplitudePx,
                        cycles = item.waveCycles,
                    )
                } else {
                    waveBaseOffset = 0f
                    waveOffset = 0f
                }
                IntOffset(
                    x.roundToInt(),
                    (laneTopPx + waveBaseOffset + waveOffset).roundToInt(),
                )
            }
            .wrapContentWidth(align = Alignment.Start, unbounded = true)
            .onSizeChanged { commentWidth = it.width },
    ) {
        val backdrop = LocalCommentBackdropEnabled.current
        val fontFamily = LocalCommentFontFamily.current
        val commentAlpha = 1f - LocalCommentTransparency.current
        val fillStyle = playbackTextStyle(
            item,
            if (backdrop) colors.shadowFor(item.colorRole) else Color.Transparent,
            fontFamily,
            baseFontSize,
        )
        val textModifier = Modifier.padding(
            horizontal = COMMENT_CONTENT_HORIZONTAL_PADDING +
                GLYPH_SAFETY_HORIZONTAL_PADDING,
            vertical = COMMENT_CONTENT_VERTICAL_PADDING,
        )
        val maxLines = if (item.behavior == ResolvedPlaybackBehavior.FIXED) Int.MAX_VALUE else 1
        val softWrap = item.behavior == ResolvedPlaybackBehavior.FIXED
        val overflow = if (item.behavior == ResolvedPlaybackBehavior.FIXED) {
            TextOverflow.Clip
        } else {
            TextOverflow.Visible
        }
        Surface(
            modifier = Modifier
                .graphicsLayer { alpha = commentAlpha }
                .then(if (item.renderWidthPx > 0f) Modifier.width(surfaceWidth) else Modifier),
            color = if (backdrop) {
                colors.commentBackground.copy(
                    alpha = colors.commentBackground.alpha * item.opacity,
                )
            } else {
                Color.Transparent
            },
            shape = RoundedCornerShape(6.dp),
            shadowElevation = if (!backdrop) 0.dp else if (isStrong) 3.dp else 1.dp,
        ) {
            Box {
                if (!backdrop) {
                    // The cut-out edge: the same glyphs stroked underneath in the plate's old
                    // colour, so bare letters keep a rim between themselves and whatever is
                    // behind them — the way the video site letters its comments.
                    // The rim scales with the letters (the site strokes ~8% of the font)
                    // and stays translucent, so it reads as an edge rather than a border.
                    val strokeWidthPx = with(density) {
                        (fillStyle.fontSize.toPx() * COMMENT_EDGE_FONT_FRACTION)
                            .coerceAtLeast(COMMENT_CUTOUT_EDGE.toPx())
                    }
                    Text(
                        text = item.text,
                        modifier = textModifier,
                        color = colors.shadowFor(item.colorRole).copy(
                            alpha = COMMENT_EDGE_OPACITY * item.opacity,
                        ),
                        maxLines = maxLines,
                        softWrap = softWrap,
                        overflow = overflow,
                        style = fillStyle.copy(
                            shadow = null,
                            drawStyle = Stroke(
                                width = strokeWidthPx,
                                join = StrokeJoin.Round,
                                cap = StrokeCap.Round,
                            ),
                        ),
                    )
                }
                Text(
                    text = item.text,
                    modifier = textModifier,
                    color = colors.textFor(item.colorRole).copy(
                        alpha = item.opacity,
                    ),
                    maxLines = maxLines,
                    softWrap = softWrap,
                    overflow = overflow,
                    style = fillStyle,
                )
            }
        }
    }
}

/** Advances app playback only while PLAYING; a PAUSED effect consumes no time. */
@Composable
fun CommentPlaybackFrameClock(
    status: PlaybackStatus,
    onFrame: (Long) -> Unit,
) {
    LaunchedEffect(status) {
        if (status != PlaybackStatus.PLAYING) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        // Whole milliseconds go to the animation; the nanosecond remainder carries into the next
        // frame, so time is neither lost (a slow drift) nor delivered unevenly (a visible shake).
        var carryNanos = 0L
        while (true) {
            val frame = withFrameNanos { it }
            val deltaNanos = frame - previousFrame + carryNanos
            carryNanos = deltaNanos % NANOS_PER_MILLISECOND
            onFrame(deltaNanos / NANOS_PER_MILLISECOND)
            previousFrame = frame
        }
    }
}

/** 点滅の呼吸: a soft pulse between faint and full, about once a second. */
private fun blinkAlpha(elapsedInItemMillis: Long): Float {
    val phase = (elapsedInItemMillis.mod(BLINK_PERIOD_MILLIS)).toFloat() / BLINK_PERIOD_MILLIS
    val wave = (kotlin.math.sin(phase * 2.0 * Math.PI) * 0.5 + 0.5).toFloat()
    return BLINK_MIN_ALPHA + (1f - BLINK_MIN_ALPHA) * wave
}

private const val BLINK_PERIOD_MILLIS = 900L
private const val BLINK_MIN_ALPHA = 0.25f

private const val NANOS_PER_MILLISECOND = 1_000_000L

/** The size a comment's letters wear when no surface says otherwise. */
val COMMENT_BASE_FONT_SIZE = 20.sp

/**
 * The video site's proportion: a medium comment is lettered at 74px on its 1080px canvas,
 * so a stage that knows its height sizes the base font as height × this fraction. The
 * result is clamped to [STAGE_MINIMUM_BASE_FONT_SIZE, STAGE_MAXIMUM_BASE_FONT_SIZE] so a
 * sliver of a stage stays legible and a wall-sized one does not shout.
 */
const val STAGE_FONT_HEIGHT_FRACTION = 74f / 1080f
val STAGE_MINIMUM_BASE_FONT_SIZE = 14.sp
val STAGE_MAXIMUM_BASE_FONT_SIZE = 60.sp

/** How dark the room goes behind flying comments on a bright page. */
private const val COMMENT_FLIGHT_SCRIM_ALPHA = 0.62f
val COMMENT_WAVE_DESIRED_AMPLITUDE = 6.dp
private val COMMENT_CONTENT_HORIZONTAL_PADDING = 2.dp

/**
 * Stroke width of the cut-out edge drawn when the backdrop plate is off. The stroke straddles
 * the glyph outline, so half of this sticks out — 1.6dp keeps the rim inside the 2dp glyph
 * safety padding the measure already reserves.
 */
private val COMMENT_CUTOUT_EDGE = 1.6.dp
private const val COMMENT_EDGE_FONT_FRACTION = 0.085f
private const val COMMENT_EDGE_OPACITY = 0.55f
private val COMMENT_CONTENT_VERTICAL_PADDING = 1.dp
private val GLYPH_SAFETY_HORIZONTAL_PADDING = 2.dp
private val FIXED_COMMENT_HORIZONTAL_MARGIN = 16.dp

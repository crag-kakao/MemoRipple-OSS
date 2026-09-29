package io.github.cragcoffee.memoripple.ui.memos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.cragcoffee.memoripple.domain.comments.CommentAppearance
import io.github.cragcoffee.memoripple.domain.comments.CommentColorRole
import io.github.cragcoffee.memoripple.domain.comments.CommentEmphasisRole
import io.github.cragcoffee.memoripple.domain.comments.CommentFlowDirection
import io.github.cragcoffee.memoripple.domain.comments.CommentFlowEffect
import io.github.cragcoffee.memoripple.domain.comments.CommentMotion
import io.github.cragcoffee.memoripple.domain.comments.CommentMotionMode
import io.github.cragcoffee.memoripple.domain.comments.CommentPlacementRole
import io.github.cragcoffee.memoripple.domain.comments.CommentSizeRole
import io.github.cragcoffee.memoripple.domain.comments.CommentSpeedRole
import io.github.cragcoffee.memoripple.domain.playback.PlaybackEmphasis
import io.github.cragcoffee.memoripple.domain.playback.PlaybackItem
import io.github.cragcoffee.memoripple.domain.playback.ResolvedPlaybackBehavior
import io.github.cragcoffee.memoripple.ui.playback.inlineCommentRendererColors
import io.github.cragcoffee.memoripple.ui.playback.LocalCommentFontFamily
import io.github.cragcoffee.memoripple.ui.playback.playbackTextStyle
import io.github.cragcoffee.memoripple.ui.playback.stageCommentRendererColors
import io.github.cragcoffee.memoripple.ui.playback.textFor

internal enum class CommentExpressionVariant { MEMO, FUTURE }

internal fun CommentColorRole.displayName(): String = when (this) {
    CommentColorRole.DEFAULT -> "標準"
    CommentColorRole.RED -> "赤"
    CommentColorRole.BLUE -> "青"
    CommentColorRole.GREEN -> "緑"
    CommentColorRole.YELLOW -> "黄"
    CommentColorRole.CYAN -> "シアン"
    CommentColorRole.PINK -> "ピンク"
    CommentColorRole.ORANGE -> "オレンジ"
    CommentColorRole.PURPLE -> "紫"
    CommentColorRole.GRAY -> "灰"
    CommentColorRole.BLACK -> "黒"
}

internal fun CommentSizeRole.displayName(): String = when (this) {
    CommentSizeRole.SMALL -> "小さめ"
    CommentSizeRole.STANDARD -> "標準"
    CommentSizeRole.LARGE -> "大きめ"
}

internal fun CommentEmphasisRole.displayName(): String = when (this) {
    CommentEmphasisRole.NORMAL -> "通常"
    CommentEmphasisRole.STRONG -> "強調"
}

internal fun CommentSpeedRole.displayName(): String = when (this) {
    CommentSpeedRole.SLOW -> "ゆっくり"
    CommentSpeedRole.STANDARD -> "標準"
    CommentSpeedRole.FAST -> "速い"
}

internal fun CommentPlacementRole.displayName(): String = when (this) {
    CommentPlacementRole.AUTO -> "自動"
    CommentPlacementRole.TOP -> "上側"
    CommentPlacementRole.MIDDLE -> "中央"
    CommentPlacementRole.BOTTOM -> "下側"
}

internal fun CommentMotionMode.displayName(): String = when (this) {
    CommentMotionMode.FLOW -> "流れる"
    CommentMotionMode.FIXED_TOP -> "上に固定"
    CommentMotionMode.FIXED_BOTTOM -> "下に固定"
}

internal fun CommentFlowDirection.displayName(): String = when (this) {
    CommentFlowDirection.RIGHT_TO_LEFT -> "右から左"
    CommentFlowDirection.LEFT_TO_RIGHT -> "左から右"
}

internal fun CommentFlowEffect.displayName(): String = when (this) {
    CommentFlowEffect.STRAIGHT -> "直線"
    CommentFlowEffect.WAVE -> "波"
}

internal fun CommentAppearance.summary(): String = if (isDefault) {
    "標準"
} else {
    listOfNotNull(
        colorRole.displayName().takeUnless { colorRole == CommentColorRole.DEFAULT },
        sizeRole.displayName().takeUnless { sizeRole == CommentSizeRole.STANDARD },
        emphasisRole.displayName().takeUnless { emphasisRole == CommentEmphasisRole.NORMAL },
    ).joinToString("・")
}

internal fun commentExpressionSummary(
    appearance: CommentAppearance,
    motion: CommentMotion,
): String {
    val parts = buildList {
        if (appearance.colorRole != CommentColorRole.DEFAULT) {
            add(appearance.colorRole.displayName())
        }
        if (appearance.sizeRole != CommentSizeRole.STANDARD) {
            add(appearance.sizeRole.displayName())
        }
        if (appearance.emphasisRole != CommentEmphasisRole.NORMAL) {
            add(appearance.emphasisRole.displayName())
        }
        if (motion.mode != CommentMotionMode.FLOW) {
            add(motion.mode.displayName())
        } else {
            if (motion.speedRole != CommentSpeedRole.STANDARD) {
                add(motion.speedRole.displayName())
            }
            if (motion.placementRole != CommentPlacementRole.AUTO) {
                add(motion.placementRole.displayName())
            }
            if (motion.direction != CommentFlowDirection.RIGHT_TO_LEFT) {
                add(motion.direction.displayName())
            }
            if (motion.flowEffect != CommentFlowEffect.STRAIGHT) {
                add(motion.flowEffect.displayName())
            }
        }
    }
    return parts.ifEmpty { listOf("標準") }.joinToString("・")
}

internal fun CommentAppearance.Companion.fromEntity(
    color: String,
    size: String,
    emphasis: String,
): CommentAppearance = CommentAppearance(
    colorRole = CommentColorRole.fromStorageId(color),
    sizeRole = CommentSizeRole.fromStorageId(size),
    emphasisRole = CommentEmphasisRole.fromStorageId(emphasis),
)

internal fun CommentMotion.Companion.fromEntity(
    speed: String,
    placement: String,
    mode: String = "flow",
    direction: String = "rtl",
    effect: String = "straight",
): CommentMotion = CommentMotion(
    mode = CommentMotionMode.fromStorageId(mode),
    speedRole = CommentSpeedRole.fromStorageId(speed),
    placementRole = CommentPlacementRole.fromStorageId(placement),
    direction = CommentFlowDirection.fromStorageId(direction),
    flowEffect = CommentFlowEffect.fromStorageId(effect),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CommentExpressionSheet(
    initialAppearance: CommentAppearance,
    initialMotion: CommentMotion,
    previewText: String,
    variant: CommentExpressionVariant = CommentExpressionVariant.MEMO,
    keepExpression: Boolean? = null,
    onKeepExpressionChange: (Boolean) -> Unit = {},
    onDismiss: () -> Unit,
    onConfirm: (CommentAppearance, CommentMotion) -> Unit,
) {
    var appearance by remember(initialAppearance) { mutableStateOf(initialAppearance) }
    var motion by remember(initialMotion) { mutableStateOf(initialMotion) }
    val colors = if (variant == CommentExpressionVariant.FUTURE) {
        stageCommentRendererColors()
    } else {
        inlineCommentRendererColors()
    }
    val previewItem = remember(appearance, motion.mode) {
        PlaybackItem(
            id = 0,
            text = previewText.ifBlank { "ここ好き！" },
            startTimeMillis = 0,
            travelDurationMillis = 1,
            laneIndex = 0,
            fontScale = appearance.sizeRole.scaleMultiplier,
            opacity = 1f,
            emphasis = if (appearance.emphasisRole == CommentEmphasisRole.STRONG) {
                PlaybackEmphasis.STRONG
            } else {
                PlaybackEmphasis.NORMAL
            },
            colorRole = appearance.colorRole,
            behavior = if (motion.mode == CommentMotionMode.FLOW) {
                ResolvedPlaybackBehavior.FLOW
            } else {
                ResolvedPlaybackBehavior.FIXED
            },
        )
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("comment_appearance_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ProductSize.screenHorizontalPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(ProductSpacing.md),
        ) {
            Text(
                if (variant == CommentExpressionVariant.FUTURE) {
                    "未来コメントの表現"
                } else {
                    "コメントの表現"
                },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("comment_appearance_preview"),
                color = if (variant == CommentExpressionVariant.FUTURE) {
                    Color.Black
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(modifier = Modifier.fillMaxWidth().height(64.dp)) {
                        Text(
                            text = previewItem.text,
                            color = colors.textFor(appearance.colorRole),
                            style = playbackTextStyle(
                                previewItem,
                                colors.shadow,
                                LocalCommentFontFamily.current,
                            ),
                            maxLines = if (motion.mode == CommentMotionMode.FLOW) 1 else Int.MAX_VALUE,
                            softWrap = motion.mode != CommentMotionMode.FLOW,
                            modifier = Modifier.align(
                                when (motion.mode) {
                                    CommentMotionMode.FLOW -> Alignment.CenterStart
                                    CommentMotionMode.FIXED_TOP -> Alignment.TopCenter
                                    CommentMotionMode.FIXED_BOTTOM -> Alignment.BottomCenter
                                },
                            ),
                        )
                    }
                    Text(
                        text = if (
                            variant == CommentExpressionVariant.MEMO &&
                            motion.mode == CommentMotionMode.FLOW
                        ) {
                            listOf(
                                motion.speedRole.displayName(),
                                motion.placementRole.displayName(),
                                motion.direction.displayName(),
                                motion.flowEffect.displayName(),
                            ).joinToString("・")
                        } else {
                            motion.mode.displayName()
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("comment_motion_preview_label"),
                    )
                }
            }
            ExpressionRow("色") {
                CommentColorRole.entries.forEach { role ->
                    ExpressionSwatch(
                        selected = appearance.colorRole == role,
                        onClick = { appearance = appearance.copy(colorRole = role) },
                        swatch = colors.textFor(role),
                        description = "色 ${role.displayName()}",
                        testTag = "appearance_color_${role.storageId}",
                    )
                }
            }
            ExpressionRow("大きさ") {
                CommentSizeRole.entries.forEach { role ->
                    ExpressionOption(
                        selected = appearance.sizeRole == role,
                        onClick = { appearance = appearance.copy(sizeRole = role) },
                        description = "大きさ ${role.displayName()}",
                        testTag = "appearance_size_${role.storageId}",
                    ) {
                        // The letter is drawn at the size it stands for, so the row can be read
                        // without reading it.
                        Text("A", fontSize = (BASE_SAMPLE_SP * role.scaleMultiplier).sp)
                    }
                }
            }
            ExpressionRow("強調") {
                CommentEmphasisRole.entries.forEach { role ->
                    ExpressionOption(
                        selected = appearance.emphasisRole == role,
                        onClick = { appearance = appearance.copy(emphasisRole = role) },
                        description = "強調 ${role.displayName()}",
                        testTag = "appearance_emphasis_${role.storageId}",
                    ) { Text(role.displayName(), style = MaterialTheme.typography.labelMedium) }
                }
            }
            ExpressionRow("表示") {
                CommentMotionMode.entries.forEach { mode ->
                    ExpressionOption(
                        selected = motion.mode == mode,
                        onClick = { motion = motion.copy(mode = mode) },
                        description = "表示方法 ${mode.displayName()}",
                        testTag = "motion_mode_${mode.storageId}",
                    ) { Text(mode.displayName(), style = MaterialTheme.typography.labelMedium) }
                }
            }
            if (motion.mode == CommentMotionMode.FLOW &&
                variant == CommentExpressionVariant.MEMO
            ) {
                ExpressionRow("速さ") {
                    CommentSpeedRole.entries.forEach { role ->
                        ExpressionOption(
                            selected = motion.speedRole == role,
                            onClick = { motion = motion.copy(speedRole = role) },
                            description = "流れる速さ ${role.displayName()}",
                            testTag = "motion_speed_${role.storageId}",
                        ) { Text(role.displayName(), style = MaterialTheme.typography.labelMedium) }
                    }
                }
                ExpressionRow("位置") {
                    CommentPlacementRole.entries.forEach { role ->
                        ExpressionOption(
                            selected = motion.placementRole == role,
                            onClick = { motion = motion.copy(placementRole = role) },
                            description = "流れる位置 ${role.displayName()}",
                            testTag = "motion_placement_${role.storageId}",
                        ) { Text(role.displayName(), style = MaterialTheme.typography.labelMedium) }
                    }
                }
                ExpressionRow("方向") {
                    CommentFlowDirection.entries.forEach { direction ->
                        ExpressionOption(
                            selected = motion.direction == direction,
                            onClick = { motion = motion.copy(direction = direction) },
                            description = "方向 ${direction.displayName()}",
                            testTag = "motion_direction_${direction.storageId}",
                        ) {
                            Text(direction.displayName(), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                ExpressionRow("動き") {
                    CommentFlowEffect.entries.forEach { effect ->
                        ExpressionOption(
                            selected = motion.flowEffect == effect,
                            onClick = { motion = motion.copy(flowEffect = effect) },
                            description = "動き ${effect.displayName()}",
                            testTag = "motion_effect_${effect.storageId}",
                        ) { Text(effect.displayName(), style = MaterialTheme.typography.labelMedium) }
                    }
                }
            } else if (variant == CommentExpressionVariant.MEMO) {
                Text(
                    "固定表示では流れる速さ・位置・方向・動きは使用しません。流れる表示へ戻すと以前の設定が復元されます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The composer's sheet also carries the site's own pair: keep what was set, or put
            // it all back. Editing an existing comment offers neither — those are about the
            // next comment, not this one.
            if (keepExpression != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "設定内容を保持する",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = keepExpression,
                        onCheckedChange = onKeepExpressionChange,
                        modifier = Modifier.testTag("keep_comment_expression"),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (keepExpression != null) {
                    TextButton(
                        onClick = {
                            appearance = CommentAppearance.Default
                            motion = CommentMotion.Default
                        },
                        modifier = Modifier.testTag("reset_comment_expression"),
                    ) { Text("リセット") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("キャンセル") }
                TextButton(
                    onClick = { onConfirm(appearance, motion) },
                    modifier = Modifier.testTag("confirm_comment_appearance"),
                ) { Text("決定") }
            }
        }
    }
}

/**
 * One thing that can be chosen about a comment, and the choices for it.
 *
 * A label on the left and the options beside it, the way the palette on a video site puts them.
 * Every choice used to be a full-height chip under its own heading, which turned seven decisions
 * into a page that had to be scrolled to be seen. Here the whole expression fits in one look.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpressionRow(label: String, options: @Composable FlowRowScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(EXPRESSION_LABEL_WIDTH)
                .padding(top = ROW_LABEL_TOP_PADDING),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ProductSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(ProductSpacing.sm),
            content = options,
        )
    }
}

/**
 * One choice.
 *
 * Drawn small and reached at 48dp: a selectable [Surface] stretches its touch target past the box
 * it paints, so the row can stay compact without becoming hard to hit.
 */
@Composable
private fun ExpressionOption(
    selected: Boolean,
    onClick: () -> Unit,
    description: String,
    testTag: String,
    content: @Composable () -> Unit,
) {
    Surface(
        selected = selected,
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier
            .heightIn(min = EXPRESSION_OPTION_HEIGHT)
            .semantics { contentDescription = description }
            .testTag(testTag),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = ProductSpacing.md, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** A colour, shown as itself. A word for a colour is slower to read than the colour. */
@Composable
internal fun ExpressionSwatch(
    selected: Boolean,
    onClick: () -> Unit,
    swatch: Color,
    description: String,
    testTag: String,
) {
    Surface(
        selected = selected,
        onClick = onClick,
        shape = CircleShape,
        color = swatch,
        border = BorderStroke(
            if (selected) 3.dp else 1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier
            .size(EXPRESSION_SWATCH_SIZE)
            .semantics { contentDescription = description }
            .testTag(testTag),
    ) {}
}

private val EXPRESSION_LABEL_WIDTH = 52.dp
private val EXPRESSION_OPTION_HEIGHT = 34.dp
private val EXPRESSION_SWATCH_SIZE = 34.dp
private val ROW_LABEL_TOP_PADDING = 9.dp
private const val BASE_SAMPLE_SP = 15f

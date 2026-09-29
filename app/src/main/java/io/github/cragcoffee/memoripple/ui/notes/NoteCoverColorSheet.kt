package io.github.cragcoffee.memoripple.ui.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.domain.notes.NoteCoverColor
import io.github.cragcoffee.memoripple.domain.notes.NoteCoverPaint
import io.github.cragcoffee.memoripple.domain.notes.NoteCoverPalette
import io.github.cragcoffee.memoripple.ui.components.ProductSheetHeader
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing

/**
 * A note's cover: the colour it is found by, and the picture on top of it.
 *
 * Colours are shown as covers rather than as names, at the size and shape they are drawn at
 * everywhere else. A word for a colour is slower to read than the colour, and the point of
 * picking one is to know the note at a glance in a list.
 *
 * The presets come first. Under them are マイカラー, the colours the writer has kept, then three
 * sliders for making a new one. The picture is last: it sits on top of whatever colour is
 * chosen, and the colour is what shows if the picture is ever gone. The same sheet is reached
 * from the shelf and from the note's own page, so there is one place to learn.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun NoteCoverColorSheet(
    selected: NoteCoverPaint,
    onPick: (NoteCoverPaint) -> Unit,
    onDismiss: () -> Unit,
    hasPhoto: Boolean,
    onPickPhoto: () -> Unit,
    onClearPhoto: () -> Unit,
    myColors: List<Int>,
    onSaveMyColor: (Int) -> Unit,
    onRemoveMyColor: (Int) -> Unit,
) {
    // The sliders open on the colour the cover already wears when it is the writer's own, so a
    // small change is a small move; otherwise on a colour that is not any of the presets.
    val opening = remember(selected) {
        (selected as? NoteCoverPaint.Custom)?.let { NoteCoverPaint.toHsl(it.argb) }
            ?: floatArrayOf(210f, 0.45f, 0.45f)
    }
    var hue by remember(opening) { mutableFloatStateOf(opening[0]) }
    var saturation by remember(opening) { mutableFloatStateOf(opening[1] * 100f) }
    var lightness by remember(opening) { mutableFloatStateOf(opening[2] * 100f) }
    val own = NoteCoverPaint.Custom(NoteCoverPaint.fromHsl(hue, saturation / 100f, lightness / 100f))

    // Tall enough that half of it is never the useful half: it opens at its full height.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("note_cover_color_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ProductSize.screenHorizontalPadding),
        ) {
            ProductSheetHeader(title = "表紙", onDismiss = onDismiss)
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = ProductSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(ProductSpacing.md),
                verticalArrangement = Arrangement.spacedBy(ProductSpacing.md),
            ) {
                NoteCoverColor.entries.forEach { color ->
                    val paint = NoteCoverPaint.Preset(color)
                    Swatch(
                        paint = paint,
                        selected = paint == selected,
                        description = "表紙の色 ${color.storageId}",
                        onClick = { onPick(paint) },
                        modifier = Modifier.testTag("note_cover_${color.storageId}"),
                    )
                }
            }

            SectionLabel("マイカラー")
            if (myColors.isEmpty()) {
                Text(
                    "下で作った色を保存すると、ここに並びます（${NoteCoverPalette.MAXIMUM}色まで）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = ProductSpacing.xs),
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = ProductSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(ProductSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(ProductSpacing.md),
                ) {
                    myColors.forEachIndexed { index, argb ->
                        val paint = NoteCoverPaint.Custom(argb)
                        Swatch(
                            paint = paint,
                            selected = paint == selected,
                            description = "マイカラー ${index + 1} 長押しで削除",
                            onClick = { onPick(paint) },
                            onLongClick = { onRemoveMyColor(paint.argb) },
                            modifier = Modifier.testTag("note_cover_my_$index"),
                        )
                    }
                }
                Text(
                    "長押しで一覧から外せます",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(ProductSpacing.lg))
            SectionLabel("好きな色を作る")
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ProductSpacing.md),
            ) {
                NoteCover(
                    paint = own,
                    width = 88.dp,
                    modifier = Modifier.testTag("note_cover_custom_preview"),
                )
                Column(modifier = Modifier.weight(1f)) {
                    LabelledSlider("色相", hue, 0f..360f, "note_cover_hue") { hue = it }
                    LabelledSlider("鮮やかさ", saturation, 0f..100f, "note_cover_saturation") {
                        saturation = it
                    }
                    LabelledSlider("明るさ", lightness, 0f..100f, "note_cover_lightness") {
                        lightness = it
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(ProductSpacing.sm),
            ) {
                FilledTonalButton(
                    onClick = { onPick(own) },
                    modifier = Modifier.testTag("note_cover_custom_apply"),
                ) { Text("この色にする") }
                TextButton(
                    onClick = { onSaveMyColor(own.argb) },
                    modifier = Modifier.testTag("note_cover_custom_save"),
                ) { Text("マイカラーに保存") }
            }

            Spacer(Modifier.height(ProductSpacing.lg))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(
                onClick = onPickPhoto,
                modifier = Modifier.heightIn(min = ProductSize.minimumTouchTarget)
                    .padding(top = ProductSpacing.xs)
                    .testTag("note_cover_pick_photo"),
            ) {
                Icon(
                    Icons.Outlined.AddPhotoAlternate,
                    contentDescription = null,
                    modifier = Modifier.padding(end = ProductSpacing.sm),
                )
                Text(if (hasPhoto) "写真を選び直す" else "写真を置く")
            }
            if (hasPhoto) {
                TextButton(
                    onClick = onClearPhoto,
                    modifier = Modifier.heightIn(min = ProductSize.minimumTouchTarget)
                        .padding(bottom = ProductSpacing.lg)
                        .testTag("note_cover_clear_photo"),
                ) {
                    Text("写真を外す", color = MaterialTheme.colorScheme.error)
                }
            } else {
                Spacer(Modifier.height(ProductSpacing.lg))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Swatch(
    paint: NoteCoverPaint,
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.small
    val border = BorderStroke(
        if (selected) 3.dp else 1.dp,
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
    )
    if (onLongClick == null) {
        Surface(
            selected = selected,
            onClick = onClick,
            shape = shape,
            border = border,
            modifier = modifier.semantics { contentDescription = description },
        ) {
            NoteCover(paint = paint, width = 88.dp)
        }
    } else {
        Box(
            modifier = modifier
                .clip(shape)
                .border(border, shape)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .semantics { contentDescription = description },
        ) {
            NoteCover(paint = paint, width = 88.dp)
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    tag: String,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(52.dp),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f)
                .semantics { contentDescription = label }
                .testTag(tag),
        )
    }
}

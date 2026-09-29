package io.github.cragcoffee.memoripple.ui.memos

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing

/**
 * The one disclosure for the overlay permission, wherever it is asked for — the editor's ▶ and
 * 設定's switch alike. It says what the permission is for and what MemoRipple does not do, then
 * shows, in miniature, what Android will show next: since Android 11 the system opens the whole
 * list of apps rather than this app's own page, so the reader has to find MemoRipple in it and
 * turn its switch on. The rehearsal pulses on the row they will be looking for.
 */
@Composable
internal fun OverlayPermissionDisclosureDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    openLabel: String = "設定を開く",
    dialogTag: String = "overlay_permission_disclosure",
    openTag: String = "open_overlay_permission_settings",
    cancelTag: String = "cancel_overlay_disclosure",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("他のアプリの上にコメントを表示") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ProductSpacing.md)) {
                Text(
                    "MemoRippleに保存したコメントを、他のAndroidアプリを使用している間も" +
                        "画面上へ表示できます。\n\nこの機能にはAndroidの「他のアプリの上に表示」" +
                        "権限が必要です。\n\nMemoRippleは、表示中の他アプリの内容を" +
                        "読み取ったり記録したりしません。",
                )
                Text(
                    "「設定を開く」で表示されるアプリの一覧から MemoRipple を探し、スイッチをオンにしてください。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OverlayPermissionTutorial()
            }
        },
        confirmButton = {
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag(openTag),
            ) { Text(openLabel) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(cancelTag),
            ) { Text("キャンセル") }
        },
        modifier = Modifier.testTag(dialogTag),
    )
}

/**
 * Android's app list in miniature: three quiet rows, and the MemoRipple row breathing between
 * faint and full while its switch slides on — the one gesture the reader is about to make.
 */
@Composable
internal fun OverlayPermissionTutorial(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "overlay-tutorial")
    // 点滅: the row the reader must find, breathing about once a second.
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    // The switch slides on, holds, and starts again — what the finger will do.
    val knob by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing), RepeatMode.Restart),
        label = "knob",
    )
    val onFraction = ((knob - 0.35f) / 0.3f).coerceIn(0f, 1f)
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceVariant.copy(alpha = 0.6f))
            .padding(ProductSpacing.sm)
            .testTag("overlay_permission_tutorial"),
        verticalArrangement = Arrangement.spacedBy(ProductSpacing.xs),
    ) {
        TutorialRow(label = "ほかのアプリ", emphasis = 0f, switchOn = 0f, colors = colors)
        TutorialRow(label = "MemoRipple", emphasis = pulse, switchOn = onFraction, colors = colors)
        TutorialRow(label = "ほかのアプリ", emphasis = 0f, switchOn = 0f, colors = colors)
    }
}

@Composable
private fun TutorialRow(
    label: String,
    emphasis: Float,
    switchOn: Float,
    colors: androidx.compose.material3.ColorScheme,
) {
    val highlighted = emphasis > 0f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlighted) colors.primary.copy(alpha = 0.10f + 0.18f * emphasis) else colors.surface)
            .padding(horizontal = ProductSpacing.sm, vertical = ProductSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).clip(CircleShape)
                .background(if (highlighted) colors.primary else colors.outlineVariant)
                .alpha(if (highlighted) emphasis else 1f),
        )
        Spacer(Modifier.width(ProductSpacing.sm))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (highlighted) colors.onSurface else colors.onSurfaceVariant,
            modifier = Modifier.weight(1f).alpha(if (highlighted) 0.6f + 0.4f * emphasis else 1f),
        )
        // A switch drawn by hand so its knob can ride the tutorial's clock.
        val trackWidth = 34.dp
        val knobSize = 14.dp
        Box(
            Modifier.width(trackWidth).height(20.dp).clip(RoundedCornerShape(10.dp))
                .background(
                    if (highlighted) androidx.compose.ui.graphics.lerp(colors.outlineVariant, colors.primary, switchOn)
                    else colors.outlineVariant,
                ),
        ) {
            Box(
                Modifier
                    .padding(3.dp)
                    .size(knobSize)
                    .graphicsLayer { translationX = (trackWidth - knobSize - 6.dp).toPx() * (if (highlighted) switchOn else 0f) }
                    .clip(CircleShape)
                    .background(colors.surface),
            )
        }
    }
}

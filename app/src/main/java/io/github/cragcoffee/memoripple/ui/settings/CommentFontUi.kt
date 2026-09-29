package io.github.cragcoffee.memoripple.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.data.CommentFontStore
import io.github.cragcoffee.memoripple.domain.settings.CommentFont
import io.github.cragcoffee.memoripple.domain.settings.CommentFontSelection
import io.github.cragcoffee.memoripple.domain.settings.UserCommentFont
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.playback.commentFontFamily
import io.github.cragcoffee.memoripple.ui.playback.rememberCommentFontFamily

/**
 * コメントのフォント: the three bundled faces are permanent residents — they live in the
 * APK, so "deleting" one saved nothing and the button only misled (撤去 2026-09-04, by
 * the owner). Only the user's own added fonts carry a delete, because that genuinely
 * frees the copied file. Any font file the user owns can join, drawn in its own letters
 * so the choice is made by looking; deletion asks once first, and a deleted face that
 * was in use hands the comments back to the default.
 */
@Composable
internal fun CommentFontDialog(
    selectedId: String,
    userFonts: List<UserCommentFont>,
    store: CommentFontStore,
    builtInLabel: (CommentFont) -> String,
    onSelect: (String) -> Unit,
    onDeleteUserFont: (UserCommentFont) -> Unit,
    onAddFont: () -> Unit,
    onDismiss: () -> Unit,
) {
    var deleting by remember { mutableStateOf<UserCommentFont?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("コメントのフォント") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CommentFont.entries.forEach { font ->
                    FontRow(
                        label = builtInLabel(font),
                        fontFamily = font.commentFontFamily(),
                        selected = selectedId == font.storageId,
                        onClick = { onSelect(font.storageId) },
                        rowTag = "comment_font_builtin_${font.storageId}",
                        trailing = null,
                    )
                }
                userFonts.forEach { font ->
                    val family = rememberCommentFontFamily(
                        CommentFontSelection.userStorageId(font),
                        userFonts,
                        store,
                    )
                    FontRow(
                        label = font.name,
                        fontFamily = family,
                        selected = selectedId == CommentFontSelection.userStorageId(font),
                        onClick = { onSelect(CommentFontSelection.userStorageId(font)) },
                        rowTag = "comment_font_user_${font.id}",
                        trailing = {
                            IconButton(
                                onClick = { deleting = font },
                                modifier = Modifier
                                    .size(ProductSize.minimumTouchTarget)
                                    .testTag("comment_font_delete_${font.id}"),
                            ) {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    contentDescription = "${font.name}を削除",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ProductSize.minimumTouchTarget)
                        .clickable(onClick = onAddFont)
                        .testTag("comment_font_add"),
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = null,
                        modifier = Modifier.padding(horizontal = ProductSpacing.sm).size(20.dp),
                    )
                    Text("フォントを追加", style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    "端末のフォントファイル（ttf / otf）を選ぶと、この一覧から使えます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = ProductSpacing.sm),
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
        modifier = Modifier.testTag("comment_font_dialog"),
    )

    deleting?.let { font ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("フォントを削除") },
            text = {
                Text(
                    "「${font.name}」を削除しますか？\n" +
                        "使用中の場合はデフォルトのフォントに戻ります。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteUserFont(font)
                        deleting = null
                    },
                    modifier = Modifier.testTag("comment_font_delete_confirm"),
                ) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun FontRow(
    label: String,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    selected: Boolean,
    onClick: () -> Unit,
    rowTag: String,
    trailing: (@Composable () -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ProductSize.minimumTouchTarget)
            .clickable(onClick = onClick)
            .testTag(rowTag),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = fontFamily),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

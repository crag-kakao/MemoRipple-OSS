package io.github.cragcoffee.memoripple.ui.memos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.memos.displayTitle
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing

/**
 * What can be done to one memo, reached by holding it.
 *
 * Holding a memo asks about that memo, so the sheet answers about it. Picking several is one of the
 * answers rather than the only one: a memo that is already the subject should not have to be
 * selected before anything can happen to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoActionSheet(
    memo: MemoEntity,
    canCopyLink: Boolean,
    canMakeEpisodes: Boolean,
    onPinnedChange: (Boolean) -> Unit,
    canAddToNote: Boolean = true,
    canSelect: Boolean = true,
    selectLabel: String = "メモを選択",
    onMoveToFolder: () -> Unit = {},
    onAddTags: () -> Unit,
    onDuplicate: () -> Unit,
    onAddToNote: () -> Unit,
    onMakeEpisodes: () -> Unit,
    onSelect: () -> Unit,
    onCopyLink: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // The sheet opens whole: ゴミ箱に移動する is one of its answers, and an answer
        // that has to be dragged into view reads as not being there at all.
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        ),
        modifier = Modifier.testTag("memo_action_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(bottom = ProductSpacing.lg),
        ) {
            Text(
                memo.displayTitle(),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    start = SHEET_HORIZONTAL,
                    end = SHEET_HORIZONTAL,
                    bottom = ProductSpacing.sm,
                ),
            )
            MemoAction(
                label = if (memo.isPinned) "固定を解除" else "上部に固定",
                icon = Icons.Outlined.PushPin,
                testTag = "memo_pin_${memo.id}",
                onClick = { onPinnedChange(!memo.isPinned) },
            )
            MemoAction(
                label = "タグを付ける",
                icon = Icons.AutoMirrored.Outlined.Label,
                testTag = "memo_tag_${memo.id}",
                onClick = onAddTags,
            )
            SheetDivider()
            MemoAction(
                label = "複製を作成",
                icon = Icons.Outlined.ContentCopy,
                testTag = "memo_duplicate_${memo.id}",
                onClick = onDuplicate,
            )
            MemoAction(
                label = "フォルダへ移動",
                icon = Icons.Outlined.DriveFileMove,
                testTag = "memo_move_folder_${memo.id}",
                onClick = onMoveToFolder,
            )
            if (canAddToNote) {
                MemoAction(
                    label = "ノートに追加",
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    testTag = "memo_add_to_note_${memo.id}",
                    onClick = onAddToNote,
                )
            }
            // Offered only where there is a heading to make an episode out of. A memo with none
            // would answer with an empty note, which is not an answer.
            if (canMakeEpisodes) {
                MemoAction(
                    label = "見出しから話を作る",
                    icon = Icons.Outlined.AutoStories,
                    testTag = "memo_make_episodes_${memo.id}",
                    onClick = onMakeEpisodes,
                )
            }
            if (canSelect) {
                MemoAction(
                    label = selectLabel,
                    icon = Icons.Outlined.CheckCircle,
                    testTag = "memo_select_${memo.id}",
                    onClick = onSelect,
                )
            }
            MemoAction(
                label = "このメモへのリンクをコピー",
                icon = Icons.Outlined.Link,
                testTag = "memo_copy_link_${memo.id}",
                enabled = canCopyLink,
                // A link points at a title, so a memo without one cannot be pointed at.
                hint = if (canCopyLink) null else "タイトルを付けるとリンクできます",
                onClick = onCopyLink,
            )
            SheetDivider()
            MemoAction(
                label = "アーカイブ",
                icon = Icons.Outlined.Archive,
                testTag = "memo_archive_${memo.id}",
                onClick = onArchive,
            )
            MemoAction(
                label = "ゴミ箱に移動する",
                icon = Icons.Outlined.Delete,
                testTag = "memo_trash_${memo.id}",
                tint = MaterialTheme.colorScheme.error,
                onClick = onTrash,
            )
        }
    }
}

@Composable
private fun MemoAction(
    label: String,
    icon: ImageVector,
    testTag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    hint: String? = null,
    tint: Color = Color.Unspecified,
) {
    val content = when {
        !enabled -> MaterialTheme.colorScheme.outline
        tint != Color.Unspecified -> tint
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ProductSize.minimumTouchTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = SHEET_HORIZONTAL, vertical = ProductSpacing.sm)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ProductSpacing.lg),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = content)
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = content)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SheetDivider() {
    Spacer(Modifier.height(ProductSpacing.xs))
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = SHEET_HORIZONTAL),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    Spacer(Modifier.height(ProductSpacing.xs))
}

private val SHEET_HORIZONTAL = 24.dp

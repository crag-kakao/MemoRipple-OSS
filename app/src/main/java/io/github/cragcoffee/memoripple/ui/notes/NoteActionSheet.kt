package io.github.cragcoffee.memoripple.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.data.NoteSummary
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing

/**
 * What can be done to one note, reached by holding it.
 *
 * The same way a memo is reached, so the shelf and the wall answer to the same gesture. What is
 * here is what can be said about a note without opening it: what it is called, what it says under
 * that, and whether it should exist. The cover is not here — it is looked at on the note's own
 * page, at the size it is actually drawn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteActionSheet(
    note: NoteSummary,
    onRename: () -> Unit,
    onEditSubtitle: () -> Unit,
    onEditCover: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: () -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("note_action_sheet")) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = ProductSpacing.lg)) {
            Text(
                note.title.ifBlank { "無題のノート" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    start = SHEET_HORIZONTAL,
                    end = SHEET_HORIZONTAL,
                    bottom = ProductSpacing.sm,
                ),
            )
            NoteAction(
                label = "名前を変える",
                icon = Icons.Outlined.TextFields,
                testTag = "note_sheet_rename_${note.id}",
                onClick = onRename,
            )
            NoteAction(
                label = if (note.subtitle.isBlank()) "サブタイトルを付ける" else "サブタイトルを変える",
                icon = Icons.Outlined.Subtitles,
                testTag = "note_sheet_subtitle_${note.id}",
                onClick = onEditSubtitle,
            )
            NoteAction(
                label = "表紙",
                icon = Icons.Outlined.Palette,
                testTag = "note_sheet_cover_${note.id}",
                onClick = onEditCover,
            )
            NoteAction(
                label = "ノートを選択",
                icon = Icons.Outlined.CheckCircle,
                testTag = "note_sheet_select_${note.id}",
                onClick = onSelect,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = ProductSpacing.sm),
            )
            NoteAction(
                label = "ノートを削除",
                icon = Icons.Outlined.Delete,
                testTag = "note_sheet_delete_${note.id}",
                destructive = true,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun NoteAction(
    label: String,
    icon: ImageVector,
    testTag: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val content = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ProductSize.minimumTouchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = SHEET_HORIZONTAL, vertical = ProductSpacing.sm)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ProductSpacing.lg),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = content)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = content)
    }
}

private val SHEET_HORIZONTAL = 24.dp

package io.github.cragcoffee.memoripple.ui.notes

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.data.NoteSummary
import io.github.cragcoffee.memoripple.domain.notes.NoteCoverPaint
import io.github.cragcoffee.memoripple.ui.components.ProductSheetHeader
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing

/**
 * Which note something joins. It goes to the end, where the next one would go.
 *
 * The empty line is passed in rather than written here, because what a new note would start with
 * differs by what opened this: one memo becomes the first episode, an outline becomes several.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotePickerSheet(
    notes: List<NoteSummary>,
    onPick: (Long) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
    title: String = "ノートに追加",
    emptyMessage: String = "まだノートがありません。作ると、このメモが最初の話になります。",
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("note_picker_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = ProductSize.screenHorizontalPadding),
        ) {
            ProductSheetHeader(title = title, onDismiss = onDismiss)
            if (notes.isEmpty()) {
                Text(
                    emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = ProductSpacing.xs),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .weight(1f, fill = false)
                        .padding(top = ProductSpacing.sm),
                ) {
                    items(notes, key = NoteSummary::id) { note ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = ProductSize.minimumTouchTarget)
                                .clickable { onPick(note.id) }
                                .padding(vertical = ProductSpacing.xs)
                                .testTag("note_pick_${note.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ProductSpacing.md),
                        ) {
                            NoteCover(
                                paint = NoteCoverPaint.fromStorageId(note.coverColor),
                                width = 64.dp,
                            )
                            Text(
                                note.title.ifBlank { "無題のノート" },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = ProductSize.minimumTouchTarget)
                    .clickable(onClick = onCreate)
                    .padding(vertical = ProductSpacing.sm)
                    .testTag("note_pick_new"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ProductSpacing.md),
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "新しいノートを作る",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(ProductSpacing.lg))
        }
    }
}

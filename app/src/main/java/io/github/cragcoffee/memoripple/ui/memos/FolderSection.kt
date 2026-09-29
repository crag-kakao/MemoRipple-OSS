package io.github.cragcoffee.memoripple.ui.memos

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.data.FolderEntity
import io.github.cragcoffee.memoripple.data.toNode
import io.github.cragcoffee.memoripple.domain.folders.FolderName
import io.github.cragcoffee.memoripple.domain.folders.FolderNode
import io.github.cragcoffee.memoripple.domain.folders.FolderRow
import io.github.cragcoffee.memoripple.domain.folders.FolderTree
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import java.text.Collator
import java.util.Locale

/**
 * フォルダ on a page: where the page stands (the breadcrumb) and the folders inside that place.
 * One tree serves the memo page and the outliner page; each page shows its own kind of
 * document under the same folders. The outline *inside* a document is body text and is not
 * a folder — nothing here reads a body.
 */
internal data class FolderSectionState(
    val path: List<FolderEntity>,
    val children: List<FolderEntity>,
    val onGoTo: (Long?) -> Unit,
    val onOpen: (Long) -> Unit,
    val onHold: (FolderEntity) -> Unit,
) {
    val insideFolder: Boolean get() = path.isNotEmpty()
}

/** すべて › 開発 › Android › current — every step but the last is a way back. */
@Composable
internal fun FolderBreadcrumb(path: List<FolderEntity>, onGoTo: (Long?) -> Unit) {
    val scroll = rememberScrollState()
    // A deep trail overflows the width; the end of it is where the reader stands, so show that.
    LaunchedEffect(path) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(vertical = ProductSpacing.xs)
            .testTag("folder_breadcrumb"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CrumbStep(label = "すべて", description = "すべてのフォルダへ戻る", onClick = { onGoTo(null) }, testTag = "folder_crumb_root")
        path.dropLast(1).forEach { ancestor ->
            CrumbSeparator()
            CrumbStep(
                label = ancestor.name,
                description = "${ancestor.name}へ戻る",
                onClick = { onGoTo(ancestor.id) },
                testTag = "folder_crumb_${ancestor.id}",
            )
        }
        path.lastOrNull()?.let { current ->
            CrumbSeparator()
            Text(
                current.name,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = ProductSpacing.xs).testTag("folder_crumb_current"),
            )
        }
    }
}

@Composable
private fun CrumbStep(label: String, description: String, onClick: () -> Unit, testTag: String) {
    Box(
        modifier = Modifier
            .heightIn(min = ProductSize.minimumTouchTarget)
            .combinedClickable(role = Role.Button, onClick = onClick, onClickLabel = description)
            .padding(horizontal = ProductSpacing.xs)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("${testTag}_label"),
        )
    }
}

@Composable
private fun CrumbSeparator() {
    Text("›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One folder as a row: a folder mark, its name, and the chevron that says it opens. */
@Composable
internal fun FolderRow(folder: FolderEntity, onOpen: () -> Unit, onHold: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onHold, onClickLabel = "${folder.name}を開く")
            .testTag("folder_row_${folder.id}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = ProductSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = "フォルダ",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(ProductSpacing.sm))
            Text(
                folder.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** An open folder with nothing in it — neither folders nor documents of this kind. */
@Composable
internal fun FolderEmptyState() {
    Text(
        "このフォルダは空です",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(ProductSpacing.xl).testTag("folder_empty_state"),
    )
}

/** Asks for a folder's name; the answer is trimmed and never blank. */
@Composable
internal fun FolderNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // The caret starts after the name, so a rename continues the word rather than prefixing it.
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val name = FolderName.normalize(value.text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("フォルダ名") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { name?.let(onConfirm) }),
                modifier = Modifier.fillMaxWidth().testTag("folder_name_input"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { name?.let(onConfirm) },
                enabled = name != null,
                modifier = Modifier.testTag("folder_name_confirm"),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
        modifier = Modifier.testTag("folder_name_dialog"),
    )
}

/** What can be done to a held folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolderActionSheet(
    folder: FolderEntity,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("folder_action_sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = ProductSpacing.xl)) {
            Text(
                folder.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = ProductSpacing.lg, vertical = ProductSpacing.sm),
            )
            SheetRow("名前を変更", Icons.Outlined.Edit, "folder_rename_${folder.id}", onRename)
            SheetRow("フォルダへ移動", Icons.Outlined.DriveFileMove, "folder_move_${folder.id}", onMove)
            SheetRow("フォルダを削除", Icons.Outlined.Delete, "folder_delete_${folder.id}", onDelete)
        }
    }
}

@Composable
private fun SheetRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, testTag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ProductSize.minimumTouchTarget)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = ProductSpacing.lg, vertical = ProductSpacing.sm)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(ProductSpacing.md))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Deleting a folder keeps everything: it only asks because the folder itself goes. */
@Composable
internal fun FolderDeleteDialog(folder: FolderEntity, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${folder.name}」を削除しますか？") },
        text = { Text("中のメモ・アウトライン・フォルダは消えません。ひとつ上の階層へ移ります。") },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("folder_delete_confirm")) { Text("削除する") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
        modifier = Modifier.testTag("folder_delete_dialog"),
    )
}

/**
 * Where to put something: the root, or any folder of the one shared tree, drawn as an
 * indented list. [excluded] folders (a moving folder and everything under it) are not
 * offered — the tree rule, shown rather than explained.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolderPickerSheet(
    title: String,
    folders: List<FolderEntity>,
    excluded: Set<Long>,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val ordered = remember(folders) { orderedTree(folders) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("folder_picker"),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = ProductSpacing.xl)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = ProductSpacing.lg, vertical = ProductSpacing.sm),
            )
            PickerRow(label = "ルート（フォルダなし）", depth = 0, icon = Icons.Outlined.FolderOpen, testTag = "folder_pick_root") { onPick(null) }
            ordered.forEach { (folder, depth) ->
                if (folder.id !in excluded) {
                    PickerRow(label = folder.name, depth = depth + 1, icon = Icons.Outlined.Folder, testTag = "folder_pick_${folder.id}") {
                        onPick(folder.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(label: String, depth: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, testTag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ProductSize.minimumTouchTarget)
            .combinedClickable(onClick = onClick)
            .padding(start = ProductSpacing.lg + ProductSpacing.lg * depth, end = ProductSpacing.lg, top = ProductSpacing.sm, bottom = ProductSpacing.sm)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ProductSpacing.md),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The tree flattened for a list: parents before children, siblings by name (the way tags sort). */
internal fun orderedTree(folders: List<FolderEntity>): List<Pair<FolderEntity, Int>> {
    val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
    val byId = folders.associateBy { it.id }
    val order = Comparator<FolderNode> { a, b ->
        collator.compare(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id)
    }
    return FolderTree.flatten(folders.map(FolderEntity::toNode), order)
        .mapNotNull { (node, depth) -> byId[node.id]?.let { it to depth } }
}

/**
 * One row of the navigator in the wall's drawer (docs/FOLDER_NAVIGATOR_AUDIT.md): indented by
 * depth, a chevron when the folder has children, marked when it is the folder the wall shows.
 * A tap opens the folder on the wall; the chevron only opens or closes the row; a hold offers
 * the same rename / move / delete as the folder's card. Indent is capped so a deep chain still
 * leaves the name room.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NavigatorFolderRow(
    row: FolderRow,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onHold: () -> Unit,
) {
    // The whole row steps in with its depth, so its touch target and its highlight say where it
    // sits; the root row (depth 0) lines up with the drawer's other entries.
    val indent = ProductSpacing.sm + NAVIGATOR_INDENT * minOf(row.depth, NAVIGATOR_MAX_INDENT_DEPTH)
    val contentColor = if (row.selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        color = if (row.selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier
            .padding(start = indent, end = ProductSpacing.sm)
            .fillMaxWidth()
            .height(ProductSize.minimumTouchTarget)
            .semantics { selected = row.selected }
            .combinedClickable(onClick = onOpen, onLongClick = onHold, onClickLabel = "${row.name}を開く")
            .testTag("navigator_folder_${row.folderId}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = ProductSpacing.lg, end = ProductSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (row.expanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(ProductSpacing.md))
            Text(
                row.name,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (row.hasChildren) {
                IconButton(
                    onClick = onToggle,
                    modifier = Modifier.size(ProductSize.minimumTouchTarget).testTag("navigator_toggle_${row.folderId}"),
                ) {
                    Icon(
                        if (row.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (row.expanded) "${row.name}を折りたたむ" else "${row.name}を展開",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The root above every folder: すべて — the wall as it is with no folder open. */
@Composable
internal fun NavigatorRootRow(isSelected: Boolean, onOpen: () -> Unit) {
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier
            .padding(horizontal = ProductSpacing.sm)
            .fillMaxWidth()
            .height(ProductSize.minimumTouchTarget)
            .semantics { selected = isSelected }
            .combinedClickable(onClick = onOpen, onClickLabel = "すべてのフォルダを開く")
            .testTag("navigator_root"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = ProductSpacing.lg, end = ProductSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = contentColor, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(ProductSpacing.md))
            Text("すべて", style = MaterialTheme.typography.bodyLarge, color = contentColor, maxLines = 1)
        }
    }
}

/** 16dp per level; past six levels the rows stop stepping in so the name keeps its room. */
private val NAVIGATOR_INDENT = 16.dp
private const val NAVIGATOR_MAX_INDENT_DEPTH = 6

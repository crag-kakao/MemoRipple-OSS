package io.github.cragcoffee.memoripple.ui.memos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.data.PhotoAttachment
import io.github.cragcoffee.memoripple.domain.memos.MemoBlock
import io.github.cragcoffee.memoripple.ui.attachments.AttachmentImageLoader
import io.github.cragcoffee.memoripple.ui.attachments.InlinePhoto
import io.github.cragcoffee.memoripple.ui.attachments.PhotoAttachmentStrip
import io.github.cragcoffee.memoripple.ui.attachments.PhotoViewer
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A memo with photos, written (docs/MEMO_CONTENT_BLOCKS.md): its blocks in their order in one
 * column that scrolls — each text a field as tall as its words, each photo a picture at the
 * content width. The text being written is bound to the screen's body field ([activeField]), so
 * the toolbar, the markup guard, the task boxes and the shortcut panels act on it exactly as they
 * act on a memo without photos; tapping another text makes it the one being written.
 *
 * A photo is a picture that answers no touch here: its round ⋮ at the top right offers
 * フルスクリーン (the viewer), 写真一覧 (every photo of the memo as the row of thumbnails, with
 * 並べ替え) and 削除 (asked first). Backspace at the start of a text joins it to a text just
 * before; never to a photo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoBlockColumn(
    blocks: List<MemoBlock>,
    activeTextId: Long?,
    activeField: TextFieldValue,
    onActiveFieldChange: (TextFieldValue) -> Unit,
    /** Another text becomes the one being written, with the caret where the finger put it. */
    onActivate: (Long, TextRange?) -> Unit,
    readOnly: Boolean,
    onFocused: (Boolean) -> Unit,
    focusRequesterFor: (Long) -> FocusRequester,
    onToggleTaskActive: (Int) -> Unit,
    onToggleTaskIn: (Long, Int) -> Unit,
    onBackspaceAtStart: (Long) -> Unit,
    photos: List<PhotoAttachment>,
    imageLoader: AttachmentImageLoader,
    /** 削除 and 並べ替え — an active memo, not playing. */
    photoEditable: Boolean,
    viewerEditable: Boolean,
    importing: Boolean,
    onReorderPhotos: (List<Long>, (Boolean) -> Unit) -> Unit,
    onDeletePhoto: (PhotoAttachment) -> Unit,
    modifier: Modifier = Modifier,
    /** The journal's words are plain: no inline markup, no task boxes (docs/MEMO_CONTENT_BLOCKS.md §11). */
    plain: Boolean = false,
    /** The tag of the text being written. */
    activeTag: String = "memo_body",
    /** What follows the last block inside the same scroll (the journal's 未来の自分へ). */
    footer: (@Composable () -> Unit)? = null,
) {
    val byId = remember(photos) { photos.associateBy { it.id } }
    val ordered = blocks.filterIsInstance<MemoBlock.Photo>().mapNotNull { byId[it.attachmentId] }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var confirmDelete by remember { mutableStateOf<PhotoAttachment?>(null) }
    var showOverview by remember { mutableStateOf(false) }
    // Only a text that has the keyboard is followed: opening the page scrolls nowhere by itself.
    var focusedId by remember { mutableStateOf<Long?>(null) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val marginPx = with(LocalDensity.current) { 24.dp.roundToPx() }
    BoxWithConstraints(modifier) {
        val viewportPx = constraints.maxHeight
        val targetPx = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(320, 1440)
        val lastText = blocks.indexOfLast { it is MemoBlock.Text }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scroll).testTag("memo_block_column"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            blocks.forEachIndexed { index, block ->
                key(block.id) {
                    when (block) {
                        is MemoBlock.Text -> {
                            val active = block.id == activeTextId
                            var top by remember { mutableIntStateOf(0) }
                            var own by remember { mutableStateOf(TextFieldValue(block.text)) }
                            if (!active && own.text != block.text) own = own.copy(text = block.text)
                            val previousIsText = index > 0 && blocks[index - 1] is MemoBlock.Text
                            Box(Modifier.fillMaxWidth().onPlaced { top = it.positionInParent().y.roundToInt() }) {
                                MemoBodyEditor(
                                    body = if (active) activeField else own,
                                    onBodyChange = if (active) onActiveFieldChange else { value ->
                                        own = value
                                        onActivate(block.id, value.selection)
                                    },
                                    onFocused = { focused ->
                                        if (focused) focusedId = block.id else if (focusedId == block.id) focusedId = null
                                        if (focused && !active) onActivate(block.id, null)
                                        onFocused(focused)
                                    },
                                    readOnly = readOnly,
                                    onToggleTask = if (active) onToggleTaskActive else { line -> onToggleTaskIn(block.id, line) },
                                    focusRequester = focusRequesterFor(block.id),
                                    onCaret = { report ->
                                        if (active && focusedId == block.id) {
                                            NotePageScroll.follow(top, report, scroll.value, viewportPx, marginPx)?.let { target ->
                                                scope.launch { scroll.animateScrollTo(target) }
                                            }
                                        }
                                    },
                                    flow = true,
                                    // The last text reaches down: the empty page under it is where one writes on.
                                    flowMinHeight = if (index == lastText) 160.dp else 0.dp,
                                    onBackspaceAtStart = if (previousIsText) {
                                        { onBackspaceAtStart(block.id) }
                                    } else {
                                        null
                                    },
                                    hint = "",
                                    tag = if (active) activeTag else "memo_text_block_${block.id}",
                                    plain = plain,
                                )
                            }
                        }
                        is MemoBlock.Photo -> {
                            val photo = byId[block.attachmentId]
                            if (photo != null) {
                                Box(Modifier.fillMaxWidth().testTag("memo_photo_block_${block.id}")) {
                                    InlinePhoto(
                                        photo = photo,
                                        imageLoader = imageLoader,
                                        targetPx = targetPx,
                                        description = "添付写真 ${ordered.indexOf(photo) + 1} / ${ordered.size}",
                                        onClick = null,
                                    )
                                    Box(Modifier.align(Alignment.TopEnd)) {
                                        IconButton(
                                            onClick = { menuFor = block.id },
                                            modifier = Modifier.testTag("memo_photo_menu_${block.id}"),
                                        ) {
                                            Surface(
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                                contentColor = MaterialTheme.colorScheme.onSurface,
                                                shadowElevation = 1.dp,
                                                modifier = Modifier.size(32.dp),
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Outlined.MoreVert, contentDescription = "写真の操作", modifier = Modifier.size(20.dp))
                                                }
                                            }
                                        }
                                        DropdownMenu(expanded = menuFor == block.id, onDismissRequest = { menuFor = null }) {
                                            DropdownMenuItem(
                                                text = { Text("フルスクリーン") },
                                                leadingIcon = { Icon(Icons.Outlined.Fullscreen, contentDescription = null) },
                                                onClick = { menuFor = null; viewerIndex = ordered.indexOf(photo) },
                                                modifier = Modifier.testTag("memo_photo_fullscreen"),
                                            )
                                            DropdownMenuItem(
                                                text = { Text("写真一覧") },
                                                leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                                                onClick = { menuFor = null; showOverview = true },
                                                modifier = Modifier.testTag("memo_photo_overview"),
                                            )
                                            if (photoEditable) {
                                                DropdownMenuItem(
                                                    text = { Text("削除") },
                                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                                                    onClick = { menuFor = null; confirmDelete = photo },
                                                    modifier = Modifier.testTag("memo_photo_delete"),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            footer?.invoke()
        }
    }
    viewerIndex?.let { index ->
        if (ordered.isEmpty()) {
            viewerIndex = null
        } else {
            PhotoViewer(
                photos = ordered,
                initialIndex = index.coerceIn(ordered.indices),
                imageLoader = imageLoader,
                editable = viewerEditable,
                onDismiss = { viewerIndex = null },
                onDelete = onDeletePhoto,
            )
        }
    }
    if (showOverview) {
        // 写真一覧: the photos as the row of thumbnails they once were, with 並べ替え — the same strip.
        ModalBottomSheet(onDismissRequest = { showOverview = false }) {
            PhotoAttachmentStrip(
                photos = ordered,
                imageLoader = imageLoader,
                editable = viewerEditable,
                importing = importing,
                onDelete = onDeletePhoto,
                reorderEnabled = photoEditable,
                onReorder = onReorderPhotos,
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
                    .testTag("memo_photo_overview_sheet"),
            )
        }
    }
    confirmDelete?.let { photo ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("写真を削除しますか？") },
            text = { Text("前後の文章はそのまま残ります。") },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = null; onDeletePhoto(photo) },
                    modifier = Modifier.testTag("memo_photo_delete_confirm"),
                ) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("キャンセル") } },
        )
    }
}

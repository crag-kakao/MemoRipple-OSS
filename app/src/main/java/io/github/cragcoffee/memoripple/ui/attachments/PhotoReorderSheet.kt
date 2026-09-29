package io.github.cragcoffee.memoripple.ui.attachments

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.data.PhotoAttachment
import io.github.cragcoffee.memoripple.ui.components.ProductSheetHeader
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.memos.CARD_LIFT
import io.github.cragcoffee.memoripple.ui.memos.CardDragController
import io.github.cragcoffee.memoripple.ui.memos.EdgeScrollWhileCarrying
import io.github.cragcoffee.memoripple.ui.memos.cardDragHandle
import io.github.cragcoffee.memoripple.ui.memos.cardSlots
import io.github.cragcoffee.memoripple.ui.memos.carriedCard
import kotlinx.coroutines.launch

internal fun <T> movePhotoItem(items: List<T>, fromIndex: Int, toIndex: Int): List<T> {
    if (fromIndex !in items.indices || toIndex !in items.indices || fromIndex == toIndex) {
        return items
    }
    return items.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoReorderSheet(
    photos: List<PhotoAttachment>,
    imageLoader: AttachmentImageLoader,
    onDismiss: () -> Unit,
    onSave: (List<Long>, (Boolean) -> Unit) -> Unit,
) {
    val localPhotos = remember { mutableStateListOf<PhotoAttachment>().apply { addAll(photos) } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    // The handle carries its row as a floating card and the others slide as it crosses them —
    // the same drag as 設定 → ショートカットバー (CardDragController); 完了 writes the order.
    val drag = remember(listState) {
        CardDragController(
            scope = scope,
            slots = {
                cardSlots(listState.layoutInfo.visibleItemsInfo.map { Triple(it.key, IntOffset(0, it.offset), IntSize(Int.MAX_VALUE / 2, it.size)) }) { key ->
                    key as? Long
                }
            },
            onReorder = {},
            onCrossed = { id, targetId ->
                val from = localPhotos.indexOfFirst { it.id == id }
                val to = localPhotos.indexOfFirst { it.id == targetId }
                val moved = movePhotoItem(localPhotos.toList(), from, to)
                if (from >= 0 && to >= 0 && moved != localPhotos.toList()) {
                    localPhotos.clear()
                    localPhotos.addAll(moved)
                }
            },
        )
    }
    val liftPx = with(LocalDensity.current) { CARD_LIFT.toPx() }
    EdgeScrollWhileCarrying(
        drag = drag,
        viewport = { listState.layoutInfo.viewportStartOffset to listState.layoutInfo.viewportSize.height },
        scrollBy = { listState.scrollBy(it) },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.testTag("photo_reorder_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = ProductSpacing.xl),
        ) {
            ProductSheetHeader(
                title = "写真を並べ替え",
                onDismiss = if (saving) null else onDismiss,
            )
            Text(
                "ハンドルを引いて移動できます。読み上げ操作では「前へ移動」「後ろへ移動」を使えます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = ProductSpacing.md),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 520.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(ProductSpacing.sm),
            ) {
                itemsIndexed(localPhotos, key = { _, photo -> photo.id }) { index, photo ->
                    PhotoReorderRow(
                        photo = photo,
                        index = index,
                        count = localPhotos.size,
                        imageLoader = imageLoader,
                        modifier = (if (drag.activeId == photo.id) Modifier else Modifier.animateItem())
                            .carriedCard(drag, photo.id, liftPx),
                        dragHandle = Modifier.cardDragHandle(drag, photo.id, enabled = !saving, longPress = false) { emptyList() },
                        onMove = { destination ->
                            val moved = movePhotoItem(localPhotos, index, destination)
                            if (moved !== localPhotos) {
                                localPhotos.clear()
                                localPhotos.addAll(moved)
                                val visible = listState.layoutInfo.visibleItemsInfo
                                val firstVisible = visible.firstOrNull()?.index
                                val lastVisible = visible.lastOrNull()?.index
                                if (firstVisible != null && lastVisible != null &&
                                    destination !in firstVisible..lastVisible
                                ) {
                                    scope.launch { listState.scrollToItem(destination) }
                                }
                            }
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ProductSpacing.lg),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, enabled = !saving) { Text("キャンセル") }
                Button(
                    onClick = {
                        saving = true
                        onSave(localPhotos.map(PhotoAttachment::id)) { saving = false }
                    },
                    enabled = !saving,
                    modifier = Modifier.padding(start = ProductSpacing.sm)
                        .testTag("save_photo_order"),
                ) {
                    Text(if (saving) "保存中…" else "完了")
                }
            }
        }
    }
}

@Composable
private fun PhotoReorderRow(
    photo: PhotoAttachment,
    index: Int,
    count: Int,
    imageLoader: AttachmentImageLoader,
    modifier: Modifier,
    dragHandle: Modifier,
    onMove: (Int) -> Unit,
) {
    val accessibilityActions = buildList {
        if (index > 0) {
            add(CustomAccessibilityAction("前へ移動") { onMove(index - 1); true })
        }
        if (index < count - 1) {
            add(CustomAccessibilityAction("後ろへ移動") { onMove(index + 1); true })
        }
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "写真 ${index + 1} / $count"
                customActions = accessibilityActions
            }
            .testTag("photo_reorder_row_${photo.id}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(ProductSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReorderThumbnail(photo, imageLoader)
            Text(
                "写真 ${index + 1}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(horizontal = ProductSpacing.md),
            )
            Box(
                modifier = Modifier
                    .size(ProductSize.minimumTouchTarget)
                    .testTag("photo_drag_handle_${photo.id}")
                    .then(dragHandle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.DragHandle, contentDescription = null)
            }
        }
    }
}

@Composable
private fun ReorderThumbnail(
    photo: PhotoAttachment,
    imageLoader: AttachmentImageLoader,
) {
    var bitmap by remember(photo.blobSha256) { mutableStateOf<Bitmap?>(null) }
    var loaded by remember(photo.blobSha256) { mutableStateOf(false) }
    LaunchedEffect(photo.blobSha256) {
        bitmap = imageLoader.load(photo, 192)
        loaded = true
    }
    Surface(shape = MaterialTheme.shapes.small, modifier = Modifier.size(56.dp)) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp))
            }
            else -> Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.HideImage, contentDescription = null)
            }
        }
    }
}

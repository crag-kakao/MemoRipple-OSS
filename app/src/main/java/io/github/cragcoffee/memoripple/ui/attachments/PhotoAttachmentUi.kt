package io.github.cragcoffee.memoripple.ui.attachments

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.cragcoffee.memoripple.data.AttachmentBlobStore
import io.github.cragcoffee.memoripple.data.AttachmentImageSampling
import io.github.cragcoffee.memoripple.data.AttachmentLimits
import io.github.cragcoffee.memoripple.data.PhotoAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class AttachmentImageLoader(private val store: AttachmentBlobStore) {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    suspend fun load(photo: PhotoAttachment, targetPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val bucket = targetPx.coerceAtLeast(64)
        val key = "${photo.blobSha256}:$bucket"
        if (!store.isValid(photo.blobSha256, photo.sizeBytes)) {
            cache.remove(key)
            return@withContext null
        }
        cache.get(key)?.takeUnless(Bitmap::isRecycled) ?: runCatching {
            val file = store.blobFile(photo.blobSha256)
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.setTargetSampleSize(
                    maxOf(
                        AttachmentImageSampling.targetSampleSize(
                            maxOf(info.size.width, info.size.height).coerceAtLeast(1),
                            bucket,
                        ),
                        PhotoViewerTransformMath.safeDecodeSampleSize(
                            sourceWidthPx = info.size.width,
                            sourceHeightPx = info.size.height,
                            targetLongestEdgePx = bucket,
                        ),
                    ),
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }.also { cache.put(key, it) }
        }.getOrNull()
    }
}
/**
 * The photos a record already has.
 *
 * Adding one is an action, and it lives with the other writing actions above the keyboard. This
 * shows what is there, so a record with no photos shows nothing at all rather than a placeholder
 * taking the space the body wants.
 */
@Composable
fun PhotoAttachmentStrip(
    photos: List<PhotoAttachment>,
    imageLoader: AttachmentImageLoader,
    editable: Boolean,
    importing: Boolean,
    onDelete: (PhotoAttachment) -> Unit,
    reorderEnabled: Boolean = false,
    onReorder: (List<Long>, (Boolean) -> Unit) -> Unit = { _, complete -> complete(false) },
    modifier: Modifier = Modifier,
) {
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var showReorderSheet by remember { mutableStateOf(false) }
    if (photos.isEmpty() && !importing) return
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("写真", style = MaterialTheme.typography.labelLarge)
            Text(
                " ${photos.size}/${AttachmentLimits.MAX_PHOTOS_PER_RECORD}枚",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (reorderEnabled && photos.size >= 2 && !importing) {
                // At the row's end, where the eye looks for an action (S26 review, 2026-09-24).
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { showReorderSheet = true },
                    modifier = Modifier.testTag("open_photo_reorder"),
                ) {
                    Text("並べ替え")
                }
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            if (importing) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(width = 104.dp, height = 88.dp)
                            .testTag("photo_importing"),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text("追加中…", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            itemsIndexed(photos, key = { _, photo -> photo.id }) { index, photo ->
                AttachmentThumbnail(
                    photo = photo,
                    imageLoader = imageLoader,
                    index = index,
                    count = photos.size,
                    onClick = { viewerIndex = index },
                )
            }
        }
    }
    viewerIndex?.let { index ->
        if (photos.isEmpty()) {
            viewerIndex = null
        } else {
            PhotoViewer(
                photos = photos,
                initialIndex = index.coerceIn(photos.indices),
                imageLoader = imageLoader,
                editable = editable,
                onDismiss = { viewerIndex = null },
                onDelete = onDelete,
            )
        }
    }
    if (showReorderSheet) {
        PhotoReorderSheet(
            photos = photos,
            imageLoader = imageLoader,
            onDismiss = { showReorderSheet = false },
            onSave = { orderedIds, complete ->
                onReorder(orderedIds) { success ->
                    complete(success)
                    showReorderSheet = false
                }
            },
        )
    }
}

@Composable
private fun AttachmentThumbnail(
    photo: PhotoAttachment,
    imageLoader: AttachmentImageLoader,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    var bitmap by remember(photo.blobSha256) { mutableStateOf<Bitmap?>(null) }
    var loaded by remember(photo.blobSha256) { mutableStateOf(false) }
    LaunchedEffect(photo.blobSha256) {
        bitmap = imageLoader.load(photo, 320)
        loaded = true
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.size(88.dp).clickable(onClick = onClick)
            .semantics { contentDescription = "添付写真 ${index + 1} / $count" },
    ) {
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
            else -> MissingPhoto()
        }
    }
}

@Composable
internal fun PhotoViewer(
    photos: List<PhotoAttachment>,
    initialIndex: Int,
    imageLoader: AttachmentImageLoader,
    editable: Boolean,
    onDismiss: () -> Unit,
    onDelete: (PhotoAttachment) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(photos.indices),
        pageCount = { photos.size },
    )
    val scope = rememberCoroutineScope()
    var currentPageZoomed by remember { mutableStateOf(false) }
    var transformGestureActive by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<PhotoAttachment?>(null) }
    LaunchedEffect(photos.map(PhotoAttachment::id)) {
        val target = PhotoViewerTransformMath.pageAfterRemoval(
            currentPage = pagerState.currentPage,
            remainingCount = photos.size,
        ) ?: return@LaunchedEffect
        if (target != pagerState.currentPage) pagerState.scrollToPage(target)
        currentPageZoomed = false
        transformGestureActive = false
        deleteTarget = deleteTarget?.takeIf { targetPhoto ->
            photos.any { it.id == targetPhoto.id }
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        currentPageZoomed = false
        transformGestureActive = false
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.scrim, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = !currentPageZoomed && !transformGestureActive,
                    beyondViewportPageCount = 1,
                    key = { page -> photos[page].id },
                    modifier = Modifier.fillMaxSize().padding(vertical = 72.dp)
                        .testTag("photo_viewer_pager"),
                ) { page ->
                    val pagePhoto = photos[page]
                    PhotoViewerPage(
                        photo = pagePhoto,
                        index = page,
                        count = photos.size,
                        imageLoader = imageLoader,
                        isCurrent = page == pagerState.currentPage,
                        onTransformStateChanged = { zoomed, active ->
                            if (page == pagerState.currentPage) {
                                currentPageZoomed = zoomed
                                transformGestureActive = active
                            }
                        },
                    )
                }
                Row(
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "閉じる", tint = Color.White)
                    }
                    Text(
                        "${pagerState.currentPage.coerceIn(photos.indices) + 1} / ${photos.size}",
                        color = Color.White,
                        modifier = Modifier.weight(1f).testTag("photo_viewer_counter"),
                    )
                    if (editable) {
                        IconButton(
                            onClick = {
                                deleteTarget = photos.getOrNull(
                                    pagerState.currentPage.coerceIn(photos.indices),
                                )
                            },
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = "写真を削除", tint = Color.White)
                        }
                    }
                }
                if (photos.size > 1) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        },
                        enabled = pagerState.currentPage > 0,
                        modifier = Modifier.align(Alignment.CenterStart),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "前の写真", tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        },
                        enabled = pagerState.currentPage < photos.lastIndex,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "次の写真", tint = Color.White)
                    }
                }
            }
        }
    }
    deleteTarget?.let { photo ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("写真を削除しますか？") },
            text = { Text("メモや日記の本文は削除されません。") },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("キャンセル") }
            },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; onDelete(photo) }) {
                    Text("削除")
                }
            },
        )
    }
}

@Composable
private fun PhotoViewerPage(
    photo: PhotoAttachment,
    index: Int,
    count: Int,
    imageLoader: AttachmentImageLoader,
    isCurrent: Boolean,
    onTransformStateChanged: (zoomed: Boolean, gestureActive: Boolean) -> Unit,
) {
    var viewportSize by remember(photo.id) { mutableStateOf(IntSize.Zero) }
    val decodeTargetPx = remember(photo.id, viewportSize) {
        PhotoViewerTransformMath.viewerDecodeTargetPx(
            viewportWidthPx = viewportSize.width,
            viewportHeightPx = viewportSize.height,
            sourceWidthPx = photo.widthPx,
            sourceHeightPx = photo.heightPx,
        )
    }
    var bitmap by remember(photo.blobSha256) { mutableStateOf<Bitmap?>(null) }
    var loaded by remember(photo.blobSha256) { mutableStateOf(false) }
    var transform by remember(photo.id) { mutableStateOf(PhotoViewerTransform()) }
    var gestureActive by remember(photo.id) { mutableStateOf(false) }
    LaunchedEffect(photo.blobSha256, decodeTargetPx) {
        if (decodeTargetPx <= 0) return@LaunchedEffect
        loaded = false
        bitmap = imageLoader.load(photo, decodeTargetPx)
        loaded = true
        if (bitmap == null) transform = PhotoViewerTransform()
    }
    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            transform = PhotoViewerTransform()
            gestureActive = false
        }
    }
    LaunchedEffect(transform.scale, gestureActive, isCurrent) {
        if (isCurrent) {
            onTransformStateChanged(
                PhotoViewerTransformMath.isZoomed(transform.scale),
                gestureActive,
            )
        }
    }
    val imageWidth = bitmap?.width?.toFloat() ?: 0f
    val imageHeight = bitmap?.height?.toFloat() ?: 0f
    val imageDescription = "添付写真 ${index + 1} / $count"
    Box(
        modifier = Modifier.fillMaxSize()
            .onSizeChanged { size ->
                viewportSize = size
                transform = PhotoViewerTransformMath.clamp(
                    transform = transform,
                    viewportWidth = size.width.toFloat(),
                    viewportHeight = size.height.toFloat(),
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                )
            }
            .testTag("photo_viewer_page_${photo.id}"),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer {
                        scaleX = transform.scale
                        scaleY = transform.scale
                        translationX = transform.translationX
                        translationY = transform.translationY
                        clip = true
                    }
                    .pointerInput(photo.id, bitmap) {
                        detectTapGestures(
                            onDoubleTap = { tap ->
                                transform = PhotoViewerTransformMath.doubleTap(
                                    current = transform,
                                    tapX = tap.x,
                                    tapY = tap.y,
                                    viewportWidth = viewportSize.width.toFloat(),
                                    viewportHeight = viewportSize.height.toFloat(),
                                    imageWidth = imageWidth,
                                    imageHeight = imageHeight,
                                )
                            },
                        )
                    }
                    .pointerInput(photo.id, bitmap) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var accumulatedPanX = 0f
                            var accumulatedPanY = 0f
                            var panGestureStarted = false
                            do {
                                val event = awaitPointerEvent()
                                val pressedCount = event.changes.count { it.pressed }
                                val pan = event.calculatePan()
                                val zoomChange = if (pressedCount >= 2) event.calculateZoom() else 1f
                                if (pressedCount >= 2) {
                                    panGestureStarted = true
                                } else if (
                                    pressedCount == 1 &&
                                    PhotoViewerTransformMath.isZoomed(transform.scale) &&
                                    !panGestureStarted
                                ) {
                                    accumulatedPanX += pan.x
                                    accumulatedPanY += pan.y
                                    panGestureStarted = PhotoViewerTransformMath.exceedsTouchSlop(
                                        panX = accumulatedPanX,
                                        panY = accumulatedPanY,
                                        touchSlop = viewConfiguration.touchSlop,
                                    )
                                }
                                val applyAccumulatedPan = pressedCount == 1 &&
                                    panGestureStarted &&
                                    (accumulatedPanX != 0f || accumulatedPanY != 0f)
                                val appliedPanX = if (applyAccumulatedPan) accumulatedPanX else pan.x
                                val appliedPanY = if (applyAccumulatedPan) accumulatedPanY else pan.y
                                val handleTransform = PhotoViewerTransformMath.shouldHandleTransform(
                                    pressedPointerCount = pressedCount,
                                    scale = transform.scale,
                                    zoomChange = zoomChange,
                                    panX = appliedPanX,
                                    panY = appliedPanY,
                                    panGestureStarted = panGestureStarted,
                                )
                                if (handleTransform) {
                                    gestureActive = true
                                    val focus = event.calculateCentroid(useCurrent = true)
                                    transform = PhotoViewerTransformMath.transformBy(
                                        current = transform,
                                        zoomChange = zoomChange,
                                        panX = appliedPanX,
                                        panY = appliedPanY,
                                        focusX = focus.x,
                                        focusY = focus.y,
                                        viewportWidth = viewportSize.width.toFloat(),
                                        viewportHeight = viewportSize.height.toFloat(),
                                        imageWidth = imageWidth,
                                        imageHeight = imageHeight,
                                    )
                                    event.changes.forEach { change ->
                                        if (change.positionChanged()) change.consume()
                                    }
                                    accumulatedPanX = 0f
                                    accumulatedPanY = 0f
                                }
                            } while (event.changes.any { it.pressed })
                            gestureActive = false
                        }
                    }
                    .semantics {
                        contentDescription = imageDescription
                        stateDescription = zoomDescription(transform.scale)
                        customActions = buildList {
                            if (transform.scale < PHOTO_VIEWER_MAX_SCALE - PHOTO_VIEWER_SCALE_EPSILON) {
                                add(CustomAccessibilityAction("拡大") {
                                    transform = PhotoViewerTransformMath.setScale(
                                        current = transform,
                                        requestedScale = transform.scale + PHOTO_VIEWER_ACCESSIBILITY_SCALE_STEP,
                                        viewportWidth = viewportSize.width.toFloat(),
                                        viewportHeight = viewportSize.height.toFloat(),
                                        imageWidth = imageWidth,
                                        imageHeight = imageHeight,
                                    )
                                    true
                                })
                            }
                            if (PhotoViewerTransformMath.isZoomed(transform.scale)) {
                                add(CustomAccessibilityAction("縮小") {
                                    transform = PhotoViewerTransformMath.setScale(
                                        current = transform,
                                        requestedScale = transform.scale - PHOTO_VIEWER_ACCESSIBILITY_SCALE_STEP,
                                        viewportWidth = viewportSize.width.toFloat(),
                                        viewportHeight = viewportSize.height.toFloat(),
                                        imageWidth = imageWidth,
                                        imageHeight = imageHeight,
                                    )
                                    true
                                })
                                add(CustomAccessibilityAction("等倍に戻す") {
                                    transform = PhotoViewerTransform()
                                    true
                                })
                            }
                        }
                    }
                    .testTag("photo_viewer_image_${photo.id}"),
                contentScale = ContentScale.Fit,
            )
            !loaded -> CircularProgressIndicator()
            else -> MissingPhoto(
                modifier = Modifier.size(160.dp),
                accessibleDescription = "$imageDescription、写真を表示できません",
            )
        }
    }
}

private fun zoomDescription(scale: Float): String {
    val tenths = (PhotoViewerTransformMath.sanitizeScale(scale) * 10f).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}倍" else "${tenths / 10f}倍"
}

@Composable
internal fun MissingPhoto(
    modifier: Modifier = Modifier,
    accessibleDescription: String? = null,
) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant).then(
            if (accessibleDescription == null) {
                Modifier
            } else {
                Modifier.clearAndSetSemantics { contentDescription = accessibleDescription }
            },
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.HideImage, contentDescription = null)
        Text("写真を表示できません", style = MaterialTheme.typography.labelSmall)
    }
}

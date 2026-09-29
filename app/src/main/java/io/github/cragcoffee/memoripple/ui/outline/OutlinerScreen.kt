package io.github.cragcoffee.memoripple.ui.outline

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.outlined.MoreVert
import io.github.cragcoffee.memoripple.ui.attachments.PhotoViewer
import io.github.cragcoffee.memoripple.ui.attachments.InlinePhoto
import androidx.compose.ui.platform.LocalConfiguration
import io.github.cragcoffee.memoripple.data.PhotoAttachment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.outlined.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import io.github.cragcoffee.memoripple.data.AttachmentLimits
import io.github.cragcoffee.memoripple.domain.NoteLink
import io.github.cragcoffee.memoripple.domain.InlineStyle
import io.github.cragcoffee.memoripple.domain.InlineTextMarkup
import io.github.cragcoffee.memoripple.ui.attachments.AttachmentImageLoader
import io.github.cragcoffee.memoripple.ui.memos.CommentLinkPickerSheet
import io.github.cragcoffee.memoripple.ui.memos.EditorShortcutPanel
import io.github.cragcoffee.memoripple.ui.memos.MemoLinkPickerSheet
import io.github.cragcoffee.memoripple.ui.memos.MemoTemplateSheet
import io.github.cragcoffee.memoripple.ui.memos.CommentLinkToolbarItem
import io.github.cragcoffee.memoripple.ui.memos.LinkToolbarItem
import io.github.cragcoffee.memoripple.ui.memos.TemplateToolbarItem
import io.github.cragcoffee.memoripple.ui.memos.HighlightToolbarItem
import io.github.cragcoffee.memoripple.ui.memos.BoldToolbarItem
import io.github.cragcoffee.memoripple.ui.memos.HistoryToolbarItems
import io.github.cragcoffee.memoripple.ui.memos.PhotoToolbarItem
import io.github.cragcoffee.memoripple.ui.memos.rememberInlineMarkupTransformation
import io.github.cragcoffee.memoripple.ui.memos.EDITOR_HISTORY_LIMIT
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.domain.memos.EditorToolbarSurface
import io.github.cragcoffee.memoripple.domain.memos.ToolbarChips
import io.github.cragcoffee.memoripple.domain.memos.ToolbarChipGroup
import io.github.cragcoffee.memoripple.domain.memos.ToolbarChipArrangement
import io.github.cragcoffee.memoripple.domain.memos.EditorToolbarOrder
import io.github.cragcoffee.memoripple.domain.memos.EditorToolbarItem
import io.github.cragcoffee.memoripple.domain.WorkOutlineEditing
import io.github.cragcoffee.memoripple.domain.OutlineSymbolSelection
import io.github.cragcoffee.memoripple.domain.OutlineEdit
import io.github.cragcoffee.memoripple.domain.OutlineChipLabel
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.CheckBox
import kotlinx.coroutines.isActive
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material.icons.outlined.Add
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.map
import io.github.cragcoffee.memoripple.domain.OutlineSymbolRole
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.domain.outline.OutlineBlank
import io.github.cragcoffee.memoripple.domain.outline.OutlineDocument
import io.github.cragcoffee.memoripple.domain.outline.OutlineEditing
import io.github.cragcoffee.memoripple.domain.outline.OutlineEntry
import io.github.cragcoffee.memoripple.domain.outline.OutlineFoldKeys
import io.github.cragcoffee.memoripple.domain.outline.OutlineNode
import io.github.cragcoffee.memoripple.domain.outline.OutlinePhotos
import io.github.cragcoffee.memoripple.domain.outline.OutlineText
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.components.ProductTopBar
import io.github.cragcoffee.memoripple.ui.memos.MemoEditorViewModel
import kotlinx.coroutines.launch

/**
 * アウトライナー: an outline (a memo of kind = outline) edited as a tree of lines. The body
 * text stays the record — every edit is serialized back through the memo editor's own view
 * model and its autosave, so nothing new is stored and nothing is stored twice, and the
 * comment, reading and read-aloud paths see plain text. What is folded is remembered on the
 * device by content key (§OutlineFoldKeys), never in the body; a zoom is a way of looking
 * and lives only while the screen does. Opened from the home アウトライナー page and the
 * archive; the kind is decided at creation and never here.
 */
@Composable
fun OutlinerRoute(
    memoId: Long,
    appSettings: AppSettings,
    /** ＋ just made this outline: left with nothing in it, it is taken back (docs/OUTLINE_PHOTO_ROWS.md §8). */
    fresh: Boolean = false,
    onBack: () -> Unit,
    onOpenMemo: (Long) -> Unit = {},
    onSwapPrimaryMemo: (Long) -> Unit = onOpenMemo,
    onOpenNoteAsPrimary: (Long) -> Unit = {},
) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    val viewModel: MemoEditorViewModel = viewModel(
        key = "memo-editor-$memoId",
        factory = MemoEditorViewModel.factory(
            application.memoRepository,
            application.memoCommentRepository,
            memoId,
            application.speechController,
            application.tagRepository,
            application.attachmentRepository,
            application.templateRepository,
            settingsRepository = application.settingsRepository,
            outlineStore = application.outlineStore,
            discardIfEmpty = fresh,
        ),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val symbolsStored by application.settingsRepository.outlineSymbolSet.collectAsStateWithLifecycle(initialValue = "")
    val symbols = remember(symbolsStored) { OutlineSymbolSelection.decode(symbolsStored) }
    val session = remember(viewModel) { OutlinerSession() }
    // What the memo editor wears above and below the writing, on the outline's own screen.
    val playback = rememberOutlinerPlaybackUi(
        memoId = memoId,
        viewModel = viewModel,
        state = state,
        appSettings = appSettings,
        symbols = symbols,
        snackbarHostState = snackbarHostState,
        onLeave = onBack,
        onOpenMemo = onOpenMemo,
        onSwapPrimaryMemo = onSwapPrimaryMemo,
        onOpenNoteAsPrimary = onOpenNoteAsPrimary,
        // A task ticked on the reading page is an edit of the outline like any other: the same
        // line change as before, through the session — so the folds, the zoom and the undo / redo
        // history stay, and 元に戻す takes it back (2026-09-25). It used to go round the session as
        // a whole new body, which the session read as an outside write and reset everything for.
        onToggleReadingTask = { line ->
            session.edit(viewModel::updateOutline) { current ->
                val id = OutlineEditing.entryIdAtBodyLine(current, line) ?: return@edit current
                OutlineEditing.rewriteLine(current, id) { raw -> WorkOutlineEditing.toggleTaskAt(raw, 0, symbols) }
            }
        },
    )
    // The memo editor's writing aids, read from the same view model: templates, link targets,
    // comments and photos, plus the photo picker (photos hang on the memo id, not on the outline).
    val aidScope = rememberCoroutineScope()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val linkTargets by viewModel.linkTargets.collectAsStateWithLifecycle()
    val comments by viewModel.comments.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val photoMessage by viewModel.photoMessage.collectAsStateWithLifecycle()
    val imageLoader = remember(application) { AttachmentImageLoader(application.attachmentBlobStore) }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AttachmentLimits.MAX_PHOTOS_PER_RECORD),
    ) { uris -> viewModel.addPhotos(uris) }
    LaunchedEffect(photoMessage) {
        photoMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearPhotoMessage()
        }
    }
    val aids = OutlinerAids(
        templates = templates,
        onDeleteTemplate = viewModel::deleteTemplate,
        onCreateTemplate = viewModel::createTemplate,
        linkTargets = linkTargets,
        comments = comments,
        onLinkComment = viewModel::linkComment,
        onOpenComments = playback.openComments,
        onAddPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        photos = photos,
        importingPhotos = state.isImportingPhotos,
        onDeletePhoto = viewModel::deletePhoto,
        imageLoader = imageLoader,
        onMessage = { message -> aidScope.launch { snackbarHostState.showSnackbar(message) } },
    )
    val storedFolds by application.settingsRepository.outlinerFolds(memoId)
        .collectAsStateWithLifecycle(initialValue = null)
    // "" = nothing remembered; null = not read yet. The place is restored once, after the folds.
    val anchorFlow = remember(memoId) { application.settingsRepository.outlinerScrollAnchor(memoId).map { it ?: "" } }
    val storedAnchor by anchorFlow.collectAsStateWithLifecycle(initialValue = null)
    var placeRestored by remember(viewModel) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The bar as the writer arranged it in 設定, and the glyphs its chips write.
    val toolbarStored by application.settingsRepository.editorToolbarOutlinerOrder.collectAsStateWithLifecycle(initialValue = "")
    val toolbarArrangement = remember(toolbarStored) { EditorToolbarOrder.decodeArrangement(toolbarStored, EditorToolbarSurface.OUTLINER) }
    val toolbarTwoRows by application.settingsRepository.editorToolbarTwoRows.collectAsStateWithLifecycle(initialValue = false)
    val chipLabelStored by application.settingsRepository.outlineChipLabel.collectAsStateWithLifecycle(initialValue = "")
    val chipLabel = remember(chipLabelStored) { OutlineChipLabel.fromStorageId(chipLabelStored) }
    val labelChipsStored by application.settingsRepository.toolbarChips("OUTLINER", "LABELS").collectAsStateWithLifecycle(initialValue = "")
    val labelChips = remember(labelChipsStored) { ToolbarChips.decode(labelChipsStored, ToolbarChipGroup.LABELS) }
    val flowChipsStored by application.settingsRepository.toolbarChips("OUTLINER", "FLOW").collectAsStateWithLifecycle(initialValue = "")
    val flowChips = remember(flowChipsStored) { ToolbarChips.decode(flowChipsStored, ToolbarChipGroup.FLOW) }
    // The zoom rides the activity's saved state as a content key — a way of looking, kept
    // through rotation and recreation but written nowhere durable. The key this screen was
    // recreated with is read once, before the running session starts overwriting it.
    var savedZoomKey by rememberSaveable { mutableStateOf<String?>(null) }
    val zoomKeyOnEntry = remember { savedZoomKey }
    var zoomRestored by remember(session) { mutableStateOf(false) }
    LaunchedEffect(state.isLoading, state.body, state.outline) {
        if (!state.isLoading) {
            session.acceptBody(state.body, state.outline)
            session.offerFirstLine()
            if (!zoomRestored) {
                zoomRestored = true
                session.restoreZoom(zoomKeyOnEntry)
            }
        }
    }
    LaunchedEffect(session) {
        snapshotFlow { session.zoomKey() }.collect { savedZoomKey = it }
    }
    // Photos just added become photo rows where the writing is (docs/OUTLINE_PHOTO_ROWS.md §3):
    // an edit like any other — it saves, and 元に戻す takes it back.
    LaunchedEffect(state.pendingOutlinePhotos) {
        val ids = state.pendingOutlinePhotos
        if (ids.isEmpty()) return@LaunchedEffect
        var focus: FocusTarget? = null
        session.edit(viewModel::updateOutline) { current ->
            val caret = session.focusedId?.let { session.focusedSelection.end }
            val placed = OutlineEditing.insertPhotos(current, session.focusedId, ids, caret)
            focus = placed.focusId?.let { FocusTarget(it, placed.caret) }
            placed.document
        }
        focus?.let(session::requestFocus)
        viewModel.consumeOutlinePhotos()
    }
    LaunchedEffect(storedFolds, storedAnchor, state.isLoading) {
        val keys = storedFolds ?: return@LaunchedEffect
        if (state.isLoading) return@LaunchedEffect
        session.offerStoredFolds(keys)
        val anchor = storedAnchor ?: return@LaunchedEffect
        if (!placeRestored) {
            placeRestored = true
            session.restoreScroll(anchor.takeIf { it.isNotBlank() })
        }
    }
    val persistFolds: () -> Unit = {
        val keys = session.foldKeys()
        scope.launch { application.settingsRepository.setOutlinerFolds(memoId, keys) }
    }
    val persistPlace: () -> Unit = {
        val key = session.scrollKey()
        scope.launch { application.settingsRepository.setOutlinerScrollAnchor(memoId, key) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.saveNow()
                viewModel.pausePlayback()
                if (session.foldsTouched) persistFolds()
                persistPlace()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val leave = {
        if (session.foldsTouched) persistFolds()
        persistPlace()
        viewModel.saveAndThen(onBack)
    }
    // A zoom unwinds one level per Back; only the root leaves the screen.
    // Back unwinds what is on top first: 閲覧モード or a split, then one zoom level; only the
    // root leaves the screen.
    BackHandler(enabled = !state.isLoading) {
        if (!playback.handleBack()) {
            if (session.zoomId != null) session.zoomOut() else leave()
        }
    }

    OutlinerScreen(
        session = session,
        loading = state.isLoading,
        onBack = leave,
        onDocumentChange = viewModel::updateOutline,
        onFoldsChanged = persistFolds,
        toolbar = toolbarArrangement.visible,
        toolbarLower = toolbarArrangement.lower,
        toolbarTwoRows = toolbarTwoRows,
        symbols = symbols,
        chipLabel = chipLabel,
        labelChips = labelChips,
        flowChips = flowChips,
        playback = playback,
        aids = aids,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        onPhotoLimit = { viewModel.showPhotoMessage(OUTLINE_HISTORY_PHOTO_LIMIT_MESSAGE) },
        notice = if (state.outlineConflict) {
            { OutlineConflictNotice(onReload = viewModel::reloadOutline) }
        } else {
            null
        },
    )
}

/**
 * The outline was written elsewhere after this editor last read it (docs/OUTLINE_STABLE_ROWS.md
 * §9): nothing from here was saved, and nothing will be until the latest is read again.
 */
@Composable
private fun OutlineConflictNotice(onReload: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = ProductSize.screenHorizontalPadding, vertical = ProductSpacing.sm)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("outliner_conflict"),
    ) {
        Row(
            modifier = Modifier.padding(start = ProductSpacing.md, end = ProductSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "このアウトラインは別の場所で更新されました。ここでの変更は保存されていません。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = ProductSpacing.sm),
            )
            TextButton(onClick = onReload, modifier = Modifier.testTag("outliner_conflict_reload")) { Text("再読み込み") }
        }
    }
}

/**
 * A photo row (docs/OUTLINE_PHOTO_ROWS.md): the picture at its row's depth, at the width left to
 * it, whole and in its own proportions; nothing counts the photos. The picture takes no touch;
 * its round ⋮ offers フルスクリーン, 上へ / 下へ, 字下げ / 字下げを戻す and 削除 — the moves every line has
 * (上へ / 下へ trade places with the neighbouring line, as the bar does) and a delete that 元に戻す
 * takes back.
 */
@Composable
private fun OutlinePhotoRow(
    node: OutlineNode,
    photo: PhotoAttachment?,
    imageLoader: AttachmentImageLoader?,
    depthOffset: Int,
    editable: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canIndent: Boolean,
    canOutdent: Boolean,
    onFullScreen: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onIndent: () -> Unit,
    onOutdent: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val steps = (node.depth - depthOffset).coerceAtLeast(0)
    val targetPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }.coerceIn(320, 1440)
    var menu by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = ProductSize.screenHorizontalPadding + DEPTH_STEP * steps + BULLET_SIZE,
                end = ProductSize.screenHorizontalPadding,
                top = ProductSpacing.xs,
                bottom = ProductSpacing.xs,
            )
            .testTag("outliner_photo_${node.id}"),
    ) {
        if (photo != null && imageLoader != null) {
            InlinePhoto(photo = photo, imageLoader = imageLoader, targetPx = targetPx, description = "写真", onClick = null)
        } else {
            Text(
                "写真が見つかりません",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = ProductSpacing.sm),
            )
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("outliner_photo_menu_${node.id}")) {
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
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("フルスクリーン") },
                    onClick = { menu = false; onFullScreen() },
                    modifier = Modifier.testTag("outliner_photo_fullscreen"),
                )
                if (editable) {
                    DropdownMenuItem(text = { Text("上へ") }, enabled = canMoveUp, onClick = { menu = false; onMoveUp() }, modifier = Modifier.testTag("outliner_photo_up"))
                    DropdownMenuItem(text = { Text("下へ") }, enabled = canMoveDown, onClick = { menu = false; onMoveDown() }, modifier = Modifier.testTag("outliner_photo_down"))
                    DropdownMenuItem(text = { Text("字下げ") }, enabled = canIndent, onClick = { menu = false; onIndent() }, modifier = Modifier.testTag("outliner_photo_indent"))
                    DropdownMenuItem(text = { Text("字下げを戻す") }, enabled = canOutdent, onClick = { menu = false; onOutdent() }, modifier = Modifier.testTag("outliner_photo_outdent"))
                    DropdownMenuItem(text = { Text("削除") }, onClick = { menu = false; onDelete() }, modifier = Modifier.testTag("outliner_photo_delete"))
                }
            }
        }
    }
}

/** What a press of 元に戻す / やり直し came to. */
enum class HistoryStep {
    APPLIED,

    /** Nothing to take back or give again (or the outline is read-only). */
    NONE,

    /** Not taken: it would show more photos than the cap allows. */
    PHOTO_LIMIT,
}

/** 元に戻す / やり直し refused for the photo cap — what the screen says (never silent). */
const val OUTLINE_HISTORY_PHOTO_LIMIT_MESSAGE = "写真は最大20枚までです。追加した写真を減らしてから元に戻してください。"

/** Where the caret goes after a structural edit: which line, and which character. */
data class FocusTarget(val id: Int, val caret: Int?)

/**
 * The outline as the screen holds it: the parsed document, what is folded, the zoom, which
 * line has the caret, and the body text it last wrote — so its own echo through the view
 * model is not parsed again, while a body changed elsewhere is.
 */
class OutlinerSession {
    var document: OutlineDocument by mutableStateOf(OutlineDocument(emptyList(), 1))
        private set
    var collapsed: Set<Int> by mutableStateOf(emptySet())
        private set
    var zoomId: Int? by mutableStateOf(null)
        private set
    var focusedId: Int? by mutableStateOf(null)
    var pendingFocus: FocusTarget? by mutableStateOf(null)
    /** The caret or selection inside the live line, in that line's own text — what the bar's writing aids act on. */
    var focusedSelection: TextRange by mutableStateOf(TextRange.Zero)

    /**
     * False while the outline stands in conflict with a newer one (§9): no edit, undo or redo is
     * made — what is on screen is not the outline's to change until the latest is read again.
     */
    var writable: Boolean = true

    /** Makes [target]'s line the live field and asks for the caret there. */
    fun requestFocus(target: FocusTarget) {
        focusedId = target.id
        pendingFocus = target
    }

    /** True once the writer folded or unfolded something here — what is worth persisting. */
    var foldsTouched: Boolean = false
        private set
    private var lastSerialized: String? = null
    private var awaitingStoredFolds = false

    /**
     * The next id a new line takes never goes back (docs/OUTLINE_STABLE_ROWS.md): an undo that
     * takes a line away does not free its id for the next line, so a lasting id names one line.
     */
    private var idFloor = 1

    private fun withIdFloor(next: OutlineDocument): OutlineDocument {
        idFloor = maxOf(idFloor, next.nextId)
        return if (next.nextId >= idFloor) next else next.copy(nextId = idFloor)
    }

    /**
     * The outline as stored: [stored] carries each line's lasting id (Room 28) and is taken when
     * it is what [body] says; without it (no rows yet) the body is read afresh. The same words
     * with other photo rows (Room 29 — the outline read again after a conflict) are still a
     * different outline; the session's own echo (the same rows) changes nothing.
     */
    fun acceptBody(body: String, stored: OutlineDocument? = null) {
        if (body == lastSerialized && (stored == null || stored.entries == document.entries)) return
        lastSerialized = body
        document = withIdFloor(stored?.takeIf { OutlineText.serialize(it) == body } ?: OutlineText.parse(body))
        collapsed = emptySet()
        zoomId = null
        awaitingStoredFolds = true
        // A body changed elsewhere renumbers every line; what was undone before no longer fits.
        undoStack.clear()
        redoStack.clear()
    }

    // 元に戻す・やり直し: whole documents (immutable, cheap to keep) with the caret that went with
    // them, pushed by [edit] — the one door every change comes through — and capped like the
    // memo editor's history. A caret move alone records nothing.
    private val undoStack = mutableStateListOf<Pair<OutlineDocument, FocusTarget?>>()
    private val redoStack = mutableStateListOf<Pair<OutlineDocument, FocusTarget?>>()
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    private fun currentFocus(): FocusTarget? = focusedId?.let { FocusTarget(it, focusedSelection.end) }

    private fun restore(snapshot: Pair<OutlineDocument, FocusTarget?>, onDocumentChange: (OutlineDocument) -> Unit) {
        val previous = document
        document = withIdFloor(snapshot.first)
        keepViewOnLines(previous)
        lastSerialized = OutlineText.serialize(document)
        snapshot.second?.takeIf { target -> document.entries.any { it.id == target.id } }?.let(::requestFocus)
        onDocumentChange(document)
    }

    fun undo(onDocumentChange: (OutlineDocument) -> Unit): HistoryStep {
        if (!writable) return HistoryStep.NONE
        val snapshot = undoStack.lastOrNull() ?: return HistoryStep.NONE
        if (overPhotoLimit(snapshot.first)) return HistoryStep.PHOTO_LIMIT
        undoStack.removeAt(undoStack.lastIndex)
        redoStack.add(document to currentFocus())
        restore(snapshot, onDocumentChange)
        return HistoryStep.APPLIED
    }

    fun redo(onDocumentChange: (OutlineDocument) -> Unit): HistoryStep {
        if (!writable) return HistoryStep.NONE
        val snapshot = redoStack.lastOrNull() ?: return HistoryStep.NONE
        if (overPhotoLimit(snapshot.first)) return HistoryStep.PHOTO_LIMIT
        redoStack.removeAt(redoStack.lastIndex)
        undoStack.add(document to currentFocus())
        restore(snapshot, onDocumentChange)
        return HistoryStep.APPLIED
    }

    /**
     * 元に戻す / やり直し never takes the outline above the photo cap (docs/OUTLINE_PHOTO_ROWS.md
     * §7.3): a step that would show more than [AttachmentLimits.MAX_PHOTOS_PER_RECORD] photos,
     * and more than are shown now, is not taken — nothing changes, the step stays where it is, and
     * the screen says why. Photos shown now are never taken away to make room.
     */
    private fun overPhotoLimit(next: OutlineDocument): Boolean {
        val after = OutlinePhotos.shown(next).size
        return after > AttachmentLimits.MAX_PHOTOS_PER_RECORD && after > OutlinePhotos.shown(document).size
    }

    /**
     * A fresh outline has no line to tap. This puts the first one there — an empty item,
     * already the live field — without writing anything: the body stays empty until words
     * are typed, so an outline opened and left at once carries no marker for it.
     */
    fun offerFirstLine() {
        // An empty body parses to one blank line, which is still nothing to tap.
        if (document.entries.any { it is OutlineNode }) return
        // The empty line keeps its lasting id; it only becomes an item to write in.
        val id = document.entries.firstOrNull()?.id ?: document.nextId
        document = withIdFloor(OutlineDocument(listOf(OutlineText.parseLine(id, FIRST_LINE)), maxOf(document.nextId, id + 1)))
        requestFocus(FocusTarget(id, caret = 0))
    }

    /** The folds remembered for this memo, applied to a freshly parsed document. */
    fun offerStoredFolds(keys: Set<String>) {
        if (!awaitingStoredFolds && foldsTouched) return
        awaitingStoredFolds = false
        collapsed = OutlineFoldKeys.restore(document, keys)
    }

    fun foldKeys(): Set<String> = OutlineFoldKeys.keysFor(document, collapsed)

    /** Applies [change] and hands the new body to [onDocumentChange] only if something changed. */
    fun edit(onDocumentChange: (OutlineDocument) -> Unit, change: (OutlineDocument) -> OutlineDocument) {
        if (!writable) return
        val updated = change(document)
        if (updated === document) return
        undoStack.add(document to currentFocus())
        if (undoStack.size > EDITOR_HISTORY_LIMIT) undoStack.removeAt(0)
        redoStack.clear()
        val previous = document
        document = withIdFloor(updated)
        keepViewOnLines(previous)
        lastSerialized = OutlineText.serialize(document)
        onDocumentChange(document)
    }

    /**
     * After a change of lines, the zoom and the folds name only lines that are still there
     * (2026-09-25). A zoom whose line is gone — joined into the line above by a Backspace at its
     * start, or taken away by an undo — stands on the nearest line it was nested under that is
     * still there (where 一つ上へ would go), or on the root when there is none; it never moves to
     * an unrelated line, and a zoom left on a missing line would stop the page from drawing.
     */
    private fun keepViewOnLines(previous: OutlineDocument) {
        fun present(id: Int) = document.entries.any { it.id == id && it is OutlineNode }
        zoomId?.let { zoom ->
            if (!present(zoom)) {
                zoomId = if (previous.entries.any { it.id == zoom }) {
                    OutlineEditing.ancestors(previous, zoom).map { it.id }.lastOrNull(::present)
                } else {
                    null
                }
            }
        }
        if (collapsed.any { !present(it) }) collapsed = collapsed.filterTo(mutableSetOf(), ::present)
    }

    fun toggleFold(id: Int) {
        collapsed = OutlineEditing.toggleFold(document, collapsed, id)
        foldsTouched = true
    }

    fun zoomInto(id: Int) {
        // A zoom stands on a line of words, never on a photo row (docs/OUTLINE_PHOTO_ROWS.md).
        if ((document.entries.firstOrNull { it.id == id } as? OutlineNode)?.isPhoto == true) return
        zoomId = id
    }

    /** One level up: the parent becomes the zoom, or the root when there is no parent. */
    fun zoomOut() {
        val current = zoomId ?: return
        zoomId = OutlineEditing.ancestors(document, current).lastOrNull()?.id
    }

    fun zoomTo(id: Int?) {
        zoomId = id
    }

    /**
     * The zoom as something that outlives this session's ids: the same content key a fold is
     * kept by (depth, words, occurrence — §OutlineFoldKeys), never the line's position or its
     * parse-time id. Null when not zoomed.
     */
    fun zoomKey(): String? = zoomId?.let { OutlineFoldKeys.keyFor(document, it) }

    /** The first line on screen — what is remembered as the place to come back to. */
    var scrollAnchorId: Int? by mutableStateOf(null)
    /** A line to scroll to as soon as it is on the list — the remembered place, once. */
    var pendingScrollId: Int? by mutableStateOf(null)
    fun scrollKey(): String? = scrollAnchorId?.let { OutlineFoldKeys.keyFor(document, it) }
    fun restoreScroll(key: String?) {
        pendingScrollId = key?.let { OutlineFoldKeys.restore(document, setOf(it)).firstOrNull() }
    }

    /**
     * Zooms back to the line [key] names in the current document. When no line answers to it —
     * the words were rewritten, the line was removed — the outliner stands at the root: being
     * unzoomed is safe, being zoomed on the wrong line is not.
     */
    fun restoreZoom(key: String?) {
        zoomId = key?.let { OutlineFoldKeys.restore(document, setOf(it)).firstOrNull() }
    }
}

@Composable
fun OutlinerScreen(
    session: OutlinerSession,
    loading: Boolean,
    onBack: () -> Unit,
    onDocumentChange: (OutlineDocument) -> Unit,
    onFoldsChanged: () -> Unit = {},
    toolbar: List<EditorToolbarItem> = EditorToolbarOrder.itemsFor(EditorToolbarSurface.OUTLINER),
    toolbarLower: Set<EditorToolbarItem> = EditorToolbarOrder.OutlinerLowerRow,
    toolbarTwoRows: Boolean = false,
    symbols: OutlineSymbolSelection = OutlineSymbolSelection.Default,
    chipLabel: OutlineChipLabel = OutlineChipLabel.SYMBOL_AND_WORD,
    labelChips: ToolbarChipArrangement = ToolbarChips.defaultArrangement(ToolbarChipGroup.LABELS),
    flowChips: ToolbarChipArrangement = ToolbarChips.defaultArrangement(ToolbarChipGroup.FLOW),
    playback: OutlinerPlaybackUi? = null,
    aids: OutlinerAids? = null,
    snackbarHost: @Composable () -> Unit = {},
    /** 元に戻す / やり直し was refused for the photo cap: the screen says so. */
    onPhotoLimit: () -> Unit = {},
    /**
     * A line above the outline that stops the writing: the outline was written elsewhere (§9).
     * While it stands the lines are read-only and no change is made.
     */
    notice: (@Composable () -> Unit)? = null,
) {
    session.writable = notice == null
    val document = session.document
    // The photo row whose picture is open full screen.
    var photoViewer by remember { mutableStateOf<Int?>(null) }
    val focusedId = session.focusedId
    val zoomId = session.zoomId
    val visible = remember(document, session.collapsed, zoomId) {
        OutlineEditing.visible(document, session.collapsed, zoomId)
    }
    val parents = remember(document) { OutlineEditing.nodesWithChildren(document) }
    val listState = rememberLazyListState()
    // A bullet held and carried. The rows stay where they are while the finger moves; a dot
    // and a line show where the line will land and how deep; the move happens on release, and
    // the carried row settles into its new place while its neighbours slide (animateItem).
    var drag by remember { mutableStateOf<OutlineDragState?>(null) }
    var settlingId by remember { mutableStateOf<Int?>(null) }
    val settle = remember { Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val depthStepPx = with(density) { DEPTH_STEP.toPx() }
    val depthOffset = zoomId?.let { id -> (document.entries.firstOrNull { it.id == id } as? OutlineNode)?.depth } ?: 0
    fun itemTop(id: Int): Float? = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.offset?.toFloat()
    fun dropTarget(d: OutlineDragState): DropTarget {
        val rows = listState.layoutInfo.visibleItemsInfo.filter { val k = it.key; k is Int && k !in d.blockIds && k != zoomId }
        val below = rows.firstOrNull { it.offset + it.size / 2f > d.pointerY }
        val above = if (below != null) rows.takeWhile { it !== below }.lastOrNull() else rows.lastOrNull()
        val afterId = above?.key as? Int
        val wanted = d.originalDepth + (d.dx / depthStepPx).toInt()
        val depth = OutlineEditing.landingDepth(document, d.id, afterId, wanted, session.collapsed, zoomId)
        val lineY = below?.offset?.toFloat() ?: above?.let { (it.offset + it.size).toFloat() } ?: 0f
        return DropTarget(afterId, depth, lineY)
    }
    fun release() {
        val d = drag ?: return
        val target = dropTarget(d)
        val draggedTop = itemTop(d.id) ?: (d.pointerY - d.grabY)
        val blockHeight = listState.layoutInfo.visibleItemsInfo.filter { it.key in d.blockIds }.sumOf { it.size }
        // Where the row will stand after the move: the line, less its own block when it comes from above.
        val slotTop = if (target.lineY > draggedTop) target.lineY - blockHeight else target.lineY
        val residual = (d.pointerY - d.grabY) - slotTop
        drag = null
        session.edit(onDocumentChange) { OutlineEditing.relocate(it, d.id, target.afterId ?: zoomId, target.depth) }
        settlingId = d.id
        dragScope.launch {
            settle.snapTo(residual)
            settle.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
            if (settlingId == d.id) settlingId = null
        }
    }
    // Near the top or bottom edge the list scrolls under the finger for as long as it stays
    // there — and only then: with the finger in the middle nothing runs, so the screen is idle.
    val edgePx = with(density) { AUTOSCROLL_EDGE.toPx() }
    val autoScroll by remember {
        derivedStateOf {
            val d = drag ?: return@derivedStateOf 0
            val info = listState.layoutInfo
            val y = d.pointerY - info.viewportStartOffset
            when {
                y < edgePx -> -1
                y > info.viewportSize.height - edgePx -> 1
                else -> 0
            }
        }
    }
    LaunchedEffect(autoScroll) {
        if (autoScroll == 0) return@LaunchedEffect
        val step = with(density) { AUTOSCROLL_STEP.toPx() } * autoScroll
        while (isActive) {
            listState.scrollBy(step)
            withFrameNanos { }
        }
    }
    val visibleNow = rememberUpdatedState(visible)
    // The first line on screen is the place to come back to; a remembered place is taken once
    // its line is on the list (after the folds have been applied).
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index -> session.scrollAnchorId = (visibleNow.value.getOrNull(index) as? OutlineNode)?.id }
    }
    LaunchedEffect(session.pendingScrollId, visible) {
        val id = session.pendingScrollId ?: return@LaunchedEffect
        val index = visible.indexOfFirst { it.id == id }
        if (index >= 0) {
            listState.scrollToItem(index)
            session.pendingScrollId = null
        }
    }
    fun focusedNode(): OutlineNode? =
        focusedId?.let { id -> document.entries.firstOrNull { it.id == id } as? OutlineNode }
    // The bar's writing aids act on the live line where its caret or selection is: the aid
    // rewrites the line's words (a template may bring lines of its own — replaceText splits
    // them) and the caret follows the result.
    val selection = session.focusedSelection
    val liveLine = focusedNode()
    val lineEdit = liveLine?.let { n ->
        OutlineEdit(n.text, selection.start.coerceIn(0, n.text.length), selection.end.coerceIn(0, n.text.length))
    }
    val applyToText: ((OutlineEdit) -> OutlineEdit) -> Unit = { op ->
        val n = liveLine
        val e = lineEdit
        if (n != null && e != null) {
            val result = op(e)
            if (result.text != e.text) {
                var change: OutlineEditing.TextChange? = null
                session.edit(onDocumentChange) { current ->
                    OutlineEditing.replaceText(current, n.id, result.text, result.selectionEnd).also { change = it }.document
                }
                change?.let { session.requestFocus(FocusTarget(it.focusId, it.caret)) }
            } else {
                session.requestFocus(FocusTarget(n.id, result.selectionEnd))
            }
        }
    }
    // The bar raises one panel at a time: another tool swaps it, the same tool closes it.
    var shortcutPanel by remember { mutableStateOf<EditorShortcutPanel?>(null) }

    Scaffold(
        modifier = Modifier.testTag("outliner_screen"),
        // No title: the outline names itself, and the bar is only the way back.
        topBar = {
            ProductTopBar(title = null, onBack = onBack, actions = { playback?.topActions?.invoke(this) })
        },
        snackbarHost = snackbarHost,
        bottomBar = {
            val readingBar = playback?.readingBar
            if (!loading && readingBar != null) {
                readingBar()
            } else if (!loading) {
                val node = focusedNode()
                OutlinerToolbar(
                    canIndent = node != null && OutlineEditing.canIndent(document, node.id),
                    canOutdent = node != null && OutlineEditing.canOutdent(document, node.id, zoomId),
                    canMoveUp = node != null && OutlineEditing.canMoveUp(document, node.id),
                    canMoveDown = node != null && OutlineEditing.canMoveDown(document, node.id),
                    folded = node != null && node.id in session.collapsed,
                    foldable = node != null &&
                        (node.id in session.collapsed || OutlineEditing.hasChildren(document, node.id)),
                    canZoom = node != null && node.id != zoomId,
                    onIndent = { node?.let { n -> session.edit(onDocumentChange) { OutlineEditing.indent(it, n.id) } } },
                    onOutdent = {
                        node?.let { n -> session.edit(onDocumentChange) { OutlineEditing.outdent(it, n.id, zoomId) } }
                    },
                    onMoveUp = { node?.let { n -> session.edit(onDocumentChange) { OutlineEditing.moveUp(it, n.id) } } },
                    onMoveDown = {
                        node?.let { n -> session.edit(onDocumentChange) { OutlineEditing.moveDown(it, n.id) } }
                    },
                    onToggleFold = {
                        node?.let { n ->
                            session.toggleFold(n.id)
                            onFoldsChanged()
                        }
                    },
                    onZoom = { node?.let { n -> session.zoomInto(n.id) } },
                    items = toolbar,
                    lower = toolbarLower,
                    twoRows = toolbarTwoRows,
                    line = node,
                    symbols = symbols,
                    chipLabel = chipLabel,
                    labelChips = labelChips,
                    flowChips = flowChips,
                    // A line tool rewrites the caret's line the way the memo editor's bar would.
                    onRewriteLine = { transform ->
                        node?.let { n -> session.edit(onDocumentChange) { OutlineEditing.rewriteLine(it, n.id, transform) } }
                    },
                    canUndo = session.canUndo,
                    canRedo = session.canRedo,
                    onUndo = { if (session.undo(onDocumentChange) == HistoryStep.PHOTO_LIMIT) onPhotoLimit() },
                    onRedo = { if (session.redo(onDocumentChange) == HistoryStep.PHOTO_LIMIT) onPhotoLimit() },
                    onAddPhoto = { aids?.onAddPhoto?.invoke() },
                    lineEdit = lineEdit,
                    onApplyText = applyToText,
                    onPickTemplate = { shortcutPanel = EditorShortcutPanel.toggle(shortcutPanel, EditorShortcutPanel.TEMPLATE) },
                    onPickLink = { shortcutPanel = EditorShortcutPanel.toggle(shortcutPanel, EditorShortcutPanel.MEMO_LINK) },
                    onPickCommentLink = { shortcutPanel = EditorShortcutPanel.toggle(shortcutPanel, EditorShortcutPanel.COMMENT_LINK) },
                    end = { playback?.toolbarEnd?.invoke(this) },
                )
            }
        },
    ) { padding ->
        if (loading) return@Scaffold
        val reading = playback?.reading
        val frame: @Composable (@Composable () -> Unit) -> Unit = playback?.frame ?: { content -> content() }
        frame {
        Column(Modifier.fillMaxSize().padding(padding)) {
            notice?.invoke()
            if (zoomId != null && reading == null) {
                ZoomBreadcrumb(
                    ancestors = OutlineEditing.ancestors(document, zoomId),
                    current = document.entries.firstOrNull { it.id == zoomId } as? OutlineNode,
                    onZoomTo = session::zoomTo,
                )
            }
            playback?.stage?.invoke()
            // While comments fly the lines hold still under them, as the memo's body does.
            CompositionLocalProvider(LocalOutlinerReadOnly provides (playback?.readOnly == true || notice != null)) {
            Box(Modifier.fillMaxSize().onSizeChanged { size -> playback?.onBoundsChanged?.invoke(size) }) {
            // 閲覧モード reads the outline as the memo's page in place of the lines; the stage and
            // the flight around it stay where they are.
            if (reading != null) reading() else LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("outliner_list"),
                contentPadding = PaddingValues(top = ProductSpacing.sm, bottom = ProductSpacing.xxl),
            ) {
                items(
                    items = visible,
                    key = OutlineEntry::id,
                    contentType = { if (it is OutlineBlank) "blank" else "line" },
                ) { entry ->
                    when (entry) {
                        is OutlineBlank -> Spacer(
                            Modifier.height(ProductSpacing.md).testTag("outliner_blank_${entry.id}"),
                        )
                        is OutlineNode -> if (entry.isPhoto) {
                            // A photo row: a line of the outline of its own, drawn as the picture.
                            val photosById = aids?.photos.orEmpty().associateBy { it.id }
                            OutlinePhotoRow(
                                node = entry,
                                photo = entry.photoAttachmentId?.let(photosById::get),
                                imageLoader = aids?.imageLoader,
                                depthOffset = zoomId?.let { id -> (document.entries.firstOrNull { it.id == id } as? OutlineNode)?.depth } ?: 0,
                                editable = playback?.readOnly != true && notice == null,
                                canMoveUp = OutlineEditing.canMoveUp(document, entry.id),
                                canMoveDown = OutlineEditing.canMoveDown(document, entry.id),
                                canIndent = OutlineEditing.canIndent(document, entry.id),
                                canOutdent = OutlineEditing.canOutdent(document, entry.id, zoomId),
                                onFullScreen = { photoViewer = entry.id },
                                onMoveUp = { session.edit(onDocumentChange) { OutlineEditing.moveUp(it, entry.id) } },
                                onMoveDown = { session.edit(onDocumentChange) { OutlineEditing.moveDown(it, entry.id) } },
                                onIndent = { session.edit(onDocumentChange) { OutlineEditing.indent(it, entry.id) } },
                                onOutdent = { session.edit(onDocumentChange) { OutlineEditing.outdent(it, entry.id, zoomId) } },
                                onDelete = { session.edit(onDocumentChange) { OutlineEditing.removePhoto(it, entry.id) } },
                                modifier = Modifier.animateItem(),
                            )
                        } else OutlineNodeRow(
                            node = entry,
                            // The carried row and the one settling place themselves; the rest slide.
                            modifier = if (drag?.id == entry.id || settlingId == entry.id) Modifier else Modifier.animateItem(),
                            carried = drag?.id == entry.id,
                            lift = when {
                                drag?.id == entry.id -> drag!!.let { d -> d.pointerY - d.grabY - (itemTop(entry.id) ?: 0f) }
                                settlingId == entry.id -> settle.value
                                else -> 0f
                            },
                            onGrab = { grabY ->
                                drag = OutlineDragState(
                                    id = entry.id,
                                    blockIds = OutlineEditing.blockIds(document, entry.id),
                                    grabY = grabY,
                                    originalDepth = entry.depth,
                                    pointerY = (itemTop(entry.id) ?: 0f) + grabY,
                                )
                            },
                            onCarry = { dx, dy -> drag?.let { it.dx += dx; it.pointerY += dy } },
                            onRelease = { release() },
                            // Inside a zoom the root stands at the margin; its lines step in from it.
                            depthOffset = zoomId?.let { id ->
                                (document.entries.firstOrNull { it.id == id } as? OutlineNode)?.depth
                            } ?: 0,
                            hasChildren = entry.id in parents,
                            folded = entry.id in session.collapsed && entry.id != zoomId,
                            isZoomRoot = entry.id == zoomId,
                            // One live text field: the tapped line. Every other line is
                            // plain text, which is what lets a thousand of them scroll.
                            editing = entry.id == focusedId,
                            pendingCaret = session.pendingFocus?.takeIf { it.id == entry.id }?.caret,
                            onCaretPlaced = { session.pendingFocus = null },
                            onTapAt = { caret -> session.requestFocus(FocusTarget(entry.id, caret)) },
                            onFocused = { session.focusedId = entry.id },
                            onToggleFold = {
                                session.toggleFold(entry.id)
                                onFoldsChanged()
                            },
                            hiddenCount = if (entry.id in session.collapsed && entry.id != zoomId) OutlineEditing.hiddenCount(document, entry.id) else 0,
                            onZoom = { if (entry.id != zoomId) session.zoomInto(entry.id) },
                            onToggleTask = { session.edit(onDocumentChange) { OutlineEditing.toggleTask(it, entry.id) } },
                            // A tap on the rail folds the line it hangs from: the ancestor at that level.
                            onRailTap = { level ->
                                val offset = zoomId?.let { id -> (document.entries.firstOrNull { it.id == id } as? OutlineNode)?.depth } ?: 0
                                OutlineEditing.ancestors(document, entry.id).firstOrNull { it.depth == offset + level }?.let { parent ->
                                    session.toggleFold(parent.id)
                                    onFoldsChanged()
                                }
                            },
                            onIndent = { session.edit(onDocumentChange) { OutlineEditing.indent(it, entry.id) } },
                            onOutdent = { session.edit(onDocumentChange) { OutlineEditing.outdent(it, entry.id, zoomId) } },
                            onSelectionChange = { session.focusedSelection = it },
                            onTextChange = { text ->
                                val newline = text.indexOf('\n')
                                if (newline < 0) {
                                    session.edit(onDocumentChange) { OutlineEditing.updateText(it, entry.id, text) }
                                } else {
                                    // Enter: the words before the newline stay, the rest start the new line —
                                    // except on an empty nested line, where Enter steps the line out instead,
                                    // the way WorkFlowy closes a branch.
                                    val before = text.substring(0, newline)
                                    val after = text.substring(newline + 1).replace("\n", "")
                                    session.edit(onDocumentChange) { current ->
                                        if (before.isBlank() && after.isBlank() && OutlineEditing.canOutdent(current, entry.id, zoomId)) {
                                            return@edit OutlineEditing.outdent(current, entry.id, zoomId)
                                        }
                                        val kept = OutlineEditing.updateText(current, entry.id, before)
                                        val insertion = OutlineEditing.insertAfter(kept, entry.id, text = after)
                                        session.requestFocus(FocusTarget(insertion.newId, caret = 0))
                                        insertion.document
                                    }
                                }
                            },
                            onBackspaceAtStart = {
                                session.edit(onDocumentChange) { current ->
                                    val deletion = OutlineEditing.deleteBackward(current, entry.id)
                                    if (deletion.document !== current) {
                                        deletion.focusId?.let { session.requestFocus(FocusTarget(it, deletion.caret)) }
                                    }
                                    deletion.document
                                }
                            },
                        )
                    }
                }
                // The + under the last line, where WorkFlowy keeps it: a new item at the end of
                // what is shown — the document, or the zoomed branch. Only once there are lines:
                // the list anchors its position to the first item's key, and a + standing alone
                // before the body arrives would carry that anchor to the bottom.
                if (visible.isNotEmpty()) item(key = "append", contentType = "append") {
                    AppendRow(
                        indentSteps = if (zoomId != null) 1 else 0,
                        onAppend = {
                            session.edit(onDocumentChange) { current ->
                                val insertion = OutlineEditing.appendLine(current, zoomId)
                                session.requestFocus(FocusTarget(insertion.newId, caret = 0))
                                insertion.document
                            }
                        },
                    )
                }
            }
            drag?.let { d ->
                val target = dropTarget(d)
                val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
                val lineColor = MaterialTheme.colorScheme.primary
                Canvas(Modifier.matchParentSize().testTag("outliner_drop_line")) {
                    val y = target.lineY - listState.layoutInfo.viewportStartOffset
                    val steps = (target.depth - depthOffset).coerceAtLeast(0)
                    val x = (ProductSize.screenHorizontalPadding + DEPTH_STEP * steps + BULLET_SIZE / 2).toPx()
                    drawCircle(dotColor, radius = (BULLET_DOT / 2).toPx(), center = Offset(x, y))
                    drawLine(lineColor.copy(alpha = 0.6f), Offset(x + BULLET_DOT.toPx(), y), Offset(size.width - ProductSize.screenHorizontalPadding.toPx(), y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            playback?.overlays?.invoke(this)
            photoViewer?.let { rowId ->
                val photosById = aids?.photos.orEmpty().associateBy { it.id }
                val ordered = document.entries.mapNotNull { (it as? OutlineNode)?.photoAttachmentId?.let(photosById::get) }
                val start = (document.entries.firstOrNull { it.id == rowId } as? OutlineNode)?.photoAttachmentId
                val loader = aids?.imageLoader
                if (ordered.isEmpty() || loader == null) {
                    photoViewer = null
                } else {
                    PhotoViewer(
                        photos = ordered,
                        initialIndex = ordered.indexOfFirst { it.id == start }.coerceAtLeast(0),
                        imageLoader = loader,
                        // Taking a photo out is the row's 削除, so 元に戻す can bring it back.
                        editable = false,
                        onDismiss = { photoViewer = null },
                        onDelete = {},
                    )
                }
            }
            }
            }
        }
        }
        playback?.sheets?.invoke()
        // The pickers keep the keyboard and stand at the bottom of the content — above the bar, not
        // under it — so they get the same box the memo editor gives them.
        Box(Modifier.fillMaxSize().padding(padding)) {
        aids?.let { a ->
            when (shortcutPanel) {
                EditorShortcutPanel.TEMPLATE -> MemoTemplateSheet(
                    templates = a.templates,
                    onCreate = a.onCreateTemplate,
                    onPick = { template ->
                        shortcutPanel = null
                        applyToText { WorkOutlineEditing.insert(it, template.body) }
                    },
                    onDelete = a.onDeleteTemplate,
                    onDismiss = { shortcutPanel = null },
                )
                EditorShortcutPanel.MEMO_LINK -> MemoLinkPickerSheet(
                    targets = a.linkTargets,
                    onPick = { title ->
                        shortcutPanel = null
                        applyToText { NoteLink.insert(it, title) }
                    },
                    onDismiss = { shortcutPanel = null },
                )
                EditorShortcutPanel.COMMENT_LINK -> CommentLinkPickerSheet(
                    comments = a.comments,
                    onPick = { comment ->
                        shortcutPanel = null
                        val insertMarker = { number: Int -> applyToText { WorkOutlineEditing.insert(it, "[R$number]") } }
                        val number = comment.linkNo
                        if (number != null) {
                            insertMarker(number)
                        } else {
                            a.onLinkComment(comment) { assigned ->
                                if (assigned != null) insertMarker(assigned) else a.onMessage("リンクを設定できませんでした")
                            }
                        }
                    },
                    onOpenComments = {
                        shortcutPanel = null
                        a.onOpenComments()
                    },
                    onDismiss = { shortcutPanel = null },
                )
                null -> Unit
            }
        }
        }
    }
}

/** Comments are flying: every line is read, none is written, until they have passed. */
private val LocalOutlinerReadOnly = compositionLocalOf { false }

@Composable
private fun ZoomBreadcrumb(
    ancestors: List<OutlineNode>,
    current: OutlineNode?,
    onZoomTo: (Int?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ProductSize.minimumTouchTarget)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = ProductSpacing.lg)
            .testTag("outliner_breadcrumb"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BreadcrumbStep(label = "すべて", description = "ズームを解除して全体を表示", onClick = { onZoomTo(null) })
        ancestors.forEach { ancestor ->
            BreadcrumbSeparator()
            BreadcrumbStep(
                label = ancestor.readableText.ifBlank { "（無題）" },
                description = "${ancestor.readableText}へズーム",
                onClick = { onZoomTo(ancestor.id) },
                testTag = "outliner_crumb_${ancestor.id}",
            )
        }
        if (current != null) {
            BreadcrumbSeparator()
            Text(
                text = current.readableText.ifBlank { "（無題）" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("outliner_crumb_current"),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun BreadcrumbStep(
    label: String,
    description: String,
    onClick: () -> Unit,
    testTag: String = "outliner_crumb_root",
) {
    // A centred box around the label: the words sit on the current name's line, not at the
    // top of the 48dp touch target.
    Box(
        modifier = Modifier
            .heightIn(min = ProductSize.minimumTouchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = ProductSpacing.xs)
            .semantics { contentDescription = description }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("${testTag}_label"),
        )
    }
}

@Composable
private fun BreadcrumbSeparator() {
    Text(
        text = "›",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = ProductSpacing.xs),
    )
}

@Composable
private fun OutlineNodeRow(
    node: OutlineNode,
    modifier: Modifier = Modifier,
    depthOffset: Int,
    hasChildren: Boolean,
    folded: Boolean,
    isZoomRoot: Boolean,
    editing: Boolean,
    pendingCaret: Int?,
    onCaretPlaced: () -> Unit,
    onTapAt: (Int) -> Unit,
    onFocused: () -> Unit,
    onToggleFold: () -> Unit,
    hiddenCount: Int,
    onZoom: () -> Unit,
    onToggleTask: () -> Unit,
    onRailTap: (Int) -> Unit,
    onIndent: () -> Unit,
    onOutdent: () -> Unit,
    carried: Boolean,
    lift: Float,
    onGrab: (Float) -> Unit,
    onCarry: (Float, Float) -> Unit,
    onRelease: () -> Unit,
    onTextChange: (String) -> Unit,
    onSelectionChange: (TextRange) -> Unit,
    onBackspaceAtStart: () -> Unit,
) {
    val isTask = node.role == OutlineSymbolRole.TASK || node.role == OutlineSymbolRole.TASK_DONE
    val indentSteps = (node.depth - depthOffset).coerceAtLeast(0)
    // The rail: a guide for every ancestor level, full height — a parent's own guide begins
    // below its row, so it stands clear of its bullet the way it stands clear of the last child's.
    val guides = OutlineGuides.levels(indentSteps)
    // Faint, the way WorkFlowy draws it: the rail guides the eye and never competes with the words.
    val guideColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
    val carriedFill = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    val liftPx = with(LocalDensity.current) { DRAG_LIFT.toPx() }
    val cornerPx = with(LocalDensity.current) { 12.dp.toPx() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // A carried row rides the finger, lifted and tinted like a card, above its neighbours;
            // one just let go settles into its place. The tag sits inside the layer, so what a
            // test (or TalkBack) sees is the row where it is drawn.
            .zIndex(if (carried || lift != 0f) 1f else 0f)
            .graphicsLayer {
                translationY = lift
                shadowElevation = if (carried) liftPx else 0f
                shape = RoundedCornerShape(cornerPx)
                clip = false
            }
            .testTag("outliner_row_${node.id}")
            .drawBehind {
                if (carried) drawRoundRect(carriedFill, cornerRadius = CornerRadius(cornerPx))
                val stroke = 1.dp.toPx()
                guides.forEach { level ->
                    val x = (ProductSize.screenHorizontalPadding + DEPTH_STEP * level + BULLET_SIZE / 2).toPx()
                    drawLine(guideColor, Offset(x, 0f), Offset(x, size.height), stroke)
                }
            }
            .padding(end = ProductSize.screenHorizontalPadding)
            .height(IntrinsicSize.Min),
        // Top-aligned: the bullet, the count and the fold each stand one line tall, so they sit
        // on the first line of the words even when the words wrap.
        verticalAlignment = Alignment.Top,
    ) {
        // The rail stands where the start margin was: its own element, so a tap on a guide is a
        // direct hit that beats the bullet's widened touch target next to it. A tap folds the
        // line the tapped guide hangs from.
        Box(
            modifier = Modifier
                .width(ProductSize.screenHorizontalPadding + DEPTH_STEP * indentSteps)
                .fillMaxHeight()
                .pointerInput(indentSteps) {
                    if (indentSteps == 0) return@pointerInput
                    val first = (ProductSize.screenHorizontalPadding + BULLET_SIZE / 2).toPx()
                    detectTapGestures { position ->
                        val level = ((position.x - first) / DEPTH_STEP.toPx()).roundToInt().coerceIn(0, indentSteps - 1)
                        onRailTap(level)
                    }
                },
        )
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Bullet(
                node = node,
                folded = folded && !isZoomRoot,
                zoomable = !isZoomRoot,
                onZoom = onZoom,
                onToggleTask = onToggleTask,
                onGrab = onGrab,
                onCarry = onCarry,
                onRelease = onRelease,
            )
        }
        if (isTask) {
            // The box beside the dot: small, in the dots' grey, on the first line of the words; a tap
            // ticks the line, as on the memo's reading page.
            Icon(
                if (node.role == OutlineSymbolRole.TASK_DONE) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                contentDescription = if (node.role == OutlineSymbolRole.TASK_DONE) "完了。未完了に戻す" else "未完了。完了にする",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .height(LINE_ROW)
                    .width(TASK_BOX + ProductSpacing.sm)
                    .padding(end = ProductSpacing.sm)
                    .wrapContentHeight()
                    .clickable(role = Role.Checkbox, onClick = onToggleTask)
                    .testTag("outliner_task_box_${node.id}"),
            )
        }
        Spacer(Modifier.width(ProductSpacing.sm))
        if (editing) {
            EditableLine(
                node = node,
                pendingCaret = pendingCaret,
                onCaretPlaced = onCaretPlaced,
                onFocused = onFocused,
                onTextChange = onTextChange,
                onSelectionChange = onSelectionChange,
                onBackspaceAtStart = onBackspaceAtStart,
                onIndent = onIndent,
                onOutdent = onOutdent,
                modifier = Modifier.weight(1f),
            )
        } else {
            // A tap turns the line into the one live field, with the caret where the tap
            // landed on the words — a fresh line has nothing to land on, so at its end.
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            val done = node.role == OutlineSymbolRole.TASK_DONE
            Text(
                text = node.text,
                style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (done) TextDecoration.LineThrough else null),
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                onTextLayout = { layout = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = ProductSpacing.sm)
                    .heightIn(min = LINE_MIN_HEIGHT)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .pointerInput(node.id) {
                        detectTapGestures { position ->
                            onTapAt(layout?.getOffsetForPosition(position) ?: node.text.length)
                        }
                    }
                    .semantics {
                        contentDescription = "アウトラインの行"
                        if (done) stateDescription = "完了"
                    }
                    .testTag("outliner_node_${node.id}"),
            )
        }
        if (folded && !isZoomRoot && hiddenCount > 0) {
            // What a closed branch is keeping out of sight.
            Text(
                text = "+$hiddenCount",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.height(LINE_ROW).wrapContentHeight().padding(horizontal = ProductSpacing.xs).testTag("outliner_hidden_${node.id}"),
            )
        }
        if ((hasChildren || folded) && !isZoomRoot) {
            // The fold sits at the line's right edge, clear of the words, as WorkFlowy keeps it.
            // A plain clickable, not an IconButton: hundreds of these scroll past, and the
            // button's ripple and indication machinery were a measurable part of each row's
            // cost on a phone. It keeps the button role for TalkBack.
            Icon(
                imageVector = if (folded) {
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight
                } else {
                    Icons.Outlined.ExpandMore
                },
                contentDescription = if (folded) "この行の下を展開する" else "この行の下を折りたたむ",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .width(FOLD_SIZE)
                    .height(LINE_ROW)
                    .clickable(role = Role.Button, onClick = onToggleFold)
                    .padding(horizontal = ProductSpacing.sm, vertical = (LINE_ROW - FOLD_SIZE) / 2 + ProductSpacing.sm)
                    .testTag("outliner_fold_${node.id}"),
            )
        }
    }
}

/**
 * The bullet: a dot for a plain list marker (`-`, `*`, `+`, `・`), ringed while the line is
 * folded so a closed branch reads as one; any other marker (a heading, a task box, a number)
 * shows as written. A tap zooms into the line — or ticks it, when it is a task; a long press
 * picks the line up to carry it anywhere.
 */
@Composable
private fun Bullet(
    node: OutlineNode,
    folded: Boolean,
    zoomable: Boolean,
    onZoom: () -> Unit,
    onToggleTask: () -> Unit,
    onGrab: (Float) -> Unit,
    onCarry: (Float, Float) -> Unit,
    onRelease: () -> Unit,
) {
    val isTask = node.role == OutlineSymbolRole.TASK || node.role == OutlineSymbolRole.TASK_DONE
    val tapLabel = when {
        isTask && node.role == OutlineSymbolRole.TASK -> "完了にする"
        isTask -> "未完了に戻す"
        else -> "この行にズーム"
    }
    Box(
        modifier = Modifier
            .width(BULLET_SIZE)
            .height(LINE_ROW)
            .pointerInput(node.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { position -> onGrab(position.y) },
                    onDragEnd = onRelease,
                    onDragCancel = onRelease,
                ) { change, amount ->
                    change.consume()
                    onCarry(amount.x, amount.y)
                }
            }
            .clickable(
                enabled = isTask || zoomable,
                role = Role.Button,
                onClickLabel = tapLabel,
                onClick = { if (isTask) onToggleTask() else onZoom() },
            )
            .testTag("outliner_bullet_${node.id}"),
        contentAlignment = Alignment.Center,
    ) {
        val glyph = node.glyph
        // A task line keeps the dot every line has — 「・□」, so it reads like its neighbours; the
        // box itself is drawn by the row, right after this bullet.
        if (isTask || glyph.isEmpty() || glyph in PLAIN_BULLETS) {
            val dot = MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                modifier = Modifier
                    .size(if (folded) BULLET_RING else BULLET_DOT)
                    .background(if (folded) dot.copy(alpha = 0.25f) else dot, CircleShape)
                    .testTag("outliner_glyph_${node.id}"),
                contentAlignment = Alignment.Center,
            ) {
                if (folded) Box(Modifier.size(BULLET_DOT).background(dot, CircleShape))
            }
        } else {
            Text(
                text = glyph,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("outliner_glyph_${node.id}"),
            )
        }
    }
}

/** A bullet held and carried: which line, where the finger is (in the list's own offsets), how far it has moved sideways. */
private class OutlineDragState(
    val id: Int,
    val blockIds: Set<Int>,
    val grabY: Float,
    val originalDepth: Int,
    pointerY: Float,
) {
    var pointerY by mutableFloatStateOf(pointerY)
    var dx by mutableFloatStateOf(0f)
}

/** Where a carried line would land now: after which line, how deep, and the y of the line between rows. */
private data class DropTarget(val afterId: Int?, val depth: Int, val lineY: Float)

@Composable
private fun EditableLine(
    node: OutlineNode,
    pendingCaret: Int?,
    onCaretPlaced: () -> Unit,
    onFocused: () -> Unit,
    onTextChange: (String) -> Unit,
    onSelectionChange: (TextRange) -> Unit,
    onBackspaceAtStart: () -> Unit,
    onIndent: () -> Unit,
    onOutdent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A ticked task reads as done while it is edited too: struck through, in the quiet grey.
    val done = node.role == OutlineSymbolRole.TASK_DONE
    // The field keeps its own selection; the node's text is the value. When the text changes
    // from outside (a join does) the caret lands at the end unless a caret was asked for.
    var value by remember(node.id) { mutableStateOf(TextFieldValue(node.text, TextRange(node.text.length))) }
    if (value.text != node.text) value = TextFieldValue(node.text, TextRange(node.text.length))
    // The requester lives with the field, so it is attached by the time it is asked: the
    // line became the live field because it was chosen, and it takes the focus itself.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(node.id) { focusRequester.requestFocus() }
    LaunchedEffect(pendingCaret) {
        val caret = pendingCaret ?: return@LaunchedEffect
        value = TextFieldValue(node.text, TextRange(caret.coerceIn(0, node.text.length)))
        onSelectionChange(value.selection)
        onCaretPlaced()
    }
    BasicTextField(
        value = value,
        onValueChange = { changed ->
            // Judged against the field's own last value, not the node's: when a line stops
            // being the live field the platform can report its buffer once more with the
            // same text, and a second Enter would otherwise add a second line.
            val previous = value
            value = changed
            onSelectionChange(changed.selection)
            if (changed.text != previous.text) onTextChange(changed.text)
        },
        // Bold and highlights show as what they mean, not their markers — the memo editor's own drawing.
        visualTransformation = rememberInlineMarkupTransformation(),
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (done) TextDecoration.LineThrough else null,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        readOnly = LocalOutlinerReadOnly.current,
        modifier = modifier
            .padding(vertical = ProductSpacing.sm)
            .heightIn(min = LINE_MIN_HEIGHT)
            .wrapContentHeight(Alignment.CenterVertically)
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            // Backspace with nothing before the caret is a structural edit, not a text
            // change, so the field's value never shows it: it is read from the key. Soft
            // keyboards send it as a key event when the field has nothing to delete.
            .onPreviewKeyEvent { event ->
                val atStart = value.selection.collapsed && value.selection.start == 0
                when {
                    event.type == KeyEventType.KeyDown && event.key == Key.Backspace && atStart -> {
                        onBackspaceAtStart()
                        true
                    }
                    // A hardware keyboard: Tab steps the line in, Shift+Tab steps it out.
                    event.type == KeyEventType.KeyDown && event.key == Key.Tab -> {
                        if (event.isShiftPressed) onOutdent() else onIndent()
                        true
                    }
                    else -> false
                }
            }
            .semantics {
                contentDescription = "アウトラインの行"
                if (done) stateDescription = "完了"
            }
            .testTag("outliner_node_${node.id}"),
    )
}

@Composable
private fun OutlinerToolbar(
    canIndent: Boolean,
    canOutdent: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    foldable: Boolean,
    folded: Boolean,
    canZoom: Boolean,
    onIndent: () -> Unit,
    onOutdent: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleFold: () -> Unit,
    onZoom: () -> Unit,
    items: List<EditorToolbarItem>,
    lower: Set<EditorToolbarItem>,
    twoRows: Boolean,
    line: OutlineNode?,
    symbols: OutlineSymbolSelection,
    chipLabel: OutlineChipLabel,
    onRewriteLine: ((String) -> String) -> Unit,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onAddPhoto: () -> Unit = {},
    /** The live line's words with its caret or selection; null while no line has the caret. */
    lineEdit: OutlineEdit? = null,
    onApplyText: ((OutlineEdit) -> OutlineEdit) -> Unit = {},
    onPickTemplate: () -> Unit = {},
    onPickLink: () -> Unit = {},
    onPickCommentLink: () -> Unit = {},
    labelChips: ToolbarChipArrangement = ToolbarChips.defaultArrangement(ToolbarChipGroup.LABELS),
    flowChips: ToolbarChipArrangement = ToolbarChips.defaultArrangement(ToolbarChipGroup.FLOW),
    end: @Composable RowScope.() -> Unit = {},
) {
    // The caret's line as the memo editor's line tools see it: the raw line, caret at its end.
    val edit = line?.let { n -> n.toLine().let { OutlineEdit(it, it.length, it.length) } }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.imePadding().navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val unit: @Composable (EditorToolbarItem) -> Unit = { item ->
                    when (item) {
                        // The memo editor's writing aids, on the live line's own words and caret.
                        EditorToolbarItem.PHOTO -> PhotoToolbarItem(onAddPhoto)
                        EditorToolbarItem.HISTORY -> HistoryToolbarItems(canUndo, canRedo, onUndo, onRedo)
                        EditorToolbarItem.BOLD -> {
                            val e = lineEdit ?: OutlineEdit("", 0, 0)
                            BoldToolbarItem(e, TextFieldValue(e.text, TextRange(e.selectionStart, e.selectionEnd))) { result -> onApplyText { result } }
                        }
                        EditorToolbarItem.HIGHLIGHT -> {
                            val e = lineEdit ?: OutlineEdit("", 0, 0)
                            HighlightToolbarItem(TextFieldValue(e.text, TextRange(e.selectionStart, e.selectionEnd))) { role ->
                                onApplyText { InlineTextMarkup.toggle(it, InlineStyle.HIGHLIGHT, role) }
                            }
                        }
                        EditorToolbarItem.TEMPLATE -> TemplateToolbarItem(onPickTemplate)
                        EditorToolbarItem.LINK -> LinkToolbarItem(onPickLink)
                        EditorToolbarItem.COMMENT_LINK -> CommentLinkToolbarItem(onPickCommentLink)
                        EditorToolbarItem.INDENT -> {
                            IconButton(onClick = onOutdent, enabled = canOutdent, modifier = Modifier.testTag("outliner_outdent")) {
                                Icon(Icons.AutoMirrored.Outlined.FormatIndentDecrease, contentDescription = "段を上げる")
                            }
                            IconButton(onClick = onIndent, enabled = canIndent, modifier = Modifier.testTag("outliner_indent")) {
                                Icon(Icons.AutoMirrored.Outlined.FormatIndentIncrease, contentDescription = "段を下げる")
                            }
                        }
                        EditorToolbarItem.MOVE_LINES -> {
                            IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.testTag("outliner_move_up")) {
                                Icon(Icons.Outlined.ArrowUpward, contentDescription = "上へ移動")
                            }
                            IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.testTag("outliner_move_down")) {
                                Icon(Icons.Outlined.ArrowDownward, contentDescription = "下へ移動")
                            }
                        }
                        EditorToolbarItem.FOLD -> IconButton(onClick = onToggleFold, enabled = foldable, modifier = Modifier.testTag("outliner_fold")) {
                            Icon(
                                imageVector = if (folded) Icons.Outlined.UnfoldMore else Icons.Outlined.UnfoldLess,
                                contentDescription = if (folded) "展開する" else "折りたたむ",
                            )
                        }
                        EditorToolbarItem.ZOOM -> IconButton(onClick = onZoom, enabled = canZoom, modifier = Modifier.testTag("outliner_zoom")) {
                            Icon(Icons.Outlined.ZoomIn, contentDescription = "この行にズーム")
                        }
                        EditorToolbarItem.TASK -> {
                            val state = edit?.let(WorkOutlineEditing::taskState) ?: WorkOutlineEditing.TaskState.NONE
                            IconButton(
                                onClick = { onRewriteLine { l -> WorkOutlineEditing.cycleTask(OutlineEdit(l, l.length, l.length), symbols).text } },
                                enabled = edit != null,
                                modifier = Modifier.testTag("outliner_task"),
                            ) {
                                Icon(
                                    if (state == WorkOutlineEditing.TaskState.DONE) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                                    contentDescription = when (state) {
                                        WorkOutlineEditing.TaskState.NONE -> "チェックボックスにする"
                                        WorkOutlineEditing.TaskState.OPEN -> "完了にする"
                                        WorkOutlineEditing.TaskState.DONE -> "チェックボックスを外す"
                                    },
                                    tint = if (state == WorkOutlineEditing.TaskState.NONE) LocalContentColor.current else MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        EditorToolbarItem.OUTLINE_LABELS -> labelChips.visible.forEach { id ->
                            val role = ToolbarChips.roleOf(id) ?: return@forEach
                            val index = WorkOutlineEditing.chipRoles.indexOf(role)
                            FilterChip(
                                selected = edit != null && WorkOutlineEditing.isMarkerActive(edit, role),
                                enabled = edit != null,
                                onClick = { onRewriteLine { l -> WorkOutlineEditing.toggleMarker(OutlineEdit(l, l.length, l.length), role, symbols).text } },
                                label = { Text(chipLabel.textFor(OUTLINE_LABEL_WORDS[index], role, symbols)) },
                                modifier = Modifier.padding(horizontal = 2.dp).testTag("outliner_label_$index"),
                            )
                        }
                        EditorToolbarItem.FLOW_MODIFIERS -> flowChips.visible.forEach { id ->
                            val chip = ToolbarChips.chip(ToolbarChipGroup.FLOW, id) ?: return@forEach
                            val token = ToolbarChips.flowToken(id) ?: return@forEach
                            val description = chip.description
                            val tag = "outliner_flow_$id"
                            TextButton(
                                onClick = { onRewriteLine { l -> WorkOutlineEditing.applyFlowModifier(OutlineEdit(l, l.length, l.length), token).text } },
                                enabled = edit != null,
                                contentPadding = PaddingValues(horizontal = ProductSpacing.xs),
                                modifier = Modifier
                                    .heightIn(min = ProductSize.minimumTouchTarget)
                                    .semantics { contentDescription = description }
                                    .testTag(tag),
                            ) {
                                Text(chip.word, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        else -> Unit
                    }
            }
            // One row by default. 設定 can stand the bar in two (the same setting as the editors'
            // bars): the writer's upper row above, the lower row beneath — which units stand
            // where is arranged in ショートカットバー（アウトライナー）.
            val rows = if (twoRows) listOf(items.filterNot(lower::contains), items.filter(lower::contains)) else listOf(items)
            rows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(if (!twoRows) "outliner_toolbar" else if (index == 0) "outliner_toolbar_upper" else "outliner_toolbar_lower"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = ProductSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        row.forEach { unit(it) }
                    }
                    // Comments, 再生設定 and ■ stand pinned past the scrolling strip, on the last
                    // row, as the memo editor keeps them: reachable whether or not the strip
                    // has been pushed aside.
                    if (index == rows.lastIndex) end()
                }
            }
        }
    }
}

/** The words under the label chips, in [WorkOutlineEditing.chipRoles] order — as on the memo bar. */
private val OUTLINE_LABEL_WORDS = listOf("見出し", "項目", "補足", "重要", "疑問")


/** The + that closes the list: one more line, in the bullet column of the level it will join. */
@Composable
private fun AppendRow(indentSteps: Int, onAppend: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ProductSize.screenHorizontalPadding + DEPTH_STEP * indentSteps, end = ProductSize.screenHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = "行を追加",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(BULLET_SIZE)
                .clickable(role = Role.Button, onClick = onAppend)
                .padding(ProductSpacing.xs)
                .testTag("outliner_append"),
        )
    }
}

private val DEPTH_STEP = 16.dp
/** The dot itself, the ring a folded dot wears, the gap before its guide, the fold target. */
private val BULLET_DOT = 7.dp
private val BULLET_RING = 15.dp
private val TASK_BOX = 20.dp
private val FOLD_SIZE = 40.dp
/** One line of words with its padding: what a bullet, a count and a fold stand as tall as. */
private val LINE_ROW = 44.dp
private val DRAG_LIFT = 8.dp
private val AUTOSCROLL_EDGE = 72.dp
private val AUTOSCROLL_STEP = 10.dp
private val PLAIN_BULLETS = setOf("-", "*", "+", "・", "•")
private val BULLET_SIZE = 32.dp
/** The one line a new outline is offered: an item with nothing in it yet. */
private const val FIRST_LINE = "- "
/** A line's words and a line's field stand the same height, so a tap does not shift the list. */
private val LINE_MIN_HEIGHT = 28.dp

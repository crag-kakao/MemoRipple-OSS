package io.github.cragcoffee.memoripple.ui.memos

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.IntOffset
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplatePolicy
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.outlined.FormatIndentIncrease
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.shape.CircleShape
import io.github.cragcoffee.memoripple.domain.comments.CommentColorRole
import io.github.cragcoffee.memoripple.domain.split.SplitReference
import io.github.cragcoffee.memoripple.domain.split.SplitReferenceKind
import io.github.cragcoffee.memoripple.domain.split.SplitWorkspacePolicy
import io.github.cragcoffee.memoripple.domain.WorkTextStats
import io.github.cragcoffee.memoripple.domain.memos.ExportableMemo
import io.github.cragcoffee.memoripple.domain.memos.MemoMarkdownExport
import io.github.cragcoffee.memoripple.domain.memos.LinkableMemo
import io.github.cragcoffee.memoripple.domain.memos.MemoLinks
import io.github.cragcoffee.memoripple.domain.memos.MemoDocxExport
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.EditorToolbarArrangement
import io.github.cragcoffee.memoripple.domain.memos.EditorToolbarItem
import io.github.cragcoffee.memoripple.domain.memos.EditorToolbarOrder
import io.github.cragcoffee.memoripple.domain.memos.EditorToolbarSurface
import io.github.cragcoffee.memoripple.domain.memos.ToolbarChips
import io.github.cragcoffee.memoripple.domain.memos.ToolbarChipGroup
import io.github.cragcoffee.memoripple.domain.memos.ToolbarChipArrangement
import io.github.cragcoffee.memoripple.domain.NoteLink
import io.github.cragcoffee.memoripple.domain.ProseTyping
import io.github.cragcoffee.memoripple.domain.WorkCommentScope
import io.github.cragcoffee.memoripple.domain.BodyReading
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.Color
import io.github.cragcoffee.memoripple.ui.playback.textFor
import io.github.cragcoffee.memoripple.domain.InlineMarkupEditing
import io.github.cragcoffee.memoripple.domain.InlineStyle
import io.github.cragcoffee.memoripple.domain.InlineTextMarkup
import io.github.cragcoffee.memoripple.domain.TaskGlyphs
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.data.MemoCommentEntity
import io.github.cragcoffee.memoripple.data.FavoriteComment
import io.github.cragcoffee.memoripple.data.PlaybackStyle
import io.github.cragcoffee.memoripple.data.TagEntity
import io.github.cragcoffee.memoripple.data.PhotoAttachment
import io.github.cragcoffee.memoripple.data.AttachmentLimits
import io.github.cragcoffee.memoripple.domain.OutlineEdit
import io.github.cragcoffee.memoripple.domain.OutlineChipLabel
import io.github.cragcoffee.memoripple.domain.OutlineSymbolRole
import io.github.cragcoffee.memoripple.domain.OutlineSymbolSelection
import io.github.cragcoffee.memoripple.domain.WorkOutlineEditing
import io.github.cragcoffee.memoripple.domain.comments.CommentLinkMarkers
import io.github.cragcoffee.memoripple.domain.playback.PlaybackStatus
import io.github.cragcoffee.memoripple.domain.playback.CommentLaneAllocator
import io.github.cragcoffee.memoripple.domain.playback.PlaybackContentMode
import io.github.cragcoffee.memoripple.domain.playback.PlaybackTimeline
import io.github.cragcoffee.memoripple.domain.playback.PlaybackValidationIssue
import io.github.cragcoffee.memoripple.domain.comments.CommentAppearance
import io.github.cragcoffee.memoripple.domain.comments.CommentMotion
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.domain.tags.TagNameNormalizer
import io.github.cragcoffee.memoripple.domain.tags.TagNameValidation
import io.github.cragcoffee.memoripple.domain.memos.MemoLifecycleState
import io.github.cragcoffee.memoripple.overlay.OverlayDensity
import io.github.cragcoffee.memoripple.overlay.OverlayDisplayRegion
import io.github.cragcoffee.memoripple.overlay.OverlayPermissionGateway
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackOptions
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackPlan
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackPlanFactory
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackRequest
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackState
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackStatus
import io.github.cragcoffee.memoripple.overlay.OverlayServiceStarter
import io.github.cragcoffee.memoripple.overlay.OverlayStartDecision
import io.github.cragcoffee.memoripple.overlay.OverlayTimelineResolution
import io.github.cragcoffee.memoripple.overlay.OverlayWindowPolicy
import io.github.cragcoffee.memoripple.overlay.FIXED_COMMENT_TOO_LARGE_MESSAGE
import io.github.cragcoffee.memoripple.overlay.overlayStartDecision
import io.github.cragcoffee.memoripple.speech.SpeechState
import io.github.cragcoffee.memoripple.speech.SpeechStatus
import io.github.cragcoffee.memoripple.ui.playback.CommentPlaybackFrameClock
import io.github.cragcoffee.memoripple.ui.playback.CommentRendererColors
import io.github.cragcoffee.memoripple.ui.playback.CommentRenderer
import io.github.cragcoffee.memoripple.ui.playback.COMMENT_BASE_FONT_SIZE
import io.github.cragcoffee.memoripple.ui.playback.COMMENT_WAVE_DESIRED_AMPLITUDE
import io.github.cragcoffee.memoripple.ui.playback.STAGE_FONT_HEIGHT_FRACTION
import io.github.cragcoffee.memoripple.ui.playback.STAGE_MAXIMUM_BASE_FONT_SIZE
import io.github.cragcoffee.memoripple.ui.playback.STAGE_MINIMUM_BASE_FONT_SIZE
import io.github.cragcoffee.memoripple.ui.playback.stageCommentRendererColors
import io.github.cragcoffee.memoripple.ui.playback.PlaybackDisplayMode
import io.github.cragcoffee.memoripple.ui.playback.inlineCommentRendererColors
import io.github.cragcoffee.memoripple.ui.playback.LocalCommentFontFamily
import io.github.cragcoffee.memoripple.ui.playback.measureCommentTextMetricsPx
import io.github.cragcoffee.memoripple.ui.playback.memoCommentStagePalette
import io.github.cragcoffee.memoripple.ui.playback.resolveCommentLaneLayout
import io.github.cragcoffee.memoripple.ui.components.DestructiveTextButton
import io.github.cragcoffee.memoripple.ui.components.ProductEmptyState
import io.github.cragcoffee.memoripple.ui.components.ProductSheetHeader
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.components.ProductTopBar
import io.github.cragcoffee.memoripple.ui.components.SectionHeader
import io.github.cragcoffee.memoripple.ui.attachments.AttachmentImageLoader
import io.github.cragcoffee.memoripple.ui.attachments.InlinePhoto
import io.github.cragcoffee.memoripple.ui.attachments.PhotoAttachmentStrip
import io.github.cragcoffee.memoripple.ui.attachments.PhotoReorderSheet
import io.github.cragcoffee.memoripple.ui.attachments.PhotoViewer
import io.github.cragcoffee.memoripple.domain.memos.MemoBlock
import io.github.cragcoffee.memoripple.domain.memos.MemoContent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlin.math.ceil

@Composable
fun MemoEditorRoute(
    memoId: Long,
    initialFolderId: Long? = null,
    appSettings: AppSettings = AppSettings.Default,
    onBack: () -> Unit,
    onOpenAppSettings: () -> Unit = {},
    onOpenMemo: (Long) -> Unit = {},
    onSwapPrimaryMemo: (Long) -> Unit = onOpenMemo,
    onOpenNoteAsPrimary: (Long) -> Unit = {},
    onOpenNoteReader: (Long, Long) -> Unit = { _, _ -> },
) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    // The way playback was last asked for, remembered on the device so ▶ is one press.
    val playbackStyle by application.settingsRepository.playbackStyle
        .collectAsStateWithLifecycle(initialValue = PlaybackStyle())
    val keepExpression by application.settingsRepository.keepCommentExpression
        .collectAsStateWithLifecycle(initialValue = false)
    val toolbarOrderStored by application.settingsRepository.editorToolbarOrder
        .collectAsStateWithLifecycle(initialValue = "")
    val toolbarNoteOrderStored by application.settingsRepository.editorToolbarNoteOrder
        .collectAsStateWithLifecycle(initialValue = "")
    val outlineSymbolsStored by application.settingsRepository.outlineSymbolSet
        .collectAsStateWithLifecycle(initialValue = "")
    val outlineChipLabelStored by application.settingsRepository.outlineChipLabel
        .collectAsStateWithLifecycle(initialValue = "")
    // The chips of the two chip units, as arranged on their own stage — one set per bar.
    val memoLabelChipsStored by application.settingsRepository.toolbarChips("MEMO", "LABELS").collectAsStateWithLifecycle(initialValue = "")
    val memoFlowChipsStored by application.settingsRepository.toolbarChips("MEMO", "FLOW").collectAsStateWithLifecycle(initialValue = "")
    val noteLabelChipsStored by application.settingsRepository.toolbarChips("NOTE", "LABELS").collectAsStateWithLifecycle(initialValue = "")
    val noteFlowChipsStored by application.settingsRepository.toolbarChips("NOTE", "FLOW").collectAsStateWithLifecycle(initialValue = "")
    val toolbarTwoRows by application.settingsRepository.editorToolbarTwoRows
        .collectAsStateWithLifecycle(initialValue = false)
    val hideTags by application.settingsRepository.editorHideTags
        .collectAsStateWithLifecycle(initialValue = false)
    val keptExpressionIds by application.settingsRepository.keptCommentExpression
        .collectAsStateWithLifecycle(initialValue = null)
    val favoriteComments by application.settingsRepository.favoriteComments
        .collectAsStateWithLifecycle(initialValue = FavoriteComment.Defaults)
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
            initialFolderId = initialFolderId,
            contentStore = application.memoContentStore,
        ),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val speechFollowLine by viewModel.speechFollowLine.collectAsStateWithLifecycle()
    val comments by viewModel.comments.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val tagMessage by viewModel.tagMessage.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val links by viewModel.links.collectAsStateWithLifecycle()
    val linkTargets by viewModel.linkTargets.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val photoMessage by viewModel.photoMessage.collectAsStateWithLifecycle()
    val imageLoader = remember(application) { AttachmentImageLoader(application.attachmentBlobStore) }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AttachmentLimits.MAX_PHOTOS_PER_RECORD),
    ) { uris -> viewModel.addPhotos(uris) }
    // Export writes through the same file store the backup uses, so there is one place that talks
    // to a document the user picked.
    val exportScope = rememberCoroutineScope()
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val exportDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(MemoMarkdownExport.MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val markdown = MemoMarkdownExport.render(
            ExportableMemo(state.title, state.body, tags.map(TagEntity::name)),
        )
        exportScope.launch {
            val written = application.backupFileStore.write(uri, markdown.toByteArray())
            exportMessage = if (written) "Markdownで書き出しました" else "書き出せませんでした"
        }
    }
    val exportDocxDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(MemoDocxExport.MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = MemoDocxExport.render(
            ExportableMemo(state.title, state.body, tags.map(TagEntity::name)),
        )
        exportScope.launch {
            val written = application.backupFileStore.write(uri, bytes)
            exportMessage = if (written) "Word (.docx)で書き出しました" else "書き出せませんでした"
        }
    }
    val exportDocx by application.settingsRepository.editorExportDocx
        .collectAsStateWithLifecycle(initialValue = false)
    val exportPdfDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exportScope.launch {
            val result = application.portableExportEngine.exportMemoPdf(
                memoId = memoId,
                destination = uri,
                nowMillis = System.currentTimeMillis(),
            )
            exportMessage = when (result) {
                is io.github.cragcoffee.memoripple.portableexport.PortableExportEngine
                    .ExportResult.Done,
                -> "PDFで保存しました"

                else -> "書き出せませんでした"
            }
        }
    }
    val showCopyAllMenu by application.settingsRepository.editorMenuCopyAll
        .collectAsStateWithLifecycle(initialValue = true)
    val showPdfExportMenu by application.settingsRepository.editorMenuPdfExport
        .collectAsStateWithLifecycle(initialValue = true)
    val playbackStatusFlow = remember(viewModel) {
        viewModel.playbackState.map { animation -> animation.status }.distinctUntilChanged()
    }
    val playbackStatus by playbackStatusFlow.collectAsStateWithLifecycle(PlaybackStatus.IDLE)
    val speechState by viewModel.speechState.collectAsStateWithLifecycle()
    val overlayState by application.overlayPlaybackStateStore.state.collectAsStateWithLifecycle()
    val overlayServiceStarter = remember(application) {
        OverlayServiceStarter(application.overlayPlaybackStateStore)
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.saveNow()
                viewModel.pausePlayback()
                // The voice is deliberately NOT stopped here: ON_STOP is the screen going
                // dark or the app stepping back, and a reading in flight is meant to keep
                // going there — the speech foreground service holds the process for it.
                // Leaving this screen still silences it, in onDispose below.
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopPlayback()
            viewModel.stopSpeech()
            if (application.overlayPlaybackStateStore.state.value.status ==
                OverlayPlaybackStatus.WAITING_PERMISSION
            ) {
                application.overlayPlaybackStateStore.idle()
            }
        }
    }

    BackHandler(enabled = !state.isLoading) { viewModel.saveAndThen(onBack) }
    CommentPlaybackFrameClock(
        status = playbackStatus,
        onFrame = viewModel::advancePlayback,
    )

    MemoEditorScreen(
        state = state,
        comments = comments,
        tags = tags,
        allTags = allTags,
        tagMessage = tagMessage,
        photos = photos,
        photoMessage = photoMessage,
        links = links,
        linkTargets = linkTargets,
        templates = templates,
        onSaveTemplate = viewModel::saveAsTemplate,
        onCreateTemplate = viewModel::createTemplate,
        onDeleteTemplate = viewModel::deleteTemplate,
        onOpenLinkedMemo = { viewModel.saveNow(); onOpenMemo(it) },
        memoId = memoId,
        onSwapPrimaryMemo = { viewModel.saveNow(); onSwapPrimaryMemo(it) },
        onOpenNoteAsPrimary = { viewModel.saveNow(); onOpenNoteAsPrimary(it) },
        onOpenNoteReader = { noteId, episodeId ->
            viewModel.saveNow()
            onOpenNoteReader(noteId, episodeId)
        },
        exportMessage = exportMessage,
        onExportMessageShown = { exportMessage = null },
        onExportMarkdown = {
            if (exportDocx) {
                exportDocxDocument.launch(MemoDocxExport.fileName(state.title))
            } else {
                exportDocument.launch(MemoMarkdownExport.fileName(state.title))
            }
        },
        exportAsDocx = exportDocx,
        onExportPdf = {
            exportPdfDocument.launch(
                MemoMarkdownExport.fileName(state.title)
                    .removeSuffix(MemoMarkdownExport.EXTENSION) + ".pdf",
            )
        },
        showCopyAllMenu = showCopyAllMenu,
        showPdfExportMenu = showPdfExportMenu,
        imageLoader = imageLoader,
        onAddPhotos = {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onDeletePhoto = viewModel::deletePhoto,
        onReorderPhotos = viewModel::reorderPhotos,
        onActivateText = viewModel::activateText,
        onUpdateBlockText = viewModel::updateBlockText,
        onPhotoCaret = viewModel::rememberPhotoCaret,
        onMergeTextBlock = viewModel::mergeWithPrevious,
        onPendingCaretConsumed = viewModel::consumePendingCaret,
        onPhotoMessageShown = viewModel::clearPhotoMessage,
        playbackStatus = playbackStatus,
        speechState = speechState,
        onTitleChange = viewModel::updateTitle,
        onBodyChange = viewModel::updateBody,
        onBack = { viewModel.saveAndThen(onBack) },
        onOpenAppSettings = onOpenAppSettings,
        onArchive = { viewModel.archive(onBack) },
        onUnarchive = { viewModel.unarchive(onBack) },
        onMoveToTrash = { viewModel.moveToTrash(onBack) },
        onAttachTag = viewModel::attachTag,
        onDetachTag = viewModel::detachTag,
        onCreateTag = viewModel::createAndAttachTag,
        onTagMessageShown = viewModel::clearTagMessage,
        onPinnedChange = viewModel::setPinned,
        onAddComment = viewModel::addComment,
        onUpdateCommentExpression = viewModel::updateCommentExpression,
        onDeleteComment = viewModel::deleteComment,
        onLinkComment = viewModel::linkComment,
        onUnlinkComment = viewModel::unlinkComment,
        onEmitLink = viewModel::fireLinkedComment,
        linkedCommentFires = viewModel.linkedCommentFires,
        onCreateSingleCommentTimeline = { id ->
            viewModel.createSingleCommentTimeline(id, appSettings)
        },
        onStartSpeechWithLinks = viewModel::startSpeechWithLinks,
        onJumpSpeechToLine = viewModel::jumpSpeechToLine,
        speechFollowLine = speechFollowLine,
        onBeginCommentReorder = viewModel::beginCommentReorder,
        onReorderComments = viewModel::reorderComments,
        onResetCommentOrder = viewModel::resetCommentOrder,
        onCreateTimeline = { mode -> viewModel.createPlaybackTimeline(mode, appSettings) },
        onPlayTimeline = viewModel::playTimeline,
        onPause = viewModel::pausePlayback,
        onResume = viewModel::resumePlayback,
        onStop = viewModel::stopPlayback,
        overlayState = overlayState,
        onSaveForOverlay = viewModel::saveForOverlay,
        onOverlayWaiting = application.overlayPlaybackStateStore::waiting,
        onCancelOverlayRequest = application.overlayPlaybackStateStore::idle,
        onStartOverlay = { request ->
            runCatching { overlayServiceStarter.start(application, request) }
                .onFailure {
                    application.overlayPlaybackStateStore.fail(
                        "オーバーレイ再生を開始できませんでした",
                    )
                }
        },
        onStopOverlay = { overlayServiceStarter.stop(application) },
        onOverlayMessageShown = application.overlayPlaybackStateStore::clearMessage,
        onStartSpeech = viewModel::startSpeech,
        onStopSpeech = viewModel::stopSpeech,
        onSpeechMessageShown = viewModel::clearSpeechMessage,
        appSettings = appSettings,
        onCommentScopeChange = { scope ->
            exportScope.launch { application.settingsRepository.setCommentScope(scope) }
        },
        displayMode = PlaybackDisplayMode.entries
            .firstOrNull { it.name == playbackStyle.displayModeId } ?: PlaybackDisplayMode.INLINE,
        contentMode = PlaybackContentMode.entries
            .firstOrNull { it.name == playbackStyle.contentModeId } ?: PlaybackContentMode.BOTH,
        overlayRegionId = playbackStyle.overlayRegionId ?: OverlayDisplayRegion.FULL.storageId,
        overlayDensityId = playbackStyle.overlayDensityId ?: OverlayDensity.STANDARD.storageId,
        onDisplayModeChange = { mode ->
            exportScope.launch { application.settingsRepository.setPlaybackDisplayMode(mode.name) }
        },
        onContentModeChange = { mode ->
            exportScope.launch { application.settingsRepository.setPlaybackContentMode(mode.name) }
        },
        onOverlayRegionChange = { id ->
            exportScope.launch { application.settingsRepository.setOverlayRegion(id) }
        },
        onOverlayDensityChange = { id ->
            exportScope.launch { application.settingsRepository.setOverlayDensity(id) }
        },
        keepExpression = keepExpression,
        toolbarTwoRows = toolbarTwoRows,
        toolbarOrder = remember(toolbarOrderStored) {
            EditorToolbarOrder.decodeArrangement(toolbarOrderStored, EditorToolbarSurface.MEMO)
        },
        toolbarNoteOrder = remember(toolbarNoteOrderStored) {
            EditorToolbarOrder.decodeArrangement(toolbarNoteOrderStored, EditorToolbarSurface.NOTE)
        },
        outlineSymbols = remember(outlineSymbolsStored) {
            OutlineSymbolSelection.decode(outlineSymbolsStored)
        },
        outlineChipLabel = remember(outlineChipLabelStored) {
            OutlineChipLabel.fromStorageId(outlineChipLabelStored)
        },
        labelChips = remember(memoLabelChipsStored) { ToolbarChips.decode(memoLabelChipsStored, ToolbarChipGroup.LABELS) },
        flowChips = remember(memoFlowChipsStored) { ToolbarChips.decode(memoFlowChipsStored, ToolbarChipGroup.FLOW) },
        noteLabelChips = remember(noteLabelChipsStored) { ToolbarChips.decode(noteLabelChipsStored, ToolbarChipGroup.LABELS) },
        noteFlowChips = remember(noteFlowChipsStored) { ToolbarChips.decode(noteFlowChipsStored, ToolbarChipGroup.FLOW) },
        hideTags = hideTags,
        keptExpression = keptExpressionIds
            ?.split('|')
            ?.takeIf { it.size == 8 }
            ?.let { ids ->
                CommentAppearance.fromEntity(ids[0], ids[1], ids[2]) to
                    CommentMotion.fromEntity(ids[3], ids[4], ids[5], ids[6], ids[7])
            },
        onKeepExpressionChange = { keep ->
            exportScope.launch { application.settingsRepository.setKeepCommentExpression(keep) }
        },
        onKeepExpression = { appearance, motion ->
            exportScope.launch {
                application.settingsRepository.setKeptCommentExpression(
                    listOf(
                        appearance.colorRole.storageId,
                        appearance.sizeRole.storageId,
                        appearance.emphasisRole.storageId,
                        motion.speedRole.storageId,
                        motion.placementRole.storageId,
                        motion.mode.storageId,
                        motion.direction.storageId,
                        motion.flowEffect.storageId,
                    ).joinToString("|"),
                )
            }
        },
        favoriteComments = favoriteComments,
        onAddFavorite = { text ->
            exportScope.launch {
                application.settingsRepository.setFavoriteComments(
                    favoriteComments + FavoriteComment(
                        key = "user_${System.currentTimeMillis()}",
                        label = text,
                        text = text,
                    ),
                )
            }
        },
        onRemoveFavorite = { favorite ->
            exportScope.launch {
                application.settingsRepository.setFavoriteComments(
                    favoriteComments.filterNot { it.key == favorite.key },
                )
            }
        },
        playbackLayer = { modifier, colors, dimBehind, baseFontSize ->
            MemoPlaybackLayer(viewModel, modifier, colors, dimBehind, baseFontSize)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@SuppressLint("InlinedApi")
@Composable
private fun MemoEditorScreen(
    state: MemoEditorUiState,
    onOpenAppSettings: () -> Unit = {},
    comments: List<MemoCommentEntity>,
    tags: List<TagEntity>,
    allTags: List<TagEntity>,
    tagMessage: String?,
    photos: List<PhotoAttachment>,
    photoMessage: String?,
    links: MemoLinks,
    linkTargets: List<LinkableMemo>,
    templates: List<MemoTemplate>,
    onSaveTemplate: (String, (Boolean) -> Unit) -> Unit,
    onCreateTemplate: (String, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    onDeleteTemplate: (String) -> Unit,
    onOpenLinkedMemo: (Long) -> Unit,
    memoId: Long = 0L,
    onSwapPrimaryMemo: (Long) -> Unit = {},
    onOpenNoteAsPrimary: (Long) -> Unit = {},
    onOpenNoteReader: (Long, Long) -> Unit = { _, _ -> },
    exportMessage: String?,
    onExportMessageShown: () -> Unit,
    onExportMarkdown: () -> Unit,
    onExportPdf: () -> Unit = {},
    showCopyAllMenu: Boolean = true,
    showPdfExportMenu: Boolean = true,
    exportAsDocx: Boolean = false,
    imageLoader: AttachmentImageLoader,
    onAddPhotos: () -> Unit,
    onDeletePhoto: (PhotoAttachment) -> Unit,
    onReorderPhotos: (List<Long>, (Boolean) -> Unit) -> Unit,
    onPhotoMessageShown: () -> Unit,
    /** A memo written in blocks (docs/MEMO_CONTENT_BLOCKS.md): which text is written, its words. */
    onActivateText: (Long) -> Unit = {},
    onUpdateBlockText: (Long, String) -> Unit = { _, _ -> },
    /** Where the caret stands in the text being written as the photo button is pressed (null: not writing). */
    onPhotoCaret: (Int?) -> Unit = {},
    onMergeTextBlock: (Long) -> Unit = {},
    onPendingCaretConsumed: () -> Unit = {},
    playbackStatus: PlaybackStatus,
    speechState: SpeechState,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onBack: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onMoveToTrash: () -> Unit,
    onAttachTag: (Long) -> Unit,
    onDetachTag: (Long) -> Unit,
    onCreateTag: (String, (Boolean) -> Unit) -> Unit,
    onTagMessageShown: () -> Unit,
    onPinnedChange: (Boolean) -> Unit,
    onAddComment: (String, CommentAppearance, CommentMotion, (Boolean) -> Unit) -> Unit,
    onUpdateCommentExpression: (
        MemoCommentEntity,
        CommentAppearance,
        CommentMotion,
        (Boolean) -> Unit,
    ) -> Unit,
    onDeleteComment: (MemoCommentEntity) -> Unit,
    onLinkComment: (MemoCommentEntity, (Int?) -> Unit) -> Unit,
    onUnlinkComment: (MemoCommentEntity) -> Unit,
    onEmitLink: (Int) -> Unit,
    linkedCommentFires: kotlinx.coroutines.flow.Flow<MemoCommentEntity>,
    onCreateSingleCommentTimeline: (Long) -> PlaybackTimeline?,
    onStartSpeechWithLinks: (Boolean) -> Boolean,
    onJumpSpeechToLine: (Int) -> Boolean = { false },
    speechFollowLine: Int? = null,
    onBeginCommentReorder: () -> Unit,
    onReorderComments: (List<Long>) -> Unit,
    onResetCommentOrder: () -> Unit,
    onCreateTimeline: (PlaybackContentMode) -> PlaybackTimeline,
    onPlayTimeline: (PlaybackTimeline) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    overlayState: OverlayPlaybackState,
    onSaveForOverlay: ((Long?) -> Unit) -> Unit,
    onOverlayWaiting: (OverlayPlaybackRequest) -> Unit,
    onCancelOverlayRequest: () -> Unit,
    onStartOverlay: (OverlayPlaybackRequest) -> Unit,
    onStopOverlay: () -> Unit,
    onOverlayMessageShown: () -> Unit,
    onStartSpeech: (Boolean) -> Boolean,
    onStopSpeech: () -> Unit,
    onSpeechMessageShown: () -> Unit,
    appSettings: AppSettings,
    onCommentScopeChange: (WorkCommentScope) -> Unit,
    displayMode: PlaybackDisplayMode,
    contentMode: PlaybackContentMode,
    overlayRegionId: String,
    overlayDensityId: String,
    onDisplayModeChange: (PlaybackDisplayMode) -> Unit,
    onContentModeChange: (PlaybackContentMode) -> Unit,
    onOverlayRegionChange: (String) -> Unit,
    onOverlayDensityChange: (String) -> Unit,
    keepExpression: Boolean = false,
    toolbarTwoRows: Boolean = false,
    toolbarOrder: EditorToolbarArrangement = EditorToolbarArrangement(EditorToolbarOrder.Default),
    toolbarNoteOrder: EditorToolbarArrangement =
        EditorToolbarArrangement(EditorToolbarOrder.Default),
    labelChips: ToolbarChipArrangement = ToolbarChips.defaultArrangement(ToolbarChipGroup.LABELS),
    flowChips: ToolbarChipArrangement = ToolbarChips.defaultArrangement(ToolbarChipGroup.FLOW),
    noteLabelChips: ToolbarChipArrangement = ToolbarChips.defaultArrangement(ToolbarChipGroup.LABELS),
    noteFlowChips: ToolbarChipArrangement = ToolbarChips.defaultArrangement(ToolbarChipGroup.FLOW),
    outlineSymbols: OutlineSymbolSelection = OutlineSymbolSelection.Default,
    outlineChipLabel: OutlineChipLabel = OutlineChipLabel.SYMBOL_AND_WORD,
    hideTags: Boolean = false,
    keptExpression: Pair<CommentAppearance, CommentMotion>? = null,
    onKeepExpressionChange: (Boolean) -> Unit = {},
    onKeepExpression: (CommentAppearance, CommentMotion) -> Unit = { _, _ -> },
    favoriteComments: List<FavoriteComment> = FavoriteComment.Defaults,
    onAddFavorite: (String) -> Unit = {},
    onRemoveFavorite: (FavoriteComment) -> Unit = {},
    playbackLayer: @Composable (Modifier, CommentRendererColors, Boolean, TextUnit) -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showTagPicker by remember { mutableStateOf(false) }
    var showCreateTagDialog by remember { mutableStateOf(false) }
    var showOrganizationMenu by remember { mutableStateOf(false) }
    var showCommentsSheet by remember { mutableStateOf(false) }
    var showPlaybackSettings by remember { mutableStateOf(false) }
    var showOverlaySetup by remember { mutableStateOf(false) }
    var showSpeechDialog by remember { mutableStateOf(false) }
    var playbackBounds by remember(displayMode) { mutableStateOf(IntSize.Zero) }
    // Whether either writing field holds the caret. The bottom bar reads this rather than the
    // IME: focus is the writing posture itself, and it is what a press on a field grants.
    var writingFocused by remember { mutableStateOf(false) }
    var titleFocused by remember { mutableStateOf(false) }
    var bodyFocused by remember { mutableStateOf(false) }
    var showPhotoReorder by remember { mutableStateOf(false) }
    if (showPhotoReorder) {
        // 写真を並べ替え: the pictures in their order in the text; the places stay, the pictures move.
        val byId = photos.associateBy { it.id }
        val inOrder = state.blocks.filterIsInstance<MemoBlock.Photo>().mapNotNull { byId[it.attachmentId] }.ifEmpty { photos }
        PhotoReorderSheet(
            photos = inOrder,
            imageLoader = imageLoader,
            onDismiss = { showPhotoReorder = false },
            onSave = { orderedIds, complete ->
                onReorderPhotos(orderedIds) { success ->
                    complete(success)
                    showPhotoReorder = false
                }
            },
        )
    }
    LaunchedEffect(titleFocused, bodyFocused) { writingFocused = titleFocused || bodyFocused }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()
    val commentFontFamily = LocalCommentFontFamily.current
    val density = LocalDensity.current
    val safetyGapPx = with(density) { COMMENT_SAFETY_GAP.toPx() }
    val laneAllocator = remember(safetyGapPx) { CommentLaneAllocator(safetyGapPx) }
    val stagePalette = memoCommentStagePalette(appSettings.stageBackground)
    // ニコニコの縮尺: the stage letters a medium comment at 74/1080 of its own height, so a
    // taller stage writes larger letters. The inline page keeps the fixed reading size.
    val playbackBaseFontSize = if (
        displayMode == PlaybackDisplayMode.STAGE && playbackBounds.height > 0
    ) {
        with(density) {
            (playbackBounds.height * STAGE_FONT_HEIGHT_FRACTION).toSp()
        }.value.coerceIn(
            STAGE_MINIMUM_BASE_FONT_SIZE.value,
            STAGE_MAXIMUM_BASE_FONT_SIZE.value,
        ).sp
    } else {
        COMMENT_BASE_FONT_SIZE
    }
    val speechActive = speechState.status == SpeechStatus.INITIALIZING ||
        speechState.status == SpeechStatus.SPEAKING
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val overlayPermissionGateway = remember(context) { OverlayPermissionGateway(context) }
    val overlayPlanFactory = remember { OverlayPlaybackPlanFactory() }
    val windowManager = remember(context) { context.getSystemService(WindowManager::class.java) }
    val currentOnStartOverlay by rememberUpdatedState(onStartOverlay)
    val currentOnOverlayWaiting by rememberUpdatedState(onOverlayWaiting)
    val currentOnCancelOverlayRequest by rememberUpdatedState(onCancelOverlayRequest)
    val overlayApp = context.applicationContext as MemoRippleApplication
    val hasSeenOverlaySetup by overlayApp.settingsRepository.hasSeenOverlaySetup
        .collectAsStateWithLifecycle(initialValue = true)
    val currentHasSeenOverlaySetup by rememberUpdatedState(hasSeenOverlaySetup)
    val overlayIntroScope = rememberCoroutineScope()
    // アウトライン初回ガイド: rides the first outline-chip tap, once. The chip's own edit
    // has already landed by the time this shows.
    val hasSeenOutlineGuide by overlayApp.settingsRepository.hasSeenOutlineGuide
        .collectAsStateWithLifecycle(initialValue = true)
    var showOutlineGuide by remember { mutableStateOf(false) }
    val overlayLaunch = remember(overlayPermissionGateway, context) {
        OverlayLaunchFlow(
            isPermissionGranted = overlayPermissionGateway::isGranted,
            needsNotificationDecision = {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
            },
            onStart = { currentOnStartOverlay(it) },
            onWaitingForPermission = { currentOnOverlayWaiting(it) },
            onCancelled = { currentOnCancelOverlayRequest() },
            hasSeenSetupIntro = { currentHasSeenOverlaySetup },
            onSetupIntroSeen = {
                overlayIntroScope.launch {
                    overlayApp.settingsRepository.setHasSeenOverlaySetup(true)
                }
            },
        )
    }
    val submitOverlayRequest: (OverlayPlaybackRequest) -> Unit = { request ->
        overlayLaunch.submit(request, overlayStartDecision(overlayState))
    }

    LaunchedEffect(overlayState.status, overlayLaunch.requestAfterStop) {
        if (overlayState.status == OverlayPlaybackStatus.IDLE) {
            overlayLaunch.onOverlayIdle()
        }
    }

    LaunchedEffect(speechState.message) {
        speechState.message?.let {
            snackbarHostState.showSnackbar(it)
            onSpeechMessageShown()
        }
    }
    LaunchedEffect(overlayState.message) {
        overlayState.message?.let {
            snackbarHostState.showSnackbar(it)
            onOverlayMessageShown()
        }
    }
    LaunchedEffect(tagMessage) {
        tagMessage?.let {
            snackbarHostState.showSnackbar(it)
            onTagMessageShown()
        }
    }
    LaunchedEffect(photoMessage) {
        photoMessage?.let {
            snackbarHostState.showSnackbar(it)
            onPhotoMessageShown()
        }
    }
    LaunchedEffect(exportMessage) {
        exportMessage?.let {
            snackbarHostState.showSnackbar(it)
            onExportMessageShown()
        }
    }
    val updatePlaybackBounds: (IntSize) -> Unit = { size ->
        if (playbackBounds != IntSize.Zero && playbackBounds != size &&
            playbackStatus != PlaybackStatus.IDLE
        ) {
            onStop()
        }
        playbackBounds = size
    }
    // The measurement pipeline, reusable for any source timeline: the ordinary full reading
    // and a single linked comment flowing on demand go through the same lanes.
    val startTimelinePlayback: (PlaybackTimeline) -> Unit = { sourceTimeline ->
        if (sourceTimeline.items.isNotEmpty() &&
            playbackBounds.width > 0 &&
            playbackBounds.height > 0
        ) {
            val textMetrics = measureCommentTextMetricsPx(
                timeline = sourceTimeline,
                textMeasurer = textMeasurer,
                density = density,
                availableWidthPx = playbackBounds.width.toFloat(),
                fontFamily = commentFontFamily,
                baseFontSize = playbackBaseFontSize,
            )
            val timeline = resolveCommentLaneLayout(
                timeline = sourceTimeline,
                textMetrics = textMetrics,
                availableHeightPx = playbackBounds.height.toFloat(),
                minimumLaneHeightPx = with(density) { MINIMUM_LANE_HEIGHT.toPx() },
                verticalSafetyGapPx = with(density) { LANE_VERTICAL_SAFETY_GAP.toPx() },
                availableWidthPx = playbackBounds.width.toFloat(),
                desiredWaveAmplitudePx = with(density) {
                    COMMENT_WAVE_DESIRED_AMPLITUDE.toPx()
                },
                longCommentReadability = appSettings.longCommentReadability,
            )
            val renderWidths = textMetrics.mapValues { (_, metrics) -> metrics.renderWidthPx }
            if (timeline.validationIssue == PlaybackValidationIssue.FIXED_COMMENT_DOES_NOT_FIT) {
                snackbarScope.launch {
                    snackbarHostState.showSnackbar(FIXED_COMMENT_TOO_LARGE_MESSAGE)
                }
            } else {
                onPlayTimeline(
                    laneAllocator.allocate(
                        timeline = timeline,
                        renderWidthsPx = renderWidths,
                        containerWidthPx = playbackBounds.width.toFloat(),
                    ),
                )
            }
        }
    }
    val playResolvedTimeline = { startTimelinePlayback(onCreateTimeline(contentMode)) }
    // コメントリンクの発火 queue: fires arrive from taps and from speech passing markers; each
    // flows as its own tiny timeline once the stage is free, in arrival order.
    val linkedEmitQueue = remember { mutableStateListOf<Long>() }
    LaunchedEffect(linkedCommentFires) {
        linkedCommentFires.collect { comment -> linkedEmitQueue.add(comment.id) }
    }
    LaunchedEffect(playbackStatus, linkedEmitQueue.size, playbackBounds) {
        if (playbackStatus == PlaybackStatus.IDLE && linkedEmitQueue.isNotEmpty()) {
            val nextId = linkedEmitQueue.removeAt(0)
            onCreateSingleCommentTimeline(nextId)?.let(startTimelinePlayback)
        }
    }
    val playSelectedTimelineNow: () -> Unit = {
        if (displayMode != PlaybackDisplayMode.OVERLAY) {
            playResolvedTimeline()
        } else {
            showOverlaySetup = true
        }
        Unit
    }
    val keyboardVisible = WindowInsets.isImeVisible
    // 分割表示: the working session lives in memory on the application, so it survives both
    // rotation and the swap navigation that replaces this screen. rememberSaveable mirrors it
    // through configuration changes; nothing is written to Room, backup, or DataStore.
    val splitSession = (context.applicationContext as io.github.cragcoffee.memoripple.MemoRippleApplication)
        .splitWorkspaceSession
    var splitReference by rememberSaveable(
        memoId,
        stateSaver = androidx.compose.runtime.saveable.listSaver(
            save = { value -> if (value == null) emptyList() else listOf(value.kind.name, value.id) },
            restore = { saved ->
                if (saved.size == 2) {
                    SplitReference(
                        SplitReferenceKind.valueOf(saved[0] as String),
                        saved[1] as Long,
                    )
                } else {
                    null
                }
            },
        ),
    ) { mutableStateOf(splitSession.resumeFor(memoId)) }
    var splitRatio by rememberSaveable(memoId) {
        mutableFloatStateOf(
            if (splitSession.resumeFor(memoId) != null) splitSession.ratio
            else SplitWorkspacePolicy.DEFAULT_RATIO,
        )
    }
    var showSplitPicker by remember { mutableStateOf(false) }
    val closeSplit = {
        splitReference = null
        splitSession.close()
    }
    // Back closes the split before leaving the screen. Registered early so every later
    // handler — sheets, dialogs, reading mode — still wins over it; the platform closes the
    // keyboard before any of this runs.
    BackHandler(enabled = splitReference != null) { closeSplit() }
    // The toolbar edits the line the caret is on, so the body is held as a value with a selection
    // rather than a bare String.
    var bodyField by remember { mutableStateOf(TextFieldValue(state.activeText)) }
    // A memo written in blocks (docs/MEMO_CONTENT_BLOCKS.md) binds this field to the text being
    // written; every tool that edits "the body" edits that text. Moving to another text keeps
    // each text's caret where it was left.
    val blockSelections = remember { mutableStateMapOf<Long, TextRange>() }
    val blockRequesters = remember { mutableMapOf<Long, FocusRequester>() }
    val blockRequesterFor: (Long) -> FocusRequester = { id -> blockRequesters.getOrPut(id) { FocusRequester() } }
    var boundTextId by remember { mutableStateOf(state.activeTextId) }
    if (boundTextId != state.activeTextId) {
        // Only a move from one text to another swaps what the field holds. A memo getting its
        // first text's id (a new memo's first save lands while the writer is mid-edit) keeps
        // the field — and the caret — exactly as they are.
        val from = boundTextId
        val to = state.activeTextId
        if (from != null && to != null) {
            blockSelections[from] = bodyField.selection
            val text = state.activeText
            val kept = blockSelections[to] ?: TextRange(text.length)
            bodyField = TextFieldValue(text, TextRange(kept.start.coerceAtMost(text.length), kept.end.coerceAtMost(text.length)))
        }
        boundTextId = to
    }
    if (bodyField.text != state.activeText) bodyField = bodyField.copy(text = state.activeText)
    val activateText: (Long, TextRange?) -> Unit = { id, selection ->
        selection?.let { blockSelections[id] = it }
        onActivateText(id)
    }
    // The history knows which text each step was in; a change of the blocks' shape (a photo
    // added, moved, deleted, two texts joined) closes it.
    val undoStack = remember { mutableStateListOf<Pair<Long?, TextFieldValue>>() }
    val redoStack = remember { mutableStateListOf<Pair<Long?, TextFieldValue>>() }
    val commitBody: (TextFieldValue, Boolean) -> Unit = { next, recordHistory ->
        if (recordHistory && next.text != bodyField.text) {
            undoStack.add(boundTextId to bodyField)
            if (undoStack.size > EDITOR_HISTORY_LIMIT) undoStack.removeAt(0)
            redoStack.clear()
        }
        bodyField = next
        if (next.text != state.activeText) onBodyChange(next.text)
    }
    val restoreHistory: (Long?, TextFieldValue) -> Unit = { id, value ->
        if (id != null && id != boundTextId) {
            boundTextId = id
            onActivateText(id)
            bodyField = value
            onBodyChange(value.text)
        } else {
            commitBody(value, false)
        }
    }
    val onBodyFieldChange: (TextFieldValue) -> Unit = { next ->
        // The inline markers are invisible while their span matches, so a keyboard edit can land
        // inside one without the writer knowing; the guard reads such an edit as the visible edit
        // it meant. Composition is left alone — rewriting composing text breaks IME conversion.
        val guarded = if (next.composition == null) {
            InlineMarkupEditing.guard(
                OutlineEdit(bodyField.text, bodyField.selection.min, bodyField.selection.max),
                OutlineEdit(next.text, next.selection.min, next.selection.max),
            )
        } else {
            null
        }
        if (guarded != null) {
            commitBody(
                TextFieldValue(guarded.text, TextRange(guarded.selectionStart, guarded.selectionEnd)),
                true,
            )
        } else {
            // A bracket opened by hand gets its partner. Only a single character typed at the caret
            // counts, so pasting a paragraph that happens to open a quote changes nothing.
            val closed = ProseTyping.closeBracket(
                bodyField.text,
                OutlineEdit(next.text, next.selection.start, next.selection.end),
            )
            commitBody(
                if (closed == null) {
                    next
                } else {
                    TextFieldValue(
                        closed.text,
                        TextRange(closed.selectionStart, closed.selectionEnd),
                    )
                },
                true,
            )
        }
    }
    val applyOutline: (OutlineEdit) -> Unit = { result ->
        commitBody(
            TextFieldValue(result.text, TextRange(result.selectionStart, result.selectionEnd)),
            true,
        )
    }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val playbackActive = playbackStatus != PlaybackStatus.IDLE
    // Playback is a viewing mode: the editor goes read-only so the keyboard cannot reopen and
    // resize the playback area, which the bounds contract would treat as a reason to stop.
    // Starting from a typing session therefore has to wait for the keyboard to finish leaving.
    var pendingPlay by remember { mutableStateOf(false) }
    // The shortcut bar raises one panel at a time: another tool swaps it, the same tool closes it.
    var shortcutPanel by remember { mutableStateOf<EditorShortcutPanel?>(null) }
    val linkTargetsByTitle = remember(linkTargets) {
        linkTargets.associateBy { NoteLink.key(it.title) }
    }
    var showTemplateSaveDialog by remember { mutableStateOf(false) }
    // Google ドキュメント式: an existing memo opens to be read; only a brand-new one opens
    // writing. A double tap on the page — or 編集 — returns to the pen.
    // A resumed 分割表示 session means work in progress — that opens writing too.
    var readingMode by rememberSaveable {
        mutableStateOf(memoId != 0L && splitReference == null)
    }
    // 閲覧モード's doors to the pen (2026-09-22): a single tap on the title, a double tap on a
    // written line — each hands its field the keyboard once the page has become the editor.
    // The page's own double tap (an empty place) still opens writing with no field taken.
    val titleFocusRequester = remember { FocusRequester() }
    val bodyFocusRequester = remember { FocusRequester() }
    LaunchedEffect(state.structureVersion) {
        if (state.structureVersion == 0) return@LaunchedEffect
        undoStack.clear()
        redoStack.clear()
        val (id, offset) = state.pendingCaret ?: return@LaunchedEffect
        onPendingCaretConsumed()
        blockSelections[id] = TextRange(offset)
        if (boundTextId == id) bodyField = bodyField.copy(selection = TextRange(offset.coerceAtMost(bodyField.text.length)))
        // The text is composed on the frame after the change; ask for it then.
        withFrameNanos { }
        withFrameNanos { }
        runCatching { (if (state.blockMode) blockRequesterFor(id) else bodyFocusRequester).requestFocus() }
    }
    var pendingWritingFocus by remember { mutableStateOf<WritingFocus?>(null) }
    // Counts the title's taps: each one asks the title field to put its caret at the end.
    var titleCaretToEnd by remember { mutableIntStateOf(0) }
    LaunchedEffect(readingMode, pendingWritingFocus) {
        val pending = pendingWritingFocus ?: return@LaunchedEffect
        if (readingMode) return@LaunchedEffect
        // The editor field is composed on the frame after the switch.
        withFrameNanos { }
        when (pending) {
            is WritingFocus.Title -> {
                titleCaretToEnd += 1
                titleFocusRequester.requestFocus()
            }
            is WritingFocus.Body -> {
                val target = if (state.blockMode) MemoContent.lineToBlock(state.blocks, pending.line) else null
                if (target != null) {
                    val (blockId, local) = target
                    val text = (state.blocks.firstOrNull { it.id == blockId } as? MemoBlock.Text)?.text.orEmpty()
                    val offset = BodyReading.lineEndOffset(text, local)
                    blockSelections[blockId] = TextRange(offset)
                    if (boundTextId == blockId) bodyField = bodyField.copy(selection = TextRange(offset))
                    onActivateText(blockId)
                    withFrameNanos { }
                    runCatching { blockRequesterFor(blockId).requestFocus() }
                } else {
                    val offset = BodyReading.lineEndOffset(bodyField.text, pending.line)
                    onBodyFieldChange(bodyField.copy(selection = TextRange(offset)))
                    bodyFocusRequester.requestFocus()
                }
            }
        }
        keyboardController?.show()
        pendingWritingFocus = null
    }
    // An episode's 閲覧モード is its note's reader, not this surface; its editor is only ever
    // opened to write, so it opens writing. So does a memo with nothing in it yet — one just made
    // elsewhere (the チャット tab's 新しいメモ): there is nothing to read, only a page to fill.
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading && (state.isEpisode || (state.exists && state.title.isBlank() && state.body.isBlank()))) readingMode = false
    }
    // The reverse jump: a badge tap in 閲覧モード walks to the first marker's line.
    var readingScrollTarget by remember { mutableStateOf<Int?>(null) }
    // Folds are remembered by source line, which a checkbox tap does not move.
    val collapsedHeadings = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf<Int>() }
    val playSelectedTimeline: () -> Unit = {
        if (keyboardVisible) {
            keyboardController?.hide()
            focusManager.clearFocus()
            pendingPlay = true
        } else {
            playSelectedTimelineNow()
        }
    }
    LaunchedEffect(pendingPlay, keyboardVisible, playbackBounds) {
        if (pendingPlay && !keyboardVisible) {
            pendingPlay = false
            playSelectedTimelineNow()
        }
    }

    Scaffold(
        topBar = {
            ProductTopBar(
                // The memo's own title sits directly under the bar, so naming the screen "メモ"
                // only takes the widest slot to repeat what the tab that opened it already said.
                title = null,
                onBack = onBack,
                actions = {
                    // Whether there is anything to play means building a full timeline of the
                    // body — much too heavy to redo on every keystroke inside the top bar.
                    // Remembered against exactly what feeds it: the body, the comments, the
                    // content mode, and the timeline lambda (which carries the settings).
                    val canPlayContent = remember(
                        state.body, comments, contentMode, onCreateTimeline,
                    ) {
                        onCreateTimeline(contentMode).items.isNotEmpty()
                    }
                    PlaybackTransportActions(
                        status = playbackStatus,
                        canPlay = displayMode == PlaybackDisplayMode.OVERLAY || canPlayContent,
                        overlayMode = displayMode == PlaybackDisplayMode.OVERLAY,
                        overlayActive = overlayState.isActive,
                        onPlay = playSelectedTimeline,
                        onPause = onPause,
                        onResume = onResume,
                    )
                    Text(
                        when {
                            speechState.status == SpeechStatus.INITIALIZING -> "読み上げ準備中…"
                            state.saveStatus == SaveStatus.SAVING -> "保存中…"
                            state.saveStatus == SaveStatus.SAVED -> "保存済み"
                            else -> ""
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(
                        onClick = {
                            if (speechActive) onStopSpeech() else showSpeechDialog = true
                        },
                        enabled = state.title.isNotBlank() || state.body.isNotBlank(),
                        modifier = Modifier.testTag("memo_speech_action"),
                    ) {
                        Icon(
                            if (speechActive) {
                                Icons.Outlined.Stop
                            } else {
                                Icons.AutoMirrored.Outlined.VolumeUp
                            },
                            contentDescription = if (speechActive) "読み上げを停止" else "読み上げ",
                        )
                    }
                    if (state.exists) {
                        Box {
                            IconButton(
                                onClick = { showOrganizationMenu = true },
                                modifier = Modifier
                                    .sizeIn(
                                        minWidth = ProductSize.minimumTouchTarget,
                                        minHeight = ProductSize.minimumTouchTarget,
                                    )
                                    .testTag("memo_editor_more"),
                            ) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "メモのその他の操作")
                            }
                            DropdownMenu(
                                expanded = showOrganizationMenu,
                                onDismissRequest = { showOrganizationMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(if (state.isPinned) "固定を解除" else "上部に固定")
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.PushPin, contentDescription = null)
                                    },
                                    onClick = {
                                        onPinnedChange(!state.isPinned)
                                        showOrganizationMenu = false
                                    },
                                    modifier = Modifier.testTag("memo_editor_pin"),
                                )
                                // An episode belongs to its note. Archiving would put it in a
                                // list the note cannot see, so it is not offered; releasing it
                                // from the note first makes it an ordinary memo again.
                                if (!state.isEpisode) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (state.lifecycleState ==
                                                    MemoLifecycleState.ARCHIVED
                                                ) {
                                                    "アーカイブから戻す"
                                                } else {
                                                    "アーカイブ"
                                                },
                                            )
                                        },
                                        onClick = {
                                            showOrganizationMenu = false
                                            if (state.lifecycleState ==
                                                MemoLifecycleState.ARCHIVED
                                            ) {
                                                onUnarchive()
                                            } else {
                                                onArchive()
                                            }
                                        },
                                        modifier = Modifier.testTag("memo_editor_archive"),
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("分割表示") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.VerticalSplit,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        showOrganizationMenu = false
                                        showSplitPicker = true
                                    },
                                    modifier = Modifier.testTag("memo_editor_split"),
                                )
                                DropdownMenuItem(
                                    // The row names the door, not the room: while writing it
                                    // offers the reading, while reading it offers the pen.
                                    text = { Text(if (readingMode) "編集モード" else "閲覧モード") },
                                    leadingIcon = {
                                        Icon(
                                            if (readingMode) {
                                                Icons.Outlined.Edit
                                            } else {
                                                Icons.AutoMirrored.Outlined.MenuBook
                                            },
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        showOrganizationMenu = false
                                        if (readingMode) {
                                            readingMode = false
                                            return@DropdownMenuItem
                                        }
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                        // An episode reads where its note reads: the paged
                                        // reader with ruby and the read-aloud follow. Plain
                                        // memos keep the in-place reading surface.
                                        val noteId = state.noteId
                                        if (noteId != null) {
                                            onOpenNoteReader(noteId, memoId)
                                        } else {
                                            readingMode = true
                                        }
                                    },
                                    modifier = Modifier.testTag("memo_editor_reading_mode"),
                                )
                                // Photo management lives here, not on the page: the page shows the
                                // pictures and nothing about them.
                                if (!readingMode && state.lifecycleState == MemoLifecycleState.ACTIVE &&
                                    photos.size >= 2 && !state.isImportingPhotos
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("写真を並べ替え") },
                                        onClick = {
                                            showOrganizationMenu = false
                                            showPhotoReorder = true
                                        },
                                        modifier = Modifier.testTag("open_photo_reorder"),
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("テンプレートとして保存") },
                                    onClick = {
                                        showOrganizationMenu = false
                                        showTemplateSaveDialog = true
                                    },
                                    modifier = Modifier.testTag("memo_editor_save_template"),
                                )
                                // The memo as one plain text: the title, a blank line, the body.
                                // What the writer copies or shares is exactly what is stored.
                                val wholeText = listOf(state.title, state.body)
                                    .filter { it.isNotBlank() }
                                    .joinToString("\n\n")
                                if (showCopyAllMenu) {
                                    DropdownMenuItem(
                                        text = { Text("全てのテキストをコピー") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Outlined.ContentCopy,
                                                contentDescription = null,
                                            )
                                        },
                                        enabled = wholeText.isNotBlank(),
                                        onClick = {
                                            showOrganizationMenu = false
                                            clipboardManager.setText(AnnotatedString(wholeText))
                                            snackbarScope.launch {
                                                snackbarHostState.showSnackbar(
                                                    "全てのテキストをコピーしました",
                                                )
                                            }
                                        },
                                        modifier = Modifier.testTag("memo_editor_copy_all"),
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("共有") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Share, contentDescription = null)
                                    },
                                    enabled = wholeText.isNotBlank(),
                                    onClick = {
                                        showOrganizationMenu = false
                                        val send = android.content.Intent(
                                            android.content.Intent.ACTION_SEND,
                                        ).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, wholeText)
                                            if (state.title.isNotBlank()) {
                                                putExtra(
                                                    android.content.Intent.EXTRA_SUBJECT,
                                                    state.title,
                                                )
                                            }
                                        }
                                        context.startActivity(
                                            android.content.Intent.createChooser(send, null),
                                        )
                                    },
                                    modifier = Modifier.testTag("memo_editor_share"),
                                )
                                DropdownMenuItem(
                                    // The one export row, wearing whichever format 設定 chose.
                                    text = {
                                        Text(
                                            if (exportAsDocx) "Word (.docx)で書き出す"
                                            else "Markdownで書き出す",
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.FileDownload, contentDescription = null)
                                    },
                                    onClick = {
                                        showOrganizationMenu = false
                                        onExportMarkdown()
                                    },
                                    modifier = Modifier.testTag("memo_editor_export"),
                                )
                                if (showPdfExportMenu) {
                                    DropdownMenuItem(
                                        text = { Text("PDFで保存") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Outlined.PictureAsPdf,
                                                contentDescription = null,
                                            )
                                        },
                                        // The PDF draws from what is saved; a memo that has
                                        // never been saved has nothing on disk to draw.
                                        enabled = memoId != 0L,
                                        onClick = {
                                            showOrganizationMenu = false
                                            onExportPdf()
                                        },
                                        modifier = Modifier.testTag("memo_editor_export_pdf"),
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("ゴミ箱へ移動") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Delete, contentDescription = null)
                                    },
                                    onClick = {
                                        showOrganizationMenu = false
                                        showDeleteDialog = true
                                    },
                                    modifier = Modifier.testTag("memo_editor_trash"),
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!state.isLoading) {
                // Pinned directly above the keyboard: these are writing aids, and a writer must be
                // able to reach them without dismissing the IME first.
                // The frame the outliner's bar set: the divider full width, the rows padded
                // 12dp like its rows, clear of the navigation bar, glyphs at 24dp.
                Column(Modifier.imePadding().navigationBarsPadding()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    EditorStatsRow(state.body)
                    if (readingMode) {
                        val canFold = remember(state.body) {
                            BodyReading.foldableHeadings(
                                BodyReading.lines(state.body),
                            ).isNotEmpty()
                        }
                        ReadingToolbar(
                            playbackStatus = playbackStatus,
                            canFold = canFold,
                            overlayMode = displayMode == PlaybackDisplayMode.OVERLAY,
                            overlayActive = overlayState.isActive,
                            canComment = state.exists,
                            onStop = onStop,
                            onStopOverlay = onStopOverlay,
                            onComments = { showCommentsSheet = true },
                            onSettings = { showPlaybackSettings = true },
                            anyFolded = collapsedHeadings.isNotEmpty(),
                            onFoldAll = {
                                val foldable = BodyReading.foldableHeadings(
                                    BodyReading.lines(state.body),
                                )
                                collapsedHeadings.clear()
                                collapsedHeadings.addAll(foldable)
                            },
                            onUnfoldAll = collapsedHeadings::clear,
                            onEdit = { readingMode = false },
                        )
                    } else if (writingFocused || keyboardVisible) {
                        StructureToolbar(
                            playbackStatus = playbackStatus,
                            overlayMode = displayMode == PlaybackDisplayMode.OVERLAY,
                            overlayActive = overlayState.isActive,
                            // An episode is prose and a loose memo is notes; each gets the
                            // tools of its own kind of writing and not the other's.
                            prose = state.isEpisode,
                            twoRows = toolbarTwoRows,
                            canComment = state.exists,
                            onStop = onStop,
                            onStopOverlay = onStopOverlay,
                            onComments = { showCommentsSheet = true },
                            onSettings = { showPlaybackSettings = true },
                            body = bodyField,
                            onApply = applyOutline,
                            onAddPhoto = {
                                // A photo goes where the caret is — between the words it stands in.
                                onPhotoCaret(if (bodyFocused && state.blockMode) bodyField.selection.min else null)
                                onAddPhotos()
                            },
                            canUndo = undoStack.isNotEmpty(),
                            canRedo = redoStack.isNotEmpty(),
                            onUndo = {
                                undoStack.removeLastOrNull()?.let { (id, previous) ->
                                    redoStack.add(boundTextId to bodyField)
                                    restoreHistory(id, previous)
                                }
                            },
                            onRedo = {
                                redoStack.removeLastOrNull()?.let { (id, next) ->
                                    undoStack.add(boundTextId to bodyField)
                                    restoreHistory(id, next)
                                }
                            },
                            onPickHighlight = { role ->
                                applyOutline(
                                    InlineTextMarkup.toggle(
                                        OutlineEdit(
                                            bodyField.text,
                                            bodyField.selection.start,
                                            bodyField.selection.end,
                                        ),
                                        InlineStyle.HIGHLIGHT,
                                        role,
                                    ),
                                )
                            },
                            onPickLink = { shortcutPanel = EditorShortcutPanel.toggle(shortcutPanel, EditorShortcutPanel.MEMO_LINK) },
                            onPickTemplate = { shortcutPanel = EditorShortcutPanel.toggle(shortcutPanel, EditorShortcutPanel.TEMPLATE) },
                            onPickCommentLink = { shortcutPanel = EditorShortcutPanel.toggle(shortcutPanel, EditorShortcutPanel.COMMENT_LINK) },
                            arrangement = if (state.isEpisode) {
                                toolbarNoteOrder
                            } else {
                                toolbarOrder
                            },
                            symbols = outlineSymbols,
                            chipLabel = outlineChipLabel,
                            labelChips = if (state.isEpisode) noteLabelChips else labelChips,
                            flowChips = if (state.isEpisode) noteFlowChips else flowChips,
                            onOutlineFirstUse = {
                                if (!hasSeenOutlineGuide) showOutlineGuide = true
                            },
                        )
                    } else {
                        // The writing aids are for writing, and writing is when the keyboard is
                        // up. With the keyboard away this row keeps only what reading a memo
                        // wants: its comments, how they play, and a way to stop them. The aids
                        // are not gone — they come back with the keyboard, which is the same
                        // gesture that makes them useful.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                        ) {
                            PlaybackToolbarItems(
                                canComment = state.exists,
                                onComments = { showCommentsSheet = true },
                                onSettings = { showPlaybackSettings = true },
                            )
                            PlaybackStopButton(
                                status = playbackStatus,
                                overlayMode = displayMode == PlaybackDisplayMode.OVERLAY,
                                overlayActive = overlayState.isActive,
                                onStop = onStop,
                                onStopOverlay = onStopOverlay,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
        val editorBody: @Composable () -> Unit = {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
            ) {
                // While the keyboard is up only the title sits above the body, so the column does
                // not need to scroll: the body takes the exact leftover and scrolls its own cursor
                // into view. The compact scrolling layout stays for genuinely short screens — but
                // never inside the split, where flipping layouts as the keyboard arrives would
                // tear down the focused field's input session and cancel the keyboard itself.
                val shortViewport = splitReference == null && !keyboardVisible && maxHeight < 420.dp
                // A bounded height, not merely a minimum. Inside the compact scrolling column the
                // incoming height is unbounded; an unbounded body field lays every line out at once
                // and never scrolls its own cursor into view. The box must also finish inside the
                // visible viewport, otherwise the cursor sits at the bottom of a box that is itself
                // below the fold. COMPACT_BODY_CHROME is the measured height of the title, tag row
                // and photo strip that sit above it.
                val compactBodyHeight = (maxHeight - COMPACT_BODY_CHROME).coerceAtLeast(120.dp)
                val compactScroll = rememberScrollState()
                val photoTargetPx = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(320, 1440)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            when {
                                shortViewport -> Modifier.verticalScroll(compactScroll)
                                else -> Modifier
                            },
                        ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = ProductSize.screenHorizontalPadding),
                    ) {
                        if (state.lifecycleState == MemoLifecycleState.ARCHIVED) {
                            Text(
                                "アーカイブ中",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp).testTag("memo_archived_indicator"),
                            )
                        }
                        Box(Modifier.fillMaxWidth()) {
                            EditorTextField(
                                value = state.title,
                                onValueChange = onTitleChange,
                                hint = "タイトル",
                                textStyle = MaterialTheme.typography.headlineSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                modifier = Modifier.fillMaxWidth()
                                    .padding(top = ProductSpacing.xs, bottom = ProductSpacing.sm)
                                    .onFocusChanged { titleFocused = it.isFocused }
                                    .testTag("memo_title"),
                                singleLine = true,
                                readOnly = playbackActive || readingMode,
                                focusRequester = titleFocusRequester,
                                caretToEndKey = titleCaretToEnd,
                            )
                            // In 閲覧モード the title is one tap from the pen; under a voice or a
                            // playback the page keeps itself, as it does for the double tap.
                            if (readingMode && !speechActive && !playbackActive) {
                                Box(
                                    Modifier.matchParentSize()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            pendingWritingFocus = WritingFocus.Title
                                            readingMode = false
                                        }
                                        .testTag("memo_title_reading_tap"),
                                )
                            }
                        }
                        // While the keyboard is up the writing surface is what matters: tags and photos
                        // are not typing tools, and on a phone they consume the whole remaining height.
                        // They come back the moment the keyboard is dismissed.
                        if (!keyboardVisible) {
                            // The wall's cards keep wearing their tags; this only clears the
                            // writing surface for whoever asked 設定 for it.
                            if (!hideTags) {
                                EditorTagRow(
                                    tags = tags,
                                    enabled = state.exists,
                                    onAdd = { showTagPicker = true },
                                    onRemove = onDetachTag,
                                    editable = !readingMode,
                                )
                            }
                            MemoLinkRow(links = links, onOpen = onOpenLinkedMemo)
                        }
                    }
                    // A memo's photos are blocks of its text (docs/MEMO_CONTENT_BLOCKS.md). Only a
                    // record without blocks — an outline opened here — shows them as the strip.
                    if (state.blocks.isEmpty() && !keyboardVisible) {
                        PhotoAttachmentStrip(
                            photos = photos,
                            imageLoader = imageLoader,
                            editable = !readingMode && state.lifecycleState != MemoLifecycleState.TRASHED,
                            importing = state.isImportingPhotos,
                            onDelete = onDeletePhoto,
                            reorderEnabled = !readingMode && state.lifecycleState == MemoLifecycleState.ACTIVE,
                            onReorder = onReorderPhotos,
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = ProductSize.screenHorizontalPadding)
                                .padding(vertical = 12.dp),
                        )
                    }
                    // The photos of a memo written in blocks, on the reading page, and their viewer.
                    val photosById = photos.associateBy { it.id }
                    val orderedPhotos = state.blocks.filterIsInstance<MemoBlock.Photo>().mapNotNull { photosById[it.attachmentId] }
                    var readingViewer by remember { mutableStateOf<Int?>(null) }
                    val readingPhotos: @Composable (List<Long>) -> Unit = { ids ->
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ids.forEach { attachmentId ->
                                photosById[attachmentId]?.let { photo ->
                                    InlinePhoto(
                                        photo = photo,
                                        imageLoader = imageLoader,
                                        targetPx = photoTargetPx,
                                        description = "添付写真 ${orderedPhotos.indexOf(photo) + 1} / ${orderedPhotos.size}",
                                        onClick = { readingViewer = orderedPhotos.indexOf(photo) },
                                    )
                                }
                            }
                        }
                    }
                    readingViewer?.let { index ->
                        if (orderedPhotos.isEmpty()) {
                            readingViewer = null
                        } else {
                            PhotoViewer(
                                photos = orderedPhotos,
                                initialIndex = index.coerceIn(orderedPhotos.indices),
                                imageLoader = imageLoader,
                                editable = false,
                                onDismiss = { readingViewer = null },
                                onDelete = onDeletePhoto,
                            )
                        }
                    }
                    // A box ticked on the reading page: in a memo written in blocks, the page line
                    // is found in its text.
                    val toggleTaskOnPage: (Int) -> Unit = { line ->
                        if (state.blockMode) {
                            MemoContent.lineToBlock(state.blocks, line)?.let { (blockId, local) ->
                                val text = (state.blocks.firstOrNull { it.id == blockId } as? MemoBlock.Text)?.text
                                if (text != null) {
                                    val updated = WorkOutlineEditing.toggleTaskAt(text, local, outlineSymbols)
                                    if (updated != text) onUpdateBlockText(blockId, updated)
                                }
                            }
                        } else {
                            val updated = WorkOutlineEditing.toggleTaskAt(bodyField.text, line, outlineSymbols)
                            if (updated != bodyField.text) commitBody(bodyField.copy(text = updated), true)
                        }
                    }
                    // A box ticked in the text being written.
                    val toggleTaskInField: (Int) -> Unit = { line ->
                        val updated = WorkOutlineEditing.toggleTaskAt(bodyField.text, line, outlineSymbols)
                        if (updated != bodyField.text) {
                            val end = updated.length
                            commitBody(
                                bodyField.copy(
                                    text = updated,
                                    selection = TextRange(
                                        bodyField.selection.start.coerceAtMost(end),
                                        bodyField.selection.end.coerceAtMost(end),
                                    ),
                                ),
                                true,
                            )
                        }
                    }
                    val bodySurface: @Composable () -> Unit = {
                        if (readingMode) {
                            MemoReadingView(
                                body = state.body,
                                collapsed = collapsedHeadings.toSet(),
                                onToggleFold = { line ->
                                    if (!collapsedHeadings.remove(line)) collapsedHeadings.add(line)
                                },
                                onToggleTask = toggleTaskOnPage,
                                onOpenLink = { title ->
                                    linkTargetsByTitle[NoteLink.key(title)]?.let {
                                        onOpenLinkedMemo(it.id)
                                    }
                                },
                                isLinkResolved = { linkTargetsByTitle.containsKey(NoteLink.key(it)) },
                                modifier = Modifier.fillMaxSize(),
                                linkedNumbers = comments.mapNotNull(MemoCommentEntity::linkNo).toSet(),
                                onEmitLink = onEmitLink,
                                scrollToLine = readingScrollTarget,
                                onScrolledToLine = { readingScrollTarget = null },
                                followLine = if (speechActive) speechFollowLine else null,
                                // While a voice is reading or comments are flowing, a tap
                                // belongs to that playback — the page must not fall out of
                                // 閲覧モード under someone reaching for a marker or a pause.
                                onDoubleTapEdit = if (speechActive || playbackActive) {
                                    null
                                } else {
                                    { readingMode = false }
                                },
                                // A written line is its own door: the pen lands on that line.
                                onDoubleTapLine = if (speechActive || playbackActive) {
                                    null
                                } else {
                                    { line ->
                                        pendingWritingFocus = WritingFocus.Body(line)
                                        readingMode = false
                                    }
                                },
                                // While the voice reads, a tap on a line re-aims it there
                                // — the same gesture the note reader answers to.
                                onTapLine = if (speechActive) {
                                    { line -> onJumpSpeechToLine(line) }
                                } else {
                                    null
                                },
                                photoBreaks = if (state.blockMode) MemoContent.photoBreaks(state.blocks) else emptyMap(),
                                photoContent = if (state.blockMode) readingPhotos else null,
                            )
                        } else if (state.blockMode) {
                            MemoBlockColumn(
                                blocks = state.blocks,
                                activeTextId = state.activeTextId,
                                activeField = bodyField,
                                onActiveFieldChange = onBodyFieldChange,
                                onActivate = activateText,
                                readOnly = playbackActive,
                                onFocused = { bodyFocused = it },
                                focusRequesterFor = blockRequesterFor,
                                onToggleTaskActive = toggleTaskInField,
                                onToggleTaskIn = { blockId, line ->
                                    val text = (state.blocks.firstOrNull { it.id == blockId } as? MemoBlock.Text)?.text
                                    if (text != null) {
                                        val updated = WorkOutlineEditing.toggleTaskAt(text, line, outlineSymbols)
                                        if (updated != text) onUpdateBlockText(blockId, updated)
                                    }
                                },
                                onBackspaceAtStart = onMergeTextBlock,
                                photos = photos,
                                imageLoader = imageLoader,
                                photoEditable = state.lifecycleState == MemoLifecycleState.ACTIVE && !playbackActive,
                                viewerEditable = state.lifecycleState != MemoLifecycleState.TRASHED,
                                importing = state.isImportingPhotos,
                                onReorderPhotos = onReorderPhotos,
                                onDeletePhoto = onDeletePhoto,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            MemoBodyEditor(
                                bodyField,
                                onBodyFieldChange,
                                readOnly = playbackActive,
                                onFocused = { bodyFocused = it },
                                focusRequester = bodyFocusRequester,
                                onToggleTask = toggleTaskInField,
                            )
                        }
                    }
                    when (displayMode) {
                        PlaybackDisplayMode.INLINE -> Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (shortViewport) Modifier.height(compactBodyHeight)
                                    else Modifier.weight(1f),
                                )
                                .onSizeChanged(updatePlaybackBounds),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = ProductSize.screenHorizontalPadding),
                            ) {
                                bodySurface()
                            }
                            // The page dims and the letters go white while comments fly:
                            // the video site's look, carried onto the reading page itself.
                            // The dim and the flight own the whole width, like the note
                            // reader; only the written page keeps its gutters.
                            playbackLayer(
                                Modifier.fillMaxSize(),
                                stageCommentRendererColors(),
                                true,
                                COMMENT_BASE_FONT_SIZE,
                            )
                        }
                        PlaybackDisplayMode.STAGE -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (shortViewport) Modifier.height(300.dp)
                                    else Modifier.weight(1f),
                                ),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // Only the stage owns the full viewport. Its siblings
                                    // keep their gutters instead of constraining this frame.
                                    .aspectRatio(16f / 9f)
                                    .background(stagePalette.background)
                                    .testTag("comment_stage")
                                    .onSizeChanged(updatePlaybackBounds),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier.matchParentSize().testTag(
                                        "comment_stage_background_${appSettings.stageBackground.storageId}",
                                    ),
                                )
                                if (playbackStatus == PlaybackStatus.IDLE) {
                                    Text(
                                        "コメントステージ",
                                        color = stagePalette.placeholderText,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                                playbackLayer(
                                    Modifier.fillMaxSize(),
                                    stagePalette.commentColors,
                                    false,
                                    playbackBaseFontSize,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(horizontal = ProductSize.screenHorizontalPadding)
                                    .padding(top = 8.dp),
                            ) {
                                bodySurface()
                            }
                        }
                        PlaybackDisplayMode.OVERLAY -> Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ProductSize.screenHorizontalPadding)
                                .then(
                                    if (shortViewport) Modifier.height(compactBodyHeight)
                                    else Modifier.weight(1f),
                                ),
                        ) {
                            bodySurface()
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().padding(padding)) {
            val reference = splitReference
            if (reference == null) {
                editorBody()
            } else {
                SplitWorkspaceScaffold(
                    reference = reference,
                    ratio = splitRatio,
                    onRatioChange = { value ->
                        splitRatio = value
                        splitSession.updateRatio(value)
                    },
                    onChangeReference = { showSplitPicker = true },
                    onEditReference = {
                        when (reference.kind) {
                            SplitReferenceKind.MEMO, SplitReferenceKind.OUTLINE -> {
                                splitSession.open(
                                    primaryMemoId = reference.id,
                                    reference = SplitReference(SplitReferenceKind.MEMO, memoId),
                                    ratio = splitRatio,
                                )
                                onSwapPrimaryMemo(reference.id)
                            }
                            SplitReferenceKind.NOTE -> {
                                closeSplit()
                                onOpenNoteAsPrimary(reference.id)
                            }
                        }
                    },
                    onClose = closeSplit,
                    onRetarget = { linkedId ->
                        val next = SplitReference(SplitReferenceKind.MEMO, linkedId)
                        if (SplitWorkspacePolicy.canReference(memoId, next)) {
                            splitReference = next
                            splitSession.open(memoId, next, splitRatio)
                        }
                    },
                    primary = editorBody,
                )
            }
            when (shortcutPanel) {
                EditorShortcutPanel.COMMENT_LINK -> CommentLinkPickerSheet(
                    comments = comments,
                    onPick = { comment ->
                        shortcutPanel = null
                        val insertMarker = { number: Int ->
                            applyOutline(
                                WorkOutlineEditing.insert(
                                    OutlineEdit(
                                        bodyField.text,
                                        bodyField.selection.start,
                                        bodyField.selection.end,
                                    ),
                                    "[R$number]",
                                ),
                            )
                        }
                        val number = comment.linkNo
                        if (number != null) {
                            insertMarker(number)
                        } else {
                            // An unlinked comment links on the way: one tap gives it its number
                            // and writes the marker where the caret stands.
                            onLinkComment(comment) { assigned ->
                                if (assigned != null) {
                                    insertMarker(assigned)
                                } else {
                                    snackbarScope.launch {
                                        snackbarHostState.showSnackbar("リンクを設定できませんでした")
                                    }
                                }
                            }
                        }
                    },
                    onOpenComments = {
                        shortcutPanel = null
                        showCommentsSheet = true
                    },
                    onDismiss = { shortcutPanel = null },
                )
                EditorShortcutPanel.TEMPLATE -> MemoTemplateSheet(
                    templates = templates,
                    memoBody = state.body,
                    onPick = { template ->
                        shortcutPanel = null
                        applyOutline(
                            WorkOutlineEditing.insert(
                                OutlineEdit(bodyField.text, bodyField.selection.start, bodyField.selection.end),
                                template.body,
                            ),
                        )
                    },
                    onDelete = onDeleteTemplate,
                    onCreate = onCreateTemplate,
                    onDismiss = { shortcutPanel = null },
                )
                EditorShortcutPanel.MEMO_LINK -> MemoLinkPickerSheet(
                    targets = linkTargets,
                    onPick = { title ->
                        shortcutPanel = null
                        applyOutline(
                            NoteLink.insert(
                                OutlineEdit(bodyField.text, bodyField.selection.start, bodyField.selection.end),
                                title,
                            ),
                        )
                    },
                    onDismiss = { shortcutPanel = null },
                )
                null -> Unit
            }
        }
        }
    }

    if (showSplitPicker) {
        SplitReferencePickerSheet(
            primaryMemoId = memoId,
            onPick = { picked ->
                if (SplitWorkspacePolicy.canReference(memoId, picked)) {
                    splitReference = picked
                    splitSession.open(memoId, picked, splitRatio)
                }
                showSplitPicker = false
            },
            onDismiss = { showSplitPicker = false },
        )
    }




    if (showTemplateSaveDialog) {
        SaveTemplateDialog(
            suggestedName = state.title,
            onSave = { name ->
                showTemplateSaveDialog = false
                onSaveTemplate(name) { saved ->
                    snackbarScope.launch {
                        snackbarHostState.showSnackbar(
                            if (saved) {
                                "テンプレートに保存しました"
                            } else {
                                "本文が空のためテンプレートにできません"
                            },
                        )
                    }
                }
            },
            onDismiss = { showTemplateSaveDialog = false },
        )
    }


    if (showCommentsSheet) {
        UserCommentsSheet(
            comments = comments,
            onDismiss = { showCommentsSheet = false },
            onAdd = { text, appearance, motion, onResult ->
                onAddComment(text, appearance, motion) { saved ->
                    if (saved) {
                        snackbarScope.launch {
                            snackbarHostState.showSnackbar("コメントを追加しました")
                        }
                    }
                    onResult(saved)
                }
            },
            onDelete = onDeleteComment,
            onLinkComment = { comment ->
                onLinkComment(comment) { number ->
                    snackbarScope.launch {
                        snackbarHostState.showSnackbar(
                            if (number != null) {
                                "R$number を割り当てました。バッジをタップすると本文に挿入できます"
                            } else {
                                "リンクを設定できませんでした"
                            },
                        )
                    }
                }
            },
            onUnlinkComment = onUnlinkComment,
            onLinkBadgeTap = { comment ->
                val number = comment.linkNo ?: return@UserCommentsSheet
                showCommentsSheet = false
                if (readingMode) {
                    val line = state.body.lines().indexOfFirst { lineText ->
                        CommentLinkMarkers.markersIn(lineText).any { it.number == number }
                    }
                    if (line >= 0) {
                        readingScrollTarget = line
                    } else {
                        snackbarScope.launch {
                            snackbarHostState.showSnackbar("本文に [R$number] がありません")
                        }
                    }
                } else {
                    applyOutline(
                        WorkOutlineEditing.insert(
                            OutlineEdit(
                                bodyField.text,
                                bodyField.selection.start,
                                bodyField.selection.end,
                            ),
                            "[R$number]",
                        ),
                    )
                }
            },
            markerCountOf = { number ->
                CommentLinkMarkers.occurrenceCount(state.body, number)
            },
            onUpdateExpression = { comment, appearance, motion ->
                onUpdateCommentExpression(comment, appearance, motion) { saved ->
                    snackbarScope.launch {
                        snackbarHostState.showSnackbar(
                            if (saved) "コメントの表現を変更しました"
                            else "表現を変更できませんでした",
                        )
                    }
                }
            },
            expressionEditable = state.lifecycleState != MemoLifecycleState.TRASHED,
            keepExpression = keepExpression,
            keptExpression = keptExpression,
            onKeepExpressionChange = onKeepExpressionChange,
            onKeepExpression = onKeepExpression,
            favoriteComments = favoriteComments,
            onAddFavorite = onAddFavorite,
            onRemoveFavorite = onRemoveFavorite,
            onReorderStart = onBeginCommentReorder,
            onReorder = onReorderComments,
            onResetOrder = onResetCommentOrder,
        )
    }

    if (showOutlineGuide) {
        val dismissOutlineGuide: () -> Unit = {
            showOutlineGuide = false
            overlayIntroScope.launch {
                overlayApp.settingsRepository.setHasSeenOutlineGuide(true)
            }
        }
        val guideLines = listOf(
            OutlineSymbolRole.HEADING to "見出し",
            OutlineSymbolRole.ITEM to "項目",
            OutlineSymbolRole.TASK to "チェック",
            OutlineSymbolRole.IMPORTANT to "重要",
            OutlineSymbolRole.QUESTION to "疑問",
        ).joinToString("\n") { (role, word) -> outlineSymbols.marker(role) + word }
        AlertDialog(
            onDismissRequest = dismissOutlineGuide,
            title = { Text("書いた文章をコメントにできます") },
            text = {
                Text(
                    "行の先頭に記号を付けると、その行をコメントとして流せます。\n\n" +
                        guideLines + "\n\n" +
                        "ショートカットバーから入力できるので、記号を覚える必要はありません。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = dismissOutlineGuide,
                    modifier = Modifier.testTag("outline_guide_try"),
                ) { Text("使ってみる") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        dismissOutlineGuide()
                        onOpenAppSettings()
                    },
                    modifier = Modifier.testTag("outline_guide_details"),
                ) { Text("詳しく見る") }
            },
            modifier = Modifier.testTag("outline_first_use_guide"),
        )
    }

    if (showPlaybackSettings) {
        PlaybackSettingsSheet(
            contentMode = contentMode,
            displayMode = displayMode,
            commentScope = appSettings.commentScope,
            onCommentScopeChange = { scope ->
                if (scope != appSettings.commentScope) {
                    onStop()
                    onCommentScopeChange(scope)
                }
            },
            onContentModeChange = { mode ->
                if (mode != contentMode) {
                    onStop()
                    onContentModeChange(mode)
                }
            },
            onDisplayModeChange = { mode ->
                if (mode != displayMode) {
                    onStop()
                    onDisplayModeChange(mode)
                }
            },
            onDismiss = { showPlaybackSettings = false },
        )
    }

    if (showOverlaySetup) {
        val options = OverlayPlaybackOptions(
            contentMode = contentMode,
            displayRegion = OverlayDisplayRegion.fromStorageId(overlayRegionId),
            density = OverlayDensity.fromStorageId(overlayDensityId),
        )
        // Parsed and planned once per sheet opening: the sheet cannot edit the body, so
        // re-deriving the timeline on every recomposition of the editor bought nothing.
        val sourceTimeline = remember(contentMode) { onCreateTimeline(contentMode) }
        val usableBounds = OverlayWindowPolicy.usableBounds(context, windowManager)
        val regionBounds = OverlayWindowPolicy.regionBounds(usableBounds, options.displayRegion)
        val textMetrics = remember(
            sourceTimeline,
            textMeasurer,
            density,
            regionBounds.width,
            commentFontFamily,
        ) {
            measureCommentTextMetricsPx(
                sourceTimeline,
                textMeasurer,
                density,
                regionBounds.width.toFloat(),
                commentFontFamily,
            )
        }
        val previewPlan = remember(sourceTimeline, options, regionBounds, textMetrics, density) {
            overlayPlanFactory.create(
                sourceTimeline = sourceTimeline,
                options = options,
                renderWidthsPx = textMetrics.mapValues { it.value.renderWidthPx },
                textMetrics = textMetrics,
                containerWidthPx = regionBounds.width.toFloat(),
                availableHeightPx = regionBounds.height.toFloat(),
                minimumLaneHeightPx = with(density) { MINIMUM_LANE_HEIGHT.toPx() },
                verticalSafetyGapPx = with(density) { LANE_VERTICAL_SAFETY_GAP.toPx() },
                desiredWaveAmplitudePx = with(density) {
                    COMMENT_WAVE_DESIRED_AMPLITUDE.toPx()
                },
            )
        }
        OverlaySetupSheet(
            options = options,
            previewPlan = previewPlan,
            onContentModeChange = { onContentModeChange(it) },
            onRegionChange = { onOverlayRegionChange(it.storageId) },
            onDensityChange = { onOverlayDensityChange(it.storageId) },
            onStart = {
                onSaveForOverlay { memoId ->
                    if (memoId == null) {
                        snackbarScope.launch {
                            snackbarHostState.showSnackbar("メモを保存できませんでした")
                        }
                    } else {
                        showOverlaySetup = false
                        submitOverlayRequest(OverlayPlaybackRequest.create(memoId, options))
                    }
                }
            },
            onDismiss = { showOverlaySetup = false },
        )
    }

    OverlayLaunchGates(
        overlayLaunch = overlayLaunch,
        onStopOverlay = onStopOverlay,
        snackbarHostState = snackbarHostState,
    )

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("メモをゴミ箱へ移動しますか？") },
            text = { Text("ゴミ箱から復元できます。") },
            confirmButton = { DestructiveTextButton("ゴミ箱へ移動", onMoveToTrash) },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("キャンセル") }
            },
        )
    }

    if (showTagPicker) {
        MemoTagPickerSheet(
            tags = allTags,
            assignedTagIds = tags.mapTo(hashSetOf(), TagEntity::id),
            onToggle = { tag, selected ->
                if (selected) onAttachTag(tag.id) else onDetachTag(tag.id)
            },
            onCreate = { showCreateTagDialog = true },
            onDismiss = { showTagPicker = false },
        )
    }

    if (showCreateTagDialog) {
        CreateTagDialog(
            onCreate = { value, finished ->
                onCreateTag(value) { success ->
                    finished()
                    if (success) showCreateTagDialog = false
                }
            },
            onDismiss = { showCreateTagDialog = false },
        )
    }

    if (showSpeechDialog) {
        SpeechStartDialog(
            hasComments = comments.isNotEmpty(),
            onStart = { includeComments ->
                if (readingMode) onStartSpeechWithLinks(includeComments) else onStartSpeech(includeComments)
            },
            onDismiss = { showSpeechDialog = false },
        )
    }
}

/**
 * 読み上げ: title and body, with the comments if asked. [onStart] says whether the reading
 * began; the dialog closes only then, so a voice that could not start leaves its reason on
 * the screen. Shared by the memo editor and the outliner.
 */
@Composable
internal fun SpeechStartDialog(
    hasComments: Boolean,
    onStart: (includeComments: Boolean) -> Boolean,
    onDismiss: () -> Unit,
) {
    var includeComments by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("memo_speech_dialog"),
        title = { Text("メモを読み上げる") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("タイトルと本文を読み上げます。")
                FilterChip(
                    selected = includeComments,
                    onClick = { includeComments = !includeComments },
                    label = { Text("コメントも含める") },
                    enabled = hasComments,
                    modifier = Modifier.testTag("memo_speech_include_comments"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (onStart(includeComments)) onDismiss() },
                modifier = Modifier.testTag("start_memo_speech"),
            ) { Text("読み上げる") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserCommentsSheet(
    comments: List<MemoCommentEntity>,
    onDismiss: () -> Unit,
    onAdd: (String, CommentAppearance, CommentMotion, (Boolean) -> Unit) -> Unit,
    onDelete: (MemoCommentEntity) -> Unit,
    onUpdateExpression: (MemoCommentEntity, CommentAppearance, CommentMotion) -> Unit,
    onLinkComment: (MemoCommentEntity) -> Unit,
    onUnlinkComment: (MemoCommentEntity) -> Unit,
    onLinkBadgeTap: (MemoCommentEntity) -> Unit,
    markerCountOf: (Int) -> Int,
    expressionEditable: Boolean,
    keepExpression: Boolean,
    keptExpression: Pair<CommentAppearance, CommentMotion>?,
    onKeepExpressionChange: (Boolean) -> Unit,
    onKeepExpression: (CommentAppearance, CommentMotion) -> Unit,
    favoriteComments: List<FavoriteComment>,
    onAddFavorite: (String) -> Unit,
    onRemoveFavorite: (FavoriteComment) -> Unit,
    onReorderStart: () -> Unit,
    onReorder: (List<Long>) -> Unit,
    onResetOrder: () -> Unit,
) {
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    // The composer opens wearing the kept expression when keeping is on — that is what 保持 is.
    val initialAppearance =
        if (keepExpression) keptExpression?.first ?: CommentAppearance.Default
        else CommentAppearance.Default
    val initialMotion =
        if (keepExpression) keptExpression?.second ?: CommentMotion.Default
        else CommentMotion.Default
    var draftAppearance by remember { mutableStateOf(initialAppearance) }
    var draftMotion by remember { mutableStateOf(initialMotion) }
    var addingFavorite by remember { mutableStateOf(false) }
    var deletingFavorite by remember { mutableStateOf<FavoriteComment?>(null) }
    var editExpressionFor by remember { mutableStateOf<MemoCommentEntity?>(null) }
    var editComposerExpression by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<MemoCommentEntity?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }
    // Arranging is a mode, the way it is everywhere else in this app: the handles and the
    // way back to creation order appear together, and a row at rest carries nothing to press.
    var arranging by remember { mutableStateOf(false) }
    var heldComment by remember { mutableStateOf<MemoCommentEntity?>(null) }
    var localComments by remember { mutableStateOf(comments) }
    var pendingPersistedOrder by remember { mutableStateOf<List<Long>?>(null) }
    val incomingOrder = comments.map(MemoCommentEntity::id)
    val commentListState = rememberLazyListState()
    val commentScope = rememberCoroutineScope()

    fun moveComment(commentId: Long, direction: Int): Boolean {
        val fromIndex = localComments.indexOfFirst { it.id == commentId }
        if (fromIndex < 0) return false
        val targetIndex = (fromIndex + direction).coerceIn(localComments.indices)
        if (targetIndex == fromIndex) return false
        localComments = localComments.toMutableList().apply {
            add(targetIndex, removeAt(fromIndex))
        }
        return true
    }

    fun persistCurrentOrder() {
        val reorderedIds = localComments.map(MemoCommentEntity::id)
        if (reorderedIds != incomingOrder) {
            pendingPersistedOrder = reorderedIds
            onReorder(reorderedIds)
        }
    }

    fun moveAndPersist(commentId: Long, direction: Int): Boolean {
        onReorderStart()
        return moveComment(commentId, direction).also { moved ->
            if (moved) persistCurrentOrder()
        }
    }

    // The handle carries its row as a floating card and the others slide as it crosses them —
    // the same walk 設定's ショートカットバー takes (2026-09-22); release writes the order once.
    val localNow = rememberUpdatedState(localComments)
    val persistNow = rememberUpdatedState { persistCurrentOrder() }
    val startNow = rememberUpdatedState(onReorderStart)
    val commentDrag = remember(commentListState) {
        CardDragController(
            scope = commentScope,
            slots = {
                cardSlots(commentListState.layoutInfo.visibleItemsInfo.map { Triple(it.key, IntOffset(0, it.offset), IntSize(Int.MAX_VALUE / 2, it.size)) }) { key -> key as? Long }
            },
            onReorder = { persistNow.value() },
            onCrossed = { id, targetId ->
                startNow.value()
                val shown = localNow.value
                val from = shown.indexOfFirst { it.id == id }
                val to = shown.indexOfFirst { it.id == targetId }
                if (from >= 0 && to >= 0) repeat(kotlin.math.abs(to - from)) { moveComment(id, if (to > from) 1 else -1) }
            },
        )
    }
    val commentLiftPx = with(LocalDensity.current) { CARD_LIFT.toPx() }

    LaunchedEffect(comments, commentDrag.carriedId, pendingPersistedOrder) {
        if (pendingPersistedOrder == incomingOrder) pendingPersistedOrder = null
        if (commentDrag.carriedId == null && pendingPersistedOrder == null) {
            localComments = comments
        }
    }

    val latestLocalOrder by rememberUpdatedState(localComments.map(MemoCommentEntity::id))
    val latestIncomingOrder by rememberUpdatedState(incomingOrder)
    val latestPendingOrder by rememberUpdatedState(pendingPersistedOrder)
    DisposableEffect(Unit) {
        onDispose {
            if (latestPendingOrder == null && latestLocalOrder != latestIncomingOrder) {
                onReorder(latestLocalOrder)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("user_comments_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(
                    horizontal = ProductSize.screenHorizontalPadding,
                    vertical = ProductSpacing.sm,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // No header at all. The sheet says what it is through what it holds, its handle
            // already says how it closes, and the two ordering commands live with the list
            // they order. The first thing here is the thing this sheet is for: writing.
            // The palette stands where the comment is being written, the way it does anywhere a
            // comment is composed. It wears the colour it will send, so the choice can be read
            // without opening anything; the whole of it is in the label a screen reader hears.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val expression = commentExpressionSummary(draftAppearance, draftMotion)
                IconButton(
                    onClick = { editComposerExpression = true },
                    modifier = Modifier.size(ProductSize.minimumTouchTarget)
                        .keepsCaret()
                        .semantics { contentDescription = "コメントの表現、$expression" }
                        .testTag("edit_composer_appearance"),
                ) {
                    Icon(
                        Icons.Outlined.Palette,
                        contentDescription = null,
                        tint = inlineCommentRendererColors().textFor(draftAppearance.colorRole),
                    )
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f).testTag("user_comment_input"),
                    label = { Text("新しいコメント") },
                    maxLines = 4,
                )
                IconButton(
                    onClick = {
                        onAdd(draft.text, draftAppearance, draftMotion) { saved ->
                            if (saved) {
                                draft = TextFieldValue()
                                if (keepExpression) {
                                    onKeepExpression(draftAppearance, draftMotion)
                                } else {
                                    draftAppearance = CommentAppearance.Default
                                    draftMotion = CommentMotion.Default
                                }
                            }
                        }
                    },
                    enabled = draft.text.isNotBlank(),
                    modifier = Modifier.size(ProductSize.minimumTouchTarget)
                        .keepsCaret()
                        .testTag("add_user_comment"),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "コメントを追加")
                }
            }
            // The chips are the owner's favourites, not fixtures: a tap writes one, holding
            // one asks about removing it, and the + at the end takes a new one — capped the
            // way the site this row copies caps its own.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                favoriteComments.forEach { favorite ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .keepsCaret()
                            .combinedClickable(
                                onClick = { draft = insertCommentPreset(draft, favorite.text) },
                                onLongClickLabel = "お気に入りから削除",
                                onLongClick = { deletingFavorite = favorite },
                            )
                            .testTag("comment_preset_${favorite.key}"),
                    ) {
                        Text(
                            favorite.label,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            modifier = Modifier.padding(
                                horizontal = ProductSpacing.md,
                                vertical = ProductSpacing.sm,
                            ),
                        )
                    }
                }
                if (favoriteComments.size < FavoriteComment.MAXIMUM) {
                    IconButton(
                        onClick = { addingFavorite = true },
                        modifier = Modifier
                            .size(ProductSize.minimumTouchTarget)
                            .keepsCaret()
                            .testTag("favorite_comment_add"),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = "お気に入りコメントを追加")
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (localComments.isEmpty()) {
                Text(
                    "まだコメントがありません",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(vertical = ProductSpacing.sm)
                        .testTag("comment_empty_state"),
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "コメント一覧",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (arranging) {
                        TextButton(
                            onClick = { showResetDialog = true },
                            enabled = localComments.size > 1,
                            modifier = Modifier.testTag("reset_user_comment_order"),
                        ) {
                            Icon(Icons.Outlined.Restore, contentDescription = null)
                            Text("作成順に戻す", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    if (localComments.size > 1 || arranging) {
                        TextButton(
                            onClick = { arranging = !arranging },
                            modifier = Modifier.testTag("user_comments_arrange_mode"),
                        ) { Text(if (arranging) "完了" else "編集") }
                    }
                }
                LazyColumn(
                    state = commentListState,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(localComments, key = MemoCommentEntity::id) { comment ->
                        val index = localComments.indexOfFirst { it.id == comment.id }
                        val isDragging = commentDrag.isCarried(comment.id)
                        val accessibilityActions = buildList {
                            if (index > 0) {
                                add(
                                    CustomAccessibilityAction("上へ移動") {
                                        moveAndPersist(comment.id, -1)
                                    },
                                )
                            }
                            if (index in 0 until localComments.lastIndex) {
                                add(
                                    CustomAccessibilityAction("下へ移動") {
                                        moveAndPersist(comment.id, 1)
                                    },
                                )
                            }
                        }
                        // A row at rest is only what was written: the words and, when it has
                        // one, its expression. Everything that can be done to it answers to
                        // holding it, the same gesture every card in this app already takes;
                        // the handle belongs to arranging, which is a mode.
                        Surface(
                            modifier = (if (commentDrag.activeId == comment.id) Modifier else Modifier.animateItem())
                                .carriedCard(commentDrag, comment.id, commentLiftPx)
                                .fillMaxWidth()
                                .then(
                                    if (arranging) {
                                        Modifier
                                    } else {
                                        Modifier.combinedClickable(
                                            onClick = {},
                                            onLongClickLabel = "コメントの操作",
                                            onLongClick = { heldComment = comment },
                                        )
                                    },
                                )
                                .testTag("user_comment_row_${comment.id}"),
                            shape = MaterialTheme.shapes.small,
                            color = Color.Transparent,
                            border = BorderStroke(
                                1.dp,
                                if (isDragging) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            ),
                            shadowElevation = if (isDragging) 4.dp else 0.dp,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        comment.linkNo?.let { number ->
                                            // The R badge is also the door: writing inserts
                                            // the marker at the caret, reading walks to it.
                                            Surface(
                                                shape = MaterialTheme.shapes.extraSmall,
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                modifier = Modifier
                                                    .padding(end = 6.dp)
                                                    .combinedClickable(
                                                        onClick = { onLinkBadgeTap(comment) },
                                                    )
                                                    .testTag("comment_link_badge_${comment.id}"),
                                            ) {
                                                Text(
                                                    "R$number",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(
                                                        horizontal = 6.dp,
                                                        vertical = 2.dp,
                                                    ),
                                                )
                                            }
                                        }
                                        Text(
                                            comment.text,
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                    }
                                    val appearance = CommentAppearance.fromEntity(
                                        comment.appearanceColor,
                                        comment.appearanceSize,
                                        comment.appearanceEmphasis,
                                    )
                                    val motion = CommentMotion.fromEntity(
                                        comment.motionSpeed,
                                        comment.motionPlacement,
                                        comment.motionMode,
                                        comment.flowDirection,
                                        comment.flowEffect,
                                    )
                                    if (!appearance.isDefault || !motion.isDefault) {
                                        val summary = commentExpressionSummary(appearance, motion)
                                        Text(
                                            summary,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.semantics {
                                                contentDescription =
                                                    "コメントの表現 $summary"
                                            }.testTag("comment_appearance_summary_${comment.id}"),
                                        )
                                    }
                                }
                                if (arranging) {
                                    Box(
                                        modifier = Modifier
                                            .size(MINIMUM_DRAG_HANDLE_SIZE)
                                            .testTag("reorder_user_comment_${comment.id}")
                                            .semantics {
                                                contentDescription = "コメントを並べ替え"
                                                customActions = accessibilityActions
                                            }
                                            .cardDragHandle(commentDrag, comment.id, enabled = true, longPress = false) { emptyList() },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Outlined.DragHandle, contentDescription = null)
                                    }
                                } else {
                                    Spacer(Modifier.size(ProductSpacing.md))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (editComposerExpression) {
        CommentExpressionSheet(
            initialAppearance = draftAppearance,
            initialMotion = draftMotion,
            previewText = draft.text,
            keepExpression = keepExpression,
            onKeepExpressionChange = { keep ->
                onKeepExpressionChange(keep)
                if (keep) onKeepExpression(draftAppearance, draftMotion)
            },
            onDismiss = { editComposerExpression = false },
            onConfirm = { appearance, motion ->
                draftAppearance = appearance
                draftMotion = motion
                if (keepExpression) onKeepExpression(appearance, motion)
                editComposerExpression = false
            },
        )
    }

    editExpressionFor?.let { comment ->
        CommentExpressionSheet(
            initialAppearance = CommentAppearance.fromEntity(
                comment.appearanceColor,
                comment.appearanceSize,
                comment.appearanceEmphasis,
            ),
            initialMotion = CommentMotion.fromEntity(
                comment.motionSpeed,
                comment.motionPlacement,
                comment.motionMode,
                comment.flowDirection,
                comment.flowEffect,
            ),
            previewText = comment.text,
            onDismiss = { editExpressionFor = null },
            onConfirm = { appearance, motion ->
                onUpdateExpression(comment, appearance, motion)
                editExpressionFor = null
            },
        )
    }

    if (addingFavorite) {
        var favoriteDraft by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingFavorite = false },
            modifier = Modifier.testTag("favorite_comment_dialog"),
            title = { Text("お気に入りコメント") },
            text = {
                Column {
                    OutlinedTextField(
                        value = favoriteDraft,
                        onValueChange = { favoriteDraft = it },
                        modifier = Modifier.fillMaxWidth().testTag("favorite_comment_input"),
                        singleLine = true,
                    )
                    Text(
                        "${favoriteComments.size}/${FavoriteComment.MAXIMUM}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = ProductSpacing.xs),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAddFavorite(favoriteDraft.trim())
                        addingFavorite = false
                    },
                    enabled = favoriteDraft.isNotBlank(),
                    modifier = Modifier.testTag("favorite_comment_confirm"),
                ) { Text("追加") }
            },
            dismissButton = {
                TextButton(onClick = { addingFavorite = false }) { Text("キャンセル") }
            },
        )
    }

    deletingFavorite?.let { favorite ->
        AlertDialog(
            onDismissRequest = { deletingFavorite = null },
            title = { Text("お気に入りから削除") },
            text = { Text("「${favorite.label}」を削除しますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveFavorite(favorite)
                        deletingFavorite = null
                    },
                    modifier = Modifier.testTag("favorite_delete_confirm"),
                ) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { deletingFavorite = null }) { Text("キャンセル") }
            },
        )
    }

    heldComment?.let { held ->
        // What can be done to one comment, reached by holding it — the same gesture and the
        // same kind of answer as holding a memo or a note.
        ModalBottomSheet(
            onDismissRequest = { heldComment = null },
            modifier = Modifier.testTag("user_comment_action_sheet"),
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = ProductSpacing.lg)) {
                Text(
                    held.text,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        start = ProductSize.screenHorizontalPadding,
                        end = ProductSize.screenHorizontalPadding,
                        bottom = ProductSpacing.sm,
                    ),
                )
                if (expressionEditable) {
                    CommentActionRow(
                        label = "表現を変更",
                        icon = Icons.Outlined.Palette,
                        testTag = "comment_action_expression",
                        onClick = {
                            heldComment = null
                            editExpressionFor = held
                        },
                    )
                }
                if (expressionEditable) {
                    if (held.linkNo == null) {
                        CommentActionRow(
                            label = "コメントリンク",
                            icon = Icons.Outlined.Link,
                            testTag = "comment_action_link",
                            onClick = {
                                heldComment = null
                                onLinkComment(held)
                            },
                        )
                    } else {
                        CommentActionRow(
                            label = "リンクを解除",
                            icon = Icons.Outlined.LinkOff,
                            testTag = "comment_action_unlink",
                            onClick = {
                                heldComment = null
                                onUnlinkComment(held)
                            },
                        )
                    }
                }
                CommentActionRow(
                    label = "コメントを削除",
                    icon = Icons.Outlined.Delete,
                    destructive = true,
                    testTag = "comment_action_delete",
                    onClick = {
                        heldComment = null
                        pendingDelete = held
                    },
                )
            }
        }
    }

    pendingDelete?.let { comment ->
        val markerCount = comment.linkNo?.let(markerCountOf) ?: 0
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("このコメントを削除しますか？") },
            text = {
                Column {
                    Text("「${comment.text}」")
                    if (markerCount > 0) {
                        Text(
                            "本文にリンクが${markerCount}箇所あります。削除しても本文の" +
                                "[R${comment.linkNo}]は残りますが、タップしても何も起きなく" +
                                "なります。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(top = ProductSpacing.sm)
                                .testTag("comment_delete_link_warning"),
                        )
                    }
                }
            },
            confirmButton = {
                DestructiveTextButton(
                    label = "削除",
                    onClick = {
                        onDelete(comment)
                        pendingDelete = null
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("キャンセル") }
            },
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("コメントを作成順に戻しますか？") },
            text = { Text("現在設定している再生順は変更されます。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onReorderStart()
                        pendingPersistedOrder = null
                        onResetOrder()
                        showResetDialog = false
                    },
                    modifier = Modifier.testTag("confirm_reset_user_comment_order"),
                ) { Text("作成順に戻す") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("キャンセル") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OverlaySetupSheet(
    options: OverlayPlaybackOptions,
    previewPlan: OverlayPlaybackPlan,
    onContentModeChange: (PlaybackContentMode) -> Unit,
    onRegionChange: (OverlayDisplayRegion) -> Unit,
    onDensityChange: (OverlayDensity) -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("overlay_setup_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = ProductSize.screenHorizontalPadding,
                    vertical = ProductSpacing.sm,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProductSheetHeader("オーバーレイ再生", onDismiss)
            SectionHeader("再生内容")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlaybackContentMode.entries.forEach { mode ->
                    FilterChip(
                        selected = options.contentMode == mode,
                        onClick = { onContentModeChange(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    PlaybackContentMode.BOTH -> "両方"
                                    PlaybackContentMode.WORK_ONLY -> "アウトライン"
                                    PlaybackContentMode.USER_ONLY -> "コメント"
                                },
                            )
                        },
                        modifier = Modifier.testTag("overlay_content_${mode.name.lowercase()}"),
                    )
                }
            }
            SectionHeader("表示範囲")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OverlayDisplayRegion.entries.forEach { region ->
                    FilterChip(
                        selected = options.displayRegion == region,
                        onClick = { onRegionChange(region) },
                        label = {
                            Text(
                                when (region) {
                                    OverlayDisplayRegion.FULL -> "画面全体"
                                    OverlayDisplayRegion.TOP_HALF -> "上半分"
                                    OverlayDisplayRegion.CENTER -> "中央"
                                    OverlayDisplayRegion.BOTTOM_HALF -> "下半分"
                                },
                            )
                        },
                        modifier = Modifier.testTag("overlay_region_${region.storageId}"),
                    )
                }
            }
            SectionHeader("コメント密度")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OverlayDensity.entries.forEach { choice ->
                    FilterChip(
                        selected = options.density == choice,
                        onClick = { onDensityChange(choice) },
                        label = {
                            Text(
                                when (choice) {
                                    OverlayDensity.SPARSE -> "少なめ"
                                    OverlayDensity.STANDARD -> "標準"
                                    OverlayDensity.DENSE -> "多め"
                                },
                            )
                        },
                        modifier = Modifier.testTag("overlay_density_${choice.storageId}"),
                    )
                }
            }
            HorizontalDivider()
            SectionHeader("再生プレビュー")
            Text(
                "コメント ${previewPlan.itemCount}件",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("overlay_preview_item_count"),
            )
            Text(
                if (previewPlan.resolution is OverlayTimelineResolution.TooLong) {
                    "予想再生時間 2分以上"
                } else {
                    val seconds = ceil(previewPlan.estimatedDurationMillis / 1_000.0).toLong()
                    "予想再生時間 約${seconds}秒"
                },
                modifier = Modifier.testTag("overlay_preview_duration"),
            )
            when (previewPlan.resolution) {
                OverlayTimelineResolution.Empty -> Text(
                    "再生できるコメントがありません。",
                    color = MaterialTheme.colorScheme.error,
                )
                is OverlayTimelineResolution.TooLong -> Text(
                    "一度に再生できる時間を超えています。再生内容やコメント密度を変更してください。",
                    color = MaterialTheme.colorScheme.error,
                )
                is OverlayTimelineResolution.CannotRenderFixedComment -> Text(
                    FIXED_COMMENT_TOO_LARGE_MESSAGE,
                    color = MaterialTheme.colorScheme.error,
                )
                is OverlayTimelineResolution.Ready -> Unit
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("キャンセル") }
                FilledTonalButton(
                    onClick = onStart,
                    enabled = previewPlan.canStart,
                    modifier = Modifier.testTag("start_overlay_from_setup"),
                ) { Text("再生") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackSettingsSheet(
    contentMode: PlaybackContentMode,
    displayMode: PlaybackDisplayMode,
    commentScope: WorkCommentScope,
    onContentModeChange: (PlaybackContentMode) -> Unit,
    onDisplayModeChange: (PlaybackDisplayMode) -> Unit,
    onCommentScopeChange: (WorkCommentScope) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("comment_playback_settings_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = ProductSpacing.xl, vertical = ProductSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProductSheetHeader("再生設定", onDismiss)
            SectionHeader("再生内容")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlaybackContentMode.entries.forEach { mode ->
                    FilterChip(
                        selected = contentMode == mode,
                        onClick = { onContentModeChange(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    PlaybackContentMode.BOTH -> "両方"
                                    PlaybackContentMode.WORK_ONLY -> "アウトライン"
                                    PlaybackContentMode.USER_ONLY -> "コメント"
                                },
                            )
                        },
                        modifier = Modifier.testTag("playback_content_${mode.name.lowercase()}"),
                    )
                }
            }
            SectionHeader("本文から流す範囲")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WorkCommentScope.entries.forEach { scope ->
                    FilterChip(
                        selected = commentScope == scope,
                        onClick = { onCommentScopeChange(scope) },
                        label = {
                            Text(
                                when (scope) {
                                    WorkCommentScope.OUTLINE -> "記法のある行だけ"
                                    WorkCommentScope.BODY -> "本文すべて"
                                },
                            )
                        },
                        modifier = Modifier.testTag("comment_scope_${scope.storageId}"),
                    )
                }
            }
            SectionHeader("表示方法")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = displayMode == PlaybackDisplayMode.INLINE,
                    onClick = { onDisplayModeChange(PlaybackDisplayMode.INLINE) },
                    label = { Text("本文上") },
                    modifier = Modifier.testTag("playback_mode_inline"),
                )
                FilterChip(
                    selected = displayMode == PlaybackDisplayMode.STAGE,
                    onClick = { onDisplayModeChange(PlaybackDisplayMode.STAGE) },
                    label = { Text("コメントステージ") },
                    modifier = Modifier.testTag("playback_mode_stage"),
                )
                FilterChip(
                    selected = displayMode == PlaybackDisplayMode.OVERLAY,
                    onClick = { onDisplayModeChange(PlaybackDisplayMode.OVERLAY) },
                    label = { Text("Androidオーバーレイ") },
                    modifier = Modifier.testTag("playback_mode_overlay"),
                )
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End).testTag("close_playback_settings"),
            ) {
                Text("閉じる")
            }
        }
    }
}

@Composable
private fun MemoPlaybackLayer(
    viewModel: MemoEditorViewModel,
    modifier: Modifier,
    colors: CommentRendererColors,
    dimBehind: Boolean,
    baseFontSize: TextUnit,
) {
    val playbackState = viewModel.playbackState.collectAsStateWithLifecycle()
    CommentRenderer(
        animationState = playbackState,
        offsetProvider = viewModel::playbackOffsetPx,
        colors = colors,
        modifier = modifier,
        dimBehind = dimBehind,
        baseFontSize = baseFontSize,
    )
}

@Composable
internal fun MemoBodyEditor(
    body: TextFieldValue,
    onBodyChange: (TextFieldValue) -> Unit,
    onFocused: (Boolean) -> Unit = {},
    readOnly: Boolean,
    onToggleTask: (Int) -> Unit = {},
    focusRequester: FocusRequester? = null,
    onCaret: (CaretReport) -> Unit = {},
    /**
     * A text block in a memo written block by block (docs/MEMO_CONTENT_BLOCKS.md): the field is as
     * tall as its words inside a column that scrolls, at least [flowMinHeight] tall.
     */
    flow: Boolean = false,
    flowMinHeight: Dp = 0.dp,
    /** Backspace with the caret at the very start — the block boundary; null where there is none. */
    onBackspaceAtStart: (() -> Unit)? = null,
    hint: String = "本文を書きはじめる",
    /** The text being written is `memo_body`; the other texts of a block memo carry their own tag. */
    tag: String = "memo_body",
    /**
     * Plain words (a journal, docs/MEMO_CONTENT_BLOCKS.md §11): drawn as typed, with no inline
     * markup and no task boxes — the diary's field has never styled either.
     */
    plain: Boolean = false,
) {
    // A tap on a drawn box ticks its line, as on the reading page, instead of only parking
    // the caret beside it. The boxes are found in the raw body and hit-tested in the drawn
    // layout; the tap is taken in the initial pass so the field never sees it as a caret
    // placement — anywhere else the finger lands, the field behaves as it always has.
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val rawNow = rememberUpdatedState(body.text)
    val boxesNow = rememberUpdatedState(remember(body.text) { WorkOutlineEditing.taskBoxes(body.text) })
    val toggleNow = rememberUpdatedState(onToggleTask)
    val slop = with(LocalDensity.current) { TASK_BOX_SLOP.toPx() }
    // Where the caret is, for a column that scrolls around this field (a memo written in
    // blocks). The layout is of the drawn text, so the raw caret is carried across the task
    // boxes first.
    var fieldHeight by remember { mutableStateOf(0) }
    LaunchedEffect(layout.value, body.selection, fieldHeight) {
        val drawnLayout = layout.value ?: return@LaunchedEffect
        // A field not yet measured says nothing yet.
        if (fieldHeight <= 0) return@LaunchedEffect
        val raw = body.text
        val drawn = TaskGlyphs.rawToDrawn(body.selection.end.coerceIn(0, raw.length), TaskGlyphs.replacements(raw))
            .coerceIn(0, drawnLayout.layoutInput.text.length)
        val cursor = drawnLayout.getCursorRect(drawn)
        onCaret(CaretReport(cursor.bottom, drawnLayout.size.height, fieldHeight, caretTopPx = cursor.top))
    }
    val requester = focusRequester ?: remember { FocusRequester() }
    val selectionNow = rememberUpdatedState(body.selection)
    val backspaceNow = rememberUpdatedState(onBackspaceAtStart)
    val boundary = Modifier.onPreviewKeyEvent { event ->
        val atStart = backspaceNow.value
        if (atStart != null && event.type == KeyEventType.KeyDown && event.key == Key.Backspace &&
            selectionNow.value == TextRange.Zero
        ) {
            atStart()
            true
        } else {
            false
        }
    }
    val size = if (flow) Modifier.fillMaxWidth().heightIn(min = flowMinHeight) else Modifier.fillMaxSize()
    MemoBodyField(body, onBodyChange, readOnly, layout, rawNow, boxesNow, toggleNow, slop, requester,
        onFocused, { fieldHeight = it }, size.then(boundary), hint, tag, plain)
}

@Composable
private fun MemoBodyField(
    body: TextFieldValue,
    onBodyChange: (TextFieldValue) -> Unit,
    readOnly: Boolean,
    layout: MutableState<TextLayoutResult?>,
    rawNow: State<String>,
    boxesNow: State<List<WorkOutlineEditing.TaskBox>>,
    toggleNow: State<(Int) -> Unit>,
    slop: Float,
    focusRequester: FocusRequester,
    onFocused: (Boolean) -> Unit,
    onHeight: (Int) -> Unit,
    size: Modifier,
    hint: String,
    tag: String,
    plain: Boolean,
) {
    EditorTextField(
        value = body,
        onValueChange = onBodyChange,
        hint = hint,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = size
            .onSizeChanged { onHeight(it.height) }
            .pointerInput(readOnly, plain) {
                if (readOnly || plain) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    val hit = taskBoxUnder(down.position, layout.value, rawNow.value, boxesNow.value, slop)
                        ?: return@awaitEachGesture
                    down.consume()
                    val up = waitForUpOrCancellation(pass = PointerEventPass.Initial) ?: return@awaitEachGesture
                    up.consume()
                    toggleNow.value(hit.line)
                }
            }
            .onFocusChanged { onFocused(it.isFocused) }
            .testTag(tag),
        singleLine = false,
        readOnly = readOnly,
        visualTransformation = if (plain) VisualTransformation.None else rememberInlineMarkupTransformation(),
        onTextLayout = { layout.value = it },
        focusRequester = focusRequester,
    )
}

/** The box whose drawn glyph — widened by [slop] on every side — holds [position], if any. */
private fun taskBoxUnder(
    position: Offset,
    layout: TextLayoutResult?,
    raw: String,
    boxes: List<WorkOutlineEditing.TaskBox>,
    slop: Float,
): WorkOutlineEditing.TaskBox? {
    if (layout == null || boxes.isEmpty()) return null
    // The layout is of the drawn text; a box's raw offset is carried across the replacements.
    val replacements = TaskGlyphs.replacements(raw)
    return boxes.firstOrNull { box ->
        val drawn = TaskGlyphs.rawToDrawn(box.rawOffset, replacements)
        if (drawn !in layout.layoutInput.text.indices) return@firstOrNull false
        val glyph = layout.getBoundingBox(drawn)
        val line = layout.getLineForOffset(drawn)
        position.x >= glyph.left - slop && position.x <= glyph.right + slop &&
            position.y >= layout.getLineTop(line) && position.y <= layout.getLineBottom(line)
    }
}

/** How far outside a box's glyph a tap still counts as on it. */
private val TASK_BOX_SLOP = 6.dp

@Composable
internal fun PlaybackTransportActions(
    status: PlaybackStatus,
    canPlay: Boolean,
    overlayMode: Boolean,
    overlayActive: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    if (overlayMode && overlayActive) {
        IconButton(
            onClick = onPlay,
            enabled = canPlay,
            modifier = Modifier.testTag("start_new_overlay_playback"),
        ) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = "新しく再生")
        }
    } else when (status) {
        PlaybackStatus.IDLE -> IconButton(
            onClick = onPlay,
            enabled = canPlay,
            modifier = Modifier.testTag("play_work_comments"),
        ) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = "再生")
        }

        PlaybackStatus.PLAYING -> IconButton(
            onClick = onPause,
            modifier = Modifier.testTag("pause_work_comments"),
        ) {
            Icon(Icons.Outlined.Pause, contentDescription = "一時停止")
        }

        PlaybackStatus.PAUSED -> IconButton(
            onClick = onResume,
            modifier = Modifier.testTag("resume_work_comments"),
        ) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = "再開")
        }
    }
}

/**
 * What the memo's comments are and how they behave.
 *
 * Pinned, not left in the scrolling strip. The strip carries the writing tools and nothing else:
 * what scrolls is what a writer reaches for while writing, and what is pinned is everything that
 * has to be there whether or not the strip has been pushed aside. Comments are half of what this
 * app is, and a setting nobody can find is a setting that does not exist.
 */
/** A toolbar button whose label is the thing it writes. An icon for 「」 would only be 「」 again. */
@Composable
internal fun SymbolButton(
    label: String,
    description: String,
    testTag: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = ProductSpacing.xs),
        modifier = Modifier
            .heightIn(min = ProductSize.minimumTouchTarget)
            .keepsCaret()
            .semantics { contentDescription = description }
            .testTag(testTag),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun CommentActionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
            .padding(
                horizontal = ProductSize.screenHorizontalPadding,
                vertical = ProductSpacing.sm,
            )
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ProductSpacing.lg),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = content)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = content)
    }
}

@Composable
internal fun PlaybackToolbarItems(
    canComment: Boolean,
    onComments: () -> Unit,
    onSettings: () -> Unit,
) {
    CommentsButton(canComment, onComments)
    PlaybackSettingsButton(onSettings)
}

@Composable
internal fun CommentsButton(canComment: Boolean, onComments: () -> Unit) {
    IconButton(
        onClick = onComments,
        enabled = canComment,
        modifier = Modifier.size(ProductSize.minimumTouchTarget)
            .keepsCaret()
            .testTag("open_user_comments"),
    ) {
        Icon(
            Icons.Outlined.ChatBubbleOutline,
            contentDescription = "コメント",
            modifier = Modifier.size(TOOLBAR_GLYPH),
        )
    }
}

@Composable
internal fun PlaybackSettingsButton(onSettings: () -> Unit) {
    IconButton(
        onClick = onSettings,
        modifier = Modifier.size(ProductSize.minimumTouchTarget)
            .keepsCaret()
            .testTag("open_playback_settings"),
    ) {
        Icon(
            Icons.Outlined.Settings,
            contentDescription = "再生設定",
            modifier = Modifier.size(TOOLBAR_GLYPH),
        )
    }
}

/**
 * Ending playback.
 *
 * Pinned outside the scrolling strip. It is the one control here wanted in a hurry, and a control
 * wanted in a hurry cannot be behind a scroll. It lives in the toolbar rather than the top bar
 * because that bar already carries a transport icon, the save status, speech and the overflow.
 */
@Composable
internal fun PlaybackStopButton(
    status: PlaybackStatus,
    overlayMode: Boolean,
    overlayActive: Boolean,
    onStop: () -> Unit,
    onStopOverlay: () -> Unit,
) {
    if (overlayMode && overlayActive) {
        IconButton(
            onClick = onStopOverlay,
            modifier = Modifier.size(ProductSize.minimumTouchTarget)
                .testTag("stop_overlay_playback"),
        ) {
            Icon(Icons.Outlined.Stop, contentDescription = "オーバーレイを停止")
        }
    } else if (status != PlaybackStatus.IDLE) {
        IconButton(
            onClick = onStop,
            modifier = Modifier.size(ProductSize.minimumTouchTarget),
        ) {
            Icon(Icons.Outlined.Stop, contentDescription = "再生を終了")
        }
    }
}

/**
 * The tags this memo carries, and the way to add one.
 *
 * A chip is 32dp tall, and the row is not what the screen is for. Forcing them to the 48dp touch
 * target drew the box at that size too, which put an empty "+ タグ" directly under the title as the
 * loudest thing on a page meant for writing. Material already stretches the touch target past what
 * it draws, so the target survives the smaller chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorTagRow(
    tags: List<TagEntity>,
    enabled: Boolean,
    onAdd: () -> Unit,
    onRemove: (Long) -> Unit,
    editable: Boolean = true,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = ProductSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(ProductSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(ProductSpacing.xs),
    ) {
        tags.forEach { tag ->
            InputChip(
                selected = true,
                onClick = { if (editable) onRemove(tag.id) },
                label = { Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = if (editable) {
                    { Icon(Icons.Outlined.Close, contentDescription = null) }
                } else {
                    null
                },
                modifier = Modifier
                    .testTag("editor_tag_${tag.id}")
                    .semantics {
                        contentDescription = if (editable) "${tag.name}タグを外す" else tag.name
                    },
            )
        }
        if (editable) {
            AssistChip(
                onClick = onAdd,
                enabled = enabled,
                label = { Text(if (enabled) "+ タグ" else "保存後にタグを追加") },
                modifier = Modifier.testTag("open_tag_picker"),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoTagPickerSheet(
    tags: List<TagEntity>,
    assignedTagIds: Set<Long>,
    onToggle: (TagEntity, Boolean) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val visibleTags = tags.filter { TagNameNormalizer.matches(it.name, query) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("tag_picker_sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = ProductSize.screenHorizontalPadding)) {
            ProductSheetHeader("タグを追加", onDismiss)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("タグを検索") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = ProductSpacing.md)
                    .testTag("tag_picker_search"),
            )
            if (visibleTags.isEmpty()) {
                Text(
                    if (query.isBlank()) "タグはまだありません" else "一致するタグがありません",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = ProductSpacing.lg),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(visibleTags, key = TagEntity::id) { tag ->
                        val selected = tag.id in assignedTagIds
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onToggle(tag, !selected) }
                                .heightIn(min = ProductSize.minimumTouchTarget)
                                .testTag("tag_picker_${tag.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = { onToggle(tag, it) },
                            )
                            Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            FilledTonalButton(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth().padding(vertical = ProductSpacing.lg)
                    .heightIn(min = ProductSize.minimumTouchTarget)
                    .testTag("create_tag_from_picker"),
            ) { Text("新しいタグを作成") }
        }
    }
}

@Composable
private fun CreateTagDialog(
    onCreate: (String, () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val validation = TagNameNormalizer.validate(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("create_tag_dialog"),
        title = { Text("新しいタグ") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("タグ名") },
                supportingText = {
                    if (value.isNotEmpty() && validation !is TagNameValidation.Valid) {
                        Text("1〜40文字で入力してください")
                    }
                },
                isError = value.isNotEmpty() && validation !is TagNameValidation.Valid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("create_tag_name"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    submitting = true
                    onCreate(value) { submitting = false }
                },
                enabled = validation is TagNameValidation.Valid && !submitting,
                modifier = Modifier.testTag("confirm_create_tag"),
            ) { Text("作成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
internal fun EditorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier,
    singleLine: Boolean,
    readOnly: Boolean = false,
    focusRequester: FocusRequester? = null,
    /** Each change (from 0) puts the caret at the end of the text — where the writing continues. */
    caretToEndKey: Int = 0,
) {
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    if (field.text != value) field = field.copy(text = value)
    LaunchedEffect(caretToEndKey) {
        if (caretToEndKey != 0) field = field.copy(selection = TextRange(field.text.length))
    }
    EditorTextField(
        value = field,
        onValueChange = { field = it; if (it.text != value) onValueChange(it.text) },
        hint = hint,
        textStyle = textStyle,
        modifier = modifier,
        singleLine = singleLine,
        readOnly = readOnly,
        focusRequester = focusRequester,
    )
}

/**
 * Renders the inline decoration without hiding its markers, so offsets stay one to one and the body
 * reads the same way it is stored. The markers themselves are dimmed rather than removed.
 */
@Composable
internal fun rememberInlineMarkupTransformation(): VisualTransformation {
    // Highlights reuse the comment palette, so a colour means the same thing wherever it appears.
    val palette = inlineCommentRendererColors()
    val markerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val linkColor = MaterialTheme.colorScheme.primary
    // A task box is a quiet mark like the other markers, not a call to action.
    val boxColor = MaterialTheme.colorScheme.onSurfaceVariant
    return remember(palette, markerColor, linkColor, boxColor) {
        VisualTransformation { text ->
            // Task notation draws as the box it means, so every style below speaks in the
            // drawn text's offsets; c() carries a raw offset across the replacements.
            val boxes = TaskGlyphs.replacements(text.text)
            fun c(offset: Int): Int = TaskGlyphs.rawToDrawn(offset, boxes)
            val builder = AnnotatedString.Builder(TaskGlyphs.drawn(text.text, boxes))
            boxes.forEach { box ->
                builder.addStyle(
                    SpanStyle(color = if (box.done) markerColor else boxColor),
                    c(box.rawStart),
                    c(box.rawStart) + 1,
                )
            }
            InlineTextMarkup.spans(text.text).forEach { span ->
                val content = SpanStyle(
                    fontWeight = if (span.style == InlineStyle.BOLD) FontWeight.Bold else null,
                    background = if (span.style == InlineStyle.HIGHLIGHT) {
                        palette.textFor(span.color).copy(alpha = 0.28f)
                    } else {
                        Color.Unspecified
                    },
                )
                builder.addStyle(content, c(span.contentRange.first), c(span.contentRange.last + 1))
                // The markers stay in the text — the file format is theirs — but the eye gets
                // the result, not the notation: bold shows bold, a highlight shows its colour.
                // Drawn transparent at near-zero size they take no room, and the mapping stays
                // the identity, so the caret still walks through them and backspace still
                // removes them one at a time.
                val hiddenMarker = SpanStyle(color = Color.Transparent, fontSize = 0.1.sp)
                builder.addStyle(hiddenMarker, c(span.openMarker.first), c(span.openMarker.last + 1))
                builder.addStyle(hiddenMarker, c(span.closeMarker.first), c(span.closeMarker.last + 1))
            }
            // A reference keeps its brackets, like every other marker, and takes the accent so it
            // reads as something that leads somewhere.
            NoteLink.spans(text.text).forEach { span ->
                builder.addStyle(
                    SpanStyle(color = linkColor),
                    c(span.contentRange.first),
                    c(span.contentRange.last + 1),
                )
                builder.addStyle(
                    SpanStyle(color = markerColor),
                    c(span.openMarker.first),
                    c(span.openMarker.last + 1),
                )
                builder.addStyle(
                    SpanStyle(color = markerColor),
                    c(span.closeMarker.first),
                    c(span.closeMarker.last + 1),
                )
            }
            // A checked task reads as done at a glance without its words leaving the body.
            WorkOutlineEditing.doneTaskRanges(text.text).forEach { line ->
                builder.addStyle(
                    SpanStyle(
                        color = markerColor,
                        textDecoration = TextDecoration.LineThrough,
                    ),
                    c(line.first),
                    c(line.last + 1),
                )
            }
            val mapping = if (boxes.isEmpty()) {
                OffsetMapping.Identity
            } else {
                object : OffsetMapping {
                    override fun originalToTransformed(offset: Int): Int =
                        TaskGlyphs.rawToDrawn(offset, boxes)

                    override fun transformedToOriginal(offset: Int): Int =
                        TaskGlyphs.drawnToRaw(offset, boxes)
                }
            }
            TransformedText(builder.toAnnotatedString(), mapping)
        }
    }
}

@Composable
internal fun EditorTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    hint: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier,
    singleLine: Boolean,
    readOnly: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    /** The screen's own requester when it means to hand this field the keyboard (閲覧モード's doors). */
    focusRequester: FocusRequester? = null,
) {
    val ownRequester = remember { FocusRequester() }
    val focusRequester = focusRequester ?: ownRequester
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.focusRequester(focusRequester),
        textStyle = textStyle,
        singleLine = singleLine,
        readOnly = readOnly,
        visualTransformation = visualTransformation,
        onTextLayout = onTextLayout,
        keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty()) {
                    Text(hint, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inner()
            }
        },
    )
}

/**
 * Reading has one writing action, which is to stop reading. The rest folds the document away or
 * opens it back up, so a long memo can be taken in at a glance.
 */
@Composable
internal fun ReadingToolbar(
    playbackStatus: PlaybackStatus,
    overlayMode: Boolean,
    overlayActive: Boolean,
    canComment: Boolean,
    onStop: () -> Unit,
    onStopOverlay: () -> Unit,
    onComments: () -> Unit,
    onSettings: () -> Unit,
    canFold: Boolean,
    anyFolded: Boolean,
    onFoldAll: () -> Unit,
    onUnfoldAll: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Folding is for memos with headings; prose without any gets no button that
        // would do nothing.
        if (canFold) {
            IconButton(
                onClick = if (anyFolded) onUnfoldAll else onFoldAll,
                modifier = Modifier.size(ProductSize.minimumTouchTarget)
                    .testTag("reading_fold_all"),
            ) {
                Icon(
                    if (anyFolded) Icons.Outlined.UnfoldMore else Icons.Outlined.UnfoldLess,
                    contentDescription = if (anyFolded) "すべて展開" else "すべて折りたたむ",
                )
            }
        }
        Spacer(Modifier.weight(1f))
        PlaybackToolbarItems(canComment, onComments, onSettings)
        PlaybackStopButton(playbackStatus, overlayMode, overlayActive, onStop, onStopOverlay)
        // A compact pill, standing clear of the screen's edge: the row's pinned buttons are
        // 48dp squares, and a full-height button beside them read as a slab — and on a large
        // font it ran off the screen.
        FilledTonalButton(
            onClick = onEdit,
            contentPadding = PaddingValues(horizontal = ProductSpacing.md),
            modifier = Modifier
                .padding(end = ProductSpacing.md)
                .heightIn(min = EDIT_PILL_HEIGHT)
                .testTag("reading_edit"),
        ) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = null,
                modifier = Modifier.size(18.dp).padding(end = 2.dp),
            )
            Text("編集")
        }
    }
}

/**
 * What this memo points at and what points back at it. It sits with the tags and the photos, which
 * are the other things that say where a memo belongs, and it is absent when there is nothing to say.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoLinkRow(links: MemoLinks, onOpen: (Long) -> Unit) {
    if (links.isEmpty) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm).testTag("memo_links"),
        verticalArrangement = Arrangement.spacedBy(ProductSpacing.xs),
    ) {
        if (links.outgoing.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs)) {
                links.outgoing.forEach { link ->
                    AssistChip(
                        onClick = { link.memoId?.let(onOpen) },
                        enabled = link.isResolved,
                        label = {
                            Text(
                                if (link.isResolved) link.title else "${link.title}（未作成）",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                        modifier = Modifier.heightIn(min = ProductSize.minimumTouchTarget)
                            .testTag("memo_link_out_${link.title}"),
                    )
                }
            }
        }
        if (links.backlinks.isNotEmpty()) {
            Text(
                "このメモを参照",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs)) {
                links.backlinks.forEach { backlink ->
                    AssistChip(
                        onClick = { onOpen(backlink.id) },
                        label = {
                            Text(backlink.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                        },
                        modifier = Modifier.heightIn(min = ProductSize.minimumTouchTarget)
                            .testTag("memo_backlink_${backlink.id}"),
                    )
                }
            }
        }
    }
}

/**
 * A picker that keeps the keyboard: unlike a modal sheet — its own window, which takes focus
 * and pulls the IME down — this panel lives in the editor's window, rising over the body with
 * a scrim, so pressing テンプレート・リンク・コメントリンク never interrupts the writing
 * posture. Back closes it; anything focusable inside (the link search) shares the same
 * keyboard the body was using.
 *
 * It still behaves like the 再生設定 sheet to the hand (the user's remark of 2026-09-21): it
 * slides up, wears a drag handle, and a swipe down on the handle or the title — past a quarter
 * of its height, or a quick flick — slides it away; a shorter drag springs back. The lists
 * inside keep their own scrolling, so only the head of the panel drags.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyboardKeepingPanel(
    panelTag: String,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    var panelHeight by remember { mutableIntStateOf(0) }
    var entered by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val slideAway: () -> Unit = {
        if (!leaving) {
            leaving = true
            scope.launch {
                offsetY.animateTo(panelHeight.toFloat().coerceAtLeast(1f), tween(180))
                onDismiss()
            }
        }
    }
    Box(Modifier.fillMaxSize().zIndex(2f)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = slideAway,
                ),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { size ->
                    panelHeight = size.height
                    if (!entered) {
                        entered = true
                        scope.launch {
                            offsetY.snapTo(size.height.toFloat())
                            offsetY.animateTo(0f, tween(220))
                        }
                    }
                }
                .offset { IntOffset(0, offsetY.value.roundToInt().coerceAtLeast(0)) }
                .testTag(panelTag),
            shape = MaterialTheme.shapes.extraLarge.copy(
                bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
                bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp),
            ),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = ProductSize.screenHorizontalPadding),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                if (!leaving) {
                                    scope.launch { offsetY.snapTo((offsetY.value + delta).coerceAtLeast(0f)) }
                                }
                            },
                            onDragStopped = { velocity ->
                                if (offsetY.value > panelHeight * 0.25f || velocity > 1_500f) {
                                    slideAway()
                                } else {
                                    offsetY.animateTo(0f, tween(150))
                                }
                            },
                        ),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().testTag("${panelTag}_handle"),
                        contentAlignment = Alignment.Center,
                    ) {
                        BottomSheetDefaults.DragHandle()
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        IconButton(onClick = slideAway, modifier = Modifier.testTag("${panelTag}_close")) {
                            Icon(Icons.Outlined.Close, contentDescription = "閉じる")
                        }
                    }
                }
                content()
            }
        }
    }
}

@Composable
internal fun CommentLinkPickerSheet(
    comments: List<MemoCommentEntity>,
    onPick: (MemoCommentEntity) -> Unit,
    onOpenComments: () -> Unit,
    onDismiss: () -> Unit,
) {
    KeyboardKeepingPanel(
        panelTag = "comment_link_picker_sheet",
        title = "コメントリンク",
        onDismiss = onDismiss,
    ) {
            Text(
                if (comments.isEmpty()) {
                    "コメントを作ると、その目印をカーソルの位置に差し込めます。"
                } else {
                    "選んだコメントの目印をカーソルの位置に差し込みます。番号のないコメントには番号が付きます。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = ProductSpacing.xs),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .weight(1f, fill = false)
                    .padding(vertical = ProductSpacing.sm),
            ) {
                items(comments, key = MemoCommentEntity::id) { comment ->
                    TextButton(
                        onClick = { onPick(comment) },
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = ProductSize.minimumTouchTarget)
                            .testTag("comment_link_pick_${comment.id}"),
                    ) {
                        comment.linkNo?.let { number ->
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.padding(end = ProductSpacing.xs),
                            ) {
                                Text(
                                    "R$number",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(
                                        horizontal = 6.dp,
                                        vertical = 2.dp,
                                    ),
                                )
                            }
                        }
                        Text(
                            comment.text,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Start,
                        )
                    }
                }
            }
            TextButton(
                onClick = onOpenComments,
                modifier = Modifier.padding(bottom = ProductSpacing.md)
                    .testTag("comment_link_open_comments"),
            ) { Text("コメントを開く") }
    }
}

/**
 * The templates on this device, and a place to write a new one: 「テンプレートを作成」 turns the
 * panel into a name and a body (「このメモの本文を入れる」 copies the memo in), 保存 keeps it and
 * returns to the list with it on top — no settings list on the way (the user's remark of
 * 2026-09-21). A template made here is a plain paste template, so this panel inserts it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoTemplateSheet(
    templates: List<MemoTemplate>,
    onPick: (MemoTemplate) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    memoBody: String = "",
    onCreate: (String, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var newBody by rememberSaveable { mutableStateOf("") }
    var refused by rememberSaveable { mutableStateOf(false) }
    KeyboardKeepingPanel(
        panelTag = "memo_template_sheet",
        title = if (creating) "テンプレートを作成" else "テンプレート",
        onDismiss = onDismiss,
    ) {
        if (creating) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it; refused = false },
                label = { Text("名前") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.xs)
                    .testTag("memo_template_new_name"),
            )
            OutlinedTextField(
                value = newBody,
                onValueChange = { newBody = it; refused = false },
                label = { Text("内容") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm)
                    .testTag("memo_template_new_body"),
            )
            if (memoBody.isNotBlank()) {
                TextButton(
                    onClick = { newBody = memoBody; refused = false },
                    modifier = Modifier.testTag("memo_template_new_from_memo"),
                ) { Text("このメモの本文を入れる") }
            }
            if (refused) {
                Text(
                    "名前と内容を入力してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = ProductSpacing.xs).testTag("memo_template_new_error"),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ProductSpacing.sm),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { creating = false; refused = false },
                    modifier = Modifier.testTag("memo_template_new_back"),
                ) { Text("戻る") }
                Button(
                    onClick = {
                        if (!MemoTemplatePolicy.isUsable(newName, newBody)) {
                            refused = true
                        } else {
                            onCreate(newName, newBody) { saved ->
                                if (saved) {
                                    newName = ""
                                    newBody = ""
                                    creating = false
                                } else {
                                    refused = true
                                }
                            }
                        }
                    },
                    modifier = Modifier.padding(start = ProductSpacing.sm).testTag("memo_template_new_save"),
                ) { Text("保存") }
            }
        } else {
            Text(
                if (templates.isEmpty()) {
                    "「テンプレートを作成」か、メモの「テンプレートとして保存」から作れます。この端末に保存され、バックアップには含まれません。"
                } else {
                    "カーソルの位置に差し込みます。この端末に保存され、バックアップには含まれません。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = ProductSpacing.xs),
            )
            TextButton(
                onClick = { creating = true },
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = ProductSize.minimumTouchTarget)
                    .testTag("memo_template_create"),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.padding(end = ProductSpacing.xs))
                Text("テンプレートを作成", modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .weight(1f, fill = false)
                    .padding(vertical = ProductSpacing.sm),
            ) {
                items(templates, key = MemoTemplate::id) { template ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { onPick(template) },
                            modifier = Modifier.weight(1f)
                                .heightIn(min = ProductSize.minimumTouchTarget)
                                .testTag("memo_template_${template.id}"),
                        ) {
                            Text(
                                template.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Start,
                            )
                        }
                        IconButton(
                            onClick = { onDelete(template.id) },
                            modifier = Modifier.size(ProductSize.minimumTouchTarget)
                                .testTag("memo_template_delete_${template.id}"),
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "${template.name}を削除",
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(ProductSpacing.lg))
        }
    }
}
/** Names the template being kept, so a list of them stays something a person can read. */
@Composable
internal fun SaveTemplateDialog(
    suggestedName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(suggestedName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("テンプレートとして保存") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名前") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("template_name_field"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name) },
                modifier = Modifier.testTag("template_save_confirm"),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
        modifier = Modifier.testTag("template_save_dialog"),
    )
}

/** Picks the memo a reference points at, so a title never has to be typed from memory. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoLinkPickerSheet(
    targets: List<LinkableMemo>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(targets, query) {
        if (query.isBlank()) targets else targets.filter { TagNameNormalizer.matches(it.title, query) }
    }
    KeyboardKeepingPanel(
        panelTag = "memo_link_sheet",
        title = "メモへのリンク",
        onDismiss = onDismiss,
    ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("メモを探す") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm)
                    .testTag("memo_link_search"),
            )
            if (visible.isEmpty()) {
                Text(
                    if (targets.isEmpty()) {
                        "リンクできる他のメモがまだありません。"
                    } else {
                        "一致するメモがありません。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = ProductSpacing.lg),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .weight(1f, fill = false)
                        .padding(vertical = ProductSpacing.sm),
                ) {
                    items(visible, key = LinkableMemo::id) { memo ->
                        TextButton(
                            onClick = { onPick(memo.title) },
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = ProductSize.minimumTouchTarget)
                                .testTag("memo_link_target_${memo.id}"),
                        ) {
                            Text(
                                memo.title,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Start,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(ProductSpacing.lg))
    }
}

/**
 * A live count of what has been written. It sits above the toolbar rather than under the body so it
 * stays visible while the keyboard is up, which is when a writer actually watches it.
 */
@Composable
private fun EditorStatsRow(body: String) {
    val stats = remember(body) { WorkTextStats.of(body) }
    val label = buildString {
        append("${stats.characters}文字")
        if (stats.hasTasks) append(" ・ タスク ${stats.doneTasks}/${stats.totalTasks}")
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = ProductSpacing.md).padding(bottom = 2.dp).testTag("editor_stats"),
        textAlign = TextAlign.End,
    )
}

@Composable
private fun StructureToolbar(
    playbackStatus: PlaybackStatus,
    overlayMode: Boolean,
    overlayActive: Boolean,
    prose: Boolean,
    twoRows: Boolean,
    canComment: Boolean,
    onStop: () -> Unit,
    onStopOverlay: () -> Unit,
    onComments: () -> Unit,
    onSettings: () -> Unit,
    body: TextFieldValue,
    onApply: (OutlineEdit) -> Unit,
    onAddPhoto: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onPickHighlight: (CommentColorRole) -> Unit,
    onPickLink: () -> Unit,
    onPickTemplate: () -> Unit,
    onPickCommentLink: () -> Unit,
    arrangement: EditorToolbarArrangement =
        EditorToolbarArrangement(EditorToolbarOrder.Default),
    symbols: OutlineSymbolSelection = OutlineSymbolSelection.Default,
    chipLabel: OutlineChipLabel = OutlineChipLabel.SYMBOL_AND_WORD,
    onOutlineFirstUse: () -> Unit = {},
    labelChips: ToolbarChipArrangement = ToolbarChips.defaultArrangement(ToolbarChipGroup.LABELS),
    flowChips: ToolbarChipArrangement = ToolbarChips.defaultArrangement(ToolbarChipGroup.FLOW),
) {
    val labels = listOf("見出し", "項目", "補足", "重要", "疑問")
    val edit = OutlineEdit(body.text, body.selection.start, body.selection.end)
    // The bar in the writer's own arrangement (設定 > ショートカットバーの並びと表示), minus
    // what this kind of writing has no use for: prose marks belong to episodes, the outline
    // machinery to loose memos.
    val visibleOrder = arrangement.visible.filter { item ->
        when (item) {
            EditorToolbarItem.PROSE_MARKS -> prose
            EditorToolbarItem.TASK,
            EditorToolbarItem.OUTLINE_LABELS,
            EditorToolbarItem.INDENT,
            -> !prose

            else -> true
        }
    }
    val unit: @Composable (EditorToolbarItem) -> Unit = { item ->
        when (item) {
            EditorToolbarItem.PHOTO -> PhotoToolbarItem(onAddPhoto)
            EditorToolbarItem.HISTORY -> HistoryToolbarItems(canUndo, canRedo, onUndo, onRedo)
            EditorToolbarItem.BOLD -> BoldToolbarItem(edit, body, onApply)
            EditorToolbarItem.HIGHLIGHT -> HighlightToolbarItem(body, onPickHighlight)
            EditorToolbarItem.PROSE_MARKS -> ProseMarkToolbarItems(edit, onApply)
            EditorToolbarItem.TEMPLATE -> TemplateToolbarItem(onPickTemplate)
            EditorToolbarItem.LINK -> LinkToolbarItem(onPickLink)
            EditorToolbarItem.COMMENT_LINK -> CommentLinkToolbarItem(onPickCommentLink)
            EditorToolbarItem.FLOW_MODIFIERS -> FlowModifierToolbarItems(edit, onApply, flowChips)
            EditorToolbarItem.TASK -> TaskToolbarItem(edit, symbols, onApply)
            EditorToolbarItem.OUTLINE_LABELS ->
                OutlineLabelToolbarItems(edit, labels, symbols, chipLabel, onApply, onOutlineFirstUse, labelChips)
            EditorToolbarItem.INDENT -> IndentToolbarItems(edit, onApply)
            // The outliner's own tools never stand on a memo's or a note's bar.
            EditorToolbarItem.MOVE_LINES, EditorToolbarItem.FOLD, EditorToolbarItem.ZOOM -> Unit
        }
    }
    // One row by default: the writing aids scroll, and what has to be reachable at once is
    // pinned to the end. 設定 can stand the bar in two rows instead — the aids above, the
    // inserts and structure below — for whoever wants every tool in sight at the cost of a
    // taller bar.
    if (twoRows) {
        // Which unit stands in which row is the writer's (設定 > ショートカットバーの
        // 並びと表示), and starts as the bar always drew it: the structure shelf below —
        // a memo's outline machinery, an episode's prose marks — everything else above.
        val lower = visibleOrder.filter(arrangement::isLower)
        val upper = visibleOrder.filterNot(arrangement::isLower)
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = ProductSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                upper.forEach { unit(it) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = ProductSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    lower.forEach { unit(it) }
                }
                PlaybackToolbarItems(canComment, onComments, onSettings)
                PlaybackStopButton(playbackStatus, overlayMode, overlayActive, onStop, onStopOverlay)
            }
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            // The 48dp targets already hold 12dp of air around every 24dp glyph; extra spacing
            // between them only pushed the bar's tail out of sight.
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = ProductSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            visibleOrder.forEach { unit(it) }
        }
        PlaybackToolbarItems(canComment, onComments, onSettings)
        PlaybackStopButton(playbackStatus, overlayMode, overlayActive, onStop, onStopOverlay)
    }
}

@Composable
internal fun PhotoToolbarItem(onAddPhoto: () -> Unit) {
    IconButton(
        onClick = onAddPhoto,
        modifier = Modifier.size(ProductSize.minimumTouchTarget)
            .keepsCaret()
            .testTag("toolbar_add_photo"),
    ) {
        Icon(
            Icons.Outlined.AddPhotoAlternate,
            contentDescription = "写真を追加",
            modifier = Modifier.size(TOOLBAR_GLYPH),
        )
    }
}

@Composable
internal fun HistoryToolbarItems(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    IconButton(
        onClick = onUndo,
        enabled = canUndo,
        modifier = Modifier.size(ProductSize.minimumTouchTarget)
            .keepsCaret()
            .testTag("toolbar_undo"),
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.Undo,
            contentDescription = "取り消す",
            modifier = Modifier.size(TOOLBAR_GLYPH),
        )
    }
    IconButton(
        onClick = onRedo,
        enabled = canRedo,
        modifier = Modifier.size(ProductSize.minimumTouchTarget)
            .keepsCaret()
            .testTag("toolbar_redo"),
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.Redo,
            contentDescription = "やり直す",
            modifier = Modifier.size(TOOLBAR_GLYPH),
        )
    }
    ToolbarUnitDivider()
}

@Composable
internal fun BoldToolbarItem(
    edit: OutlineEdit,
    body: TextFieldValue,
    onApply: (OutlineEdit) -> Unit,
) {
    val hasSelection = !body.selection.collapsed
    IconButton(
        onClick = { onApply(InlineTextMarkup.toggle(edit, InlineStyle.BOLD)) },
        enabled = hasSelection,
        modifier = Modifier.size(ProductSize.minimumTouchTarget)
            .keepsCaret()
            .testTag("toolbar_bold"),
    ) {
        Icon(
            Icons.Outlined.FormatBold,
            contentDescription = "太字",
            modifier = Modifier.size(TOOLBAR_GLYPH),
            tint = if (InlineTextMarkup.isActive(edit, InlineStyle.BOLD)) {
                MaterialTheme.colorScheme.primary
            } else {
                LocalContentColor.current
            },
        )
    }
}

@Composable
internal fun HighlightToolbarItem(
    body: TextFieldValue,
    onPickHighlight: (CommentColorRole) -> Unit,
) {
    val hasSelection = !body.selection.collapsed
    // The palette opens as a popup that never takes focus: a bottom sheet is its own
    // window and pulls the keyboard down, which is exactly what a writing tool must
    // not do. The colours are the comment palette's own swatches.
    Box {
        var pickingHighlight by remember { mutableStateOf(false) }
        IconButton(
            onClick = { pickingHighlight = true },
            enabled = hasSelection,
            modifier = Modifier.size(ProductSize.minimumTouchTarget)
                .keepsCaret()
                .testTag("toolbar_highlight"),
        ) {
            Icon(
                Icons.Outlined.BorderColor,
                contentDescription = "ハイライト",
                modifier = Modifier.size(TOOLBAR_GLYPH),
            )
        }
        DropdownMenu(
            expanded = pickingHighlight,
            onDismissRequest = { pickingHighlight = false },
            properties = PopupProperties(focusable = false),
            modifier = Modifier.testTag("highlight_color_sheet"),
        ) {
            val palette = inlineCommentRendererColors()
            Row(
                modifier = Modifier.padding(horizontal = ProductSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(ProductSpacing.sm),
            ) {
                CommentColorRole.entries.take(5).forEach { role ->
                    ExpressionSwatch(
                        selected = false,
                        onClick = {
                            pickingHighlight = false
                            onPickHighlight(role)
                        },
                        swatch = palette.textFor(role),
                        description = "ハイライト ${role.displayName()}",
                        testTag = "highlight_color_${role.storageId}",
                    )
                }
            }
            Row(
                modifier = Modifier.padding(
                    horizontal = ProductSpacing.md,
                    vertical = ProductSpacing.sm,
                ),
                horizontalArrangement = Arrangement.spacedBy(ProductSpacing.sm),
            ) {
                CommentColorRole.entries.drop(5).forEach { role ->
                    ExpressionSwatch(
                        selected = false,
                        onClick = {
                            pickingHighlight = false
                            onPickHighlight(role)
                        },
                        swatch = palette.textFor(role),
                        description = "ハイライト ${role.displayName()}",
                        testTag = "highlight_color_${role.storageId}",
                    )
                }
            }
        }
    }
    ToolbarUnitDivider()
}

@Composable
private fun ProseMarkToolbarItems(edit: OutlineEdit, onApply: (OutlineEdit) -> Unit) {
    // The marks prose is actually written with: an episode needs 「」 and ……
    // on every other line, and none of them belongs on a shopping list.
    SymbolButton("「」", "かぎ括弧", "toolbar_quote") {
        onApply(ProseTyping.wrap(edit, "「", "」"))
    }
    SymbolButton("『』", "二重かぎ括弧", "toolbar_double_quote") {
        onApply(ProseTyping.wrap(edit, "『", "』"))
    }
    SymbolButton("……", "三点リーダ", "toolbar_ellipsis") {
        onApply(WorkOutlineEditing.insert(edit, "……"))
    }
    SymbolButton("――", "ダッシュ", "toolbar_dash") {
        onApply(WorkOutlineEditing.insert(edit, "――"))
    }
    SymbolButton("ルビ", "ルビを振る", "toolbar_ruby") {
        onApply(ProseTyping.insertRuby(edit))
    }
    ToolbarUnitDivider()
}

@Composable
internal fun TemplateToolbarItem(onPickTemplate: () -> Unit) {
    IconButton(
        onClick = onPickTemplate,
        modifier = Modifier.size(ProductSize.minimumTouchTarget)
            .keepsCaret()
            .testTag("toolbar_template"),
    ) {
        Icon(
            Icons.Outlined.Description,
            contentDescription = "テンプレートを挿入",
            modifier = Modifier.size(TOOLBAR_GLYPH),
        )
    }
}

@Composable
internal fun LinkToolbarItem(onPickLink: () -> Unit) {
    IconButton(
        onClick = onPickLink,
        modifier = Modifier.size(ProductSize.minimumTouchTarget)
            .keepsCaret()
            .testTag("toolbar_link"),
    ) {
        Icon(
            Icons.Outlined.Link,
            contentDescription = "メモへのリンク",
            modifier = Modifier.size(TOOLBAR_GLYPH),
        )
    }
}

@Composable
internal fun CommentLinkToolbarItem(onPickCommentLink: () -> Unit) {
    IconButton(
        onClick = onPickCommentLink,
        modifier = Modifier.size(ProductSize.minimumTouchTarget)
            .keepsCaret()
            .testTag("toolbar_comment_link"),
    ) {
        Icon(
            Icons.Outlined.AddComment,
            contentDescription = "コメントリンクを挿入",
            modifier = Modifier.size(TOOLBAR_GLYPH),
        )
    }
}

@Composable
private fun TaskToolbarItem(
    edit: OutlineEdit,
    symbols: OutlineSymbolSelection,
    onApply: (OutlineEdit) -> Unit,
) {
    // The outline machinery — checkboxes, markers, hierarchy — is how notes are
    // written. Prose has paragraphs, not bullet depths.
    val taskState = WorkOutlineEditing.taskState(edit)
    IconButton(
        onClick = { onApply(WorkOutlineEditing.cycleTask(edit, symbols)) },
        modifier = Modifier.size(ProductSize.minimumTouchTarget)
            .testTag("toolbar_task"),
    ) {
        Icon(
            if (taskState == WorkOutlineEditing.TaskState.DONE) {
                Icons.Outlined.CheckBox
            } else {
                Icons.Outlined.CheckBoxOutlineBlank
            },
            contentDescription = when (taskState) {
                WorkOutlineEditing.TaskState.NONE -> "チェックボックスにする"
                WorkOutlineEditing.TaskState.OPEN -> "完了にする"
                WorkOutlineEditing.TaskState.DONE -> "チェックボックスを外す"
            },
            tint = if (taskState == WorkOutlineEditing.TaskState.NONE) {
                LocalContentColor.current
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}

@Composable
private fun OutlineLabelToolbarItems(
    edit: OutlineEdit,
    labels: List<String>,
    symbols: OutlineSymbolSelection,
    chipLabel: OutlineChipLabel,
    onApply: (OutlineEdit) -> Unit,
    onFirstUse: () -> Unit = {},
    chips: ToolbarChipArrangement = ToolbarChips.defaultArrangement(ToolbarChipGroup.LABELS),
) {
    // The chips the writer kept, in the order they gave them; a chip's tag is its role's place
    // in the bar's original list, so it stays the same however the row is arranged.
    chips.visible.forEach { id ->
        val role = ToolbarChips.roleOf(id) ?: return@forEach
        val index = WorkOutlineEditing.chipRoles.indexOf(role)
        val active = WorkOutlineEditing.isMarkerActive(edit, role)
        FilterChip(
            selected = active,
            onClick = {
                // The marker lands first; the one-time guide rides on top of a tap that
                // already did what it said, never in place of it.
                onApply(WorkOutlineEditing.toggleMarker(edit, role, symbols))
                onFirstUse()
            },
            // The chip wears the glyph it will write, so switching the set is visible
            // right on the bar — unless the writer asked for the word alone, or for the
            // glyph alone to fit more of the bar in sight.
            label = { Text(chipLabel.textFor(labels[index], role, symbols)) },
            modifier = Modifier.keepsCaret().testTag("outline_helper_$index"),
        )
    }
    ToolbarUnitDivider()
}

@Composable
private fun IndentToolbarItems(edit: OutlineEdit, onApply: (OutlineEdit) -> Unit) {
    IconButton(
        onClick = { onApply(WorkOutlineEditing.outdent(edit)) },
        modifier = Modifier.size(ProductSize.minimumTouchTarget)
            .testTag("toolbar_outdent"),
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.FormatIndentDecrease,
            contentDescription = "階層を上げる",
            modifier = Modifier.size(TOOLBAR_GLYPH),
        )
    }
    IconButton(
        onClick = { onApply(WorkOutlineEditing.indent(edit)) },
        modifier = Modifier.size(ProductSize.minimumTouchTarget)
            .testTag("toolbar_indent"),
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.FormatIndentIncrease,
            contentDescription = "階層を下げる",
            modifier = Modifier.size(TOOLBAR_GLYPH),
        )
    }
}

/**
 * 流れ方: the line-end modifier chips. Each writes its token at the caret line's end —
 * replacing a token of its own kind — so how a line flies is one tap, not typed arrows.
 */
@Composable
private fun FlowModifierToolbarItems(
    edit: OutlineEdit,
    onApply: (OutlineEdit) -> Unit,
    chips: ToolbarChipArrangement = ToolbarChips.defaultArrangement(ToolbarChipGroup.FLOW),
) {
    chips.visible.forEach { id ->
        val chip = ToolbarChips.chip(ToolbarChipGroup.FLOW, id) ?: return@forEach
        val token = ToolbarChips.flowToken(id) ?: return@forEach
        SymbolButton(chip.word, chip.description, "toolbar_flow_$id") {
            onApply(WorkOutlineEditing.applyFlowModifier(edit, token))
        }
    }
    ToolbarUnitDivider()
}

/** The air between tool clusters; it travels with the unit it closes. */
@Composable
private fun ToolbarUnitDivider() {
    VerticalDivider(
        modifier = Modifier.height(24.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
internal val COMMENT_SAFETY_GAP = 8.dp
internal val MINIMUM_LANE_HEIGHT = 26.dp
internal val LANE_VERTICAL_SAFETY_GAP = 4.dp
private val MINIMUM_DRAG_HANDLE_SIZE = 48.dp
private val COMPACT_BODY_CHROME = 270.dp


internal const val EDITOR_HISTORY_LIMIT = 50

/** Toolbar glyphs sit at 22dp, the size every sheet and drawer in this app draws its icons. */
internal val TOOLBAR_GLYPH = 24.dp

/** The reading bar's 編集 pill: shorter than the 48dp squares beside it, so it reads as a button and not a slab. */
private val EDIT_PILL_HEIGHT = 40.dp

/**
 * A control that must not take the caret. Since Compose 1.7 a click moves focus onto the
 * clickable even in touch mode, which yanks it out of the text field and closes the keyboard —
 * so every button on the writing bar declines focus and the keyboard stays where it was.
 */
internal fun Modifier.keepsCaret(): Modifier = focusProperties { canFocus = false }


/** Which field 閲覧モード's tap meant for the pen: the title, or the body at a source line. */
private sealed interface WritingFocus {
    data object Title : WritingFocus
    data class Body(val line: Int) : WritingFocus
}

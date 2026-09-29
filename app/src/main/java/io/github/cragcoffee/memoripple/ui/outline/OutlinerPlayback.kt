package io.github.cragcoffee.memoripple.ui.outline

import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalConfiguration
import io.github.cragcoffee.memoripple.domain.outline.OutlineDocument
import io.github.cragcoffee.memoripple.domain.outline.OutlineNode
import io.github.cragcoffee.memoripple.ui.attachments.PhotoViewer
import io.github.cragcoffee.memoripple.ui.attachments.InlinePhoto
import io.github.cragcoffee.memoripple.ui.attachments.AttachmentImageLoader
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import io.github.cragcoffee.memoripple.ui.memos.SplitWorkspaceScaffold
import io.github.cragcoffee.memoripple.ui.memos.SplitReferencePickerSheet
import io.github.cragcoffee.memoripple.ui.memos.SaveTemplateDialog
import io.github.cragcoffee.memoripple.ui.memos.ReadingToolbar
import io.github.cragcoffee.memoripple.ui.memos.MemoReadingView
import io.github.cragcoffee.memoripple.portableexport.PortableExportEngine
import io.github.cragcoffee.memoripple.domain.split.SplitWorkspacePolicy
import io.github.cragcoffee.memoripple.domain.split.SplitReferenceKind
import io.github.cragcoffee.memoripple.domain.split.SplitReference
import io.github.cragcoffee.memoripple.domain.memos.MemoMarkdownExport
import io.github.cragcoffee.memoripple.domain.memos.MemoDocxExport
import io.github.cragcoffee.memoripple.domain.memos.ExportableMemo
import io.github.cragcoffee.memoripple.domain.OutlineSymbolSelection
import io.github.cragcoffee.memoripple.domain.NoteLink
import io.github.cragcoffee.memoripple.domain.BodyReading
import io.github.cragcoffee.memoripple.data.TagEntity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.data.FavoriteComment
import io.github.cragcoffee.memoripple.data.PlaybackStyle
import io.github.cragcoffee.memoripple.domain.comments.CommentAppearance
import io.github.cragcoffee.memoripple.domain.comments.CommentLinkMarkers
import io.github.cragcoffee.memoripple.domain.comments.CommentMotion
import io.github.cragcoffee.memoripple.domain.memos.MemoLifecycleState
import io.github.cragcoffee.memoripple.domain.playback.CommentLaneAllocator
import io.github.cragcoffee.memoripple.domain.playback.PlaybackContentMode
import io.github.cragcoffee.memoripple.domain.playback.PlaybackStatus
import io.github.cragcoffee.memoripple.domain.playback.PlaybackTimeline
import io.github.cragcoffee.memoripple.domain.playback.PlaybackValidationIssue
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.overlay.FIXED_COMMENT_TOO_LARGE_MESSAGE
import io.github.cragcoffee.memoripple.overlay.OverlayDensity
import io.github.cragcoffee.memoripple.overlay.OverlayDisplayRegion
import io.github.cragcoffee.memoripple.overlay.OverlayPermissionGateway
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackOptions
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackPlanFactory
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackRequest
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackStatus
import io.github.cragcoffee.memoripple.overlay.OverlayServiceStarter
import io.github.cragcoffee.memoripple.overlay.OverlayWindowPolicy
import io.github.cragcoffee.memoripple.overlay.overlayStartDecision
import io.github.cragcoffee.memoripple.speech.SpeechStatus
import io.github.cragcoffee.memoripple.ui.components.DestructiveTextButton
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.memos.COMMENT_SAFETY_GAP
import io.github.cragcoffee.memoripple.ui.memos.LANE_VERTICAL_SAFETY_GAP
import io.github.cragcoffee.memoripple.ui.memos.MINIMUM_LANE_HEIGHT
import io.github.cragcoffee.memoripple.ui.memos.MemoEditorUiState
import io.github.cragcoffee.memoripple.ui.memos.MemoEditorViewModel
import io.github.cragcoffee.memoripple.ui.memos.OverlayLaunchFlow
import io.github.cragcoffee.memoripple.ui.memos.OverlayLaunchGates
import io.github.cragcoffee.memoripple.ui.memos.OverlaySetupSheet
import io.github.cragcoffee.memoripple.ui.memos.PlaybackSettingsSheet
import io.github.cragcoffee.memoripple.ui.memos.PlaybackStopButton
import io.github.cragcoffee.memoripple.ui.memos.PlaybackToolbarItems
import io.github.cragcoffee.memoripple.ui.memos.PlaybackTransportActions
import io.github.cragcoffee.memoripple.ui.memos.SaveStatus
import io.github.cragcoffee.memoripple.ui.memos.SpeechStartDialog
import io.github.cragcoffee.memoripple.ui.memos.UserCommentsSheet
import io.github.cragcoffee.memoripple.ui.memos.fromEntity
import io.github.cragcoffee.memoripple.ui.playback.COMMENT_BASE_FONT_SIZE
import io.github.cragcoffee.memoripple.ui.playback.COMMENT_WAVE_DESIRED_AMPLITUDE
import io.github.cragcoffee.memoripple.ui.playback.CommentPlaybackFrameClock
import io.github.cragcoffee.memoripple.ui.playback.CommentRenderer
import io.github.cragcoffee.memoripple.ui.playback.CommentRendererColors
import io.github.cragcoffee.memoripple.ui.playback.LocalCommentFontFamily
import io.github.cragcoffee.memoripple.ui.playback.PlaybackDisplayMode
import io.github.cragcoffee.memoripple.ui.playback.STAGE_FONT_HEIGHT_FRACTION
import io.github.cragcoffee.memoripple.ui.playback.STAGE_MAXIMUM_BASE_FONT_SIZE
import io.github.cragcoffee.memoripple.ui.playback.STAGE_MINIMUM_BASE_FONT_SIZE
import io.github.cragcoffee.memoripple.ui.playback.measureCommentTextMetricsPx
import io.github.cragcoffee.memoripple.ui.playback.memoCommentStagePalette
import io.github.cragcoffee.memoripple.ui.playback.resolveCommentLaneLayout
import io.github.cragcoffee.memoripple.ui.playback.stageCommentRendererColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What the outliner wears above and below the writing, the way the memo editor does: the
 * ⋮ actions, the comments, コメントを流す and 読み上げ. Built once per screen by
 * [rememberOutlinerPlaybackUi] and handed to [OutlinerScreen] as slots, so the screen itself
 * stays a tree of lines and knows nothing of comments.
 */
class OutlinerPlaybackUi internal constructor(
    /** Comments are flowing: the lines hold still under them, as the memo's body does. */
    val readOnly: Boolean,
    /** The list's box reports its size here — the inline stage is that box. */
    val onBoundsChanged: (IntSize) -> Unit,
    /** コメントステージ above the lines, in that display mode only. */
    val stage: (@Composable () -> Unit)?,
    /** The flight over the lines, in the inline mode. */
    val overlays: @Composable BoxScope.() -> Unit,
    /** ▷ / 保存済み / 読み上げ / ⋮ at the end of the top bar. */
    val topActions: @Composable RowScope.() -> Unit,
    /** コメント / 再生設定 / ■, pinned at the end of the shortcut bar. */
    val toolbarEnd: @Composable RowScope.() -> Unit,
    /** The sheets and dialogs those open. */
    val sheets: @Composable () -> Unit,
    /** 閲覧モード: the outline read as the memo's page, in place of the lines; null while writing. */
    val reading: (@Composable () -> Unit)?,
    /** The bar under the page while reading; null while writing. */
    val readingBar: (@Composable () -> Unit)?,
    /** 分割表示: wraps the screen's content in the split scaffold while a reference is open. */
    val frame: @Composable (content: @Composable () -> Unit) -> Unit,
    /** Back closes what this UI opened — reading, a split — before the screen hears it. */
    val handleBack: () -> Boolean,
    /** Opens the comments sheet — the door the comment-link picker leads to when a memo has none yet. */
    val openComments: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberOutlinerPlaybackUi(
    memoId: Long,
    viewModel: MemoEditorViewModel,
    state: MemoEditorUiState,
    appSettings: AppSettings,
    symbols: OutlineSymbolSelection,
    snackbarHostState: SnackbarHostState,
    onLeave: () -> Unit,
    onOpenMemo: (Long) -> Unit,
    onSwapPrimaryMemo: (Long) -> Unit,
    onOpenNoteAsPrimary: (Long) -> Unit,
    /** A task box ticked on the reading page, by the body's line — an edit through the outliner's session. */
    onToggleReadingTask: (Int) -> Unit,
): OutlinerPlaybackUi {
    val context = LocalContext.current
    val application = context.applicationContext as MemoRippleApplication
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val commentFontFamily = LocalCommentFontFamily.current

    // The same view model the memo editor drives: its comments, its animator, its voice.
    val comments by viewModel.comments.collectAsStateWithLifecycle()
    val playbackStatusFlow = remember(viewModel) {
        viewModel.playbackState.map { animation -> animation.status }.distinctUntilChanged()
    }
    val playbackStatus by playbackStatusFlow.collectAsStateWithLifecycle(PlaybackStatus.IDLE)
    val speechState by viewModel.speechState.collectAsStateWithLifecycle()
    val overlayState by application.overlayPlaybackStateStore.state.collectAsStateWithLifecycle()
    val currentOverlayState by rememberUpdatedState(overlayState)
    val overlayServiceStarter = remember(application) {
        OverlayServiceStarter(application.overlayPlaybackStateStore)
    }
    val playbackStyle by application.settingsRepository.playbackStyle
        .collectAsStateWithLifecycle(initialValue = PlaybackStyle())
    val keepExpression by application.settingsRepository.keepCommentExpression
        .collectAsStateWithLifecycle(initialValue = false)
    val keptExpressionIds by application.settingsRepository.keptCommentExpression
        .collectAsStateWithLifecycle(initialValue = null)
    val favoriteComments by application.settingsRepository.favoriteComments
        .collectAsStateWithLifecycle(initialValue = FavoriteComment.Defaults)
    val hasSeenOverlaySetup by application.settingsRepository.hasSeenOverlaySetup
        .collectAsStateWithLifecycle(initialValue = true)
    val showCopyAllMenu by application.settingsRepository.editorMenuCopyAll
        .collectAsStateWithLifecycle(initialValue = true)
    val showPdfExportMenu by application.settingsRepository.editorMenuPdfExport
        .collectAsStateWithLifecycle(initialValue = true)
    val exportDocx by application.settingsRepository.editorExportDocx
        .collectAsStateWithLifecycle(initialValue = false)
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val linkTargets by viewModel.linkTargets.collectAsStateWithLifecycle()
    val linkTargetsByTitle = remember(linkTargets) { linkTargets.associateBy { NoteLink.key(it.title) } }
    val speechFollowLine by viewModel.speechFollowLine.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val currentHasSeenOverlaySetup by rememberUpdatedState(hasSeenOverlaySetup)

    val displayMode = PlaybackDisplayMode.entries
        .firstOrNull { it.name == playbackStyle.displayModeId } ?: PlaybackDisplayMode.INLINE
    val contentMode = PlaybackContentMode.entries
        .firstOrNull { it.name == playbackStyle.contentModeId } ?: PlaybackContentMode.BOTH
    val overlayRegionId = playbackStyle.overlayRegionId ?: OverlayDisplayRegion.FULL.storageId
    val overlayDensityId = playbackStyle.overlayDensityId ?: OverlayDensity.STANDARD.storageId
    val overlayMode = displayMode == PlaybackDisplayMode.OVERLAY

    var showMenu by remember { mutableStateOf(false) }
    var showCommentsSheet by remember { mutableStateOf(false) }
    var showPlaybackSettings by remember { mutableStateOf(false) }
    var showOverlaySetup by remember { mutableStateOf(false) }
    var showSpeechDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showTemplateSaveDialog by remember { mutableStateOf(false) }
    var showSplitPicker by remember { mutableStateOf(false) }
    // 閲覧モード: the outline read as the memo's page. Folds here are source lines, the page's
    // own idea of a fold — not the tree's content keys.
    var readingMode by rememberSaveable { mutableStateOf(false) }
    val collapsedLines = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) { mutableStateListOf<Int>() }
    // 分割表示: the working session lives on the application, as the memo editor keeps it, so
    // it survives rotation and the swap that replaces this screen with the reference's.
    val splitSession = application.splitWorkspaceSession
    var splitReference by rememberSaveable(
        memoId,
        stateSaver = listSaver(
            save = { value -> if (value == null) emptyList() else listOf(value.kind.name, value.id) },
            restore = { saved ->
                if (saved.size == 2) SplitReference(SplitReferenceKind.valueOf(saved[0] as String), saved[1] as Long) else null
            },
        ),
    ) { mutableStateOf(splitSession.resumeFor(memoId)) }
    var splitRatio by rememberSaveable(memoId) {
        mutableFloatStateOf(if (splitSession.resumeFor(memoId) != null) splitSession.ratio else SplitWorkspacePolicy.DEFAULT_RATIO)
    }
    val closeSplit = {
        splitReference = null
        splitSession.close()
    }
    var playbackBounds by remember(displayMode) { mutableStateOf(IntSize.Zero) }

    val createTimeline: (PlaybackContentMode) -> PlaybackTimeline = { mode ->
        viewModel.createPlaybackTimeline(mode, appSettings)
    }
    val safetyGapPx = with(density) { COMMENT_SAFETY_GAP.toPx() }
    val laneAllocator = remember(safetyGapPx) { CommentLaneAllocator(safetyGapPx) }
    val stagePalette = memoCommentStagePalette(appSettings.stageBackground)
    // ニコニコの縮尺, as on the memo: a taller stage writes larger letters; inline keeps the reading size.
    val playbackBaseFontSize = if (displayMode == PlaybackDisplayMode.STAGE && playbackBounds.height > 0) {
        with(density) { (playbackBounds.height * STAGE_FONT_HEIGHT_FRACTION).toSp() }.value
            .coerceIn(STAGE_MINIMUM_BASE_FONT_SIZE.value, STAGE_MAXIMUM_BASE_FONT_SIZE.value).sp
    } else {
        COMMENT_BASE_FONT_SIZE
    }
    val speechActive = speechState.status == SpeechStatus.INITIALIZING ||
        speechState.status == SpeechStatus.SPEAKING
    val playbackActive = playbackStatus != PlaybackStatus.IDLE

    val stop: () -> Unit = { viewModel.stopPlayback() }
    val updateBounds: (IntSize) -> Unit = { size ->
        if (playbackBounds != IntSize.Zero && playbackBounds != size && playbackActive) stop()
        playbackBounds = size
    }
    // The memo editor's measurement pipeline, run on the outline's own stage.
    val startTimelinePlayback: (PlaybackTimeline) -> Unit = { source ->
        if (source.items.isNotEmpty() && playbackBounds.width > 0 && playbackBounds.height > 0) {
            val textMetrics = measureCommentTextMetricsPx(
                timeline = source,
                textMeasurer = textMeasurer,
                density = density,
                availableWidthPx = playbackBounds.width.toFloat(),
                fontFamily = commentFontFamily,
                baseFontSize = playbackBaseFontSize,
            )
            val timeline = resolveCommentLaneLayout(
                timeline = source,
                textMetrics = textMetrics,
                availableHeightPx = playbackBounds.height.toFloat(),
                minimumLaneHeightPx = with(density) { MINIMUM_LANE_HEIGHT.toPx() },
                verticalSafetyGapPx = with(density) { LANE_VERTICAL_SAFETY_GAP.toPx() },
                availableWidthPx = playbackBounds.width.toFloat(),
                desiredWaveAmplitudePx = with(density) { COMMENT_WAVE_DESIRED_AMPLITUDE.toPx() },
                longCommentReadability = appSettings.longCommentReadability,
            )
            val renderWidths = textMetrics.mapValues { (_, metrics) -> metrics.renderWidthPx }
            if (timeline.validationIssue == PlaybackValidationIssue.FIXED_COMMENT_DOES_NOT_FIT) {
                scope.launch { snackbarHostState.showSnackbar(FIXED_COMMENT_TOO_LARGE_MESSAGE) }
            } else {
                viewModel.playTimeline(
                    laneAllocator.allocate(
                        timeline = timeline,
                        renderWidthsPx = renderWidths,
                        containerWidthPx = playbackBounds.width.toFloat(),
                    ),
                )
            }
        }
    }
    val playNow: () -> Unit = {
        if (overlayMode) showOverlaySetup = true else startTimelinePlayback(createTimeline(contentMode))
    }
    // The keyboard goes first: while it is up, a line holds the caret and the stage is the
    // short space above it; letting it fall after the flight began would resize the stage
    // and end the flight. So ▷ hides it and the flight starts once the stage has settled.
    val keyboardVisible = WindowInsets.isImeVisible
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var pendingPlay by remember { mutableStateOf(false) }
    val play: () -> Unit = {
        if (keyboardVisible) {
            keyboardController?.hide()
            focusManager.clearFocus()
            pendingPlay = true
        } else {
            playNow()
        }
    }
    LaunchedEffect(pendingPlay, keyboardVisible, playbackBounds) {
        if (pendingPlay && !keyboardVisible) {
            pendingPlay = false
            playNow()
        }
    }
    // Whether there is anything to play means building a timeline of the body — remembered
    // against what feeds it, never redone on every keystroke.
    val canPlayContent = remember(state.body, comments, contentMode, appSettings) {
        createTimeline(contentMode).items.isNotEmpty()
    }
    val wholeText = remember(state.title, state.body) {
        listOf(state.title, state.body).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    // Export writes through the same file store the backup uses, as the memo editor does.
    val exportableMemo = { ExportableMemo(state.title, state.body, tags.map(TagEntity::name), outline = true) }
    val exportDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(MemoMarkdownExport.MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val markdown = MemoMarkdownExport.render(exportableMemo())
        scope.launch {
            val written = application.backupFileStore.write(uri, markdown.toByteArray())
            snackbarHostState.showSnackbar(if (written) "Markdownで書き出しました" else "書き出せませんでした")
        }
    }
    val exportDocxDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(MemoDocxExport.MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = MemoDocxExport.render(exportableMemo())
        scope.launch {
            val written = application.backupFileStore.write(uri, bytes)
            snackbarHostState.showSnackbar(if (written) "Word (.docx)で書き出しました" else "書き出せませんでした")
        }
    }
    val exportPdfDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = application.portableExportEngine.exportMemoPdf(
                memoId = memoId,
                destination = uri,
                nowMillis = System.currentTimeMillis(),
            )
            snackbarHostState.showSnackbar(
                if (result is PortableExportEngine.ExportResult.Done) "PDFで保存しました" else "書き出せませんでした",
            )
        }
    }
    val exportMarkdown: () -> Unit = {
        if (exportDocx) exportDocxDocument.launch(MemoDocxExport.fileName(state.title))
        else exportDocument.launch(MemoMarkdownExport.fileName(state.title))
    }
    val exportPdf: () -> Unit = {
        exportPdfDocument.launch(MemoMarkdownExport.fileName(state.title).removeSuffix(MemoMarkdownExport.EXTENSION) + ".pdf")
    }

    // Overlay playback: the same launch flow and gates as the memo editor's.
    val overlayPermissionGateway = remember(context) { OverlayPermissionGateway(context) }
    val overlayPlanFactory = remember { OverlayPlaybackPlanFactory() }
    val windowManager = remember(context) { context.getSystemService(WindowManager::class.java) }
    val overlayLaunch = remember(overlayPermissionGateway, context) {
        OverlayLaunchFlow(
            isPermissionGranted = overlayPermissionGateway::isGranted,
            needsNotificationDecision = {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
            },
            onStart = { request ->
                runCatching { overlayServiceStarter.start(application, request) }.onFailure {
                    application.overlayPlaybackStateStore.fail("オーバーレイ再生を開始できませんでした")
                }
            },
            onWaitingForPermission = { application.overlayPlaybackStateStore.waiting(it) },
            onCancelled = { application.overlayPlaybackStateStore.idle() },
            hasSeenSetupIntro = { currentHasSeenOverlaySetup },
            onSetupIntroSeen = {
                scope.launch { application.settingsRepository.setHasSeenOverlaySetup(true) }
            },
        )
    }
    LaunchedEffect(overlayState.status, overlayLaunch.requestAfterStop) {
        if (overlayState.status == OverlayPlaybackStatus.IDLE) overlayLaunch.onOverlayIdle()
    }
    LaunchedEffect(speechState.message) {
        speechState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSpeechMessage()
        }
    }
    CommentPlaybackFrameClock(status = playbackStatus, onFrame = viewModel::advancePlayback)
    // Leaving the screen silences the flight and the voice; a screen merely going dark does
    // not (the memo editor's rule, kept: the speech service holds a reading in flight).
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.stopPlayback()
            viewModel.stopSpeech()
            if (application.overlayPlaybackStateStore.state.value.status ==
                OverlayPlaybackStatus.WAITING_PERMISSION
            ) {
                application.overlayPlaybackStateStore.idle()
            }
        }
    }

    val layer: @Composable (Modifier, CommentRendererColors, Boolean, TextUnit) -> Unit =
        { modifier, colors, dimBehind, baseFontSize ->
            val animation = viewModel.playbackState.collectAsStateWithLifecycle()
            CommentRenderer(
                animationState = animation,
                offsetProvider = viewModel::playbackOffsetPx,
                colors = colors,
                modifier = modifier,
                dimBehind = dimBehind,
                baseFontSize = baseFontSize,
            )
        }

    return OutlinerPlaybackUi(
        readOnly = playbackActive,
        onBoundsChanged = if (displayMode == PlaybackDisplayMode.INLINE) updateBounds else { _ -> },
        stage = if (displayMode == PlaybackDisplayMode.STAGE) {
            {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(stagePalette.background)
                        .testTag("comment_stage")
                        .onSizeChanged(updateBounds),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!playbackActive) {
                        Text(
                            "コメントステージ",
                            color = stagePalette.placeholderText,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    layer(Modifier.fillMaxSize(), stagePalette.commentColors, false, playbackBaseFontSize)
                }
            }
        } else {
            null
        },
        overlays = {
            if (displayMode == PlaybackDisplayMode.INLINE) {
                // The page dims and the letters go white while comments fly, as on the memo.
                layer(Modifier.fillMaxSize(), stageCommentRendererColors(), true, COMMENT_BASE_FONT_SIZE)
            }
        },
        topActions = {
            PlaybackTransportActions(
                status = playbackStatus,
                canPlay = overlayMode || canPlayContent,
                overlayMode = overlayMode,
                overlayActive = overlayState.isActive,
                onPlay = play,
                onPause = viewModel::pausePlayback,
                onResume = viewModel::resumePlayback,
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
                onClick = { if (speechActive) viewModel.stopSpeech() else showSpeechDialog = true },
                enabled = state.title.isNotBlank() || state.body.isNotBlank(),
                modifier = Modifier.testTag("memo_speech_action"),
            ) {
                Icon(
                    if (speechActive) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = if (speechActive) "読み上げを停止" else "読み上げ",
                )
            }
            if (state.exists) {
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier
                            .sizeIn(minWidth = ProductSize.minimumTouchTarget, minHeight = ProductSize.minimumTouchTarget)
                            .testTag("memo_editor_more"),
                    ) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "メモのその他の操作")
                    }
                    // The memo's menu, whole: the same rows in the same order.
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (state.isPinned) "固定を解除" else "上部に固定") },
                            leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                            onClick = {
                                viewModel.setPinned(!state.isPinned)
                                showMenu = false
                            },
                            modifier = Modifier.testTag("memo_editor_pin"),
                        )
                        val archived = state.lifecycleState == MemoLifecycleState.ARCHIVED
                        DropdownMenuItem(
                            text = { Text(if (archived) "アーカイブから戻す" else "アーカイブ") },
                            onClick = {
                                showMenu = false
                                if (archived) viewModel.unarchive(onLeave) else viewModel.archive(onLeave)
                            },
                            modifier = Modifier.testTag("memo_editor_archive"),
                        )
                        DropdownMenuItem(
                            text = { Text("分割表示") },
                            leadingIcon = { Icon(Icons.Outlined.VerticalSplit, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                showSplitPicker = true
                            },
                            modifier = Modifier.testTag("memo_editor_split"),
                        )
                        DropdownMenuItem(
                            // The row names the door, not the room, as on the memo.
                            text = { Text(if (readingMode) "編集モード" else "閲覧モード") },
                            leadingIcon = {
                                Icon(if (readingMode) Icons.Outlined.Edit else Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null)
                            },
                            onClick = {
                                showMenu = false
                                if (readingMode) {
                                    readingMode = false
                                } else {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                    readingMode = true
                                }
                            },
                            modifier = Modifier.testTag("memo_editor_reading_mode"),
                        )
                        DropdownMenuItem(
                            text = { Text("テンプレートとして保存") },
                            onClick = {
                                showMenu = false
                                showTemplateSaveDialog = true
                            },
                            modifier = Modifier.testTag("memo_editor_save_template"),
                        )
                        if (showCopyAllMenu) {
                            DropdownMenuItem(
                                text = { Text("全てのテキストをコピー") },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                enabled = wholeText.isNotBlank(),
                                onClick = {
                                    showMenu = false
                                    clipboardManager.setText(AnnotatedString(wholeText))
                                    scope.launch { snackbarHostState.showSnackbar("全てのテキストをコピーしました") }
                                },
                                modifier = Modifier.testTag("memo_editor_copy_all"),
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("共有") },
                            leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                            enabled = wholeText.isNotBlank(),
                            onClick = {
                                showMenu = false
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, wholeText)
                                    if (state.title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, state.title)
                                }
                                context.startActivity(Intent.createChooser(send, null))
                            },
                            modifier = Modifier.testTag("memo_editor_share"),
                        )
                        DropdownMenuItem(
                            // The one export row, wearing whichever format 設定 chose.
                            text = { Text(if (exportDocx) "Word (.docx)で書き出す" else "Markdownで書き出す") },
                            leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                exportMarkdown()
                            },
                            modifier = Modifier.testTag("memo_editor_export"),
                        )
                        if (showPdfExportMenu) {
                            DropdownMenuItem(
                                text = { Text("PDFで保存") },
                                leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, contentDescription = null) },
                                // The PDF draws from what is saved.
                                enabled = memoId != 0L,
                                onClick = {
                                    showMenu = false
                                    exportPdf()
                                },
                                modifier = Modifier.testTag("memo_editor_export_pdf"),
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("ゴミ箱へ移動") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                showDeleteDialog = true
                            },
                            modifier = Modifier.testTag("memo_editor_trash"),
                        )
                    }
                }
            }
        },
        toolbarEnd = {
            PlaybackToolbarItems(
                canComment = state.exists,
                onComments = { showCommentsSheet = true },
                onSettings = { showPlaybackSettings = true },
            )
            PlaybackStopButton(
                status = playbackStatus,
                overlayMode = overlayMode,
                overlayActive = overlayState.isActive,
                onStop = stop,
                onStopOverlay = { overlayServiceStarter.stop(application) },
            )
        },
        sheets = {
            if (showCommentsSheet) {
                UserCommentsSheet(
                    comments = comments,
                    onDismiss = { showCommentsSheet = false },
                    onAdd = { text, appearance, motion, onResult ->
                        viewModel.addComment(text, appearance, motion) { saved ->
                            if (saved) scope.launch { snackbarHostState.showSnackbar("コメントを追加しました") }
                            onResult(saved)
                        }
                    },
                    onDelete = viewModel::deleteComment,
                    onLinkComment = { comment ->
                        viewModel.linkComment(comment) { number ->
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (number != null) "R$number を割り当てました。行に [R$number] と書くと、読み上げがそこを通るときに流れます"
                                    else "リンクを設定できませんでした",
                                )
                            }
                        }
                    },
                    onUnlinkComment = viewModel::unlinkComment,
                    onLinkBadgeTap = { comment ->
                        val number = comment.linkNo ?: return@UserCommentsSheet
                        showCommentsSheet = false
                        scope.launch { snackbarHostState.showSnackbar("行に [R$number] と書くと、読み上げがそこを通るときに流れます") }
                    },
                    markerCountOf = { number -> CommentLinkMarkers.occurrenceCount(state.body, number) },
                    onUpdateExpression = { comment, appearance, motion ->
                        viewModel.updateCommentExpression(comment, appearance, motion) { saved ->
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (saved) "コメントの表現を変更しました" else "表現を変更できませんでした",
                                )
                            }
                        }
                    },
                    expressionEditable = state.lifecycleState != MemoLifecycleState.TRASHED,
                    keepExpression = keepExpression,
                    keptExpression = keptExpressionIds
                        ?.split('|')
                        ?.takeIf { it.size == 8 }
                        ?.let { ids ->
                            CommentAppearance.fromEntity(ids[0], ids[1], ids[2]) to
                                CommentMotion.fromEntity(ids[3], ids[4], ids[5], ids[6], ids[7])
                        },
                    onKeepExpressionChange = { keep ->
                        scope.launch { application.settingsRepository.setKeepCommentExpression(keep) }
                    },
                    onKeepExpression = { appearance, motion ->
                        scope.launch {
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
                        scope.launch {
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
                        scope.launch {
                            application.settingsRepository.setFavoriteComments(
                                favoriteComments.filterNot { it.key == favorite.key },
                            )
                        }
                    },
                    onReorderStart = viewModel::beginCommentReorder,
                    onReorder = viewModel::reorderComments,
                    onResetOrder = viewModel::resetCommentOrder,
                )
            }
            if (showPlaybackSettings) {
                PlaybackSettingsSheet(
                    contentMode = contentMode,
                    displayMode = displayMode,
                    commentScope = appSettings.commentScope,
                    onCommentScopeChange = { value ->
                        if (value != appSettings.commentScope) {
                            stop()
                            scope.launch { application.settingsRepository.setCommentScope(value) }
                        }
                    },
                    onContentModeChange = { mode ->
                        if (mode != contentMode) {
                            stop()
                            scope.launch { application.settingsRepository.setPlaybackContentMode(mode.name) }
                        }
                    },
                    onDisplayModeChange = { mode ->
                        if (mode != displayMode) {
                            stop()
                            scope.launch { application.settingsRepository.setPlaybackDisplayMode(mode.name) }
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
                val sourceTimeline = remember(contentMode) { createTimeline(contentMode) }
                val usableBounds = OverlayWindowPolicy.usableBounds(context, windowManager)
                val regionBounds = OverlayWindowPolicy.regionBounds(usableBounds, options.displayRegion)
                val textMetrics = remember(sourceTimeline, textMeasurer, density, regionBounds.width, commentFontFamily) {
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
                        desiredWaveAmplitudePx = with(density) { COMMENT_WAVE_DESIRED_AMPLITUDE.toPx() },
                    )
                }
                OverlaySetupSheet(
                    options = options,
                    previewPlan = previewPlan,
                    onContentModeChange = { mode ->
                        scope.launch { application.settingsRepository.setPlaybackContentMode(mode.name) }
                    },
                    onRegionChange = { region ->
                        scope.launch { application.settingsRepository.setOverlayRegion(region.storageId) }
                    },
                    onDensityChange = { value ->
                        scope.launch { application.settingsRepository.setOverlayDensity(value.storageId) }
                    },
                    onStart = {
                        viewModel.saveForOverlay { savedId ->
                            if (savedId == null) {
                                scope.launch { snackbarHostState.showSnackbar("メモを保存できませんでした") }
                            } else {
                                showOverlaySetup = false
                                overlayLaunch.submit(
                                    OverlayPlaybackRequest.create(savedId, options),
                                    overlayStartDecision(currentOverlayState),
                                )
                            }
                        }
                    },
                    onDismiss = { showOverlaySetup = false },
                )
            }
            OverlayLaunchGates(
                overlayLaunch = overlayLaunch,
                onStopOverlay = { overlayServiceStarter.stop(application) },
                snackbarHostState = snackbarHostState,
            )
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
                        viewModel.saveAsTemplate(name) { saved ->
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (saved) "テンプレートに保存しました" else "本文が空のためテンプレートにできません",
                                )
                            }
                        }
                    },
                    onDismiss = { showTemplateSaveDialog = false },
                )
            }
            if (showSpeechDialog) {
                SpeechStartDialog(
                    hasComments = comments.isNotEmpty(),
                    onStart = { includeComments ->
                        if (readingMode) viewModel.startSpeechWithLinks(includeComments) else viewModel.startSpeech(includeComments)
                    },
                    onDismiss = { showSpeechDialog = false },
                )
            }
            if (showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = { Text("メモをゴミ箱へ移動しますか？") },
                    text = { Text("ゴミ箱から復元できます。") },
                    confirmButton = { DestructiveTextButton("ゴミ箱へ移動", onClick = { viewModel.moveToTrash(onLeave) }) },
                    dismissButton = {
                        TextButton(onClick = { showDeleteDialog = false }) { Text("キャンセル") }
                    },
                )
            }
        },
        reading = if (readingMode) {
            {
                // Photo rows between the lines where they stand (docs/OUTLINE_PHOTO_ROWS.md): the
                // page is the body's lines, so a photo is drawn before the line that follows it.
                val readingPhotos by viewModel.photos.collectAsStateWithLifecycle()
                val photoLoader = remember(application) { AttachmentImageLoader(application.attachmentBlobStore) }
                val breaks = remember(state.outline) { outlinePhotoBreaks(state.outline) }
                var readingViewer by remember { mutableStateOf<Long?>(null) }
                val photosById = readingPhotos.associateBy { it.id }
                val inOrder = state.outline?.entries.orEmpty().mapNotNull { (it as? OutlineNode)?.photoAttachmentId?.let(photosById::get) }
                val targetPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }.coerceIn(320, 1440)
                readingViewer?.let { start ->
                    if (inOrder.isEmpty()) readingViewer = null else PhotoViewer(
                        photos = inOrder,
                        initialIndex = inOrder.indexOfFirst { it.id == start }.coerceAtLeast(0),
                        imageLoader = photoLoader,
                        editable = false,
                        onDismiss = { readingViewer = null },
                        onDelete = {},
                    )
                }
                MemoReadingView(
                    photoBreaks = breaks,
                    photoContent = { ids ->
                        Column(verticalArrangement = Arrangement.spacedBy(ProductSpacing.sm)) {
                            ids.forEach { id ->
                                photosById[id]?.let { photo ->
                                    InlinePhoto(photo = photo, imageLoader = photoLoader, targetPx = targetPx, description = "写真", onClick = { readingViewer = id })
                                }
                            }
                        }
                    },
                    body = state.body,
                    collapsed = collapsedLines.toSet(),
                    onToggleFold = { line -> if (!collapsedLines.remove(line)) collapsedLines.add(line) },
                    onToggleTask = onToggleReadingTask,
                    onOpenLink = { title -> linkTargetsByTitle[NoteLink.key(title)]?.let { onOpenMemo(it.id) } },
                    isLinkResolved = { title -> linkTargetsByTitle.containsKey(NoteLink.key(title)) },
                    // The memo's page keeps the memo's gutters; the lines' own bullets sit in theirs.
                    modifier = Modifier.fillMaxSize().padding(horizontal = ProductSize.screenHorizontalPadding),
                    linkedNumbers = comments.mapNotNull { it.linkNo }.toSet(),
                    onEmitLink = viewModel::fireLinkedComment,
                    taskDot = true,
                    followLine = if (speechActive) speechFollowLine else null,
                    onDoubleTapEdit = if (speechActive || playbackActive) null else ({ readingMode = false }),
                    onTapLine = if (speechActive) ({ line -> viewModel.jumpSpeechToLine(line) }) else null,
                )
            }
        } else {
            null
        },
        readingBar = if (readingMode) {
            {
                val foldable = remember(state.body) { BodyReading.foldableHeadings(BodyReading.lines(state.body)) }
                Column(Modifier.imePadding().navigationBarsPadding()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ReadingToolbar(
                        playbackStatus = playbackStatus,
                        overlayMode = overlayMode,
                        overlayActive = overlayState.isActive,
                        canComment = state.exists,
                        onStop = stop,
                        onStopOverlay = { overlayServiceStarter.stop(application) },
                        onComments = { showCommentsSheet = true },
                        onSettings = { showPlaybackSettings = true },
                        canFold = foldable.isNotEmpty(),
                        anyFolded = collapsedLines.isNotEmpty(),
                        onFoldAll = {
                            collapsedLines.clear()
                            collapsedLines.addAll(foldable)
                        },
                        onUnfoldAll = collapsedLines::clear,
                        onEdit = { readingMode = false },
                    )
                }
            }
        } else {
            null
        },
        frame = { content ->
            val reference = splitReference
            if (reference == null) {
                content()
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
                    primary = content,
                )
            }
        },
        openComments = { showCommentsSheet = true },
        handleBack = {
            when {
                readingMode -> { readingMode = false; true }
                splitReference != null -> { closeSplit(); true }
                else -> false
            }
        },
    )
}

/** For the reading page, which draws the body line by line: the photos to draw before each line. */
internal fun outlinePhotoBreaks(document: OutlineDocument?): Map<Int, List<Long>> {
    val breaks = linkedMapOf<Int, MutableList<Long>>()
    var line = 0
    document?.entries?.forEach { entry ->
        val photo = (entry as? OutlineNode)?.photoAttachmentId
        if (photo != null) breaks.getOrPut(line) { mutableListOf() } += photo else line++
    }
    return breaks
}

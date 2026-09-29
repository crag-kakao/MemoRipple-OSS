package io.github.cragcoffee.memoripple.ui.memos

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import io.github.cragcoffee.memoripple.ui.OnTabReselect
import io.github.cragcoffee.memoripple.ui.TopLevelTab
import io.github.cragcoffee.memoripple.ui.scrollToTopOnReselect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.R
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.NoteSummary
import io.github.cragcoffee.memoripple.data.PlaybackStyle
import io.github.cragcoffee.memoripple.data.TagEntity
import io.github.cragcoffee.memoripple.domain.BodyReading
import io.github.cragcoffee.memoripple.domain.NoteLink
import io.github.cragcoffee.memoripple.domain.WorkCommentScope
import io.github.cragcoffee.memoripple.domain.WorkTextStats
import io.github.cragcoffee.memoripple.domain.memos.ExportableMemo
import io.github.cragcoffee.memoripple.domain.memos.ImportedMemo
import io.github.cragcoffee.memoripple.domain.memos.MemoFilterMode
import io.github.cragcoffee.memoripple.domain.memos.MemoMarkdownExport
import io.github.cragcoffee.memoripple.domain.memos.MemoMarkdownImport
import io.github.cragcoffee.memoripple.domain.memos.MemoSearch
import io.github.cragcoffee.memoripple.domain.memos.MemoSortMode
import io.github.cragcoffee.memoripple.domain.memos.TagMatchMode
import io.github.cragcoffee.memoripple.domain.memos.WallDisplayMode
import io.github.cragcoffee.memoripple.domain.memos.displayTitle
import io.github.cragcoffee.memoripple.domain.memos.isOutline
import io.github.cragcoffee.memoripple.data.FolderEntity
import io.github.cragcoffee.memoripple.data.toNode
import io.github.cragcoffee.memoripple.domain.folders.FolderTree
import io.github.cragcoffee.memoripple.domain.notes.NoteCoverColor
import io.github.cragcoffee.memoripple.domain.notes.NoteCoverPaint
import io.github.cragcoffee.memoripple.domain.notes.OutlineEpisodes
import io.github.cragcoffee.memoripple.domain.playback.CommentAnimationState
import io.github.cragcoffee.memoripple.domain.playback.PlaybackContentMode
import io.github.cragcoffee.memoripple.domain.playback.PlaybackItem
import io.github.cragcoffee.memoripple.domain.playback.PlaybackStatus
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.domain.tags.TagNameNormalizer
import io.github.cragcoffee.memoripple.overlay.OverlayDensity
import io.github.cragcoffee.memoripple.overlay.OverlayDisplayRegion
import io.github.cragcoffee.memoripple.overlay.OverlayPermissionGateway
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackOptions
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackRequest
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackState
import io.github.cragcoffee.memoripple.overlay.OverlayPlaybackStatus
import io.github.cragcoffee.memoripple.overlay.OverlayServiceStarter
import io.github.cragcoffee.memoripple.overlay.overlayStartDecision
import io.github.cragcoffee.memoripple.ui.attachments.AttachmentImageLoader
import io.github.cragcoffee.memoripple.ui.components.ImportWording
import io.github.cragcoffee.memoripple.ui.components.ProductCompactTopBar
import io.github.cragcoffee.memoripple.ui.components.ProductEmptyState
import io.github.cragcoffee.memoripple.ui.components.ProductSheetHeader
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.notes.NoteActionSheet
import io.github.cragcoffee.memoripple.ui.notes.NoteCoverColorSheet
import io.github.cragcoffee.memoripple.ui.notes.NoteDeleteDialog
import io.github.cragcoffee.memoripple.ui.notes.NoteListPage
import io.github.cragcoffee.memoripple.ui.notes.NoteListUiState
import io.github.cragcoffee.memoripple.ui.notes.NoteListViewModel
import io.github.cragcoffee.memoripple.ui.notes.NotePickerSheet
import io.github.cragcoffee.memoripple.ui.notes.NoteTextDialog
import io.github.cragcoffee.memoripple.ui.notes.NoteTitleDialog
import io.github.cragcoffee.memoripple.ui.playback.CommentPlaybackFrameClock
import io.github.cragcoffee.memoripple.ui.playback.CommentRenderer
import io.github.cragcoffee.memoripple.ui.playback.stageCommentRendererColors
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun MemoListRoute(
    appSettings: AppSettings = AppSettings.Default,
    onOpenMemo: (Long) -> Unit,
    /** A new memo, filed in the folder the wall is showing (null = the root). */
    onCreateMemo: (Long?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTagManagement: () -> Unit,
    onOpenArchive: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenNote: (Long) -> Unit = {},
    onReadEpisode: (noteId: Long, memoId: Long) -> Unit = { _, _ -> },
    onOpenOutline: (Long) -> Unit = {},
    /** An outline ＋ just made: opened so that, left with nothing in it, it is taken back. */
    onOpenNewOutline: (Long) -> Unit = onOpenOutline,
    registerSelectionClearer: ((() -> Unit) -> Unit) = {},
) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    val viewModel: MemoListViewModel = viewModel(
        factory = MemoListViewModel.factory(
            application.memoRepository,
            application.tagRepository,
            application.attachmentRepository,
            application.settingsRepository,
            application.folderRepository,
        ),
    )
    val noteViewModel: NoteListViewModel = viewModel(
        factory = NoteListViewModel.factory(
            application.noteRepository,
            application.memoRepository,
            application.attachmentRepository,
        ),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val displayMode by viewModel.displayMode.collectAsStateWithLifecycle()
    val wallSingleColumn by viewModel.wallSingleColumn.collectAsStateWithLifecycle()
    val autoPlayOnLaunch by application.settingsRepository.autoPlayOnLaunch
        .collectAsStateWithLifecycle(initialValue = false)
    val wallOverlayPlayback by application.settingsRepository.wallOverlayPlayback
        .collectAsStateWithLifecycle(initialValue = false)
    val wallPlaysContent by application.settingsRepository.wallPlaysContent
        .collectAsStateWithLifecycle(initialValue = false)
    val playbackStyle by application.settingsRepository.playbackStyle
        .collectAsStateWithLifecycle(initialValue = PlaybackStyle())
    val overlayState by application.overlayPlaybackStateStore.state.collectAsStateWithLifecycle()
    val overlayServiceStarter = remember(application) {
        OverlayServiceStarter(application.overlayPlaybackStateStore)
    }
    DisposableEffect(application) {
        onDispose {
            // A request left waiting for the permission round-trip dies with the screen; the
            // banner must not stand over a wall that is no longer asking.
            if (application.overlayPlaybackStateStore.state.value.status ==
                OverlayPlaybackStatus.WAITING_PERMISSION
            ) {
                application.overlayPlaybackStateStore.idle()
            }
        }
    }
    val noteState by noteViewModel.uiState.collectAsStateWithLifecycle()
    val myCoverColors by application.settingsRepository.myCoverColors
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // Per-frame playback state stays out of the screen: elapsedMillis changes every frame
    // while the wall speaks, and passing the whole state down re-ran this entire screen body
    // per frame. The screen gets the status alone (which changes a handful of times per
    // stream); the stage leaf collects the full state itself, the way the editor does.
    val wallPlaybackStatus by remember(viewModel) {
        viewModel.wallPlayback.map { it.status }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(PlaybackStatus.IDLE)
    CommentPlaybackFrameClock(status = wallPlaybackStatus, onFrame = viewModel::advanceWall)
    // The wall speaks only while the wall is in front. Opening a memo, switching tab, or
    // leaving the app stops the stream — it neither drifts across the navigation transition nor
    // lies frozen, waiting to resume unasked when this screen comes back.
    LifecycleResumeEffect(Unit) {
        onPauseOrDispose { viewModel.stopWall() }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val exportScope = rememberCoroutineScope()
    // What is written is what the list is showing, so a search or a tag filter also narrows the
    // export. There is no second, hidden idea of "everything".
    // Nothing is written until the count has been shown and accepted.
    var pendingImport by remember { mutableStateOf<List<ImportedMemo>?>(null) }
    val importDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exportScope.launch {
            val text = application.backupFileStore
                .readText(uri, MemoMarkdownImport.MAX_CHARACTERS)
            val parsed = text?.let(MemoMarkdownImport::parse).orEmpty()
            if (parsed.isEmpty()) {
                snackbarHostState.showSnackbar(
                    if (text == null) "ファイルを読めませんでした" else ImportWording.MARKDOWN_NOTHING_TO_READ,
                )
            } else {
                pendingImport = parsed
            }
        }
    }
    pendingImport?.let { parsed ->
        MarkdownImportConfirmDialog(
            parsed = parsed,
            onConfirm = {
                pendingImport = null
                viewModel.importMemos(parsed) { count ->
                    exportScope.launch {
                        snackbarHostState.showSnackbar(ImportWording.markdownDone(count))
                    }
                }
            },
            onDismiss = { pendingImport = null },
        )
    }

    val exportDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(MemoMarkdownExport.MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val exportables = state.memos.map { memo ->
            ExportableMemo(
                title = memo.title,
                body = memo.body,
                tagNames = state.tagsByMemo[memo.id].orEmpty().map(TagEntity::name),
            )
        }
        val count = state.memos.size
        exportScope.launch {
            // Rendering the whole library is proportional to the library; off the main thread
            // together with the write.
            val written = withContext(Dispatchers.IO) {
                application.backupFileStore.write(
                    uri,
                    MemoMarkdownExport.renderAll(exportables).toByteArray(),
                )
            }
            snackbarHostState.showSnackbar(
                if (written) "${count}件をMarkdownで書き出しました" else "書き出せませんでした",
            )
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.bulkEvents.collectLatest { event ->
            if (event is MemoBulkEvent.Lifecycle) {
                if (snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = "元に戻す",
                        duration = SnackbarDuration.Long,
                    ) ==
                    SnackbarResult.ActionPerformed
                ) {
                    viewModel.undo(event)
                }
            } else {
                snackbarHostState.showSnackbar(event.message)
            }
        }
    }
    LaunchedEffect(viewModel, registerSelectionClearer) {
        registerSelectionClearer(viewModel::clearSelection)
    }

    fun navigateAfterClearingSelection(action: () -> Unit) {
        viewModel.clearSelection()
        action()
    }

    MemoListScreen(
        state = state,
        displayMode = displayMode,
        wallSingleColumn = wallSingleColumn,
        autoPlayOnLaunch = autoPlayOnLaunch,
        onDisplayModeChange = viewModel::updateDisplayMode,
        onWallSingleColumnChange = viewModel::updateWallSingleColumn,
        onQueryChange = viewModel::updateQuery,
        onFilterChange = viewModel::updateFilter,
        onSortChange = viewModel::updateSort,
        onTagFilterChange = viewModel::updateTagFilter,
        onOpenMemo = { navigateAfterClearingSelection { onOpenMemo(it) } },
        onCreateMemo = { navigateAfterClearingSelection { onCreateMemo(state.currentFolderId) } },
        onOpenOutline = { navigateAfterClearingSelection { onOpenOutline(it) } },
        onOpenFolder = viewModel::openFolder,
        onGoToFolder = viewModel::goToFolder,
        onLeaveFolder = { viewModel.leaveFolder() },
        onToggleFolder = viewModel::toggleFolderExpanded,
        onCreateFolder = viewModel::createFolder,
        onRenameFolder = viewModel::renameFolder,
        onMoveFolder = viewModel::moveFolder,
        onDeleteFolder = viewModel::deleteFolder,
        onMoveMemoToFolder = viewModel::moveMemoToFolder,
        onReorder = viewModel::applyManualOrder,
        // The outline exists first, then the outliner opens on it — no editor in between.
        onCreateOutline = {
            viewModel.createOutline { id -> navigateAfterClearingSelection { onOpenNewOutline(id) } }
        },
        onOpenSettings = { navigateAfterClearingSelection(onOpenSettings) },
        onOpenTagManagement = { navigateAfterClearingSelection(onOpenTagManagement) },
        onOpenArchive = { navigateAfterClearingSelection(onOpenArchive) },
        onOpenTrash = { navigateAfterClearingSelection(onOpenTrash) },
        onRequestSelection = viewModel::requestSelectionMode,
        onStartSelection = viewModel::startSelection,
        onToggleSelection = viewModel::toggleSelection,
        onClearSelection = viewModel::clearSelection,
        onSelectAllVisible = viewModel::selectAllVisible,
        onBulkPinned = viewModel::setPinnedForSelection,
        onBulkAddTags = viewModel::addTagsToSelection,
        onBulkRemoveTags = viewModel::removeTagsFromSelection,
        onBulkArchive = viewModel::archiveSelection,
        onBulkTrash = viewModel::trashSelection,
        onBulkMoveToFolder = viewModel::moveSelectionToFolder,
        onImportMarkdown = { importDocument.launch(MEMO_IMPORT_MIME_TYPES) },
        onPinMemo = viewModel::setPinnedFor,
        onArchiveMemos = viewModel::archiveMemos,
        onTrashMemos = viewModel::trashMemos,
        onTagMemos = { memoIds, tagIds -> viewModel.addTagsTo(memoIds, tagIds) },
        onDuplicateMemo = { memoId ->
            viewModel.duplicate(memoId) { copied ->
                exportScope.launch {
                    snackbarHostState.showSnackbar(
                        if (copied) "複製を作成しました" else "複製できませんでした",
                    )
                }
            }
        },
        onAddMemoToNote = { memoId, noteId ->
            noteViewModel.addEpisode(noteId, memoId) {
                exportScope.launch { snackbarHostState.showSnackbar("ノートに追加しました") }
            }
        },
        onAddMemoToNewNote = { memoId, title ->
            noteViewModel.createNote(title) { noteId ->
                noteViewModel.addEpisode(noteId, memoId) {
                    exportScope.launch { snackbarHostState.showSnackbar("ノートに追加しました") }
                }
            }
        },
        onMakeEpisodes = { memo, noteId ->
            noteViewModel.makeEpisodesFromOutline(noteId, memo) { made ->
                exportScope.launch { snackbarHostState.showSnackbar("${made}話を作りました") }
            }
        },
        onMakeEpisodesInNewNote = { memo, title ->
            noteViewModel.createNote(title) { noteId ->
                noteViewModel.makeEpisodesFromOutline(noteId, memo) { made ->
                    exportScope.launch { snackbarHostState.showSnackbar("${made}話を作りました") }
                }
            }
        },
        onLinkCopied = {
            exportScope.launch { snackbarHostState.showSnackbar("リンクをコピーしました") }
        },
        wallPlaybackStatus = wallPlaybackStatus,
        wallPlaybackState = viewModel.wallPlayback,
        wallOffsetProvider = viewModel::wallOffsetPx,
        onPlayWall = { bodies, scope -> viewModel.playWall(bodies, scope, appSettings) },
        onPauseWall = viewModel::pauseWall,
        onResumeWall = viewModel::resumeWall,
        onStopWall = viewModel::stopWall,
        wallHasSomethingToSay = { bodies, scope ->
            viewModel.wallHasSomethingToSay(bodies, scope, appSettings)
        },
        wallOverlayEnabled = wallOverlayPlayback,
        wallPlaysContent = wallPlaysContent,
        overlayState = overlayState,
        overlayRegionId = playbackStyle.overlayRegionId,
        overlayDensityId = playbackStyle.overlayDensityId,
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
        noteState = noteState,
        onToggleNoteExpanded = noteViewModel::toggleExpanded,
        onReorderNotes = noteViewModel::reorder,
        onOpenNote = { navigateAfterClearingSelection { onOpenNote(it) } },
        onOpenEpisode = { noteId, memoId ->
            navigateAfterClearingSelection { onReadEpisode(noteId, memoId) }
        },
        onWriteNextEpisode = { noteId ->
            noteViewModel.writeNextEpisode(noteId) { memoId ->
                navigateAfterClearingSelection { onOpenMemo(memoId) }
            }
        },
        onCreateNote = { title, subtitle ->
            noteViewModel.createNote(title, subtitle) { noteId ->
                navigateAfterClearingSelection { onOpenNote(noteId) }
            }
        },
        onRenameNote = noteViewModel::renameNote,
        onSubtitleNote = noteViewModel::setSubtitle,
        onNoteCoverColor = noteViewModel::setCoverPaint,
        onNoteCoverPhoto = noteViewModel::setCoverPhoto,
        onNoteCoverPhotoClear = noteViewModel::clearCoverPhoto,
        myCoverColors = myCoverColors,
        onSaveMyCoverColor = { argb ->
            exportScope.launch { application.settingsRepository.rememberCoverColor(argb) }
        },
        onRemoveMyCoverColor = { argb ->
            exportScope.launch { application.settingsRepository.forgetCoverColor(argb) }
        },
        onDeleteNote = { noteId ->
            noteViewModel.deleteNote(noteId) {
                exportScope.launch { snackbarHostState.showSnackbar("ノートを削除しました") }
            }
        },
        onDeleteNotes = { noteIds, onDone ->
            noteViewModel.deleteNotes(noteIds) { deleted ->
                onDone()
                exportScope.launch { snackbarHostState.showSnackbar("${deleted}件のノートを削除しました") }
            }
        },
        onExportVisible = {
            exportDocument.launch(
                MemoMarkdownExport.collectionFileName(
                    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
                ),
            )
        },
        snackbarHostState = snackbarHostState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoListScreen(
    state: MemoListUiState,
    displayMode: WallDisplayMode,
    wallSingleColumn: Boolean,
    autoPlayOnLaunch: Boolean,
    onDisplayModeChange: (WallDisplayMode) -> Unit,
    onWallSingleColumnChange: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    onFilterChange: (MemoFilterMode) -> Unit,
    onSortChange: (MemoSortMode) -> Unit,
    onTagFilterChange: (Set<Long>, TagMatchMode) -> Unit,
    onOpenMemo: (Long) -> Unit,
    onCreateMemo: () -> Unit,
    onOpenOutline: (Long) -> Unit,
    onCreateOutline: () -> Unit,
    onOpenFolder: (Long) -> Unit,
    onGoToFolder: (Long?) -> Unit,
    onLeaveFolder: () -> Unit,
    onToggleFolder: (Long) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (Long, String) -> Unit,
    onMoveFolder: (Long, Long?) -> Unit,
    onDeleteFolder: (Long) -> Unit,
    onMoveMemoToFolder: (Long, Long?) -> Unit,
    /** The cards of the open page in the order a drag left them (selection mode). */
    onReorder: (List<Long>) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTagManagement: () -> Unit,
    onOpenArchive: () -> Unit,
    onOpenTrash: () -> Unit,
    onRequestSelection: () -> Unit,
    onStartSelection: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAllVisible: () -> Unit,
    onBulkPinned: (Boolean) -> Unit,
    onBulkAddTags: (Set<Long>) -> Unit,
    onBulkRemoveTags: (Set<Long>) -> Unit,
    onBulkArchive: () -> Unit,
    onBulkTrash: () -> Unit,
    onBulkMoveToFolder: (Long?) -> Unit,
    onImportMarkdown: () -> Unit,
    onExportVisible: () -> Unit,
    onPinMemo: (Set<Long>, Boolean) -> Unit,
    onArchiveMemos: (Set<Long>) -> Unit,
    onTrashMemos: (Set<Long>) -> Unit,
    onTagMemos: (Set<Long>, Set<Long>) -> Unit,
    onDuplicateMemo: (Long) -> Unit,
    onAddMemoToNote: (memoId: Long, noteId: Long) -> Unit,
    onAddMemoToNewNote: (memoId: Long, title: String) -> Unit,
    onMakeEpisodes: (memo: MemoEntity, noteId: Long) -> Unit,
    onMakeEpisodesInNewNote: (memo: MemoEntity, title: String) -> Unit,
    onLinkCopied: () -> Unit,
    wallPlaybackStatus: PlaybackStatus,
    wallPlaybackState: kotlinx.coroutines.flow.StateFlow<CommentAnimationState>,
    wallOffsetProvider: (PlaybackItem, Long, Float, Float) -> Float,
    onPlayWall: (List<String>, WorkCommentScope) -> Unit,
    onPauseWall: () -> Unit,
    onResumeWall: () -> Unit,
    onStopWall: () -> Unit,
    wallHasSomethingToSay: (List<String>, WorkCommentScope) -> Boolean,
    wallOverlayEnabled: Boolean,
    wallPlaysContent: Boolean,
    overlayState: OverlayPlaybackState,
    overlayRegionId: String?,
    overlayDensityId: String?,
    onOverlayWaiting: (OverlayPlaybackRequest) -> Unit,
    onCancelOverlayRequest: () -> Unit,
    onStartOverlay: (OverlayPlaybackRequest) -> Unit,
    onStopOverlay: () -> Unit,
    noteState: NoteListUiState,
    onToggleNoteExpanded: (Long) -> Unit,
    onReorderNotes: (List<Long>) -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenEpisode: (noteId: Long, memoId: Long) -> Unit,
    onWriteNextEpisode: (Long) -> Unit,
    onCreateNote: (title: String, subtitle: String) -> Unit,
    onRenameNote: (noteId: Long, title: String) -> Unit,
    onSubtitleNote: (noteId: Long, subtitle: String) -> Unit,
    onDeleteNote: (noteId: Long) -> Unit,
    onDeleteNotes: (noteIds: Set<Long>, onDone: () -> Unit) -> Unit,
    onNoteCoverColor: (noteId: Long, paint: NoteCoverPaint) -> Unit,
    onNoteCoverPhoto: (noteId: Long, uri: Uri, onDone: (Boolean) -> Unit) -> Unit,
    onNoteCoverPhotoClear: (noteId: Long) -> Unit,
    myCoverColors: List<Int>,
    onSaveMyCoverColor: (Int) -> Unit,
    onRemoveMyCoverColor: (Int) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    var showDisplayOptions by remember { mutableStateOf(false) }
    var showTagFilter by remember { mutableStateOf(false) }
    var showSelectionMenu by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var bulkTagMode by remember { mutableStateOf<BulkTagMode?>(null) }
    // フォルダへ移動 for the whole selection: the same picker a single card uses from its sheet.
    var filingSelection by remember { mutableStateOf(false) }
    // Parsed once per query, then reused by every card that has to mark its match.
    val searchHighlights = remember(state.query) { MemoSearch.parse(state.query).highlights }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val hasAnyMemos = state.totalMemoCount > 0
    // The strip announces genuine narrowings — pinned, tags, an unusual order — and its
    // クリア undoes exactly those. The chosen view is a mode, not a narrowing: it neither
    // banners itself here nor gets knocked back by クリア.
    val activeDisplayParts = buildList {
        if (state.filter != MemoFilterMode.ALL) add(state.filter.label)
        if (state.selectedTags.isNotEmpty()) {
            add(
                if (state.selectedTags.size == 1) {
                    state.selectedTags.single().name
                } else {
                    "タグ${state.selectedTags.size}個"
                },
            )
        }
        // an order is how the wall is arranged, not a way it is narrowed (2026-09-21 23:15): no chip for any of them
    }
    // Holding a memo asks about that memo, so the answer is about it.
    var heldMemo by remember { mutableStateOf<MemoEntity?>(null) }
    var pickingNoteFor by remember { mutableStateOf<MemoEntity?>(null) }
    var makingEpisodesFrom by remember { mutableStateOf<MemoEntity?>(null) }
    var namingNote by remember { mutableStateOf(false) }
    var heldNote by remember { mutableStateOf<NoteSummary?>(null) }
    // ノートを選択 (2026-09-28): picking notes with a tap, carrying one by its ＝ handle, the picked ones for the trash button.
    var selectingNotes by remember { mutableStateOf(false) }
    var selectedNoteIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var deletingSelectedNotes by remember { mutableStateOf(false) }
    var renamingNote by remember { mutableStateOf<NoteSummary?>(null) }
    var subtitlingNote by remember { mutableStateOf<NoteSummary?>(null) }
    var deletingNote by remember { mutableStateOf<NoteSummary?>(null) }
    var coveringNote by remember { mutableStateOf<NoteSummary?>(null) }
    var taggingMemo by remember { mutableStateOf<MemoEntity?>(null) }
    // Folders: what is being made, held, renamed, moved, deleted — and which document is being filed.
    var creatingFolder by remember { mutableStateOf(false) }
    var heldFolder by remember { mutableStateOf<FolderEntity?>(null) }
    var renamingFolder by remember { mutableStateOf<FolderEntity?>(null) }
    var movingFolder by remember { mutableStateOf<FolderEntity?>(null) }
    var deletingFolder by remember { mutableStateOf<FolderEntity?>(null) }
    var filingMemo by remember { mutableStateOf<MemoEntity?>(null) }
    // Browsing shows the folder section; a search reaches the whole kind and hides it.
    val folderSection = if (state.query.isBlank()) {
        FolderSectionState(
            path = state.folderPath,
            children = state.childFolders,
            onGoTo = onGoToFolder,
            onOpen = onOpenFolder,
            onHold = { heldFolder = it },
        )
    } else {
        null
    }
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { MEMO_VIEW_MODES.size })
    val currentMode = MEMO_VIEW_MODES[pagerState.currentPage]
    // One wall. How much of it is shown is a way of looking, chosen in the display options —
    // not a place a memo is moved to by how it happens to be written.
    val (plainMemos, outlinedMemos) = remember(state.memos) {
        state.memos.partition { !BodyReading.hasStructure(it.body) }
    }
    val wallMemos = when (displayMode) {
        WallDisplayMode.COMBINED -> state.memos
        WallDisplayMode.MEMO -> plainMemos
        WallDisplayMode.OUTLINE -> outlinedMemos
    }
    val wallScope = if (displayMode == WallDisplayMode.OUTLINE) {
        WorkCommentScope.OUTLINE
    } else {
        WorkCommentScope.BODY
    }
    // A stream built from memos that are no longer shown is a stream about nothing.
    LaunchedEffect(currentMode, displayMode, state.memos, state.isSelectionMode) { onStopWall() }
    val wallBodies = remember(wallMemos) { wallMemos.map(MemoEntity::body) }
    // What the wall says when asked: titles by default — a roll call — or, when 設定 says so,
    // the bodies themselves. The launch greeting and ▶ are the same voice, so they share this.
    val wallTitles = remember(wallMemos) {
        wallMemos.map(MemoEntity::title).filter(String::isNotBlank)
    }
    val wallStream = if (wallPlaysContent) wallBodies else wallTitles
    val wallStreamScope = if (wallPlaysContent) wallScope else WorkCommentScope.BODY
    // Whether the wall has anything to say is a full parse of every visible line — far too
    // expensive to ask inside the top bar's recompose scope, which re-runs every frame while
    // the wall is playing. Asked once per change of the stream instead, remembered.
    val wallCanPlay = remember(wallStream, wallStreamScope, wallHasSomethingToSay) {
        wallHasSomethingToSay(wallStream, wallStreamScope)
    }
    // The wall's ▶ can send its stream to the overlay instead of its own sky, chosen in 設定.
    // The gates are the editor's gates: same permission disclosure, same replacement question.
    val overlayContext = LocalContext.current
    val overlayPermissionGateway = remember(overlayContext) {
        OverlayPermissionGateway(overlayContext)
    }
    val currentOnStartOverlay by rememberUpdatedState(onStartOverlay)
    val currentOnOverlayWaiting by rememberUpdatedState(onOverlayWaiting)
    val currentOnCancelOverlayRequest by rememberUpdatedState(onCancelOverlayRequest)
    val overlayApp = overlayContext.applicationContext as MemoRippleApplication
    val hasSeenOverlaySetup by overlayApp.settingsRepository.hasSeenOverlaySetup
        .collectAsStateWithLifecycle(initialValue = true)
    val currentHasSeenOverlaySetup by rememberUpdatedState(hasSeenOverlaySetup)
    val overlayIntroScope = rememberCoroutineScope()
    val overlayLaunch = remember(overlayPermissionGateway, overlayContext) {
        OverlayLaunchFlow(
            isPermissionGranted = overlayPermissionGateway::isGranted,
            needsNotificationDecision = {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    overlayContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
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
    LaunchedEffect(overlayState.status, overlayLaunch.requestAfterStop) {
        if (overlayState.status == OverlayPlaybackStatus.IDLE) {
            overlayLaunch.onOverlayIdle()
        }
    }
    val wallMemoIds = remember(wallMemos) { wallMemos.map(MemoEntity::id) }
    val playWallWhereChosen: () -> Unit = {
        if (wallOverlayEnabled) {
            overlayLaunch.submit(
                OverlayPlaybackRequest.createWall(
                    memoIds = wallMemoIds,
                    scope = wallScope,
                    options = OverlayPlaybackOptions(
                        contentMode = PlaybackContentMode.WORK_ONLY,
                        displayRegion = OverlayDisplayRegion.fromStorageId(overlayRegionId),
                        density = OverlayDensity.fromStorageId(overlayDensityId),
                    ),
                ),
                overlayStartDecision(overlayState),
            )
        } else {
            onPlayWall(wallStream, wallStreamScope)
        }
    }
    OverlayLaunchGates(
        overlayLaunch = overlayLaunch,
        onStopOverlay = onStopOverlay,
        snackbarHostState = snackbarHostState,
    )
    // The wall greets an opened app, once per launch: flowing comments are the centre of this
    // app, and the first open is when they introduce themselves. The greeting says whatever the
    // wall says — titles by default, contents when 設定 asks — because it and ▶ are one voice.
    // It waits for the wall to actually load, breathes past the stop-on-change effect above,
    // and never fires again this process — later writing must not start unasked playback.
    val latestWallStream by rememberUpdatedState(wallStream)
    val latestWallStreamScope by rememberUpdatedState(wallStreamScope)
    LaunchedEffect(autoPlayOnLaunch) {
        if (!autoPlayOnLaunch || wallGreetedThisLaunch) return@LaunchedEffect
        wallGreetedThisLaunch = true
        val lines = withTimeoutOrNull(5_000) {
            snapshotFlow { latestWallStream }
                .first { it.isNotEmpty() && wallHasSomethingToSay(it, latestWallStreamScope) }
        } ?: return@LaunchedEffect
        delay(400)
        onPlayWall(lines, latestWallStreamScope)
    }
    val pagerScope = rememberCoroutineScope()
    val clearDisplayOptions: () -> Unit = {
        onQueryChange("")
        onDisplayModeChange(WallDisplayMode.COMBINED)
        onFilterChange(MemoFilterMode.ALL)
        onSortChange(MemoSortMode.UPDATED_DESC)
        onTagFilterChange(emptySet(), TagMatchMode.ANY)
    }
    // The strip's クリア undoes narrowings and leaves the chosen view and order standing.
    val clearNarrowing: () -> Unit = {
        onQueryChange("")
        onFilterChange(MemoFilterMode.ALL)
        onTagFilterChange(emptySet(), TagMatchMode.ANY)
    }
    // The wall's own scroll states, hoisted so the bar can lift while content moves under it.
    val cardsGridState = rememberLazyStaggeredGridState()
    val shelfGridState = rememberLazyGridState()
    // The other two pages' lists, hoisted beside the wall's so a reselect can reach them.
    val outlinerListState = rememberLazyListState()
    val noteListState = rememberLazyListState()
    // メモ tapped again in the bottom bar (docs/BOTTOM_NAV_RESELECT.md): the list of the page
    // being shown goes back to its top — the page, the folder, the search, the order and the
    // other pages' places stay as they are.
    OnTabReselect(TopLevelTab.MEMOS) {
        when (MEMO_VIEW_MODES[pagerState.currentPage]) {
            MemoViewMode.MEMO -> if (displayMode == WallDisplayMode.OUTLINE) {
                shelfGridState.scrollToTopOnReselect()
            } else {
                cardsGridState.scrollToTopOnReselect()
            }
            MemoViewMode.OUTLINER -> outlinerListState.scrollToTopOnReselect()
            MemoViewMode.NOTE -> noteListState.scrollToTopOnReselect()
        }
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    fun withDrawerClosed(action: () -> Unit) {
        drawerScope.launch { drawerState.close() }
        action()
    }

    // One Back for the wall, in one order (docs/FOLDER_NAVIGATOR_AUDIT.md): the navigator, if
    // open, closes; a selection ends; a note being arranged is put down; the search field
    // empties; inside a folder, up one level — the folder tree, not the outliner's zoom; at the
    // root Back is not ours and means what it always meant. Four handlers used to compete for
    // this, and the last one composed won.
    val backAction: (() -> Unit)? = when {
        drawerState.isOpen -> { { drawerScope.launch { drawerState.close() } } }
        state.isSelectionMode -> onClearSelection
        selectingNotes -> { { selectingNotes = false; selectedNoteIds = emptySet() } }
        state.query.isNotBlank() -> { { onQueryChange(""); keyboardController?.hide() } }
        state.currentFolderId != null -> onLeaveFolder
        else -> null
    }
    BackHandler(enabled = backAction != null) { backAction?.invoke() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Nothing is being organised while memos are selected, so the edge swipe stays out of it.
        gesturesEnabled = !state.isSelectionMode,
        drawerContent = {
            MemoNavigationDrawerSheet(
                state = state,
                hasAnyMemos = hasAnyMemos,
                onShowAll = {
                    withDrawerClosed {
                        onFilterChange(MemoFilterMode.ALL)
                        onTagFilterChange(emptySet(), TagMatchMode.ANY)
                    }
                },
                onShowPinned = {
                    withDrawerClosed { onFilterChange(MemoFilterMode.PINNED) }
                },
                onSelectTag = { tagId ->
                    withDrawerClosed {
                        val already = state.selectedTagIds == setOf(tagId)
                        onTagFilterChange(
                            if (already) emptySet() else setOf(tagId),
                            TagMatchMode.ANY,
                        )
                    }
                },
                onOpenTagManagement = { withDrawerClosed(onOpenTagManagement) },
                onOpenArchive = { withDrawerClosed(onOpenArchive) },
                onOpenTrash = { withDrawerClosed(onOpenTrash) },
                onDisplayOptions = { withDrawerClosed { showDisplayOptions = true } },
                onRequestSelection = { withDrawerClosed(onRequestSelection) },
                onImportMarkdown = { withDrawerClosed(onImportMarkdown) },
                onExportVisible = { withDrawerClosed(onExportVisible) },
                onOpenSettings = { withDrawerClosed(onOpenSettings) },
                onOpenFolder = { withDrawerClosed { onGoToFolder(it) } },
                onToggleFolder = onToggleFolder,
                onHoldFolder = { withDrawerClosed { heldFolder = it } },
            )
        },
    ) {
        Scaffold(
            topBar = {
                if (state.isSelectionMode) {
                    ProductCompactTopBar(
                        modifier = Modifier.testTag("memo_compact_top_bar"),
                        navigationIcon = {
                            IconButton(
                                onClick = onClearSelection,
                                modifier = Modifier.size(ProductSize.minimumTouchTarget),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = "選択を終了",
                                )
                            }
                        },
                        centerContent = {
                            Text(
                                "${state.selectedMemoIds.size}件を選択",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        },
                        action = {
                            Box {
                                IconButton(
                                    onClick = { showSelectionMenu = true },
                                    modifier = Modifier
                                        .size(ProductSize.minimumTouchTarget)
                                        .testTag("memo_bulk_menu"),
                                ) {
                                    Icon(
                                        Icons.Outlined.MoreVert,
                                        contentDescription = "選択したメモの操作",
                                    )
                                }
                                DropdownMenu(
                                    expanded = showSelectionMenu,
                                    onDismissRequest = { showSelectionMenu = false },
                                ) {
                                    BulkActionMenuItems(
                                        hasSelection = state.selectedMemoIds.isNotEmpty(),
                                        onDismiss = { showSelectionMenu = false },
                                        onSelectAll = onSelectAllVisible,
                                        onPinned = onBulkPinned,
                                        onAddTags = { bulkTagMode = BulkTagMode.ADD },
                                        onRemoveTags = { bulkTagMode = BulkTagMode.REMOVE },
                                        onMoveToFolder = { filingSelection = true },
                                        onArchive = onBulkArchive,
                                        onTrash = onBulkTrash,
                                    )
                                }
                            }
                        },
                    )
                } else if (selectingNotes) {
                    // ノートを選択: the count once something is picked, as on the memo wall.
                    ProductCompactTopBar(
                        modifier = Modifier.testTag("note_arranging_bar"),
                        navigationIcon = {
                            IconButton(
                                onClick = { selectingNotes = false; selectedNoteIds = emptySet() },
                                modifier = Modifier.size(ProductSize.minimumTouchTarget),
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "選択を終了")
                            }
                        },
                        centerContent = {
                            Text(
                                if (selectedNoteIds.isEmpty()) "ノートを選択" else "${selectedNoteIds.size}件を選択",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        },
                        action = {},
                    )
                } else {
                    // The bar is the search field. Searching is not a mode entered through an
                    // icon; the first line of the screen already takes the words. The drawer's
                    // handle and the wall's play control ride inside it — the way the app this
                    // bar is modelled on carries its own — and the overflow holds the commands
                    // that act on the list rather than going anywhere.
                    // The bar keeps the page's colour whether the list is at its top or scrolled
                    // (the user's review, 2026-09-25: it used to lift a step while the wall was
                    // scrolled under it); the hairline under the tabs says where it ends.
                    val barColor = MaterialTheme.colorScheme.background
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(barColor)
                            .testTag("memo_compact_top_bar"),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    // The same 24dp rail the cards stand on. The overflow's
                                    // glyph ends up visually on the right rail through its own
                                    // 12dp of internal padding.
                                    start = ProductSize.screenHorizontalPadding,
                                    end = ProductSpacing.md,
                                    top = ProductSpacing.sm,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = CircleShape,
                                // The same surface language as the cards below, after the 1.0
                                // polish: a filled shape, no outline. The cards sit one quiet
                                // step off the page; the search pill sits several, so it stays
                                // the anchor of the row — and still reads against the lifted
                                // bar, which only rises to surfaceContainerHigh.
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.weight(1f),
                            ) {
                                Row(
                                    modifier = Modifier.heightIn(
                                        min = ProductSize.minimumTouchTarget,
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(
                                        onClick = {
                                            drawerScope.launch { drawerState.open() }
                                        },
                                        modifier = Modifier
                                            .size(ProductSize.minimumTouchTarget)
                                            .testTag("memo_top_menu"),
                                    ) {
                                        Icon(
                                            Icons.Outlined.Menu,
                                            contentDescription = "メモの整理と設定",
                                        )
                                    }
                                    Box(Modifier.weight(1f)) {
                                        MemoCompactSearchField(
                                            query = state.query,
                                            onQueryChange = onQueryChange,
                                            onClear = { onQueryChange("") },
                                            focusRequester = searchFocusRequester,
                                        )
                                    }
                                }
                            }
                            // The Keep gesture, on the combined wall only: it can lie flat as
                            // one full-width card per row, or stand as the two-column grid. The
                            // icon shows the shape a tap would give, the way the wall it copies
                            // does. メモのみ keeps its Keep cards and アウトラインのみ its shelf
                            // — the single views are the old tabs, and they do not change shape.
                            if (currentMode == MemoViewMode.MEMO &&
                                displayMode == WallDisplayMode.COMBINED &&
                                !state.isSelectionMode
                            ) {
                                IconButton(
                                    onClick = { onWallSingleColumnChange(!wallSingleColumn) },
                                    modifier = Modifier
                                        .size(ProductSize.minimumTouchTarget)
                                        .testTag("wall_column_toggle"),
                                ) {
                                    Icon(
                                        if (wallSingleColumn) {
                                            Icons.Outlined.GridView
                                        } else {
                                            Icons.Outlined.ViewAgenda
                                        },
                                        contentDescription = if (wallSingleColumn) {
                                            "2列で表示"
                                        } else {
                                            "1列で表示"
                                        },
                                        // Secondary to the play control beside it: the row keeps
                                        // one accent, and it belongs to the thing that moves.
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            // The wall's voice stands outside the search pill: the pill's label
                            // says what the pill holds, and this plays the wall, not the query.
                            // Lit primary when there is something to flow — the one place the
                            // centre of this app shows its colour before being asked.
                            if (currentMode == MemoViewMode.MEMO && wallMemos.isNotEmpty()) {
                                WallTransportIcons(
                                    status = wallPlaybackStatus,
                                    enabled = wallCanPlay,
                                    onPlay = playWallWhereChosen,
                                    onPause = onPauseWall,
                                    onResume = onResumeWall,
                                    onStop = onStopWall,
                                )
                            }
                            Box {
                                IconButton(
                                    onClick = { showOverflowMenu = true },
                                    modifier = Modifier
                                        .size(ProductSize.minimumTouchTarget)
                                        .testTag("memo_overflow"),
                                ) {
                                    Icon(
                                        Icons.Outlined.MoreVert,
                                        contentDescription = "リストの操作",
                                        // Secondary, like the column toggle: quiet until asked.
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                DropdownMenu(
                                    expanded = showOverflowMenu,
                                    onDismissRequest = { showOverflowMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("表示条件") },
                                        onClick = {
                                            showOverflowMenu = false
                                            showDisplayOptions = true
                                        },
                                        modifier = Modifier.testTag("overflow_display_options"),
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text("並び順")
                                                Text(
                                                    state.sort.label,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme
                                                        .onSurfaceVariant,
                                                )
                                            }
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            showSortMenu = true
                                        },
                                        modifier = Modifier.testTag("overflow_sort"),
                                    )
                                    if (hasAnyMemos) {
                                        DropdownMenuItem(
                                            text = { Text("メモを選択") },
                                            onClick = {
                                                showOverflowMenu = false
                                                onRequestSelection()
                                            },
                                            modifier = Modifier.testTag("request_memo_selection"),
                                        )
                                    }
                                    if (currentMode != MemoViewMode.NOTE) {
                                        DropdownMenuItem(
                                            text = { Text("新しいフォルダ") },
                                            onClick = {
                                                showOverflowMenu = false
                                                creatingFolder = true
                                            },
                                            modifier = Modifier.testTag("overflow_new_folder"),
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Markdownを読み込む") },
                                        onClick = {
                                            showOverflowMenu = false
                                            onImportMarkdown()
                                        },
                                        modifier = Modifier.testTag("import_markdown"),
                                    )
                                    if (hasAnyMemos) {
                                        DropdownMenuItem(
                                            text = { Text("表示中のメモを書き出す") },
                                            onClick = {
                                                showOverflowMenu = false
                                                onExportVisible()
                                            },
                                            modifier = Modifier.testTag("export_visible_memos"),
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false },
                                ) {
                                    MemoSortMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode.label) },
                                            leadingIcon = {
                                                if (state.sort == mode) {
                                                    Icon(
                                                        Icons.Outlined.Check,
                                                        contentDescription = "選択中",
                                                    )
                                                } else {
                                                    Spacer(Modifier.size(24.dp))
                                                }
                                            },
                                            onClick = {
                                                showSortMenu = false
                                                onSortChange(mode)
                                            },
                                            modifier = Modifier
                                                .testTag("memo_sort_${mode.name}")
                                                .semantics { selected = state.sort == mode },
                                        )
                                    }
                                }
                            }
                        }
                        MemoViewModeTabs(
                            pagerState = pagerState,
                            onSelectPage = { page ->
                                pagerScope.launch { pagerState.animateScrollToPage(page) }
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            },
            floatingActionButton = {
                // What the button makes follows the tab: a note tab that made memos would be
                // pointing at the wrong thing. What it does not do is come and go. A primary
                // action that disappears on an empty tab is missing exactly when it is needed,
                // so the only thing that hides it is picking memos, where it would act on nothing.
                val page = MEMO_VIEW_MODES[pagerState.currentPage]
                val trashNotes = page == MemoViewMode.NOTE && selectingNotes && selectedNoteIds.isNotEmpty()
                if (trashNotes) {
                    // Picked notes: the same place, the same button, the note sheet's ノートを削除 for all of them.
                    FloatingActionButton(
                        onClick = { deletingSelectedNotes = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.testTag("trash_selected_notes"),
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = "選択したノートを削除")
                    }
                } else if (!state.isSelectionMode) {
                    // A plus is read faster than the words for it, and the words were the widest
                    // thing on the wall. What it makes is said by the tab it is standing on.
                    FloatingActionButton(
                        onClick = when (page) {
                            MemoViewMode.MEMO -> onCreateMemo
                            MemoViewMode.OUTLINER -> onCreateOutline
                            MemoViewMode.NOTE -> { { namingNote = true } }
                        },
                        // The one filled control on the wall carries the strongest colour the
                        // palette has. Its old fill, primaryContainer, measures 1.10:1 against
                        // this page — the most important button on the screen was standing on
                        // its shadow alone.
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.testTag(
                            when (page) {
                                MemoViewMode.MEMO -> "create_memo"
                                MemoViewMode.OUTLINER -> "create_outline"
                                MemoViewMode.NOTE -> "create_note"
                            },
                        ),
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = when (page) {
                                MemoViewMode.MEMO -> "新しいメモ"
                                MemoViewMode.OUTLINER -> "新しいアウトライン"
                                MemoViewMode.NOTE -> "新しいノート"
                            },
                        )
                    }
                } else if (page != MemoViewMode.NOTE && state.selectedMemoIds.isNotEmpty()) {
                    // While memos or outlines are picked, the same button in the same place moves them to
                    // the trash — the selection menu's ゴミ箱へ移動, one tap closer (2026-09-28). The same
                    // call, so the undo snackbar and the ended selection come with it; notes are left as they were.
                    FloatingActionButton(
                        onClick = onBulkTrash,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.testTag(
                            if (page == MemoViewMode.MEMO) "trash_selected_memos" else "trash_selected_outlines",
                        ),
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = if (page == MemoViewMode.MEMO) "選択したメモをゴミ箱へ移動" else "選択したアウトラインをゴミ箱へ移動",
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopCenter,
            ) {
                val contentModifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp)
                    .padding(horizontal = ProductSize.screenHorizontalPadding)
                val application = LocalContext.current.applicationContext as MemoRippleApplication
                val noteImageLoader = remember(application) {
                    AttachmentImageLoader(application.attachmentBlobStore)
                }
                HorizontalPager(
                    state = pagerState,
                    // Picking memos is a two-handed job already; a stray swipe must not change tab.
                    userScrollEnabled = !state.isSelectionMode,
                    modifier = Modifier.fillMaxSize().skipZeroWidthMeasure().testTag("memo_pager"),
                    key = { MEMO_VIEW_MODES[it] },
                ) { page ->
                    when (MEMO_VIEW_MODES[page]) {
                        MemoViewMode.MEMO -> when (displayMode) {
                            WallDisplayMode.OUTLINE -> MemoOutlineShelfPage(
                                state = state,
                                memos = outlinedMemos,
                                gridState = shelfGridState,
                                activeDisplayParts = activeDisplayParts,
                                onOpenMemo = onOpenMemo,
                                onHoldMemo = { heldMemo = it },
                                onToggleSelection = onToggleSelection,
                                onClearDisplayOptions = clearDisplayOptions,
                                onClearNarrowing = clearNarrowing,
                                folderSection = folderSection,
                                onReorder = onReorder,
                                modifier = contentModifier,
                            )

                            else -> MemoCardsPage(
                                state = state,
                                memos = wallMemos,
                                displayMode = displayMode,
                                singleColumn = displayMode == WallDisplayMode.COMBINED &&
                                    wallSingleColumn,
                                gridState = cardsGridState,
                                highlights = searchHighlights,
                                activeDisplayParts = activeDisplayParts,
                                onOpenMemo = onOpenMemo,
                                onToggleSelection = onToggleSelection,
                                onHoldMemo = { heldMemo = it },
                                onClearDisplayOptions = clearDisplayOptions,
                                onClearNarrowing = clearNarrowing,
                                folderSection = folderSection,
                                onReorder = onReorder,
                                modifier = contentModifier,
                            )
                        }

                        MemoViewMode.OUTLINER -> OutlinerListPage(
                            state = state,
                            outlines = state.outlines,
                            activeDisplayParts = activeDisplayParts,
                            onOpenOutline = onOpenOutline,
                            onHoldOutline = { heldMemo = it },
                            onClearNarrowing = clearNarrowing,
                            folderSection = folderSection,
                            onToggleSelection = onToggleSelection,
                            onReorder = onReorder,
                            modifier = contentModifier,
                            listState = outlinerListState,
                        )

                        MemoViewMode.NOTE -> NoteListPage(
                            onHoldNote = { heldNote = it },
                            coverPhotos = noteState.coverPhotos,
                            imageLoader = noteImageLoader,
                            state = noteState,
                            onToggleExpanded = onToggleNoteExpanded,
                            selecting = selectingNotes,
                            selectedIds = selectedNoteIds,
                            onToggleSelected = { id ->
                                selectedNoteIds = if (id in selectedNoteIds) selectedNoteIds - id else selectedNoteIds + id
                            },
                            onReorder = onReorderNotes,
                            onOpenNote = onOpenNote,
                            onOpenEpisode = onOpenEpisode,
                            onWriteNextEpisode = onWriteNextEpisode,
                            onCreateNote = { namingNote = true },
                            modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
                            listState = noteListState,
                        )
                    }
                }
                if (currentMode == MemoViewMode.MEMO) {
                    WallPlaybackLayer(
                        state = wallPlaybackState,
                        offsetProvider = wallOffsetProvider,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    heldMemo?.let { memo ->
        MemoActionSheet(
            memo = memo,
            canCopyLink = memo.title.isNotBlank(),
            // An outline is a document of its own: it is not read as a note's episode, not
            // cut into episodes, and not picked with the wall's memos for bulk actions.
            canAddToNote = !memo.isOutline,
            canMakeEpisodes = !memo.isOutline && OutlineEpisodes.canMake(memo.body),
            canSelect = true,
            selectLabel = if (memo.isOutline) "アウトライナーを選択" else "メモを選択",
            onMoveToFolder = { heldMemo = null; filingMemo = memo },
            onPinnedChange = { pinned ->
                heldMemo = null
                onPinMemo(setOf(memo.id), pinned)
            },
            onAddTags = { heldMemo = null; taggingMemo = memo },
            onDuplicate = { heldMemo = null; onDuplicateMemo(memo.id) },
            onAddToNote = { heldMemo = null; pickingNoteFor = memo },
            onMakeEpisodes = { heldMemo = null; makingEpisodesFrom = memo },
            onSelect = { heldMemo = null; onStartSelection(memo.id) },
            onCopyLink = {
                heldMemo = null
                clipboardScope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(
                            ClipData.newPlainText(
                                "MemoRipple",
                                NoteLink.OPEN + memo.title + NoteLink.CLOSE,
                            ),
                        ),
                    )
                }
                onLinkCopied()
            },
            onArchive = { heldMemo = null; onArchiveMemos(setOf(memo.id)) },
            onTrash = { heldMemo = null; onTrashMemos(setOf(memo.id)) },
            onDismiss = { heldMemo = null },
        )
    }

    pickingNoteFor?.let { memo ->
        NotePickerSheet(
            notes = noteState.notes,
            onPick = { noteId ->
                pickingNoteFor = null
                onAddMemoToNote(memo.id, noteId)
            },
            onCreate = {
                pickingNoteFor = null
                onAddMemoToNewNote(memo.id, memo.displayTitle())
            },
            onDismiss = { pickingNoteFor = null },
        )
    }

    heldNote?.let { note ->
        NoteActionSheet(
            note = note,
            onRename = { heldNote = null; renamingNote = note },
            onEditSubtitle = { heldNote = null; subtitlingNote = note },
            onEditCover = { heldNote = null; coveringNote = note },
            onDelete = { heldNote = null; deletingNote = note },
            onDismiss = { heldNote = null },
            onSelect = { heldNote = null; selectedNoteIds = emptySet(); selectingNotes = true },
        )
    }

    renamingNote?.let { note ->
        NoteTextDialog(
            title = "ノートの名前",
            initial = note.title,
            confirmLabel = "変える",
            testTag = "note_rename_from_list",
            onConfirm = { renamingNote = null; onRenameNote(note.id, it) },
            onDismiss = { renamingNote = null },
        )
    }

    subtitlingNote?.let { note ->
        NoteTextDialog(
            title = "サブタイトル",
            initial = note.subtitle,
            confirmLabel = "変える",
            testTag = "note_subtitle_from_list",
            onConfirm = { subtitlingNote = null; onSubtitleNote(note.id, it) },
            onDismiss = { subtitlingNote = null },
        )
    }

    // The picture is picked with the system's picker, which does not know which note asked, so
    // the note is kept until the picker answers. Its cover is the same sheet the note's page has.
    val coverPhotoScope = rememberCoroutineScope()
    var noteAwaitingPhoto by remember { mutableStateOf<Long?>(null) }
    val coverPhotoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val noteId = noteAwaitingPhoto
        noteAwaitingPhoto = null
        if (uri != null && noteId != null) {
            coveringNote = null
            onNoteCoverPhoto(noteId, uri) { stored ->
                if (!stored) {
                    coverPhotoScope.launch {
                        snackbarHostState.showSnackbar("この画像は表紙にできませんでした")
                    }
                }
            }
        }
    }
    coveringNote?.let { note ->
        NoteCoverColorSheet(
            selected = NoteCoverPaint.fromStorageId(note.coverColor),
            onPick = { coveringNote = null; onNoteCoverColor(note.id, it) },
            onDismiss = { coveringNote = null },
            hasPhoto = noteState.coverPhotos[note.id] != null,
            onPickPhoto = {
                noteAwaitingPhoto = note.id
                coverPhotoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onClearPhoto = { coveringNote = null; onNoteCoverPhotoClear(note.id) },
            myColors = myCoverColors,
            onSaveMyColor = onSaveMyCoverColor,
            onRemoveMyColor = onRemoveMyCoverColor,
        )
    }

    deletingNote?.let { note ->
        NoteDeleteDialog(
            testTag = "note_delete_from_list",
            onConfirm = { deletingNote = null; onDeleteNote(note.id) },
            onDismiss = { deletingNote = null },
        )
    }

    // The same question and the same deletion as the sheet's ノートを削除, for every picked note in one go.
    if (deletingSelectedNotes) {
        NoteDeleteDialog(
            testTag = "note_delete_selected",
            onConfirm = {
                deletingSelectedNotes = false
                onDeleteNotes(selectedNoteIds) { selectedNoteIds = emptySet() }
            },
            onDismiss = { deletingSelectedNotes = false },
        )
    }

    if (namingNote) {
        NoteTitleDialog(
            onConfirm = { title, subtitle ->
                namingNote = false
                onCreateNote(title, subtitle)
            },
            onDismiss = { namingNote = false },
        )
    }

    if (creatingFolder) {
        FolderNameDialog(
            title = "新しいフォルダ",
            initial = "",
            confirmLabel = "作る",
            onConfirm = { creatingFolder = false; onCreateFolder(it) },
            onDismiss = { creatingFolder = false },
        )
    }

    heldFolder?.let { folder ->
        FolderActionSheet(
            folder = folder,
            onRename = { heldFolder = null; renamingFolder = folder },
            onMove = { heldFolder = null; movingFolder = folder },
            onDelete = { heldFolder = null; deletingFolder = folder },
            onDismiss = { heldFolder = null },
        )
    }

    renamingFolder?.let { folder ->
        FolderNameDialog(
            title = "フォルダの名前",
            initial = folder.name,
            confirmLabel = "変える",
            onConfirm = { renamingFolder = null; onRenameFolder(folder.id, it) },
            onDismiss = { renamingFolder = null },
        )
    }

    movingFolder?.let { folder ->
        // Neither the folder itself nor anything below it is offered: the tree rule, shown.
        val excluded = remember(folder.id, state.folders) {
            FolderTree.descendants(state.folders.map(FolderEntity::toNode), folder.id) + folder.id
        }
        FolderPickerSheet(
            title = "「${folder.name}」の移動先",
            folders = state.folders,
            excluded = excluded,
            onPick = { movingFolder = null; onMoveFolder(folder.id, it) },
            onDismiss = { movingFolder = null },
        )
    }

    deletingFolder?.let { folder ->
        FolderDeleteDialog(
            folder = folder,
            onConfirm = { deletingFolder = null; onDeleteFolder(folder.id) },
            onDismiss = { deletingFolder = null },
        )
    }

    if (filingSelection) {
        FolderPickerSheet(
            title = "${state.selectedMemoIds.size}件の移動先",
            folders = state.folders,
            excluded = emptySet(),
            onPick = { filingSelection = false; onBulkMoveToFolder(it) },
            onDismiss = { filingSelection = false },
        )
    }

    filingMemo?.let { memo ->
        FolderPickerSheet(
            title = "「${memo.displayTitle()}」の移動先",
            folders = state.folders,
            excluded = emptySet(),
            onPick = { filingMemo = null; onMoveMemoToFolder(memo.id, it) },
            onDismiss = { filingMemo = null },
        )
    }

    makingEpisodesFrom?.let { memo ->
        NotePickerSheet(
            title = "見出しから話を作る",
            emptyMessage = "まだノートがありません。作ると、見出しがそのまま話になります。",
            notes = noteState.notes,
            onPick = { noteId ->
                makingEpisodesFrom = null
                onMakeEpisodes(memo, noteId)
            },
            onCreate = {
                makingEpisodesFrom = null
                onMakeEpisodesInNewNote(memo, memo.displayTitle())
            },
            onDismiss = { makingEpisodesFrom = null },
        )
    }

    taggingMemo?.let { memo ->
        BulkTagSheet(
            mode = BulkTagMode.ADD,
            tags = state.tags,
            onApply = { tagIds ->
                taggingMemo = null
                onTagMemos(setOf(memo.id), tagIds)
            },
            onDismiss = { taggingMemo = null },
        )
    }

    if (showDisplayOptions) {
        MemoDisplayOptionsSheet(
            displayMode = displayMode,
            filter = state.filter,
            selectedTags = state.selectedTags,
            onDisplayModeChange = { mode ->
                onDisplayModeChange(mode)
                showDisplayOptions = false
            },
            onFilterChange = { mode ->
                onFilterChange(mode)
                showDisplayOptions = false
            },
            onOpenTags = {
                showDisplayOptions = false
                showTagFilter = true
            },
            onReset = {
                onDisplayModeChange(WallDisplayMode.COMBINED)
                onFilterChange(MemoFilterMode.ALL)
                onSortChange(MemoSortMode.UPDATED_DESC)
                onTagFilterChange(emptySet(), TagMatchMode.ANY)
                showDisplayOptions = false
            },
            onDismiss = { showDisplayOptions = false },
        )
    }

    if (showTagFilter) {
        TagFilterSheet(
            tags = state.tags,
            selectedTagIds = state.selectedTagIds,
            initialMatchMode = state.tagMatchMode,
            onApply = { ids, mode ->
                onTagFilterChange(ids, mode)
                showTagFilter = false
            },
            onDismiss = { showTagFilter = false },
        )
    }

    bulkTagMode?.let { mode ->
        BulkTagSheet(
            mode = mode,
            tags = if (mode == BulkTagMode.ADD) state.tags else state.removableTags,
            onApply = { tagIds ->
                if (mode == BulkTagMode.ADD) onBulkAddTags(tagIds) else onBulkRemoveTags(tagIds)
                bulkTagMode = null
            },
            onDismiss = { bulkTagMode = null },
        )
    }

}

/**
 * Where a memo is looked for: the scopes, the tags a memo can belong to, and the things done to the
 * list as a whole. It replaces a menu because a tag list is not a menu — it grows with the memos.
 */
@Composable
private fun MemoNavigationDrawerSheet(
    state: MemoListUiState,
    hasAnyMemos: Boolean,
    onShowAll: () -> Unit,
    onShowPinned: () -> Unit,
    onSelectTag: (Long) -> Unit,
    onOpenTagManagement: () -> Unit,
    onOpenArchive: () -> Unit,
    onOpenTrash: () -> Unit,
    onDisplayOptions: () -> Unit,
    onRequestSelection: () -> Unit,
    onImportMarkdown: () -> Unit,
    onExportVisible: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFolder: (Long?) -> Unit,
    onToggleFolder: (Long) -> Unit,
    onHoldFolder: (FolderEntity) -> Unit,
) {
    // Narrow enough to stay a hand's width: a drawer of one-word destinations does not need
    // most of the screen to say them.
    ModalDrawerSheet(modifier = Modifier.width(DRAWER_WIDTH).testTag("memo_drawer")) {
        // A lazy list: the folder tree can run to hundreds of rows, and it is the navigator's
        // job to show them all (docs/FOLDER_NAVIGATOR_AUDIT.md).
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("memo_drawer_list"),
            contentPadding = PaddingValues(vertical = ProductSpacing.md),
        ) {
            item(key = "all") {
                DrawerEntry(
                    label = "すべてのメモ",
                    icon = Icons.AutoMirrored.Outlined.Article,
                    selected = state.filter == MemoFilterMode.ALL && state.selectedTagIds.isEmpty(),
                    testTag = "drawer_all_memos",
                    onClick = onShowAll,
                )
            }
            item(key = "pinned") {
                DrawerEntry(
                    label = "固定したメモ",
                    icon = Icons.Outlined.PushPin,
                    selected = state.filter == MemoFilterMode.PINNED,
                    testTag = "drawer_pinned_memos",
                    onClick = onShowPinned,
                )
            }
            item(key = "archive") {
                DrawerEntry(
                    label = "アーカイブ",
                    icon = Icons.Outlined.Archive,
                    testTag = "open_archive",
                    onClick = onOpenArchive,
                )
            }
            item(key = "trash") {
                DrawerEntry(
                    label = "ゴミ箱",
                    icon = Icons.Outlined.Delete,
                    testTag = "open_trash",
                    onClick = onOpenTrash,
                )
            }
            item(key = "divider-folders") { DrawerDivider() }

            // フォルダ: the tree, expandable and collapsible, the open folder marked. Folders are
            // where documents are kept — never the outline inside one.
            item(key = "folders-header") {
                Text(
                    "フォルダ",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = ProductSpacing.lg + ProductSpacing.sm, vertical = ProductSpacing.sm),
                )
            }
            item(key = "folder-root") {
                NavigatorRootRow(isSelected = state.currentFolderId == null) { onOpenFolder(null) }
            }
            items(state.navigatorRows, key = { "folder-${it.folderId}" }) { row ->
                NavigatorFolderRow(
                    row = row,
                    onOpen = { onOpenFolder(row.folderId) },
                    onToggle = { onToggleFolder(row.folderId) },
                    onHold = { state.folders.firstOrNull { it.id == row.folderId }?.let(onHoldFolder) },
                )
            }
            item(key = "divider-tags") { DrawerDivider() }

            // No explainer when there are no tags: タグを管理 is right here and says the same
            // thing by existing. A sentence in a list of places is furniture in a hallway.
            items(state.tags, key = { "tag-${it.id}" }) { tag ->
                DrawerEntry(
                    label = tag.name,
                    icon = Icons.AutoMirrored.Outlined.Label,
                    selected = tag.id in state.selectedTagIds,
                    testTag = "drawer_tag_${tag.id}",
                    onClick = { onSelectTag(tag.id) },
                )
            }
            item(key = "manage-tags") {
                DrawerEntry(
                    label = "タグを管理",
                    icon = Icons.Outlined.Sell,
                    testTag = "manage_tags",
                    onClick = onOpenTagManagement,
                )
            }
            item(key = "divider-settings") { DrawerDivider() }

            // Only places live here. Commands that act on the list — display options,
            // selection, import, export — moved to the bar's overflow: a drawer where places
            // and verbs share one column has to be read whole every time it is opened.
            item(key = "settings") {
                DrawerEntry(
                    label = "設定",
                    icon = Icons.Outlined.Settings,
                    testTag = "memo_open_settings",
                    onClick = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun DrawerDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(
            horizontal = ProductSpacing.lg,
            vertical = ProductSpacing.sm,
        ),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun DrawerEntry(
    label: String,
    icon: ImageVector,
    testTag: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = {
            Text(
                label,
                // The size every other list of actions in this app is set at. The component's own
                // labelLarge is a semi-bold 15sp, which next to a sheet's plain 16sp reads as a
                // heavier thing rather than a different one.
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(DRAWER_ICON)) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier
            // 8dp here plus the item's own 16dp puts the icon 24dp from the edge, where the
            // action sheets put theirs.
            .padding(horizontal = ProductSpacing.sm)
            // The component stands 56dp tall on its own; the rest of the app is 48dp, and a
            // drawer that is one size larger than everything behind it looks like another app.
            .height(ProductSize.minimumTouchTarget)
            .testTag(testTag),
    )
}

/** Matched to the action sheets, which draw their icons at 22dp rather than the default 24dp. */
private val DRAWER_ICON = 22.dp

/** A hand's width of destinations, not most of the screen. */
private val DRAWER_WIDTH = 300.dp

/** Whether this process's one greeting has been spent. */
/** The one place the per-frame wall state is read, so only this layer recomposes with it. */
@Composable
private fun WallPlaybackLayer(
    state: kotlinx.coroutines.flow.StateFlow<CommentAnimationState>,
    offsetProvider: (PlaybackItem, Long, Float, Float) -> Float,
    modifier: Modifier = Modifier,
) {
    val playback = state.collectAsStateWithLifecycle()
    // The wall darkens under white stroked lettering while comments fly — the video
    // site's look, not the page's own ink.
    CommentRenderer(
        animationState = playback,
        offsetProvider = offsetProvider,
        colors = stageCommentRendererColors(),
        modifier = modifier,
        dimBehind = true,
    )
}

private var wallGreetedThisLaunch = false

/**
 * The wall's voice, riding in the search bar.
 *
 * It used to be a pair of floating buttons in the corner, which made it the third strong colour
 * on a wall that also had the button that writes. As a line icon in the bar it keeps its one-press
 * reach and stops competing: the only filled thing left on the wall is the thing that writes.
 * An icon button also has a real disabled state, which the floating button never did.
 */
@Composable
private fun WallTransportIcons(
    status: PlaybackStatus,
    enabled: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    if (status != PlaybackStatus.IDLE) {
        IconButton(
            onClick = onStop,
            modifier = Modifier.size(ProductSize.minimumTouchTarget).testTag("wall_stop"),
        ) {
            Icon(Icons.Outlined.Stop, contentDescription = "コメントを終える")
        }
    }
    when (status) {
        PlaybackStatus.PLAYING -> IconButton(
            onClick = onPause,
            modifier = Modifier.size(ProductSize.minimumTouchTarget).testTag("wall_pause"),
        ) {
            Icon(Icons.Outlined.Pause, contentDescription = "コメントを一時停止")
        }

        PlaybackStatus.PAUSED -> IconButton(
            onClick = onResume,
            modifier = Modifier.size(ProductSize.minimumTouchTarget).testTag("wall_resume"),
        ) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = "コメントを再開")
        }

        else -> IconButton(
            onClick = onPlay,
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.size(ProductSize.minimumTouchTarget).testTag("wall_play"),
        ) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = "コメントを流す")
        }
    }
}

@Composable
private fun MemoCompactSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    focusRequester: FocusRequester,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .semantics { contentDescription = "メモを検索" }
            .testTag("memo_search"),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onBackground,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            "メモを検索",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier
                            .size(ProductSize.minimumTouchTarget)
                            .testTag("memo_search_clear"),
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "検索語を消去")
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MemoDisplayOptionsSheet(
    displayMode: WallDisplayMode,
    filter: MemoFilterMode,
    selectedTags: List<TagEntity>,
    onDisplayModeChange: (WallDisplayMode) -> Unit,
    onFilterChange: (MemoFilterMode) -> Unit,
    onOpenTags: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("memo_display_options_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ProductSize.screenHorizontalPadding),
        ) {
            ProductSheetHeader(title = "表示するメモ", onDismiss = onDismiss)

            Text(
                "表示",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = ProductSpacing.lg),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(ProductSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(ProductSpacing.sm),
            ) {
                WallDisplayMode.entries.forEach { mode ->
                    FilterChip(
                        selected = displayMode == mode,
                        onClick = { onDisplayModeChange(mode) },
                        label = { Text(mode.label) },
                        modifier = Modifier
                            .heightIn(min = ProductSize.minimumTouchTarget)
                            .testTag("memo_wall_${mode.name}"),
                    )
                }
            }

            Text(
                "対象",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = ProductSpacing.lg),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(ProductSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(ProductSpacing.sm),
            ) {
                MemoFilterMode.entries.forEach { mode ->
                    FilterChip(
                        selected = filter == mode,
                        onClick = { onFilterChange(mode) },
                        label = { Text(mode.label) },
                        modifier = Modifier
                            .heightIn(min = ProductSize.minimumTouchTarget)
                            .testTag("memo_filter_${mode.name}"),
                    )
                }
            }

            Text(
                "タグ",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = ProductSpacing.lg),
            )
            Surface(
                onClick = onOpenTags,
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = ProductSpacing.sm)
                    .testTag("memo_tag_filter")
                    .semantics(mergeDescendants = true) {
                        contentDescription = if (selectedTags.isEmpty()) {
                            "タグで絞り込み、指定なし"
                        } else {
                            "タグで絞り込み、" + selectedTags.joinToString { it.name }
                        }
                    },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = ProductSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Label,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(
                        modifier = Modifier.weight(1f).padding(start = ProductSpacing.md),
                    ) {
                        Text("タグを選ぶ", style = MaterialTheme.typography.labelLarge)
                        Text(
                            when (selectedTags.size) {
                                0 -> "指定なし"
                                1 -> selectedTags.single().name
                                else -> "${selectedTags.size}個を選択中"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            TextButton(
                onClick = onReset,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(vertical = ProductSpacing.lg)
                    .testTag("reset_memo_display_options"),
            ) {
                Text("表示条件をリセット")
            }
        }
    }
}

@Composable
internal fun EmptyMemoList(
    state: MemoListUiState,
    onShowAll: () -> Unit,
) {
    val searching = state.query.isNotBlank()
    val (title, description) = when {
        state.totalMemoCount == 0 ->
            "まだメモがありません" to
                "メモを書いて、読み返した自分のコメントを流せます。"
        searching ->
            "検索結果がありません" to
                "語を減らすと広がります。スペースで絞り込み、#タグでタグだけ、" +
                "-語で除外、\"…\"で語順のまま探せます。"
        state.selectedTagIds.isNotEmpty() ->
            "このタグのメモはありません" to "タグの割り当てまたは他の条件を確認してください。"
        else -> "固定したメモはまだありません" to "ピン留めしたメモがここに表示されます。"
    }
    ProductEmptyState(
        title = title,
        description = description,
        // On a first run the floating button is already offering to write; a second button
        // saying the same thing would only split the eye between them.
        actionLabel = "すべて表示".takeIf { state.totalMemoCount > 0 },
        onAction = onShowAll.takeIf { state.totalMemoCount > 0 },
        icon = Icons.Outlined.Search.takeIf { searching },
        visual = if (state.totalMemoCount == 0) {
            {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_monochrome),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(88.dp),
                )
            }
        } else {
            null
        },
        prominent = state.totalMemoCount == 0,
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = ProductSpacing.xxl)
            .testTag(
                when {
                    searching -> "memo_search_empty_state"
                    state.totalMemoCount == 0 -> "memo_empty_state"
                    state.selectedTagIds.isNotEmpty() -> "memo_tag_empty_state"
                    else -> "memo_pinned_empty_state"
                },
            ),
    )
}

/** Marks every searched word inside [source] so the eye lands on it without re-reading the line. */
@Composable
internal fun markMatches(source: String, terms: List<String>): AnnotatedString {
    val background = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    return remember(source, terms, background) {
        if (terms.isEmpty()) {
            return@remember AnnotatedString(source)
        }
        buildAnnotatedString {
            append(source)
            terms.forEach { term ->
                if (term.isEmpty()) return@forEach
                var from = source.indexOf(term, ignoreCase = true)
                while (from >= 0) {
                    addStyle(
                        SpanStyle(background = background, fontWeight = FontWeight.Medium),
                        from,
                        from + term.length,
                    )
                    from = source.indexOf(term, from + term.length, ignoreCase = true)
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagFilterSheet(
    tags: List<TagEntity>,
    selectedTagIds: Set<Long>,
    initialMatchMode: TagMatchMode,
    onApply: (Set<Long>, TagMatchMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var pendingIds by remember(selectedTagIds) { mutableStateOf(selectedTagIds) }
    var matchMode by remember(initialMatchMode) { mutableStateOf(initialMatchMode) }
    val visible = tags.filter { TagNameNormalizer.matches(it.name, query) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("tag_filter_sheet")) {
        Column(
            Modifier.fillMaxWidth().imePadding()
                .padding(horizontal = ProductSize.screenHorizontalPadding),
        ) {
            Text("タグで絞り込み", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("タグを検索") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = ProductSpacing.md)
                    .testTag("tag_filter_search"),
            )
            // Bounded but yielding: the trailing actions must stay reachable while the IME is up
            // (docs/DESIGN_SYSTEM.md "Bottom sheets" / "Short-height layouts").
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).weight(1f, fill = false)) {
                items(visible, key = TagEntity::id) { tag ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            pendingIds = pendingIds.toggle(tag.id)
                        }
                            .heightIn(min = ProductSize.minimumTouchTarget)
                            .testTag("tag_filter_${tag.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = tag.id in pendingIds,
                            onCheckedChange = { pendingIds = pendingIds.toggle(tag.id) },
                        )
                        Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (pendingIds.size > 1) {
                Text("一致条件", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = matchMode == TagMatchMode.ANY,
                        onClick = { matchMode = TagMatchMode.ANY },
                    )
                    Text(
                        "いずれか",
                        Modifier.clickable { matchMode = TagMatchMode.ANY }
                            .testTag("tag_match_any"),
                    )
                    RadioButton(
                        selected = matchMode == TagMatchMode.ALL,
                        onClick = { matchMode = TagMatchMode.ALL },
                    )
                    Text(
                        "すべて",
                        Modifier.clickable { matchMode = TagMatchMode.ALL }
                            .testTag("tag_match_all"),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = ProductSpacing.md),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { pendingIds = emptySet() }) { Text("クリア") }
                TextButton(
                    onClick = { onApply(pendingIds, matchMode) },
                    modifier = Modifier.testTag("apply_tag_filter"),
                ) { Text("完了") }
            }
        }
    }
}

private enum class BulkTagMode { ADD, REMOVE }

/**
 * 「Markdownを読み込む」's one question. What the file holds may be memos or an outline
 * (docs/OUTLINE_EXPORT_IMPORT.md), so it counts ドキュメント, never メモ ([ImportWording]).
 */
@Composable
internal fun MarkdownImportConfirmDialog(
    parsed: List<ImportedMemo>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ImportWording.markdownConfirmTitle(parsed.size)) },
        text = {
            Text(ImportWording.markdownConfirmBody(withTags = parsed.any { it.tagNames.isNotEmpty() }))
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("import_confirm")) { Text("読み込む") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("やめる") }
        },
        modifier = Modifier.testTag("import_confirm_dialog"),
    )
}

@Composable
private fun BulkActionMenuItems(
    hasSelection: Boolean,
    onDismiss: () -> Unit,
    onSelectAll: () -> Unit,
    onPinned: (Boolean) -> Unit,
    onAddTags: () -> Unit,
    onRemoveTags: () -> Unit,
    onMoveToFolder: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
) {
    fun run(action: () -> Unit) { onDismiss(); action() }
    DropdownMenuItem(
        text = { Text("表示中をすべて選択") },
        onClick = { run(onSelectAll) },
        modifier = Modifier.testTag("select_all_visible"),
    )
    DropdownMenuItem(
        text = { Text("ピン留め") },
        enabled = hasSelection,
        onClick = { run { onPinned(true) } },
        modifier = Modifier.testTag("bulk_pin"),
    )
    DropdownMenuItem(
        text = { Text("ピンを解除") },
        enabled = hasSelection,
        onClick = { run { onPinned(false) } },
        modifier = Modifier.testTag("bulk_unpin"),
    )
    DropdownMenuItem(
        text = { Text("タグを追加") },
        enabled = hasSelection,
        onClick = { run(onAddTags) },
        modifier = Modifier.testTag("bulk_add_tags"),
    )
    DropdownMenuItem(
        text = { Text("タグを外す") },
        enabled = hasSelection,
        onClick = { run(onRemoveTags) },
        modifier = Modifier.testTag("bulk_remove_tags"),
    )
    DropdownMenuItem(
        text = { Text("フォルダへ移動") },
        enabled = hasSelection,
        onClick = { run(onMoveToFolder) },
        modifier = Modifier.testTag("bulk_move_folder"),
    )
    DropdownMenuItem(
        text = { Text("アーカイブ") },
        enabled = hasSelection,
        onClick = { run(onArchive) },
        modifier = Modifier.testTag("bulk_archive"),
    )
    DropdownMenuItem(
        text = { Text("ゴミ箱へ移動") },
        enabled = hasSelection,
        onClick = { run(onTrash) },
        modifier = Modifier.testTag("bulk_trash"),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BulkTagSheet(
    mode: BulkTagMode,
    tags: List<TagEntity>,
    onApply: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable(mode) { mutableStateOf("") }
    var selectedIds by remember(mode) { mutableStateOf<Set<Long>>(emptySet()) }
    val visible = tags.filter { TagNameNormalizer.matches(it.name, query) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("bulk_tag_sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding()
                .padding(horizontal = ProductSize.screenHorizontalPadding),
        ) {
            Text(
                if (mode == BulkTagMode.ADD) "タグを追加" else "タグを外す",
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("タグを検索") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = ProductSpacing.md),
            )
            if (tags.isEmpty()) {
                Text(
                    if (mode == BulkTagMode.ADD) "利用できるタグがありません" else "外せるタグがありません",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp).weight(1f, fill = false),
                ) {
                    items(visible, key = TagEntity::id) { tag ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { selectedIds = selectedIds.toggle(tag.id) }
                                .heightIn(min = ProductSize.minimumTouchTarget)
                                .testTag("bulk_tag_${tag.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = tag.id in selectedIds,
                                onCheckedChange = { selectedIds = selectedIds.toggle(tag.id) },
                            )
                            Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = ProductSpacing.md),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("キャンセル") }
                TextButton(
                    enabled = selectedIds.isNotEmpty(),
                    onClick = { onApply(selectedIds) },
                    modifier = Modifier.testTag("apply_bulk_tags"),
                ) { Text(if (mode == BulkTagMode.ADD) "追加" else "外す") }
            }
        }
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id


/** Some file managers label a .md file as plain text, so both are offered. */
private val MEMO_IMPORT_MIME_TYPES = arrayOf(MemoMarkdownExport.MIME_TYPE, "text/plain")

private val MemoFilterMode.label: String
    get() = when (this) {
        MemoFilterMode.ALL -> "すべて"
        MemoFilterMode.PINNED -> "ピン留め"
    }

private val MemoSortMode.label: String
    get() = when (this) {
        MemoSortMode.UPDATED_DESC -> "更新が新しい順"
        MemoSortMode.CREATED_DESC -> "作成が新しい順"
        MemoSortMode.TITLE_ASC -> "タイトル順"
        MemoSortMode.MANUAL -> "手動で並べた順"
    }

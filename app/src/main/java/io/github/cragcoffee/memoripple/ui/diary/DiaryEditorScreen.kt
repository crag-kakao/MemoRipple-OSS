package io.github.cragcoffee.memoripple.ui.diary

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.speech.SpeechState
import io.github.cragcoffee.memoripple.speech.SpeechStatus
import io.github.cragcoffee.memoripple.ui.memos.EditorTextField
import io.github.cragcoffee.memoripple.ui.memos.MemoBlockColumn
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.components.ProductTopBar
import io.github.cragcoffee.memoripple.data.AttachmentLimits
import io.github.cragcoffee.memoripple.data.PhotoAttachment
import io.github.cragcoffee.memoripple.ui.attachments.AttachmentImageLoader
import io.github.cragcoffee.memoripple.ui.attachments.PhotoAttachmentStrip

@Composable
fun DiaryEditorRoute(
    entryId: Long,
    onBack: () -> Unit,
    onReplayFutureComment: (Long) -> Unit,
) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    val viewModel: DiaryEditorViewModel = viewModel(
        key = "journal-$entryId",
        factory = DiaryEditorViewModel.factory(
            application.diaryRepository,
            application.futureDiaryCommentRepository,
            entryId,
            application.speechController,
            application.attachmentRepository,
            application.settingsRepository,
            contentStore = application.diaryContentStore,
        ),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val futureComments by viewModel.futureComments.collectAsStateWithLifecycle()
    val speechState by viewModel.speechState.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val imageLoader = remember(application) { AttachmentImageLoader(application.attachmentBlobStore) }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AttachmentLimits.MAX_PHOTOS_PER_RECORD),
    ) { uris -> viewModel.addPhotos(uris) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.refreshForResume()
                Lifecycle.Event.ON_STOP -> {
                    viewModel.saveNow()
                    viewModel.stopSpeech()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopSpeech()
        }
    }

    BackHandler(enabled = !state.isLoading) { viewModel.saveAndThen(onBack) }
    DiaryEditorScreen(
        state = state,
        speechState = speechState,
        photos = photos,
        imageLoader = imageLoader,
        onAddPhotos = {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onDeletePhoto = viewModel::deletePhoto,
        onReorderPhotos = viewModel::reorderPhotos,
        onBodyChange = viewModel::updateBody,
        onActivateText = viewModel::activateText,
        onUpdateBlockText = viewModel::updateBlockText,
        onMergeTextBlock = viewModel::mergeWithPrevious,
        onPendingCaretConsumed = viewModel::consumePendingCaret,
        onPhotoCaret = viewModel::rememberPhotoCaret,
        onBack = { viewModel.saveAndThen(onBack) },
        onMessageShown = viewModel::consumeMessage,
        futureComments = futureComments,
        canCreateFutureComment = state.exists && viewModel.isDiaryDateToday(),
        defaultFutureDate = viewModel::defaultFutureDate,
        onResolveRevealAt = viewModel::futureRevealAt,
        isFutureRevealAt = viewModel::isFutureRevealAt,
        onCreateFutureComment = viewModel::createFutureComment,
        onDeleteFutureComment = viewModel::deleteFutureComment,
        onReplayFutureComment = onReplayFutureComment,
        onStartSpeech = viewModel::startSpeech,
        onStopSpeech = viewModel::stopSpeech,
        onSpeechMessageShown = viewModel::clearSpeechMessage,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DiaryEditorScreen(
    state: DiaryEditorUiState,
    speechState: SpeechState,
    photos: List<PhotoAttachment>,
    imageLoader: AttachmentImageLoader,
    onAddPhotos: () -> Unit,
    onDeletePhoto: (PhotoAttachment) -> Unit,
    onReorderPhotos: (List<Long>, (Boolean) -> Unit) -> Unit,
    onBodyChange: (String) -> Unit,
    /** The entry written block by block (docs/MEMO_CONTENT_BLOCKS.md §11). */
    onActivateText: (Long) -> Unit,
    onUpdateBlockText: (Long, String) -> Unit,
    onMergeTextBlock: (Long) -> Unit,
    onPendingCaretConsumed: () -> Unit,
    /** Where the caret stands as the photo button is pressed (null: not writing). */
    onPhotoCaret: (Int?) -> Unit,
    onBack: () -> Unit,
    onMessageShown: () -> Unit,
    futureComments: List<io.github.cragcoffee.memoripple.domain.diary.FutureDiaryCommentItem>,
    canCreateFutureComment: Boolean,
    defaultFutureDate: () -> java.time.LocalDate,
    onResolveRevealAt: (java.time.LocalDate, java.time.LocalTime) -> Long,
    isFutureRevealAt: (Long) -> Boolean,
    onCreateFutureComment: (
        String,
        Long,
        io.github.cragcoffee.memoripple.domain.diary.FutureCommentExpression,
        () -> Unit,
    ) -> Unit,
    onDeleteFutureComment: (Long) -> Unit,
    onReplayFutureComment: (Long) -> Unit,
    onStartSpeech: () -> Boolean,
    onStopSpeech: () -> Unit,
    onSpeechMessageShown: () -> Unit,
) {
    var showFutureCreator by remember { mutableStateOf(false) }
    var showSpeechDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val keyboardVisible = WindowInsets.isImeVisible
    val speechActive = speechState.status == SpeechStatus.INITIALIZING ||
        speechState.status == SpeechStatus.SPEAKING

    // The field holds the text being written with its caret (docs/MEMO_CONTENT_BLOCKS.md §11): a
    // photo goes where the caret is. In an entry with photos it is bound to one text block, and
    // moving to another keeps each text's caret where it was left — the memo editor's binding.
    var activeField by remember { mutableStateOf(TextFieldValue(state.activeText)) }
    val blockSelections = remember { mutableStateMapOf<Long, TextRange>() }
    val blockRequesters = remember { mutableMapOf<Long, FocusRequester>() }
    val blockRequesterFor: (Long) -> FocusRequester = { id -> blockRequesters.getOrPut(id) { FocusRequester() } }
    val bodyRequester = remember { FocusRequester() }
    var boundTextId by remember { mutableStateOf(state.activeTextId) }
    if (boundTextId != state.activeTextId) {
        val from = boundTextId
        val to = state.activeTextId
        if (from != null && to != null) {
            blockSelections[from] = activeField.selection
            val text = state.activeText
            val kept = blockSelections[to] ?: TextRange(text.length)
            activeField = TextFieldValue(text, TextRange(kept.start.coerceAtMost(text.length), kept.end.coerceAtMost(text.length)))
        }
        boundTextId = to
    }
    if (activeField.text != state.activeText) activeField = activeField.copy(text = state.activeText)
    var bodyFocused by remember { mutableStateOf(false) }
    val onFieldChange: (TextFieldValue) -> Unit = { next ->
        activeField = next
        if (next.text != state.activeText) onBodyChange(next.text)
    }
    LaunchedEffect(state.structureVersion) {
        if (state.structureVersion == 0) return@LaunchedEffect
        val (id, offset) = state.pendingCaret ?: return@LaunchedEffect
        onPendingCaretConsumed()
        blockSelections[id] = TextRange(offset)
        if (boundTextId == id) activeField = activeField.copy(selection = TextRange(offset.coerceAtMost(activeField.text.length)))
        // The text is composed on the frame after the change; ask for it then.
        withFrameNanos { }
        withFrameNanos { }
        runCatching { (if (state.blockMode) blockRequesterFor(id) else bodyRequester).requestFocus() }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            onMessageShown()
        }
    }
    LaunchedEffect(speechState.message) {
        speechState.message?.let {
            snackbarHostState.showSnackbar(it)
            onSpeechMessageShown()
        }
    }

    Scaffold(
        topBar = {
            ProductTopBar(
                title = formatDiaryDate(state.diaryDate),
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = {
                            if (speechActive) onStopSpeech() else showSpeechDialog = true
                        },
                        enabled = state.body.isNotBlank(),
                        modifier = Modifier.testTag("diary_speech_action"),
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
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!state.isLoading) {
                // Pinned above the keyboard: the photo action and the save status stay in view
                // while the body is being written.
                Column(
                    Modifier.imePadding()
                        .padding(horizontal = ProductSize.screenHorizontalPadding),
                ) {
                    DiaryEditorActions(
                        state = state,
                        hasPhotos = photos.isNotEmpty(),
                        canAddPhotos = state.isEditable &&
                            !state.isImportingPhotos &&
                            photos.size < AttachmentLimits.MAX_PHOTOS_PER_RECORD,
                        onAddPhotos = {
                            // A photo goes where the caret is — between the words it stands in.
                            onPhotoCaret(if (bodyFocused) activeField.selection.min else null)
                            onAddPhotos()
                        },
                    )
                }
            }
        },
    ) { padding ->
        if (state.isLoading) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (state.blockMode) {
            // An entry with photos (docs/MEMO_CONTENT_BLOCKS.md §11): its words and photos in their
            // order in one column that scrolls, the photos as content — nothing counts them — and
            // 未来の自分へ after the last block, in the same scroll.
            Column(
                modifier = Modifier.fillMaxSize().padding(padding)
                    .padding(horizontal = ProductSize.screenHorizontalPadding),
            ) {
                DiaryPageStatus(state, speechState)
                MemoBlockColumn(
                    blocks = state.blocks,
                    activeTextId = state.activeTextId,
                    activeField = activeField,
                    onActiveFieldChange = onFieldChange,
                    onActivate = { id, selection ->
                        selection?.let { blockSelections[id] = it }
                        onActivateText(id)
                    },
                    readOnly = !state.isEditable,
                    onFocused = { bodyFocused = it },
                    focusRequesterFor = blockRequesterFor,
                    onToggleTaskActive = {},
                    onToggleTaskIn = { _, _ -> },
                    onBackspaceAtStart = onMergeTextBlock,
                    photos = photos,
                    imageLoader = imageLoader,
                    photoEditable = state.isEditable,
                    viewerEditable = state.isEditable,
                    importing = state.isImportingPhotos,
                    onReorderPhotos = onReorderPhotos,
                    onDeletePhoto = onDeletePhoto,
                    plain = true,
                    activeTag = "diary_body",
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = ProductSpacing.sm),
                    footer = {
                        FutureDiaryCommentSection(
                            comments = futureComments,
                            canCreate = canCreateFutureComment,
                            onCreate = { showFutureCreator = true },
                            onDelete = onDeleteFutureComment,
                            onReplay = onReplayFutureComment,
                        )
                        Spacer(Modifier.height(ProductSpacing.lg))
                    },
                )
            }
        } else {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                // Measured with the IME already subtracted: an open keyboard switches to the
                // scrollable layout the same way a genuinely short screen does, so the future
                // comment section below stays reachable while the body is bounded.
                val useCompactScrollableLayout = maxHeight < 600.dp
                // Bounded, and small enough to finish inside the viewport: an unbounded body lays
                // every line out at once and never scrolls its own cursor into view, while an
                // oversized one puts the cursor below the fold.
                val compactBodyHeight = (maxHeight - DIARY_COMPACT_BODY_CHROME).coerceAtLeast(120.dp)
            Column(
                // With the keyboard up the page scrolls and is **as tall as what it holds**
                // (2026-09-24): filling the viewport here left a band of nothing under 未来の自分へ,
                // because the bounded body reserves a fixed height and the rest no longer fills the
                // rest. Without the keyboard the page still fills, so the body can take the slack.
                modifier = Modifier
                    .then(if (useCompactScrollableLayout) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                    .padding(horizontal = ProductSize.screenHorizontalPadding)
                    .then(
                        if (useCompactScrollableLayout) {
                            Modifier.verticalScroll(rememberScrollState())
                        } else {
                            Modifier
                        },
                    ),
            ) {
                DiaryPageStatus(state, speechState)
                // The body is the page: no card, no outline — the text sits on the background, with
                // the placeholder as the only thing in an empty day. It is the memo editor's own
                // field (2026-09-24): bounded, it scrolls inside itself and carries the caret with
                // it, so an open keyboard never hides the line being written. It holds its caret,
                // so the first photo goes where the caret is (docs/MEMO_CONTENT_BLOCKS.md §11).
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .then(
                            if (useCompactScrollableLayout) {
                                Modifier.height(compactBodyHeight)
                            } else {
                                Modifier.weight(1f)
                            },
                        )
                        .padding(top = ProductSpacing.sm),
                ) {
                    EditorTextField(
                        value = activeField,
                        onValueChange = onFieldChange,
                        hint = "今日のことを書いてみましょう",
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxSize()
                            .onFocusChanged { bodyFocused = it.isFocused }
                            .testTag("diary_body"),
                        singleLine = false,
                        readOnly = !state.isEditable,
                        focusRequester = bodyRequester,
                    )
                }
                // An entry without blocks (none in a Room 27 database) still shows its photos under it.
                if (state.blocks.isEmpty() && !keyboardVisible) {
                    PhotoAttachmentStrip(
                        photos = photos,
                        imageLoader = imageLoader,
                        editable = state.isEditable,
                        importing = state.isImportingPhotos,
                        onDelete = onDeletePhoto,
                        reorderEnabled = state.isEditable,
                        onReorder = onReorderPhotos,
                        modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm),
                    )
                }
                FutureDiaryCommentSection(
                    comments = futureComments,
                    canCreate = canCreateFutureComment,
                    onCreate = { showFutureCreator = true },
                    onDelete = onDeleteFutureComment,
                    onReplay = onReplayFutureComment,
                )
                Spacer(Modifier.height(ProductSpacing.lg))
            }
            }
        }
    }

    if (showFutureCreator) {
        FutureCommentCreatorSheet(
            initialDate = defaultFutureDate(),
            onResolveRevealAt = onResolveRevealAt,
            isFutureRevealAt = isFutureRevealAt,
            onDismiss = { showFutureCreator = false },
            onSend = { text, revealAt, expression ->
                onCreateFutureComment(text, revealAt, expression) {
                    showFutureCreator = false
                }
            },
        )
    }

    if (showSpeechDialog) {
        AlertDialog(
            onDismissRequest = { showSpeechDialog = false },
            modifier = Modifier.testTag("diary_speech_dialog"),
            title = { Text("日記を読み上げる") },
            text = { Text("現在表示している日記本文を読み上げます。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (onStartSpeech()) showSpeechDialog = false
                    },
                    modifier = Modifier.testTag("start_diary_speech"),
                ) { Text("読み上げる") }
            },
            dismissButton = {
                TextButton(onClick = { showSpeechDialog = false }) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun DiaryEditorActions(
    state: DiaryEditorUiState,
    hasPhotos: Boolean,
    canAddPhotos: Boolean,
    onAddPhotos: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.lg),
        horizontalAlignment = Alignment.End,
    ) {
        if (state.isEditable) {
            Text(
                when (state.saveStatus) {
                    DiarySaveStatus.IDLE -> ""
                    DiarySaveStatus.SAVING -> "保存中…"
                    DiarySaveStatus.SAVED -> "保存済み"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = ProductSpacing.sm),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canAddPhotos) {
                IconButton(
                    onClick = onAddPhotos,
                    modifier = Modifier.size(ProductSize.minimumTouchTarget)
                        .testTag("diary_add_photo"),
                ) {
                    Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "写真を追加")
                }
            }
            Spacer(Modifier.weight(1f))
            // A locked day says so once, at the top beside the date (2026-09-24): the sentence that
            // used to repeat it here was the second of two, and one quiet line is enough.
        }
    }
}

// Shorter by the heading the page no longer draws: a titleLarge line and the 8dp above it.
private val DIARY_COMPACT_BODY_CHROME = 170.dp

/** The page's one line of state: 読み上げを準備中… while it lasts, and a LOCKED day says so once. */
@Composable
private fun DiaryPageStatus(state: DiaryEditorUiState, speechState: SpeechState) {
    // The bar above already carries the date, so the page does not name itself again.
    // What is left is the one state worth a line of its own while it lasts.
    if (speechState.status == SpeechStatus.INITIALIZING) {
        Text(
            "読み上げを準備中…",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = ProductSpacing.sm),
        )
    }
    // The state is shown only when it means something (2026-09-24, the user's review): the
    // fixed one-a-day lifecycle is retired, so 編集中 says nothing about a page that is simply
    // being written. LOCKED is the one state that still changes what the page does, and it is
    // the one the page says. The lifecycle itself is untouched.
    if (state.state == DiaryState.LOCKED) {
        Text(
            state.state.displayName(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = ProductSpacing.sm).testTag("diary_state_label"),
        )
    }
}

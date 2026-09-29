package io.github.cragcoffee.memoripple.ui.chat

import android.content.ClipData
import io.github.cragcoffee.memoripple.ui.OnTabReselect
import io.github.cragcoffee.memoripple.ui.TopLevelTab
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.R
import io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult
import io.github.cragcoffee.memoripple.domain.ai.AiTiming
import io.github.cragcoffee.memoripple.domain.ai.CommandPreview
import io.github.cragcoffee.memoripple.domain.ai.ModelAvailability
import io.github.cragcoffee.memoripple.domain.ai.ModelUnavailableReason
import io.github.cragcoffee.memoripple.domain.ai.WriteOutcome
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatConversation
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessage
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessageKind
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.documents.CreateDestination
import io.github.cragcoffee.memoripple.domain.folders.FolderChoice
import io.github.cragcoffee.memoripple.domain.ai.conversation.MemoChoice
import io.github.cragcoffee.memoripple.domain.documents.DocumentTitles
import io.github.cragcoffee.memoripple.domain.memos.PinnedTemplates
import io.github.cragcoffee.memoripple.domain.memos.TemplateFolder
import io.github.cragcoffee.memoripple.domain.memos.TemplatePicker
import io.github.cragcoffee.memoripple.domain.memos.StarterTemplates
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.memos.HomeShortcut
import io.github.cragcoffee.memoripple.domain.memos.HomeShortcuts
import io.github.cragcoffee.memoripple.domain.memos.TemplateFlow
import io.github.cragcoffee.memoripple.domain.memos.ThinkTemplates
import io.github.cragcoffee.memoripple.domain.memos.TemplateAction
import io.github.cragcoffee.memoripple.domain.memos.TemplateField
import io.github.cragcoffee.memoripple.domain.memos.TemplateFieldType
import io.github.cragcoffee.memoripple.domain.memos.TemplateScript
import io.github.cragcoffee.memoripple.domain.memos.TemplateTargetSpec
import io.github.cragcoffee.memoripple.domain.memos.TemplateValues
import io.github.cragcoffee.memoripple.ui.chat.AiWording.chatLabel
import io.github.cragcoffee.memoripple.ui.chat.AiWording.explanation
import io.github.cragcoffee.memoripple.ui.components.ProductCompactTopBar
import io.github.cragcoffee.memoripple.ui.components.ProductEmptyState
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * チャット (docs/CHAT_UI_TEMPLATE_V2.md §13): the place to ask MemoRipple for something in natural
 * words — one conversation, nothing else on top. The top bar is the menu (history), the
 * conversation's title with the model as its subtitle (a tap switches the model or chooses
 * 「AIモデルなし」), a new chat and an overflow; the body is the transcript — the user's words in a
 * dark bubble on the right, MemoRipple's flat on the left with a copy action and the model's
 * timing under an answer — with the current operation cards under it; the bottom is ＋
 * (templates), the input and send, pinned above the keyboard. Free text needs a Local AI
 * model; a template needs none — both run through the same orchestrator, the same preview and
 * the same confirm button. The screen knows no route, model file or engine — it hands refs to
 * the navigator.
 *
 * [stage]: 新しいチャット opens the same screen as its own stage — a distinct background, no
 * bottom navigation, a back arrow — and begins with no conversation; its first message creates
 * one, which the tab then follows. [onOpenStage] is the tab's 新しいチャット; on the stage the
 * icon starts another fresh conversation in place.
 */
@Composable
fun ChatRoute(
    onOpen: (DocumentRef) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAiModels: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenTemplates: () -> Unit,
    onEditTemplate: (String?) -> Unit,
    onEditTemplateDraft: (MemoTemplate) -> Unit = {},
    stage: Boolean = false,
    onBack: () -> Unit = {},
    onOpenStage: (() -> Unit)? = null,
) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.factory(
            application.aiOrchestrator, application.chatHistoryRepository, application.lastConversationStore, application.templateRepository.templates,
            models = application.modelManager, startFresh = stage,
            recent = application.recentTemplateRepository, now = { application.timeProvider.nowMillis() },
            pins = application.pinnedConversationStore,
            templatePins = application.pinnedTemplateStore,
            templateFolders = application.templateFolderRepository,
            chatDestination = application.chatDestinationStore,
            folderChoices = application.folderChoices,
            hints = application.chatHintStore,
            homeShortcuts = application.homeShortcutStore,
            templateRemover = application.templateRemover,
            memoChoices = application.memoChoices,
            memoSelections = application.chatMemoSelectionStore,
        ),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // チャット tapped again in the bottom bar: back to the chat's home (the tab only — the stage has no bar).
    if (!stage) OnTabReselect(TopLevelTab.CHAT) { viewModel.goHome() }
    // Phase 7: whenever this screen is (back) in front — first open, return from the Local AI モデル screen, return
    // from the background — the chat learns again whether a model can answer free text (the hint under the input).
    LifecycleResumeEffect(Unit) {
        viewModel.refreshAvailability()
        onPauseOrDispose { }
    }
    // A unique OPEN is handed to the navigator exactly once, from the host's side effect.
    val pendingOpen = state.ai.pendingOpen
    LaunchedEffect(pendingOpen) {
        if (pendingOpen != null) {
            viewModel.openConsumed()
            onOpen(pendingOpen)
        }
    }
    ChatScreen(
        state = state,
        stage = stage,
        onBack = onBack,
        onInputChange = viewModel::updateInput,
        onAsk = viewModel::ask,
        onOpen = onOpen,
        onOpenSettings = onOpenSettings,
        onOpenAiModels = onOpenAiModels,
        onOpenHistory = onOpenHistory,
        onOpenTemplates = onOpenTemplates,
        onEditTemplate = onEditTemplate,
        onNewConversation = onOpenStage ?: viewModel::newConversation,
        onOpenConversation = viewModel::openConversation,
        onDeleteConversation = viewModel::deleteConversation,
        onTogglePin = viewModel::togglePin,
        onDismissAiHint = viewModel::dismissAiHint,
        onShortcut = viewModel::openShortcut,
        onEditAsked = viewModel::editAskedAnswer,
        onRemoveShortcut = viewModel::removeHomeShortcut,
        onRenameShortcut = viewModel::renameHomeShortcut,
        onAddShortcut = viewModel::addHomeShortcut,
        onDeleteTemplate = viewModel::deleteTemplate,
        onDismissAi = viewModel::dismissAiResult,
        onChooseCandidate = { summary -> viewModel.chooseCandidate(summary); onOpen(summary.ref) },
        onConfirmWrite = viewModel::confirmWrite,
        onRetry = viewModel::retry,
        onOpenPicker = viewModel::openTemplatePicker,
        onClosePicker = viewModel::closeTemplatePicker,
        onPickTemplate = viewModel::pickTemplate,
        onAnswerOption = viewModel::answerOption,
        onAnswerDate = viewModel::answerDate,
        onSkip = viewModel::skipCurrent,
        onCancelSession = viewModel::cancelSession,
        onChooseTarget = viewModel::chooseTarget,
        onClarifyChoice = viewModel::answerClarification,
        onEditAnswer = viewModel::editAnswer,
        onSelectModel = viewModel::selectModel,
        onSelectNoModel = viewModel::selectNoModel,
        onUseSuggestion = viewModel::useSuggestion,
        onSaveThink = viewModel::saveThinkResult,
        onFinishThink = viewModel::finishThink,
        onTogglePinTemplate = viewModel::togglePinTemplate,
        onSaveConversation = viewModel::saveConversationAsMemo,
        onSelectDestination = viewModel::selectDestination,
        onCreateDestinationFolder = viewModel::createDestinationFolder,
        onOpenMemoPicker = viewModel::openMemoPicker,
        onCloseMemoPicker = viewModel::closeMemoPicker,
        onMemoPickerQuery = viewModel::setMemoPickerQuery,
        onMemoPickerFolder = viewModel::setMemoPickerFolder,
        onSelectMemo = viewModel::selectMemo,
        onClearSelectedMemo = viewModel::clearSelectedMemo,
        onDraftTemplate = { viewModel.templateDraftFromConversation()?.let { onEditTemplateDraft(it); true } ?: false },
        today = application.timeProvider.currentLocalDate(),
    )
}

/** The screen over its state alone (internal so the layout / theme / semantics tests can render it without an activity of the product). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ChatScreen(
    state: ChatUiState,
    stage: Boolean = false,
    onBack: () -> Unit = {},
    onInputChange: (String) -> Unit = {},
    onAsk: () -> Unit = {},
    onOpen: (DocumentRef) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenAiModels: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenTemplates: () -> Unit = {},
    onEditTemplate: (String?) -> Unit = {},
    onNewConversation: () -> Unit = {},
    onDismissAi: () -> Unit = {},
    onChooseCandidate: (DocumentSummary) -> Unit = {},
    onConfirmWrite: () -> Unit = {},
    onRetry: () -> Unit = {},
    onOpenPicker: () -> Unit = {},
    onClosePicker: () -> Unit = {},
    onPickTemplate: (MemoTemplate) -> Unit = {},
    onAnswerOption: (String) -> Unit = {},
    onAnswerDate: (String) -> Unit = {},
    onSkip: () -> Unit = {},
    onCancelSession: () -> Unit = {},
    onChooseTarget: (DocumentSummary) -> Unit = {},
    onClarifyChoice: (io.github.cragcoffee.memoripple.domain.ai.decision.ClarificationChoice) -> Unit = {},
    onEditAnswer: (String) -> Unit = {},
    onSelectModel: (String) -> Unit = {},
    onSelectNoModel: () -> Unit = {},
    onUseSuggestion: () -> Unit = {},
    onSaveThink: () -> Unit = {},
    onFinishThink: () -> Unit = {},
    onTogglePinTemplate: (String) -> Unit = {},
    onSaveConversation: () -> Unit = {},
    onDraftTemplate: () -> Boolean = { false },
    onSelectDestination: (Long?) -> Unit = {},
    onCreateDestinationFolder: (String) -> Unit = {},
    /** 「メモを選択」 (docs/CHAT_MEMO_CONTEXT.md): the sheet, its search and folder, a choice, and the chip's ×. */
    onOpenMemoPicker: () -> Unit = {},
    onCloseMemoPicker: () -> Unit = {},
    onMemoPickerQuery: (String) -> Unit = {},
    onMemoPickerFolder: (Long?) -> Unit = {},
    onSelectMemo: (Long) -> Unit = {},
    onClearSelectedMemo: () -> Unit = {},
    onOpenConversation: (Long) -> Unit = {},
    onDeleteConversation: (Long) -> Unit = {},
    onTogglePin: (Long) -> Unit = {},
    /** UI/UX review 2026-09-23: the × on the hint above the input — closed for good. */
    onDismissAiHint: () -> Unit = {},
    /** The chat home launcher (2026-09-23): a cell of the grid was tapped — an entrance, never an action. */
    onShortcut: (HomeShortcut) -> Unit = {},
    /** 「修正」 on a preview that came from one asked question (2026-09-24): ask it again. */
    onEditAsked: () -> Unit = {},
    onRemoveShortcut: (String) -> Unit = {},
    onRenameShortcut: (String, String) -> Unit = { _, _ -> },
    onAddShortcut: (String) -> Unit = {},
    onDeleteTemplate: (String) -> Unit = {},
    today: LocalDate = LocalDate.now(),
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val hideKeyboard: () -> Unit = { keyboard?.hide() }
    // Back, innermost first: an open question is cancelled; a preview or a candidate list closes; while a confirmed write runs nothing
    // happens; on the stage the system Back is the back arrow; otherwise the tab's own Back (to メモ) stands. The picker sheet closes itself.
    val executing = state.ai.phase == AiPhase.EXECUTING
    val asking = state.session?.asking == true
    BackHandler(enabled = asking || state.ai.isDismissable || executing || stage) {
        when {
            executing -> Unit
            asking -> onCancelSession()
            state.ai.isDismissable -> onDismissAi()
            else -> onBack()
        }
    }
    val listState = rememberLazyListState()
    val imeVisible = WindowInsets.isImeVisible
    // 「修正」 on a template preview (§16): the list of answers under the card; closed by a new result
    var editOpen by remember(state.ai.result) { mutableStateOf(false) }
    // 「この会話からテンプレートを作成」 with nothing to draft: one small card, dismissed by its ×
    var draftNotice by remember { mutableStateOf(false) }
    // the newest line — a message, a status, a card — is scrolled into view as it arrives, and again when the keyboard rises over it
    LaunchedEffect(state.transcript.size, state.ai.phase, state.ai.result, state.session?.step, imeVisible, editOpen) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last > 0) listState.animateScrollToItem(last)
    }
    val notAtEnd by remember { derivedStateOf { listState.canScrollForward } }
    var overflow by remember { mutableStateOf(false) }
    // the history as a side drawer (2026-09-21 evening): the menu icon opens it over the chat; on the stage the left icon is Back and the overflow keeps 「チャット履歴」
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val closeDrawer: () -> Unit = { drawerScope.launch { drawerState.close() } }
    var deleteAsk by remember { mutableStateOf<ChatConversation?>(null) }
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !stage && state.historyEnabled,
        drawerContent = {
            HistoryDrawer(
                drawerState = drawerState,
                conversations = state.conversations,
                pinnedIds = state.pinnedConversationIds,
                currentId = state.conversationId,
                onOpen = { id -> closeDrawer(); onOpenConversation(id) },
                onTogglePin = onTogglePin,
                onDeleteAsk = { deleteAsk = it },
                onNew = { closeDrawer(); onNewConversation() },
                onManage = { closeDrawer(); onOpenHistory() },
            )
        },
    ) {
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val copy: (String) -> Unit = { text -> clipboardScope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MemoRipple", text))) } }
    val background = if (stage) chatStageBackground() else MaterialTheme.colorScheme.background
    Scaffold(
        // the host already keeps the content above the navigation bar (or its bottom bar); the input bar only follows the keyboard
        modifier = Modifier.consumeWindowInsets(WindowInsets.navigationBars).let { if (stage) it.testTag("chat_stage") else it },
        containerColor = background,
        contentColor = MaterialTheme.colorScheme.onBackground,   // black is no scheme colour: the content colour must be said
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ProductCompactTopBar(
                modifier = Modifier.testTag("chat_compact_top_bar"),
                containerColor = background,
                navigationIcon = {
                    if (stage) {
                        IconButton(onClick = onBack, modifier = Modifier.size(ProductSize.minimumTouchTarget).testTag("chat_stage_back")) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                        }
                    } else {
                        IconButton(onClick = { drawerScope.launch { drawerState.open() } }, enabled = state.historyEnabled, modifier = Modifier.size(ProductSize.minimumTouchTarget).testTag("chat_history")) {
                            Icon(Icons.Outlined.Menu, contentDescription = "チャット履歴")
                        }
                    }
                },
                centerContent = {
                    TitleWithModel(
                        title = state.conversationTitle ?: "チャット",
                        subtitle = AiWording.modelSubtitle(state.ai.availability),
                        models = state.installedModels,
                        selectedModelId = state.selectedModelId,
                        onSelectModel = onSelectModel,
                        onSelectNoModel = onSelectNoModel,
                        onOpenAiModels = onOpenAiModels,
                    )
                },
                action = {
                    Row {
                        IconButton(onClick = onNewConversation, enabled = !state.busy, modifier = Modifier.size(ProductSize.minimumTouchTarget).testTag("chat_new_conversation")) {
                            Icon(painterResource(R.drawable.ic_edit_square), contentDescription = "新しいチャット")
                        }
                        Box {
                            IconButton(onClick = { overflow = true }, modifier = Modifier.size(ProductSize.minimumTouchTarget).testTag("chat_overflow")) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "その他")
                            }
                            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                if (stage) DropdownMenuItem(text = { Text("チャット履歴") }, enabled = state.historyEnabled, onClick = { overflow = false; onOpenHistory() }, modifier = Modifier.testTag("chat_open_history"))
                                // Review Batch 2: the whole conversation as a memo, by rule, through the preview and the one confirm; only when there is one
                                DropdownMenuItem(text = { Text("この会話をメモとして保存") }, enabled = state.conversationId != null && state.transcript.isNotEmpty() && !state.busy, onClick = { overflow = false; onSaveConversation() }, modifier = Modifier.testTag("chat_save_conversation"))
                                // 「この会話からテンプレートを作成」: a draft for the editor from the questions asked and answered here — saved only by the editor
                                DropdownMenuItem(text = { Text("この会話からテンプレートを作成") }, enabled = state.conversationId != null && state.transcript.isNotEmpty() && !state.busy, onClick = { overflow = false; draftNotice = !onDraftTemplate() }, modifier = Modifier.testTag("chat_template_from_conversation"))
                                DropdownMenuItem(text = { Text("テンプレートを管理") }, onClick = { overflow = false; onOpenTemplates() }, modifier = Modifier.testTag("chat_open_templates"))
                                DropdownMenuItem(text = { Text("Local AIモデル") }, onClick = { overflow = false; onOpenAiModels() }, modifier = Modifier.testTag("chat_open_ai_models"))
                                DropdownMenuItem(text = { Text("設定") }, onClick = { overflow = false; onOpenSettings() }, modifier = Modifier.testTag("chat_open_settings"))
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            InputBar(
                value = state.input,
                enabled = !state.busy,
                availability = state.ai.availability,
                hintDismissed = state.aiHintDismissed,
                background = background,
                placeholder = if (state.session?.asking == true) "回答を入力" else "メッセージを入力",
                onValueChange = onInputChange,
                onSend = { hideKeyboard(); onAsk() },
                onPlus = onOpenPicker,
                onOpenAiModels = onOpenAiModels,
                modifier = Modifier.imePadding(),
                onDismissHint = onDismissAiHint,
                folderChoices = state.folderChoices,
                destination = state.destination,
                onSelectDestination = onSelectDestination,
                onCreateFolder = onCreateDestinationFolder,
                selectedMemo = state.selectedMemo,
                onOpenMemoPicker = onOpenMemoPicker,
                onClearSelectedMemo = onClearSelectedMemo,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = ProductSize.screenHorizontalPadding).testTag("chat_list"),
                verticalArrangement = Arrangement.spacedBy(ProductSpacing.sm),
                contentPadding = PaddingValues(top = ProductSpacing.lg),   // the conversation breathes under the top bar
            ) {
                if (state.historyEnabled) {
                    val lastIndex = state.transcript.lastIndex
                    itemsIndexed(state.transcript, key = { _, m -> "msg-${m.id}" }) { index, message ->
                        TranscriptRow(message, timing = state.ai.timing.takeIf { index == lastIndex && message.role == ChatRole.ASSISTANT }, onCopy = copy)
                    }
                }
                val nothingYet = state.transcript.isEmpty() && state.ai.phase == AiPhase.IDLE && state.session == null
                if (nothingYet) {
                    // the home launcher belongs to an empty conversation only: one line of it, or an open question, and the grid is gone
                    val shortcuts = if (state.clarification == null) HomeShortcuts.of(state.templates, state.homeShortcuts) else emptyList()
                    item(key = "empty") { EmptyConversation(onExample = onInputChange, shortcuts = shortcuts, onShortcut = onShortcut, onRemoveShortcut = onRemoveShortcut, onRenameShortcut = onRenameShortcut) }
                }
                // Template = conversation script (§16): the candidates for a target name, then the chips of the open question
                state.session?.let { session ->
                    if (session.step == TemplateScript.Step.AskTarget && session.targetCandidates.isNotEmpty()) {
                        items(session.targetCandidates, key = { "target-${it.ref.kind.name}-${it.ref.id}" }) { summary -> ResultRow(summary = summary, onClick = { onChooseTarget(summary) }, tagPrefix = "chat_target_candidate") }
                    }
                    if (session.asking && state.ai.phase == AiPhase.IDLE) {
                        item(key = "answer_options") { AnswerOptions(session, today, onOption = onAnswerOption, onDate = onAnswerDate, onSkip = onSkip, onCancel = onCancelSession) }
                    }
                }
                // DecisionEngine clarification (Phase 3, docs/DECISION_ENGINE.md): the fixed choices under the
                // asked question — small chips of what the user already saw, never a form, never a technical word
                state.clarification?.takeIf { it.choices.isNotEmpty() }?.let { pending ->
                    item(key = "clarify_choices") {
                        FlowRow(modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.xs).testTag("chat_clarify_choices"), horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs), verticalArrangement = Arrangement.spacedBy(ProductSpacing.xs)) {
                            pending.choices.forEach { choice ->
                                FilterChip(
                                    selected = false,
                                    onClick = { onClarifyChoice(choice) },
                                    label = { Text(choice.label) },
                                    modifier = Modifier.testTag("chat_clarify_choice_${choice.ordinal ?: "anchor"}"),
                                )
                            }
                        }
                    }
                }
                when (state.ai.phase) {
                    AiPhase.IDLE -> Unit
                    AiPhase.LOADING -> item(key = "ai_loading") { StatusLine("AIモデルを読み込んでいます…", "chat_ai_status_loading") }
                    AiPhase.GENERATING -> item(key = "ai_generating") { StatusLine("処理しています…", "chat_ai_status_generating") }
                    AiPhase.DONE -> aiResult(state.ai, executing = false, onOpen, onDismissAi, onChooseCandidate, onConfirmWrite, onOpenAiModels, onRetry, onOpenPicker, onUseSuggestion, state.session, { key -> editOpen = false; onEditAnswer(key) }, editOpen, { editOpen = !editOpen }, state.canEditAsked, onEditAsked, onSaveThink, onFinishThink)
                    AiPhase.EXECUTING -> aiResult(state.ai, executing = true, onOpen, onDismissAi, onChooseCandidate, onConfirmWrite, onOpenAiModels, onRetry, onOpenPicker, onUseSuggestion, state.session, onEditAnswer, false, {}, false, {}, {}, {})
                }
                if (draftNotice) {
                    item(key = "draft_none") { AiCard(tag = "chat_template_draft_none", title = "テンプレートにできる質問が見つかりませんでした", body = "MemoRippleの質問とその答えがある会話から作れます。", onDismiss = { draftNotice = false }) }
                }
                item(key = "bottom") { Spacer(Modifier.height(ProductSpacing.xl)) }
            }
            // the reference's chevron: one tap back to the newest line when the list has been scrolled up
            if (notAtEnd) {
                val scope = rememberCoroutineScope()
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1) } },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = ProductSpacing.sm).testTag("chat_scroll_to_bottom"),
                ) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "最新のメッセージへ")
                }
            }
        }
    }
    }
    deleteAsk?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleteAsk = null },
            modifier = Modifier.testTag("chat_drawer_delete_dialog"),
            title = { Text("「${conversation.title}」を削除しますか？") },
            text = { Text("この会話だけを削除します。メモや日記は削除されません。") },
            confirmButton = { TextButton(onClick = { deleteAsk = null; onDeleteConversation(conversation.id) }, modifier = Modifier.testTag("chat_drawer_delete_confirm")) { Text("削除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteAsk = null }, modifier = Modifier.testTag("chat_drawer_delete_cancel")) { Text("キャンセル") } },
        )
    }
    state.memoPicker?.let { picker ->
        MemoPickerSheet(
            state = picker,
            folders = state.folderChoices,
            onQuery = onMemoPickerQuery,
            onFolder = onMemoPickerFolder,
            onPick = { id -> hideKeyboard(); onSelectMemo(id) },
            onDismiss = { hideKeyboard(); onCloseMemoPicker() },
        )
    }
    if (state.pickerOpen) {
        TemplatePickerSheet(
            templates = state.templates,
            recent = state.recentTemplates,
            pinnedIds = state.pinnedTemplateIds,
            folders = state.templateFolders,
            onTogglePin = onTogglePinTemplate,
            onDismiss = onClosePicker,
            onPick = onPickTemplate,
            onCreate = { onClosePicker(); onEditTemplate(null) },
            onManage = { onClosePicker(); onOpenTemplates() },
            openAt = state.pickerSection,
            adding = state.pickerMode == PickerMode.ADD_SHORTCUT,
            onHome = state.homeShortcuts.map { it.templateId },
            onAddShortcut = onAddShortcut,
            onDeleteTemplate = onDeleteTemplate,
        )
    }
}

/** The stage's own background (UI review 2026-09-21): black over a dark theme, as the reference; the lowest surface over a light one. The host paints the system bars with it too. */
@Composable
fun chatStageBackground(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color.Black else MaterialTheme.colorScheme.surfaceContainerLowest

/**
 * The title with the model under it, as the reference shows them. The subtitle is the model
 * button: a tap lists the installed models, 「AIモデルなし」 and 「モデルを管理」. Choosing none stops
 * the AI (the manager clears the selection and unloads); choosing a model selects it — the next
 * free text loads it. Nothing here loads a model.
 */
@Composable
private fun TitleWithModel(
    title: String,
    subtitle: String,
    models: List<ModelChoice>,
    selectedModelId: String?,
    onSelectModel: (String) -> Unit,
    onSelectNoModel: () -> Unit,
    onOpenAiModels: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().testTag("chat_title"),
        )
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClickLabel = "AIモデルを変更") { open = true }.padding(horizontal = ProductSpacing.xs).testTag("chat_model_button"),
            ) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("chat_subtitle"),
                )
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.name, fontWeight = if (model.id == selectedModelId) FontWeight.SemiBold else null) },
                        onClick = { open = false; onSelectModel(model.id) },
                        modifier = Modifier.testTag("chat_model_option_${model.id}"),
                    )
                }
                DropdownMenuItem(
                    text = { Text(AiWording.NO_MODEL, fontWeight = if (selectedModelId == null) FontWeight.SemiBold else null) },
                    onClick = { open = false; onSelectNoModel() },
                    modifier = Modifier.testTag("chat_model_none"),
                )
                DropdownMenuItem(text = { Text("モデルを管理") }, onClick = { open = false; onOpenAiModels() }, modifier = Modifier.testTag("chat_model_manage"))
            }
        }
    }
}

/**
 * The history as a side drawer (2026-09-21 evening, refined 21:29 — the ChatGPT / Claude shape):
 * 「チャット」 tight at the top with a search icon (a field that filters the rows by title), 「ピン留め」
 * when any, 「最近」 — the conversations newest first, the current one marked, a tap opens it in
 * place, a long press opens a small menu (ピン留め / 削除) — then 「新しいチャット」 and 「履歴を管理」
 * (the full screen, where everything can be deleted). Narrower than the screen so the chat shows
 * beside it. Titles only; nothing here runs anything.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryDrawer(
    drawerState: DrawerState,
    conversations: List<ChatConversation>,
    pinnedIds: Set<Long>,
    currentId: Long?,
    onOpen: (Long) -> Unit,
    onTogglePin: (Long) -> Unit,
    onDeleteAsk: (ChatConversation) -> Unit,
    onNew: () -> Unit,
    onManage: () -> Unit,
) {
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val shown = if (query.isBlank()) conversations else conversations.filter { it.title.contains(query.trim(), ignoreCase = true) }
    val pinned = shown.filter { it.id in pinnedIds }
    val recent = shown.filter { it.id !in pinnedIds }
    // given the drawer's state, Material3 closes the open drawer on Back before anything else hears it (the S20 final smoke, 2026-09-27)
    ModalDrawerSheet(
        drawerState = drawerState,
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        windowInsets = WindowInsets(0, 0, 0, 0),   // the host already keeps the chat below the status bar: no second gap at the top
        modifier = Modifier.fillMaxWidth(0.8f).testTag("chat_drawer"),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = ProductSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm)) {
                Text("チャット", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).padding(start = ProductSpacing.sm))
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }, modifier = Modifier.size(ProductSize.minimumTouchTarget).testTag("chat_drawer_search")) {
                    Icon(if (searching) Icons.Outlined.Clear else Icons.Outlined.Search, contentDescription = if (searching) "検索をやめる" else "履歴を検索")
                }
            }
            if (searching) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("履歴を検索") },
                    modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.xs).testTag("chat_drawer_search_field"),
                )
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (pinned.isNotEmpty()) {
                    item(key = "pinned_title") { Text("ピン留め", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = ProductSpacing.sm, top = ProductSpacing.md, bottom = ProductSpacing.xs).testTag("chat_drawer_section_pinned")) }
                    items(pinned, key = { "pin-${it.id}" }) { c -> DrawerRow(c, current = c.id == currentId, pinned = true, tag = "chat_drawer_pinned_${c.id}", onOpen = onOpen, onTogglePin = onTogglePin, onDeleteAsk = onDeleteAsk) }
                }
                item(key = "recent_title") { Text("最近", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = ProductSpacing.sm, top = ProductSpacing.md, bottom = ProductSpacing.xs)) }
                if (recent.isEmpty()) {
                    item(key = "empty") { Text(if (conversations.isEmpty()) "チャット履歴はありません" else "見つかりません", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(ProductSpacing.sm).testTag(if (conversations.isEmpty()) "chat_drawer_empty" else "chat_drawer_no_match")) }
                }
                items(recent, key = { it.id }) { c -> DrawerRow(c, current = c.id == currentId, pinned = false, tag = "chat_drawer_row_${c.id}", onOpen = onOpen, onTogglePin = onTogglePin, onDeleteAsk = onDeleteAsk) }
            }
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm).testTag("chat_drawer_new")) {
                Icon(painterResource(R.drawable.ic_edit_square), contentDescription = null, modifier = Modifier.size(18.dp))
                Text("新しいチャット", modifier = Modifier.padding(start = ProductSpacing.sm))
            }
            TextButton(onClick = onManage, modifier = Modifier.fillMaxWidth().padding(bottom = ProductSpacing.md).testTag("chat_drawer_manage")) { Text("履歴を管理") }
        }
    }
}

/** One conversation in the drawer: title and time; a tap opens it; a long press opens ピン留め / 削除. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerRow(c: ChatConversation, current: Boolean, pinned: Boolean, tag: String, onOpen: (Long) -> Unit, onTogglePin: (Long) -> Unit, onDeleteAsk: (ChatConversation) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            modifier = Modifier.fillMaxWidth()
                .combinedClickable(onClick = { onOpen(c.id) }, onLongClick = { menu = true }, onLongClickLabel = "メニュー")
                .semantics(mergeDescendants = true) { selected = current }
                .testTag(tag),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = ProductSpacing.sm, vertical = ProductSpacing.sm)) {
                Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatVersion(c.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // the menu opens on the right of the row, as ChatGPT's (2026-09-21 22:58): anchored at the row's end, an icon on each entry
        Box(Modifier.align(Alignment.TopEnd)) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.large, modifier = Modifier.testTag("chat_drawer_menu")) {
                DropdownMenuItem(
                    text = { Text(if (pinned) "ピン留めを外す" else "ピン留め") },
                    leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                    onClick = { menu = false; onTogglePin(c.id) }, modifier = Modifier.testTag("chat_drawer_pin"),
                )
                DropdownMenuItem(
                    text = { Text("削除", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDeleteAsk(c) }, modifier = Modifier.testTag("chat_drawer_delete"),
                )
            }
        }
    }
}

/**
 * A quiet empty state: the question, the home launcher, and two or three examples a tap puts into
 * the input. The grid reads first and the examples under it (the launcher is the entrance, the
 * examples are a hint about what free text can say).
 */
@Composable
private fun EmptyConversation(onExample: (String) -> Unit, shortcuts: List<HomeShortcut> = emptyList(), onShortcut: (HomeShortcut) -> Unit = {}, onRemoveShortcut: (String) -> Unit = {}, onRenameShortcut: (String, String) -> Unit = { _, _ -> }) {
    Column(Modifier.fillMaxWidth().padding(top = ProductSpacing.xl).testTag("chat_empty")) {
        Text("MemoRippleに何を頼みますか？", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = ProductSpacing.xs))
        Text("メッセージを入力するか、＋からテンプレートを選べます。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = ProductSpacing.sm))
        if (shortcuts.isNotEmpty()) HomeLauncher(shortcuts, onShortcut, onRemoveShortcut, onRenameShortcut)
        listOf("昨日の日記を探して", "MemoRipple開発に追記して", "振り返りのテンプレートを作りたい").forEach { example ->
            Text(
                example,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { onExample(example) }.padding(vertical = ProductSpacing.xs).testTag("chat_example"),
            )
        }
    }
}

/**
 * The chat home's launcher (human brief 2026-09-23): four columns, at most two rows, of light
 * shortcuts — the four fixed entrances and the user's pinned templates — in the app's own colours,
 * with no card around a cell and no colour of its own. A tap opens an entrance that already
 * exists; the grid itself does nothing else. The mark never carries the meaning alone: every cell
 * has its word under it and says that word to a screen reader.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeLauncher(
    shortcuts: List<HomeShortcut>,
    onShortcut: (HomeShortcut) -> Unit,
    onRemoveShortcut: (String) -> Unit,
    onRenameShortcut: (String, String) -> Unit,
) {
    val COLUMNS = 4
    // a long press belongs to the cell the user pressed; the rename dialog is opened from its menu
    var menuFor by remember { mutableStateOf<HomeShortcut.Template?>(null) }
    var renameFor by remember { mutableStateOf<HomeShortcut.Template?>(null) }
    Column(
        Modifier.fillMaxWidth().padding(bottom = ProductSpacing.md).testTag("chat_home_launcher"),
        verticalArrangement = Arrangement.spacedBy(ProductSpacing.xs),
    ) {
        shortcuts.chunked(COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                row.forEach { shortcut ->
                    Box(Modifier.weight(1f)) {
                        HomeShortcutCell(
                            shortcut,
                            onClick = { onShortcut(shortcut) },
                            onLongClick = (shortcut as? HomeShortcut.Template)?.let { { menuFor = it } },
                        )
                        // the menu sits under the cell it belongs to, as the drawer's does at its row's end
                        val open = menuFor
                        if (open != null && open.key == shortcut.key) {
                            DropdownMenu(expanded = true, onDismissRequest = { menuFor = null }, shape = MaterialTheme.shapes.large, modifier = Modifier.testTag("chat_home_shortcut_menu")) {
                                DropdownMenuItem(
                                    text = { Text("名前を変更") },
                                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                                    onClick = { renameFor = open; menuFor = null },
                                    modifier = Modifier.testTag("chat_home_shortcut_rename_${open.template.id}"),
                                )
                                DropdownMenuItem(
                                    text = { Text("ホームから削除", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menuFor = null; onRemoveShortcut(open.template.id) },
                                    modifier = Modifier.testTag("chat_home_shortcut_remove_${open.template.id}"),
                                )
                            }
                        }
                    }
                }
                // the last row keeps the columns aligned; no empty slot is drawn
                repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    renameFor?.let { target ->
        var name by remember(target.key) { mutableStateOf(target.entry.label) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("名前を変更") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= HomeShortcuts.MAX_LABEL_CHARS) name = it },
                        singleLine = true,
                        label = { Text("ホームでの名前") },
                        placeholder = { Text(target.template.name) },
                        modifier = Modifier.fillMaxWidth().testTag("chat_home_shortcut_rename_input"),
                    )
                    Text(
                        "空のままにすると「${target.template.name}」に戻ります。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = ProductSpacing.xs),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { val t = target; renameFor = null; onRenameShortcut(t.template.id, name) }, modifier = Modifier.testTag("chat_home_shortcut_rename_confirm")) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("キャンセル") } },
            modifier = Modifier.testTag("chat_home_shortcut_rename_dialog"),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeShortcutCell(shortcut: HomeShortcut, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val description = shortcut.label
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onClickLabel = description, role = Role.Button, onLongClick = onLongClick, onLongClickLabel = onLongClick?.let { "メニュー" })
            .heightIn(min = ProductSize.minimumTouchTarget)
            .padding(vertical = ProductSpacing.xs, horizontal = 2.dp)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag(shortcut.testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        HomeShortcutMark(shortcut)
        Text(
            shortcut.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

/** The tag a cell carries: the four entrances by name, a template of the home by its id. */
private val HomeShortcut.testTag: String
    get() = when (this) {
        HomeShortcut.Memo -> "chat_home_shortcut_memo"
        HomeShortcut.Search -> "chat_home_shortcut_search"
        HomeShortcut.Journal -> "chat_home_shortcut_journal"
        HomeShortcut.Organize -> "chat_home_shortcut_organize"
        HomeShortcut.Add -> "chat_home_shortcut_add"
        is HomeShortcut.Template -> "chat_home_shortcut_template_${template.id}"
    }

/**
 * The app's own marks, from the design system's icon set — never an emoji, never a colour of its
 * own. A pinned template is marked by what it does, so the grid reads at a glance.
 */
@Composable
private fun HomeShortcutMark(shortcut: HomeShortcut) {
    val mark = when (shortcut) {
        HomeShortcut.Memo -> Icons.Outlined.EditNote
        HomeShortcut.Search -> Icons.Outlined.Search
        HomeShortcut.Journal -> Icons.Outlined.CalendarMonth
        HomeShortcut.Organize -> Icons.Outlined.Lightbulb
        HomeShortcut.Add -> Icons.Outlined.Add
        is HomeShortcut.Template -> when {
            shortcut.template.flow == TemplateFlow.THINK -> Icons.Outlined.Lightbulb
            shortcut.template.action == TemplateAction.SEARCH -> Icons.Outlined.Search
            shortcut.template.action == TemplateAction.APPEND -> Icons.Outlined.PlaylistAdd
            shortcut.template.documentKind == DocumentKind.JOURNAL -> Icons.Outlined.CalendarMonth
            else -> Icons.Outlined.EditNote
        }
    }
    Icon(mark, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
}

/**
 * One line of the persistent transcript — read-only: the user's words as a dark rounded bubble on
 * the right, MemoRipple's flat on the left with a small meta row under it — a copy action, and for
 * the answer of the current ask the model's timing. No button that can write ever comes back here.
 *
 * The conversation reads at the body size of the design system (bodyLarge, 16 sp — UI/UX review
 * 2026-09-23): the user's words and MemoRipple's answer are the same size, as they were before.
 */
@Composable
private fun TranscriptRow(message: ChatMessage, timing: AiTiming?, onCopy: (String) -> Unit) {
    val user = message.role == ChatRole.USER
    Row(modifier = Modifier.fillMaxWidth().testTag("chat_transcript_row"), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        if (user) {
            // the S20 review (2026-09-21 night): the bubble is as wide as its words — capped at 84 % of the row — and sits on the right
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.widthIn(max = maxWidth * 0.84f).wrapContentWidth(Alignment.End).semantics(mergeDescendants = true) { }.testTag("chat_message_${message.id}"),
                ) {
                    Text(message.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = ProductSpacing.sm))
                }
            }
        } else {
            Column(Modifier.fillMaxWidth(0.92f)) {
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (message.kind == ChatMessageKind.FAILURE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = ProductSpacing.xs).semantics(mergeDescendants = true) { }.testTag("chat_message_${message.id}"),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onCopy(message.text) }, modifier = Modifier.size(32.dp).testTag("chat_message_copy_${message.id}")) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "コピー", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                    if (timing != null) {
                        Text(
                            AiWording.timing(timing),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = ProductSpacing.xs).testTag("chat_ai_timing"),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The bottom bar (2026-09-22, the user's reference: ChatGPT's pill): one rounded input with ＋ (templates) on its
 * left and 送る on its right, inside it; above the pill a small 「フォルダを選択」 chip — the wall's folder the chat's
 * creates go into (「📁 仕事 ×」 when chosen; nothing chosen = the root, as before). With no model a one-line hint
 * says free text needs one and offers the setup — the bar itself, and every template, stay usable. It follows the keyboard.
 */
@Composable
private fun InputBar(
    value: String,
    enabled: Boolean,
    availability: ModelAvailability?,
    background: Color,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onPlus: () -> Unit,
    onOpenAiModels: () -> Unit,
    modifier: Modifier = Modifier,
    hintDismissed: Boolean = false,
    onDismissHint: () -> Unit = {},
    placeholder: String = "メッセージを入力",
    folderChoices: List<FolderChoice> = emptyList(),
    destination: CreateDestination? = null,
    onSelectDestination: (Long?) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    selectedMemo: MemoChoice? = null,
    onOpenMemoPicker: () -> Unit = {},
    onClearSelectedMemo: () -> Unit = {},
) {
    Surface(color = background, contentColor = MaterialTheme.colorScheme.onBackground, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = ProductSize.screenHorizontalPadding).padding(top = ProductSpacing.xs, bottom = ProductSpacing.sm)) {
            // The hint the user may close for good (UI/UX review 2026-09-23): 設定 leads to the models, × ends the line
            // for this install. Closing it loses nothing — the top bar still names the model (or 「AIモデルなし」) and a
            // free-text send with no model still answers with the setup card.
            if (availability is ModelAvailability.Unavailable && !hintDismissed) {
                val settings = availability.reason != ModelUnavailableReason.UNSUPPORTED_DEVICE
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = ProductSpacing.xs)) {
                    Text(
                        if (settings) "自由文の依頼にはLocal AIモデルが必要です。＋のテンプレートはそのまま使えます。" else "この端末ではLocal AIを利用できません。＋のテンプレートはそのまま使えます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).testTag("chat_ai_hint"),
                    )
                    if (settings) TextButton(onClick = onOpenAiModels, modifier = Modifier.testTag("chat_ai_hint_setup")) { Text("設定") }
                    IconButton(onClick = onDismissHint, modifier = Modifier.size(40.dp).testTag("chat_ai_hint_dismiss")) {
                        Icon(Icons.Outlined.Close, contentDescription = "この案内を閉じる", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
            // the folder chip: where a memo or an outline the chat makes will go — the user's choice, never the model's.
            // No × beside the name (2026-09-22): a folder is changed by choosing another, and 「フォルダなし（通常）」 clears it,
            // so nothing sits next to the name to be hit by accident.
            var folderMenu by remember { mutableStateOf(false) }
            var newFolder by remember { mutableStateOf(false) }
            // the two contexts side by side (2026-09-26): where a new document goes, and the memo the conversation is about;
            // on a narrow screen the memo chip wraps to its own line rather than squeezing either
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = ProductSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(ProductSpacing.md),
                verticalArrangement = Arrangement.spacedBy(ProductSpacing.xs),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
            Box {
                Row(
                    Modifier.clip(MaterialTheme.shapes.small).clickable(enabled = enabled) { folderMenu = true }.padding(horizontal = ProductSpacing.xs, vertical = 2.dp).semantics(mergeDescendants = true) { }.testTag("chat_folder_chip"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(destination?.name ?: "フォルダを選択", style = MaterialTheme.typography.labelLarge, color = if (destination != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = ProductSpacing.xs), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }, shape = MaterialTheme.shapes.large) {
                    DropdownMenuItem(text = { Text("フォルダなし（通常）") }, onClick = { folderMenu = false; onSelectDestination(null) }, modifier = Modifier.testTag("chat_folder_choice_none"))
                    // a folder is made here too, without going to the memo wall (2026-09-22)
                    DropdownMenuItem(
                        text = { Text("＋ 新しいフォルダ") },
                        onClick = { folderMenu = false; newFolder = true },
                        modifier = Modifier.testTag("chat_folder_new"),
                    )
                    folderChoices.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choice.name, modifier = Modifier.padding(start = (choice.depth * 16).dp)) },
                            leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                            onClick = { folderMenu = false; onSelectDestination(choice.id) },
                            modifier = Modifier.testTag("chat_folder_choice_${choice.id}"),
                        )
                    }
                }
            }
            MemoChip(selected = selectedMemo, enabled = enabled, onOpen = onOpenMemoPicker, onClear = onClearSelectedMemo)
            }
            if (newFolder) {
                var name by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { newFolder = false },
                    title = { Text("新しいフォルダ") },
                    text = {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            label = { Text("フォルダ名") },
                            modifier = Modifier.fillMaxWidth().testTag("chat_folder_new_input"),
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { newFolder = false; onCreateFolder(name) },
                            enabled = name.isNotBlank(),
                            modifier = Modifier.testTag("chat_folder_new_confirm"),
                        ) { Text("作成") }
                    },
                    dismissButton = { TextButton(onClick = { newFolder = false }) { Text("やめる") } },
                    modifier = Modifier.testTag("chat_folder_new_dialog"),
                )
            }
            // one pill: ＋ on the left inside, the text, the clear when there is text, 送る on the right inside
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = ProductSize.minimumTouchTarget).padding(horizontal = ProductSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onPlus, enabled = enabled, modifier = Modifier.size(ProductSize.minimumTouchTarget).testTag("chat_plus")) {
                        Icon(Icons.Outlined.Add, contentDescription = "テンプレート")
                    }
                    // the S20 review (2026-09-21 night): the caret and the selection follow the theme — white-ish in the dark, never the field's default black
                    CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(handleColor = MaterialTheme.colorScheme.primary, backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        enabled = enabled,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onSend() }),
                        modifier = Modifier.weight(1f).padding(horizontal = ProductSpacing.xs).testTag("chat_input"),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (value.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                                inner()
                            }
                        },
                    )
                    }
                    if (value.isNotEmpty()) {
                        IconButton(onClick = { onValueChange("") }, enabled = enabled, modifier = Modifier.size(ProductSize.minimumTouchTarget).testTag("chat_input_clear")) {
                            Icon(Icons.Outlined.Clear, contentDescription = "入力を消す")
                        }
                    }
                    IconButton(onClick = onSend, enabled = enabled && value.isNotBlank(), modifier = Modifier.size(ProductSize.minimumTouchTarget).testTag("chat_send")) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "送る")
                    }
                }
            }
        }
    }
}

/** Where the ＋ picker stands (2026-09-22): the entrance, or one folder opened inside the same sheet. */
private sealed interface PickerPage {
    data object Root : PickerPage
    data object PinnedAll : PickerPage
    data class BuiltIn(val section: ThinkTemplates.Section) : PickerPage
    data object Mine : PickerPage
    /** One of the user's folders, or 未分類 (null). */
    data class MineFolder(val folderId: String?) : PickerPage
}

/**
 * The ＋ picker as an entrance, not a list (docs/CHAT_UI_TEMPLATE_V2.md §20): the root names at most
 * three pins and three recents, then four compact folder rows — 記録 / 整理・壁打ち / 探す (the starters)
 * and 自分のテンプレート (the user's folders and 未分類) — and 「＋ テンプレートを作成」 / 「テンプレートを管理」.
 * A folder opens as a page of the same sheet with a back arrow; Back walks the pages, then closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplatePickerSheet(templates: List<MemoTemplate>, recent: List<MemoTemplate>, pinnedIds: List<String>, folders: List<TemplateFolder>, onTogglePin: (String) -> Unit, onDismiss: () -> Unit, onPick: (MemoTemplate) -> Unit, onCreate: () -> Unit, onManage: () -> Unit, openAt: ThinkTemplates.Section? = null, adding: Boolean = false, onHome: List<String> = emptyList(), onAddShortcut: (String) -> Unit = {}, onDeleteTemplate: (String) -> Unit = {}) {
    // the home launcher opens the sheet straight at a built-in folder (2026-09-23); Back still walks up to the root
    var page by remember { mutableStateOf<PickerPage>(openAt?.let { PickerPage.BuiltIn(it) } ?: PickerPage.Root) }
    val back: () -> Unit = { page = when (page) { is PickerPage.MineFolder -> PickerPage.Mine; else -> PickerPage.Root } }
    val root = TemplatePicker.root(templates, pinnedIds, recent.map { it.id }, folders)
    val pinnedSet = pinnedIds.toSet()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // the sheet opens at full height: a dozen rows scroll inside what is on screen, never below it.
    // The system Back on a folder page walks back to the root (the sheet's own back callback calls
    // onDismissRequest while the sheet is still up); a scrim tap or a swipe has hidden it first and closes.
    ModalBottomSheet(
        onDismissRequest = { if (page != PickerPage.Root && sheetState.currentValue != SheetValue.Hidden) back() else onDismiss() },
        sheetState = sheetState,
        modifier = Modifier.testTag("chat_template_picker"),
    ) {
        // The sheet is its own window: a BackHandler here must register on the *dialog's* dispatchers (found from
        // its view tree), not the activity's the composition inherits — then, while a page is open, it runs before
        // the sheet's own back-to-dismiss and walks back to the root instead.
        val view = LocalView.current
        val backOwner = remember(view) { view.findViewTreeOnBackPressedDispatcherOwner() }
        val navOwner = remember(view) { view.findViewTreeNavigationEventDispatcherOwner() }
        if (backOwner != null && navOwner != null) {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides backOwner, LocalNavigationEventDispatcherOwner provides navOwner) {
                BackHandler(enabled = page != PickerPage.Root, onBack = back)
            }
        }
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = ProductSize.screenHorizontalPadding).testTag("chat_template_list"), verticalArrangement = Arrangement.spacedBy(ProductSpacing.xs)) {
            val homeSet = onHome.toSet()
            val homeFull = onHome.size >= HomeShortcuts.MAX_SHORTCUTS
            fun rows(list: List<MemoTemplate>, tagOf: (MemoTemplate) -> String, keyPrefix: String) {
                items(list, key = { "$keyPrefix-${it.id}" }) { template ->
                    val alreadyHome = template.id in homeSet
                    TemplateRow(
                        template,
                        tag = tagOf(template),
                        pinned = template.id in pinnedSet,
                        adding = adding,
                        onHome = alreadyHome,
                        enabled = !adding || (!alreadyHome && !homeFull),
                        onClick = { if (adding) onAddShortcut(template.id) else onPick(template) },
                        onTogglePin = { onTogglePin(template.id) },
                        onDelete = { onDeleteTemplate(template.id) },
                    )
                }
            }
            when (val p = page) {
                PickerPage.Root -> {
                    item(key = "title") { Text(if (adding) "ホームに追加するテンプレート" else "テンプレート", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = ProductSpacing.xs).testTag("chat_template_picker_title")) }
                    // 「＋ 追加」 (2026-09-23): the same list, but a tap puts the template on the home instead of running it
                    if (adding) item(key = "adding_note") {
                        Text(
                            if (homeFull) "ホームには4つまで置けます。入れ替えるには、ホームのアイコンを長押しして削除してください。" else "タップするとホームのショートカットになります。",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (homeFull) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = ProductSpacing.xs).testTag("chat_template_adding_note"),
                        )
                    }
                    if (root.pinned.isNotEmpty()) {
                        item(key = "pinned_title") { SectionLabel("ピン留め", "chat_template_section_pinned") }
                        rows(root.pinned, { "chat_template_pinned_${it.id}" }, "pinned")
                        if (root.morePinned) item(key = "pinned_all") { TextButton(onClick = { page = PickerPage.PinnedAll }, modifier = Modifier.testTag("chat_template_pinned_all")) { Text("すべて表示") } }
                    }
                    if (root.recent.isNotEmpty()) {
                        item(key = "recent_title") { SectionLabel("最近使ったテンプレート", "chat_template_section_recent") }
                        rows(root.recent, { "chat_template_recent_${it.id}" }, "recent")
                    }
                    item(key = "folders_title") { SectionLabel("フォルダ", "chat_template_section_folders") }
                    listOf(
                        Triple(ThinkTemplates.Section.RECORD, "記録", "chat_template_folder_record"),
                        Triple(ThinkTemplates.Section.THINK, "整理・壁打ち", "chat_template_folder_think"),
                        Triple(ThinkTemplates.Section.SEARCH, "探す", "chat_template_folder_search"),
                    ).forEach { (section, label, tag) ->
                        val n = root.builtInCounts[section] ?: 0
                        if (n > 0) item(key = tag) { FolderRow(label, n, tag = tag, onClick = { page = PickerPage.BuiltIn(section) }) }
                    }
                    item(key = "folder_mine") { FolderRow("自分のテンプレート", root.mineCount, tag = "chat_template_folder_mine", onClick = { page = PickerPage.Mine }) }
                    item(key = "actions") {
                        Column(Modifier.fillMaxWidth().padding(top = ProductSpacing.sm, bottom = ProductSpacing.xl)) {
                            OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth().testTag("chat_template_create")) { Text("＋ テンプレートを作成") }
                            TextButton(onClick = onManage, modifier = Modifier.fillMaxWidth().testTag("chat_template_manage")) { Text("テンプレートを管理") }
                        }
                    }
                }
                PickerPage.PinnedAll -> {
                    item(key = "head") { PageHead("ピン留め", back) }
                    rows(PinnedTemplates.resolve(pinnedIds, templates), { "chat_template_pinned_${it.id}" }, "pinned")
                    item(key = "bottom") { Spacer(Modifier.height(ProductSpacing.xl)) }
                }
                is PickerPage.BuiltIn -> {
                    item(key = "head") { PageHead(when (p.section) { ThinkTemplates.Section.RECORD -> "記録"; ThinkTemplates.Section.THINK -> "整理・壁打ち"; ThinkTemplates.Section.SEARCH -> "探す" }, back) }
                    rows(TemplatePicker.builtIn(templates, p.section), { "chat_template_item_${it.id}" }, "item")
                    item(key = "bottom") { Spacer(Modifier.height(ProductSpacing.xl)) }
                }
                PickerPage.Mine -> {
                    item(key = "head") { PageHead("自分のテンプレート", back) }
                    val mine = TemplatePicker.mine(templates, folders)
                    if (folders.isEmpty()) item(key = "no_folders") { Text("まだフォルダはありません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = ProductSpacing.xs).testTag("chat_template_no_folders")) }
                    items(mine, key = { "mine-${it.folder?.id ?: "none"}" }) { row ->
                        FolderRow(row.folder?.name ?: "未分類", row.count, tag = if (row.folder == null) "chat_template_folder_mine_none" else "chat_template_folder_mine_${row.folder.id}", onClick = { page = PickerPage.MineFolder(row.folder?.id) })
                    }
                    item(key = "bottom") { Spacer(Modifier.height(ProductSpacing.xl)) }
                }
                is PickerPage.MineFolder -> {
                    item(key = "head") { PageHead(folders.firstOrNull { it.id == p.folderId }?.name ?: "未分類", back) }
                    val inFolder = TemplatePicker.inFolder(templates, folders, p.folderId)
                    if (inFolder.isEmpty()) item(key = "none") { Text("まだテンプレートがありません。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("chat_template_picker_empty")) }
                    rows(inFolder, { "chat_template_item_${it.id}" }, "item")
                    item(key = "bottom") { Spacer(Modifier.height(ProductSpacing.xl)) }
                }
            }
        }
    }
}

/** A page's head inside the sheet: the back arrow and the folder's name. */
@Composable
private fun PageHead(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = ProductSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("chat_template_folder_back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る") }
        Text(title, style = MaterialTheme.typography.titleSmall)
    }
}

/** A compact folder row: the icon, the name, how many templates, a chevron — never a card. */
@Composable
private fun FolderRow(name: String, count: Int, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = ProductSpacing.sm).semantics(mergeDescendants = true) { }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = ProductSpacing.sm), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = ProductSpacing.xs))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionLabel(text: String, tag: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = ProductSpacing.sm, bottom = ProductSpacing.xs).testTag(tag))
}

/**
 * One template as the picker shows it: the name, its description, what it does — and 「スターター」 on a built-in one.
 * A long press opens the row's menu on the right (the drawer's convention): 「ピン留め」 / 「ピン留めを外す」 (Review Batch 2).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TemplateRow(
    template: MemoTemplate,
    tag: String,
    pinned: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    /** 「＋ 追加」 mode (2026-09-23): a tap puts the template on the home, and the row says so. */
    adding: Boolean = false,
    onHome: Boolean = false,
    enabled: Boolean = true,
    onDelete: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val starter = StarterTemplates.isStarter(template.id)
    Box {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().combinedClickable(enabled = enabled, onClick = onClick, onLongClick = { menu = true }, onLongClickLabel = "メニュー").semantics(mergeDescendants = true) { }.testTag(tag)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = ProductSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(template.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                if (template.description.isNotBlank()) Text(template.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = ProductSpacing.sm)) {
                // in the adding mode the row says what a tap does — never 作成する / 探す, which would be a lie there
                if (adding) {
                    Text(
                        if (onHome) "ホームにあります" else "ホームに追加",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (onHome) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag(if (onHome) "chat_template_on_home_${template.id}" else "chat_template_add_home_${template.id}"),
                    )
                } else {
                    Text(template.doingLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (starter) Text("スターター", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("chat_template_starter_label"))
            }
        }
    }
    Box(Modifier.align(Alignment.TopEnd)) {
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.large) {
            DropdownMenuItem(
                text = { Text(if (pinned) "ピン留めを外す" else "ピン留め") },
                leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                onClick = { menu = false; onTogglePin() },
                modifier = Modifier.testTag(if (pinned) "chat_template_unpin_${template.id}" else "chat_template_pin_${template.id}"),
            )
            // a starter is code: it can be copied and edited, never deleted (2026-09-23)
            if (!starter) {
                DropdownMenuItem(
                    text = { Text("削除", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; confirmDelete = true },
                    modifier = Modifier.testTag("chat_template_delete_${template.id}"),
                )
            }
        }
    }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("「${template.name}」を削除しますか？") },
            text = { Text("このテンプレートだけを削除します。メモや日記は削除されません。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }, modifier = Modifier.testTag("chat_template_delete_confirm")) { Text("削除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } },
            modifier = Modifier.testTag("chat_template_delete_dialog"),
        )
    }
}

/** What the template does, as the picker labels it — the user's words, never the enum's; a Think template says 整理する. */
private val MemoTemplate.doingLabel: String
    get() = when {
        flow == TemplateFlow.THINK -> "整理する"
        action == TemplateAction.CREATE -> "作成する"
        action == TemplateAction.SEARCH -> "探す"
        else -> "追記する"
    }

/**
 * The chips under an open question (§16): a date as 今日 / 昨日 / 日付を選ぶ, はい / いいえ, one of the
 * choices, スキップ for an optional question, やめる always. A text question has only the last two —
 * the message input is its answer. The candidates for a target name are rows above the chips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnswerOptions(session: TemplateSessionState, today: LocalDate, onOption: (String) -> Unit, onDate: (String) -> Unit, onSkip: () -> Unit, onCancel: () -> Unit) {
    var pick by remember { mutableStateOf(false) }
    val field = (session.step as? TemplateScript.Step.Ask)?.field
    FlowRow(modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.xs).testTag("chat_answer_options"), horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs), verticalArrangement = Arrangement.spacedBy(ProductSpacing.xs)) {
        when (field?.type) {
            TemplateFieldType.DATE -> {
                FilterChip(selected = false, onClick = { onOption("TODAY") }, label = { Text("今日") }, modifier = Modifier.testTag("chat_answer_option_TODAY"))
                FilterChip(selected = false, onClick = { onOption("YESTERDAY") }, label = { Text("昨日") }, modifier = Modifier.testTag("chat_answer_option_YESTERDAY"))
                FilterChip(selected = false, onClick = { pick = true }, label = { Text("日付を選ぶ") }, modifier = Modifier.testTag("chat_answer_pick_date"))
            }
            TemplateFieldType.BOOLEAN -> {
                FilterChip(selected = false, onClick = { onOption("true") }, label = { Text("はい") }, modifier = Modifier.testTag("chat_answer_option_true"))
                FilterChip(selected = false, onClick = { onOption("false") }, label = { Text("いいえ") }, modifier = Modifier.testTag("chat_answer_option_false"))
            }
            TemplateFieldType.CHOICE -> field.choices.forEach { choice ->
                FilterChip(selected = false, onClick = { onOption(choice) }, label = { Text(choice) }, modifier = Modifier.testTag("chat_answer_option_$choice"))
            }
            else -> Unit
        }
        if (field != null && !field.required && field.type != TemplateFieldType.BOOLEAN) {
            FilterChip(selected = false, onClick = onSkip, label = { Text("スキップ") }, modifier = Modifier.testTag("chat_answer_skip"))
        }
        // the S20 review (2026-09-21 night): やめる belongs to the start — the first question (or the target question); later, Back still cancels
        val first = session.step == TemplateScript.Step.AskTarget || ((session.step as? TemplateScript.Step.Ask)?.index == 0 && !session.editing)
        if (first) FilterChip(selected = false, onClick = onCancel, label = { Text("やめる") }, modifier = Modifier.testTag("chat_answer_cancel"))
    }
    if (pick) {
        val state = rememberDatePickerState(initialSelectedDateMillis = today.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pick = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis -> onDate(Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate().toString()) }
                    pick = false
                }, modifier = Modifier.testTag("chat_template_date_confirm")) { Text("決定") }
            },
            dismissButton = { TextButton(onClick = { pick = false }) { Text("キャンセル") } },
            modifier = Modifier.testTag("chat_template_date_dialog"),
        ) { DatePicker(state = state) }
    }
}

/**
 * 「修正」 (2026-09-24, the user's review): the template's own fields, **one pressable row each** —
 * the name, what it says now underneath, and a chevron at the end, so what can be tapped is the
 * row and not a word coloured like a link. A tap asks that one question again, through the same
 * one-question-at-a-time script; nothing is typed here and nothing is written. The rows come from
 * the template's field metadata, so every template reads the same way.
 */
@Composable
private fun EditAnswers(fields: List<TemplateField>, answers: Map<String, String>, onEdit: (String) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm).testTag("chat_edit_list")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = ProductSpacing.sm)) {
            Text("修正する項目を選んでください", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = ProductSpacing.xs))
            fields.forEach { f ->
                val shown = answerText(f, answers[f.key])
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(onClickLabel = "${f.label}を修正", role = Role.Button) { onEdit(f.key) }
                        .heightIn(min = ProductSize.minimumTouchTarget)
                        .padding(vertical = ProductSpacing.xs)
                        .semantics(mergeDescendants = true) { contentDescription = "${f.label}　$shown" }
                        .testTag("chat_edit_field_${f.key}"),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(f.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            shown,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = ProductSpacing.sm),
                    )
                }
            }
        }
    }
}

/** What a field says now, in the user's words: an unanswered or skipped one reads 「未入力」. */
private fun answerText(field: TemplateField, answer: String?): String =
    if (answer.isNullOrBlank()) "未入力" else TemplateScript.spokenAnswer(field, answer)

// --- the current operation's cards, under the transcript (ephemeral: never rebuilt from history) ---

private fun LazyListScope.aiResult(
    ai: AiPanelState,
    executing: Boolean,
    onOpen: (DocumentRef) -> Unit,
    onDismissAi: () -> Unit,
    onChooseCandidate: (DocumentSummary) -> Unit,
    onConfirmWrite: () -> Unit,
    onOpenAiModels: () -> Unit,
    onRetry: () -> Unit,
    onOpenPicker: () -> Unit,
    onUseSuggestion: () -> Unit,
    session: TemplateSessionState? = null,
    onEditAnswer: (String) -> Unit = {},
    editOpen: Boolean = false,
    onToggleEdit: () -> Unit = {},
    /** A memo asked for in one question can be asked again (2026-09-24). */
    canEditAnswer: Boolean = false,
    onEditAsked: () -> Unit = {},
    onSaveThink: () -> Unit = {},
    onFinishThink: () -> Unit = {},
) {
    // Template first-class (§14): a template whose name the sentence contained — offered above the result, never run by itself
    ai.suggestion?.let { template ->
        item(key = "suggestion") {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = ProductSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text("「${template.name}」テンプレートを使えます", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).testTag("chat_template_suggestion"))
                    TextButton(onClick = onUseSuggestion, modifier = Modifier.testTag("chat_template_suggestion_use")) { Text("使う") }
                }
            }
        }
    }
    when (val result = ai.result) {
        null -> Unit
        is AiInteractionResult.TemplateForm -> Unit   // shown as the form, never as a card
        is AiInteractionResult.Written -> item(key = "ai_written") {
            WrittenCard(result.outcome, result.preview, onDismiss = onDismissAi, onReconfirm = onRetry)
        }
        is AiInteractionResult.SearchResults -> {
            item(key = "ai_count") {
                ResultCount(title = "検索結果", count = result.results.size, rowTag = "chat_ai_search_results", countTag = null)
            }
            if (result.results.isEmpty()) {
                item(key = "ai_none") {
                    ProductEmptyState(
                        title = "この条件の記録はありません",
                        description = "言葉や日付を変えて、もう一度頼んでみてください。",
                        modifier = Modifier.fillMaxWidth().testTag("chat_ai_empty_none"),
                    )
                }
            }
            items(result.results, key = { "ai-${it.ref.kind.name}-${it.ref.id}" }) { summary ->
                ResultRow(summary = summary, onClick = { onOpen(summary.ref) })
            }
        }
        is AiInteractionResult.Open -> item(key = "ai_opened") {
            AiCard(tag = "chat_ai_opened", title = "開きました", body = "${result.target.ref.kind.chatLabel}「${result.target.title}」", onDismiss = onDismissAi)
        }
        is AiInteractionResult.Ambiguous -> {
            item(key = "ai_candidates") {
                AiCard(tag = "chat_ai_candidates", title = "どれを開きますか？", body = "同じ名前の記録が${result.candidates.size}件あります。ひとつ選んでください。", onDismiss = onDismissAi)
            }
            items(result.candidates, key = { "cand-${it.ref.kind.name}-${it.ref.id}" }) { summary ->
                ResultRow(summary = summary, onClick = { onChooseCandidate(summary) }, tagPrefix = "chat_ai_candidate")
            }
        }
        // Think (docs/THINK_TEMPLATES.md): the words are the transcript line above; this is only what can be done with them
        is AiInteractionResult.ThinkResult -> item(key = "ai_think") {
            ThinkResultCard(onEdit = onToggleEdit, onSave = onSaveThink, onFinish = onFinishThink)
        }
        is AiInteractionResult.WritePreview -> item(key = "ai_preview") {
            WritePreviewCard(
                result.preview,
                executing = executing,
                onCancel = onDismissAi,
                onConfirm = onConfirmWrite,
                // a template with fields is corrected field by field; a memo asked for in one question
                // is corrected by being asked again (2026-09-24)
                canEdit = (session != null && session.template.fields.isNotEmpty()) || canEditAnswer,
                onEdit = if (session != null && session.template.fields.isNotEmpty()) onToggleEdit else onEditAsked,
                answers = session?.answers,
            )
        }
        is AiInteractionResult.NeedsInformation -> item(key = "ai_needs") {
            AiCard(tag = "chat_ai_needs_information", title = AiWording.needsInformationTitle(result), body = "対象や内容を含めて、もう一度入力してください。", onDismiss = onDismissAi)
        }
        is AiInteractionResult.NotFound -> item(key = "ai_not_found") {
            AiCard(tag = "chat_ai_not_found", title = AiWording.notFoundTitle(result), body = "名前を確かめて、もう一度頼んでみてください。", onDismiss = onDismissAi)
        }
        is AiInteractionResult.Invalid -> item(key = "ai_invalid") {
            AiCard(tag = "chat_ai_invalid", title = "この依頼は実行できません", body = result.reasons.joinToString("\n") { it.explanation }, onDismiss = onDismissAi)
        }
        AiInteractionResult.Unknown -> item(key = "ai_unknown") {
            AiCard(tag = "chat_ai_unknown", title = "この依頼はまだ扱えません", body = "探す・開く・追記・新規作成・テンプレートからの作成に対応しています。", onDismiss = onDismissAi)
        }
        AiInteractionResult.ThermalBlocked -> item(key = "ai_thermal") {
            AiCard(tag = "chat_ai_thermal_blocked", title = AiWording.THERMAL_TITLE, body = "冷めてからもう一度お試しください。テンプレートはそのまま使えます。", onDismiss = onDismissAi)
        }
        is AiInteractionResult.ModelUnavailable -> item(key = "ai_unavailable") {
            AiUnavailableCard(reason = result.reason, onDismiss = onDismissAi, onOpenAiModels = onOpenAiModels, onOpenPicker = onOpenPicker)
        }
        is AiInteractionResult.RuntimeError -> item(key = "ai_error") {
            // Phase 7: one tap retries — the same words, a new load or generation; nothing retries by itself
            AiCard(
                tag = "chat_ai_runtime_error", title = AiWording.runtimeErrorTitle(result.stage), body = "現在のデータは変更されていません。テンプレートはそのまま使えます。", onDismiss = onDismissAi,
                actionLabel = "再試行", actionTag = "chat_ai_retry", onAction = onRetry,
            )
        }
    }
    if (editOpen && session != null && (ai.result is AiInteractionResult.WritePreview || ai.result is AiInteractionResult.ThinkResult)) {
        item(key = "edit") { EditAnswers(session.template.fields, session.answers, onEdit = onEditAnswer) }
    }
}

/**
 * The end of a Think conversation (docs/THINK_TEMPLATES.md): the result itself is the assistant's line above;
 * this card only offers what to do with it — 「メモとして保存」 (the CREATE preview and the one confirm) or
 * 「終了」 (the line stays, nothing is written). No summary model, no ticket, no write from here.
 */
@Composable
private fun ThinkResultCard(onEdit: () -> Unit, onSave: () -> Unit, onFinish: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.xs).testTag("chat_think_result")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = ProductSpacing.sm)) {
            Text("この内容をメモに残せます。残さなくても、この会話には残ります。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(Modifier.fillMaxWidth().padding(top = ProductSpacing.xs), horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs)) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.testTag("chat_think_edit")) { Text("修正") }
                Button(onClick = onSave, modifier = Modifier.testTag("chat_think_save")) { Text("メモとして保存") }
                TextButton(onClick = onFinish, modifier = Modifier.testTag("chat_think_done")) { Text("終了") }
            }
        }
    }
}

/**
 * Why free text cannot be asked right now, the one next step, and what still works. The result of a
 * free-text send that found no model; the bar's hint says the same in one line. A CPU nothing can fix gets no
 * settings shortcut.
 */
@Composable
private fun AiUnavailableCard(reason: ModelUnavailableReason, onDismiss: (() -> Unit)?, onOpenAiModels: () -> Unit, onOpenPicker: () -> Unit) {
    val (title, body) = AiWording.unavailable(reason)
    val settings = reason != ModelUnavailableReason.UNSUPPORTED_DEVICE
    AiCard(
        tag = "chat_ai_model_unavailable", title = title, body = body, onDismiss = onDismiss,
        actionLabel = if (settings) "AIモデルを設定" else null, actionTag = "chat_ai_open_settings", onAction = if (settings) onOpenAiModels else null,
        secondaryLabel = "テンプレートを使う", secondaryTag = "chat_ai_use_templates", onSecondary = onOpenPicker,
    )
}

/** A line the model's progress is read from: a polite live region, so a screen reader hears the state change. */
@Composable
private fun StatusLine(text: String, tag: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().padding(vertical = ProductSpacing.md).semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag),
    )
}

/** One explanation, with a way to put it away. Its text is one node for a reader and a test. */
@Composable
private fun AiCard(tag: String, title: String, body: String, onDismiss: (() -> Unit)?, actionLabel: String? = null, actionTag: String? = null, onAction: (() -> Unit)? = null, secondaryLabel: String? = null, secondaryTag: String? = null, onSecondary: (() -> Unit)? = null) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = ProductSpacing.md)) {
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(tag)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(ProductSpacing.xs))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(ProductSpacing.sm))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ProductSpacing.sm), verticalArrangement = Arrangement.spacedBy(ProductSpacing.xs)) {
                if (actionLabel != null && onAction != null) {
                    Button(onClick = onAction, modifier = Modifier.testTag(actionTag ?: "chat_ai_action")) { Text(actionLabel) }
                }
                if (secondaryLabel != null && onSecondary != null) {
                    OutlinedButton(onClick = onSecondary, modifier = Modifier.testTag(secondaryTag ?: "chat_ai_secondary")) { Text(secondaryLabel) }
                }
                if (onDismiss != null) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.testTag("chat_ai_dismiss")) { Text("閉じる") }
                }
            }
        }
    }
}

/**
 * What a confirmed write came to — one card, in the user's words; a success has already opened the
 * document. A conflict offers a new preview (Phase 7: the same words asked again, so the user sees
 * the document as it is now); the old ticket is never run again.
 */
@Composable
private fun WrittenCard(outcome: WriteOutcome, preview: CommandPreview, onDismiss: () -> Unit, onReconfirm: () -> Unit) {
    val (tag, title, body) = AiWording.written(outcome, preview)
    if (outcome == WriteOutcome.Conflict) {
        AiCard(tag = tag, title = title, body = body, onDismiss = onDismiss, actionLabel = "もう一度確認する", actionTag = "chat_ai_reconfirm", onAction = onReconfirm)
    } else {
        AiCard(tag = tag, title = title, body = body, onDismiss = onDismiss)
    }
}

/**
 * What a write would do, and the one control that lets it happen: キャンセル, or the confirm button
 * whose only effect is the view model's `confirmWrite()` (docs/AI_CONFIRMED_WRITE.md). While the
 * write runs, both are disabled and the card says so; a second tap lands on nothing.
 */
@Composable
private fun WritePreviewCard(
    preview: CommandPreview,
    executing: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    canEdit: Boolean = false,
    onEdit: () -> Unit = {},
    /** The answers behind a template preview, when they are still at hand — then the card reads by field. */
    answers: Map<String, String>? = null,
) {
    val (tag, title, confirmLabel) = when (preview) {
        is CommandPreview.Create -> Triple("chat_ai_preview_create", "新規作成の確認", "作成")
        is CommandPreview.Append -> Triple("chat_ai_preview_append", "追記の確認", "追記")
        is CommandPreview.Template -> Triple("chat_ai_preview_template", "テンプレートから作成の確認", "作成")
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = ProductSpacing.md)) {
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(tag)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(ProductSpacing.sm))
                when (preview) {
                    is CommandPreview.Create -> {
                        PreviewField("種類", preview.kind.chatLabel + (preview.journalDate?.let { "（" + formatDay(it) + "）" } ?: ""))
                        PreviewField("内容", preview.initialText?.takeIf { it.isNotBlank() } ?: "（空のまま作成）")
                        preview.folderName?.let { PreviewField("保存先", it, tag = "chat_ai_preview_folder") }
                    }
                    is CommandPreview.Append -> {
                        PreviewField("追記先", "${preview.target.ref.kind.chatLabel}「${preview.target.title}」")
                        PreviewField("現在の内容", preview.currentBody.ifBlank { "（本文なし）" }, maxLines = 6)
                        PreviewField("追加する内容", preview.text)
                        Text(
                            formatVersion(preview.expectedVersion.token) + " 時点の内容に対する追記です",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = ProductSpacing.xs).testTag("chat_ai_preview_version"),
                        )
                    }
                    is CommandPreview.Template -> {
                        PreviewField("テンプレート", preview.template.name)
                        PreviewField("種類", preview.kind.chatLabel + (preview.journalDate?.let { "（" + formatDay(it) + "）" } ?: ""))
                        // Read as the template's own fields when the answers are still at hand
                        // (2026-09-24, the user's review): a person checks 良かったこと and 明日やること,
                        // not the `## ` the file will hold. The body that will be written is unchanged —
                        // only how it is shown. With no answers to read from (a template run straight
                        // from its values) the rendered body is shown as before.
                        val fields = preview.template.fields
                        if (answers != null && fields.isNotEmpty()) {
                            Column(Modifier.fillMaxWidth().testTag("chat_ai_preview_fields")) {
                                fields.forEach { f -> PreviewField(f.label, answerText(f, answers[f.key]), tag = "chat_ai_preview_field_${f.key}") }
                            }
                        } else {
                            PreviewField("内容", preview.renderedBody, maxLines = 12)
                        }
                        preview.folderName?.let { PreviewField("保存先", it, tag = "chat_ai_preview_folder") }
                    }
                }
            }
            Spacer(Modifier.height(ProductSpacing.sm))
            if (executing) {
                StatusLine("書き込んでいます…", "chat_ai_executing")
            } else {
                Text(
                    "「$confirmLabel」を押すまで書き込みません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("chat_ai_preview_note"),
                )
            }
            Spacer(Modifier.height(ProductSpacing.sm))
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ProductSpacing.sm), verticalArrangement = Arrangement.spacedBy(ProductSpacing.xs)) {
                if (canEdit) {
                    OutlinedButton(onClick = onEdit, enabled = !executing, modifier = Modifier.testTag("chat_ai_preview_edit")) { Text("修正") }
                }
                OutlinedButton(onClick = onCancel, enabled = !executing, modifier = Modifier.testTag("chat_ai_preview_cancel")) { Text("キャンセル") }
                Button(onClick = onConfirm, enabled = !executing, modifier = Modifier.testTag("chat_ai_preview_confirm")) { Text(confirmLabel) }
            }
        }
    }
}

@Composable
private fun PreviewField(label: String, value: String, maxLines: Int = 4, tag: String? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = ProductSpacing.sm).then(if (tag != null) Modifier.testTag(tag) else Modifier)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ResultCount(title: String, count: Int, rowTag: String?, countTag: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.md, bottom = ProductSpacing.xs).let { if (rowTag != null) it.testTag(rowTag) else it },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(
            "${count}件",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (countTag != null) Modifier.testTag(countTag) else Modifier,
        )
    }
}

@Composable
private fun ResultRow(summary: DocumentSummary, onClick: () -> Unit, tagPrefix: String = "chat_result") {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().semantics { selected = false }
            .testTag("${tagPrefix}_${summary.ref.kind.name.lowercase()}_${summary.ref.id}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = ProductSpacing.md).testTag(tagPrefix),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                summary.ref.kind.chatLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = ProductSpacing.md),
            )
            Text(
                summary.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatUpdated(summary.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = ProductSpacing.sm),
            )
        }
    }
}

private fun formatUpdated(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("M/d", Locale.JAPAN)) + " 更新"

private fun formatVersion(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M/d HH:mm", Locale.JAPAN))

private fun formatDay(day: LocalDate): String = day.format(DateTimeFormatter.ofPattern("M月d日", Locale.JAPAN))

/**
 * 「メモを選択」 (docs/CHAT_MEMO_CONTEXT.md, 2026-09-26): beside the folder, the memo this conversation is
 * about. Unselected it opens the sheet; selected it names the memo on one line (a tap chooses
 * another) and its × lets the selection go — nothing else. It is a context, never a write: a write
 * still stops at its preview and waits for the confirmation.
 */
@Composable
private fun MemoChip(selected: MemoChoice?, enabled: Boolean, onOpen: () -> Unit, onClear: () -> Unit) {
    if (selected == null) {
        Row(
            Modifier.clip(MaterialTheme.shapes.small).clickable(enabled = enabled, onClick = onOpen).padding(horizontal = ProductSpacing.xs, vertical = 2.dp).semantics(mergeDescendants = true) { }.testTag("chat_memo_chip"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text("メモを選択", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = ProductSpacing.xs), maxLines = 1)
        }
    } else {
        Row(Modifier.widthIn(max = 280.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f, fill = false).clip(MaterialTheme.shapes.small).clickable(enabled = enabled, onClick = onOpen).padding(start = ProductSpacing.xs, top = 2.dp, bottom = 2.dp).semantics(mergeDescendants = true) { }.testTag("chat_memo_selected"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(
                    selected.title.ifBlank { DocumentTitles.UNTITLED_MEMO },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = ProductSpacing.xs),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onClear, enabled = enabled, modifier = Modifier.size(32.dp).testTag("chat_memo_clear")) {
                Icon(Icons.Outlined.Close, contentDescription = "メモの選択を解除", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * The sheet 「メモを選択」 opens: active memos only (no outline, journal, note, archive or trash), newest
 * first, at most 200 — a search over title and body, and the wall's folder to narrow to. A row shows
 * the title, one line of the body and when it was last written; a tap chooses it and writes nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoPickerSheet(
    state: MemoPickerState,
    folders: List<FolderChoice>,
    onQuery: (String) -> Unit,
    onFolder: (Long?) -> Unit,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("chat_memo_picker")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = ProductSize.screenHorizontalPadding).padding(bottom = ProductSpacing.lg)) {
            Text("メモを選択", style = MaterialTheme.typography.titleMedium)
            Text(
                "選んだメモは、この会話で「このメモを開いて」「『…』を追記して」の対象になります。追記は確認してから行います。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = ProductSpacing.xs),
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                singleLine = true,
                placeholder = { Text("タイトルや本文で探す") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm).testTag("chat_memo_picker_search"),
            )
            if (folders.isNotEmpty()) {
                var folderMenu by remember { mutableStateOf(false) }
                val chosen = folders.firstOrNull { it.id == state.folderId }
                Box(Modifier.padding(top = ProductSpacing.xs)) {
                    TextButton(onClick = { folderMenu = true }, modifier = Modifier.testTag("chat_memo_picker_folder")) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(chosen?.name ?: "すべてのフォルダ", modifier = Modifier.padding(start = ProductSpacing.xs), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }, shape = MaterialTheme.shapes.large) {
                        DropdownMenuItem(text = { Text("すべてのフォルダ") }, onClick = { folderMenu = false; onFolder(null) }, modifier = Modifier.testTag("chat_memo_picker_folder_all"))
                        folders.forEach { folder ->
                            DropdownMenuItem(
                                text = { Text(folder.name, modifier = Modifier.padding(start = (folder.depth * 16).dp)) },
                                leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                                onClick = { folderMenu = false; onFolder(folder.id) },
                                modifier = Modifier.testTag("chat_memo_picker_folder_${folder.id}"),
                            )
                        }
                    }
                }
            }
            if (state.choices.isEmpty()) {
                Text(
                    if (state.query.isBlank() && state.folderId == null) "メモがありません" else "見つかりませんでした",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = ProductSpacing.lg).testTag("chat_memo_picker_empty"),
                )
            } else {
                // a list found for another search or folder starts at its top — kept in place, the newer memos would sit
                // above the view as if the folder had not changed (the S20, 2026-09-27)
                val listState = rememberLazyListState()
                LaunchedEffect(state.shownFor) { listState.scrollToItem(0) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(top = ProductSpacing.xs), state = listState) {
                    items(state.choices, key = { it.ref.id }) { choice ->
                        Column(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onPick(choice.ref.id) }
                                .padding(horizontal = ProductSpacing.xs, vertical = 10.dp)
                                .semantics(mergeDescendants = true) { }.testTag("chat_memo_choice_${choice.ref.id}"),
                        ) {
                            Text(choice.title.ifBlank { DocumentTitles.UNTITLED_MEMO }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (choice.preview.isNotEmpty()) {
                                Text(choice.preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(formatVersion(choice.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

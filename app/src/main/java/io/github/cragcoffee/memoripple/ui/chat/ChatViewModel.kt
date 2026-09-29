package io.github.cragcoffee.memoripple.ui.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult
import io.github.cragcoffee.memoripple.domain.ai.AiIntent
import io.github.cragcoffee.memoripple.domain.ai.AiOrchestrator
import io.github.cragcoffee.memoripple.domain.ai.AiResultRef
import io.github.cragcoffee.memoripple.domain.ai.IntentProposal
import io.github.cragcoffee.memoripple.domain.ai.decision.ClarificationChoice
import io.github.cragcoffee.memoripple.domain.ai.decision.ClarificationSlot
import io.github.cragcoffee.memoripple.domain.ai.decision.DecisionOutcome
import io.github.cragcoffee.memoripple.domain.ai.decision.DecisionResult
import io.github.cragcoffee.memoripple.domain.ai.decision.HomeAsk
import io.github.cragcoffee.memoripple.domain.ai.decision.HomeAsks
import io.github.cragcoffee.memoripple.domain.ai.AiProgress
import io.github.cragcoffee.memoripple.domain.ai.AiResultContext
import io.github.cragcoffee.memoripple.domain.ai.AiTiming
import io.github.cragcoffee.memoripple.domain.ai.ModelAvailability
import io.github.cragcoffee.memoripple.domain.ai.ModelUnavailableReason
import io.github.cragcoffee.memoripple.domain.ai.WriteOutcome
import io.github.cragcoffee.memoripple.domain.ai.conversation.ActiveContextBudget
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatHintStore
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMemoSelectionStore
import io.github.cragcoffee.memoripple.domain.ai.conversation.DocumentMemoChoices
import io.github.cragcoffee.memoripple.domain.ai.conversation.MemoChoice
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatConversation
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessage
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessageKind
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationReferents
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationStore
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationTitle
import io.github.cragcoffee.memoripple.domain.ai.conversation.LastConversationStore
import io.github.cragcoffee.memoripple.domain.ai.conversation.PinnedConversationStore
import io.github.cragcoffee.memoripple.domain.ai.models.InstallState
import io.github.cragcoffee.memoripple.domain.ai.models.ModelManager
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationExport
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationTemplateDraft
import io.github.cragcoffee.memoripple.domain.documents.CreateDestination
import io.github.cragcoffee.memoripple.domain.folders.ChatDestinationStore
import io.github.cragcoffee.memoripple.domain.folders.DocumentFolderChoices
import io.github.cragcoffee.memoripple.domain.folders.FolderChoice
import io.github.cragcoffee.memoripple.domain.folders.FolderName
import io.github.cragcoffee.memoripple.domain.folders.FolderChoices
import io.github.cragcoffee.memoripple.domain.memos.HomeShortcut
import io.github.cragcoffee.memoripple.domain.memos.HomeShortcutEntry
import io.github.cragcoffee.memoripple.domain.memos.HomeShortcutStore
import io.github.cragcoffee.memoripple.domain.memos.HomeShortcuts
import io.github.cragcoffee.memoripple.domain.memos.TemplateRemover
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.PinnedTemplateStore
import io.github.cragcoffee.memoripple.domain.memos.TemplateFolder
import io.github.cragcoffee.memoripple.domain.memos.TemplateFolderStore
import io.github.cragcoffee.memoripple.domain.memos.RecentTemplateStore
import io.github.cragcoffee.memoripple.domain.memos.RecentTemplates
import io.github.cragcoffee.memoripple.domain.memos.StarterTemplates
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.memos.TemplateAction
import io.github.cragcoffee.memoripple.domain.memos.TemplateFieldType
import io.github.cragcoffee.memoripple.domain.memos.TemplateFlow
import io.github.cragcoffee.memoripple.domain.memos.ThinkTemplates
import io.github.cragcoffee.memoripple.domain.memos.TemplateScript
import io.github.cragcoffee.memoripple.domain.memos.TemplateSearchSpec
import io.github.cragcoffee.memoripple.domain.memos.TemplateTargetSpec
import io.github.cragcoffee.memoripple.domain.memos.TemplateValues
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a tap in the ＋ picker means: run the template, or put it on the home (2026-09-23). */
enum class PickerMode { RUN, ADD_SHORTCUT }

/** Where the AI mode is: idle, the model loading, the model thinking, a result on screen, a confirmed write running. */
enum class AiPhase { IDLE, LOADING, GENERATING, DONE, EXECUTING }

/**
 * The AI mode's state — **ephemeral** (docs/AI_CHAT_PREVIEW.md): what the model answered, what
 * was resolved and what is previewed live here for one request and never in the saved state,
 * a preferences store or the database. [pendingOpen] is a one-shot: a unique OPEN the screen has not yet handed to
 * the navigator.
 */
data class AiPanelState(
    val phase: AiPhase = AiPhase.IDLE,
    val askedText: String = "",
    val result: AiInteractionResult? = null,
    val pendingOpen: DocumentRef? = null,
    /** Phase 7: whether a model could answer — null until checked. The input stays either way (Template v2); free text needs a model. In memory only. */
    val availability: ModelAvailability? = null,
    /** UI review 2026-09-21: how long the model took for the current ask — numbers only, shown under the answer; gone with the next ask. */
    val timing: AiTiming? = null,
    /** Template first-class (§14): a template whose name the asked sentence contains — offered, never run; the user's tap opens its form. */
    val suggestion: MemoTemplate? = null,
    /** The conversation the result on screen was asked in (2026-09-26): a preview whose conversation is gone is never confirmed. */
    val conversationId: Long? = null,
) {
    /** A preview or a candidate list is a step the user is in; Back closes it before anything else — never mid-write. */
    val isDismissable: Boolean get() = phase != AiPhase.EXECUTING && (result is AiInteractionResult.WritePreview || result is AiInteractionResult.Ambiguous)
}

/**
 * A template being run as a conversation (§16) — **ephemeral**: which template, what has been answered
 * (a skipped optional field is ""), the target when one was asked, the step the script is at, the
 * candidates shown for a target name, and whether one answer is being changed after the preview.
 * Never saved: a process death drops it, the transcript stays, nothing is written.
 */
data class TemplateSessionState(
    val template: MemoTemplate,
    val step: TemplateScript.Step,
    val answers: Map<String, String> = emptyMap(),
    val target: String? = null,
    val targetCandidates: List<DocumentSummary> = emptyList(),
    val editing: Boolean = false,
) {
    /** A question is open: the input answers it, the chips answer it. */
    val asking: Boolean get() = step !is TemplateScript.Step.Ready
}

/**
 * One open clarification of the DecisionEngine (Phase 3): which conversation asked, the slot the
 * next words fill, the draft they complete, and the fixed choices (empty for a body question).
 * Memory only — no execution authority, no persistence, no resume.
 */
data class PendingClarification(
    val conversationId: Long?,
    val slot: ClarificationSlot,
    val draft: IntentProposal,
    val anchorTarget: Boolean,
    val choices: List<ClarificationChoice>,
    /** The launcher question this came from, when it was one, so 「修正」 can ask it again (2026-09-24). */
    val asked: DecisionResult.NeedsClarification? = null,
)

/**
 * 「メモを選択」's sheet (docs/CHAT_MEMO_CONTEXT.md): what is typed, the wall's folder it narrows to
 * (null = every folder), and the active memos that match — newest first, at most 200.
 */
data class MemoPickerState(
    val query: String = "",
    val folderId: Long? = null,
    val choices: List<MemoChoice> = emptyList(),
    /** The search and folder [choices] were found for — the sheet goes back to the top of a list found for a new one. */
    val shownFor: Pair<String, Long?>? = null,
)

data class ChatUiState(
    val input: String = "",
    val ai: AiPanelState = AiPanelState(),
    /** Phase 8: the conversation on screen (null until the first message of a new one) and its persistent transcript. */
    val conversationId: Long? = null,
    val conversationTitle: String? = null,
    val transcript: List<ChatMessage> = emptyList(),
    val historyEnabled: Boolean = false,
    /** The history drawer (2026-09-21 evening): every conversation, newest first; empty without a store. */
    val conversations: List<ChatConversation> = emptyList(),
    /** The drawer's pins (2026-09-21): ids only. */
    val pinnedConversationIds: Set<Long> = emptySet(),
    /** Template v2: the templates the plus button offers, the picker, the open form. */
    val templates: List<MemoTemplate> = emptyList(),
    /** Template first-class (§14): the last few templates run, newest first — resolved from ids; empty without a store. */
    val recentTemplates: List<MemoTemplate> = emptyList(),
    /** Review Batch 2: the ＋ picker's pinned template ids, in pin order; resolved against [templates] by the screen. */
    val pinnedTemplateIds: List<String> = emptyList(),
    /** The chat home launcher (2026-09-23): the templates the user put on the home, in their order — its own list, never the picker's pins. */
    val homeShortcuts: List<HomeShortcutEntry> = emptyList(),
    /** Template folders (2026-09-22): the user's own, in order; the picker's 自分のテンプレート page. */
    val templateFolders: List<TemplateFolder> = emptyList(),
    /** The chat's folder (2026-09-22): the wall's folders it may choose, and the chosen one — null = the root, as before. */
    val folderChoices: List<FolderChoice> = emptyList(),
    val destination: CreateDestination? = null,
    val pickerOpen: Boolean = false,
    /** The chat home launcher (2026-09-23): the built-in folder the picker opens at — null is its root. */
    val pickerSection: ThinkTemplates.Section? = null,
    /** Whether the open picker runs a template or chooses one for the home (2026-09-23). */
    val pickerMode: PickerMode = PickerMode.RUN,
    /** Template = conversation script (§16): the template being asked, one question at a time; null when none. */
    val session: TemplateSessionState? = null,
    /** UI review 2026-09-21: the installed models the chat's model button offers (id and name only) and the selected id; empty without a manager. */
    val installedModels: List<ModelChoice> = emptyList(),
    val selectedModelId: String? = null,
    /**
     * DecisionEngine (Phase 3, docs/DECISION_ENGINE.md): the one open clarification — **ephemeral**
     * (never saved; a process death drops it, the question line stays in the transcript, nothing
     * is written and nothing resumes). It has no execution authority: the answer only fills the
     * asked slot, and the result still walks validator → Resolver → preview → Human Confirmation.
     */
    val clarification: PendingClarification? = null,
    /** UI/UX review 2026-09-23: the user closed the 「Local AIモデルが必要です」 hint — once true it stays true, and the line is not drawn again. */
    val aiHintDismissed: Boolean = false,
    /**
     * 2026-09-24: the preview on screen came from one asked question (メモ / 探す), so 「修正」 can ask
     * it again with what was answered. Ephemeral, like the question itself.
     */
    val canEditAsked: Boolean = false,
    /**
     * 「メモを選択」 (docs/CHAT_MEMO_CONTEXT.md, 2026-09-26): the memo this conversation is pointed at —
     * a context, never a write authority. It stands where the conversation's anchor stands; OPEN and
     * APPEND still walk the pipeline to a preview and the Human Confirmation. Null when none.
     */
    val selectedMemo: MemoChoice? = null,
    /** The memo picker while it is open; null when closed. */
    val memoPicker: MemoPickerState? = null,
) {
    val busy: Boolean get() = ai.phase == AiPhase.LOADING || ai.phase == AiPhase.GENERATING || ai.phase == AiPhase.EXECUTING
}

/** One installed model as the chat names it — an id for the manager, a display name for the user; never a path or a file. */
data class ModelChoice(val id: String, val name: String)

/**
 * チャット (docs/CHAT_UI_TEMPLATE_V2.md): the place to ask MemoRipple for something in natural
 * words — a conversation. Free text goes to the [AiOrchestrator] (a model is needed for that and
 * only for that); a template from the plus button runs through the same orchestrator with no
 * model at all: validation → rendering → the unchanged Resolver / policy / executor → the same
 * result cards, the same preview, the same confirm button. SEARCH and a unique OPEN run; a write
 * is a preview until the user confirms.
 *
 * Phase 8 (docs/AI_CONVERSATION_HISTORY.md): the user's lines and what the screen showed are
 * appended to the [ConversationStore] as plain text; the latest shown results and the last
 * opened / confirmed document are the conversation's safe context, lent to the orchestrator on
 * the next ask. The current result, preview, ticket and form stay request-scoped memory: a
 * process death keeps the transcript and drops the preview.
 *
 * The draft input lives in the saved state; the current conversation is a light preference
 * ([LastConversationStore]); nothing else is persisted from here.
 *
 * UI review 2026-09-21: [models] lets the chat's model button switch between the installed
 * models or to 「AIモデルなし」 — the manager's own `select` / `clearSelection`, which unload the
 * runtime and never load one. [startFresh] is the 新しいチャット stage: it clears the remembered
 * conversation and begins with none; its first message creates one, which the tab then follows;
 * a conversation picked from the history is followed as usual.
 */
class ChatViewModel(
    private val savedStateHandle: SavedStateHandle? = null,
    private val ai: AiOrchestrator? = null,
    private val history: ConversationStore? = null,
    private val lastConversation: LastConversationStore? = null,
    private val templates: Flow<List<MemoTemplate>>? = null,
    private val models: ModelManager? = null,
    startFresh: Boolean = false,
    private val recent: RecentTemplateStore? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val pins: PinnedConversationStore? = null,
    private val templatePins: PinnedTemplateStore? = null,
    private val templateFolders: TemplateFolderStore? = null,
    /** The chat's chosen folder (2026-09-22): the preference, and the wall's folders it may name. */
    private val chatDestination: ChatDestinationStore? = null,
    private val folderChoices: DocumentFolderChoices? = null,
    /** The chat's closed hint (UI/UX review 2026-09-23): a flag the user sets once. */
    private val hints: ChatHintStore? = null,
    /** The chat home launcher (2026-09-23): the templates the user put on the home, and removing one of their own. */
    private val homeShortcuts: HomeShortcutStore? = null,
    private val templateRemover: TemplateRemover? = null,
    /** 「メモを選択」 (docs/CHAT_MEMO_CONTEXT.md): the memos it may point at (read-only), and each conversation's choice (ids in a preference). */
    private val memoChoices: DocumentMemoChoices? = null,
    private val memoSelections: ChatMemoSelectionStore? = null,
) : ViewModel() {
    /** The stage: the remembered conversation is set aside — it is not followed until the preference has been cleared (a history pick after that is followed). */
    private var ignoreRemembered = startFresh
    private val _uiState = MutableStateFlow(
        ChatUiState(
            input = savedStateHandle?.get<String>(KEY_INPUT).orEmpty(),
            historyEnabled = history != null,
        ),
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    private var askJob: Job? = null
    private var executeJob: Job? = null

    /** Without a history store (the Phase 3 shape): the `result_N` list the model may refer to — the results on screen, or nothing. In memory only. */
    private var resultContext: AiResultContext = AiResultContext.EMPTY

    /** The conversation the transcript follows; set only from the preference and from the first message. */
    private val currentConversation = MutableStateFlow<Long?>(null)

    /**
     * A new chat's 「メモを選択」 before its first message makes the conversation (then bound to it, in the preference).
     * Memory only — the chat's saved state is its draft and nothing else (a standing rule): a process death before
     * the first message lets this go, and nothing is written either way.
     */
    private val pendingMemo = MutableStateFlow<Long?>(null)
    private val memoPickerInput = MutableStateFlow("" to (null as Long?))
    private var memoPickerJob: Job? = null

    /**
     * The request on screen (2026-09-26): its conversation, and the screen's generation when it started. A result
     * reaches the screen only while the screen still shows that conversation in that generation — a switch or a
     * deletion moves the generation on — and the conversation's deletion cancels the request. The store's own
     * check (a line is written only while its conversation exists) is the last gate either way.
     */
    private var liveGeneration = 0L
    private var requestConversation: Long? = null

    private fun live(generation: Long, conversationId: Long?): Boolean =
        generation == liveGeneration && conversationId == currentConversation.value

    init {
        followConversation()
        if (startFresh) lastConversation?.let { preference -> viewModelScope.launch { preference.setLastConversationId(null) } }
        // the user's own templates first, then the six built-in starters (never copied into the store); the recents point into that list
        templates?.let { flow ->
            val all = flow.map { list -> list + StarterTemplates.all.filter { s -> list.none { it.id == s.id } } }
            val recentIds = recent?.recent ?: flowOf(emptyList())
            viewModelScope.launch {
                combine(all, recentIds) { list, ids -> list to RecentTemplates.resolve(ids, list) }
                    .collect { (list, recents) -> _uiState.update { it.copy(templates = list, recentTemplates = recents) } }
            }
        }
        models?.let { manager ->
            viewModelScope.launch {
                combine(manager.states, manager.selectedModelId) { states, selected ->
                    manager.entries.filter { states[it.modelId] is InstallState.Installed }.map { ModelChoice(it.modelId, it.displayName) } to selected
                }.collect { (installed, selected) -> _uiState.update { it.copy(installedModels = installed, selectedModelId = selected) } }
            }
        }
        hints?.let { store -> viewModelScope.launch { store.aiHintDismissed.collect { closed -> _uiState.update { it.copy(aiHintDismissed = closed) } } } }
        homeShortcuts?.let { store -> viewModelScope.launch { store.shortcuts.collect { list -> _uiState.update { it.copy(homeShortcuts = list) } } } }
        pins?.let { store -> viewModelScope.launch { store.pinnedIds.collect { ids -> _uiState.update { it.copy(pinnedConversationIds = ids) } } } }
        templatePins?.let { store -> viewModelScope.launch { store.pinnedIds.collect { ids -> _uiState.update { it.copy(pinnedTemplateIds = ids) } } } }
        templateFolders?.let { store -> viewModelScope.launch { store.folders.collect { list -> _uiState.update { it.copy(templateFolders = list) } } } }
        // the chosen folder is resolved against the wall's folders every time either changes; a folder that vanished clears the preference
        if (chatDestination != null && folderChoices != null) {
            viewModelScope.launch {
                combine(folderChoices.choices, chatDestination.folderId) { choices, chosen -> choices to chosen }.collect { (choices, chosen) ->
                    val destination = FolderChoices.destination(choices, chosen)
                    _uiState.update { it.copy(folderChoices = choices, destination = destination) }
                    if (chosen != null && destination == null) chatDestination.set(null)
                }
            }
        }
        // 「メモを選択」: this conversation's memo (or a new chat's pending one), read through the port every time either changes —
        // a memo that is no longer one the chat may point at (deleted, archived, trashed) lets the selection go
        if (memoChoices != null && memoSelections != null) {
            viewModelScope.launch {
                combine(currentConversation, memoSelections.selections, pendingMemo) { conversation, map, pending ->
                    conversation to (if (conversation != null) map[conversation] else pending)
                }.distinctUntilChanged().flatMapLatest { (conversation, id) ->
                    if (id == null) flowOf(Triple(conversation, null, null)) else memoChoices.observe(id).map { Triple(conversation, id, it) }
                }.collect { (conversation, id, choice) ->
                    _uiState.update { it.copy(selectedMemo = choice) }
                    if (id != null && choice == null) {
                        if (conversation != null) memoSelections.set(conversation, null) else setPendingMemo(null)
                    }
                }
            }
        }
        refreshAvailability()
    }

    /**
     * 「メモを選択」: the sheet of active memos opens — on the folder chosen with 「フォルダを選択」 when there is one
     * (named and really filtered; the Human Review, 2026-09-27), else on every folder. Nothing is selected or written.
     */
    fun openMemoPicker() {
        val choices = memoChoices ?: return
        if (_uiState.value.busy) return
        val folder = _uiState.value.destination?.folderId
        memoPickerInput.value = "" to folder
        _uiState.update { it.copy(memoPicker = MemoPickerState(folderId = folder)) }
        memoPickerJob?.cancel()
        memoPickerJob = viewModelScope.launch {
            memoPickerInput.flatMapLatest { (query, folder) -> choices.choices(query, folder).map { (query to folder) to it } }
                .collect { (shownFor, list) -> _uiState.update { s -> s.copy(memoPicker = s.memoPicker?.copy(choices = list, shownFor = shownFor)) } }
        }
    }

    fun setMemoPickerQuery(query: String) {
        memoPickerInput.value = query to memoPickerInput.value.second
        _uiState.update { it.copy(memoPicker = it.memoPicker?.copy(query = query)) }
    }

    fun setMemoPickerFolder(folderId: Long?) {
        memoPickerInput.value = memoPickerInput.value.first to folderId
        _uiState.update { it.copy(memoPicker = it.memoPicker?.copy(folderId = folderId)) }
    }

    fun closeMemoPicker() {
        memoPickerJob?.cancel()
        memoPickerJob = null
        _uiState.update { it.copy(memoPicker = null) }
    }

    /** A memo chosen in the sheet: this conversation's context from now on (a new chat holds it until its first message). Writes no document. */
    fun selectMemo(memoId: Long) {
        closeMemoPicker()
        val conversation = currentConversation.value
        if (conversation == null) {
            setPendingMemo(memoId)
        } else {
            val store = memoSelections ?: return
            viewModelScope.launch { store.set(conversation, memoId) }
        }
    }

    /** The chip's ×: only the selection goes — the transcript, the shown results and the conversation's own anchor stay. */
    fun clearSelectedMemo() {
        val conversation = currentConversation.value
        if (conversation == null) {
            setPendingMemo(null)
        } else {
            val store = memoSelections ?: return
            viewModelScope.launch { store.set(conversation, null) }
        }
    }

    /**
     * Where what the chat writes goes (2026-09-27): the chosen folder for a new document — and, while a memo is
     * selected with 「メモを選択」, that memo, which then takes a memo the chat would create (appended to its end,
     * through the preview and the confirmation; the folder is not used for it).
     */
    private fun writeDestination(): CreateDestination? {
        val state = _uiState.value
        val selected = state.selectedMemo?.ref ?: return state.destination
        return (state.destination ?: CreateDestination()).copy(selectedMemo = selected)
    }

    private fun setPendingMemo(id: Long?) {
        pendingMemo.value = id
    }

    /**
     * The anchor an ask lends the pipeline: this conversation's selected memo when there is one and it is still a
     * memo the chat may point at, else the conversation's own anchor. An explicit name and a shown `result_N` still
     * come first — the pipeline only reads the anchor when the sentence names neither.
     */
    private suspend fun anchorFor(conversationId: Long, conversationAnchor: DocumentRef?): DocumentRef? {
        val choices = memoChoices ?: return conversationAnchor
        val store = memoSelections ?: return conversationAnchor
        val selected = store.selections.first()[conversationId] ?: return conversationAnchor
        return choices.observe(selected).first()?.ref ?: conversationAnchor
    }

    /**
     * The × on the hint above the input
 (UI/UX review 2026-09-23): the user says they know free
     * text needs a model, and the line never appears again — one write, and there is no write
     * that brings it back. What it said stays reachable: the model name in the top bar, and the
     * setup card a free-text send still answers with when no model can serve it.
     */
    fun dismissAiHint() {
        val store = hints ?: return
        // shown as closed at once; the preference is what keeps it closed after a restart
        _uiState.update { it.copy(aiHintDismissed = true) }
        viewModelScope.launch { store.dismissAiHint() }
    }

    /** The drawer's long press: pin or unpin one conversation — an id in a preference, nothing else. */
    fun togglePin(id: Long) {
        val store = pins ?: return
        val pinned = id in _uiState.value.pinnedConversationIds
        viewModelScope.launch { store.setPinned(id, !pinned) }
    }

    /** 「フォルダを選択」: the user's choice of where the chat's creates go — the id in one preference; null clears it. */
    fun selectDestination(folderId: Long?) {
        val store = chatDestination ?: return
        viewModelScope.launch { store.set(folderId) }
    }

    /**
     * 「＋ 新しいフォルダ」 in the chat's folder menu (2026-09-22): a folder at the root of the wall, by the
     * wall's own rule and repository, then chosen at once. A name that is no name makes nothing.
     */
    fun createDestinationFolder(name: String) {
        val folders = folderChoices ?: return
        val store = chatDestination ?: return
        val clean = FolderName.normalize(name) ?: return
        viewModelScope.launch {
            val id = folders.create(clean) ?: return@launch
            // the folders flow reaches the chat a moment after the write; choose it once it is there, so the
            // "a chosen folder that no longer exists clears the choice" rule cannot mistake it for a vanished one
            withTimeoutOrNull(2_000) { folders.choices.first { list -> list.any { it.id == id } } }
            store.set(id)
        }
    }

    /** Review Batch 2: a long press on a picker row pins or unpins the template — its id only. */
    fun togglePinTemplate(id: String) {
        val store = templatePins ?: return
        val pinned = id in _uiState.value.pinnedTemplateIds
        viewModelScope.launch { store.setPinned(id, !pinned) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun followConversation() {
        val store = history ?: return
        val preference = lastConversation ?: return
        viewModelScope.launch {
            preference.lastConversationId.collect { id ->
                if (ignoreRemembered) { if (id == null) ignoreRemembered = false else return@collect }
                val valid = id?.let { store.conversation(it) }
                if (id != null && valid == null) preference.setLastConversationId(null)   // a deleted conversation: back to none, safely
                if (currentConversation.value != valid?.id) switchTo(valid?.id, valid?.title)
            }
        }
        viewModelScope.launch {
            currentConversation.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else store.messages(id) }
                .collect { transcript -> _uiState.update { it.copy(transcript = transcript) } }
        }
        viewModelScope.launch {
            store.conversations().collect { list ->
                _uiState.update { it.copy(conversations = list) }
                val running = requestConversation
                if (running != null && list.none { it.id == running } && store.conversation(running) == null) conversationGone(running)
                val id = currentConversation.value
                if (id != null && list.none { it.id == id }) preference.setLastConversationId(null)
                else if (id != null) list.firstOrNull { it.id == id }?.let { c -> _uiState.update { it.copy(conversationTitle = c.title) } }
            }
        }
    }

    /** Another conversation (or none): its transcript and safe context replace the old ones entirely; the ephemeral result and form go. */
    private fun switchTo(id: Long?, title: String?) {
        liveGeneration++
        currentConversation.value = id
        resultContext = AiResultContext.EMPTY
        // a new chat starts with nothing selected; another conversation has its own selection
        setPendingMemo(null)
        closeMemoPicker()
        _uiState.update { it.copy(conversationId = id, conversationTitle = title, ai = AiPanelState(availability = it.ai.availability), session = null, pickerOpen = false, pickerSection = null, pickerMode = PickerMode.RUN, clarification = null) }
    }

    /**
     * Phase 7 (docs/CHAT_V1_RELEASE_READINESS.md): asks the orchestrator whether a model could
     * answer — no runtime, no load. Since Template v2 the answer only decides the hint under the
     * input and what a *free-text* send comes back with; templates need none of it.
     */
    fun refreshAvailability() {
        val orchestrator = ai
        if (orchestrator == null) {
            _uiState.update { it.copy(ai = it.ai.copy(availability = ModelAvailability.Unavailable(ModelUnavailableReason.NO_MODEL_CONFIGURED))) }
            return
        }
        viewModelScope.launch {
            val availability = orchestrator.availability()
            _uiState.update { it.copy(ai = it.ai.copy(availability = availability)) }
        }
    }

    fun updateInput(text: String) {
        _uiState.update { it.copy(input = text) }
        savedStateHandle?.set(KEY_INPUT, text)
    }

    /** Sends the free-text input to the orchestrator (a model is needed). The shown results are the model's context; a new ask replaces them. */
    fun ask() {
        val session = _uiState.value.session
        if (session != null && session.asking) answerCurrent(_uiState.value.input) else askText(_uiState.value.input.trim(), clearInput = true)
    }

    /**
     * Phase 7: the user's one tap after a failed load or generation, or after a conflict — the
     * same words asked again (a new preview, never the old ticket). Nothing calls this by itself.
     */
    fun retry() = askText(_uiState.value.ai.askedText, clearInput = false)

    /** The drawer: a conversation picked by the user becomes the current one — the preference, which this view model follows. */
    fun openConversation(id: Long) {
        if (_uiState.value.busy) return
        ignoreRemembered = false
        val preference = lastConversation ?: return
        viewModelScope.launch { preference.setLastConversationId(id) }
    }

    /** The drawer's long press, after the user confirmed: only that conversation's rows go; the documents it named stay. */
    fun deleteConversation(id: Long) {
        val store = history ?: return
        viewModelScope.launch {
            // on the main thread, in order with the request: either it has recorded its conversation (and is cancelled here)
            // or it has not yet (and finds the conversation gone right after it does)
            val wasCurrent = currentConversation.value == id
            conversationGone(id)
            if (wasCurrent) lastConversation?.setLastConversationId(null)
            store.delete(id)
        }
    }

    /**
     * A conversation is gone — deleted here, in 履歴を管理 or all at once (2026-09-26): a request still running for it
     * is cancelled (the model's lease goes with it), and if it is the one on screen the screen moves to a new chat,
     * which takes its result, its preview and that preview's ticket with it. The deletion itself is final; nothing
     * waits for the request, nothing is restored.
     */
    private fun conversationGone(id: Long) {
        if (requestConversation == id) {
            askJob?.cancel()
            requestConversation = null
        }
        if (currentConversation.value == id) switchTo(null, null)
        // its 「メモを選択」 goes too — the store's delete removes it as well, but a choice bound in the instant between the
        // conversation's creation and its deletion must not outlive it (integration of §16.105 and §16.106)
        memoSelections?.let { selections -> viewModelScope.launch { selections.set(id, null) } }
    }

    /** Phase 8: 「新しいチャット」 — nothing is created until the first message; the old conversation's context is out of reach. */
    fun newConversation() {
        if (_uiState.value.busy) return
        switchTo(null, null)
        val preference = lastConversation ?: return
        viewModelScope.launch { preference.setLastConversationId(null) }
    }

    /**
     * チャット tapped again in the bottom bar (docs/BOTTOM_NAV_RESELECT.md): the chat's home — the
     * same step as 「新しいチャット」 in place: nothing is created (a conversation starts with its
     * first message), nothing is deleted, the transcript stays in the history. Already at home
     * (no conversation, no question, no answer on screen) nothing happens; while an answer is
     * being made or a write is running, nothing happens either.
     */
    fun goHome() {
        val state = _uiState.value
        val atHome = state.conversationId == null && state.session == null && state.clarification == null &&
            state.ai.phase == AiPhase.IDLE && state.ai.result == null
        if (atHome) return
        newConversation()
    }

    private fun isBusy(): Boolean = askJob?.isActive == true || executeJob?.isActive == true

    private fun askText(text: String, clearInput: Boolean) {
        val orchestrator = ai ?: return
        if (text.isEmpty() || isBusy()) return
        // the Fast Path may answer without any model, so the bar starts at 「処理しています…」; a real load reports itself
        val initial = AiPhase.GENERATING
        _uiState.update { it.copy(ai = AiPanelState(phase = initial, askedText = text, availability = it.ai.availability), session = null, pickerOpen = false, input = if (clearInput) "" else it.input) }
        if (clearInput) savedStateHandle?.set(KEY_INPUT, "")
        askJob = viewModelScope.launch {
            val store = history
            val conversationId = if (store != null) ensureConversation(store, text) else null
            val generation = liveGeneration
            requestConversation = conversationId
            // deleted before this request could be told: it stops here, before any work
            if (store != null && conversationId != null && store.conversation(conversationId) == null) {
                conversationGone(conversationId)
                return@launch
            }
            val context: AiResultContext
            val referents: ConversationReferents
            if (store != null && conversationId != null) {
                val window = ActiveContextBudget.window(store.messages(conversationId).first())
                val safe = store.context(conversationId)
                context = safe.resultContext()
                referents = ConversationReferents(anchor = anchorFor(conversationId, safe.lastDocumentAnchor), window = window, conversationId = conversationId)
                store.append(conversationId, ChatRole.USER, ChatMessageKind.TEXT, text)
                val windowLines = window.messages.size
                val shownCount = safe.latestResults.size
                Log.i(TAG, "ask: windowLines=$windowLines truncated=${window.truncated} shown=$shownCount")
            } else {
                context = resultContext
                referents = ConversationReferents.NONE
            }
            resultContext = AiResultContext.EMPTY
            var timing: AiTiming? = null
            val suggestion = suggest(text)
            val askedChars = text.length
            // A pending clarification of this conversation (docs/DECISION_ENGINE.md): the words answer
            // exactly the asked slot — a body, a target name or the words to search for — and nothing else is
            // ever inferred from free text. Another conversation's question is dropped, never answered across.
            val pending = _uiState.value.clarification
            if (pending != null) {
                _uiState.update { it.copy(clarification = null) }
                if (pending.conversationId == conversationId) {
                    val filled = when (pending.slot) {
                        ClarificationSlot.BODY -> pending.draft.copy(text = text)
                        ClarificationSlot.TARGET -> pending.draft.copy(targetName = text)
                        ClarificationSlot.QUERY -> pending.draft.copy(query = text)
                    }
                    if (askBodyIfStillMissing(filled, pending.anchorTarget, conversationId, store)) return@launch
                    Log.i(TAG, "route=DECISION_CERTAIN length=$askedChars")
                    val result = orchestrator.completeDecision(filled, pending.anchorTarget, context, referents, writeDestination())
                    // a preview that came from one asked question can be asked again (2026-09-24)
                    askedAgain = if (result is AiInteractionResult.WritePreview) pending.asked?.let { it to text } else null
                    showResult(result, text, conversationId, recordUserLine = false, suggestion = suggestion, generation = generation)
                    if (live(generation, conversationId)) _uiState.update { it.copy(canEditAsked = askedAgain != null) }
                    return@launch
                }
            }
            // the Fast Path first (docs/CHAT_FAST_PATH.md): an unmistakable sentence never wakes the model —
            // the same pipeline, the same previews, the same transcript; NO_MATCH falls through unchanged
            val fast = orchestrator.interactFast(text, context, referents, writeDestination())
            if (fast != null) {
                Log.i(TAG, "route=FAST length=$askedChars")
                showResult(fast, text, conversationId, recordUserLine = false, suggestion = suggestion, generation = generation)
                return@launch
            }
            // then the DecisionEngine (docs/DECISION_ENGINE.md): a pure decision over this conversation's
            // safe context — CERTAIN settles with zero loads, a clarification asks its fixed question,
            // and only NotApplicable goes on to the generation model
            when (val decided = orchestrator.interactDecide(text, context, referents, writeDestination())) {
                is DecisionOutcome.Settled -> {
                    Log.i(TAG, "route=DECISION_CERTAIN length=$askedChars")
                    showResult(decided.result, text, conversationId, recordUserLine = false, suggestion = suggestion, generation = generation)
                    return@launch
                }
                is DecisionOutcome.Clarify -> {
                    Log.i(TAG, "route=DECISION_CLARIFY length=$askedChars choices=${decided.choices.size}")
                    if (store != null && conversationId != null && store.append(conversationId, ChatRole.ASSISTANT, ChatMessageKind.TEXT, decided.question) == null) {
                        conversationGone(conversationId)   // deleted while the question was on its way: nothing is asked
                        return@launch
                    }
                    if (!live(generation, conversationId)) return@launch
                    _uiState.update {
                        it.copy(
                            ai = AiPanelState(availability = it.ai.availability),
                            clarification = PendingClarification(conversationId, decided.slot, decided.draft, decided.anchorTarget, decided.choices),
                        )
                    }
                    return@launch
                }
                null -> Unit
            }
            Log.i(TAG, "route=GENERATION length=$askedChars")
            val result = orchestrator.interact(
                text,
                context,
                onProgress = { progress ->
                    val phase = when (progress) {
                        AiProgress.LOADING_MODEL -> AiPhase.LOADING
                        AiProgress.GENERATING -> AiPhase.GENERATING
                    }
                    if (live(generation, conversationId)) _uiState.update { it.copy(ai = it.ai.copy(phase = phase)) }
                },
                onNote = { note -> Log.i(TAG, note) },   // info level: a user build (the S20) filters debug logs
                referents = referents,
                onTiming = { timing = it },
                destination = writeDestination(),
            )
            if (result is AiInteractionResult.RuntimeError) Log.w(TAG, "${result.stage}: ${result.developerDetail}")
            // Phase 4B (docs/GENERATION_EFFICIENCY.md): the generation's timing decomposition — sizes and milliseconds only
            timing?.let { t ->
                val promptChars = t.promptChars; val promptTokens = t.promptTokens; val reused = t.reusedPrefixTokens; val evaluated = t.evaluatedPromptTokens
                val loadMs = t.loadMillis; val buildMs = t.promptBuildMillis; val tokenizeMs = t.tokenizeMillis; val promptEvalMs = t.promptEvalMillis
                val ttftMs = t.ttftMillis; val totalMs = t.totalMillis; val genTokens = t.generatedTokens
                Log.i(TAG, "GEN promptChars=$promptChars promptTokens=$promptTokens reused=$reused evaluated=$evaluated loadMs=$loadMs buildMs=$buildMs tokenizeMs=$tokenizeMs promptEvalMs=$promptEvalMs ttftMs=$ttftMs totalMs=$totalMs genTokens=$genTokens")
            }
            showResult(result, text, conversationId, recordUserLine = false, timing = timing, suggestion = suggestion, generation = generation)
        }
    }

    /** What a result becomes on screen and in the transcript — the same for a free-text ask and a template run. */
    private suspend fun showResult(result: AiInteractionResult, askedText: String, conversationId: Long?, recordUserLine: Boolean, timing: AiTiming? = null, suggestion: MemoTemplate? = null, generation: Long? = null) {
        val store = history
        // a result of a request whose screen has moved on (its conversation deleted, another one opened) is recorded
        // where it belongs if that still exists (the store checks), and never shown here
        val onScreen = generation == null || live(generation, conversationId)
        // a template that still wants a value is asked for it — one question, never a form (§16); nothing is recorded as a result
        if (result is AiInteractionResult.TemplateForm) {
            if (!onScreen) return
            val t = result.template
            val existing = _uiState.value.session?.takeIf { it.template.id == t.id }
            val answers = (existing?.answers ?: result.given.filterKeys { k -> k != TemplateTargetSpec.TARGET_KEY }) - result.missing
            val target = (existing?.target ?: result.given[TemplateTargetSpec.TARGET_KEY])?.takeIf { TemplateTargetSpec.TARGET_KEY !in result.missing }
            val step = TemplateScript.next(t, answers, target)
            val session = TemplateSessionState(template = t, step = step, answers = answers, target = target, editing = existing != null)
            _uiState.update { it.copy(ai = AiPanelState(availability = it.ai.availability), session = session) }
            if (step !is TemplateScript.Step.Ready) say(conversationId, spokenStep(session, first = existing == null))
            return
        }
        if (store != null && conversationId != null) {
            if (recordUserLine) store.append(conversationId, ChatRole.USER, ChatMessageKind.TEXT, askedText)
            when (result) {
                is AiInteractionResult.SearchResults -> store.setLatestResults(conversationId, result.results)
                is AiInteractionResult.Ambiguous -> store.setLatestResults(conversationId, result.candidates)
                is AiInteractionResult.Open -> store.setAnchor(conversationId, result.target.ref)
                else -> Unit
            }
            // the store writes the answer only while its conversation exists; no line means it was deleted while the
            // answer was on its way — then nothing of it is shown, whatever the screen has heard so far
            if (store.append(conversationId, ChatRole.ASSISTANT, AiWording.kindOf(result), AiWording.assistantText(result)) == null) {
                conversationGone(conversationId)
                return
            }
        } else if (onScreen) {
            resultContext = if (result is AiInteractionResult.SearchResults) AiResultContext.of(result.results) else AiResultContext.EMPTY
        }
        if (!onScreen) return
        _uiState.update {
            it.copy(
                ai = AiPanelState(
                    phase = AiPhase.DONE,
                    askedText = askedText,
                    result = result,
                    pendingOpen = (result as? AiInteractionResult.Open)?.target?.ref,
                    // an ask that found no model is the availability from now on: the hint says so, the next free text asks again
                    availability = if (result is AiInteractionResult.ModelUnavailable) ModelAvailability.Unavailable(result.reason) else it.ai.availability,
                    timing = timing,
                    suggestion = suggestion,
                    conversationId = conversationId,
                ),
            )
        }
    }

    /** The first message of a conversation creates it, titled from that message by rule; later messages join it. */
    private suspend fun ensureConversation(store: ConversationStore, firstText: String): Long {
        currentConversation.value?.let { return it }
        val id = store.create(ConversationTitle.from(firstText))
        // the new chat's choice goes with its conversation — written always, so an id the database reuses never inherits an old one
        memoSelections?.set(id, pendingMemo.value)
        currentConversation.value = id
        setPendingMemo(null)
        _uiState.update { it.copy(conversationId = id, conversationTitle = ConversationTitle.from(firstText)) }
        lastConversation?.setLastConversationId(id)
        Log.i(TAG, "conversation created")
        return id
    }

    // --- Template = conversation script (§16): the plus button, the picker, one question at a time, the run ---

    /**
     * Template first-class (§14): a deterministic suggestion — the longest template name the sentence contains
     * (two characters or more). It is shown beside whatever the ask came to; nothing runs until the user taps it.
     */
    private fun suggest(text: String): MemoTemplate? =
        _uiState.value.templates.filter { it.name.trim().length >= 2 && text.contains(it.name.trim()) }.maxByOrNull { it.name.trim().length }

    /** The user took the suggestion: the template starts — the same path as the picker. */
    fun useSuggestion() {
        val template = _uiState.value.ai.suggestion ?: return
        _uiState.update { it.copy(ai = it.ai.copy(suggestion = null)) }
        pickTemplate(template)
    }

    /** [section] opens the picker straight at one of its built-in folders (整理 does); null is the root the ＋ opens. */
    fun openTemplatePicker(section: ThinkTemplates.Section? = null) { if (!_uiState.value.busy) _uiState.update { it.copy(pickerOpen = true, pickerSection = section, pickerMode = PickerMode.RUN) } }

    fun closeTemplatePicker() { _uiState.update { it.copy(pickerOpen = false, pickerSection = null, pickerMode = PickerMode.RUN) } }

    // --- The chat home's launcher (2026-09-23): an entrance, never an action ---

    /**
     * One cell of the home grid: it opens a path the chat already has, and it can execute nothing
     * on its own. メモ and 探す open one fixed question whose answer completes through the same
     * validator → Resolver → policy → preview → Human Confirmation the rest of the chat uses; 日記
     * starts the built-in 振り返り template's ordinary script; 整理 and 追加 open the ＋ picker; a
     * pinned template starts its own script, Think included. No model is asked, nothing is typed
     * into the input for a recognizer to read, and nothing is written.
     */
    fun openShortcut(shortcut: HomeShortcut) {
        if (isBusy()) return
        when (shortcut) {
            HomeShortcut.Memo -> openQuestion(HomeAsks.clarify(HomeAsk.MEMO), shortcut.label)
            HomeShortcut.Search -> openQuestion(HomeAsks.clarify(HomeAsk.SEARCH), shortcut.label)
            HomeShortcut.Journal -> pickTemplate(journalTemplate())
            HomeShortcut.Organize -> openTemplatePicker(ThinkTemplates.Section.THINK)
            HomeShortcut.Add -> openShortcutChooser()
            is HomeShortcut.Template -> pickTemplate(shortcut.template)
        }
    }

    /** The built-in 今日の振り返り, or the user's own template of that id if they have replaced it. */
    private fun journalTemplate(): MemoTemplate =
        _uiState.value.templates.firstOrNull { it.id == StarterTemplates.dailyReview.id } ?: StarterTemplates.dailyReview

    /**
     * Opens one question of the launcher's: the line is spoken into the transcript and the draft
     * waits as the same single [PendingClarification] the DecisionEngine uses — ephemeral, with no
     * authority of its own; the next message fills exactly the asked slot and nothing else is
     * inferred. [title] names a conversation the question starts — the entrance's own word, so the
     * history reads 「メモ」, not the question.
     */
    /** The launcher question a preview on screen came from, and what was answered to it. Memory only. */
    private var askedAgain: Pair<DecisionResult.NeedsClarification, String>? = null

    private fun openQuestion(ask: DecisionResult.NeedsClarification, title: String) {
        _uiState.update { it.copy(pickerOpen = false, pickerSection = null, session = null, ai = AiPanelState(availability = it.ai.availability)) }
        askedAgain = null
        viewModelScope.launch {
            val store = history
            val conversationId = if (store != null) ensureConversation(store, title) else null
            say(conversationId, ask.question)
            _uiState.update { it.copy(clarification = PendingClarification(conversationId, ask.slot, ask.draft, ask.anchorTarget, ask.choices, asked = ask)) }
        }
    }

    /**
     * 「＋ 追加」: the picker opens to **choose** a template for the home rather than to run one.
     * The list is the same; only what a tap means changes (2026-09-23, the user's review).
     */
    fun openShortcutChooser() { if (!_uiState.value.busy) _uiState.update { it.copy(pickerOpen = true, pickerSection = null, pickerMode = PickerMode.ADD_SHORTCUT) } }

    /** The chosen template takes a slot on the home — an id, at the end, while there is room. */
    fun addHomeShortcut(templateId: String) {
        val store = homeShortcuts ?: return
        _uiState.update { it.copy(pickerOpen = false, pickerMode = PickerMode.RUN) }
        viewModelScope.launch { store.add(templateId) }
    }

    /** A long press on a cell: it leaves the home. The template itself is untouched. */
    fun removeHomeShortcut(templateId: String) {
        val store = homeShortcuts ?: return
        viewModelScope.launch { store.remove(templateId) }
    }

    /** A long press on a cell: the name it shows on the home. Blank restores the template's own. */
    fun renameHomeShortcut(templateId: String, label: String) {
        val store = homeShortcuts ?: return
        viewModelScope.launch { store.rename(templateId, label) }
    }

    /**
     * A long press in the ＋ picker: one of the user's own templates is removed for good. A
     * starter is code and is never removed; no memo, journal or outline is touched, and a home
     * slot that named it is dropped with it.
     */
    fun deleteTemplate(templateId: String) {
        val remover = templateRemover ?: return
        if (StarterTemplates.isStarter(templateId)) return
        viewModelScope.launch {
            remover.remove(templateId)
            homeShortcuts?.remove(templateId)
        }
    }

    /**
     * A picked template starts its script: the template's name is the user's line; with nothing to
     * ask it runs at once (a SEARCH says 「…を探します。」 first); otherwise the first question is asked
     * and the session waits for the answer through the input or the chips.
     */
    fun pickTemplate(template: MemoTemplate) {
        if (isBusy()) return
        _uiState.update { it.copy(pickerOpen = false, session = null, ai = AiPanelState(availability = it.ai.availability)) }
        viewModelScope.launch {
            val store = history
            val conversationId = if (store != null) ensureConversation(store, template.name) else null
            if (store != null && conversationId != null) store.append(conversationId, ChatRole.USER, ChatMessageKind.TEXT, template.name)
            when (val step = TemplateScript.next(template, emptyMap(), null)) {
                TemplateScript.Step.Ready -> {
                    if (template.action == TemplateAction.SEARCH) say(conversationId, "${template.name}を探します。")
                    executeTemplate(template, emptyMap(), null, conversationId)
                }
                else -> {
                    val session = TemplateSessionState(template = template, step = step)
                    _uiState.update { it.copy(session = session) }
                    say(conversationId, spokenStep(session, first = true))
                }
            }
        }
    }

    private suspend fun say(conversationId: Long?, text: String) {
        val store = history ?: return
        val id = conversationId ?: return
        store.append(id, ChatRole.ASSISTANT, ChatMessageKind.TEXT, text)
    }

    private fun spokenStep(session: TemplateSessionState, first: Boolean): String = when (val step = session.step) {
        is TemplateScript.Step.Ask -> if (session.editing) TemplateScript.questionOf(step.field) else TemplateScript.spoken(session.template, step, first)
        TemplateScript.Step.AskTarget -> (if (first) "${session.template.name}を始めます。\n" else "") + TemplateScript.targetQuestionOf(session.template)
        TemplateScript.Step.Ready -> ""
    }

    /** The message input while a question is open: the typed words are the answer. Blank is ignored. */
    fun answerCurrent(text: String) {
        val session = _uiState.value.session ?: return
        val answer = text.trim()
        if (answer.isEmpty() || isBusy()) return
        _uiState.update { it.copy(input = "") }
        savedStateHandle?.set(KEY_INPUT, "")
        viewModelScope.launch {
            val conversationId = currentConversation.value
            when (val step = session.step) {
                TemplateScript.Step.AskTarget -> {
                    say(conversationId, answer, user = true)
                    resolveTarget(session, answer, conversationId)
                }
                is TemplateScript.Step.Ask -> {
                    val f = step.field
                    val value: String? = when (f.type) {
                        TemplateFieldType.TEXT, TemplateFieldType.MULTILINE -> answer
                        TemplateFieldType.DATE -> TemplateValues.resolveDate(answer, today())?.let { if (answer.uppercase() in setOf("TODAY", "今日")) "TODAY" else if (answer.uppercase() in setOf("YESTERDAY", "昨日")) "YESTERDAY" else it.toString() }
                        TemplateFieldType.CHOICE -> answer.takeIf { it in f.choices }
                        TemplateFieldType.BOOLEAN -> when (answer.lowercase()) { "はい", "yes", "true", "1", "on" -> "true"; "いいえ", "no", "false", "0", "off" -> "false"; else -> null }
                    }
                    say(conversationId, answer, user = true)
                    if (value == null) {
                        say(conversationId, when (f.type) {
                            TemplateFieldType.DATE -> "日付として読めませんでした。今日・昨日か、日付を選んでください。"
                            TemplateFieldType.CHOICE -> "選択肢から選んでください：${f.choices.joinToString("・")}"
                            else -> "「はい」か「いいえ」で答えてください。"
                        })
                        return@launch
                    }
                    record(session, f.key, value, conversationId)
                }
                TemplateScript.Step.Ready -> Unit
            }
        }
    }

    private suspend fun say(conversationId: Long?, text: String, user: Boolean) {
        val store = history ?: return
        val id = conversationId ?: return
        store.append(id, if (user) ChatRole.USER else ChatRole.ASSISTANT, ChatMessageKind.TEXT, text)
    }

    /** A chip under the question: 今日 / 昨日 / a picked day, はい / いいえ, one of the choices. The transcript keeps the user's words, never a token. */
    fun answerOption(value: String) {
        val session = _uiState.value.session ?: return
        val step = session.step as? TemplateScript.Step.Ask ?: return
        if (isBusy()) return
        viewModelScope.launch {
            val conversationId = currentConversation.value
            say(conversationId, TemplateScript.spokenAnswer(step.field, value), user = true)
            record(session, step.field.key, value, conversationId)
        }
    }

    /** A picked day from the calendar: an ISO date the run resolves; the transcript reads 「yyyy年M月d日」. */
    fun answerDate(iso: String) {
        val session = _uiState.value.session ?: return
        val step = session.step as? TemplateScript.Step.Ask ?: return
        if (isBusy()) return
        viewModelScope.launch {
            val conversationId = currentConversation.value
            say(conversationId, TemplateValues.formatDate(iso, today()) ?: iso, user = true)
            record(session, step.field.key, iso, conversationId)
        }
    }

    /** An optional question left unanswered: 「スキップ」 is the user's line; the field is "" from here on. */
    fun skipCurrent() {
        val session = _uiState.value.session ?: return
        val step = session.step as? TemplateScript.Step.Ask ?: return
        if (step.field.required || isBusy()) return
        viewModelScope.launch {
            val conversationId = currentConversation.value
            say(conversationId, "スキップ", user = true)
            record(session, step.field.key, "", conversationId)
        }
    }

    /** A target candidate the user tapped: its title is the target; the run re-resolves it by name at the end. */
    fun chooseTarget(summary: DocumentSummary) {
        val session = _uiState.value.session ?: return
        if (session.step != TemplateScript.Step.AskTarget || isBusy()) return
        viewModelScope.launch {
            val conversationId = currentConversation.value
            say(conversationId, summary.title, user = true)
            acceptTarget(session, summary.title, conversationId)
        }
    }

    /**
     * The target's name is looked up at once, through the orchestrator's own SEARCH (no model): one
     * hit is the target; several are shown as candidates to tap; none asks again. The run still
     * resolves the name itself before the preview.
     */
    private suspend fun resolveTarget(session: TemplateSessionState, name: String, conversationId: Long?) {
        val orchestrator = ai ?: return
        val probe = MemoTemplate(id = "target-probe", name = name, body = "", action = TemplateAction.SEARCH, searchSpec = TemplateSearchSpec(query = name, kinds = setOf(DocumentKind.MEMO, DocumentKind.OUTLINE, DocumentKind.JOURNAL)))
        val results = (orchestrator.runTemplate(probe, emptyMap()) as? AiInteractionResult.SearchResults)?.results.orEmpty()
        val exact = results.filter { it.title.trim() == name }
        val hits = if (exact.size == 1) exact else results
        val hitCount = hits.size
        Log.i(TAG, "template target: hits=$hitCount")
        when (hits.size) {
            0 -> say(conversationId, "「$name」という記録は見つかりませんでした。もう一度、名前を教えてください。")
            1 -> acceptTarget(session, hits.single().title, conversationId)
            else -> {
                _uiState.update { it.copy(session = session.copy(targetCandidates = hits)) }
                say(conversationId, "いくつか見つかりました。どれに追記しますか？")
            }
        }
    }

    private suspend fun acceptTarget(session: TemplateSessionState, title: String, conversationId: Long?) {
        say(conversationId, "「$title」に追記します。")
        advance(session.copy(target = title, targetCandidates = emptyList()), conversationId)
    }

    private suspend fun record(session: TemplateSessionState, key: String, value: String, conversationId: Long?) {
        advance(session.copy(answers = session.answers + (key to value)), conversationId)
    }

    /** The next step of the script: another question, the target, or — everything there — the run to its preview. */
    private suspend fun advance(session: TemplateSessionState, conversationId: Long?) {
        val next = TemplateScript.next(session.template, session.answers, session.target)
        val moved = session.copy(step = next)
        _uiState.update { it.copy(session = moved) }
        when (next) {
            TemplateScript.Step.Ready -> executeTemplate(session.template, session.answers, session.target, conversationId)
            else -> say(conversationId, spokenStep(moved, first = false))
        }
    }

    /**
     * Runs a template through the orchestrator — no model, no runtime (docs/CHAT_UI_TEMPLATE_V2.md).
     * The result is shown and recorded like a free-text result; a write still stops at its preview
     * and the same confirm. A value the runner still wants is asked again as a question, never as a form.
     */
    private suspend fun executeTemplate(template: MemoTemplate, answers: Map<String, String>, target: String?, conversationId: Long?, save: Boolean = false) {
        // Think (docs/THINK_TEMPLATES.md): the questions end in a result, not a run — until the user asks to save
        if (template.flow == TemplateFlow.THINK && !save) {
            completeThink(template, answers, conversationId)
            return
        }
        val orchestrator = ai ?: return
        _uiState.update { it.copy(ai = AiPanelState(phase = AiPhase.GENERATING, askedText = template.name, availability = it.ai.availability)) }
        val values = if (target != null) answers + (TemplateTargetSpec.TARGET_KEY to target) else answers
        val result = orchestrator.runTemplate(template, values, destination = writeDestination())
        if (result !is AiInteractionResult.TemplateForm) recent?.record(template.id, now())
        val fieldCount = template.fields.size
        Log.i(TAG, "template run: action=${template.action} fields=$fieldCount resultKind=${AiWording.kindOf(result)}")
        showResult(result, template.name, conversationId, recordUserLine = false)
    }

    /**
     * Think templates (docs/THINK_TEMPLATES.md): every question answered, the body is rendered by the
     * deterministic engine — no orchestrator, no model, no write — and shown as the result with
     * 「メモとして保存」 / 「終了」 under it. The result's words go to the transcript; the session stays so
     * a save can run the same answers, or 修正 can ask one again. Remembered as recent here.
     */
    private suspend fun completeThink(template: MemoTemplate, answers: Map<String, String>, conversationId: Long?) {
        val rendered = ThinkTemplates.result(template, answers, today())
        recent?.record(template.id, now())
        val fieldCount = template.fields.size
        val renderedChars = rendered.text.length
        Log.i(TAG, "think completed: fields=$fieldCount renderedChars=$renderedChars")
        showResult(AiInteractionResult.ThinkResult(template, rendered.text, answers), template.name, conversationId, recordUserLine = false)
    }

    /** 「メモとして保存」: the user's tap — the same template and answers go down the CREATE path to its preview and the one confirm. */
    fun saveThinkResult() {
        val result = _uiState.value.ai.result as? AiInteractionResult.ThinkResult ?: return
        if (isBusy()) return
        viewModelScope.launch {
            say(currentConversation.value, "メモとして保存", user = true)
            executeTemplate(result.template, result.answers, null, currentConversation.value, save = true)
        }
    }

    /** 「終了」: the result stays in the transcript; the session and the card go; nothing was written. */
    fun finishThink() {
        if (_uiState.value.ai.result !is AiInteractionResult.ThinkResult) return
        _uiState.update { it.copy(session = null, ai = AiPanelState(availability = it.ai.availability)) }
    }

    /**
     * 「この会話からテンプレートを作成」: a draft for the editor from the transcript, by rule — the questions and
     * their order, never the answers; null when there is no question → answer pair. The chat writes no
     * template: the screen hands the draft to the editor, and only the editor's 保存 keeps it.
     */
    fun templateDraftFromConversation(): MemoTemplate? {
        val state = _uiState.value
        if (state.conversationId == null) return null
        val draft = ConversationTemplateDraft.from(state.conversationTitle.orEmpty(), state.transcript)
        val fieldCount = draft?.fields?.size ?: 0
        Log.i(TAG, "conversation draft: fields=$fieldCount")
        return draft
    }

    // --- Review Batch 2: 「この会話をメモとして保存」 — the transcript as a memo, by rule, through the ordinary preview and the one confirm ---

    /** True when there is a conversation with something in it to save. */
    val canSaveConversation: Boolean get() = _uiState.value.conversationId != null && _uiState.value.transcript.isNotEmpty()

    /**
     * The whole current conversation, exported by [ConversationExport] (deterministic: the title, then each
     * line under あなた / MemoRipple; nothing the user did not see), offered as a CREATE MEMO preview
     * from the orchestrator — no model, no runtime; the write is the same confirm as every other.
     */
    fun saveConversationAsMemo() {
        val orchestrator = ai ?: return
        val state = _uiState.value
        val conversationId = state.conversationId ?: return
        if (isBusy()) return
        val body = ConversationExport.bodyOrNull(state.conversationTitle.orEmpty(), state.transcript, today()) ?: return
        _uiState.update { it.copy(session = null, ai = AiPanelState(phase = AiPhase.GENERATING, askedText = "この会話をメモとして保存", availability = it.ai.availability)) }
        viewModelScope.launch {
            val result = orchestrator.previewMemo(body, destination = writeDestination())
            val lineCount = state.transcript.size
            Log.i(TAG, "conversation export: lines=$lineCount resultKind=${AiWording.kindOf(result)}")
            showResult(result, "この会話をメモとして保存", conversationId, recordUserLine = false)
        }
    }

    /** The plus-button path for a template with nothing to ask (kept for callers that have the values already, e.g. tests). */
    fun runTemplate(template: MemoTemplate, values: Map<String, String>) {
        if (isBusy()) return
        askJob = viewModelScope.launch {
            val store = history
            val conversationId = if (store != null) ensureConversation(store, template.name) else null
            if (store != null && conversationId != null) store.append(conversationId, ChatRole.USER, ChatMessageKind.TEXT, template.name)
            executeTemplate(template, values - TemplateTargetSpec.TARGET_KEY, values[TemplateTargetSpec.TARGET_KEY], conversationId)
        }
    }

    /** 「修正」 on the preview: the chosen answer is asked again; the preview returns with the new value. No cancel line. */
    fun editAnswer(key: String) {
        val session = _uiState.value.session ?: return
        val field = session.template.fields.firstOrNull { it.key == key } ?: return
        if (_uiState.value.ai.phase == AiPhase.EXECUTING) return
        resultContext = AiResultContext.EMPTY
        val again = session.copy(answers = session.answers - key, step = TemplateScript.Step.Ask(field, session.template.fields.indexOf(field), session.template.fields.size), editing = true)
        _uiState.update { it.copy(ai = AiPanelState(availability = it.ai.availability), session = again) }
        viewModelScope.launch { say(currentConversation.value, spokenStep(again, first = false)) }
    }

    /**
     * 「修正」 on a preview that came from one asked question — メモ, and 探す before it (2026-09-24).
     * There is no template to pick a field from, so the one question is asked again and what was
     * answered is put back in the input to be changed rather than retyped. The preview goes with
     * it: nothing was written, and nothing is written until the next one is confirmed.
     */
    fun editAskedAnswer() {
        val (ask, previous) = askedAgain ?: return
        if (isBusy()) return
        askedAgain = null
        _uiState.update { it.copy(canEditAsked = false, input = previous, ai = AiPanelState(availability = it.ai.availability)) }
        savedStateHandle?.set(KEY_INPUT, previous)
        viewModelScope.launch {
            val conversationId = currentConversation.value
            say(conversationId, ask.question)
            _uiState.update { it.copy(clarification = PendingClarification(conversationId, ask.slot, ask.draft, ask.anchorTarget, ask.choices, asked = ask)) }
        }
    }

    /** 「やめる」 or Back while a question is open: one line says so; the answers so far are dropped; nothing was written. */
    fun cancelSession() {
        val session = _uiState.value.session ?: return
        if (_uiState.value.ai.phase == AiPhase.EXECUTING) return
        _uiState.update { it.copy(session = null, ai = AiPanelState(availability = it.ai.availability)) }
        viewModelScope.launch { say(currentConversation.value, AiWording.CANCELLED) }
    }

    private fun today(): java.time.LocalDate = java.time.Instant.ofEpochMilli(now()).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

    // --- UI review 2026-09-21: the model button — 「AIモデルなし」 stops the AI; an installed model brings it back ---

    /**
     * 「AIモデルなし」: the manager clears the selection, which unloads a loaded runtime (after a
     * running answer finishes — never mid-write) and never loads one; free text then gets the
     * setup card, templates keep working. The availability is read again afterwards.
     */
    fun selectNoModel() {
        val manager = models ?: return
        viewModelScope.launch {
            manager.clearSelection()
            refreshAvailability()
        }
    }

    /** An installed model by id — the manager's own `select` (unloads, never loads); the next free text loads it. */
    fun selectModel(id: String) {
        val manager = models ?: return
        viewModelScope.launch {
            manager.select(id)
            refreshAvailability()
        }
    }

    /** The screen handed the unique OPEN to the navigator; it must not be handed again on recomposition. */
    fun openConsumed() {
        _uiState.update { it.copy(ai = it.ai.copy(pendingOpen = null)) }
    }

    /**
     * DecisionEngine (Phase 3, docs/DECISION_ENGINE.md): the user tapped one of the fixed choices —
     * a position in the shown results or the last opened document. The pick fills exactly the asked
     * target slot; a still-missing append body becomes the next fixed question; everything then
     * walks the ordinary pipeline (preview, one confirmation), with no model and no load.
     */
    fun answerClarification(choice: ClarificationChoice) {
        val orchestrator = ai ?: return
        val pending = _uiState.value.clarification ?: return
        if (isBusy()) return
        _uiState.update { it.copy(clarification = null, ai = it.ai.copy(phase = AiPhase.GENERATING, askedText = choice.label)) }
        askJob = viewModelScope.launch {
            val store = history
            val conversationId = pending.conversationId
            val generation = liveGeneration
            requestConversation = conversationId
            val context: AiResultContext
            val referents: ConversationReferents
            if (store != null && conversationId != null) {
                val window = ActiveContextBudget.window(store.messages(conversationId).first())
                val safe = store.context(conversationId)
                context = safe.resultContext()
                referents = ConversationReferents(anchor = anchorFor(conversationId, safe.lastDocumentAnchor), window = window, conversationId = conversationId)
                store.append(conversationId, ChatRole.USER, ChatMessageKind.TEXT, choice.label)
            } else {
                context = resultContext
                referents = ConversationReferents.NONE
            }
            val anchorTarget = pending.anchorTarget || choice.ordinal == null
            val filled = if (choice.ordinal != null) pending.draft.copy(targetRef = AiResultRef(choice.ordinal)) else pending.draft
            if (askBodyIfStillMissing(filled, anchorTarget, conversationId, store)) return@launch
            val pickedChars = choice.label.length
            Log.i(TAG, "route=DECISION_CERTAIN length=$pickedChars")
            val result = orchestrator.completeDecision(filled, anchorTarget, context, referents, writeDestination())
            showResult(result, choice.label, conversationId, recordUserLine = false, generation = generation)
        }
    }

    /** A filled target whose append body is still missing asks the next fixed question instead of running. */
    private suspend fun askBodyIfStillMissing(filled: IntentProposal, anchorTarget: Boolean, conversationId: Long?, store: ConversationStore?): Boolean {
        if (filled.intent != AiIntent.APPEND || !filled.text.isNullOrBlank()) return false
        val question = "何を追記しますか？"
        if (store != null && conversationId != null) store.append(conversationId, ChatRole.ASSISTANT, ChatMessageKind.TEXT, question)
        _uiState.update {
            it.copy(
                ai = AiPanelState(availability = it.ai.availability),
                clarification = PendingClarification(conversationId, ClarificationSlot.BODY, filled, anchorTarget, emptyList()),
            )
        }
        return true
    }

    /** Phase 8: the user picked one of the shown candidates — it becomes the conversation's anchor and a line of the transcript. */
    fun chooseCandidate(summary: DocumentSummary) {
        val store = history
        val id = currentConversation.value
        if (store != null && id != null) {
            viewModelScope.launch {
                store.setAnchor(id, summary.ref)
                store.append(id, ChatRole.ASSISTANT, ChatMessageKind.RESULT, AiWording.opened(summary))
            }
        }
        dismissAiResult()
    }

    /**
     * The Human Confirmation (docs/AI_CONFIRMED_WRITE.md): the user pressed the confirm button on
     * a write preview. One execution per ticket — the EXECUTING phase refuses a second press, and
     * the ticket itself refuses a second run. Nothing here needs the model.
     */
    fun confirmWrite() {
        val orchestrator = ai ?: return
        val current = _uiState.value.ai
        val previewed = current.result as? AiInteractionResult.WritePreview ?: return
        if (current.phase != AiPhase.DONE || executeJob?.isActive == true) return
        val origin = current.conversationId
        _uiState.update { it.copy(ai = it.ai.copy(phase = AiPhase.EXECUTING)) }
        executeJob = viewModelScope.launch {
            // the ticket belongs to the conversation it was previewed in: gone (or no longer on screen), it writes nothing
            val chats = history
            if (chats != null && origin != null && (origin != currentConversation.value || chats.conversation(origin) == null)) {
                _uiState.update { it.copy(ai = AiPanelState(availability = it.ai.availability)) }
                return@launch
            }
            val outcome = orchestrator.execute(previewed.pending)
            if (outcome is WriteOutcome.Failed) Log.w(TAG, "write failed: ${outcome.developerDetail}")
            resultContext = AiResultContext.EMPTY
            val store = history
            val id = currentConversation.value
            if (store != null && id != null) {
                if (outcome is WriteOutcome.Success) store.setAnchor(id, outcome.ref)
                store.append(id, ChatRole.ASSISTANT, if (outcome is WriteOutcome.Success) ChatMessageKind.WRITE_EVENT else ChatMessageKind.FAILURE, AiWording.writtenText(outcome, previewed.preview))
            }
            _uiState.update {
                it.copy(
                    ai = AiPanelState(
                        phase = AiPhase.DONE,
                        askedText = it.ai.askedText,
                        result = AiInteractionResult.Written(outcome, previewed.preview),
                        pendingOpen = (outcome as? WriteOutcome.Success)?.ref,
                        availability = it.ai.availability,
                    ),
                    session = null,
                )
            }
        }
    }

    /** Closes the result card — a preview, a candidate list, an explanation. The shown context goes with it. Never mid-write. The availability is asked again. */
    /** A preview dismissed or replaced takes its 「修正」 with it. */
    fun dismissAiResult() {
        val current = _uiState.value.ai
        if (current.phase == AiPhase.EXECUTING) return
        resultContext = AiResultContext.EMPTY
        // a preview that goes takes its 「修正」 with it (2026-09-24)
        askedAgain = null
        _uiState.update { it.copy(canEditAsked = false) }
        // a cancelled preview is something the user saw happen: one line in the transcript, nothing else
        val store = history
        val id = currentConversation.value
        if (store != null && id != null && current.result is AiInteractionResult.WritePreview) {
            viewModelScope.launch { store.append(id, ChatRole.ASSISTANT, ChatMessageKind.WRITE_EVENT, AiWording.CANCELLED) }
        }
        _uiState.update { it.copy(ai = AiPanelState(availability = it.ai.availability), session = null) }
        refreshAvailability()
    }

    // Phase 2 (docs/AI_RESOURCE_CONTROLLER.md): onCleared no longer unloads. Keep-warm outlives
    // the screen — leaving the chat, closing the stage or switching tabs costs no reload; the
    // resource controller's idle clock, memory pressure, thermal policy or an explicit model
    // switch do the unloading. (This supersedes Phase 3's screen-tied unload by the 2026-09-23 brief.)

    companion object {
        private const val TAG = "AiChat"
        const val KEY_INPUT = "chatInput"

        fun factory(
            ai: AiOrchestrator?,
            history: ConversationStore? = null,
            lastConversation: LastConversationStore? = null,
            templates: Flow<List<MemoTemplate>>? = null,
            models: ModelManager? = null,
            startFresh: Boolean = false,
            recent: RecentTemplateStore? = null,
            now: () -> Long = { System.currentTimeMillis() },
            pins: PinnedConversationStore? = null,
            templatePins: PinnedTemplateStore? = null,
            templateFolders: TemplateFolderStore? = null,
            chatDestination: ChatDestinationStore? = null,
            folderChoices: DocumentFolderChoices? = null,
            hints: ChatHintStore? = null,
            homeShortcuts: HomeShortcutStore? = null,
            templateRemover: TemplateRemover? = null,
            memoChoices: DocumentMemoChoices? = null,
            memoSelections: ChatMemoSelectionStore? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(createSavedStateHandle(), ai, history, lastConversation, templates, models, startFresh, recent, now, pins, templatePins, templateFolders, chatDestination, folderChoices, hints, homeShortcuts, templateRemover, memoChoices, memoSelections) }
        }
    }
}

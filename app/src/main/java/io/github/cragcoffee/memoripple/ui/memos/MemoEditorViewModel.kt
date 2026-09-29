package io.github.cragcoffee.memoripple.ui.memos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.cragcoffee.memoripple.data.MemoCommentEntity
import io.github.cragcoffee.memoripple.data.MemoContentStore
import io.github.cragcoffee.memoripple.data.OutlineStore
import io.github.cragcoffee.memoripple.domain.outline.OutlineDocument
import io.github.cragcoffee.memoripple.domain.outline.OutlinePhotos
import io.github.cragcoffee.memoripple.domain.outline.OutlineRows
import io.github.cragcoffee.memoripple.domain.outline.OutlineText
import io.github.cragcoffee.memoripple.domain.memos.MemoBlock
import io.github.cragcoffee.memoripple.domain.memos.MemoContent
import io.github.cragcoffee.memoripple.data.MemoCommentRepository
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoRepository
import io.github.cragcoffee.memoripple.data.TagEntity
import io.github.cragcoffee.memoripple.data.TagMutationResult
import io.github.cragcoffee.memoripple.data.TagRepository
import io.github.cragcoffee.memoripple.data.TemplateRepository
import io.github.cragcoffee.memoripple.data.AttachmentRepository
import io.github.cragcoffee.memoripple.data.PhotoAttachment
import io.github.cragcoffee.memoripple.data.PhotoReorderResult
import android.net.Uri
import io.github.cragcoffee.memoripple.domain.tags.TagNameIssue
import io.github.cragcoffee.memoripple.domain.comments.CommentAppearance
import io.github.cragcoffee.memoripple.domain.comments.CommentMotion
import io.github.cragcoffee.memoripple.domain.memos.LinkableMemo
import io.github.cragcoffee.memoripple.domain.memos.MemoLinkGraph
import io.github.cragcoffee.memoripple.domain.memos.MemoLinks
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplatePolicy
import io.github.cragcoffee.memoripple.domain.memos.MemoLifecycleState
import io.github.cragcoffee.memoripple.domain.memos.lifecycleState
import io.github.cragcoffee.memoripple.domain.playback.CommentAnimationState
import io.github.cragcoffee.memoripple.domain.playback.CommentAnimator
import io.github.cragcoffee.memoripple.domain.playback.MemoPlaybackTimelineFactory
import io.github.cragcoffee.memoripple.domain.playback.PlaybackItem
import io.github.cragcoffee.memoripple.domain.playback.PlaybackContentMode
import io.github.cragcoffee.memoripple.domain.playback.PlaybackTimeline
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.domain.speech.SpeechContentComposer
import io.github.cragcoffee.memoripple.speech.SpeechController
import io.github.cragcoffee.memoripple.speech.SpeechState
import io.github.cragcoffee.memoripple.speech.SpeechPlaybackCoordinator
import java.text.Collator
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SaveStatus { IDLE, SAVING, SAVED }

data class MemoEditorUiState(
    val title: String = "",
    val body: String = "",
    val isPinned: Boolean = false,
    val isLoading: Boolean = true,
    val saveStatus: SaveStatus = SaveStatus.IDLE,
    val exists: Boolean = false,
    val lifecycleState: MemoLifecycleState = MemoLifecycleState.ACTIVE,
    val isImportingPhotos: Boolean = false,
    /**
     * Whether this memo is an episode of a note. An episode is read where its note is, so the one
     * action that would move it somewhere else on its own — archiving — is not offered here.
     */
    val isEpisode: Boolean = false,
    /** The note an episode belongs to; null for a memo that stands on its own. */
    val noteId: Long? = null,
    /**
     * A memo's blocks (docs/MEMO_CONTENT_BLOCKS.md) with the texts as they are being written; empty
     * for an outline and before a memo exists. [body] is always their projection.
     */
    val blocks: List<MemoBlock> = emptyList(),
    /** The text block being written — it takes the part the one body field always had. */
    val activeTextId: Long? = null,
    /** Moves on every change of the blocks' shape: a photo added, moved or deleted, texts joined. */
    val structureVersion: Int = 0,
    /** Where the caret goes after such a change: (text block, offset); consumed by the screen. */
    val pendingCaret: Pair<Long, Int>? = null,
    /**
     * An outline's lines with their lasting ids (Room 28, docs/OUTLINE_STABLE_ROWS.md); null for a
     * memo. [body] is always its projection.
     */
    val outline: OutlineDocument? = null,
    /**
     * The outline was written elsewhere (the AI, the split pane…) after this editor last read or
     * wrote it: nothing more is saved from here until the latest is read again (§9).
     */
    val outlineConflict: Boolean = false,
    /**
     * Photos just added to an outline, waiting for the outliner to place them as photo rows where
     * the writing is (docs/OUTLINE_PHOTO_ROWS.md); the placing is an edit like any other.
     */
    val pendingOutlinePhotos: List<Long> = emptyList(),
) {
    /** A memo with photos is written block by block; one without is the one body field it always was. */
    val blockMode: Boolean get() = blocks.any { it is MemoBlock.Photo }

    /** What the body field edits: the active text in block mode, the whole body otherwise. */
    val activeText: String get() = if (!blockMode) body else
        (blocks.firstOrNull { it.id == activeTextId && it is MemoBlock.Text } as MemoBlock.Text?)?.text ?: body
}

@OptIn(ExperimentalCoroutinesApi::class)
class MemoEditorViewModel(
    private val repository: MemoRepository,
    private val commentRepository: MemoCommentRepository,
    private val memoId: Long,
    private val playbackTimelineFactory: MemoPlaybackTimelineFactory =
        MemoPlaybackTimelineFactory(),
    private val commentAnimator: CommentAnimator = CommentAnimator(),
    private val speechController: SpeechController,
    private val speechContentComposer: SpeechContentComposer = SpeechContentComposer(),
    private val tagRepository: TagRepository,
    private val attachmentRepository: AttachmentRepository,
    private val templateRepository: TemplateRepository,
    private val settingsRepository: io.github.cragcoffee.memoripple.data.SettingsRepository? = null,
    /** The folder a memo made here is filed in — only ever read when the row is first created. */
    private val initialFolderId: Long? = null,
    /** A memo's blocks; null for the outliner, which has none. */
    private val contentStore: MemoContentStore? = null,
    /** An outline's rows; given by the outliner only. */
    private val outlineStore: OutlineStore? = null,
    /**
     * The outliner opened on an outline ＋ just made: left with nothing in it, the outline is taken
     * back as if it had never been made (docs/OUTLINE_PHOTO_ROWS.md §8).
     */
    private val discardIfEmpty: Boolean = false,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MemoEditorUiState())
    val uiState: StateFlow<MemoEditorUiState> = _uiState.asStateFlow()
    val playbackState: StateFlow<CommentAnimationState> = commentAnimator.state
    val speechState: StateFlow<SpeechState> = speechController.state
    private val currentMemoId = MutableStateFlow<Long?>(null)
    val comments: StateFlow<List<MemoCommentEntity>> = currentMemoId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else commentRepository.observeForMemo(id)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val tags: StateFlow<List<TagEntity>> = currentMemoId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else tagRepository.observeTagsForMemo(id)
        }
        .map(::sortEditorTags)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val photos: StateFlow<List<PhotoAttachment>> = currentMemoId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else attachmentRepository.observeMemoPhotos(id)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _photoMessage = MutableStateFlow<String?>(null)
    val photoMessage: StateFlow<String?> = _photoMessage.asStateFlow()
    val allTags: StateFlow<List<TagEntity>> = tagRepository.observeAllTags()
        .map(::sortEditorTags)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _tagMessage = MutableStateFlow<String?>(null)
    val tagMessage: StateFlow<String?> = _tagMessage.asStateFlow()

    fun saveAsTemplate(name: String, onResult: (Boolean) -> Unit = {}) {
        val state = _uiState.value
        val cleanName = MemoTemplatePolicy.cleanName(name, state.title)
        if (!MemoTemplatePolicy.isUsable(cleanName, state.body)) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            templateRepository.save(
                MemoTemplate(UUID.randomUUID().toString(), cleanName, state.body),
            )
            onResult(true)
        }
    }

    /**
     * A template written inside the shortcut bar's テンプレート panel: a name and a body, judged
     * by the same policy as 「テンプレートとして保存」 and kept in the same store. It is a plain
     * paste template — no fields — so the panel that made it can insert it.
     */
    fun createTemplate(name: String, body: String, onResult: (Boolean) -> Unit = {}) {
        val cleanName = MemoTemplatePolicy.cleanName(name)
        if (!MemoTemplatePolicy.isUsable(cleanName, body)) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            templateRepository.save(MemoTemplate(UUID.randomUUID().toString(), cleanName, body))
            onResult(true)
        }
    }

    fun deleteTemplate(id: String) {
        viewModelScope.launch { templateRepository.delete(id) }
    }

    /** Every other memo, which is what a title has to be matched against to become a link. */
    private val linkableMemos: StateFlow<List<LinkableMemo>> = repository.observeMemos("")
        .map { memos -> memos.map { LinkableMemo(it.id, it.title, it.body) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val linkTargets: StateFlow<List<LinkableMemo>> = combine(
        linkableMemos,
        currentMemoId,
    ) { memos, id -> memos.filter { it.id != id && it.title.isNotBlank() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Only body-only CREATE templates are pasted into an editor; a Template v2 with fields or another action runs from チャット. */
    val templates: StateFlow<List<MemoTemplate>> = templateRepository.templates
        .map { list -> list.filter { it.action == io.github.cragcoffee.memoripple.domain.memos.TemplateAction.CREATE && it.fields.isEmpty() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val links: StateFlow<MemoLinks> = combine(
        _uiState.map { it.title to it.body }.distinctUntilChanged(),
        currentMemoId,
        linkableMemos,
    ) { content, id, memos ->
        MemoLinkGraph.of(LinkableMemo(id ?: 0L, content.first, content.second), memos)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoLinks())

    private var currentMemo: MemoEntity? = null
    private var saveJob: Job? = null
    private var contentRevision = 0L
    private var savedRevision = 0L
    private val saveMutex = Mutex()
    private val speechPlaybackCoordinator = SpeechPlaybackCoordinator(
        speechController = speechController,
        stopCommentPlayback = commentAnimator::stop,
    )

    init {
        viewModelScope.launch {
            currentMemo = if (memoId > 0) repository.findById(memoId) else null
            currentMemoId.value = currentMemo?.id
            // A memo's blocks arrive with it, in the same state: a page drawn first without them
            // would put the photos in above what is already on screen.
            val initialBlocks = currentMemo?.let { memo -> contentStore?.materialize(memo.id) }.orEmpty()
            // An outline arrives with its lines' lasting ids; the body is their projection.
            val initialOutline = currentMemo?.let { memo -> outlineStore?.materialize(memo.id) }
            _uiState.value = currentMemo?.let {
                MemoEditorUiState(
                    title = it.title,
                    isPinned = it.isPinned,
                    isLoading = false,
                    saveStatus = SaveStatus.SAVED,
                    exists = true,
                    lifecycleState = it.lifecycleState,
                    isEpisode = it.noteId != null,
                    noteId = it.noteId,
                    blocks = initialBlocks,
                    activeTextId = initialBlocks.lastOrNull { block -> block is MemoBlock.Text }?.id,
                    body = when {
                        initialBlocks.isNotEmpty() -> MemoContent.projection(initialBlocks)
                        initialOutline != null -> OutlineText.serialize(initialOutline)
                        else -> it.body
                    },
                    outline = initialOutline,
                )
            } ?: MemoEditorUiState(isLoading = false)
        }
    }

    /**
     * The memo's blocks from the store (stored as read, so each has an id). [keepText] is the
     * single text a just-created memo is still being typed into: the in-memory words win.
     */
    private suspend fun adoptStoredBlocks(memoId: Long, keepText: String?, activate: Long? = null, caret: Pair<Long, Int>? = null, structural: Boolean = false) {
        val store = contentStore ?: return
        val stored = store.materialize(memoId)
        if (stored.isEmpty()) return
        val blocks = if (keepText != null && stored.size == 1) listOf(MemoBlock.Text(stored.single().id, keepText)) else stored
        val state = _uiState.value
        val texts = blocks.filterIsInstance<MemoBlock.Text>()
        val active = activate?.takeIf { id -> texts.any { it.id == id } }
            ?: state.activeTextId?.takeIf { id -> texts.any { it.id == id } }
            ?: texts.last().id
        _uiState.value = state.copy(
            blocks = blocks,
            body = if (keepText != null) state.body else MemoContent.projection(blocks),
            activeTextId = active,
            structureVersion = if (structural) state.structureVersion + 1 else state.structureVersion,
            pendingCaret = caret ?: state.pendingCaret,
        )
    }

    /** Whatever is typed but not yet stored, stored now — before the blocks change shape. */
    private suspend fun flushSave() {
        saveJob?.cancel()
        saveCurrent()
    }

    /** The body field moved to another text block. */
    fun activateText(id: Long) {
        val state = _uiState.value
        if (state.activeTextId == id || state.blocks.none { it.id == id && it is MemoBlock.Text }) return
        _uiState.value = state.copy(activeTextId = id)
    }

    fun consumePendingCaret() {
        if (_uiState.value.pendingCaret != null) _uiState.value = _uiState.value.copy(pendingCaret = null)
    }

    /** One text block's words, as written; the body becomes the projection. */
    fun updateBlockText(id: Long, text: String) {
        val state = _uiState.value
        val block = state.blocks.firstOrNull { it.id == id } as? MemoBlock.Text ?: return
        if (block.text == text) return
        commentAnimator.stop()
        speechController.stop()
        contentRevision += 1
        val blocks = state.blocks.map { if (it.id == id) MemoBlock.Text(id, text) else it }
        _uiState.value = state.copy(
            blocks = blocks,
            body = MemoContent.projection(blocks),
            activeTextId = id,
            saveStatus = SaveStatus.IDLE,
        )
        scheduleSave()
    }

    /** Backspace at the start of a text whose previous block is text: the two become one. */
    fun mergeWithPrevious(textBlockId: Long) {
        val store = contentStore ?: return
        val id = currentMemoId.value ?: return
        viewModelScope.launch {
            flushSave()
            val merge = store.mergeWithPrevious(id, textBlockId, emptyMap()) ?: return@launch
            adoptStoredBlocks(id, keepText = null, activate = merge.textBlockId, caret = merge.textBlockId to merge.caret, structural = true)
        }
    }

    fun updateTitle(value: String) {
        if (value != _uiState.value.title) speechController.stop()
        if (value == _uiState.value.title) return
        contentRevision += 1
        _uiState.value = _uiState.value.copy(title = value, saveStatus = SaveStatus.IDLE)
        scheduleSave()
    }

    /**
     * The outliner's change: the whole outline with its lines' ids. The body becomes its
     * projection; the save writes each line by its id (docs/OUTLINE_STABLE_ROWS.md).
     */
    fun updateOutline(document: OutlineDocument) {
        val state = _uiState.value
        if (state.outline == null) {
            updateBody(OutlineText.serialize(document))
            return
        }
        if (document == state.outline || state.outlineConflict) return
        val body = OutlineText.serialize(document)
        if (body != state.body) {
            commentAnimator.stop()
            speechController.stop()
        }
        contentRevision += 1
        _uiState.value = state.copy(body = body, outline = document, saveStatus = SaveStatus.IDLE)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MILLIS)
            saveCurrent()
        }
    }

    fun updateBody(value: String) {
        // An outline's lines keep their ids: a whole new body (a task ticked on the reading
        // page) is laid onto them line by line.
        _uiState.value.outline?.let { outline ->
            if (value == _uiState.value.body) return
            val rows = OutlineRows.reconcile(OutlineRows.rowsOf(outline), value, outline.nextId)
            updateOutline(OutlineRows.documentOf(rows, floor = outline.nextId))
            return
        }
        // A memo's words live in its blocks: the body field writes the text being written.
        val blocksNow = _uiState.value
        val textId = when {
            blocksNow.blockMode -> blocksNow.activeTextId
            blocksNow.blocks.size == 1 -> blocksNow.blocks.single().id
            else -> null
        }
        if (textId != null) {
            updateBlockText(textId, value)
            return
        }
        if (value == _uiState.value.body) return
        if (value != _uiState.value.body) {
            commentAnimator.stop()
            speechController.stop()
        }
        contentRevision += 1
        _uiState.value = _uiState.value.copy(body = value, saveStatus = SaveStatus.IDLE)
        scheduleSave()
    }

    fun createPlaybackTimeline(
        contentMode: PlaybackContentMode,
        appSettings: AppSettings = AppSettings.Default,
    ): PlaybackTimeline {
        return playbackTimelineFactory.create(
            memoBody = _uiState.value.body,
            userComments = comments.value,
            contentMode = contentMode,
            appSettings = appSettings,
        )
    }

    fun saveForOverlay(onComplete: (Long?) -> Unit) {
        commentAnimator.stop()
        speechController.stop()
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            saveCurrent()
            onComplete(currentMemoId.value)
        }
    }

    fun addComment(
        text: String,
        appearance: CommentAppearance,
        motion: CommentMotion,
        onComplete: (Boolean) -> Unit,
    ) {
        commentAnimator.stop()
        speechController.stop()
        val id = currentMemoId.value
        if (id == null || text.isBlank()) {
            onComplete(false)
            return
        }
        viewModelScope.launch {
            val saved = commentRepository.add(
                memoId = id,
                text = text,
                now = System.currentTimeMillis(),
                appearance = appearance,
                motion = motion,
            )
            onComplete(saved != null)
        }
    }

    fun updateCommentExpression(
        comment: MemoCommentEntity,
        appearance: CommentAppearance,
        motion: CommentMotion,
        onComplete: (Boolean) -> Unit,
    ) {
        commentAnimator.stop()
        speechController.stop()
        viewModelScope.launch {
            onComplete(commentRepository.updateExpression(comment, appearance, motion))
        }
    }

    fun deleteComment(comment: MemoCommentEntity) {
        commentAnimator.stop()
        speechController.stop()
        viewModelScope.launch { commentRepository.delete(comment) }
    }

    fun attachTag(tagId: Long) {
        val id = currentMemoId.value ?: return
        viewModelScope.launch { tagRepository.attach(id, tagId) }
    }

    fun detachTag(tagId: Long) {
        val id = currentMemoId.value ?: return
        viewModelScope.launch { tagRepository.detach(id, tagId) }
    }

    fun createAndAttachTag(name: String, onComplete: (Boolean) -> Unit) {
        val id = currentMemoId.value
        if (id == null) {
            _tagMessage.value = "メモの保存後にタグを追加できます"
            onComplete(false)
            return
        }
        viewModelScope.launch {
            when (val result = tagRepository.create(name, System.currentTimeMillis())) {
                is TagMutationResult.Success -> {
                    result.tag?.let { tagRepository.attach(id, it.id) }
                    onComplete(true)
                }
                TagMutationResult.Duplicate -> {
                    _tagMessage.value = "同じ名前のタグがあります"
                    onComplete(false)
                }
                is TagMutationResult.InvalidName -> {
                    _tagMessage.value = when (result.issue) {
                        TagNameIssue.BLANK -> "タグ名を入力してください"
                        TagNameIssue.TOO_LONG -> "タグ名は40文字以内で入力してください"
                    }
                    onComplete(false)
                }
                TagMutationResult.Missing -> onComplete(false)
            }
        }
    }

    fun clearTagMessage() {
        _tagMessage.value = null
    }

    /**
     * Where the caret stood in the text being written when the photo button was pressed (null:
     * not writing — the photos go after that text). Read once by the next [addPhotos].
     */
    private var photoCaret: Int? = null

    fun rememberPhotoCaret(caret: Int?) {
        photoCaret = caret
    }

    fun addPhotos(uris: List<Uri>) {
        val caret = photoCaret
        photoCaret = null
        if (uris.isEmpty() || _uiState.value.isImportingPhotos) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImportingPhotos = true)
            val id = ensureMemoExists()
            if (id == null) {
                _photoMessage.value = "写真を追加できませんでした"
                _uiState.value = _uiState.value.copy(isImportingPhotos = false)
                return@launch
            }
            // An outline places its photos itself, as photo rows where the writing is.
            if (_uiState.value.outline != null) {
                // The 20-photo cap counts the photo rows the outline shows (and those about to be
                // placed) — never a photo kept only for 元に戻す (docs/OUTLINE_PHOTO_ROWS.md §7.3).
                val active = OutlinePhotos.activeCount(_uiState.value.outline, _uiState.value.pendingOutlinePhotos)
                val result = attachmentRepository.importMemoPhotos(id, uris, activeCount = active)
                // A picture whose row was taken out in this session (kept for 元に戻す) is shown
                // again where the writing is, rather than refused as already there; one a row
                // shows is still refused as already there.
                val placed = OutlinePhotos.toPlace(result.addedIds, result.duplicateIds, _uiState.value.outline, _uiState.value.pendingOutlinePhotos)
                _uiState.value = _uiState.value.copy(
                    isImportingPhotos = false,
                    pendingOutlinePhotos = _uiState.value.pendingOutlinePhotos + placed,
                )
                _photoMessage.value = when {
                    placed.isNotEmpty() && result.rejected > 0 -> "${placed.size}枚追加しました。${result.rejected}枚は追加できませんでした。"
                    placed.isNotEmpty() -> null
                    result.duplicates > 0 && result.rejected == 0 -> "同じ写真は追加済みです"
                    result.limitReached -> "写真は20枚まで追加できます"
                    else -> "この写真は追加できませんでした"
                }
                return@launch
            }
            // The words typed so far are stored first; the photos go where the caret was in the
            // text being written (its end when it was not being written), and writing goes on in
            // the text below them.
            flushSave()
            val state = _uiState.value
            val writing = state.takeIf { it.blocks.isNotEmpty() }
                ?.let { it.activeTextId ?: it.blocks.last { b -> b is MemoBlock.Text }.id }
            val at = caret?.takeIf { writing != null && writing == state.activeTextId }
            val result = attachmentRepository.importMemoPhotos(id, uris, writingTextId = writing, caret = at)
            _uiState.value = _uiState.value.copy(isImportingPhotos = false)
            if (result.added > 0) {
                val next = result.writingTextId
                adoptStoredBlocks(id, keepText = null, activate = next, caret = next?.let { it to 0 }, structural = true)
            }
            _photoMessage.value = when {
                result.added > 0 && result.rejected > 0 ->
                    "${result.added}枚追加しました。${result.rejected}枚は追加できませんでした。"
                result.added > 0 -> "${result.added}枚の写真を追加しました"
                result.duplicates > 0 && result.rejected == 0 -> "同じ写真は追加済みです"
                result.limitReached -> "写真は20枚まで追加できます"
                else -> "この写真は追加できませんでした"
            }
        }
    }

    fun deletePhoto(photo: PhotoAttachment) {
        val id = currentMemoId.value ?: return
        viewModelScope.launch {
            flushSave()
            attachmentRepository.deleteMemoPhoto(id, photo.id)
            adoptStoredBlocks(id, keepText = null, structural = true)
        }
    }

    fun reorderPhotos(orderedAttachmentIds: List<Long>, onComplete: (Boolean) -> Unit) {
        val id = currentMemoId.value
        if (id == null || _uiState.value.lifecycleState != MemoLifecycleState.ACTIVE ||
            _uiState.value.isImportingPhotos
        ) {
            _photoMessage.value = "写真の並べ替えを保存できませんでした"
            onComplete(false)
            return
        }
        viewModelScope.launch {
            flushSave()
            val result = runCatching {
                attachmentRepository.reorderMemoPhotos(id, orderedAttachmentIds)
            }.getOrDefault(PhotoReorderResult.REJECTED)
            adoptStoredBlocks(id, keepText = null, structural = true)
            val success = result != PhotoReorderResult.REJECTED
            if (!success) _photoMessage.value = "写真の並べ替えを保存できませんでした"
            onComplete(success)
        }
    }

    fun clearPhotoMessage() {
        _photoMessage.value = null
    }

    /** A message about the photos the editor itself decided on (the outliner's photo cap). */
    fun showPhotoMessage(message: String) {
        _photoMessage.value = message
    }

    fun beginCommentReorder() {
        commentAnimator.stop()
        speechController.stop()
    }

    fun reorderComments(orderedCommentIds: List<Long>) {
        commentAnimator.stop()
        speechController.stop()
        val id = currentMemoId.value ?: return
        viewModelScope.launch { commentRepository.reorder(id, orderedCommentIds) }
    }

    fun resetCommentOrder() {
        commentAnimator.stop()
        speechController.stop()
        val id = currentMemoId.value ?: return
        viewModelScope.launch { commentRepository.resetToCreationOrder(id) }
    }

    fun playTimeline(timeline: PlaybackTimeline) {
        speechPlaybackCoordinator.startCommentPlayback { commentAnimator.play(timeline) }
    }

    fun startSpeech(includeComments: Boolean): Boolean {
        return speechPlaybackCoordinator.startSpeech(
            speechContentComposer.memo(
                title = _uiState.value.title,
                body = _uiState.value.body,
                comments = comments.value,
                includeComments = includeComments,
                readRubyReadings = cachedReadRuby,
            ),
        )
    }

    /** コメントリンクの発火: the linked comment whose marker reading just passed or tapped. */
    val linkedCommentFires =
        kotlinx.coroutines.flow.MutableSharedFlow<MemoCommentEntity>(extraBufferCapacity = 16)

    /** Hands the comment carrying [number] to the surface, if it still exists. */
    fun fireLinkedComment(number: Int) {
        comments.value.firstOrNull { it.linkNo == number }?.let(linkedCommentFires::tryEmit)
    }

    /** The linkNo numbers that still belong to a living comment — the reading view's map. */
    fun linkedNumbers(): Set<Int> = comments.value.mapNotNull(MemoCommentEntity::linkNo).toSet()

    fun linkComment(comment: MemoCommentEntity, onDone: (Int?) -> Unit) {
        val id = currentMemoId.value ?: return onDone(null)
        viewModelScope.launch { onDone(commentRepository.assignLink(id, comment.id)) }
    }

    fun unlinkComment(comment: MemoCommentEntity) {
        viewModelScope.launch { commentRepository.clearLink(comment.id) }
    }

    /**
     * 閲覧モードの読み上げ, marker-aware: the body is stripped and planned so each [R n]
     * fires exactly when the reading passes it. Mode is auto-detected once per device —
     * engines that report ranges keep the seamless large chunks, the rest get the body cut
     * at the markers — and remembered in a device-local flag.
     */
    fun startSpeechWithLinks(includeComments: Boolean, fromLine: Int = 0): Boolean {
        val body = _uiState.value.body
        lastIncludeComments = includeComments
        // Order of truth: the line pass keeps text speakable with the markers still in it,
        // then the markers come off carrying their exact offsets in the spoken text. The
        // lines are kept one to one with the raw body — no trimming — so a spoken offset
        // always names the source line under it, which is what the follow-along cursor reads.
        // A tapped line re-aims the voice: reading starts at [fromLine], and everything
        // before it — markers included — stays quiet, the way a jump is not a pass.
        val allLines = preprocessLinesKeepingMarkers(body)
        val baseLine = fromLine.coerceIn(0, (allLines.size - 1).coerceAtLeast(0))
        val readableLines = allLines.drop(baseLine)
        val stripped = io.github.cragcoffee.memoripple.domain.comments.CommentLinkMarkers
            .strip(readableLines.joinToString("\n"))
        if (stripped.text.isBlank() && stripped.markers.isEmpty()) {
            return if (baseLine == 0) startSpeech(includeComments) else false
        }

        // Each line's first offset in the stripped body: strip is line-local (a marker never
        // spans a newline), so the stripped lines concatenate back to the stripped body.
        val lineStarts = IntArray(readableLines.size)
        var nextLineStart = 0
        readableLines.forEachIndexed { index, line ->
            lineStarts[index] = nextLineStart
            nextLineStart += io.github.cragcoffee.memoripple.domain.comments.CommentLinkMarkers
                .strip(line).text.length + 1
        }
        fun lineFor(offset: Int): Int {
            var candidate = 0
            for (index in lineStarts.indices) {
                if (lineStarts[index] > offset) break
                candidate = index
            }
            return baseLine + candidate
        }

        val supportFlag = rangeSupportFlag()
        val mode = if (supportFlag == "no") {
            io.github.cragcoffee.memoripple.domain.speech.LinkedCommentSpeechPlan.Mode.PIECES
        } else {
            io.github.cragcoffee.memoripple.domain.speech.LinkedCommentSpeechPlan.Mode.RANGE
        }
        val plan = io.github.cragcoffee.memoripple.domain.speech.LinkedCommentSpeechPlan.plan(
            mode = mode,
            // The title rides ahead only of a reading from the top; a jump lands mid-page.
            preambleTexts = if (baseLine == 0) listOf(_uiState.value.title.trim()) else emptyList(),
            body = stripped,
            maxInputLength = 3_000,
        )
        val postTexts = if (includeComments) {
            comments.value.filter { it.linkNo == null }
                .sortedWith(compareBy(MemoCommentEntity::playbackOrder, MemoCommentEntity::id))
                .map { it.text.trim() }
                .filter(String::isNotBlank)
        } else {
            emptyList()
        }
        val observer = object : io.github.cragcoffee.memoripple.speech.SpeechSessionObserver {
            override fun onSegmentStart(index: Int) {
                // Body segments move the cursor to their line; the title and the comments
                // after the body take it away — there is nothing on the page to point at.
                speechFollowLine.value = plan.bodyOffsetOf(index)?.let(::lineFor)
                plan.onSegmentStart(index).forEach(::fireLinkedComment)
            }

            override fun onSegmentRange(index: Int, startInclusive: Int) {
                plan.bodyOffsetOf(index, startInclusive)?.let {
                    speechFollowLine.value = lineFor(it)
                }
                plan.onSegmentRange(index, startInclusive).forEach(::fireLinkedComment)
            }

            override fun onFinished() {
                plan.onFinished().forEach(::fireLinkedComment)
                speechFollowLine.value = null
                recordRangeSupport(plan.sawRange, mode)
            }
        }
        return speechPlaybackCoordinator.startSpeechSegments(plan.segments + postTexts, observer)
    }

    /**
     * 読み上げ追従: the raw-body line the voice is reading right now, or null while it reads
     * the title or the comments — or nothing at all. Written from the engine's thread, read
     * by the reading surface.
     */
    val speechFollowLine = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)

    /** What the running reading was asked to include, so a mid-page jump keeps the choice. */
    @Volatile
    private var lastIncludeComments: Boolean = false

    /**
     * A tap on a line while the voice is engaged re-aims it there; a silent page stays
     * silent, so a stray touch never starts a narration.
     */
    fun jumpSpeechToLine(line: Int): Boolean {
        val status = speechController.state.value.status
        val engaged = status == io.github.cragcoffee.memoripple.speech.SpeechStatus.SPEAKING ||
            status == io.github.cragcoffee.memoripple.speech.SpeechStatus.INITIALIZING
        if (!engaged) return false
        return startSpeechWithLinks(lastIncludeComments, fromLine = line)
    }

    /** The preprocessor's line pass, one readable line per raw line, markers kept. */
    private fun preprocessLinesKeepingMarkers(body: String): List<String> = body.lines()
        .map { rawLine ->
            io.github.cragcoffee.memoripple.domain.WorkCommentSyntax
                .recognize(rawLine)?.text ?: rawLine
        }
        // 行末修飾子 are never read aloud; [R n] markers stay for the offset table.
        .map { line ->
            io.github.cragcoffee.memoripple.domain.comments.CommentLineModifiers
                .strip(line).text
        }
        .map { line ->
            io.github.cragcoffee.memoripple.domain.BodyText
                .spokenKeepingMarkers(line, cachedReadRuby)
        }

    @Volatile
    private var cachedRangeSupport: String = ""

    @Volatile
    private var cachedReadRuby: Boolean = false

    init {
        settingsRepository?.let { repository ->
            viewModelScope.launch {
                repository.speechRangeSupport.collect { cachedRangeSupport = it }
            }
            viewModelScope.launch {
                repository.speechReadRuby.collect { cachedReadRuby = it }
            }
        }
    }

    private fun rangeSupportFlag(): String = cachedRangeSupport

    private fun recordRangeSupport(
        sawRange: Boolean,
        mode: io.github.cragcoffee.memoripple.domain.speech.LinkedCommentSpeechPlan.Mode,
    ) {
        val repository = settingsRepository ?: return
        if (mode != io.github.cragcoffee.memoripple.domain.speech.LinkedCommentSpeechPlan.Mode.RANGE) {
            return
        }
        viewModelScope.launch {
            repository.setSpeechRangeSupport(if (sawRange) "yes" else "no")
        }
    }

    /** One linked comment as its own tiny timeline, for the reading surface to flow now. */
    fun createSingleCommentTimeline(
        commentId: Long,
        appSettings: AppSettings = AppSettings.Default,
    ): PlaybackTimeline? {
        val comment = comments.value.firstOrNull { it.id == commentId } ?: return null
        return playbackTimelineFactory.createForSingleComment(comment, appSettings)
    }

    fun stopSpeech() {
        speechFollowLine.value = null
        speechPlaybackCoordinator.stopSpeech()
    }

    fun clearSpeechMessage() = speechController.clearMessage()

    fun pausePlayback() = commentAnimator.pause()

    fun resumePlayback() = commentAnimator.resume()

    fun stopPlayback() = speechPlaybackCoordinator.stopCommentPlayback()

    fun advancePlayback(deltaMillis: Long) = commentAnimator.advanceBy(deltaMillis)

    fun playbackOffsetPx(
        item: PlaybackItem,
        elapsedMillis: Long,
        containerWidthPx: Float,
        commentWidthPx: Float,
    ): Float = commentAnimator.horizontalOffsetPx(
        item = item,
        elapsedMillis = elapsedMillis,
        containerWidthPx = containerWidthPx,
        commentWidthPx = commentWidthPx,
    )

    fun saveNow() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { saveCurrent() }
    }

    fun setPinned(pinned: Boolean) {
        val memo = currentMemo ?: return
        viewModelScope.launch {
            saveMutex.withLock {
                if (repository.setPinned(memo.id, pinned)) {
                    currentMemo = currentMemo?.copy(isPinned = pinned)
                    _uiState.value = _uiState.value.copy(isPinned = pinned)
                }
            }
        }
    }

    fun saveAndThen(onComplete: () -> Unit) {
        commentAnimator.stop()
        speechController.stop()
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            saveCurrent()
            releaseUnshownOutlinePhotos()
            discardEmptyNewOutline()
            onComplete()
        }
    }

    /**
     * Leaving an outline ＋ just made with nothing in it: it is taken back as if it had never been
     * made — only when everything is saved, in no conflict and no photo is waiting to be placed
     * (what the database holds is then what the screen shows); the store checks again that there
     * is no word, photo, comment, tag or title before anything is removed.
     */
    private suspend fun discardEmptyNewOutline() {
        if (!discardIfEmpty) return
        val store = outlineStore ?: return
        val state = _uiState.value
        val id = currentMemoId.value ?: return
        if (state.outline == null || state.outlineConflict || contentRevision != savedRevision) return
        if (state.pendingOutlinePhotos.isNotEmpty()) return
        if (store.discardIfEmpty(id)) {
            currentMemo = null
            currentMemoId.value = null
        }
    }

    /** The outliner placed the photos just added; nothing is waiting any more. */
    fun consumeOutlinePhotos() {
        if (_uiState.value.pendingOutlinePhotos.isNotEmpty()) _uiState.value = _uiState.value.copy(pendingOutlinePhotos = emptyList())
    }

    /**
     * Leaving the outliner ends its undo: the photos no photo row shows any more are let go —
     * only when everything is saved and in no conflict (docs/OUTLINE_PHOTO_ROWS.md §3). A failed
     * or refused save, words still unsaved, a process that dies first: nothing is removed, and a
     * photo a row shows is never removed.
     */
    private suspend fun releaseUnshownOutlinePhotos() {
        val store = outlineStore ?: return
        val state = _uiState.value
        val id = currentMemoId.value ?: return
        if (state.outline == null || state.outlineConflict || contentRevision != savedRevision) return
        if (state.pendingOutlinePhotos.isNotEmpty()) return
        attachmentRepository.releaseUnshownOutlinePhotos(id) { store.unshownPhotos(id) }
    }

    fun archive(onComplete: () -> Unit) {
        commentAnimator.stop()
        speechController.stop()
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            saveCurrent()
            val memo = currentMemo
            if (memo != null && repository.archive(memo.id)) onComplete()
        }
    }

    fun unarchive(onComplete: () -> Unit) {
        commentAnimator.stop()
        speechController.stop()
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            saveCurrent()
            val memo = currentMemo
            if (memo != null && repository.unarchive(memo.id)) onComplete()
        }
    }

    fun moveToTrash(onComplete: () -> Unit) {
        commentAnimator.stop()
        speechController.stop()
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            saveCurrent()
            val memo = currentMemo
            if (memo != null && repository.moveToTrash(memo.id)) {
                currentMemo = null
                currentMemoId.value = null
                onComplete()
            }
        }
    }

    private fun scheduleSave() {
        if (_uiState.value.isLoading) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MILLIS)
            saveCurrent()
        }
    }

    private suspend fun saveCurrent() = saveMutex.withLock {
        val snapshot = _uiState.value
        if (snapshot.isLoading) return@withLock
        // An editor that holds an older outline saves nothing until the latest is read again.
        if (snapshot.outlineConflict) return@withLock
        if (currentMemo != null && contentRevision == savedRevision) return@withLock
        if (currentMemo == null && snapshot.title.isBlank() && snapshot.body.isBlank()) {
            return@withLock
        }
        val savingRevision = contentRevision
        _uiState.value = snapshot.copy(saveStatus = SaveStatus.SAVING)
        // The write and its bookkeeping are one indivisible step. Every save entry point
        // cancels the previous job, and a cancellation landing between the INSERT committing
        // and `currentMemo` learning about the row would leave the memo looking unsaved —
        // the very next save would then insert a duplicate. Once the write starts, it and
        // its bookkeeping finish.
        withContext(NonCancellable) {
            val created = currentMemo == null
            val existing = currentMemo
            if (existing != null && snapshot.outline != null) {
                saveOutline(existing, snapshot, savingRevision)
                return@withContext
            }
            // A memo's texts are saved block by block; the stored body is their projection.
            currentMemo = (if (existing != null && snapshot.blocks.isNotEmpty()) {
                repository.saveBlocks(
                    existing,
                    snapshot.title,
                    snapshot.blocks.filterIsInstance<MemoBlock.Text>().filter { it.id > 0 }.associate { it.id to it.text },
                    System.currentTimeMillis(),
                )
            } else {
                null
            }) ?: repository.save(
                existing = currentMemo,
                title = snapshot.title,
                body = snapshot.body,
                now = System.currentTimeMillis(),
                folderId = initialFolderId,
            )
            if (created) currentMemo?.let { adoptStoredBlocks(it.id, keepText = _uiState.value.body) }
            currentMemoId.value = currentMemo?.id
            savedRevision = savingRevision
            _uiState.value = _uiState.value.copy(
                saveStatus = if (contentRevision == savedRevision) SaveStatus.SAVED else SaveStatus.IDLE,
                exists = currentMemo != null,
                isPinned = currentMemo?.isPinned ?: false,
                lifecycleState = currentMemo?.lifecycleState ?: MemoLifecycleState.ACTIVE,
                isEpisode = currentMemo?.noteId != null,
                noteId = currentMemo?.noteId,
            )
        }
    }

    /**
     * An outline's save (docs/OUTLINE_STABLE_ROWS.md §9): its lines by id, only if the outline is
     * still the version this editor last read or wrote. Another writer in between is a conflict —
     * nothing is written, the editor's words stay on screen and saving stops; never the last
     * writer winning, never a merge.
     */
    private suspend fun saveOutline(existing: MemoEntity, snapshot: MemoEditorUiState, savingRevision: Long) {
        val outline = snapshot.outline ?: return
        when (val result = repository.saveOutline(existing, snapshot.title, outline, System.currentTimeMillis())) {
            is OutlineStore.Save.Saved -> {
                currentMemo = repository.findById(existing.id)
                savedRevision = savingRevision
                _uiState.value = _uiState.value.copy(
                    saveStatus = if (contentRevision == savedRevision) SaveStatus.SAVED else SaveStatus.IDLE,
                )
            }
            OutlineStore.Save.Conflict -> {
                saveJob?.cancel()
                _uiState.value = _uiState.value.copy(outlineConflict = true, saveStatus = SaveStatus.IDLE)
            }
            OutlineStore.Save.NotOutline -> _uiState.value = _uiState.value.copy(saveStatus = SaveStatus.IDLE)
        }
    }

    /**
     * 再読み込み after a conflict: the outline as it is stored now becomes the editor's, with its
     * version; what was typed here since the last save is set aside, as the notice said.
     */
    fun reloadOutline() {
        val store = outlineStore ?: return
        val id = currentMemoId.value ?: return
        saveJob?.cancel()
        viewModelScope.launch {
            saveMutex.withLock {
                val memo = repository.findById(id) ?: return@withLock
                val outline = store.materialize(id) ?: return@withLock
                currentMemo = memo
                savedRevision = contentRevision
                _uiState.value = _uiState.value.copy(
                    title = memo.title,
                    body = OutlineText.serialize(outline),
                    outline = outline,
                    outlineConflict = false,
                    saveStatus = SaveStatus.SAVED,
                )
            }
        }
    }

    private suspend fun ensureMemoExists(): Long? = saveMutex.withLock {
        currentMemo?.let { return@withLock it.id }
        val snapshot = _uiState.value
        currentMemo = if (snapshot.title.isBlank() && snapshot.body.isBlank()) {
            repository.createEmpty(System.currentTimeMillis(), initialFolderId)
        } else {
            repository.save(null, snapshot.title, snapshot.body, System.currentTimeMillis(), folderId = initialFolderId)
        }
        currentMemoId.value = currentMemo?.id
        savedRevision = contentRevision
        _uiState.value = _uiState.value.copy(
            exists = currentMemo != null,
            saveStatus = SaveStatus.SAVED,
        )
        currentMemo?.let { adoptStoredBlocks(it.id, keepText = _uiState.value.body) }
        currentMemo?.id
    }

    override fun onCleared() {
        commentAnimator.stop()
        speechController.stop()
        super.onCleared()
    }

    companion object {
        private const val AUTOSAVE_DELAY_MILLIS = 600L

        fun factory(
            repository: MemoRepository,
            commentRepository: MemoCommentRepository,
            memoId: Long,
            speechController: SpeechController,
            tagRepository: TagRepository,
            attachmentRepository: AttachmentRepository,
            templateRepository: TemplateRepository,
            settingsRepository: io.github.cragcoffee.memoripple.data.SettingsRepository? = null,
            initialFolderId: Long? = null,
            contentStore: MemoContentStore? = null,
            outlineStore: OutlineStore? = null,
            discardIfEmpty: Boolean = false,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MemoEditorViewModel(
                        repository,
                        commentRepository,
                        memoId,
                        speechController = speechController,
                        tagRepository = tagRepository,
                        attachmentRepository = attachmentRepository,
                        templateRepository = templateRepository,
                        settingsRepository = settingsRepository,
                        initialFolderId = initialFolderId,
                        contentStore = contentStore,
                        outlineStore = outlineStore,
                        discardIfEmpty = discardIfEmpty,
                    ) as T
            }
    }
}

private fun sortEditorTags(values: List<TagEntity>): List<TagEntity> {
    val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
    return values.sortedWith { left, right ->
        collator.compare(left.name, right.name).takeIf { it != 0 } ?: left.id.compareTo(right.id)
    }
}

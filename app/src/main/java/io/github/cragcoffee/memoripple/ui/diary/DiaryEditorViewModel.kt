package io.github.cragcoffee.memoripple.ui.diary

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.cragcoffee.memoripple.data.AttachmentRepository
import io.github.cragcoffee.memoripple.data.CreateFutureCommentResult
import io.github.cragcoffee.memoripple.data.DiaryContentStore
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.DiaryRejection
import io.github.cragcoffee.memoripple.data.DiaryRepository
import io.github.cragcoffee.memoripple.data.DiaryResult
import io.github.cragcoffee.memoripple.data.FutureCommentRejection
import io.github.cragcoffee.memoripple.data.FutureDiaryCommentRepository
import io.github.cragcoffee.memoripple.data.PhotoAttachment
import io.github.cragcoffee.memoripple.data.PhotoReorderResult
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.diary.DiaryStatePolicy
import io.github.cragcoffee.memoripple.domain.diary.FutureCommentExpression
import io.github.cragcoffee.memoripple.domain.diary.FutureDiaryCommentItem
import io.github.cragcoffee.memoripple.domain.memos.MemoBlock
import io.github.cragcoffee.memoripple.domain.memos.MemoContent
import io.github.cragcoffee.memoripple.domain.speech.SpeechContentComposer
import io.github.cragcoffee.memoripple.speech.SpeechController
import io.github.cragcoffee.memoripple.speech.SpeechState
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class DiarySaveStatus { IDLE, SAVING, SAVED }

data class DiaryEditorUiState(
    val diaryDate: LocalDate,
    val body: String = "",
    val state: DiaryState = DiaryState.DRAFT,
    val isLoading: Boolean = true,
    val saveStatus: DiarySaveStatus = DiarySaveStatus.IDLE,
    val exists: Boolean = false,
    val message: String? = null,
    val isImportingPhotos: Boolean = false,
    /** The entry's text and photos in their order (Room 27, docs/MEMO_CONTENT_BLOCKS.md §11). */
    val blocks: List<MemoBlock> = emptyList(),
    /** The text block being written. */
    val activeTextId: Long? = null,
    /** Moves when the blocks change shape (a photo added or deleted, two texts joined). */
    val structureVersion: Int = 0,
    /** Where the caret goes after a change of shape: (text block, offset). */
    val pendingCaret: Pair<Long, Int>? = null,
) {
    val isEditable: Boolean
        get() = !isLoading && exists && DiaryStatePolicy.canEdit(state)

    /** Photos in the entry: the page is written block by block. Without one it is the one field it always was. */
    val blockMode: Boolean get() = blocks.any { it is MemoBlock.Photo }

    /** The words of the text being written (the whole body for an entry without blocks). */
    val activeText: String
        get() = (blocks.firstOrNull { it.id == activeTextId } as? MemoBlock.Text)?.text
            ?: if (blocks.isEmpty()) body else ""
}

/**
 * One journal entry, opened by its id (HANDOFF §16.18). The date is read from the entry, never
 * passed in; there is no lifecycle to drive — a LOCKED entry (an old row) is read-only and
 * everything else is written the way a memo is. Saves go out only when the body actually
 * changed, so opening and leaving an entry never moves its `updatedAt`.
 */
class DiaryEditorViewModel(
    private val repository: DiaryRepository,
    private val futureRepository: FutureDiaryCommentRepository,
    private val entryId: Long,
    private val speechController: SpeechController,
    private val speechContentComposer: SpeechContentComposer = SpeechContentComposer(),
    private val attachmentRepository: AttachmentRepository,
    settingsRepository: io.github.cragcoffee.memoripple.data.SettingsRepository? = null,
    private val contentStore: DiaryContentStore? = null,
) : ViewModel() {

    @Volatile
    private var cachedReadRuby: Boolean = false

    init {
        settingsRepository?.let { repository ->
            viewModelScope.launch {
                repository.speechReadRuby.collect { cachedReadRuby = it }
            }
        }
    }

    private val _uiState = MutableStateFlow(DiaryEditorUiState(diaryDate = LocalDate.ofEpochDay(0)))
    val uiState: StateFlow<DiaryEditorUiState> = _uiState.asStateFlow()
    val speechState: StateFlow<SpeechState> = speechController.state
    private val currentEntryId = MutableStateFlow<Long?>(entryId)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val futureComments: StateFlow<List<FutureDiaryCommentItem>> = currentEntryId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else futureRepository.observeForDiary(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val photos: StateFlow<List<PhotoAttachment>> = currentEntryId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else attachmentRepository.observeDiaryPhotos(id)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var currentEntry: DiaryEntryEntity? = null
    /** The body as the store last saw it — the memo editor's revision guard, in words. */
    private var savedBody: String? = null
    /** The texts as the store last saw them, by block — the same guard for an entry in blocks. */
    private var savedTexts: Map<Long, String>? = null
    private var saveJob: Job? = null
    private val saveMutex = Mutex()

    init {
        viewModelScope.launch { loadCurrent() }
    }

    /** The words of the text being written (the one field of an entry without photos, or the active block). */
    fun updateBody(value: String) {
        val state = _uiState.value
        if (!state.isEditable) return
        val id = state.activeTextId
        if (state.blocks.isNotEmpty() && id != null) {
            updateBlockText(id, value)
            return
        }
        if (value != state.body) speechController.stop()
        _uiState.value = state.copy(body = value, saveStatus = DiarySaveStatus.IDLE)
        scheduleSave()
    }

    /** One text block's words, as written; the body becomes the projection. */
    fun updateBlockText(id: Long, text: String) {
        val state = _uiState.value
        if (!state.isEditable) return
        val block = state.blocks.firstOrNull { it.id == id } as? MemoBlock.Text ?: return
        if (block.text == text) return
        speechController.stop()
        val blocks = state.blocks.map { if (it.id == id) MemoBlock.Text(id, text) else it }
        _uiState.value = state.copy(
            blocks = blocks,
            body = MemoContent.projection(blocks),
            activeTextId = id,
            saveStatus = DiarySaveStatus.IDLE,
        )
        scheduleSave()
    }

    /** The field moved to another text block. */
    fun activateText(id: Long) {
        val state = _uiState.value
        if (state.activeTextId == id || state.blocks.none { it.id == id && it is MemoBlock.Text }) return
        _uiState.value = state.copy(activeTextId = id)
    }

    fun consumePendingCaret() {
        if (_uiState.value.pendingCaret != null) _uiState.value = _uiState.value.copy(pendingCaret = null)
    }

    /**
     * Where the caret stood in the text being written when the photo button was pressed (null:
     * not writing — the photos go after that text). Read once by the next [addPhotos].
     */
    private var photoCaret: Int? = null

    fun rememberPhotoCaret(caret: Int?) {
        photoCaret = caret
    }

    /** Backspace at the start of a text whose previous block is text: the two become one. */
    fun mergeWithPrevious(textBlockId: Long) {
        if (!_uiState.value.isEditable || contentStore == null) return
        viewModelScope.launch {
            flushSave()
            val merge = repository.mergeTextBlocks(entryId, textBlockId, emptyMap()) ?: return@launch
            adoptStoredBlocks(activate = merge.textBlockId, caret = merge.textBlockId to merge.caret, structural = true)
        }
    }

    /** Whatever is typed but not yet stored, stored now — before the blocks change shape. */
    private suspend fun flushSave() {
        saveJob?.cancel()
        saveCurrent(releaseIfBlank = false)
    }

    /** The entry's blocks from the store (stored as read, so each has an id). */
    private suspend fun adoptStoredBlocks(activate: Long? = null, caret: Pair<Long, Int>? = null, structural: Boolean = false) {
        val store = contentStore ?: return
        val blocks = store.materialize(entryId)
        if (blocks.isEmpty()) return
        val state = _uiState.value
        val texts = blocks.filterIsInstance<MemoBlock.Text>()
        val active = activate?.takeIf { id -> texts.any { it.id == id } }
            ?: state.activeTextId?.takeIf { id -> texts.any { it.id == id } }
            ?: texts.last().id
        val body = MemoContent.projection(blocks)
        savedTexts = texts.associate { it.id to it.text }
        savedBody = body
        _uiState.value = state.copy(
            blocks = blocks,
            body = body,
            activeTextId = active,
            structureVersion = if (structural) state.structureVersion + 1 else state.structureVersion,
            pendingCaret = caret ?: state.pendingCaret,
        )
    }

    fun refreshForResume() {
        viewModelScope.launch { futureRepository.markDueDelivered() }
    }

    fun saveNow() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { saveCurrent(releaseIfBlank = true) }
    }

    fun saveAndThen(onComplete: () -> Unit) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            saveCurrent(releaseIfBlank = true)
            onComplete()
        }
    }

    fun defaultFutureDate(): LocalDate = futureRepository.currentDate().plusDays(1)

    /** Future comments are sealed from today's entries only — the entry's own day decides. */
    fun isDiaryDateToday(): Boolean =
        currentEntry?.diaryDateEpochDay == futureRepository.currentDate().toEpochDay()

    fun futureRevealAt(date: LocalDate, time: LocalTime): Long =
        futureRepository.toEpochMillis(LocalDateTime.of(date, time))

    fun isFutureRevealAt(revealAt: Long): Boolean = futureRepository.isFuture(revealAt)

    fun createFutureComment(
        text: String,
        revealAt: Long,
        expression: FutureCommentExpression,
        onSuccess: () -> Unit,
    ) {
        val id = currentEntry?.id
        if (id == null) {
            _uiState.value = _uiState.value.copy(message = "先に日記本文を保存してください")
            return
        }
        viewModelScope.launch {
            when (val result = futureRepository.create(id, text, revealAt, expression)) {
                is CreateFutureCommentResult.Success -> {
                    _uiState.value = _uiState.value.copy(message = "未来へ送りました")
                    onSuccess()
                }
                is CreateFutureCommentResult.Rejected -> {
                    _uiState.value = _uiState.value.copy(message = result.reason.userMessage())
                }
            }
        }
    }

    fun deleteFutureComment(id: Long) {
        viewModelScope.launch {
            if (futureRepository.delete(id)) {
                _uiState.value = _uiState.value.copy(message = "未来コメントを削除しました")
            }
        }
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun addPhotos(uris: List<Uri>) {
        val caret = photoCaret
        photoCaret = null
        val entry = currentEntry
        if (uris.isEmpty() || entry == null || !_uiState.value.isEditable || _uiState.value.isImportingPhotos) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImportingPhotos = true)
            // The words typed so far are stored first; the photos go where the caret was in the
            // text being written (after it when it was not being written), and writing goes on
            // in the text below them.
            flushSave()
            val state = _uiState.value
            val writing = state.activeTextId ?: state.blocks.lastOrNull { it is MemoBlock.Text }?.id
            val imported = attachmentRepository.importDiaryPhotos(
                entry.id,
                uris,
                writingTextId = writing,
                caret = caret?.takeIf { writing != null && writing == state.activeTextId },
            )
            if (imported.added > 0) {
                val next = imported.writingTextId
                adoptStoredBlocks(activate = next, caret = next?.let { it to 0 }, structural = true)
            }
            _uiState.value = _uiState.value.copy(
                isImportingPhotos = false,
                message = when {
                    imported.added > 0 && imported.rejected > 0 ->
                        "${imported.added}枚追加しました。${imported.rejected}枚は追加できませんでした。"
                    imported.added > 0 -> "${imported.added}枚の写真を追加しました"
                    imported.duplicates > 0 && imported.rejected == 0 -> "同じ写真は追加済みです"
                    imported.limitReached -> "写真は20枚まで追加できます"
                    else -> "この写真は追加できませんでした"
                },
            )
        }
    }

    fun deletePhoto(photo: PhotoAttachment) {
        val entry = currentEntry ?: return
        if (!_uiState.value.isEditable) return
        viewModelScope.launch {
            flushSave()
            attachmentRepository.deleteDiaryPhoto(entry.id, photo.id)
            adoptStoredBlocks(structural = true)
        }
    }

    fun reorderPhotos(orderedAttachmentIds: List<Long>, onComplete: (Boolean) -> Unit) {
        val entry = currentEntry
        if (entry == null || !_uiState.value.isEditable || _uiState.value.isImportingPhotos) {
            _uiState.value = _uiState.value.copy(message = "写真の並べ替えを保存できませんでした")
            onComplete(false)
            return
        }
        viewModelScope.launch {
            flushSave()
            val result = runCatching {
                attachmentRepository.reorderDiaryPhotos(entry.id, orderedAttachmentIds)
            }.getOrDefault(PhotoReorderResult.REJECTED)
            val success = result != PhotoReorderResult.REJECTED
            if (success) adoptStoredBlocks(structural = true)
            if (!success) {
                _uiState.value = _uiState.value.copy(message = "写真の並べ替えを保存できませんでした")
            }
            onComplete(success)
        }
    }

    fun startSpeech(): Boolean = speechController.speak(
        speechContentComposer.diary(_uiState.value.body, readRubyReadings = cachedReadRuby),
    )

    fun stopSpeech() = speechController.stop()

    fun clearSpeechMessage() = speechController.clearMessage()

    override fun onCleared() {
        speechController.stop()
        super.onCleared()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MILLIS)
            saveCurrent(releaseIfBlank = false)
        }
    }

    /**
     * [releaseIfBlank] is true only when leaving: while writing, a momentarily empty body keeps
     * its row so the entry keeps its identity; on leaving, an empty entry with no photos goes.
     */
    private suspend fun saveCurrent(releaseIfBlank: Boolean) = saveMutex.withLock {
        val snapshot = _uiState.value
        if (!snapshot.isEditable) return@withLock
        val texts = snapshot.blocks.filterIsInstance<MemoBlock.Text>().associate { it.id to it.text }
        if (snapshot.blocks.isNotEmpty() && contentStore != null) {
            if (texts == savedTexts && !(releaseIfBlank && snapshot.body.isBlank())) return@withLock
            _uiState.value = snapshot.copy(saveStatus = DiarySaveStatus.SAVING)
            withContext(NonCancellable) {
                val result = repository.saveBlocks(entryId, texts, releaseIfBlank)
                if (result is DiaryResult.Success) savedTexts = texts
                applyResult(result)
            }
            return@withLock
        }
        if (snapshot.body == savedBody && !(releaseIfBlank && snapshot.body.isBlank())) return@withLock
        _uiState.value = snapshot.copy(saveStatus = DiarySaveStatus.SAVING)
        // Indivisible, like the memo editor's save: the write and its bookkeeping finish together.
        withContext(NonCancellable) {
            applyResult(repository.saveBody(entryId, snapshot.body, releaseIfBlank))
        }
    }

    private suspend fun loadCurrent() {
        val entry = repository.findById(entryId)
        futureRepository.markDueDelivered()
        currentEntry = entry
        savedBody = entry?.body
        // The page and its blocks arrive together, so it is drawn once, in its final order.
        val blocks = entry?.let { contentStore?.materialize(it.id) }.orEmpty()
        val texts = blocks.filterIsInstance<MemoBlock.Text>()
        savedTexts = texts.associate { it.id to it.text }.takeIf { blocks.isNotEmpty() }
        _uiState.value = if (entry != null) {
            DiaryEditorUiState(
                diaryDate = LocalDate.ofEpochDay(entry.diaryDateEpochDay),
                body = if (blocks.isNotEmpty()) MemoContent.projection(blocks) else entry.body,
                state = entry.state,
                isLoading = false,
                saveStatus = DiarySaveStatus.SAVED,
                exists = true,
                blocks = blocks,
                activeTextId = texts.lastOrNull()?.id,
            )
        } else {
            DiaryEditorUiState(
                diaryDate = LocalDate.ofEpochDay(0),
                state = DiaryState.LOCKED,
                isLoading = false,
                message = "日記を読み込めませんでした",
            )
        }
    }

    private fun applyResult(result: DiaryResult) {
        when (result) {
            is DiaryResult.Success -> {
                currentEntry = result.entry
                savedBody = result.entry?.body ?: _uiState.value.body
                _uiState.value = _uiState.value.copy(
                    state = result.entry?.state ?: _uiState.value.state,
                    saveStatus = DiarySaveStatus.SAVED,
                    exists = result.entry != null,
                )
            }
            is DiaryResult.Rejected -> {
                result.entry?.let { entry ->
                    currentEntry = entry
                    savedBody = entry.body
                    _uiState.value = _uiState.value.copy(body = entry.body, state = entry.state, exists = true)
                }
                _uiState.value = _uiState.value.copy(
                    saveStatus = DiarySaveStatus.IDLE,
                    message = result.reason.userMessage(),
                )
            }
        }
    }

    private fun DiaryRejection.userMessage(): String = when (this) {
        DiaryRejection.INVALID_STATE -> "この日記はロックされているため編集できません"
        DiaryRejection.NOT_FOUND -> "日記を読み込めませんでした"
    }

    private fun FutureCommentRejection.userMessage(): String = when (this) {
        FutureCommentRejection.DIARY_NOT_FOUND -> "日記を保存してから送ってください"
        FutureCommentRejection.NOT_DIARY_DATE -> "過去の日記から未来コメントは送れません"
        FutureCommentRejection.EMPTY_TEXT -> "未来コメントを入力してください"
        FutureCommentRejection.REVEAL_NOT_FUTURE -> "公開日時は未来に設定してください"
        FutureCommentRejection.NOT_DELIVERED -> "まだ受け取れません"
    }

    companion object {
        private const val AUTOSAVE_DELAY_MILLIS = 600L

        fun factory(
            repository: DiaryRepository,
            futureRepository: FutureDiaryCommentRepository,
            entryId: Long,
            speechController: SpeechController,
            attachmentRepository: AttachmentRepository,
            settingsRepository: io.github.cragcoffee.memoripple.data.SettingsRepository? = null,
            contentStore: DiaryContentStore? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DiaryEditorViewModel(
                    repository,
                    futureRepository,
                    entryId,
                    speechController,
                    attachmentRepository = attachmentRepository,
                    settingsRepository = settingsRepository,
                    contentStore = contentStore,
                ) as T
        }
    }
}

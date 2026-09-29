package io.github.cragcoffee.memoripple.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.NoteChapterEntity
import io.github.cragcoffee.memoripple.data.MemoRepository
import io.github.cragcoffee.memoripple.data.NoteEntity
import io.github.cragcoffee.memoripple.data.NoteRepository
import io.github.cragcoffee.memoripple.data.NoteSummary
import io.github.cragcoffee.memoripple.domain.WorkTextStats
import io.github.cragcoffee.memoripple.domain.notes.OutlineEpisodes
import android.net.Uri
import io.github.cragcoffee.memoripple.data.AttachmentRepository
import io.github.cragcoffee.memoripple.data.PhotoAttachment
import io.github.cragcoffee.memoripple.domain.notes.NoteArrangement
import io.github.cragcoffee.memoripple.domain.notes.NoteCoverPaint
import io.github.cragcoffee.memoripple.domain.notes.NoteEpisode
import io.github.cragcoffee.memoripple.domain.notes.NoteSection
import io.github.cragcoffee.memoripple.domain.notes.NoteStructure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private fun List<MemoEntity>.toEpisodeInputs(): List<NoteStructure.EpisodeInput> = map { memo ->
    NoteStructure.EpisodeInput(
        memoId = memo.id,
        title = memo.title,
        characterCount = WorkTextStats.of(memo.body).characters,
        updatedAt = memo.updatedAt,
        chapterId = memo.chapterId,
    )
}

data class NoteListUiState(
    val notes: List<NoteSummary> = emptyList(),
    val coverPhotos: Map<Long, PhotoAttachment> = emptyMap(),
    val expandedNoteId: Long? = null,
    val expandedEpisodes: List<NoteEpisode> = emptyList(),
    val isLoading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class NoteListViewModel(
    private val noteRepository: NoteRepository,
    private val memoRepository: MemoRepository,
    private val attachmentRepository: AttachmentRepository,
) : ViewModel() {

    private val expanded = MutableStateFlow<Long?>(null)

    private val expandedEpisodes = expanded.flatMapLatest { noteId ->
        if (noteId == null) flowOf(emptyList()) else noteRepository.observeEpisodes(noteId)
    }

    val uiState: StateFlow<NoteListUiState> = combine(
        noteRepository.observeSummaries(),
        expanded,
        expandedEpisodes,
        attachmentRepository.observeAllNoteCovers(),
    ) { notes, expandedId, episodes, covers ->
        NoteListUiState(
            notes = notes,
            coverPhotos = covers.associateBy(PhotoAttachment::ownerId),
            expandedNoteId = expandedId,
            // The list shows the run of episodes; the headings live on the note's own page.
            expandedEpisodes = NoteStructure.flatten(
                NoteStructure.sections(emptyList(), episodes.toEpisodeInputs()),
            ),
            isLoading = false,
        )
    }
        // The per-episode text statistics are a full pass over every body; off the main thread.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteListUiState())

    /** 並べた順: the notes in the order a drag left them; only their places change. */
    fun reorder(orderedIds: List<Long>) {
        viewModelScope.launch { noteRepository.reorder(orderedIds) }
    }

    fun toggleExpanded(noteId: Long) {
        expanded.value = if (expanded.value == noteId) null else noteId
    }

    /** Puts an existing memo at the end of a note, where the next episode would go. */
    fun addEpisode(noteId: Long, memoId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            noteRepository.addEpisode(noteId, memoId)
            onDone()
        }
    }

    /**
     * Makes an episode out of every heading in [source], leaving [source] exactly as it is.
     *
     * The memos are written first and placed second, in the order the headings were read, so a note
     * built this way reads in the order the outline was written in.
     */
    fun makeEpisodesFromOutline(noteId: Long, source: MemoEntity, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            var made = 0
            OutlineEpisodes.of(source.body).forEach { draft ->
                val memo = memoRepository.save(
                    existing = null,
                    title = draft.title,
                    body = draft.body,
                    now = System.currentTimeMillis(),
                ) ?: return@forEach
                noteRepository.addEpisode(noteId, memo.id)
                made++
            }
            onDone(made)
        }
    }

    fun renameNote(noteId: Long, title: String) {
        viewModelScope.launch { noteRepository.rename(noteId, title) }
    }

    fun setSubtitle(noteId: Long, subtitle: String) {
        viewModelScope.launch { noteRepository.setSubtitle(noteId, subtitle) }
    }

    fun setCoverPaint(noteId: Long, paint: NoteCoverPaint) {
        viewModelScope.launch { noteRepository.setCoverPaint(noteId, paint) }
    }

    /** The picture goes on from the shelf too; the sheet is the same one the note's page shows. */
    fun setCoverPhoto(noteId: Long, uri: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(attachmentRepository.setNoteCover(noteId, uri)) }
    }

    fun clearCoverPhoto(noteId: Long) {
        viewModelScope.launch {
            attachmentRepository.clearNoteCover(noteId, System.currentTimeMillis())
        }
    }

    /** Deleting a note releases its episodes. They were memos before it and they stay memos. */
    fun deleteNote(noteId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            noteRepository.delete(noteId)
            onDone()
        }
    }

    /** The list's selection: the same deletion as [deleteNote], for every picked note at once. */
    fun deleteNotes(noteIds: Set<Long>, onDone: (Int) -> Unit) {
        if (noteIds.isEmpty()) return
        viewModelScope.launch { onDone(noteRepository.deleteAll(noteIds)) }
    }

    fun createNote(title: String, subtitle: String = "", onCreated: (Long) -> Unit) {
        viewModelScope.launch { onCreated(noteRepository.create(title, subtitle).id) }
    }

    /** Writes the next episode straight from the list, so the note does not have to be opened. */
    fun writeNextEpisode(noteId: Long, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val memo = memoRepository.createEmpty(System.currentTimeMillis())
            noteRepository.addEpisode(noteId, memo.id)
            onCreated(memo.id)
        }
    }

    companion object {
        fun factory(
            noteRepository: NoteRepository,
            memoRepository: MemoRepository,
            attachmentRepository: AttachmentRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NoteListViewModel(noteRepository, memoRepository, attachmentRepository) as T
        }
    }
}

data class NoteDetailUiState(
    val note: NoteEntity? = null,
    val sections: List<NoteSection> = emptyList(),
    val episodeCount: Int = 0,
    val totalCharacters: Int = 0,
    val lastWrittenAt: Long = 0,
    val coverPhoto: PhotoAttachment? = null,
    val isLoading: Boolean = true,
) {
    val coverPaint: NoteCoverPaint get() = NoteCoverPaint.fromStorageId(note?.coverColor)
}

class NoteDetailViewModel(
    private val noteId: Long,
    private val noteRepository: NoteRepository,
    private val memoRepository: MemoRepository,
    private val attachmentRepository: AttachmentRepository,
) : ViewModel() {

    val uiState: StateFlow<NoteDetailUiState> = combine(
        noteRepository.observeNote(noteId),
        noteRepository.observeChapters(noteId),
        noteRepository.observeEpisodes(noteId),
        attachmentRepository.observeNoteCover(noteId),
    ) { note, chapters, episodes, cover ->
        val inputs = episodes.toEpisodeInputs()
        NoteDetailUiState(
            note = note,
            sections = NoteStructure.sections(
                chapters.map { NoteStructure.ChapterInput(it.id, it.title, it.sortOrder) },
                inputs,
            ),
            episodeCount = inputs.size,
            totalCharacters = inputs.sumOf(NoteStructure.EpisodeInput::characterCount),
            lastWrittenAt = episodes.maxOfOrNull(MemoEntity::updatedAt) ?: note?.updatedAt ?: 0,
            coverPhoto = cover,
            isLoading = false,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteDetailUiState())

    fun rename(title: String) {
        viewModelScope.launch { noteRepository.rename(noteId, title) }
    }

    fun setCoverPaint(paint: NoteCoverPaint) {
        viewModelScope.launch { noteRepository.setCoverPaint(noteId, paint) }
    }

    /** Puts a picture on the cover. Reports failure so a rejected file does not pass silently. */
    fun setCoverPhoto(uri: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(attachmentRepository.setNoteCover(noteId, uri)) }
    }

    fun clearCoverPhoto() {
        viewModelScope.launch {
            attachmentRepository.clearNoteCover(noteId, System.currentTimeMillis())
        }
    }

    fun addChapter(title: String, atStart: Boolean = false) {
        viewModelScope.launch { noteRepository.addChapter(noteId, title, atStart) }
    }

    fun renameChapter(chapterId: Long, title: String) {
        viewModelScope.launch { noteRepository.renameChapter(chapterId, title) }
    }

    fun deleteChapter(chapterId: Long) {
        viewModelScope.launch { noteRepository.deleteChapter(chapterId) }
    }

    fun writeNextEpisode(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val memo = memoRepository.createEmpty(System.currentTimeMillis())
            noteRepository.addEpisode(noteId, memo.id)
            onCreated(memo.id)
        }
    }

    /**
     * Moves one row of the note past its neighbour, heading or episode alike.
     *
     * Where things end up is the whole answer: an episode belongs to the heading above it, so a
     * heading that steps over an episode takes it, and an episode that steps over a heading leaves
     * it. Nothing is dragged along; the arrangement is read back out of the order.
     */
    /** The rows as a drag left them, written once on release; what each episode belongs to is read from the order. */
    fun arrangeRows(rows: List<NoteArrangement.Row>) {
        viewModelScope.launch { noteRepository.arrange(noteId, NoteArrangement.placement(rows)) }
    }

    fun moveRow(rows: List<NoteArrangement.Row>, index: Int, delta: Int): Boolean {
        val moved = NoteArrangement.moved(rows, index, delta) ?: return false
        viewModelScope.launch {
            noteRepository.arrange(noteId, NoteArrangement.placement(moved))
        }
        return true
    }

    fun moveEpisodeToChapter(memoId: Long, chapterId: Long?) {
        viewModelScope.launch { noteRepository.moveEpisodeToChapter(memoId, chapterId) }
    }

    /** Takes an episode out of the note. The memo itself is untouched. */
    fun releaseEpisodes(memoIds: Set<Long>) {
        viewModelScope.launch { memoIds.forEach { noteRepository.releaseEpisode(it) } }
    }

    fun deleteNote(onDeleted: () -> Unit) {
        viewModelScope.launch {
            noteRepository.delete(noteId)
            onDeleted()
        }
    }

    companion object {
        fun factory(
            noteId: Long,
            noteRepository: NoteRepository,
            memoRepository: MemoRepository,
            attachmentRepository: AttachmentRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NoteDetailViewModel(
                    noteId,
                    noteRepository,
                    memoRepository,
                    attachmentRepository,
                ) as T
        }
    }
}

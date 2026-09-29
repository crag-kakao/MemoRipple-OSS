package io.github.cragcoffee.memoripple.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.cragcoffee.memoripple.data.MemoCommentRepository
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.NoteRepository
import io.github.cragcoffee.memoripple.domain.comments.CommentLinkMarkers
import io.github.cragcoffee.memoripple.domain.notes.NoteStructure
import io.github.cragcoffee.memoripple.domain.notes.ReaderProse
import io.github.cragcoffee.memoripple.domain.playback.PlaybackStatus
import io.github.cragcoffee.memoripple.speech.SpeechSessionObserver
import io.github.cragcoffee.memoripple.domain.playback.CommentAnimationState
import io.github.cragcoffee.memoripple.domain.playback.CommentAnimator
import io.github.cragcoffee.memoripple.domain.playback.MemoPlaybackTimelineFactory
import io.github.cragcoffee.memoripple.domain.playback.PlaybackContentMode
import io.github.cragcoffee.memoripple.domain.playback.PlaybackItem
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.speech.SpeechController
import io.github.cragcoffee.memoripple.speech.SpeechPlaybackCoordinator
import io.github.cragcoffee.memoripple.speech.SpeechState
import io.github.cragcoffee.memoripple.speech.SpeechStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One episode, opened to be read. */
data class NoteReaderUiState(
    val noteTitle: String = "",
    val episodeTitle: String = "",
    val episodeNumber: Int = 0,
    val body: String = "",
    val previousMemoId: Long? = null,
    val nextMemoId: Long? = null,
    val isLoading: Boolean = true,
    /** The episode on the page right now. */
    val memoId: Long? = null,
    /** The pages either side, for a turn that follows the finger: null at the note's ends. */
    val previous: ReaderNeighbour? = null,
    val next: ReaderNeighbour? = null,
)

/** An episode next to the one being read — enough of it to draw the page sliding in. */
data class ReaderNeighbour(val memoId: Long, val title: String, val body: String)

/**
 * Reading an episode.
 *
 * Nothing flows until it is asked for. A page of prose is there to be read, and comments crossing
 * it unasked would be in the way; pressing play is the reader saying they want the other voice now.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteReaderViewModel(
    private val noteId: Long,
    private val noteRepository: NoteRepository,
    private val commentRepository: MemoCommentRepository,
    private val speechController: SpeechController,
    private val commentAnimator: CommentAnimator = CommentAnimator(),
    private val timelineFactory: MemoPlaybackTimelineFactory = MemoPlaybackTimelineFactory(),
    settingsRepository: io.github.cragcoffee.memoripple.data.SettingsRepository? = null,
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

    val speechState: StateFlow<SpeechState> = speechController.state

    private val speechPlaybackCoordinator = SpeechPlaybackCoordinator(
        speechController = speechController,
        stopCommentPlayback = commentAnimator::stop,
    )

    private val currentMemoId = MutableStateFlow<Long?>(null)

    val playbackState: StateFlow<CommentAnimationState> = commentAnimator.state

    private val comments = currentMemoId.flatMapLatest { id ->
        if (id == null) kotlinx.coroutines.flow.flowOf(emptyList()) else {
            commentRepository.observeForMemo(id)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The linkNo numbers that still belong to a living comment — the page's marker map. */
    val linkedNumbers: StateFlow<Set<Int>> = comments.map { list ->
        list.mapNotNull(io.github.cragcoffee.memoripple.data.MemoCommentEntity::linkNo).toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val uiState: StateFlow<NoteReaderUiState> = combine(
        noteRepository.observeNote(noteId),
        noteRepository.observeEpisodes(noteId),
        currentMemoId,
    ) { note, episodes, memoId ->
        val ordered = NoteStructure.flatten(
            NoteStructure.sections(
                emptyList(),
                episodes.map { memo ->
                    NoteStructure.EpisodeInput(
                        memoId = memo.id,
                        title = memo.title,
                        characterCount = 0,
                        updatedAt = memo.updatedAt,
                        chapterId = null,
                    )
                },
            ),
        )
        val index = ordered.indexOfFirst { it.memoId == memoId }
        val current = episodes.firstOrNull { it.id == memoId }
        fun neighbour(at: Int): ReaderNeighbour? = ordered.getOrNull(at)?.let { entry ->
            episodes.firstOrNull { it.id == entry.memoId }?.let { memo ->
                ReaderNeighbour(memo.id, memo.title.ifBlank { "無題" }, memo.body)
            }
        }
        NoteReaderUiState(
            noteTitle = note?.title.orEmpty(),
            episodeTitle = current?.title?.ifBlank { "無題" } ?: "",
            episodeNumber = if (index >= 0) ordered[index].number else 0,
            body = current?.body.orEmpty(),
            previousMemoId = ordered.getOrNull(index - 1)?.memoId?.takeIf { index > 0 },
            nextMemoId = ordered.getOrNull(index + 1)?.memoId,
            isLoading = memoId == null || current == null,
            memoId = memoId,
            previous = if (index > 0) neighbour(index - 1) else null,
            next = if (index >= 0) neighbour(index + 1) else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteReaderUiState())

    fun open(memoId: Long) {
        if (currentMemoId.value == memoId) return
        commentAnimator.stop()
        speechController.stop()
        currentMemoId.value = memoId
    }

    /**
     * Where speech last set off from, and whether the title rode ahead of the prose: the two
     * numbers that turn the engine's segment index back into a sentence on the page.
     */
    private val speechOrigin = MutableStateFlow(SpeechOrigin())

    private data class SpeechOrigin(val firstSentence: Int = 0, val titleSegments: Int = 0)

    /**
     * Where a pause left off, or null while nothing is paused. The engine cannot hold its
     * breath mid-word, so pausing stops it and remembers the sentence; resuming reads that
     * sentence again from its first word — the grain a page pauses at anyway.
     */
    private val speechPausedAt = MutableStateFlow<Int?>(null)

    val speechPaused: Flow<Boolean> = speechPausedAt.map { it != null }

    /**
     * The sentence being spoken right now — or, while paused, the sentence waiting to resume —
     * counted the way [ReaderProse.sentences] counts them. The follow-along cursor the page
     * highlights; null when the voice is neither speaking nor holding a place.
     */
    val activeSentenceIndex: Flow<Int?> = combine(
        speechController.state,
        speechOrigin,
        speechPausedAt,
    ) { speech, origin, pausedAt ->
        speech.activeSegmentIndex
            ?.let { it - origin.titleSegments + origin.firstSentence }
            ?.takeIf { speech.status == SpeechStatus.SPEAKING && it >= origin.firstSentence }
            ?: pausedAt
    }

    /** The spoken sentence as of this instant, for the moment pause is pressed. */
    private fun currentSentenceOrNull(): Int? {
        val speech = speechController.state.value
        val origin = speechOrigin.value
        return speech.activeSegmentIndex
            ?.let { it - origin.titleSegments + origin.firstSentence }
            ?.takeIf { it >= origin.firstSentence }
    }

    /** Holds the voice where it stands; the highlighted sentence keeps the place. */
    fun pauseSpeech() {
        speechPausedAt.value = currentSentenceOrNull() ?: speechOrigin.value.firstSentence
        speechController.stop()
    }

    /**
     * A tap on a sentence re-aims the voice — but only while the voice is engaged, reading or
     * paused. A silent page stays silent: after 停止, the 読み上げ button is the one way back
     * in, so a stray touch while reading quietly never starts a narration.
     */
    fun jumpSpeechTo(sentenceIndex: Int): Boolean {
        val status = speechController.state.value.status
        val engaged = status == SpeechStatus.SPEAKING ||
            status == SpeechStatus.INITIALIZING ||
            speechPausedAt.value != null
        if (!engaged) return false
        return startSpeechAt(sentenceIndex)
    }

    /** Picks the held place back up, reading the paused sentence from its first word. */
    fun resumeSpeech(): Boolean {
        val at = speechPausedAt.value ?: return false
        return startSpeechAt(at)
    }

    /**
     * コメントリンクの発火 queue: fires arrive from taps on the page and from the voice passing
     * markers; each flows as its own tiny timeline once the stage is free, in arrival order.
     */
    private val linkedEmitQueue = ArrayDeque<Long>()

    /** The settings the flowing comments wear — remembered from the last play/fire request. */
    private var emitSettings = AppSettings.Default

    init {
        viewModelScope.launch {
            commentAnimator.state.collect { state ->
                if (state.status == PlaybackStatus.IDLE) drainLinkedEmit()
            }
        }
    }

    /**
     * Hands the comment carrying [number] to the stage, if it still exists. Fires can arrive
     * from the engine's own thread; the queue and the animator live on the main one.
     */
    fun fireLinkedComment(number: Int, appSettings: AppSettings = emitSettings) {
        emitSettings = appSettings
        viewModelScope.launch {
            val comment = comments.value.firstOrNull { it.linkNo == number } ?: return@launch
            linkedEmitQueue.addLast(comment.id)
            if (commentAnimator.state.value.status == PlaybackStatus.IDLE) drainLinkedEmit()
        }
    }

    private fun drainLinkedEmit() {
        while (true) {
            val id = linkedEmitQueue.removeFirstOrNull() ?: return
            val comment = comments.value.firstOrNull { it.id == id } ?: continue
            commentAnimator.play(timelineFactory.createForSingleComment(comment, emitSettings))
            return
        }
    }

    /** Reads the episode aloud: its title, then its prose sentence by sentence. */
    fun startSpeech(appSettings: AppSettings = emitSettings): Boolean {
        emitSettings = appSettings
        return startSpeechAt(0, withTitle = true)
    }

    /**
     * Reads aloud from the tapped sentence on, one voice walking the rest of the page — a tap
     * chooses where to pick the book up, not a single line to hear.
     */
    fun startSpeechAt(sentenceIndex: Int, withTitle: Boolean = false): Boolean {
        val state = uiState.value
        val sentences = ReaderProse.sentences(ReaderProse.paragraphs(state.body))
        val title = state.episodeTitle.trim()
            .takeIf { withTitle && it.isNotBlank() && it != "無題" }
        // An episode that is only a title is still read: its name is all it has to say.
        if (sentenceIndex !in sentences.indices && !(sentences.isEmpty() && title != null)) {
            return false
        }
        val segments = listOfNotNull(title) +
            sentences.drop(sentenceIndex).map { it.spoken(cachedReadRuby) }
        val titleSegments = if (title != null) 1 else 0
        speechOrigin.value = SpeechOrigin(
            firstSentence = sentenceIndex,
            titleSegments = titleSegments,
        )
        speechPausedAt.value = null
        // コメントリンク at the page's grain: a marker fires once the voice has read past its
        // sentence — and whatever the engine never reached fires when the reading ends whole.
        // Stopping or pausing mid-page fires nothing more; a fresh start is a fresh pass.
        val sentenceMarkers = sentences.withIndex()
            .filter { it.index >= sentenceIndex }
            .flatMap { (index, sentence) ->
                sentence.linkNumbers.map { CommentLinkMarkers.StrippedMarker(it, index) }
            }
        val observer = if (sentenceMarkers.isEmpty()) null else {
            val tracker = CommentLinkMarkers.PassTracker(sentenceMarkers)
            object : SpeechSessionObserver {
                override fun onSegmentStart(index: Int) {
                    val sentence = index - titleSegments + sentenceIndex
                    tracker.advanceTo(sentence - 1).forEach { fireLinkedComment(it) }
                }

                override fun onFinished() {
                    tracker.finish().forEach { fireLinkedComment(it) }
                }
            }
        }
        return speechController.speakSegments(segments, observer)
    }

    fun stopSpeech() {
        speechPausedAt.value = null
        speechPlaybackCoordinator.stopSpeech()
    }

    /**
     * Whether there is anything to flow. An episode with no marked lines and no comments has
     * nothing to send across itself, and offering to play it would be offering nothing.
     */
    fun canPlay(appSettings: AppSettings): Flow<Boolean> = combine(uiState, comments) { state, list ->
        !state.isLoading && timelineFactory.create(
            memoBody = state.body,
            userComments = list,
            contentMode = PlaybackContentMode.BOTH,
            appSettings = appSettings,
        ).items.isNotEmpty()
    }

    fun play(appSettings: AppSettings) {
        val state = uiState.value
        if (state.isLoading) return
        commentAnimator.play(
            timelineFactory.create(
                memoBody = state.body,
                userComments = comments.value,
                contentMode = PlaybackContentMode.BOTH,
                appSettings = appSettings,
            ),
        )
    }

    fun pause() = commentAnimator.pause()

    fun resume() = commentAnimator.resume()

    fun stop() = commentAnimator.stop()

    fun advance(deltaMillis: Long) = commentAnimator.advanceBy(deltaMillis)

    fun offsetPx(
        item: PlaybackItem,
        elapsedMillis: Long,
        containerWidthPx: Float,
        commentWidthPx: Float,
    ): Float = commentAnimator.horizontalOffsetPx(item, elapsedMillis, containerWidthPx, commentWidthPx)

    /** Episodes in reading order, so the reader can jump without going back to the note. */
    fun episodes(onResult: (List<MemoEntity>) -> Unit) {
        viewModelScope.launch { onResult(noteRepository.episodes(noteId)) }
    }

    override fun onCleared() {
        commentAnimator.stop()
        speechController.stop()
        super.onCleared()
    }

    companion object {
        fun factory(
            noteId: Long,
            noteRepository: NoteRepository,
            commentRepository: MemoCommentRepository,
            speechController: SpeechController,
            settingsRepository: io.github.cragcoffee.memoripple.data.SettingsRepository? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NoteReaderViewModel(
                    noteId,
                    noteRepository,
                    commentRepository,
                    speechController,
                    settingsRepository = settingsRepository,
                ) as T
        }
    }
}

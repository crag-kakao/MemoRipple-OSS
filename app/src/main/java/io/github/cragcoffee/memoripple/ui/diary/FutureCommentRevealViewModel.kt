package io.github.cragcoffee.memoripple.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.cragcoffee.memoripple.data.FutureDiaryCommentRepository
import io.github.cragcoffee.memoripple.domain.diary.FutureCommentRevealContext
import io.github.cragcoffee.memoripple.domain.diary.requiresFirstPresentation
import io.github.cragcoffee.memoripple.domain.playback.CommentAnimationState
import io.github.cragcoffee.memoripple.domain.playback.CommentAnimator
import io.github.cragcoffee.memoripple.domain.playback.FutureDiaryCommentPlaybackMapper
import io.github.cragcoffee.memoripple.domain.playback.PlaybackItem
import io.github.cragcoffee.memoripple.domain.playback.PlaybackStatus
import io.github.cragcoffee.memoripple.domain.playback.PlaybackTimeline
import io.github.cragcoffee.memoripple.domain.playback.PlaybackSettingsApplier
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.domain.speech.FutureSpeechContent
import io.github.cragcoffee.memoripple.domain.speech.SpeechContentComposer
import io.github.cragcoffee.memoripple.speech.SpeechController
import io.github.cragcoffee.memoripple.speech.SpeechState
import io.github.cragcoffee.memoripple.speech.SpeechPlaybackCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface FutureRevealUiState {
    data object Loading : FutureRevealUiState
    data class Ready(val context: FutureCommentRevealContext) : FutureRevealUiState
    data object Unavailable : FutureRevealUiState
}

class FutureCommentRevealViewModel(
    private val repository: FutureDiaryCommentRepository,
    private val commentId: Long,
    private val mapper: FutureDiaryCommentPlaybackMapper = FutureDiaryCommentPlaybackMapper(),
    private val playbackSettingsApplier: PlaybackSettingsApplier = PlaybackSettingsApplier(),
    private val animator: CommentAnimator = CommentAnimator(),
    private val speechController: SpeechController,
    private val speechContentComposer: SpeechContentComposer = SpeechContentComposer(),
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

    private val _uiState = MutableStateFlow<FutureRevealUiState>(FutureRevealUiState.Loading)
    val uiState: StateFlow<FutureRevealUiState> = _uiState.asStateFlow()
    val animationState: StateFlow<CommentAnimationState> = animator.state
    val speechState: StateFlow<SpeechState> = speechController.state
    private val _autoPlayRequest = MutableStateFlow(0)
    val autoPlayRequest: StateFlow<Int> = _autoPlayRequest.asStateFlow()
    private val speechPlaybackCoordinator = SpeechPlaybackCoordinator(
        speechController = speechController,
        stopCommentPlayback = animator::stop,
    )

    init {
        viewModelScope.launch {
            val context = if (commentId == RECEIVE_NEXT_ID) {
                repository.revealNextContext()
            } else {
                repository.findRevealContext(commentId)
            }
            _uiState.value = context?.let(FutureRevealUiState::Ready)
                ?: FutureRevealUiState.Unavailable
            if (context?.comment?.requiresFirstPresentation == true) requestInitialPlayback()
        }
    }

    fun onResume() {
        val ready = _uiState.value as? FutureRevealUiState.Ready ?: return
        if (ready.context.comment.requiresFirstPresentation &&
            animator.state.value.status == PlaybackStatus.IDLE
        ) {
            requestInitialPlayback()
        }
    }

    fun createTimeline(
        initialPresentation: Boolean,
        appSettings: AppSettings = AppSettings.Default,
    ): PlaybackTimeline {
        val ready = _uiState.value as? FutureRevealUiState.Ready ?: return PlaybackTimeline.Empty
        val delay = if (initialPresentation) {
            FutureDiaryCommentPlaybackMapper.INITIAL_CONTEXT_DELAY_MILLIS
        } else {
            FutureDiaryCommentPlaybackMapper.REPLAY_START_DELAY_MILLIS
        }
        return playbackSettingsApplier.apply(
            mapper.map(ready.context.comment, startDelayMillis = delay),
            appSettings,
        )
    }

    fun play(timeline: PlaybackTimeline) {
        speechPlaybackCoordinator.startCommentPlayback { animator.play(timeline) }
    }

    fun fallbackFixedToFlow(timeline: PlaybackTimeline): PlaybackTimeline =
        mapper.fallbackFixedToFlow(timeline)

    fun stop() = speechPlaybackCoordinator.stopCommentPlayback()

    fun startSpeech(content: FutureSpeechContent): Boolean {
        val ready = _uiState.value as? FutureRevealUiState.Ready ?: return false
        if (ready.context.comment.requiresFirstPresentation) return false
        return speechPlaybackCoordinator.startSpeech(
            speechContentComposer.future(ready.context, content, readRubyReadings = cachedReadRuby),
        )
    }

    fun stopSpeech() = speechPlaybackCoordinator.stopSpeech()

    fun clearSpeechMessage() = speechController.clearMessage()

    fun stopAll() {
        speechPlaybackCoordinator.stopAll()
    }

    fun advanceBy(deltaMillis: Long) {
        val before = animator.state.value
        animator.advanceBy(deltaMillis)
        val completedNaturally = before.status == PlaybackStatus.PLAYING &&
            before.timeline != null && animator.state.value.status == PlaybackStatus.IDLE
        if (completedNaturally) completeInitialPresentationIfNeeded()
    }

    fun offset(
        item: PlaybackItem,
        elapsedMillis: Long,
        containerWidthPx: Float,
        commentWidthPx: Float,
    ): Float = animator.horizontalOffsetPx(
        item,
        elapsedMillis,
        containerWidthPx,
        commentWidthPx,
    )

    private fun completeInitialPresentationIfNeeded() {
        val ready = _uiState.value as? FutureRevealUiState.Ready ?: return
        if (!ready.context.comment.requiresFirstPresentation) return
        viewModelScope.launch {
            val updated = repository.markFirstPresentationCompleted(ready.context.comment.id)
                ?: return@launch
            _uiState.value = FutureRevealUiState.Ready(
                ready.context.copy(comment = updated),
            )
        }
    }

    private fun requestInitialPlayback() {
        _autoPlayRequest.value += 1
    }

    override fun onCleared() {
        animator.stop()
        speechController.stop()
        super.onCleared()
    }

    companion object {
        const val RECEIVE_NEXT_ID = 0L

        fun factory(
            repository: FutureDiaryCommentRepository,
            commentId: Long,
            speechController: SpeechController,
            settingsRepository: io.github.cragcoffee.memoripple.data.SettingsRepository? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FutureCommentRevealViewModel(
                    repository,
                    commentId,
                    speechController = speechController,
                    settingsRepository = settingsRepository,
                ) as T
        }
    }
}

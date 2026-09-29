package io.github.cragcoffee.memoripple.ui.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.ui.attachments.AttachmentImageLoader
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.ui.playback.CommentPlaybackFrameClock
import io.github.cragcoffee.memoripple.speech.SpeechStatus
import io.github.cragcoffee.memoripple.domain.playback.PlaybackStatus
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect

@Composable
fun NoteDetailRoute(
    noteId: Long,
    onBack: () -> Unit,
    onOpenEpisode: (Long) -> Unit,
    onEditEpisode: (Long) -> Unit,
) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    val viewModel: NoteDetailViewModel = viewModel(
        key = "note-detail-$noteId",
        factory = NoteDetailViewModel.factory(
            noteId,
            application.noteRepository,
            application.memoRepository,
            application.attachmentRepository,
        ),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val myCoverColors by application.settingsRepository.myCoverColors
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val coverScope = rememberCoroutineScope()
    val imageLoader = remember(application) {
        AttachmentImageLoader(application.attachmentBlobStore)
    }

    NoteDetailScreen(
        state = state,
        onBack = onBack,
        onOpenEpisode = onOpenEpisode,
        onEditEpisode = onEditEpisode,
        onWriteNextEpisode = { viewModel.writeNextEpisode(onEditEpisode) },
        onRename = viewModel::rename,
        onAddChapter = viewModel::addChapter,
        onRenameChapter = viewModel::renameChapter,
        onDeleteChapter = viewModel::deleteChapter,
        onMoveToChapter = { memoIds, chapterId ->
            memoIds.forEach { viewModel.moveEpisodeToChapter(it, chapterId) }
        },
        onReleaseEpisodes = viewModel::releaseEpisodes,
        onMoveRow = viewModel::moveRow,
        onArrangeRows = viewModel::arrangeRows,
        onCoverColorChange = viewModel::setCoverPaint,
        onCoverPhotoPick = viewModel::setCoverPhoto,
        onCoverPhotoClear = viewModel::clearCoverPhoto,
        myCoverColors = myCoverColors,
        onSaveMyCoverColor = { argb ->
            coverScope.launch { application.settingsRepository.rememberCoverColor(argb) }
        },
        onRemoveMyCoverColor = { argb ->
            coverScope.launch { application.settingsRepository.forgetCoverColor(argb) }
        },
        imageLoader = imageLoader,
        onDeleteNote = { viewModel.deleteNote(onBack) },
    )
}

@Composable
fun NoteReaderRoute(
    noteId: Long,
    memoId: Long,
    appSettings: AppSettings = AppSettings.Default,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    val viewModel: NoteReaderViewModel = viewModel(
        key = "note-reader-$noteId",
        factory = NoteReaderViewModel.factory(
            noteId,
            application.noteRepository,
            application.memoCommentRepository,
            application.speechController,
            application.settingsRepository,
        ),
    )
    LaunchedEffect(memoId) { viewModel.open(memoId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Status alone for the screen; the stage leaf inside collects the per-frame state itself,
    // so a playing stream does not re-run the whole reader every frame.
    val playbackStatus by remember(viewModel) {
        viewModel.playbackState.map { it.status }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(PlaybackStatus.IDLE)
    val canPlay by remember(viewModel, appSettings) { viewModel.canPlay(appSettings) }
        .collectAsStateWithLifecycle(false)

    CommentPlaybackFrameClock(status = playbackStatus, onFrame = viewModel::advance)
    val speechState by viewModel.speechState.collectAsStateWithLifecycle()
    val activeSentenceIndex by viewModel.activeSentenceIndex
        .collectAsStateWithLifecycle(initialValue = null)
    val speechPaused by viewModel.speechPaused.collectAsStateWithLifecycle(initialValue = false)
    val linkedNumbers by viewModel.linkedNumbers.collectAsStateWithLifecycle()
    val swipeTurns by application.settingsRepository.readerSwipeTurns.collectAsStateWithLifecycle(initialValue = true)
    // Leaving the page silences it: the voice is app-scoped and must not read to an empty room.
    DisposableEffect(viewModel) {
        onDispose { viewModel.stopSpeech() }
    }

    NoteReaderScreen(
        state = state,
        playbackStatus = playbackStatus,
        playbackState = viewModel.playbackState,
        canPlay = canPlay,
        offsetProvider = viewModel::offsetPx,
        onBack = onBack,
        onPlay = { viewModel.play(appSettings) },
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onStop = viewModel::stop,
        onOpenEpisode = viewModel::open,
        onEdit = onEdit,
        speechActive = speechState.status == SpeechStatus.INITIALIZING ||
            speechState.status == SpeechStatus.SPEAKING,
        speechPaused = speechPaused,
        canSpeak = !state.isLoading &&
            (state.episodeTitle.isNotBlank() || state.body.isNotBlank()),
        onStartSpeech = { viewModel.startSpeech(appSettings) },
        onPauseSpeech = viewModel::pauseSpeech,
        onResumeSpeech = { viewModel.resumeSpeech() },
        onStopSpeech = viewModel::stopSpeech,
        activeSentenceIndex = activeSentenceIndex,
        onTapSentence = { index -> viewModel.jumpSpeechTo(index) },
        linkedNumbers = linkedNumbers,
        onEmitLink = { number -> viewModel.fireLinkedComment(number, appSettings) },
        swipeTurns = swipeTurns,
    )
}

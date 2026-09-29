package io.github.cragcoffee.memoripple.ui.diary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.domain.diary.FutureCommentRevealContext
import io.github.cragcoffee.memoripple.domain.diary.requiresFirstPresentation
import io.github.cragcoffee.memoripple.domain.playback.CommentAnimationState
import io.github.cragcoffee.memoripple.domain.playback.CommentLaneAllocator
import io.github.cragcoffee.memoripple.domain.playback.PlaybackStatus
import io.github.cragcoffee.memoripple.domain.playback.PlaybackTimeline
import io.github.cragcoffee.memoripple.domain.playback.PlaybackValidationIssue
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.domain.speech.FutureSpeechContent
import io.github.cragcoffee.memoripple.speech.SpeechState
import io.github.cragcoffee.memoripple.speech.SpeechStatus
import io.github.cragcoffee.memoripple.ui.playback.CommentOffsetProvider
import io.github.cragcoffee.memoripple.ui.playback.CommentPlaybackFrameClock
import io.github.cragcoffee.memoripple.ui.playback.CommentRenderer
import io.github.cragcoffee.memoripple.ui.playback.COMMENT_WAVE_DESIRED_AMPLITUDE
import io.github.cragcoffee.memoripple.ui.playback.LocalCommentFontFamily
import io.github.cragcoffee.memoripple.ui.playback.measureCommentTextMetricsPx
import io.github.cragcoffee.memoripple.ui.playback.resolveCommentLaneLayout
import io.github.cragcoffee.memoripple.ui.playback.stageCommentRendererColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun FutureCommentRevealRoute(
    commentId: Long,
    appSettings: AppSettings = AppSettings.Default,
    onClose: () -> Unit,
) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    val viewModel: FutureCommentRevealViewModel = viewModel(
        key = "future-comment-reveal-$commentId",
        factory = FutureCommentRevealViewModel.factory(
            application.futureDiaryCommentRepository,
            commentId,
            application.speechController,
            application.settingsRepository,
        ),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Collected as a State, never read here: a clock tick must not recompose the route.
    val animation = viewModel.animationState.collectAsStateWithLifecycle()
    val animationStatus by remember(animation) {
        derivedStateOf { animation.value.status }
    }
    val autoPlayRequest by viewModel.autoPlayRequest.collectAsStateWithLifecycle()
    val speechState by viewModel.speechState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onResume()
                Lifecycle.Event.ON_STOP -> viewModel.stopAll()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopAll()
        }
    }
    BackHandler {
        viewModel.stopAll()
        onClose()
    }
    CommentPlaybackFrameClock(animationStatus, viewModel::advanceBy)
    FutureCommentRevealScreen(
        state = state,
        animation = animation,
        speechState = speechState,
        appSettings = appSettings,
        autoPlayRequest = autoPlayRequest,
        createTimeline = { initial -> viewModel.createTimeline(initial, appSettings) },
        fallbackFixedToFlow = viewModel::fallbackFixedToFlow,
        play = viewModel::play,
        stop = viewModel::stop,
        startSpeech = viewModel::startSpeech,
        stopSpeech = viewModel::stopSpeech,
        onSpeechMessageShown = viewModel::clearSpeechMessage,
        offset = viewModel::offset,
        onClose = {
            viewModel.stopAll()
            onClose()
        },
    )
}

@Composable
private fun FutureCommentRevealScreen(
    state: FutureRevealUiState,
    animation: State<CommentAnimationState>,
    speechState: SpeechState,
    appSettings: AppSettings,
    autoPlayRequest: Int,
    createTimeline: (Boolean) -> PlaybackTimeline,
    fallbackFixedToFlow: (PlaybackTimeline) -> PlaybackTimeline,
    play: (PlaybackTimeline) -> Unit,
    stop: () -> Unit,
    startSpeech: (FutureSpeechContent) -> Boolean,
    stopSpeech: () -> Unit,
    onSpeechMessageShown: () -> Unit,
    offset: CommentOffsetProvider,
    onClose: () -> Unit,
) {
    var playbackBounds by remember { mutableStateOf(IntSize.Zero) }
    var showSpeechDialog by remember { mutableStateOf(false) }
    var speechContent by remember { mutableStateOf(FutureSpeechContent.BOTH) }
    val snackbarHostState = remember { SnackbarHostState() }
    val speechActive = speechState.status == SpeechStatus.INITIALIZING ||
        speechState.status == SpeechStatus.SPEAKING
    val textMeasurer = rememberTextMeasurer()
    val commentFontFamily = LocalCommentFontFamily.current
    val density = LocalDensity.current
    val allocator = remember(density) { CommentLaneAllocator(with(density) { 8.dp.toPx() }) }
    val resolveAndPlay: (Boolean) -> Unit = { initial ->
        val source = createTimeline(initial)
        if (source.items.isNotEmpty() && playbackBounds.width > 0 && playbackBounds.height > 0) {
            fun fit(timeline: PlaybackTimeline): Pair<PlaybackTimeline, Map<Int, io.github.cragcoffee.memoripple.ui.playback.CommentTextMetrics>> {
                val metrics = measureCommentTextMetricsPx(
                    timeline = timeline,
                    textMeasurer = textMeasurer,
                    density = density,
                    availableWidthPx = playbackBounds.width.toFloat(),
                    fontFamily = commentFontFamily,
                )
                return resolveCommentLaneLayout(
                    timeline = timeline,
                    textMetrics = metrics,
                    availableHeightPx = playbackBounds.height.toFloat(),
                    minimumLaneHeightPx = with(density) { 26.dp.toPx() },
                    verticalSafetyGapPx = with(density) { 4.dp.toPx() },
                    availableWidthPx = playbackBounds.width.toFloat(),
                    desiredWaveAmplitudePx = with(density) {
                        COMMENT_WAVE_DESIRED_AMPLITUDE.toPx()
                    },
                    longCommentReadability = appSettings.longCommentReadability,
                ) to metrics
            }
            val requested = fit(source)
            val resolved = if (
                requested.first.validationIssue ==
                PlaybackValidationIssue.FIXED_COMMENT_DOES_NOT_FIT
            ) {
                fit(fallbackFixedToFlow(source))
            } else {
                requested
            }
            play(
                allocator.allocate(
                    timeline = resolved.first,
                    renderWidthsPx = resolved.second.mapValues { it.value.renderWidthPx },
                    containerWidthPx = playbackBounds.width.toFloat(),
                ),
            )
        }
    }

    LaunchedEffect(autoPlayRequest, state, playbackBounds) {
        val ready = state as? FutureRevealUiState.Ready ?: return@LaunchedEffect
        if (autoPlayRequest <= 0 || playbackBounds.width <= 0 ||
            !ready.context.comment.requiresFirstPresentation || animation.value.timeline != null
        ) return@LaunchedEffect
        resolveAndPlay(true)
    }
    LaunchedEffect(speechState.message) {
        speechState.message?.let {
            snackbarHostState.showSnackbar(it)
            onSpeechMessageShown()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black)
            .testTag("future_comment_stage"),
    ) {
        Box(Modifier.matchParentSize().testTag("future_comment_background_black"))
        Box(
            Modifier.matchParentSize().testTag(
                "future_comment_playback_${appSettings.playbackSpeed.storageId}_" +
                    appSettings.commentSize.storageId,
            ),
        )
        when (state) {
            FutureRevealUiState.Loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
            FutureRevealUiState.Unavailable -> Text(
                "まだ受け取れません",
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
            is FutureRevealUiState.Ready -> RevealContextContent(
                context = state.context,
                onReplay = { resolveAndPlay(false) },
                speechActive = speechActive,
                onSpeech = {
                    if (speechActive) stopSpeech() else showSpeechDialog = true
                },
            )
        }

        Box(Modifier.matchParentSize().padding(top = 72.dp, bottom = 48.dp)) {
            Box(
                Modifier.fillMaxSize().onSizeChanged { playbackBounds = it },
            ) {
                CommentRenderer(
                    animationState = animation,
                    offsetProvider = offset,
                    colors = stageCommentRendererColors(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "戻る",
                tint = Color.White,
            )
        }
        val playbackEngaged by remember(animation) {
            derivedStateOf { animation.value.status != PlaybackStatus.IDLE }
        }
        if (playbackEngaged) {
            IconButton(
                onClick = stop,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                    .testTag("stop_future_comment"),
            ) {
                Icon(Icons.Outlined.Stop, contentDescription = "再生を終了", tint = Color.White)
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }

    if (showSpeechDialog) {
        AlertDialog(
            onDismissRequest = { showSpeechDialog = false },
            modifier = Modifier.testTag("future_speech_dialog"),
            title = { Text("読み上げる内容") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FutureSpeechContent.entries.forEach { content ->
                        FilterChip(
                            selected = speechContent == content,
                            onClick = { speechContent = content },
                            label = {
                                Text(
                                    when (content) {
                                        FutureSpeechContent.SOURCE_DIARY -> "元の日記"
                                        FutureSpeechContent.FUTURE_COMMENT -> "未来コメント"
                                        FutureSpeechContent.BOTH -> "両方"
                                    },
                                )
                            },
                            modifier = Modifier.testTag(
                                "future_speech_${content.name.lowercase()}",
                            ),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (startSpeech(speechContent)) showSpeechDialog = false
                    },
                    modifier = Modifier.testTag("start_future_speech"),
                ) { Text("読み上げる") }
            },
            dismissButton = {
                TextButton(onClick = { showSpeechDialog = false }) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun RevealContextContent(
    context: FutureCommentRevealContext,
    onReplay: () -> Unit,
    speechActive: Boolean,
    onSpeech: () -> Unit,
) {
    val presentationCompleted = !context.comment.requiresFirstPresentation
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 76.dp,
            bottom = 80.dp,
        ),
    ) {
        item {
            Text(
                "${formatSourceDiaryDate(context.sourceDiary.diaryDateEpochDay)}の日記から",
                color = Color(0xFFBDBDBD),
                modifier = Modifier.testTag("source_diary_date"),
            )
            Text(
                context.sourceDiary.body,
                color = Color(0xFFF1F1F1),
                modifier = Modifier.padding(top = 24.dp).testTag("source_diary_body"),
                overflow = TextOverflow.Visible,
            )
        }
        if (presentationCompleted) {
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 28.dp),
                    color = Color(0xFF5A5A5A),
                )
                Text("未来から届いたコメント", color = Color(0xFFBDBDBD))
                Text(
                    context.comment.text,
                    color = Color.White,
                    modifier = Modifier.padding(top = 12.dp).testTag("future_comment_static"),
                )
                Text(
                    "${formatFutureDateTime(context.comment.revealedAt)}に受け取りました",
                    color = Color(0xFF9E9E9E),
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (!context.comment.expression.isDefault) {
                    Text(
                        context.comment.expression.summary(),
                        color = Color(0xFF9E9E9E),
                        modifier = Modifier.padding(top = 8.dp)
                            .testTag("future_comment_expression_summary"),
                    )
                }
                TextButton(
                    onClick = onReplay,
                    modifier = Modifier.padding(top = 8.dp).testTag("replay_future_comment_context"),
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = Color.White)
                    Text("もう一度再生", color = Color.White)
                }
                TextButton(
                    onClick = onSpeech,
                    modifier = Modifier.testTag("future_speech_action"),
                ) {
                    Icon(
                        if (speechActive) {
                            Icons.Outlined.Stop
                        } else {
                            Icons.AutoMirrored.Outlined.VolumeUp
                        },
                        contentDescription = null,
                        tint = Color.White,
                    )
                    Text(
                        if (speechActive) "読み上げを停止" else "読み上げ",
                        color = Color.White,
                    )
                }
            }
        }
    }
}

private fun formatSourceDiaryDate(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(
        DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.JAPAN),
    )

package io.github.cragcoffee.memoripple.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import io.github.cragcoffee.memoripple.domain.notes.ReaderProse
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import io.github.cragcoffee.memoripple.domain.ProseTyping
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextDecoration
import io.github.cragcoffee.memoripple.domain.BodyText
import io.github.cragcoffee.memoripple.domain.WorkCommentSyntax
import io.github.cragcoffee.memoripple.domain.comments.CommentLinkMarkers
import io.github.cragcoffee.memoripple.domain.playback.CommentAnimationState
import io.github.cragcoffee.memoripple.domain.playback.PlaybackItem
import io.github.cragcoffee.memoripple.domain.playback.PlaybackStatus
import io.github.cragcoffee.memoripple.ui.components.ProductCompactTopBar
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.playback.CommentRenderer
import io.github.cragcoffee.memoripple.ui.playback.stageCommentRendererColors

/**
 * An episode, opened to be read.
 *
 * The measure is narrow and the lines are far apart, because this is prose rather than a note being
 * worked on. Comments cross it only once play is pressed.
 */
@Composable
fun NoteReaderScreen(
    state: NoteReaderUiState,
    playbackStatus: PlaybackStatus,
    playbackState: kotlinx.coroutines.flow.StateFlow<CommentAnimationState>,
    canPlay: Boolean,
    offsetProvider: (PlaybackItem, Long, Float, Float) -> Float,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onOpenEpisode: (Long) -> Unit,
    onEdit: () -> Unit,
    speechActive: Boolean = false,
    speechPaused: Boolean = false,
    canSpeak: Boolean = false,
    onStartSpeech: () -> Unit = {},
    onPauseSpeech: () -> Unit = {},
    onResumeSpeech: () -> Unit = {},
    onStopSpeech: () -> Unit = {},
    activeSentenceIndex: Int? = null,
    onTapSentence: (Int) -> Unit = {},
    /** linkNo numbers that still belong to a living comment; the rest render as broken. */
    linkedNumbers: Set<Int> = emptySet(),
    /** A tap on a live marker's quiet mark — the comment flows. */
    onEmitLink: (Int) -> Unit = {},
    /** A swipe turns the page to the next / previous episode, following the finger (設定). */
    swipeTurns: Boolean = true,
) {
    val paragraphs = remember(state.body) { ReaderProse.paragraphs(state.body) }
    val sentences = remember(paragraphs) { ReaderProse.sentences(paragraphs) }
    // Each paragraph's sentences with their global numbers, so a tap and the follow-along
    // cursor speak the same counting the voice does.
    val sentencesByParagraph = remember(sentences) {
        sentences.withIndex().groupBy({ it.value.paragraphIndex }) { it.index to it.value.raw }
    }
    val scrollState = rememberScrollState()
    val paragraphTops = remember(paragraphs) { mutableStateMapOf<Int, Float>() }
    val paragraphLayouts = remember(paragraphs) { mutableStateMapOf<Int, TextLayoutResult>() }
    val viewportHeight = remember { mutableIntStateOf(0) }
    // The page follows the voice: as the spoken sentence changes, its line is brought to the
    // upper third — far enough down to keep what was just read in sight.
    LaunchedEffect(activeSentenceIndex) {
        val index = activeSentenceIndex ?: return@LaunchedEffect
        val sentence = sentences.getOrNull(index) ?: return@LaunchedEffect
        val top = paragraphTops[sentence.paragraphIndex] ?: return@LaunchedEffect
        val layout = paragraphLayouts[sentence.paragraphIndex]
        val local = sentencesByParagraph[sentence.paragraphIndex].orEmpty()
        val ordinal = local.indexOfFirst { it.first == index }
        val lineTop = layout?.let { result ->
            val charStart = 1 + local.take(ordinal.coerceAtLeast(0))
                .sumOf { it.second.rubyDisplayLength() }
            result.getLineTop(
                result.getLineForOffset(charStart.coerceIn(0, result.layoutInput.text.length)),
            )
        } ?: 0f
        val target = (top + lineTop - viewportHeight.intValue / 3f).toInt().coerceAtLeast(0)
        scrollState.animateScrollTo(target)
    }
    // ページをめくる: the page rides the finger sideways; let go past a quarter of the width and
    // it slides off while the neighbour slides in — right to left for the next episode, left
    // to right for the previous, the way a book opens. The neighbour is drawn beside the page
    // from what the state already knows of it, so nothing waits on a round trip; only once the
    // page has left does the reader ask for the new episode, and the offset snaps back to 0 as
    // the neighbour becomes the page.
    val pageWidth = remember { mutableIntStateOf(0) }
    val turn = remember { Animatable(0f) }
    val turnScope = rememberCoroutineScope()
    var turning by remember { mutableStateOf(false) }
    val stateNow = rememberUpdatedState(state)
    val turnTo: (Long, Int) -> Unit = { id, direction ->
        if (!turning) {
            turning = true
            turnScope.launch {
                val width = pageWidth.intValue.toFloat()
                turn.animateTo(-direction * width, tween(durationMillis = 260, easing = FastOutSlowInEasing))
                onOpenEpisode(id)
                withTimeoutOrNull(1_500) { snapshotFlow { stateNow.value.memoId }.first { it == id } }
                scrollState.scrollTo(0)
                turn.snapTo(0f)
                turning = false
            }
        }
    }
    val settleTurn: () -> Unit = {
        turnScope.launch { turn.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
    }
    val swipe = if (swipeTurns) {
        Modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    val width = pageWidth.intValue.toFloat()
                    val current = stateNow.value
                    val x = turn.value
                    when {
                        x < -width / 4 && current.nextMemoId != null -> turnTo(current.nextMemoId, 1)
                        x > width / 4 && current.previousMemoId != null -> turnTo(current.previousMemoId, -1)
                        else -> settleTurn()
                    }
                },
                onDragCancel = settleTurn,
            ) { change, dragAmount ->
                if (!turning) {
                    change.consume()
                    val width = pageWidth.intValue.toFloat()
                    val current = stateNow.value
                    // At either end of the note the page stays put: there is nothing to turn to.
                    val lower = if (current.nextMemoId == null) 0f else -width
                    val upper = if (current.previousMemoId == null) 0f else width
                    val next = (turn.value + dragAmount).coerceIn(lower, upper)
                    turnScope.launch { turn.snapTo(next) }
                }
            }
        }
    } else {
        Modifier
    }
    Scaffold(
        topBar = {
            ProductCompactTopBar(
                modifier = Modifier.testTag("note_reader_top_bar"),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(ProductSize.minimumTouchTarget),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                    }
                },
                centerContent = {
                    Text(
                        state.noteTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                action = {
                    // Reading's three verbs, oldest habit last: flow the comments, hear the
                    // words, change them.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    when (playbackStatus) {
                        PlaybackStatus.IDLE -> IconButton(
                            onClick = onPlay,
                            enabled = canPlay,
                            modifier = Modifier.size(ProductSize.minimumTouchTarget)
                                .testTag("reader_play"),
                        ) {
                            Icon(
                                Icons.Outlined.PlayArrow,
                                contentDescription = "コメントを流す",
                                tint = if (canPlay) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            )
                        }

                        PlaybackStatus.PLAYING -> IconButton(
                            onClick = onPause,
                            modifier = Modifier.size(ProductSize.minimumTouchTarget)
                                .testTag("reader_pause"),
                        ) {
                            Icon(
                                Icons.Outlined.Pause,
                                contentDescription = "コメントを止める",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }

                        PlaybackStatus.PAUSED -> Row {
                            IconButton(
                                onClick = onResume,
                                modifier = Modifier.size(ProductSize.minimumTouchTarget)
                                    .testTag("reader_resume"),
                            ) {
                                Icon(
                                    Icons.Outlined.PlayArrow,
                                    contentDescription = "コメントを続ける",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(
                                onClick = onStop,
                                modifier = Modifier.size(ProductSize.minimumTouchTarget)
                                    .testTag("reader_stop"),
                            ) {
                                Icon(Icons.Outlined.Stop, contentDescription = "コメントを終える")
                            }
                        }
                    }
                    // The voice gets the comment stream's verbs: while it reads, pause and
                    // stop stand side by side; paused, the same spot resumes or lets go.
                    when {
                        speechActive -> {
                            IconButton(
                                onClick = onPauseSpeech,
                                modifier = Modifier.size(ProductSize.minimumTouchTarget)
                                    .testTag("reader_speech_pause"),
                            ) {
                                Icon(
                                    Icons.Outlined.Pause,
                                    contentDescription = "読み上げを一時停止",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(
                                onClick = onStopSpeech,
                                modifier = Modifier.size(ProductSize.minimumTouchTarget)
                                    .testTag("reader_speech_stop"),
                            ) {
                                Icon(Icons.Outlined.Stop, contentDescription = "読み上げを停止")
                            }
                        }
                        speechPaused -> {
                            IconButton(
                                onClick = onResumeSpeech,
                                modifier = Modifier.size(ProductSize.minimumTouchTarget)
                                    .testTag("reader_speech_resume"),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.VolumeUp,
                                    contentDescription = "読み上げを再開",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(
                                onClick = onStopSpeech,
                                modifier = Modifier.size(ProductSize.minimumTouchTarget)
                                    .testTag("reader_speech_stop"),
                            ) {
                                Icon(Icons.Outlined.Stop, contentDescription = "読み上げを停止")
                            }
                        }
                        else -> IconButton(
                            onClick = onStartSpeech,
                            enabled = canSpeak,
                            modifier = Modifier.size(ProductSize.minimumTouchTarget)
                                .testTag("reader_speech"),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.VolumeUp,
                                contentDescription = "読み上げ",
                            )
                        }
                    }
                        TextButton(
                            onClick = onEdit,
                            modifier = Modifier.testTag("reader_edit"),
                        ) { Text("編集") }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding)
                .onSizeChanged {
                    viewportHeight.intValue = it.height
                    pageWidth.intValue = it.width
                }
                .then(swipe),
        ) {
            val width = pageWidth.intValue.toFloat()
            if (turn.value != 0f) {
                state.previous?.let { neighbour ->
                    ReaderNeighbourPage(neighbour, Modifier.fillMaxSize().graphicsLayer { translationX = turn.value - width })
                }
                state.next?.let { neighbour ->
                    ReaderNeighbourPage(neighbour, Modifier.fillMaxSize().graphicsLayer { translationX = turn.value + width })
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = turn.value }
                    .verticalScroll(scrollState)
                    .padding(horizontal = 22.dp, vertical = ProductSpacing.lg)
                    .testTag("note_reader_body"),
            ) {
                Text(
                    state.episodeTitle,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = ProductSpacing.xs, bottom = ProductSpacing.lg),
                )
                paragraphs.forEachIndexed { paragraphIndex, paragraph ->
                    if (paragraph.isEmpty()) {
                        // A blank line the writer left: one line of air, kept as written.
                        Spacer(
                            Modifier.height(
                                with(LocalDensity.current) { 30.sp.toDp() },
                            ),
                        )
                        return@forEachIndexed
                    }
                    val local = sentencesByParagraph[paragraphIndex].orEmpty()
                    ReaderParagraph(
                        sentences = local,
                        activeSentenceIndex = activeSentenceIndex,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 30.sp),
                        onTapSentence = onTapSentence,
                        linkedNumbers = linkedNumbers,
                        onEmitLink = onEmitLink,
                        onPositioned = { y -> paragraphTops[paragraphIndex] = y },
                        onLayout = { layout -> paragraphLayouts[paragraphIndex] = layout },
                        modifier = Modifier.padding(bottom = ProductSpacing.md),
                    )
                }
                Spacer(Modifier.height(ProductSpacing.xxl))
            }
            ReaderPlaybackLayer(
                state = playbackState,
                offsetProvider = offsetProvider,
                modifier = Modifier.fillMaxSize(),
            )
            Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ProductSize.minimumTouchTarget)
                        .padding(horizontal = ProductSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { state.previousMemoId?.let { id -> turnTo(id, -1) } },
                        enabled = state.previousMemoId != null,
                        modifier = Modifier.testTag("reader_previous"),
                    ) { Text("← 前の話") }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { state.nextMemoId?.let { id -> turnTo(id, 1) } },
                        enabled = state.nextMemoId != null,
                        modifier = Modifier.testTag("reader_next"),
                    ) { Text("次の話 →") }
                }
            }
        }
    }
}

/**
 * The page beside the one being read, drawn only while a turn is under way: the same title and
 * prose as the page itself, in the same measure, but still — nothing on it is spoken or tapped.
 */
@Composable
private fun ReaderNeighbourPage(neighbour: ReaderNeighbour, modifier: Modifier = Modifier) {
    val paragraphs = remember(neighbour.body) { ReaderProse.paragraphs(neighbour.body) }
    val sentences = remember(paragraphs) { ReaderProse.sentences(paragraphs) }
    val sentencesByParagraph = remember(sentences) {
        sentences.withIndex().groupBy({ it.value.paragraphIndex }) { it.index to it.value.raw }
    }
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 22.dp, vertical = ProductSpacing.lg)
            .testTag("note_reader_neighbour_${neighbour.memoId}"),
    ) {
        Text(
            neighbour.title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = ProductSpacing.xs, bottom = ProductSpacing.lg),
        )
        paragraphs.forEachIndexed { paragraphIndex, paragraph ->
            if (paragraph.isEmpty()) {
                Spacer(Modifier.height(with(LocalDensity.current) { 30.sp.toDp() }))
                return@forEachIndexed
            }
            ReaderParagraph(
                sentences = sentencesByParagraph[paragraphIndex].orEmpty(),
                activeSentenceIndex = null,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 30.sp),
                onTapSentence = {},
                linkedNumbers = emptySet(),
                onEmitLink = {},
                onPositioned = {},
                onLayout = {},
                modifier = Modifier.padding(bottom = ProductSpacing.md),
            )
        }
    }
}

/** The display width of a raw sentence: ruby folds to its base words, markers vanish. */
private fun String.rubyDisplayLength(): Int =
    ProseTyping.rubyRuns(CommentLinkMarkers.strip(this).text).sumOf { it.text.length }

/**
 * A paragraph whose sentences the voice can point at and the finger can choose.
 *
 * `｜親文字《ふりがな》` is drawn as ruby — the reading in half-size type over the words, each
 * group one inline box whose floor is TextBottom, so the same face's descent lines the baselines
 * up. The sentence being spoken wears a quiet tint; a tap anywhere in a sentence hands its
 * number back, and the voice picks the book up from there.
 */
@Composable
private fun ReaderParagraph(
    sentences: List<Pair<Int, String>>,
    activeSentenceIndex: Int?,
    style: TextStyle,
    onTapSentence: (Int) -> Unit,
    linkedNumbers: Set<Int>,
    onEmitLink: (Int) -> Unit,
    onPositioned: (Float) -> Unit,
    onLayout: (TextLayoutResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val rubyStyle = style.copy(
        fontSize = style.fontSize * RUBY_SCALE,
        lineHeight = style.fontSize * RUBY_SCALE,
    )
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val linkColor = MaterialTheme.colorScheme.primary
    val brokenColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Every sentence's character range in the built string, for the tint and for the tap.
    val ranges = remember(sentences) { mutableListOf<Pair<Int, IntRange>>() }
    // Where each コメントリンク marker's quiet mark sits in the built string, and its numbers.
    val markerAnchors = remember(sentences) { mutableMapOf<Int, List<Int>>() }
    val inline = mutableMapOf<String, InlineTextContent>()
    val text = buildAnnotatedString {
        append("　")
        ranges.clear()
        markerAnchors.clear()
        sentences.forEach { (globalIndex, raw) ->
            val startOffset = length
            val strippedSentence = CommentLinkMarkers.strip(raw)
            strippedSentence.markers.forEach { marker ->
                // The marker's place on the page: its prefix with the ruby folded away.
                val position = startOffset +
                    ProseTyping.strip(strippedSentence.text.substring(0, marker.offset)).length
                val anchor = if (position <= 1) 1 else position - 1
                markerAnchors[anchor] = markerAnchors[anchor].orEmpty() + marker.number
            }
            ProseTyping.rubyRuns(strippedSentence.text).forEachIndexed { runIndex, run ->
                if (run.reading == null) {
                    append(run.text)
                } else {
                    val id = "ruby_${globalIndex}_$runIndex"
                    appendInlineContent(id, run.text)
                    val baseWidth = measurer.measure(run.text, style).size.width
                    val readingWidth = measurer.measure(run.reading, rubyStyle).size.width
                    val widthSp = with(density) {
                        maxOf(baseWidth, readingWidth).toFloat().toSp()
                    }
                    inline[id] = InlineTextContent(
                        Placeholder(
                            width = widthSp,
                            height = style.lineHeight,
                            // The box's floor is the surrounding text's floor, and the base
                            // text sits on it — same face, same descent, so the baselines
                            // meet. The reading stands in the leading above, which the 30sp
                            // line height already keeps clear.
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextBottom,
                        ),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                            modifier = Modifier.fillMaxHeight(),
                        ) {
                            Text(
                                run.reading,
                                style = rubyStyle,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Visible,
                            )
                            Text(
                                run.text,
                                style = style.copy(lineHeight = style.fontSize),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Visible,
                            )
                        }
                    }
                }
            }
            val range = startOffset until length
            ranges += globalIndex to range
            if (globalIndex == activeSentenceIndex) {
                addStyle(SpanStyle(background = highlight), range.first, range.last + 1)
            }
        }
        // The quiet marks: the character just before where each marker stood carries a thin
        // underline — the accent while its comment lives, greyed once the comment is gone.
        markerAnchors.forEach { (anchor, numbers) ->
            if (anchor < length) {
                val live = numbers.any { it in linkedNumbers }
                addStyle(
                    SpanStyle(
                        color = if (live) linkColor else brokenColor,
                        textDecoration = TextDecoration.Underline,
                    ),
                    anchor,
                    anchor + 1,
                )
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        style = style,
        inlineContent = inline,
        onTextLayout = { result ->
            layout = result
            onLayout(result)
        },
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { onPositioned(it.positionInParent().y) }
            .pointerInput(ranges.toList(), markerAnchors.toMap(), linkedNumbers) {
                detectTapGestures { position ->
                    val result = layout ?: return@detectTapGestures
                    val offset = result.getOffsetForPosition(position)
                    val numbers = markerAnchors[offset]
                    if (numbers != null) {
                        // A live mark flows its comment; a broken one swallows the tap.
                        numbers.filter { it in linkedNumbers }.forEach(onEmitLink)
                        return@detectTapGestures
                    }
                    ranges.firstOrNull { (_, range) -> offset in range }
                        ?.let { (globalIndex, _) -> onTapSentence(globalIndex) }
                }
            },
    )
}

/** 印刷の慣例に合わせ、ルビは親文字の半分。 */
private const val RUBY_SCALE = 0.5f

/** The one reader of the per-frame playback state, so only this layer recomposes with it. */
@Composable
private fun ReaderPlaybackLayer(
    state: kotlinx.coroutines.flow.StateFlow<CommentAnimationState>,
    offsetProvider: (PlaybackItem, Long, Float, Float) -> Float,
    modifier: Modifier = Modifier,
) {
    val playback = state.collectAsStateWithLifecycle()
    // The reading page darkens under white stroked lettering while comments fly — the
    // video site's look, not the page's own ink.
    CommentRenderer(
        animationState = playback,
        offsetProvider = offsetProvider,
        colors = stageCommentRendererColors(),
        modifier = modifier,
        dimBehind = true,
    )
}

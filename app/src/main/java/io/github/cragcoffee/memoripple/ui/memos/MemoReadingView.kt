package io.github.cragcoffee.memoripple.ui.memos

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PriorityHigh
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material3.Icon
import io.github.cragcoffee.memoripple.domain.TaskGlyphs
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.domain.BodyReading
import io.github.cragcoffee.memoripple.domain.ReadableMarkup
import io.github.cragcoffee.memoripple.domain.ReadingLine
import io.github.cragcoffee.memoripple.domain.RenderedKind
import io.github.cragcoffee.memoripple.domain.WorkLineType
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.playback.inlineCommentRendererColors
import io.github.cragcoffee.memoripple.ui.playback.textFor

/**
 * The memo as a document rather than as text being written.
 *
 * Nothing here can change a word. What it adds is what an editable field cannot give without
 * putting the caret at risk: markers come off, a heading folds away what it owns, a checkbox is
 * tapped where it sits, and a reference is followed by touching it.
 */
@Composable
fun MemoReadingView(
    body: String,
    collapsed: Set<Int>,
    onToggleFold: (Int) -> Unit,
    onToggleTask: (Int) -> Unit,
    onOpenLink: (String) -> Unit,
    isLinkResolved: (String) -> Boolean,
    modifier: Modifier = Modifier,
    /** linkNo numbers that still belong to a live comment; the rest render as broken. */
    linkedNumbers: Set<Int> = emptySet(),
    /** A tap on a live marker's quiet mark — the comment flows. */
    onEmitLink: (Int) -> Unit = {},
    /** When set, the view scrolls the given source line into view once, then reports back. */
    scrollToLine: Int? = null,
    onScrolledToLine: () -> Unit = {},
    /** 読み上げ追従: the source line the voice is reading, highlighted and kept in view. */
    followLine: Int? = null,
    /** Google ドキュメント式: a double tap anywhere on the page returns to writing. */
    onDoubleTapEdit: (() -> Unit)? = null,
    /** While the voice is engaged, a tap on a line re-aims the reading there. */
    onTapLine: ((Int) -> Unit)? = null,
    /**
     * A double tap on a written line returns to writing *at that line* — the line's own door,
     * ahead of the page's [onDoubleTapEdit], which an empty place still answers with.
     */
    onDoubleTapLine: ((Int) -> Unit)? = null,
    /** Draw 「・」 before a task box, as the outliner's lines do; a memo's page leaves the box alone. */
    taskDot: Boolean = false,
    /**
     * A memo written in blocks (docs/MEMO_CONTENT_BLOCKS.md): the photos to draw before a source
     * line (a key equal to the line count means after the last), drawn by [photoContent]. The
     * lines stay the body's lines, so folding, the voice and the jumps to a line are unchanged.
     */
    photoBreaks: Map<Int, List<Long>> = emptyMap(),
    photoContent: (@Composable (List<Long>) -> Unit)? = null,
) {
    val lines = remember(body) { BodyReading.lines(body) }
    // The air the writer left: for each content line, how many blank lines stand just before
    // it in the source. BodyReading drops blanks; the page puts the space back here.
    val blankGaps = remember(body) {
        val gaps = mutableMapOf<Int, Int>()
        var run = 0
        body.lines().forEachIndexed { index, rawLine ->
            if (rawLine.isBlank()) {
                run += 1
            } else {
                if (run > 0) gaps[index] = run
                run = 0
            }
        }
        gaps
    }
    val visible = remember(lines, collapsed) { BodyReading.visible(lines, collapsed) }
    val positionOf = remember(lines) { lines.withIndex().associate { it.value.sourceLine to it.index } }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // The list's items: the visible lines, with the photos that stand before each; a line's item
    // index is looked up here, since photos take places in the list too.
    val entries = remember(visible, photoBreaks, photoContent) {
        if (photoContent == null) visible.map { ReadingEntry.Line(it) } else readingEntries(visible, photoBreaks)
    }
    fun itemOf(sourceLine: Int): Int = entries.indexOfFirst { it is ReadingEntry.Line && it.line.sourceLine == sourceLine }
    androidx.compose.runtime.LaunchedEffect(scrollToLine, visible) {
        if (scrollToLine != null) {
            val index = itemOf(scrollToLine)
            if (index >= 0) listState.animateScrollToItem(index)
            onScrolledToLine()
        }
    }
    // The page follows the voice: when the spoken line is not comfortably in view, it is
    // brought to the upper third — far enough down to keep what was just read in sight.
    androidx.compose.runtime.LaunchedEffect(followLine, visible) {
        val target = followLine ?: return@LaunchedEffect
        val index = itemOf(target)
        if (index < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        val comfortable = item != null && item.offset >= 0 &&
            item.offset + item.size <= info.viewportEndOffset * 2 / 3
        if (!comfortable) {
            listState.animateScrollToItem(index, -info.viewportEndOffset / 3)
        }
    }
    val doubleTapModifier = if (onDoubleTapEdit != null) {
        Modifier.pointerInput(onDoubleTapEdit) {
            detectTapGestures(
                onDoubleTap = { onDoubleTapEdit() },
            )
        }
    } else {
        Modifier
    }

    if (entries.isEmpty()) {
        Box(modifier.fillMaxWidth().then(doubleTapModifier).testTag("memo_reading_empty")) {
            Text(
                "まだ本文がありません。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val followHighlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().then(doubleTapModifier).testTag("memo_reading_view"),
        verticalArrangement = Arrangement.spacedBy(ProductSpacing.xs),
    ) {
        items(entries, key = ReadingEntry::key) { entry ->
            if (entry is ReadingEntry.Photos) {
                Box(Modifier.fillMaxWidth().padding(vertical = ProductSpacing.xs).testTag("reading_photos_${entry.breakLine}")) {
                    photoContent?.invoke(entry.attachmentIds)
                }
                return@items
            }
            val line = (entry as ReadingEntry.Line).line
            val position = positionOf[line.sourceLine] ?: 0
            blankGaps[line.sourceLine]?.let { count ->
                Spacer(
                    Modifier
                        .height(readingAirHeight() * count)
                        .testTag("reading_air_${line.sourceLine}"),
                )
            }
            val rowModifier = if (line.sourceLine == followLine) {
                Modifier.fillMaxWidth().background(followHighlight)
                    .testTag("reading_follow_${line.sourceLine}")
            } else {
                Modifier.fillMaxWidth()
            }
            // The quiet jump: no ripple, and the row's own doors (links, checkboxes,
            // folds) still open first — they sit deeper and win the tap.
            val tapModifier = if (onTapLine != null) {
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onTapLine(line.sourceLine) }
            } else if (onDoubleTapLine != null) {
                Modifier.pointerInput(onDoubleTapLine, line.sourceLine) {
                    detectTapGestures(onDoubleTap = { onDoubleTapLine(line.sourceLine) })
                }
            } else {
                Modifier
            }
            Box(rowModifier.then(tapModifier)) {
            ReadingRow(
                line = line,
                foldable = BodyReading.isFoldable(lines, position),
                folded = line.sourceLine in collapsed,
                hiddenCount = BodyReading.hiddenCount(lines, position),
                onToggleFold = { onToggleFold(line.sourceLine) },
                onToggleTask = { onToggleTask(line.sourceLine) },
                taskDot = taskDot,
                onOpenLink = onOpenLink,
                isLinkResolved = isLinkResolved,
                linkedNumbers = linkedNumbers,
                onEmitLink = onEmitLink,
            )
            }
        }
        item { Spacer(Modifier.height(ProductSpacing.xxl)) }
    }
}

/** A place in the reading list: a line of the body, or the photos that stand before one. */
private sealed interface ReadingEntry {
    val key: Any

    data class Line(val line: ReadingLine) : ReadingEntry {
        override val key: Any get() = line.sourceLine
    }

    data class Photos(val breakLine: Int, val attachmentIds: List<Long>) : ReadingEntry {
        override val key: Any get() = "photos_$breakLine"
    }
}

/**
 * The visible lines with each photo break put before the first visible line at or after it; the
 * breaks after the last line close the list. A break inside a folded part moves to the next
 * visible line, so a photo is never lost from the page.
 */
private fun readingEntries(visible: List<ReadingLine>, breaks: Map<Int, List<Long>>): List<ReadingEntry> {
    val pending = breaks.toSortedMap().entries.toMutableList()
    val result = ArrayList<ReadingEntry>()
    visible.forEach { line ->
        while (pending.isNotEmpty() && pending.first().key <= line.sourceLine) {
            val (at, ids) = pending.removeAt(0)
            result += ReadingEntry.Photos(at, ids)
        }
        result += ReadingEntry.Line(line)
    }
    pending.forEach { (at, ids) -> result += ReadingEntry.Photos(at, ids) }
    return result
}

/** One blank source line's worth of vertical air, matched to the body text's line height. */
@Composable
private fun readingAirHeight(): androidx.compose.ui.unit.Dp = with(
    androidx.compose.ui.platform.LocalDensity.current,
) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() }

@Composable
private fun ReadingRow(
    line: ReadingLine,
    foldable: Boolean,
    folded: Boolean,
    hiddenCount: Int,
    onToggleFold: () -> Unit,
    onToggleTask: () -> Unit,
    onOpenLink: (String) -> Unit,
    isLinkResolved: (String) -> Boolean,
    linkedNumbers: Set<Int>,
    onEmitLink: (Int) -> Unit,
    taskDot: Boolean = false,
) {
    val text = readingText(line, onOpenLink, isLinkResolved, linkedNumbers, onEmitLink)
    val style = readingStyle(line.type)
    val indent = (line.depth * INDENT_STEP.value).dp

    when (line.type) {
        WorkLineType.HEADING -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = indent, top = ProductSpacing.sm)
                .then(
                    if (foldable) {
                        Modifier
                            .clickable(onClick = onToggleFold)
                            .semantics {
                                contentDescription = if (folded) {
                                    "${line.text}を開く。${hiddenCount}行が隠れています"
                                } else {
                                    "${line.text}を折りたたむ"
                                }
                            }
                            .testTag("reading_fold_${line.sourceLine}")
                    } else {
                        Modifier
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (foldable) {
                Icon(
                    if (folded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                    contentDescription = null,
                    modifier = Modifier.size(GLYPH_SIZE),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.width(GLYPH_SIZE))
            }
            Text(
                text,
                style = style,
                modifier = Modifier.weight(1f).padding(start = ProductSpacing.xs),
            )
            if (folded && hiddenCount > 0) {
                Text(
                    "$hiddenCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("reading_hidden_count_${line.sourceLine}"),
                )
            }
        }

        WorkLineType.TASK, WorkLineType.TASK_DONE -> {
            val done = line.type == WorkLineType.TASK_DONE
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = indent)
                    .clickable(onClick = onToggleTask)
                    .semantics {
                        contentDescription = if (done) {
                            "${line.text}。完了。未完了に戻す"
                        } else {
                            "${line.text}。未完了。完了にする"
                        }
                    }
                    .testTag("reading_task_${line.sourceLine}"),
                verticalAlignment = Alignment.Top,
            ) {
                // The box is the very glyph the editor draws (TaskGlyphs), set in the line's own
                // style so it is the same size and sits on the same baseline as the words, in the
                // quiet grey of the other marks — the row is the target, so it is no button.
                if (taskDot) {
                    Text(
                        "・",
                        style = style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("reading_task_dot_${line.sourceLine}"),
                    )
                }
                Text(
                    (if (done) TaskGlyphs.DONE_GLYPH else TaskGlyphs.OPEN_GLYPH).trimEnd(),
                    style = style,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = ProductSpacing.xs)
                        .testTag("reading_task_box_${line.sourceLine}"),
                )
                Text(
                    text,
                    style = style.copy(
                        textDecoration = if (done) TextDecoration.LineThrough else null,
                        color = if (done) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            Color.Unspecified
                        },
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        WorkLineType.ITEM -> Row(
            modifier = Modifier.fillMaxWidth().padding(start = indent),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                "・",
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(GLYPH_SIZE),
            )
            Text(text, style = style, modifier = Modifier.weight(1f))
        }

        WorkLineType.NOTE -> Row(
            modifier = Modifier.fillMaxWidth().padding(start = indent),
            verticalAlignment = Alignment.Top,
        ) {
            // The rule a quotation is given everywhere else, so the aside reads as an aside.
            Box(
                Modifier
                    .padding(end = ProductSpacing.sm, top = 2.dp)
                    .width(2.dp)
                    .height(NOTE_RULE_HEIGHT)
                    .background(
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(1.dp),
                    ),
            )
            Text(
                text,
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        WorkLineType.IMPORTANT, WorkLineType.QUESTION -> Row(
            modifier = Modifier.fillMaxWidth().padding(start = indent),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                if (line.type == WorkLineType.IMPORTANT) {
                    Icons.Outlined.PriorityHigh
                } else {
                    Icons.Outlined.QuestionMark
                },
                contentDescription = if (line.type == WorkLineType.IMPORTANT) "重要" else "疑問",
                modifier = Modifier.size(GLYPH_SIZE).padding(end = 2.dp, top = 2.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text,
                style = style,
                modifier = Modifier.weight(1f).padding(start = ProductSpacing.xs),
            )
        }

        WorkLineType.PLAIN -> Text(
            text,
            style = style,
            modifier = Modifier.fillMaxWidth().padding(start = indent),
        )
    }
}

/** The line with its markers gone, its decoration applied, and its references touchable. */
@Composable
private fun readingText(
    line: ReadingLine,
    onOpenLink: (String) -> Unit,
    isLinkResolved: (String) -> Boolean,
    linkedNumbers: Set<Int>,
    onEmitLink: (Int) -> Unit,
): AnnotatedString {
    val palette = inlineCommentRendererColors()
    val linkColor = MaterialTheme.colorScheme.primary
    val unresolvedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val readable = remember(line.text) { ReadableMarkup.of(line.text) }
    // A line that is nothing but markers still deserves marks to touch: one quiet dot each.
    val displayText = if (readable.text.isEmpty() && readable.linkMarkers.isNotEmpty()) {
        "・".repeat(readable.linkMarkers.size)
    } else {
        readable.text
    }
    return buildAnnotatedString {
        append(displayText)
        // コメントリンク: the marker's own text is gone; its place is a thin underline on
        // the character just before it (or the character after, when it opened the line).
        // Live marks answer a tap by flowing their comment; a mark whose comment was
        // deleted stays visible but grey, and promises nothing.
        readable.linkMarkers
            .groupBy { marker ->
                when {
                    readable.text.isEmpty() -> readable.linkMarkers.indexOf(marker)
                    marker.standsAlone -> 0
                    else -> marker.anchorStart
                }
            }
            .forEach { (anchor, group) ->
                val start = anchor.coerceIn(0, (displayText.length - 1).coerceAtLeast(0))
                val end = (start + 1).coerceAtMost(displayText.length)
                if (end <= start) return@forEach
                val liveNumbers = group.map { it.number }.filter { it in linkedNumbers }
                if (liveNumbers.isNotEmpty()) {
                    addLink(
                        LinkAnnotation.Clickable(
                            tag = "comment-link-${group.first().number}",
                            styles = TextLinkStyles(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                        ) { liveNumbers.forEach(onEmitLink) },
                        start,
                        end,
                    )
                } else {
                    addStyle(
                        SpanStyle(
                            color = unresolvedColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                        start,
                        end,
                    )
                }
            }
        readable.spans.forEach { span ->
            when (span.kind) {
                RenderedKind.BOLD ->
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), span.start, span.end)

                RenderedKind.HIGHLIGHT -> addStyle(
                    SpanStyle(background = palette.textFor(span.color).copy(alpha = 0.28f)),
                    span.start,
                    span.end,
                )

                RenderedKind.LINK -> {
                    val title = span.linkTitle.orEmpty()
                    val resolved = isLinkResolved(title)
                    if (resolved) {
                        addLink(
                            LinkAnnotation.Clickable(
                                tag = title,
                                styles = TextLinkStyles(SpanStyle(color = linkColor)),
                            ) { onOpenLink(title) },
                            span.start,
                            span.end,
                        )
                    } else {
                        // A reference to a memo that does not exist yet still shows; it simply
                        // does not pretend to lead anywhere.
                        addStyle(
                            SpanStyle(
                                color = unresolvedColor,
                                textDecoration = TextDecoration.Underline,
                            ),
                            span.start,
                            span.end,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun readingStyle(type: WorkLineType): TextStyle = when (type) {
    WorkLineType.HEADING -> MaterialTheme.typography.titleMedium
    WorkLineType.IMPORTANT -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
    WorkLineType.NOTE -> MaterialTheme.typography.bodyMedium
    else -> MaterialTheme.typography.bodyLarge
}

private val GLYPH_SIZE = 24.dp
private val NOTE_RULE_HEIGHT = 20.dp
private val INDENT_STEP = 16.dp

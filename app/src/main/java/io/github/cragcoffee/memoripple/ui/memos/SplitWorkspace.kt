package io.github.cragcoffee.memoripple.ui.memos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.BodyReading
import io.github.cragcoffee.memoripple.domain.BodyText
import io.github.cragcoffee.memoripple.domain.WorkCommentParser
import io.github.cragcoffee.memoripple.domain.WorkLineType
import io.github.cragcoffee.memoripple.domain.notes.NoteStructure
import io.github.cragcoffee.memoripple.domain.notes.ReaderProse
import io.github.cragcoffee.memoripple.domain.split.SplitReference
import io.github.cragcoffee.memoripple.domain.split.SplitReferenceKind
import io.github.cragcoffee.memoripple.domain.split.SplitWorkspacePolicy
import io.github.cragcoffee.memoripple.ui.attachments.AttachmentImageLoader
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 分割表示 — the editor above, something to work against below (or beside, when the screen
 * is wider than tall). A memo reference edits and saves in place, so both panes are live
 * drafts; notes and outlines below stay reading surfaces. One workspace, not two boxed
 * apps: the divider is a thin line with a short handle, and the reference sits on a
 * surface one step quieter.
 */
@Composable
internal fun SplitWorkspaceScaffold(
    reference: SplitReference,
    ratio: Float,
    onRatioChange: (Float) -> Unit,
    onChangeReference: () -> Unit,
    onEditReference: () -> Unit,
    onClose: () -> Unit,
    onRetarget: (Long) -> Unit,
    primary: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // Orientation reads the window's configuration, never the box's own constraints: the
        // keyboard shrinks those constraints below the screen's width, which would flip the
        // arrangement sideways mid-IME — recreating both panes and closing the keyboard the
        // writer just asked for.
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val horizontal = SplitWorkspacePolicy.isHorizontal(
            configuration.screenWidthDp,
            configuration.screenHeightDp,
        )
        val totalPx = with(density) {
            (if (horizontal) maxWidth else maxHeight).toPx()
        }
        val clamped = SplitWorkspacePolicy.clampRatio(ratio)

        // The panes never rearrange themselves while the keyboard arrives: swapping the layout
        // mid-IME tears down the focused field's input session, and the keyboard the writer
        // asked for closes before it is even drawn. The ratio simply holds; both panes shrink
        // with the window and each scrolls its own cursor into view.
        val referencePane: @Composable () -> Unit = {
            ReferencePane(
                reference = reference,
                onChangeReference = onChangeReference,
                onEditReference = onEditReference,
                onClose = onClose,
                onRetarget = onRetarget,
            )
        }
        val divider: @Composable () -> Unit = {
            SplitDivider(
                horizontal = horizontal,
                ratio = clamped,
                onDrag = { delta ->
                    onRatioChange(SplitWorkspacePolicy.ratioAfterDrag(clamped, delta, totalPx))
                },
                onReset = { onRatioChange(SplitWorkspacePolicy.DEFAULT_RATIO) },
                onGrowPrimary = { onRatioChange(SplitWorkspacePolicy.clampRatio(clamped + 0.1f)) },
                onGrowReference = { onRatioChange(SplitWorkspacePolicy.clampRatio(clamped - 0.1f)) },
            )
        }
        if (horizontal) {
            Row(Modifier.fillMaxSize()) {
                Box(
                    Modifier.weight(clamped).fillMaxHeight()
                        .semantics { paneTitle = "編集中の領域" },
                ) { primary() }
                divider()
                Box(
                    Modifier.weight(1f - clamped).fillMaxHeight()
                        .semantics { paneTitle = "参照中の領域" },
                ) { referencePane() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(clamped)
                        .fillMaxWidth()
                        .semantics { paneTitle = "編集中の領域" },
                ) { primary() }
                divider()
                Box(
                    Modifier
                        .weight(1f - clamped)
                        .fillMaxWidth()
                        .semantics { paneTitle = "参照中の領域" },
                ) { referencePane() }
            }
        }
    }
}

/** The quiet boundary: a hairline with a short handle, dragged along the split axis. */
@Composable
private fun SplitDivider(
    horizontal: Boolean,
    ratio: Float,
    onDrag: (Float) -> Unit,
    onReset: () -> Unit,
    onGrowPrimary: () -> Unit,
    onGrowReference: () -> Unit,
) {
    val percent = (ratio * 100).roundToInt()
    val dragState = rememberDraggableState(onDelta = onDrag)
    Box(
        modifier = Modifier
            .then(
                if (horizontal) Modifier.fillMaxHeight().width(DIVIDER_TOUCH_TARGET)
                else Modifier.fillMaxWidth().height(DIVIDER_TOUCH_TARGET),
            )
            .draggable(
                state = dragState,
                orientation = if (horizontal) Orientation.Horizontal else Orientation.Vertical,
            )
            .semantics {
                contentDescription = "分割の境界"
                stateDescription = if (horizontal) {
                    "左 $percent パーセント・右 ${100 - percent} パーセント"
                } else {
                    "上 $percent パーセント・下 ${100 - percent} パーセント"
                }
                customActions = listOf(
                    CustomAccessibilityAction(
                        if (horizontal) "左を広げる" else "上を広げる",
                    ) { onGrowPrimary(); true },
                    CustomAccessibilityAction(
                        if (horizontal) "右を広げる" else "下を広げる",
                    ) { onGrowReference(); true },
                    CustomAccessibilityAction("既定の比率へ戻す") { onReset(); true },
                )
            }
            .testTag("split_divider"),
        contentAlignment = Alignment.Center,
    ) {
        if (horizontal) {
            Box(
                Modifier.fillMaxHeight().width(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Box(
                Modifier.height(28.dp).width(4.dp)
                    .background(
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(2.dp),
                    ),
            )
        } else {
            Box(
                Modifier.fillMaxWidth().height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Box(
                Modifier.width(28.dp).height(4.dp)
                    .background(
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

/** The reference pane: a low header naming what is shown, and the reading below it. */
@Composable
private fun ReferencePane(
    reference: SplitReference,
    onChangeReference: () -> Unit,
    onEditReference: () -> Unit,
    onClose: () -> Unit,
    onRetarget: (Long) -> Unit,
) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    val title by produceReferenceTitle(reference, application)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxSize().testTag("split_reference_pane"),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(REFERENCE_HEADER_HEIGHT)
                    .padding(start = ProductSpacing.md)
                    .semantics {
                        contentDescription =
                            "参照中: ${reference.kind.displayName()}「$title」"
                    }
                    .testTag("split_reference_header"),
            ) {
                Icon(
                    when (reference.kind) {
                        SplitReferenceKind.MEMO -> Icons.AutoMirrored.Outlined.Article
                        SplitReferenceKind.NOTE -> Icons.AutoMirrored.Outlined.MenuBook
                        SplitReferenceKind.OUTLINE -> Icons.Outlined.FormatListBulleted
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = ProductSpacing.sm),
                )
                IconButton(
                    onClick = onChangeReference,
                    modifier = Modifier.size(ProductSize.minimumTouchTarget)
                        .testTag("split_change_reference"),
                ) {
                    Icon(
                        Icons.Outlined.SwapVert,
                        contentDescription = "参照を変更",
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = onEditReference,
                    modifier = Modifier.size(ProductSize.minimumTouchTarget)
                        .testTag("split_edit_reference"),
                ) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "こちらを編集",
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(ProductSize.minimumTouchTarget)
                        .testTag("split_close"),
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "分割表示を閉じる",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (reference.kind) {
                SplitReferenceKind.MEMO -> ReferenceMemoView(
                    reference.id,
                    application,
                    onOpenLinkedMemo = onRetarget,
                )
                SplitReferenceKind.NOTE -> ReferenceNoteView(reference.id, application)
                SplitReferenceKind.OUTLINE -> ReferenceOutlineView(reference.id, application)
            }
        }
    }
}

@Composable
private fun produceReferenceTitle(
    reference: SplitReference,
    application: MemoRippleApplication,
) = produceState("…", reference) {
    when (reference.kind) {
        SplitReferenceKind.MEMO, SplitReferenceKind.OUTLINE ->
            application.memoRepository.observeMemo(reference.id).collect { memo ->
                value = memo?.title?.ifBlank { "無題のメモ" } ?: "見つかりません"
            }
        SplitReferenceKind.NOTE ->
            application.noteRepository.observeNote(reference.id).collect { note ->
                value = note?.title ?: "見つかりません"
            }
    }
}

private fun SplitReferenceKind.displayName(): String = when (this) {
    SplitReferenceKind.MEMO -> "メモ"
    SplitReferenceKind.NOTE -> "ノート"
    SplitReferenceKind.OUTLINE -> "アウトライン"
}

/**
 * Another memo, open for writing: title and body edit in place and save themselves, the way
 * the editor above does — one workspace, two live drafts. Tags and photos stay read-only
 * (a tapped photo still opens the fullscreen viewer), and the memo's links re-aim this pane.
 */
@Composable
private fun ReferenceMemoView(
    memoId: Long,
    application: MemoRippleApplication,
    onOpenLinkedMemo: (Long) -> Unit,
) {
    // Each flow is remembered against what identifies it, so recomposition (every keystroke,
    // here) never tears a collection down just to start the same one again.
    val memo by remember(memoId) { application.memoRepository.observeMemo(memoId) }
        .collectAsState(initial = null)
    val tags by remember(memoId) { application.tagRepository.observeTagsForMemo(memoId) }
        .collectAsState(initial = emptyList())
    val photos by remember(memoId) { application.attachmentRepository.observeMemoPhotos(memoId) }
        .collectAsState(initial = emptyList())
    val imageLoader = remember(application) { AttachmentImageLoader(application.attachmentBlobStore) }
    // Link titles resolve against the wall the way the editor's own links do: by title key.
    val allMemos by remember { application.memoRepository.observeStandaloneMemos("") }
        .collectAsState(initial = emptyList())
    val linkTargetIds = remember(allMemos) {
        allMemos.associateBy(
            { io.github.cragcoffee.memoripple.domain.NoteLink.key(it.title) },
            MemoEntity::id,
        )
    }

    // The fields are seeded once per memo from Room and are the source of truth from then on;
    // later emissions (our own saves echoing back) never overwrite what is being typed.
    var loadedFor by remember { mutableStateOf<Long?>(null) }
    var titleField by remember { mutableStateOf("") }
    var bodyField by remember {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    }
    // Only an emission that actually IS this memo may seed: right after a retarget the state
    // still holds the previous memo while the new collection warms up, and seeding from that
    // stale value would put the old memo's words under the new memo's id.
    val current = memo?.takeIf { it.id == memoId }
    if (current != null && loadedFor != memoId) {
        loadedFor = memoId
        titleField = current.title
        bodyField = androidx.compose.ui.text.input.TextFieldValue(current.body)
    }
    // Typing settles, then saves — the same quiet autosave the editor above has. collectLatest
    // restarts the delay on every keystroke, so only a pause writes. The owner gate is what
    // keeps a retarget safe: until the fields have been reseeded for THIS memo they still hold
    // the previous one's words, and saving those here would overwrite the new memo with them.
    LaunchedEffect(memoId) {
        snapshotFlow { Triple(loadedFor, titleField, bodyField.text) }
            .collectLatest { (owner, title, body) ->
                if (owner != memoId) return@collectLatest
                delay(400)
                val existing = application.memoRepository.findById(memoId) ?: return@collectLatest
                if (existing.title == title && existing.body == body) return@collectLatest
                application.memoRepository.save(existing, title, body, System.currentTimeMillis())
            }
    }
    // Closing the pane must not lose the last keystrokes still inside the debounce window.
    // The flush runs on its own scope because the composition's is already cancelled; it
    // cancels itself when done. A retarget reseeds the fields before this fires, so it only
    // writes when the fields still belong to the memo it was registered for.
    val flushScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    DisposableEffect(memoId) {
        onDispose {
            if (loadedFor != memoId) return@onDispose
            val title = titleField
            val body = bodyField.text
            flushScope.launch {
                val existing = application.memoRepository.findById(memoId)
                if (existing != null && (existing.title != title || existing.body != body)) {
                    application.memoRepository.save(existing, title, body, System.currentTimeMillis())
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { flushScope.launch { flushScope.cancel() } } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("split_reference_memo")
            .padding(
                horizontal = ProductSize.screenHorizontalPadding,
                vertical = ProductSpacing.sm,
            ),
    ) {
        if (current == null) return@Column
        EditorTextField(
            value = titleField,
            onValueChange = { titleField = it },
            hint = "タイトル",
            textStyle = MaterialTheme.typography.titleMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.fillMaxWidth()
                .padding(bottom = ProductSpacing.xs)
                .testTag("split_reference_title"),
            singleLine = true,
        )
        if (tags.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs),
                modifier = Modifier.padding(bottom = ProductSpacing.xs),
            ) {
                tags.take(6).forEach { tag ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Text(
                            tag.name,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
        if (photos.isNotEmpty()) {
            // The editor's own strip, read-only: a tap opens the fullscreen viewer and
            // closing it lands back on this same split, untouched.
            io.github.cragcoffee.memoripple.ui.attachments.PhotoAttachmentStrip(
                photos = photos,
                imageLoader = imageLoader,
                editable = false,
                importing = false,
                onDelete = {},
                modifier = Modifier.padding(bottom = ProductSpacing.sm),
            )
        }
        val linkTitles = io.github.cragcoffee.memoripple.domain.NoteLink.titles(bodyField.text)
        if (linkTitles.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs),
                modifier = Modifier.padding(bottom = ProductSpacing.sm),
            ) {
                linkTitles.take(4).forEach { linked ->
                    val target = linkTargetIds[
                        io.github.cragcoffee.memoripple.domain.NoteLink.key(linked),
                    ]
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier
                            .then(
                                // A link inside the reference re-aims the reference
                                // itself; the writing above never moves.
                                if (target != null && target != memoId) {
                                    Modifier.clickable { onOpenLinkedMemo(target) }
                                } else {
                                    Modifier
                                },
                            )
                            .testTag("split_reference_link_$linked"),
                    ) {
                        Text(
                            linked,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
        EditorTextField(
            value = bodyField,
            onValueChange = { bodyField = it },
            hint = "本文を書きはじめる",
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = androidx.compose.ui.unit.TextUnit(
                    24f,
                    androidx.compose.ui.unit.TextUnitType.Sp,
                ),
            ),
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = 160.dp)
                .padding(bottom = ProductSpacing.sm)
                .testTag("split_reference_body"),
            singleLine = false,
            visualTransformation = rememberInlineMarkupTransformation(),
        )
    }
}

/**
 * A note read in place: one episode at a time, its neighbours a tap away — and the whole
 * table of contents one tap up, on the episode's own name.
 */
@Composable
private fun ReferenceNoteView(noteId: Long, application: MemoRippleApplication) {
    val sections by remember(noteId) {
        combine(
            application.noteRepository.observeChapters(noteId),
            application.noteRepository.observeEpisodes(noteId),
        ) { chapters, episodes ->
            NoteStructure.sections(
                chapters = chapters.map {
                    NoteStructure.ChapterInput(it.id, it.title, it.sortOrder)
                },
                episodes = episodes.map { memo ->
                    NoteStructure.EpisodeInput(
                        memoId = memo.id,
                        title = memo.title,
                        characterCount = 0,
                        updatedAt = memo.updatedAt,
                        chapterId = memo.chapterId,
                    )
                },
            )
        }
    }.collectAsState(initial = emptyList())
    val episodes = remember(sections) { NoteStructure.flatten(sections) }
    var readingIndex by rememberSaveable(noteId) { mutableIntStateOf(0) }
    val episode = episodes.getOrNull(readingIndex.coerceIn(0, (episodes.size - 1).coerceAtLeast(0)))
    val body by produceState("", episode?.memoId) {
        val id = episode?.memoId ?: return@produceState
        application.memoRepository.observeMemo(id).collect { value = it?.body.orEmpty() }
    }
    val paragraphs = remember(body) { ReaderProse.paragraphs(body) }
    Column(Modifier.fillMaxSize().testTag("split_reference_note")) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = ProductSpacing.sm),
        ) {
            TextButton(
                onClick = { if (readingIndex > 0) readingIndex -= 1 },
                enabled = readingIndex > 0,
                modifier = Modifier.testTag("split_note_previous"),
            ) { Text("前の話") }
            // The current episode's name doubles as the way to any other: tapping it opens
            // the note's table of contents, chapters as quiet headings between the episodes.
            var showJumpMenu by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable(enabled = episodes.isNotEmpty()) { showJumpMenu = true }
                        .padding(horizontal = ProductSpacing.xs, vertical = ProductSpacing.xs)
                        .testTag("split_note_jump"),
                ) {
                    Text(
                        episode?.title?.ifBlank { "無題" } ?: "",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        Icons.Outlined.ArrowDropDown,
                        contentDescription = "話へ移動",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = showJumpMenu,
                    onDismissRequest = { showJumpMenu = false },
                ) {
                    var runningIndex = 0
                    sections.forEach { section ->
                        if (section.title != null) {
                            Text(
                                section.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(
                                    horizontal = ProductSpacing.md,
                                    vertical = ProductSpacing.xs,
                                ),
                            )
                        }
                        section.episodes.forEach { entry ->
                            val index = runningIndex
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "第${entry.number}話 ${entry.title.ifBlank { "無題" }}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = if (index == readingIndex) {
                                            MaterialTheme.typography.bodyMedium.copy(
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        } else {
                                            MaterialTheme.typography.bodyMedium
                                        },
                                    )
                                },
                                onClick = {
                                    readingIndex = index
                                    showJumpMenu = false
                                },
                                modifier = Modifier.testTag("split_note_jump_${entry.memoId}"),
                            )
                            runningIndex += 1
                        }
                    }
                }
            }
            TextButton(
                onClick = { if (readingIndex < episodes.lastIndex) readingIndex += 1 },
                enabled = readingIndex < episodes.lastIndex,
                modifier = Modifier.testTag("split_note_next"),
            ) { Text("次の話") }
        }
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = ProductSize.screenHorizontalPadding,
                vertical = ProductSpacing.sm,
            ),
        ) {
            items(paragraphs.size) { index ->
                val paragraph = paragraphs[index]
                if (paragraph.isEmpty()) {
                    Spacer(Modifier.height(20.dp))
                } else {
                    Text(
                        io.github.cragcoffee.memoripple.domain.ProseTyping.strip(paragraph),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = ProductSpacing.sm),
                    )
                }
            }
        }
    }
}

/** A memo's bones: its marked lines, each opening to the prose beneath when tapped. */
@Composable
private fun ReferenceOutlineView(memoId: Long, application: MemoRippleApplication) {
    val memo by application.memoRepository.observeMemo(memoId)
        .collectAsState(initial = null)
    val parser = remember { WorkCommentParser() }
    val sectionsData = remember(memo?.body) {
        val lines = parser.parse(memo?.body.orEmpty())
        buildList {
            var current: Pair<String, MutableList<String>>? = null
            lines.forEach { line ->
                val words = BodyText.readable(line.text)
                if (line.type != WorkLineType.PLAIN) {
                    current = words to mutableListOf()
                    add(current!!)
                } else {
                    current?.second?.add(words)
                }
            }
        }
    }
    var expanded by rememberSaveable(memoId) { mutableStateOf(-1) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("split_reference_outline"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = ProductSize.screenHorizontalPadding,
            vertical = ProductSpacing.sm,
        ),
    ) {
        items(sectionsData.size) { index ->
            val (heading, prose) = sectionsData[index]
            Column(
                Modifier.fillMaxWidth()
                    .clickable { expanded = if (expanded == index) -1 else index }
                    .testTag("split_outline_heading_$index")
                    .padding(vertical = 6.dp),
            ) {
                Text(heading, style = MaterialTheme.typography.titleSmall)
                if (expanded == index) {
                    prose.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, start = ProductSpacing.sm),
                        )
                    }
                }
            }
        }
    }
}

/** The picker: メモ・ノート・アウトライン, searched and chosen from one quiet sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SplitReferencePickerSheet(
    primaryMemoId: Long,
    onPick: (SplitReference) -> Unit,
    onDismiss: () -> Unit,
) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    var kind by rememberSaveable { mutableStateOf(SplitReferenceKind.MEMO) }
    var query by rememberSaveable { mutableStateOf("") }
    val memos by application.memoRepository.observeStandaloneMemos("")
        .collectAsState(initial = emptyList())
    val notes by application.noteRepository.observeSummaries()
        .collectAsState(initial = emptyList())
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("split_reference_picker"),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = ProductSize.screenHorizontalPadding),
        ) {
            Text("参照する内容", style = MaterialTheme.typography.titleLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(ProductSpacing.sm),
                modifier = Modifier.padding(vertical = ProductSpacing.sm),
            ) {
                SplitReferenceKind.entries.forEach { candidate ->
                    val selected = candidate == kind
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier
                            .clickable { kind = candidate }
                            .testTag("split_picker_kind_${candidate.name.lowercase()}"),
                    ) {
                        Text(
                            candidate.displayName(),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("検索") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("split_picker_search"),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .padding(top = ProductSpacing.xs),
            ) {
                when (kind) {
                    SplitReferenceKind.MEMO, SplitReferenceKind.OUTLINE -> {
                        val candidates = memos.asSequence()
                            .filter { it.id != primaryMemoId }
                            .filter { kind == SplitReferenceKind.MEMO || BodyReading.hasStructure(it.body) }
                            .filter {
                                query.isBlank() ||
                                    it.title.contains(query, ignoreCase = true) ||
                                    it.body.contains(query, ignoreCase = true)
                            }
                            .take(50)
                            .toList()
                        items(candidates, key = MemoEntity::id) { candidate ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        candidate.title.ifBlank { "無題のメモ" },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        BodyText.readable(
                                            candidate.body.lineSequence()
                                                .firstOrNull(String::isNotBlank).orEmpty(),
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                modifier = Modifier
                                    .clickable { onPick(SplitReference(kind, candidate.id)) }
                                    .testTag("split_pick_memo_${candidate.id}"),
                            )
                        }
                    }
                    SplitReferenceKind.NOTE -> {
                        val candidates = notes.filter {
                            query.isBlank() || it.title.contains(query, ignoreCase = true)
                        }
                        items(candidates, key = { it.id }) { note ->
                            ListItem(
                                headlineContent = {
                                    Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = { Text("全${note.episodeCount}話") },
                                modifier = Modifier
                                    .clickable {
                                        onPick(SplitReference(SplitReferenceKind.NOTE, note.id))
                                    }
                                    .testTag("split_pick_note_${note.id}"),
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(ProductSpacing.lg)) }
            }
        }
    }
}

/** The whole 48dp band is the divider's touch target; only its hairline is visible. */
private val DIVIDER_TOUCH_TARGET = 48.dp

/** 48–52dp: present, but never a second app bar. */
internal val REFERENCE_HEADER_HEIGHT = 48.dp

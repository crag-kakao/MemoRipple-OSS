package io.github.cragcoffee.memoripple.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.ui.memos.carriedCard
import io.github.cragcoffee.memoripple.ui.memos.cardSlots
import io.github.cragcoffee.memoripple.ui.memos.cardDragHandle
import io.github.cragcoffee.memoripple.ui.memos.EdgeScrollWhileCarrying
import io.github.cragcoffee.memoripple.ui.memos.CARD_LIFT
import io.github.cragcoffee.memoripple.ui.memos.CardDragController
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import io.github.cragcoffee.memoripple.domain.notes.NoteArrangement
import io.github.cragcoffee.memoripple.domain.notes.NoteCoverPaint
import io.github.cragcoffee.memoripple.ui.attachments.AttachmentImageLoader
import kotlinx.coroutines.launch
import io.github.cragcoffee.memoripple.domain.notes.NoteEpisode
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import io.github.cragcoffee.memoripple.domain.notes.NoteSection
import io.github.cragcoffee.memoripple.domain.notes.NoteStructure
import io.github.cragcoffee.memoripple.ui.components.ProductCompactTopBar
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import java.text.DateFormat
import java.util.Date

/**
 * A note, opened to be arranged rather than read.
 *
 * The list of episodes is a table because that is what it is used as: several are picked and moved
 * at once, and the numbers beside them are compared.
 */
@Composable
fun NoteDetailScreen(
    state: NoteDetailUiState,
    onBack: () -> Unit,
    onOpenEpisode: (Long) -> Unit,
    onEditEpisode: (Long) -> Unit,
    onWriteNextEpisode: () -> Unit,
    onRename: (String) -> Unit,
    onAddChapter: (String, Boolean) -> Unit,
    onRenameChapter: (Long, String) -> Unit,
    onDeleteChapter: (Long) -> Unit,
    onMoveToChapter: (Set<Long>, Long?) -> Unit,
    onReleaseEpisodes: (Set<Long>) -> Unit,
    onMoveRow: (rows: List<NoteArrangement.Row>, index: Int, delta: Int) -> Boolean,
    onArrangeRows: (List<NoteArrangement.Row>) -> Unit,
    onCoverColorChange: (NoteCoverPaint) -> Unit,
    onCoverPhotoPick: (Uri, (Boolean) -> Unit) -> Unit,
    onCoverPhotoClear: () -> Unit,
    myCoverColors: List<Int>,
    onSaveMyCoverColor: (Int) -> Unit,
    onRemoveMyCoverColor: (Int) -> Unit,
    imageLoader: AttachmentImageLoader,
    onDeleteNote: () -> Unit,
) {
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var showMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var addingChapter by remember { mutableStateOf(false) }
    var renamingChapter by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var pickingCover by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coverScope = rememberCoroutineScope()
    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            pickingCover = false
            onCoverPhotoPick(uri) { stored ->
                if (!stored) {
                    coverScope.launch {
                        snackbarHostState.showSnackbar("この画像は表紙にできませんでした")
                    }
                }
            }
        }
    }
    var movingSelection by remember { mutableStateOf(false) }
    // Arranging is a mode rather than something available at every moment. Reading a note is the
    // ordinary thing to be doing here, and a handle on every row while reading is noise.
    var arranging by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val all = remember(state.sections) { state.sections.flatMap(NoteSection::episodes) }

    // The note as one run of rows, headings standing among the episodes. Everything here moves
    // through the same list, so a heading crossing an episode is an ordinary step.
    val rows = remember(state.sections) { NoteArrangement.rows(state.sections) }
    val rowsNow = rememberUpdatedState(rows)
    // While a row is carried the list shows the order the hand is making — kept here, not in
    // the store, so the others slide at once instead of after a round trip; release writes it
    // once, and the store's echo retires the local copy. The same rule the shortcut-bar list
    // follows.
    var localRows by remember { mutableStateOf<List<NoteArrangement.Row>?>(null) }
    val shownRows = localRows ?: rows
    val chapterTitles = remember(state.sections) { state.sections.filter { it.chapterId != null }.associate { it.chapterId!! to (it.title ?: "") } }
    val episodesById = remember(state.sections) { state.sections.flatMap(NoteSection::episodes).associateBy(NoteEpisode::memoId) }

    fun move(row: NoteArrangement.Row, delta: Int): Boolean =
        onMoveRow(rows, rows.indexOf(row), delta)

    // A handle carries its row: the row floats under the finger, the others slide as it crosses
    // them, and every crossing is written at once — the note's order is its own truth. Headings
    // and episodes share one list; a heading's key is its id below zero.
    val listState = rememberLazyListState()
    val dragScope = rememberCoroutineScope()
    val drag = remember(listState) {
        CardDragController(
            scope = dragScope,
            slots = {
                cardSlots(listState.layoutInfo.visibleItemsInfo.map { Triple(it.key, IntOffset(0, it.offset), IntSize(Int.MAX_VALUE / 2, it.size)) }) { key ->
                    (key as? String)?.let { k ->
                        k.removePrefix("row-").toLongOrNull()?.takeIf { k.startsWith("row-") }
                            ?: k.removePrefix("chapter-").toLongOrNull()?.takeIf { k.startsWith("chapter-") }?.let { -it }
                    }
                }
            },
            onReorder = { localRows?.let(onArrangeRows) },
            onCrossed = { id, targetId ->
                val current = localRows ?: rowsNow.value
                fun rowOf(key: Long) = if (key < 0) NoteArrangement.Row.Chapter(-key) else NoteArrangement.Row.Episode(key)
                val from = current.indexOf(rowOf(id))
                val to = current.indexOf(rowOf(targetId))
                // A crossing means "take that row's place": the walk there, one step at a time,
                // on the local order — the page shows it now, the store hears it on release.
                if (from >= 0 && to >= 0 && to != from) {
                    var walked = current
                    var at = from
                    while (at != to) {
                        val step = if (to > at) 1 else -1
                        walked = NoteArrangement.moved(walked, at, step) ?: break
                        at += step
                    }
                    localRows = walked
                }
            },
        )
    }
    val liftPx = with(LocalDensity.current) { CARD_LIFT.toPx() }
    LaunchedEffect(rows) {
        // The store caught up (or the drag never changed anything): the local order retires.
        if (drag.carriedId == null) localRows = null
    }
    EdgeScrollWhileCarrying(
        drag = drag.takeIf { arranging },
        viewport = { listState.layoutInfo.viewportStartOffset to listState.layoutInfo.viewportSize.height },
        scrollBy = { listState.scrollBy(it) },
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!state.isLoading) {
                WriteEpisodeBar(state.episodeCount, onWriteNextEpisode)
            }
        },
        topBar = {
            ProductCompactTopBar(
                modifier = Modifier.testTag("note_detail_top_bar"),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(ProductSize.minimumTouchTarget),
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "閉じる")
                    }
                },
                centerContent = {
                    Text(
                        state.note?.title.orEmpty().ifBlank { "無題のノート" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("note_detail_title"),
                    )
                },
                action = {
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(ProductSize.minimumTouchTarget)
                                .testTag("note_detail_menu"),
                        ) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "ノートの操作")
                        }
                        DropdownMenu(showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("名前を変える") },
                                onClick = { showMenu = false; renaming = true },
                                modifier = Modifier.testTag("note_rename"),
                            )
                            DropdownMenuItem(
                                text = { Text("表紙") },
                                onClick = { showMenu = false; pickingCover = true },
                                modifier = Modifier.testTag("note_cover_color"),
                            )
                            DropdownMenuItem(
                                text = { Text("章を追加") },
                                onClick = { showMenu = false; addingChapter = true },
                                modifier = Modifier.testTag("note_add_chapter"),
                            )
                            DropdownMenuItem(
                                text = { Text("ノートを削除") },
                                onClick = { showMenu = false; confirmDelete = true },
                                modifier = Modifier.testTag("note_delete"),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) return@Scaffold
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                NoteDetailHeader(
                    state = state,
                    arranging = arranging,
                    selectedCount = selected.size,
                    onToggleArranging = { arranging = !arranging },
                    onAddChapter = { addingChapter = true },
                    onMoveSelection = { movingSelection = true },
                )
            }
            item {
                TableHeader(
                    allSelected = selected.isNotEmpty() && selected.size == all.size,
                    anySelected = selected.isNotEmpty(),
                    onToggleAll = {
                        selected = if (selected.size == all.size) {
                            emptySet()
                        } else {
                            all.mapTo(hashSetOf(), NoteEpisode::memoId)
                        }
                    },
                    onRelease = {
                        onReleaseEpisodes(selected)
                        selected = emptySet()
                    },
                )
            }
            shownRows.forEach { shownRow ->
                if (shownRow is NoteArrangement.Row.Chapter) {
                    val chapterId = shownRow.id
                    val chapterTitle = chapterTitles[chapterId] ?: return@forEach
                    item(key = "chapter-$chapterId") {
                        val chapterKey = -chapterId
                        ChapterHeader(
                            title = chapterTitle,
                            chapterId = chapterId,
                            arranging = arranging,
                            onRename = { renamingChapter = chapterId to chapterTitle },
                            onDelete = { onDeleteChapter(chapterId) },
                            onMove = { delta -> move(NoteArrangement.Row.Chapter(chapterId), delta) },
                            modifier = (if (drag.activeId == chapterKey) Modifier else Modifier.animateItem()).carriedCard(drag, chapterKey, liftPx),
                            dragHandle = Modifier.cardDragHandle(drag, chapterKey, enabled = arranging, longPress = false) { emptyList() },
                            carried = drag.isCarried(chapterKey),
                        )
                    }
                }
                if (shownRow is NoteArrangement.Row.Episode) item(key = "row-${shownRow.memoId}") {
                    val episode = episodesById[shownRow.memoId] ?: return@item
                    val row = NoteArrangement.Row.Episode(episode.memoId)
                    val at = rows.indexOf(row)
                    EpisodeTableRow(
                        episode = episode,
                        arranging = arranging,
                        checked = episode.memoId in selected,
                        dragging = drag.isCarried(episode.memoId),
                        canMoveUp = at > 0,
                        canMoveDown = at in 0 until rows.lastIndex,
                        onMove = { delta -> move(row, delta) },
                        modifier = (if (drag.activeId == episode.memoId) Modifier else Modifier.animateItem()).carriedCard(drag, episode.memoId, liftPx),
                        dragHandle = Modifier.cardDragHandle(drag, episode.memoId, enabled = arranging, longPress = false) { emptyList() },
                        onCheckedChange = { checked ->
                            selected = if (checked) {
                                selected + episode.memoId
                            } else {
                                selected - episode.memoId
                            }
                        },
                        onOpen = { onOpenEpisode(episode.memoId) },
                        onEdit = { onEditEpisode(episode.memoId) },
                    )
                }
            }
            item { Spacer(Modifier.height(ProductSpacing.xxl)) }
        }
    }

    if (pickingCover) {
        NoteCoverColorSheet(
            selected = state.coverPaint,
            hasPhoto = state.coverPhoto != null,
            onPick = { pickingCover = false; onCoverColorChange(it) },
            myColors = myCoverColors,
            onSaveMyColor = onSaveMyCoverColor,
            onRemoveMyColor = onRemoveMyCoverColor,
            onPickPhoto = {
                coverPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onClearPhoto = { pickingCover = false; onCoverPhotoClear() },
            onDismiss = { pickingCover = false },
        )
    }

    if (renaming) {
        TextEntryDialog(
            title = "ノートの名前",
            initial = state.note?.title.orEmpty(),
            confirmLabel = "変える",
            testTag = "note_rename_dialog",
            onConfirm = { renaming = false; onRename(it) },
            onDismiss = { renaming = false },
        )
    }
    if (addingChapter) {
        ChapterAddDialog(
            onConfirm = { title, atStart -> addingChapter = false; onAddChapter(title, atStart) },
            onDismiss = { addingChapter = false },
        )
    }
    renamingChapter?.let { (chapterId, current) ->
        TextEntryDialog(
            title = "章の名前",
            initial = current,
            confirmLabel = "変える",
            testTag = "note_rename_chapter_dialog",
            onConfirm = { renamingChapter = null; onRenameChapter(chapterId, it) },
            onDismiss = { renamingChapter = null },
        )
    }
    if (movingSelection) {
        ChapterPickerDialog(
            sections = state.sections,
            onPick = { chapterId ->
                movingSelection = false
                onMoveToChapter(selected, chapterId)
                selected = emptySet()
            },
            onDismiss = { movingSelection = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("ノートを削除しますか？") },
            text = { Text("話はメモとして残ります。消えるのは並び順と章だけです。") },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; onDeleteNote() },
                    modifier = Modifier.testTag("confirm_note_delete"),
                ) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("やめる") }
            },
            modifier = Modifier.testTag("note_delete_dialog"),
        )
    }
}

@Composable
private fun NoteDetailHeader(
    state: NoteDetailUiState,
    arranging: Boolean,
    selectedCount: Int,
    onToggleArranging: () -> Unit,
    onAddChapter: () -> Unit,
    onMoveSelection: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ProductSpacing.lg)) {
        Row(
            modifier = Modifier.padding(top = ProductSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs),
        ) {
            Text(
                "ノート",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "›",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                state.note?.title.orEmpty().ifBlank { "無題のノート" },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The cover is what a note is picked out by in a list. On the note's own page the
            // title is already in the bar above, so the room goes to what it says about itself.
            // What the note says about itself on the left, what has been counted on the right:
            // one is read, the other is glanced at, and they need not share a column.
            Text(
                state.note?.subtitle.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).testTag("note_detail_subtitle"),
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "全${state.episodeCount}話　${state.totalCharacters}字",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("note_detail_summary"),
                )
                if (state.lastWrittenAt > 0) {
                    Text(
                        DateFormat.getDateInstance(DateFormat.MEDIUM)
                            .format(Date(state.lastWrittenAt)) + " に加筆",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = ProductSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(ProductSpacing.sm, Alignment.End),
        ) {
            if (arranging) {
                OutlinedButton(
                    onClick = onAddChapter,
                    modifier = Modifier.heightIn(min = ProductSize.minimumTouchTarget)
                        .testTag("note_add_chapter_inline"),
                ) { Text("新しい章を追加") }
            }
            if (selectedCount > 0) {
                OutlinedButton(
                    onClick = onMoveSelection,
                    modifier = Modifier.heightIn(min = ProductSize.minimumTouchTarget)
                        .testTag("note_move_to_chapter"),
                ) { Text("${selectedCount}話を章へ") }
            }
            FilledTonalButton(
                onClick = onToggleArranging,
                modifier = Modifier.heightIn(min = ProductSize.minimumTouchTarget)
                    .testTag("note_arrange_mode"),
            ) { Text(if (arranging) "完了" else "章と並び順の編集") }
        }
    }
}

/**
 * Writing the next one.
 *
 * At the bottom, where a hand already is, rather than above a table that is read downwards. What
 * it says depends on whether there is anything yet: the first one is written, the ones after it
 * come next.
 */
@Composable
private fun WriteEpisodeBar(episodeCount: Int, onWrite: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FilledTonalButton(
                onClick = onWrite,
                modifier = Modifier.fillMaxWidth()
                    .padding(
                        horizontal = ProductSize.screenHorizontalPadding,
                        vertical = ProductSpacing.sm,
                    )
                    .heightIn(min = ProductSize.minimumTouchTarget)
                    .testTag("note_write_next_episode"),
            ) {
                Text(if (episodeCount == 0) "エピソードを執筆" else "次のエピソードを書く")
            }
        }
    }
}

@Composable
private fun TableHeader(
    allSelected: Boolean,
    anySelected: Boolean,
    onToggleAll: () -> Unit,
    onRelease: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = ProductSize.minimumTouchTarget)
                .padding(horizontal = ProductSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = allSelected,
                onCheckedChange = { onToggleAll() },
                modifier = Modifier.testTag("note_select_all"),
            )
            Text(
                "章とエピソード",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            if (anySelected) {
                TextButton(
                    onClick = onRelease,
                    modifier = Modifier.testTag("note_release_episodes"),
                ) { Text("ノートから外す") }
            }
        }
    }
}

@Composable
private fun ChapterHeader(
    title: String,
    chapterId: Long?,
    arranging: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Boolean,
    modifier: Modifier = Modifier,
    dragHandle: Modifier = Modifier,
    carried: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    val moves = buildList {
        add(CustomAccessibilityAction("上へ移動") { onMove(-1) })
        add(CustomAccessibilityAction("下へ移動") { onMove(1) })
    }
    Column(modifier) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = ProductSize.minimumTouchTarget)
                .padding(start = ProductSpacing.lg, end = ProductSpacing.sm)
                .semantics { if (arranging) customActions = moves },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // What can be done to a chapter belongs to the mode that arranges them, like the
            // handles do. Reading a note is not the moment to be offered a way to delete a heading.
            if (!arranging) return@Row
            Box {
                IconButton(
                    onClick = { menu = true },
                    modifier = Modifier.size(ProductSize.minimumTouchTarget),
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "${title}の操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("名前を変える") },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("章を消す") },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(ProductSize.minimumTouchTarget)
                    .then(dragHandle)
                    .semantics { contentDescription = "${title}を並べ替える" }
                    .testTag("note_drag_chapter_$chapterId"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.DragHandle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EpisodeTableRow(
    episode: NoteEpisode,
    arranging: Boolean,
    checked: Boolean,
    dragging: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandle: Modifier = Modifier,
) {
    // A handle is how this app already moves one thing past another, and the same two actions are
    // offered by name so the order is not locked behind a gesture.
    val moves = buildList {
        if (canMoveUp) add(CustomAccessibilityAction("上へ移動") { onMove(-1) })
        if (canMoveDown) add(CustomAccessibilityAction("下へ移動") { onMove(1) })
    }
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = ProductSize.minimumTouchTarget)
                .padding(end = ProductSpacing.sm)
                .semantics { if (moves.isNotEmpty()) customActions = moves },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag("note_select_episode_${episode.memoId}"),
            )
            // The whole stretch from the title to its numbers is one press that opens the
            // episode — a reader aims at the row, not at the word inside it. Only the checkbox
            // and 編集 keep presses of their own.
            Row(
                modifier = Modifier.weight(1f)
                    .clickable(onClick = onOpen)
                    .padding(vertical = ProductSpacing.xs)
                    .testTag("note_episode_${episode.memoId}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    episode.title.ifBlank { "無題" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${episode.characterCount}字\n" +
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(episode.updatedAt)) + " に更新",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(end = ProductSpacing.sm),
                )
            }
            TextButton(
                onClick = onEdit,
                modifier = Modifier.testTag("note_edit_episode_${episode.memoId}"),
            ) { Text("編集") }
            // The handle belongs to the mode that uses it. Outside that mode a row is something to
            // read, and a grip on every one of them says otherwise.
            if (!arranging) return@Row
            Box(
                modifier = Modifier
                    .size(ProductSize.minimumTouchTarget)
                    .then(dragHandle)
                    .semantics {
                        contentDescription = "${episode.title.ifBlank { "無題" }}を並べ替える"
                    }
                    .testTag("note_drag_episode_${episode.memoId}"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.DragHandle,
                    contentDescription = null,
                    tint = if (dragging) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ChapterPickerDialog(
    sections: List<NoteSection>,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("どの章へ移しますか？") },
        text = {
            Column {
                TextButton(
                    onClick = { onPick(null) },
                    modifier = Modifier.fillMaxWidth().testTag("chapter_pick_none"),
                ) { Text("章に入れない", modifier = Modifier.fillMaxWidth()) }
                sections.filter { it.chapterId != null }.forEach { section ->
                    TextButton(
                        onClick = { onPick(section.chapterId) },
                        modifier = Modifier.fillMaxWidth()
                            .testTag("chapter_pick_${section.chapterId}"),
                    ) {
                        Text(section.title.orEmpty(), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
        modifier = Modifier.testTag("chapter_picker_dialog"),
    )
}

@Composable
private fun TextEntryDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    testTag: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("${testTag}_field"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                modifier = Modifier.testTag("${testTag}_confirm"),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
        modifier = Modifier.testTag(testTag),
    )
}

/** How far a drag travels before it moves an episode past the next one. */

package io.github.cragcoffee.memoripple.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollBy
import io.github.cragcoffee.memoripple.data.NoteSummary
import io.github.cragcoffee.memoripple.data.PhotoAttachment
import io.github.cragcoffee.memoripple.domain.notes.NoteCoverPaint
import io.github.cragcoffee.memoripple.ui.attachments.AttachmentImageLoader
import io.github.cragcoffee.memoripple.domain.notes.NoteEpisode
import io.github.cragcoffee.memoripple.ui.components.ProductEmptyState
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.selected
import io.github.cragcoffee.memoripple.ui.memos.SelectionMark
import io.github.cragcoffee.memoripple.ui.memos.cardFill
import io.github.cragcoffee.memoripple.ui.memos.cardOutline
import java.text.DateFormat
import java.util.Date

/**
 * The notes, as covers with titles.
 *
 * A row opens where it stands so the episodes can be seen without leaving the list; the cover and
 * the title lead into the note itself, where it is arranged rather than read.
 */
@Composable
fun NoteListPage(
    state: NoteListUiState,
    onToggleExpanded: (Long) -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenEpisode: (noteId: Long, memoId: Long) -> Unit,
    onWriteNextEpisode: (Long) -> Unit,
    onCreateNote: () -> Unit,
    onHoldNote: (NoteSummary) -> Unit,
    coverPhotos: Map<Long, PhotoAttachment>,
    imageLoader: AttachmentImageLoader,
    modifier: Modifier = Modifier,
    /**
     * ノートを選択 (2026-09-28): a tap picks or lets go of a note, the ＝ handle carries one to a new place, and the
     * picked ones answer the trash button. Picking and carrying are separate, so carrying keeps the picks.
     */
    selecting: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    onToggleSelected: (Long) -> Unit = {},
    onReorder: (List<Long>) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    if (state.isLoading) return
    val arranging = selecting
    val dragScope = rememberCoroutineScope()
    val drag = remember(listState, onReorder) {
        CardDragController(
            scope = dragScope,
            slots = {
                cardSlots(listState.layoutInfo.visibleItemsInfo.map { Triple(it.key, IntOffset(0, it.offset), IntSize(Int.MAX_VALUE / 2, it.size)) }) { key ->
                    (key as? String)?.removePrefix("note-")?.toLongOrNull()
                }
            },
            onReorder = onReorder,
        )
    }.takeIf { arranging || it.activeId != null }
    val notes = drag?.localOrder?.let { order ->
        val position = order.withIndex().associate { (index, id) -> id to index }
        state.notes.sortedBy { position[it.id] ?: Int.MAX_VALUE }
    } ?: state.notes
    LaunchedEffect(state.notes) { drag?.pageCaughtUp(state.notes.map(NoteSummary::id)) }
    EdgeScrollWhileCarrying(
        drag = drag,
        viewport = { listState.layoutInfo.viewportStartOffset to listState.layoutInfo.viewportSize.height },
        scrollBy = { listState.scrollBy(it) },
    )
    val liftPx = with(LocalDensity.current) { CARD_LIFT.toPx() }
    if (state.notes.isEmpty()) {
        ProductEmptyState(
            title = "まだノートがありません",
            description = "続きもののメモをひとつにまとめて、順番に読み返せます。",
            // The floating button already offers this; a second one only splits the eye.
            modifier = modifier.fillMaxSize().padding(bottom = ProductSpacing.xxl)
                .testTag("note_empty_state"),
        )
        return
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize().testTag("note_list")) {
        notes.forEach { note ->
            item(key = "note-${note.id}") {
                NoteRow(
                    // While picking, a long press on the row does nothing: only the ＝ handle carries a note.
                    onHold = if (arranging) null else { { onHoldNote(note) } },
                    coverPhoto = coverPhotos[note.id],
                    imageLoader = imageLoader,
                    note = note,
                    expanded = !arranging && state.expandedNoteId == note.id,
                    selecting = arranging,
                    selected = note.id in selectedIds,
                    onToggle = { onToggleExpanded(note.id) },
                    onOpen = if (arranging) { { onToggleSelected(note.id) } } else { { onOpenNote(note.id) } },
                    drag = drag,
                    shownOrder = { notes.map(NoteSummary::id) },
                    modifier = (if (drag?.activeId == note.id) Modifier else Modifier.animateItem())
                        .carriedCard(drag, note.id, liftPx),
                )
            }
            if (!arranging && state.expandedNoteId == note.id) {
                items(state.expandedEpisodes, key = { "episode-${it.memoId}" }) { episode ->
                    EpisodeRow(
                        episode = episode,
                        onClick = { onOpenEpisode(note.id, episode.memoId) },
                    )
                }
                item(key = "write-${note.id}") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = ProductSize.minimumTouchTarget)
                            .clickable { onWriteNextEpisode(note.id) }
                            .padding(start = 28.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "次の話を書く",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = ProductSpacing.sm)
                                .testTag("write_next_episode_${note.id}"),
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        item { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun NoteRow(
    note: NoteSummary,
    expanded: Boolean,
    coverPhoto: PhotoAttachment?,
    imageLoader: AttachmentImageLoader,
    onHold: (() -> Unit)?,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    selecting: Boolean = false,
    selected: Boolean = false,
    drag: CardDragController? = null,
    shownOrder: () -> List<Long> = { emptyList() },
) {
    // Where the ＝ handle stands inside the row, so a carry grabbed by the handle moves the row from the right place.
    // A plain holder, not state: it is read only when a carry starts, and writing state from a layout callback
    // would lay the row out again and again.
    val spot = remember { HandleSpot() }
    Surface(
        color = when {
            selected -> cardFill(true)
            expanded -> MaterialTheme.colorScheme.surface
            else -> MaterialTheme.colorScheme.background
        },
        modifier = modifier.fillMaxWidth()
            .onGloballyPositioned { spot.row = it }
            .semantics { if (selecting) this.selected = selected }
            .testTag("note_row_${note.id}"),
        // The memo wall's selection language: its fill, its edge and its mark.
        border = cardOutline(selected),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // A note answers to holding the way a memo does, so one gesture reaches what
                    // can be done to either. It is taken across the whole row rather than around
                    // the title alone: a memo card lights up entirely when it is held, and a
                    // patch of light in the middle of a row reads as a misfire, not an answer.
                    .combinedClickable(onClick = onOpen, onLongClick = onHold)
                    .testTag("open_note_${note.id}")
                    .padding(ProductSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selecting) {
                        SelectionMark(selected = selected, memoId = note.id, tag = "note_selected_${note.id}")
                        Spacer(Modifier.width(ProductSpacing.sm))
                    }
                    NoteCover(
                        paint = NoteCoverPaint.fromStorageId(note.coverColor),
                        photo = coverPhoto,
                        imageLoader = imageLoader,
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = ProductSpacing.md)) {
                        Text(
                            note.title.ifBlank { "無題のノート" },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            DateFormat.getDateInstance(DateFormat.MEDIUM)
                                .format(Date(note.lastWrittenAt)) + " に加筆",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = ProductSpacing.xs),
                        )
                    }
                }
                // While picking, no ∨: the row picks and the ＝ carries (the S26 review, 2026-09-28).
                if (!selecting) {
                    Box(
                        modifier = Modifier
                            .size(ProductSize.minimumTouchTarget)
                            .clickable(onClick = onToggle)
                            .semantics {
                                contentDescription = if (expanded) {
                                    "${note.title}の話を閉じる"
                                } else {
                                    "${note.title}の話を開く"
                                }
                            }
                            .testTag("toggle_note_${note.id}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (selecting) {
                    // ＝: the one place a long press carries the note; the row itself is for picking.
                    Box(
                        modifier = Modifier
                            .size(ProductSize.minimumTouchTarget)
                            .onGloballyPositioned { spot.handle = it }
                            // its own node: inside the row it would be merged into the row, which is for picking
                            .semantics(mergeDescendants = true) { contentDescription = "長押ししてノートを並べ替え" }
                            .testTag("note_drag_handle_${note.id}")
                            .cardDragHandle(
                                drag,
                                note.id,
                                enabled = true,
                                origin = spot::handleInRow,
                                shownOrder = shownOrder,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** The row's and its handle's places, kept for the moment a carry starts. */
private class HandleSpot {
    var row: LayoutCoordinates? = null
    var handle: LayoutCoordinates? = null
    fun handleInRow(): Offset {
        val r = row
        val h = handle
        return if (r != null && h != null && r.isAttached && h.isAttached) r.localPositionOf(h, Offset.Zero) else Offset.Zero
    }
}

@Composable
private fun EpisodeRow(episode: NoteEpisode, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ProductSize.minimumTouchTarget)
                    .clickable(onClick = onClick)
                    .padding(start = 28.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                    .testTag("episode_row_${episode.memoId}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ProductSpacing.sm),
            ) {
                Text(
                    "第${episode.number}話",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    episode.title.ifBlank { "無題" },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${episode.characterCount}字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

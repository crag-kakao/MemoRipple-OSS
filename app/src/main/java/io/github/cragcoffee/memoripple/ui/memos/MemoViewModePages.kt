package io.github.cragcoffee.memoripple.ui.memos

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.TagEntity
import io.github.cragcoffee.memoripple.domain.BodyReading
import io.github.cragcoffee.memoripple.domain.ReadingLine
import io.github.cragcoffee.memoripple.domain.WorkLineType
import io.github.cragcoffee.memoripple.domain.memos.displayTitle
import io.github.cragcoffee.memoripple.domain.memos.MemoSearch
import io.github.cragcoffee.memoripple.domain.memos.previewText
import io.github.cragcoffee.memoripple.domain.memos.WallDisplayMode
import io.github.cragcoffee.memoripple.ui.components.ProductEmptyState
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import kotlin.math.roundToInt

/**
 * Where a memo is being looked at from.
 *
 * Three tabs, because there are three kinds of thing: loose memos, outlines, and notes. An
 * outline here is a document made in the outliner (kind = outline) — not a memo that happens to
 * be written with outline marks. That memo stays on the wall, and アウトラインのみ stays a way of
 * drawing the wall, chosen in the display options. The old third tab held the same memos as the
 * first, sorted by how they were written, and split one collection across two places; this tab
 * holds a different kind of thing, decided when it was made.
 */
enum class MemoViewMode { MEMO, OUTLINER, NOTE }

internal val MEMO_VIEW_MODES = listOf(MemoViewMode.MEMO, MemoViewMode.OUTLINER, MemoViewMode.NOTE)

private val MemoViewMode.label: String
    get() = when (this) {
        MemoViewMode.MEMO -> "メモ"
        // The tool is the place; each thing in it is an アウトライン. 「アウトライン」 alone already
        // names the wall view, the symbol setting and the playback scope.
        MemoViewMode.OUTLINER -> "アウトライナー"
        MemoViewMode.NOTE -> "ノート"
    }

private val MemoViewMode.testTag: String
    get() = "memo_view_${name.lowercase()}"

@Composable
internal fun MemoViewModeTabs(pagerState: PagerState, onSelectPage: (Int) -> Unit) {
    // Three words, side by side on the same 24dp rail as everything under them. Full-width
    // cells put the labels at fixed fractions of the screen with gulfs between; three short
    // words do not need a screen's width (they take ~250dp of a 360dp phone), and the eye
    // reads a left rail top to bottom.
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labels = MEMO_VIEW_MODES.map { it.label }
    val labelWidths = remember(labels, style, density, measurer) {
        labels.map { with(density) { measurer.measure(it, style).size.width.toDp() } }
    }
    // Each tab is its label plus TAB_GAP/2 of touch on either side; centres follow from that.
    val centres = remember(labelWidths) {
        var x = 0.dp
        labelWidths.map { width ->
            val centre = x + width / 2
            x += width + TAB_GAP
            centre
        }
    }
    Box(Modifier.fillMaxWidth().padding(start = ProductSize.screenHorizontalPadding)) {
        Row(
            Modifier.heightIn(min = ProductSize.minimumTouchTarget).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            MEMO_VIEW_MODES.forEachIndexed { page, mode ->
                val selected = pagerState.currentPage == page
                Box(
                    modifier = Modifier
                        .width(labelWidths[page] + TAB_GAP)
                        .heightIn(min = ProductSize.minimumTouchTarget)
                        .selectable(
                            selected = selected,
                            onClick = { onSelectPage(page) },
                            role = Role.Tab,
                        )
                        .testTag(mode.testTag),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = mode.label,
                        // Large font scales force a wrap; CJK line breaking keeps the
                        // trailing chouon off the start of a line.
                        style = style.copy(lineBreak = LineBreak.Heading),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // The one word for "chosen", shared with the bottom bar: primary. The
                        // underline stays a tab's own possession, so tabs and nav still differ.
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        TabIndicator(
            pagerState = pagerState,
            labelWidths = labelWidths,
            centres = centres,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

private val TAB_GAP = 24.dp

/**
 * The memo home pager is not measured while the window offers it no width
 * (docs/MEMO_PAGER_ZERO_WIDTH.md).
 *
 * A Galaxy S20 (API 33) measures a recreated window once at 0 × 0 before its real size when the
 * system font scale changes — not on rotation. A HorizontalPager measured with a zero page size
 * treats every page but the last as scrolled off and settles on the last page, throwing away the
 * page it had just restored, so the home came back on ノート instead of メモ. A zero-width pass
 * lays out nothing and leaves the pager alone; every other measure passes through untouched.
 */
internal fun Modifier.skipZeroWidthMeasure(): Modifier = layout { measurable, constraints ->
    if (constraints.maxWidth == 0) {
        // Nothing to show and nothing to measure: the pager keeps the page it has.
        layout(0, constraints.minHeight) {}
    } else {
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/**
 * The mark under the selected tab.
 *
 * It is as wide as the word it belongs to, not as wide as the tab. A full-width bar was how tabs
 * were drawn a decade ago and reads as its age; Material draws the indicator to the label and
 * rounds only the end that shows. Widths and centres are interpolated so it still slides with the
 * pager rather than jumping when the page settles.
 */
@Composable
internal fun TabIndicator(
    pagerState: PagerState,
    labelWidths: List<Dp>,
    centres: List<Dp>,
    modifier: Modifier = Modifier,
) {
    val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
        .coerceIn(0f, (labelWidths.size - 1).toFloat())
    val from = position.toInt().coerceIn(0, labelWidths.size - 1)
    val to = (from + 1).coerceAtMost(labelWidths.size - 1)
    val fraction = position - from
    val width = labelWidths[from] + (labelWidths[to] - labelWidths[from]) * fraction
    val centre = centres[from] + (centres[to] - centres[from]) * fraction
    Box(
        modifier
            .offset { IntOffset((centre - width / 2).toPx().roundToInt(), 0) }
            .width(width)
            .height(TAB_INDICATOR_HEIGHT)
            .background(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(
                    topStart = TAB_INDICATOR_HEIGHT,
                    topEnd = TAB_INDICATOR_HEIGHT,
                ),
            ),
    )
}

private val TAB_INDICATOR_HEIGHT = 3.dp


/**
 * The memos as cards of the height their contents need.
 *
 * A short memo takes a small card and a long one takes a tall card, so the shape of the wall says
 * something about what is on it before a word is read.
 */
@Composable
internal fun MemoCardsPage(
    state: MemoListUiState,
    memos: List<MemoEntity>,
    displayMode: WallDisplayMode,
    singleColumn: Boolean = false,
    gridState: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    highlights: List<String>,
    activeDisplayParts: List<String>,
    onOpenMemo: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onHoldMemo: (MemoEntity) -> Unit,
    onClearDisplayOptions: () -> Unit,
    onClearNarrowing: () -> Unit = onClearDisplayOptions,
    modifier: Modifier = Modifier,
    folderSection: FolderSectionState? = null,
    onReorder: (List<Long>) -> Unit = {},
) {
    // In selection mode a long press carries a card; the order it makes is written on release.
    val dragScope = rememberCoroutineScope()
    val drag = remember(gridState, onReorder) {
        CardDragController(
            scope = dragScope,
            slots = { cardSlots(gridState.layoutInfo.visibleItemsInfo.map { Triple(it.key, it.offset, it.size) }) { it as? Long } },
            onReorder = onReorder,
        )
    }.takeIf { state.isSelectionMode || it.activeId != null }
    val shownMemos = orderedForDrag(memos, drag)
    LaunchedEffect(memos) { drag?.pageCaughtUp(memos.map(MemoEntity::id)) }
    EdgeScrollWhileCarrying(
        drag = drag,
        viewport = { gridState.layoutInfo.viewportStartOffset to gridState.layoutInfo.viewportSize.height },
        scrollBy = { gridState.scrollBy(it) },
    )
    val liftPx = with(LocalDensity.current) { CARD_LIFT.toPx() }
    if (memos.isEmpty() && folderSection?.children.isNullOrEmpty()) {
        if (folderSection?.insideFolder == true) {
            EmptyFolderPage(folderSection, modifier)
            return
        }
        EmptyMemoPage(
            state = state,
            mode = displayMode,
            onClearDisplayOptions = onClearDisplayOptions,
            modifier = modifier,
        )
        return
    }
    LazyVerticalStaggeredGrid(
        // Flat, every card gets the whole line and reads like a list; standing, the wall packs
        // itself into as many 160dp columns as the width gives.
        columns = if (singleColumn) {
            StaggeredGridCells.Fixed(1)
        } else {
            StaggeredGridCells.Adaptive(160.dp)
        },
        state = gridState,
        modifier = modifier.testTag("memo_cards"),
        contentPadding = WALL_CONTENT_PADDING,
        verticalItemSpacing = CARD_GAP,
        horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
    ) {
        if (folderSection != null) {
            if (folderSection.insideFolder) {
                item(key = "folder-breadcrumb", span = StaggeredGridItemSpan.FullLine) {
                    FolderBreadcrumb(folderSection.path, folderSection.onGoTo)
                }
            }
            items(folderSection.children, key = { "folder-${it.id}" }, span = { StaggeredGridItemSpan.FullLine }) { folder ->
                FolderRow(folder, onOpen = { folderSection.onOpen(folder.id) }, onHold = { folderSection.onHold(folder) })
            }
        }
        if (activeDisplayParts.isNotEmpty()) {
            item(span = StaggeredGridItemSpan.FullLine) {
                ActiveDisplayParts(activeDisplayParts, onClearNarrowing)
            }
        }
        items(shownMemos, key = MemoEntity::id) { memo ->
            MemoKeepCard(
                memo = memo,
                tags = state.tagsByMemo[memo.id].orEmpty(),
                photoCount = state.photoCounts[memo.id] ?: 0,
                highlights = highlights,
                selected = memo.id in state.selectedMemoIds,
                selectionMode = state.isSelectionMode,
                onClick = {
                    if (state.isSelectionMode) onToggleSelection(memo.id) else onOpenMemo(memo.id)
                },
                // In selection mode a long press is the handle of a drag, not a toggle.
                onLongClick = if (state.isSelectionMode) null else { { onHoldMemo(memo) } },
                modifier = (if (drag?.activeId == memo.id) Modifier else Modifier.animateItem())
                    .carriedCard(drag, memo.id, liftPx)
                    .cardDragHandle(drag, memo.id, enabled = state.isSelectionMode) { shownMemos.map(MemoEntity::id) },
            )
        }
    }
}

/**
 * The same memos as a shelf: every card the same size, showing only the bones.
 *
 * Because the cards do not vary, the eye compares the structures rather than the lengths. A long
 * memo with three headings and a short one with three headings look alike here, which is the point.
 */
@Composable
internal fun MemoOutlineShelfPage(
    state: MemoListUiState,
    memos: List<MemoEntity>,
    gridState: LazyGridState = rememberLazyGridState(),
    activeDisplayParts: List<String>,
    onOpenMemo: (Long) -> Unit,
    onHoldMemo: (MemoEntity) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onClearDisplayOptions: () -> Unit,
    onClearNarrowing: () -> Unit = onClearDisplayOptions,
    modifier: Modifier = Modifier,
    folderSection: FolderSectionState? = null,
    onReorder: (List<Long>) -> Unit = {},
) {
    val dragScope = rememberCoroutineScope()
    val drag = remember(gridState, onReorder) {
        CardDragController(
            scope = dragScope,
            slots = { cardSlots(gridState.layoutInfo.visibleItemsInfo.map { Triple(it.key, it.offset, it.size) }) { it as? Long } },
            onReorder = onReorder,
        )
    }.takeIf { state.isSelectionMode || it.activeId != null }
    val shownMemos = orderedForDrag(memos, drag)
    LaunchedEffect(memos) { drag?.pageCaughtUp(memos.map(MemoEntity::id)) }
    EdgeScrollWhileCarrying(
        drag = drag,
        viewport = { gridState.layoutInfo.viewportStartOffset to gridState.layoutInfo.viewportSize.height },
        scrollBy = { gridState.scrollBy(it) },
    )
    val liftPx = with(LocalDensity.current) { CARD_LIFT.toPx() }
    if (memos.isEmpty() && folderSection?.children.isNullOrEmpty()) {
        if (folderSection?.insideFolder == true) {
            EmptyFolderPage(folderSection, modifier)
            return
        }
        EmptyMemoPage(
            state = state,
            mode = WallDisplayMode.OUTLINE,
            onClearDisplayOptions = onClearDisplayOptions,
            modifier = modifier,
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        state = gridState,
        modifier = modifier.testTag("memo_outline_shelf"),
        contentPadding = WALL_CONTENT_PADDING,
        verticalArrangement = Arrangement.spacedBy(CARD_GAP),
        horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
    ) {
        if (folderSection != null) {
            if (folderSection.insideFolder) {
                item(key = "folder-breadcrumb", span = { GridItemSpan(maxLineSpan) }) {
                    FolderBreadcrumb(folderSection.path, folderSection.onGoTo)
                }
            }
            items(folderSection.children, key = { "folder-${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { folder ->
                FolderRow(folder, onOpen = { folderSection.onOpen(folder.id) }, onHold = { folderSection.onHold(folder) })
            }
        }
        if (activeDisplayParts.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ActiveDisplayParts(activeDisplayParts, onClearNarrowing)
            }
        }
        items(shownMemos, key = MemoEntity::id) { memo ->
            MemoOutlineSheet(
                memo = memo,
                selected = memo.id in state.selectedMemoIds,
                selectionMode = state.isSelectionMode,
                onClick = {
                    if (state.isSelectionMode) onToggleSelection(memo.id) else onOpenMemo(memo.id)
                },
                onLongClick = if (state.isSelectionMode) null else { { onHoldMemo(memo) } },
                modifier = (if (drag?.activeId == memo.id) Modifier else Modifier.animateItem())
                    .carriedCard(drag, memo.id, liftPx)
                    .cardDragHandle(drag, memo.id, enabled = state.isSelectionMode) { shownMemos.map(MemoEntity::id) },
            )
        }
    }
}

/**
 * The outlines, one to a row.
 *
 * A row shows the outline's opening lines the way the outliner draws them — bullets and
 * depth — so the page reads as what it holds. Rows run full width: an outline is a working
 * document, and a column of them reads as a list of documents rather than a wall of cards.
 * The list is the memo list's own organisation (search, pinned, order, tags) applied to the
 * outlines alone; nothing here is a memo, and nothing here is picked for bulk actions.
 */
@Composable
internal fun OutlinerListPage(
    state: MemoListUiState,
    outlines: List<MemoEntity>,
    activeDisplayParts: List<String>,
    onOpenOutline: (Long) -> Unit,
    onHoldOutline: (MemoEntity) -> Unit,
    onClearNarrowing: () -> Unit,
    modifier: Modifier = Modifier,
    folderSection: FolderSectionState? = null,
    onToggleSelection: (Long) -> Unit = {},
    onReorder: (List<Long>) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    val dragScope = rememberCoroutineScope()
    val drag = remember(listState, onReorder) {
        CardDragController(
            scope = dragScope,
            slots = { cardSlots(listState.layoutInfo.visibleItemsInfo.map { Triple(it.key, IntOffset(0, it.offset), IntSize(Int.MAX_VALUE / 2, it.size)) }) { it as? Long } },
            onReorder = onReorder,
        )
    }.takeIf { state.isSelectionMode || it.activeId != null }
    val shownOutlines = orderedForDrag(outlines, drag)
    LaunchedEffect(outlines) { drag?.pageCaughtUp(outlines.map(MemoEntity::id)) }
    EdgeScrollWhileCarrying(
        drag = drag,
        viewport = { listState.layoutInfo.viewportStartOffset to listState.layoutInfo.viewportSize.height },
        scrollBy = { listState.scrollBy(it) },
    )
    val liftPx = with(LocalDensity.current) { CARD_LIFT.toPx() }
    if (outlines.isEmpty() && folderSection?.insideFolder == true && folderSection.children.isEmpty()) {
        EmptyFolderPage(folderSection, modifier)
        return
    }
    if (outlines.isEmpty() && folderSection?.children.isNullOrEmpty()) {
        // Says which is the case: none made yet, or none inside the search or the narrowing.
        val narrowed = state.totalOutlineCount > 0
        ProductEmptyState(
            title = if (narrowed) "該当するアウトラインはありません" else "アウトライナーはまだありません",
            description = if (narrowed) null else "＋で作ると、1行が1つのコメントになります。",
            // The floating button already offers this; a second one only splits the eye.
            modifier = modifier.fillMaxSize()
                .padding(bottom = ProductSpacing.xxl)
                .testTag(if (narrowed) "outliner_search_empty_state" else "outliner_empty_state"),
        )
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.testTag("outliner_list_page"),
        contentPadding = WALL_CONTENT_PADDING,
        verticalArrangement = Arrangement.spacedBy(CARD_GAP),
    ) {
        if (folderSection != null) {
            if (folderSection.insideFolder) {
                item(key = "folder-breadcrumb") { FolderBreadcrumb(folderSection.path, folderSection.onGoTo) }
            }
            items(folderSection.children, key = { "folder-${it.id}" }) { folder ->
                FolderRow(folder, onOpen = { folderSection.onOpen(folder.id) }, onHold = { folderSection.onHold(folder) })
            }
        }
        if (activeDisplayParts.isNotEmpty()) {
            item { ActiveDisplayParts(activeDisplayParts, onClearNarrowing) }
        }
        items(shownOutlines, key = MemoEntity::id) { outline ->
            OutlineDocumentRow(
                outline = outline,
                tags = state.tagsByMemo[outline.id].orEmpty(),
                selected = outline.id in state.selectedMemoIds,
                selectionMode = state.isSelectionMode,
                onClick = { if (state.isSelectionMode) onToggleSelection(outline.id) else onOpenOutline(outline.id) },
                onLongClick = if (state.isSelectionMode) null else { { onHoldOutline(outline) } },
                modifier = (if (drag?.activeId == outline.id) Modifier else Modifier.animateItem())
                    .carriedCard(drag, outline.id, liftPx)
                    .cardDragHandle(drag, outline.id, enabled = state.isSelectionMode) { shownOutlines.map(MemoEntity::id) },
            )
        }
    }
}

@Composable
private fun OutlineDocumentRow(
    outline: MemoEntity,
    tags: List<TagEntity>,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectionMode: Boolean = false,
) {
    val lines = remember(outline.body) { BodyReading.lines(outline.body) }
    // An outline is often untitled: its first line is what it is about.
    val title = outline.title.ifBlank {
        lines.firstOrNull()?.text?.takeIf(String::isNotBlank) ?: "無題のアウトライナー"
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .testTag("outline_card_${outline.id}"),
        shape = MaterialTheme.shapes.large,
        color = cardFill(selected),
        border = cardOutline(selected),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = CARD_INNER_HORIZONTAL_PADDING,
                vertical = ProductSpacing.md,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (outline.isPinned) {
                    Icon(
                        Icons.Outlined.PushPin,
                        contentDescription = "固定済み",
                        modifier = Modifier.padding(end = ProductSpacing.xs).size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (selectionMode) {
                    SelectionMark(selected = selected, memoId = outline.id)
                }
            }
            Column(modifier = Modifier.padding(top = ProductSpacing.sm)) {
                if (lines.isEmpty()) {
                    Text(
                        "まだ何も書かれていません",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                lines.take(OUTLINE_ROW_LINES).forEach { line -> OutlineSheetLine(line) }
            }
            if (tags.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = ProductSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs),
                ) {
                    tags.take(CARD_TAGS).forEach { tag ->
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                tag.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(
                                    horizontal = ProductSpacing.sm,
                                    vertical = 3.dp,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val OUTLINE_ROW_LINES = 3

@Composable
private fun MemoOutlineSheet(
    memo: MemoEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val lines = remember(memo.body) { BodyReading.lines(memo.body) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(SHEET_HEIGHT)
            .semantics { this.selected = selected }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .testTag("memo_sheet_${memo.id}"),
        shape = MaterialTheme.shapes.large,
        color = cardFill(selected),
        border = cardOutline(selected),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = CARD_INNER_HORIZONTAL_PADDING,
                vertical = ProductSpacing.md,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    memo.displayTitle(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (selectionMode) {
                    SelectionMark(selected = selected, memoId = memo.id)
                }
            }
            Column(modifier = Modifier.padding(top = ProductSpacing.sm)) {
                if (lines.isEmpty()) {
                    Text(
                        "見出しはまだありません",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                lines.take(SHEET_LINES).forEach { line -> OutlineSheetLine(line) }
            }
        }
    }
}

@Composable
private fun OutlineSheetLine(line: ReadingLine) {
    val marker = when (line.type) {
        WorkLineType.HEADING -> "■"
        WorkLineType.TASK -> "☐"
        WorkLineType.TASK_DONE -> "☑"
        WorkLineType.ITEM -> "・"
        WorkLineType.NOTE -> "▏"
        WorkLineType.IMPORTANT -> "！"
        WorkLineType.QUESTION -> "？"
        WorkLineType.PLAIN -> ""
    }
    val heading = line.type == WorkLineType.HEADING
    // Four levels at one size: weight lifts the heading, colour drops the aside. Indenting by a
    // whole 16dp step rather than 8dp makes the nesting wider than the letters it nests.
    val aside = line.type == WorkLineType.NOTE ||
        line.type == WorkLineType.QUESTION ||
        line.type == WorkLineType.PLAIN
    Row(
        modifier = Modifier.padding(start = ProductSpacing.lg * line.depth),
        verticalAlignment = Alignment.Top,
    ) {
        if (marker.isNotEmpty()) {
            Text(
                marker,
                style = MaterialTheme.typography.bodySmall,
                color = if (heading) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(end = ProductSpacing.xs),
            )
        }
        Text(
            line.text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (heading) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (aside) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ActiveDisplayParts(parts: List<String>, onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = parts.joinToString(" ・ "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).testTag("memo_active_filters"),
        )
        TextButton(
            onClick = onClear,
            modifier = Modifier.testTag("clear_memo_display_options"),
        ) { Text("クリア") }
    }
}

/**
 * Nothing here.
 *
 * A narrowed view can be empty while the wall is full, so it says which is the case. Being told
 * there are no memos when there are plenty, only outside the chosen view, would be a lie.
 */
@Composable
private fun EmptyMemoPage(
    state: MemoListUiState,
    mode: WallDisplayMode,
    onClearDisplayOptions: () -> Unit,
    modifier: Modifier,
) {
    val elsewhere = state.memos.isNotEmpty()
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (elsewhere) {
            ProductEmptyState(
                title = when (mode) {
                    WallDisplayMode.OUTLINE -> "骨組みのあるメモはまだありません"
                    else -> "記法のないメモはまだありません"
                },
                description = when (mode) {
                    WallDisplayMode.OUTLINE ->
                        "見出しや項目を付けたメモが、ここに並びます。"
                    else ->
                        "書いたメモは見出しや項目を持っています。表示を「メモとアウトライン」に戻すと並びます。"
                },
                // The button that writes is already on screen; a second one would be noise.
                modifier = Modifier.fillMaxSize()
                    .padding(bottom = ProductSpacing.xxl)
                    .testTag(
                        if (mode == WallDisplayMode.OUTLINE) {
                            "memo_outline_empty_state"
                        } else {
                            "memo_plain_empty_state"
                        },
                    ),
            )
        } else {
            EmptyMemoList(state = state, onShowAll = onClearDisplayOptions)
        }
    }
}

private val SHEET_HEIGHT = 172.dp
private const val SHEET_LINES = 5

/**
 * The line a card keeps only while it is chosen.
 *
 * An ordinary card draws no line any more. The outline earned its keep when the card was
 * filled with the page's own colour and the stroke was the only edge there was — but that
 * stroke was also the first thing the eye met, a box read before its memo. The 1.0 polish
 * moves the boundary into the surface itself (`surfaceContainerLow` against the page), so
 * separation comes from the quiet fill and the gaps, and the words come first. Selection
 * keeps its primary edge: that line is a state, not decoration.
 */
@Composable
internal fun cardOutline(selected: Boolean): BorderStroke? = if (selected) {
    BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
} else {
    null
}

/** The card's own colour: one quiet step off the page, in both themes. */
@Composable
internal fun cardFill(selected: Boolean) = if (selected) {
    MaterialTheme.colorScheme.primaryContainer
} else {
    MaterialTheme.colorScheme.surfaceContainerLow
}

/**
 * Cards breathe a little wider than they used to: without an outline the gap is the
 * separator, and 14dp of inner air keeps the words off the rounded corner.
 */
private val CARD_INNER_HORIZONTAL_PADDING = 14.dp
private val CARD_GAP = 10.dp

/** The wall breathes at the top and clears the button that writes at the bottom. */
private val WALL_CONTENT_PADDING = PaddingValues(top = ProductSpacing.md, bottom = 88.dp)

/**
 * One memo as a card.
 *
 * What the card shows is what the memo mostly is: a list of tasks shows its boxes, anything else
 * shows its opening words. Reading the wall should not need opening anything.
 */
@Composable
private fun MemoKeepCard(
    memo: MemoEntity,
    tags: List<TagEntity>,
    photoCount: Int,
    highlights: List<String>,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // A structured memo shows its bones and a plain one its opening words — on the same wall.
    // The bones are one quiet line, headings joined by slashes behind a rule: on the combined
    // wall a card only has to say what kind of thing it is, and the outline-only display is
    // still there for reading the structure at full size.
    val bones = remember(memo.body) {
        if (!BodyReading.hasStructure(memo.body)) {
            ""
        } else {
            val lines = BodyReading.lines(memo.body)
            val headings = lines.filter { it.type == WorkLineType.HEADING }
            (headings.ifEmpty { lines }).joinToString(" / ") { it.text }
        }
    }
    val preview = remember(memo.body) {
        if (BodyReading.hasStructure(memo.body)) "" else memo.previewText()
    }
    // While a search is on, the card's job changes: not what the memo opens with, but where
    // the search landed. The snippet is the hit line, markers off, trimmed around the term —
    // built and tested long ago, wired in here. A title-only hit has no body line to show and
    // falls back to the ordinary preview.
    val searchSnippet = remember(memo.body, highlights) {
        if (highlights.isEmpty()) null else MemoSearch.snippet(memo.body, highlights)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .testTag("memo_card_${memo.id}"),
        shape = MaterialTheme.shapes.large,
        color = cardFill(selected),
        border = cardOutline(selected),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = CARD_INNER_HORIZONTAL_PADDING,
                vertical = ProductSpacing.md,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (memo.isPinned) {
                    Icon(
                        Icons.Outlined.PushPin,
                        contentDescription = "固定済み",
                        modifier = Modifier.padding(end = ProductSpacing.xs).size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    memo.displayTitle(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (selectionMode) {
                    SelectionMark(selected = selected, memoId = memo.id)
                }
            }
            if (searchSnippet != null) {
                Text(
                    text = markMatches(searchSnippet, highlights),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = ProductSpacing.xs)
                        .testTag("memo_preview_${memo.id}"),
                )
            } else if (bones.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .padding(top = ProductSpacing.xs)
                        .height(IntrinsicSize.Min),
                ) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outline),
                    )
                    Text(
                        bones,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = ProductSpacing.sm),
                    )
                }
            } else if (preview.isNotBlank()) {
                Text(
                    text = markMatches(preview, highlights),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = CARD_PREVIEW_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = ProductSpacing.xs)
                        .testTag("memo_preview_${memo.id}"),
                )
            }
            if (tags.isNotEmpty() || photoCount > 0) {
                Row(
                    modifier = Modifier.padding(top = ProductSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tags.take(CARD_TAGS).forEach { tag ->
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                tag.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(
                                    horizontal = ProductSpacing.sm,
                                    vertical = 3.dp,
                                ),
                            )
                        }
                    }
                    if (photoCount > 0) {
                        Text(
                            "写真${photoCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private const val CARD_PREVIEW_LINES = 4
private const val CARD_TAGS = 2

@Composable
internal fun SelectionMark(selected: Boolean, memoId: Long, tag: String = "memo_selected_$memoId") {
    Icon(
        if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
        contentDescription = if (selected) "選択済み" else "未選択",
        modifier = Modifier.size(20.dp).testTag(tag),
        tint = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/** An open folder with nothing of this kind in it: the breadcrumb, and the one line that says so. */
@Composable
private fun EmptyFolderPage(section: FolderSectionState, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(top = ProductSpacing.md)) {
        FolderBreadcrumb(section.path, section.onGoTo)
        FolderEmptyState()
    }
}

/** The cards in the order the drag is making, or the page's own order. */
private fun orderedForDrag(memos: List<MemoEntity>, drag: CardDragController?): List<MemoEntity> {
    val order = drag?.localOrder ?: return memos
    val position = order.withIndex().associate { (index, id) -> id to index }
    return memos.sortedBy { position[it.id] ?: Int.MAX_VALUE }
}

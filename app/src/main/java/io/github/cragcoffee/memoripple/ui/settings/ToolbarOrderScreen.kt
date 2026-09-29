package io.github.cragcoffee.memoripple.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.domain.memos.ToolbarChipGroup
import io.github.cragcoffee.memoripple.ui.memos.carriedCard
import io.github.cragcoffee.memoripple.ui.memos.cardSlots
import io.github.cragcoffee.memoripple.ui.memos.cardDragHandle
import io.github.cragcoffee.memoripple.ui.memos.CARD_LIFT
import io.github.cragcoffee.memoripple.ui.memos.CardDragController
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.lazy.rememberLazyListState
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.domain.memos.EditorToolbarArrangement
import io.github.cragcoffee.memoripple.domain.memos.EditorToolbarItem
import io.github.cragcoffee.memoripple.domain.memos.EditorToolbarSurface
import io.github.cragcoffee.memoripple.domain.memos.EditorToolbarOrder
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.components.ProductTopBar
import kotlinx.coroutines.launch

/**
 * The shortcut bar in the writer's own order, one arrangement per kind of writing: メモ and
 * ノート each keep their own. Each row is one movable unit of that editor's toolbar; the
 * handle drags it up or down the list, the eye puts it away, and the bar wears the
 * arrangement at once. The choice is a way of writing, so it lives on this device — never
 * backed up, never reset with the product settings.
 *
 * When the bar stands in two rows (設定 > ショートカットバーを二段にする), this list is
 * divided the same way — 上部の段 and 下部の段, starting where the bar has always put them —
 * and dragging a unit past the divider moves it to the other row.
 */
@Composable
fun ToolbarOrderRoute(
    surface: EditorToolbarSurface,
    onBack: () -> Unit,
    /** The two chip units have a stage of their own, where their chips are arranged. */
    onOpenChips: (ToolbarChipGroup) -> Unit = {},
) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    val repository = application.settingsRepository
    val storedFlow = when (surface) {
        EditorToolbarSurface.MEMO -> repository.editorToolbarOrder
        EditorToolbarSurface.NOTE -> repository.editorToolbarNoteOrder
        EditorToolbarSurface.OUTLINER -> repository.editorToolbarOutlinerOrder
    }
    val stored by storedFlow.collectAsState(initial = "")
    // The outliner's bar is one row whatever the editors' bars are set to.
    val twoRowsSetting by repository.editorToolbarTwoRows.collectAsState(initial = false)
    val twoRows = twoRowsSetting
    val scope = rememberCoroutineScope()
    suspend fun store(value: String) = when (surface) {
        EditorToolbarSurface.MEMO -> repository.setEditorToolbarOrder(value)
        EditorToolbarSurface.NOTE -> repository.setEditorToolbarNoteOrder(value)
        EditorToolbarSurface.OUTLINER -> repository.setEditorToolbarOutlinerOrder(value)
    }

    var local by remember { mutableStateOf(EditorToolbarOrder.defaultArrangement(surface)) }
    var pendingPersisted by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val localNow = rememberUpdatedState(local)
    val twoRowsNow = rememberUpdatedState(twoRows)

    /** One step through the flat list: the bar is one row, so the list is one list. */
    fun moveInOneRow(item: EditorToolbarItem, direction: Int): Boolean {
        val fromIndex = local.order.indexOf(item)
        if (fromIndex < 0) return false
        val targetIndex = (fromIndex + direction).coerceIn(local.order.indices)
        if (targetIndex == fromIndex) return false
        local = local.copy(
            order = local.order.toMutableList().apply { add(targetIndex, removeAt(fromIndex)) },
        )
        return true
    }

    /**
     * One step through the divided list: a unit walks its own row until it reaches the
     * divider, and the next step across takes it into the other row.
     */
    fun moveInTwoRows(item: EditorToolbarItem, direction: Int): Boolean {
        val inLower = local.isLower(item)
        val group = local.order.filter { local.isLower(it) == inLower }
        val index = group.indexOf(item)
        if (index < 0) return false
        val leavingDown = direction > 0 && index == group.lastIndex
        val leavingUp = direction < 0 && index == 0
        if (leavingDown || leavingUp) {
            // Off the bottom of the lower row, or off the top of the upper one: nowhere left.
            if (leavingDown == inLower) return false
            local = local.withRow(item, toLower = leavingDown)
            return true
        }
        val neighbour = group[index + direction]
        val here = local.order.indexOf(item)
        val there = local.order.indexOf(neighbour)
        local = local.copy(
            order = local.order.toMutableList().apply {
                set(here, neighbour)
                set(there, item)
            },
        )
        return true
    }

    // Read through the holder: the drag controller keeps this function from its first composition.
    fun move(item: EditorToolbarItem, direction: Int): Boolean =
        if (twoRowsNow.value) moveInTwoRows(item, direction) else moveInOneRow(item, direction)

    fun persist() {
        val encoded = EditorToolbarOrder.encode(local)
        if (encoded != stored) {
            pendingPersisted = encoded
            scope.launch { store(encoded) }
        }
    }

    fun moveAndPersist(item: EditorToolbarItem, direction: Int) {
        if (move(item, direction)) persist()
    }

    // A handle carries its row as a floating card; the others slide as it crosses them, every
    // crossing one step of the same walk the buttons take; release writes the arrangement.
    val drag = remember(listState) {
        CardDragController(
            scope = scope,
            slots = {
                cardSlots(listState.layoutInfo.visibleItemsInfo.map { Triple(it.key, IntOffset(0, it.offset), IntSize(Int.MAX_VALUE / 2, it.size)) }) { key ->
                    (key as? String)?.let { id -> EditorToolbarItem.entries.firstOrNull { it.storageId == id }?.ordinal?.toLong() }
                }
            },
            onReorder = { persist() },
            onCrossed = { id, targetId ->
                val item = EditorToolbarItem.entries[id.toInt()]
                val target = EditorToolbarItem.entries[targetId.toInt()]
                val shown = localNow.value.let { a -> if (twoRowsNow.value) a.order.filterNot(a::isLower) + a.order.filter(a::isLower) else a.order }
                val from = shown.indexOf(item)
                val to = shown.indexOf(target)
                if (from >= 0 && to >= 0) repeat(kotlin.math.abs(to - from)) { move(item, if (to > from) 1 else -1) }
            },
        )
    }
    val liftPx = with(LocalDensity.current) { CARD_LIFT.toPx() }
    LaunchedEffect(stored, drag.carriedId, pendingPersisted) {
        if (pendingPersisted == stored) pendingPersisted = null
        if (drag.carriedId == null && pendingPersisted == null) {
            local = EditorToolbarOrder.decodeArrangement(stored, surface)
        }
    }

    fun toggleHidden(item: EditorToolbarItem) {
        local = local.withHidden(item, !local.isHidden(item))
        persist()
    }

    Scaffold(
        topBar = {
            ProductTopBar(
                title = when (surface) {
                    EditorToolbarSurface.MEMO -> "ショートカットバー（メモ）"
                    EditorToolbarSurface.NOTE -> "ショートカットバー（ノート）"
                    EditorToolbarSurface.OUTLINER -> "ショートカットバー（アウトライナー）"
                },
                onBack = onBack,
                actions = {
                    TextButton(
                        onClick = {
                            local = EditorToolbarOrder.defaultArrangement(surface)
                            pendingPersisted = ""
                            scope.launch { store("") }
                        },
                        modifier = Modifier.testTag("toolbar_order_reset"),
                    ) { Text("元の並びに戻す") }
                },
            )
        },
    ) { padding ->
        val rows: LazyListScope.(List<EditorToolbarItem>) -> Unit = { group ->
            items(group, key = EditorToolbarItem::storageId) { item ->
                val id = item.ordinal.toLong()
                ToolbarOrderRow(
                    item = item,
                    hidden = local.isHidden(item),
                    onToggleHidden = { toggleHidden(item) },
                    onMove = { direction -> moveAndPersist(item, direction) },
                    onOpen = when (item) {
                        EditorToolbarItem.OUTLINE_LABELS -> { { onOpenChips(ToolbarChipGroup.LABELS) } }
                        EditorToolbarItem.FLOW_MODIFIERS -> { { onOpenChips(ToolbarChipGroup.FLOW) } }
                        else -> null
                    },
                    modifier = (if (drag.activeId == id) Modifier else Modifier.animateItem()).carriedCard(drag, id, liftPx),
                    dragHandle = Modifier.cardDragHandle(drag, id, enabled = true, longPress = false) { emptyList() },
                )
            }
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding).testTag("toolbar_order_list")) {
            item {
                Text(
                    buildString {
                        append(
                            when (surface) {
                                EditorToolbarSurface.MEMO ->
                                    "ハンドルを引いて、メモの編集画面のショートカットバーの並びを変えられます。"
                                EditorToolbarSurface.NOTE ->
                                    "ハンドルを引いて、ノート（エピソード）の編集画面の" +
                                        "ショートカットバーの並びを変えられます。"
                                EditorToolbarSurface.OUTLINER ->
                                    "ハンドルを引いて、アウトライナーのショートカットバーの並びを変えられます。"
                            },
                        )
                        append("目のボタンで、使わないものをバーから隠せます。隠しても並びの位置は覚えています。")
                        if (twoRows) {
                            append("バーは二段です。段の境目をまたいでハンドルを引くと、もう一方の段へ移ります。")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = ProductSpacing.lg,
                        vertical = ProductSpacing.sm,
                    ),
                )
            }
            if (!twoRows) {
                rows(local.order)
                return@LazyColumn
            }
            val upper = local.order.filterNot(local::isLower)
            val lower = local.order.filter(local::isLower)
            item { RowHeading("上部の段", "toolbar_order_heading_upper") }
            rows(upper)
            item { RowHeading("下部の段", "toolbar_order_heading_lower") }
            rows(lower)
        }
    }
}

/** The name of one row of the two-row bar, standing over the units that ride in it. */
@Composable
private fun RowHeading(label: String, tag: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ProductSpacing.lg,
                end = ProductSpacing.lg,
                top = ProductSpacing.md,
                bottom = ProductSpacing.xs,
            )
            .testTag(tag),
    )
}

@Composable
private fun ToolbarOrderRow(
    item: EditorToolbarItem,
    hidden: Boolean,
    onToggleHidden: () -> Unit,
    onMove: (Int) -> Unit,
    modifier: Modifier = Modifier,
    dragHandle: Modifier = Modifier,
    /** A unit made of chips opens a stage of its own on a tap. */
    onOpen: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = {
            Text(
                item.label,
                color = if (hidden) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
        supportingContent = {
            Text(
                if (hidden) "バーに出していません" else item.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onOpen != null) {
                    // A unit made of chips opens its own stage from this button; the row itself
                    // stays a plain row, so the handle beside it drags as every other handle does.
                    IconButton(
                        onClick = onOpen,
                        modifier = Modifier.size(HANDLE_SIZE).testTag("toolbar_order_open_${item.storageId}"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = "${item.label}の中身を並べ替える",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(
                    onClick = onToggleHidden,
                    modifier = Modifier
                        .size(HANDLE_SIZE)
                        .testTag("toolbar_order_hide_${item.storageId}"),
                ) {
                    Icon(
                        if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (hidden) {
                            "${item.label}をバーに出す"
                        } else {
                            "${item.label}をバーから隠す"
                        },
                        tint = if (hidden) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                Box(
                    modifier = Modifier
                        .size(HANDLE_SIZE)
                        .testTag("toolbar_order_handle_${item.storageId}")
                        .semantics {
                            contentDescription = "${item.label}を並べ替え"
                            customActions = listOf(
                                CustomAccessibilityAction("上へ移動") {
                                    onMove(-1)
                                    true
                                },
                                CustomAccessibilityAction("下へ移動") {
                                    onMove(1)
                                    true
                                },
                            )
                        }
                        .then(dragHandle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.DragHandle, contentDescription = null)
                }
            }
        },
        modifier = modifier.fillMaxWidth().testTag("toolbar_order_row_${item.storageId}"),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private val HANDLE_SIZE = 48.dp

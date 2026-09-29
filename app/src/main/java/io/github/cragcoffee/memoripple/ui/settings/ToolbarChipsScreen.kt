package io.github.cragcoffee.memoripple.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.domain.OutlineChipLabel
import io.github.cragcoffee.memoripple.domain.OutlineSymbolSelection
import io.github.cragcoffee.memoripple.domain.memos.EditorToolbarSurface
import io.github.cragcoffee.memoripple.domain.memos.ToolbarChipGroup
import io.github.cragcoffee.memoripple.domain.memos.ToolbarChips
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.components.ProductTopBar
import io.github.cragcoffee.memoripple.ui.memos.CARD_LIFT
import io.github.cragcoffee.memoripple.ui.memos.CardDragController
import io.github.cragcoffee.memoripple.ui.memos.cardDragHandle
import io.github.cragcoffee.memoripple.ui.memos.cardSlots
import io.github.cragcoffee.memoripple.ui.memos.carriedCard
import kotlinx.coroutines.launch

/**
 * One chip unit of a bar — 見出し・項目などの記号 or 流れ方 — on its own stage: every chip in the
 * writer's order, the eye puts one away, the handle carries one up or down as a floating row
 * while the others slide, the bar wears the arrangement at once. One arrangement per bar.
 */
@Composable
fun ToolbarChipsRoute(surface: EditorToolbarSurface, group: ToolbarChipGroup, onBack: () -> Unit) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    val repository = application.settingsRepository
    val stored by repository.toolbarChips(surface.name, group.name).collectAsState(initial = "")
    val symbolsStored by repository.outlineSymbolSet.collectAsState(initial = "")
    val symbols = remember(symbolsStored) { OutlineSymbolSelection.decode(symbolsStored) }
    val chipLabelStored by repository.outlineChipLabel.collectAsState(initial = "")
    val chipLabel = remember(chipLabelStored) { OutlineChipLabel.fromStorageId(chipLabelStored) }
    val scope = rememberCoroutineScope()

    var local by remember { mutableStateOf(ToolbarChips.defaultArrangement(group)) }
    var pendingPersisted by remember { mutableStateOf<String?>(null) }
    val localNow = rememberUpdatedState(local)
    val ids = remember(group) { ToolbarChips.chipsFor(group).map { it.id } }

    fun persist() {
        val encoded = ToolbarChips.encode(local)
        if (encoded != stored) {
            pendingPersisted = encoded
            scope.launch { repository.setToolbarChips(surface.name, group.name, encoded) }
        }
    }

    val listState = rememberLazyListState()
    val drag = remember(listState) {
        CardDragController(
            scope = scope,
            slots = {
                cardSlots(listState.layoutInfo.visibleItemsInfo.map { Triple(it.key, IntOffset(0, it.offset), IntSize(Int.MAX_VALUE / 2, it.size)) }) { key ->
                    (key as? String)?.let { id -> ids.indexOf(id).takeIf { it >= 0 }?.toLong() }
                }
            },
            onReorder = { persist() },
            onCrossed = { id, targetId ->
                val chip = ids[id.toInt()]
                val from = localNow.value.order.indexOf(chip)
                val to = localNow.value.order.indexOf(ids[targetId.toInt()])
                if (from >= 0 && to >= 0) repeat(kotlin.math.abs(to - from)) { local = local.moved(chip, if (to > from) 1 else -1) }
            },
        )
    }
    val liftPx = with(LocalDensity.current) { CARD_LIFT.toPx() }
    LaunchedEffect(stored, drag.carriedId, pendingPersisted) {
        if (pendingPersisted == stored) pendingPersisted = null
        if (drag.carriedId == null && pendingPersisted == null) local = ToolbarChips.decode(stored, group)
    }

    Scaffold(
        topBar = {
            ProductTopBar(
                title = group.title,
                onBack = onBack,
                actions = {
                    TextButton(
                        onClick = {
                            local = ToolbarChips.defaultArrangement(group)
                            pendingPersisted = ""
                            scope.launch { repository.setToolbarChips(surface.name, group.name, "") }
                        },
                        modifier = Modifier.testTag("toolbar_chips_reset"),
                    ) { Text("元の並びに戻す") }
                },
            )
        },
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding).testTag("toolbar_chips_list")) {
            item {
                Text(
                    "ハンドルを引いて、バーに並ぶ順を変えられます。目のボタンで、使わないものを隠せます。隠しても並びの位置は覚えています。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = ProductSpacing.lg, vertical = ProductSpacing.sm),
                )
            }
            items(local.order, key = { it }) { id ->
                val chip = ToolbarChips.chip(group, id) ?: return@items
                val slot = ids.indexOf(id).toLong()
                // The row wears what the bar will show: the glyph the chosen set writes, and the word.
                val face = when (group) {
                    ToolbarChipGroup.LABELS -> ToolbarChips.roleOf(id)?.let { role -> chipLabel.textFor(chip.word, role, symbols) } ?: chip.word
                    ToolbarChipGroup.FLOW -> "${chip.word} ${chip.description}"
                }
                val hidden = local.isHidden(id)
                ListItem(
                    headlineContent = { Text(face, color = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface) },
                    supportingContent = { Text(if (hidden) "バーに出していません" else chip.description, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { local = local.withHidden(id, !hidden); persist() },
                                modifier = Modifier.size(48.dp).testTag("toolbar_chips_hide_$id"),
                            ) {
                                Icon(
                                    if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (hidden) "${chip.word}をバーに出す" else "${chip.word}をバーから隠す",
                                    tint = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("toolbar_chips_handle_$id")
                                    .semantics {
                                        contentDescription = "${chip.word}を並べ替え"
                                        customActions = listOf(
                                            CustomAccessibilityAction("上へ移動") { local = local.moved(id, -1); persist(); true },
                                            CustomAccessibilityAction("下へ移動") { local = local.moved(id, 1); persist(); true },
                                        )
                                    }
                                    .cardDragHandle(drag, slot, enabled = true, longPress = false) { emptyList() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Outlined.DragHandle, contentDescription = null)
                            }
                        }
                    },
                    modifier = (if (drag.activeId == slot) Modifier else Modifier.animateItem())
                        .carriedCard(drag, slot, liftPx)
                        .fillMaxWidth()
                        .testTag("toolbar_chips_row_$id"),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

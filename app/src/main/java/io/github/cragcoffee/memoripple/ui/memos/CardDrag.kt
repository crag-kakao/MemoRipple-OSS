package io.github.cragcoffee.memoripple.ui.memos

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures

/** One card's place on its page, in the page's own offsets (what the lazy layout reports). */
internal data class CardSlot(val id: Long, val left: Float, val top: Float, val width: Float, val height: Float) {
    fun contains(x: Float, y: Float): Boolean = x >= left && x < left + width && y >= top && y < top + height
}

/** The pure part of a card drag: which slot the finger is over, and the order that follows. */
internal object CardDrop {
    /** The index in [slots] of the slot under the finger, or -1 between cards. */
    fun indexUnder(slots: List<CardSlot>, x: Float, y: Float): Int = slots.indexOfFirst { it.contains(x, y) }

    /** The index in [slots] of the row whose middle is nearest the finger's height — for a list, where a gap or a heading still means "here". */
    fun indexNearest(slots: List<CardSlot>, y: Float): Int =
        slots.withIndex().minByOrNull { (_, s) -> kotlin.math.abs(s.top + s.height / 2 - y) }?.index ?: -1

    /** [order] with [id] taken out and put back at [targetIndex]; unchanged when already there. */
    fun reorder(order: List<Long>, id: Long, targetIndex: Int): List<Long> {
        val from = order.indexOf(id)
        if (from < 0 || targetIndex !in order.indices || from == targetIndex) return order
        return order.toMutableList().apply { removeAt(from); add(targetIndex, id) }
    }
}

/**
 * A card held and carried across its page, the way Keep does it: the card lifts and rides the
 * finger, the other cards slide aside as it passes over their slots (the page shows the order
 * the drag is making), and on release the order is written once and the card settles into the
 * slot it made for itself. One controller per page; the page tells it where the cards are.
 */
internal class CardDragController(
    private val scope: CoroutineScope,
    private val slots: () -> List<CardSlot>,
    private val onReorder: (List<Long>) -> Unit,
    /**
     * For a list whose order is written the moment it changes (a note's rows): called with the
     * carried id and the id of the row the finger has crossed onto (only rows on screen have
     * slots, so an index would not do), instead of keeping a local order for release.
     */
    private val onCrossed: ((Long, Long) -> Unit)? = null,
) {
    private class Drag(val id: Long, val grabX: Float, val grabY: Float, val height: Float, x: Float, y: Float) {
        var pointerX by mutableFloatStateOf(x)
        var pointerY by mutableFloatStateOf(y)

        // Where the carried card itself stands: its top rides with the finger at the grab offset.
        val cardCenterY: Float get() = pointerY - grabY + height / 2f
    }

    private var drag by mutableStateOf<Drag?>(null)
    // Crossing mode: the row last crossed onto. Pointer events arrive faster than the list
    // re-lays out, so the same stale slot would be crossed again and again; one crossing per
    // target, until the finger moves onto another row (or back onto its own).
    private var lastCrossed: Long? = null
    private val settle = Animatable(0f)
    private val settleX = Animatable(0f)

    /** The card being carried, or the one settling after release. */
    var activeId: Long? by mutableStateOf(null)
        private set

    /** The order the drag is making — shown while it is under way and until the page catches up. */
    var localOrder: List<Long>? by mutableStateOf(null)
        private set

    val carriedId: Long? get() = drag?.id

    fun isCarried(id: Long): Boolean = drag?.id == id

    /** Where the finger is, in the page's offsets — for the page's edge scrolling. */
    val pointerY: Float? get() = drag?.pointerY

    fun grab(id: Long, grabX: Float, grabY: Float, shownOrder: List<Long>) {
        val slot = slots().firstOrNull { it.id == id } ?: return
        drag = Drag(id, grabX, grabY, slot.height, slot.left + grabX, slot.top + grabY)
        lastCrossed = null
        activeId = id
        localOrder = shownOrder
    }

    fun carry(dx: Float, dy: Float) {
        val d = drag ?: return
        d.pointerX += dx
        d.pointerY += dy
        val current = slots()
        val crossed = onCrossed
        if (crossed != null) {
            // A list: the row whose middle the carried row's own middle is nearest to counts — not
            // the finger, which may hold the row anywhere along its height — so a heading or a
            // gap between rows is no dead zone, and a row grabbed by its edge still has to
            // travel a row to change places.
            val index = CardDrop.indexNearest(current, d.cardCenterY)
            if (index < 0) return
            val target = current[index].id
            when {
                target == d.id -> lastCrossed = null
                target != lastCrossed -> {
                    lastCrossed = target
                    crossed(d.id, target)
                }
            }
            return
        }
        val index = CardDrop.indexUnder(current, d.pointerX, d.pointerY)
        if (index < 0) return
        val order = localOrder ?: return
        localOrder = CardDrop.reorder(order, d.id, index)
    }

    fun release() {
        val d = drag ?: return
        val order = localOrder
        val slot = slots().firstOrNull { it.id == d.id }
        drag = null
        // The card settles from under the finger into the slot the drag made for it.
        val residualX = slot?.let { d.pointerX - d.grabX - it.left } ?: 0f
        val residualY = slot?.let { d.pointerY - d.grabY - it.top } ?: 0f
        // A list in crossing mode has written every step already; it still hears the release.
        onReorder(if (onCrossed == null) order.orEmpty() else emptyList())
        scope.launch {
            settleX.snapTo(residualX)
            settle.snapTo(residualY)
            launch { settleX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
            settle.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
            if (drag == null) activeId = null
        }
    }

    /** The page's list is in the drag's order now; nothing more to hold. */
    fun pageCaughtUp(shownIds: List<Long>) {
        if (localOrder != null && drag == null && shownIds == localOrder) localOrder = null
    }

    /** How far the card is drawn from its slot: under the finger while carried, settling after. */
    fun offsetOf(id: Long): Pair<Float, Float> {
        val d = drag
        if (d != null && d.id == id) {
            val slot = slots().firstOrNull { it.id == id } ?: return 0f to 0f
            return (d.pointerX - d.grabX - slot.left) to (d.pointerY - d.grabY - slot.top)
        }
        if (activeId == id) return settleX.value to settle.value
        return 0f to 0f
    }
}

// --- what a page does with the controller ---

/** The page's cards as slots, in page order, from what the lazy layout reports; [idOf] reads a key. */
internal fun cardSlots(items: List<Triple<Any?, IntOffset, IntSize>>, idOf: (Any?) -> Long?): List<CardSlot> =
    items.mapNotNull { (key, offset, size) ->
        idOf(key)?.let { CardSlot(it, offset.x.toFloat(), offset.y.toFloat(), size.width.toFloat(), size.height.toFloat()) }
    }

/** A press (long, or on a handle) picks the card up; the finger carries it; release lets it settle. */
internal fun Modifier.cardDragHandle(
    drag: CardDragController?,
    id: Long,
    enabled: Boolean,
    longPress: Boolean = true,
    /** Where the handle stands inside its card, for a handle smaller than the card (the note list's ＝). */
    origin: () -> Offset = { Offset.Zero },
    shownOrder: () -> List<Long>,
): Modifier = if (drag == null || !enabled) this else pointerInput(id, longPress) {
    if (longPress) {
        detectDragGesturesAfterLongPress(
            onDragStart = { position -> val at = origin() + position; drag.grab(id, at.x, at.y, shownOrder()) },
            onDragEnd = { drag.release() },
            onDragCancel = { drag.release() },
        ) { change, amount ->
            change.consume()
            drag.carry(amount.x, amount.y)
        }
    } else {
        detectDragGestures(
            onDragStart = { position -> val at = origin() + position; drag.grab(id, at.x, at.y, shownOrder()) },
            onDragEnd = { drag.release() },
            onDragCancel = { drag.release() },
        ) { change, amount ->
            change.consume()
            drag.carry(amount.x, amount.y)
        }
    }
}

/** The carried card floats above the page under the finger; one just let go settles. */
internal fun Modifier.carriedCard(drag: CardDragController?, id: Long, liftPx: Float): Modifier =
    if (drag == null || drag.activeId != id) this else this
        .zIndex(1f)
        .graphicsLayer {
            val (dx, dy) = drag.offsetOf(id)
            translationX = dx
            translationY = dy
            val carried = drag.isCarried(id)
            shadowElevation = if (carried) liftPx else 0f
            val scale = if (carried) 1.03f else 1f
            scaleX = scale
            scaleY = scale
            shape = RoundedCornerShape(16.dp)
            clip = false
        }

/** While the finger rests near the top or bottom edge, the page scrolls under it — and only then. */
@Composable
internal fun EdgeScrollWhileCarrying(
    drag: CardDragController?,
    viewport: () -> Pair<Int, Int>,
    scrollBy: suspend (Float) -> Unit,
) {
    if (drag == null) return
    val density = LocalDensity.current
    val edgePx = with(density) { CARD_AUTOSCROLL_EDGE.toPx() }
    val stepPx = with(density) { CARD_AUTOSCROLL_STEP.toPx() }
    val direction by remember(drag) {
        derivedStateOf {
            val y = drag.pointerY ?: return@derivedStateOf 0
            val (start, height) = viewport()
            when {
                y - start < edgePx -> -1
                y - start > height - edgePx -> 1
                else -> 0
            }
        }
    }
    LaunchedEffect(direction) {
        if (direction == 0) return@LaunchedEffect
        while (isActive) {
            scrollBy(stepPx * direction)
            withFrameNanos { }
        }
    }
}

private val CARD_AUTOSCROLL_EDGE = 72.dp
private val CARD_AUTOSCROLL_STEP = 10.dp
internal val CARD_LIFT = 8.dp

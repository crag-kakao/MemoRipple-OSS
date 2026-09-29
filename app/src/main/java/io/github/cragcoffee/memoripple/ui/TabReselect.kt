package io.github.cragcoffee.memoripple.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter

/** The bottom navigation's three places (メモ / カレンダー / チャット). */
enum class TopLevelTab { MEMOS, CALENDAR, CHAT }

/** What a tap on a bottom-navigation item means. */
enum class TabTap {
    /** Another tab: the usual navigation. */
    NAVIGATE,

    /** The tab already shown, tapped again: that tab's own "back to its top" (docs/BOTTOM_NAV_RESELECT.md). */
    RESELECT,
}

/** A tap on [tapped] while [current] is shown (null: a screen that is none of the three). */
fun tabTap(current: TopLevelTab?, tapped: TopLevelTab): TabTap =
    if (current == tapped) TabTap.RESELECT else TabTap.NAVIGATE

/**
 * The bottom navigation's "tapped again" — a one-shot event, never state (docs/BOTTOM_NAV_RESELECT.md):
 * no replay, so a configuration change, a process recreation or a screen composed later never
 * sees an old tap; a tap nobody is listening to is simply gone. Each root screen listens for its
 * own tab and decides what "its top" is — the bar knows no list, no scroll state and no route
 * inside a tab.
 */
class TabReselectSignal {
    private val events = MutableSharedFlow<TopLevelTab>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    fun reselect(tab: TopLevelTab) {
        events.tryEmit(tab)
    }

    fun of(tab: TopLevelTab): Flow<TopLevelTab> = events.filter { it == tab }
}

/** The app's one signal; a screen shown outside the app (a test, a preview) gets one nobody fires. */
val LocalTabReselect = staticCompositionLocalOf { TabReselectSignal() }

/**
 * Runs [action] each time [tab] is tapped again while this is composed. A newer tap cancels the
 * one still running (a scroll still animating starts over), so taps in a row never stack.
 */
@Composable
fun OnTabReselect(tab: TopLevelTab, action: suspend () -> Unit) {
    val signal = LocalTabReselect.current
    val current by rememberUpdatedState(action)
    LaunchedEffect(signal, tab) {
        signal.of(tab).collectLatest { current() }
    }
}

/**
 * How a reselect goes back to the top — one smooth glide over a distance known in pixels, like
 * the platform apps (docs/BOTTOM_NAV_RESELECT.md). `animateScrollToItem` does not know how tall
 * the rows it has not measured are, so over rows of different heights (the calendar's month,
 * day headings and records) it moves in steps and stops at each; instead:
 * - further than one screen (or a page's own [landing]) from the top: jump, at once, to exactly
 *   that far below the top — a page with one heavy item near its top lands just inside it, so the
 *   heavy item is built in the jump's frame, before anything moves;
 * - then glide that exact distance in one ease-out animation, and settle exactly on the top.
 * A list already at its top (or empty) is left alone.
 */
private val RESELECT_GLIDE = tween<Float>(durationMillis = 360, easing = FastOutSlowInEasing)

private suspend fun ScrollableState.glideToTop(
    viewport: Int,
    landing: Int?,
    position: () -> Pair<Int, Int>,
    jumpTo: suspend (index: Int, offset: Int) -> Unit,
) {
    val screen = landing?.takeIf { it in 1 until viewport } ?: viewport
    if (screen <= 0) return jumpTo(0, 0)
    val (index, offset) = position()
    if (index > 0 || offset > screen) jumpTo(0, screen)
    val (after, afterOffset) = position()
    val distance = if (after == 0) afterOffset else screen
    if (distance > 0) animateScrollBy(-distance.toFloat(), RESELECT_GLIDE)
    jumpTo(0, 0)
}

internal suspend fun LazyListState.scrollToTopOnReselect(landing: Int? = null) {
    if (!canScrollBackward) return
    glideToTop(layoutInfo.viewportSize.height, landing, { firstVisibleItemIndex to firstVisibleItemScrollOffset }) { i, o -> scrollToItem(i, o) }
}

internal suspend fun LazyGridState.scrollToTopOnReselect() {
    if (!canScrollBackward) return
    glideToTop(layoutInfo.viewportSize.height, null, { firstVisibleItemIndex to firstVisibleItemScrollOffset }) { i, o -> scrollToItem(i, o) }
}

internal suspend fun LazyStaggeredGridState.scrollToTopOnReselect() {
    if (!canScrollBackward) return
    glideToTop(layoutInfo.viewportSize.height, null, { firstVisibleItemIndex to firstVisibleItemScrollOffset }) { i, o -> scrollToItem(i, o) }
}

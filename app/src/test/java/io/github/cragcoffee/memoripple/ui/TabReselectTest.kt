package io.github.cragcoffee.memoripple.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bottom navigation's reselect (docs/BOTTOM_NAV_RESELECT.md): the tab already shown, tapped
 * again, is a reselect and never a navigation; another tab is the usual navigation; the event is
 * one-shot — delivered to whoever listens now, never replayed to a listener that comes later
 * (a recreated screen, a configuration change), and only to its own tab.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TabReselectTest {
    @Test
    fun theTabShownTappedAgainIsAReselectAndAnyOtherIsANavigation() {
        TopLevelTab.entries.forEach { tab ->
            assertEquals(TabTap.RESELECT, tabTap(tab, tab))
            TopLevelTab.entries.filter { it != tab }.forEach { other -> assertEquals(TabTap.NAVIGATE, tabTap(other, tab)) }
            assertEquals("from a screen that is none of the three", TabTap.NAVIGATE, tabTap(null, tab))
        }
    }

    @Test
    fun aReselectReachesOnlyItsOwnTabAndOnlyThoseListeningNow() = runTest(UnconfinedTestDispatcher()) {
        val signal = TabReselectSignal()
        // A tap before anyone listens is gone — never replayed later.
        signal.reselect(TopLevelTab.MEMOS)
        val memos = mutableListOf<TopLevelTab>()
        val chat = mutableListOf<TopLevelTab>()
        val a = launch { signal.of(TopLevelTab.MEMOS).toList(memos) }
        val b = launch { signal.of(TopLevelTab.CHAT).toList(chat) }
        assertEquals(emptyList<TopLevelTab>(), memos)
        signal.reselect(TopLevelTab.MEMOS)
        signal.reselect(TopLevelTab.CALENDAR)
        assertEquals(listOf(TopLevelTab.MEMOS), memos)
        assertEquals(emptyList<TopLevelTab>(), chat)
        a.cancel()
        // A listener that comes back (a recreated screen) sees nothing of the past.
        val again = mutableListOf<TopLevelTab>()
        val c = launch { signal.of(TopLevelTab.MEMOS).toList(again) }
        assertEquals(emptyList<TopLevelTab>(), again)
        signal.reselect(TopLevelTab.MEMOS)
        assertEquals(listOf(TopLevelTab.MEMOS), again)
        b.cancel()
        c.cancel()
    }

    @Test
    fun tapsInARowNeverBlockTheBar() {
        val signal = TabReselectSignal()
        // Nobody listening, a hundred taps: nothing suspends, nothing piles up.
        repeat(100) { signal.reselect(TopLevelTab.CHAT) }
    }
}

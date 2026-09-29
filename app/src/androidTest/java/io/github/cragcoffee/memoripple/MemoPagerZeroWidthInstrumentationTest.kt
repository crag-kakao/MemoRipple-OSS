package io.github.cragcoffee.memoripple

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.ui.memos.MEMO_VIEW_MODES
import io.github.cragcoffee.memoripple.ui.memos.MemoViewMode
import io.github.cragcoffee.memoripple.ui.memos.skipZeroWidthMeasure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The memo home keeps its page through a zero-width measure (docs/MEMO_PAGER_ZERO_WIDTH.md).
 *
 * A Galaxy S20 (API 33) measures a recreated window once at 0 × 0 before its real size when the
 * system font scale changes. A HorizontalPager measured with no width treats every page but the
 * last as scrolled off and settles on the last one — the home came back on ノート instead of メモ
 * (5 of 5 on that phone, never on the emulator). The phone's pass cannot be made here, so the
 * pager the home uses — its page count, its keys and its modifier — is measured at zero width and
 * back directly: deterministic on any device.
 */
class MemoPagerZeroWidthInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val collapsed = mutableStateOf(false)
    private lateinit var pagerState: PagerState

    /** The home pager's shape: the three pages, their keys, the modifier on the pager itself. */
    private fun showThePager() {
        composeRule.setContent {
            pagerState = rememberPagerState(pageCount = { MEMO_VIEW_MODES.size })
            val side = if (collapsed.value) 0.dp else 300.dp
            Box(Modifier.size(side)) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize().skipZeroWidthMeasure().testTag("pager"),
                    key = { MEMO_VIEW_MODES[it] },
                ) { page ->
                    Box(Modifier.fillMaxSize().testTag("page_${MEMO_VIEW_MODES[page]}"))
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun choose(mode: MemoViewMode) {
        composeRule.runOnIdle { runBlocking { pagerState.scrollToPage(MEMO_VIEW_MODES.indexOf(mode)) } }
        composeRule.waitForIdle()
        assertEquals(mode, MEMO_VIEW_MODES[pagerState.currentPage])
    }

    /** The window goes to nothing and comes back, as the phone's relaunch does. */
    private fun measureAtZeroWidthAndBack() {
        composeRule.runOnIdle { collapsed.value = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle { collapsed.value = false }
        composeRule.waitForIdle()
    }

    private fun assertStillOn(mode: MemoViewMode) {
        assertEquals("the page chosen is the page shown", mode, MEMO_VIEW_MODES[pagerState.currentPage])
        composeRule.onNodeWithTag("page_$mode").assertIsDisplayed()
    }

    @Test
    fun memoStaysMemoThroughAZeroWidthMeasure() {
        showThePager()
        choose(MemoViewMode.MEMO)
        measureAtZeroWidthAndBack()
        assertStillOn(MemoViewMode.MEMO)
    }

    @Test
    fun outlinerStaysOutlinerThroughAZeroWidthMeasure() {
        showThePager()
        choose(MemoViewMode.OUTLINER)
        measureAtZeroWidthAndBack()
        assertStillOn(MemoViewMode.OUTLINER)
    }

    @Test
    fun noteStaysNoteThroughAZeroWidthMeasure() {
        showThePager()
        choose(MemoViewMode.NOTE)
        measureAtZeroWidthAndBack()
        assertStillOn(MemoViewMode.NOTE)
    }

    @Test
    fun atItsWidthThePagerStillSwipesBothWays() {
        showThePager()
        choose(MemoViewMode.MEMO)
        composeRule.onNodeWithTag("pager").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertStillOn(MemoViewMode.OUTLINER)
        composeRule.onNodeWithTag("pager").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertStillOn(MemoViewMode.NOTE)
        composeRule.onNodeWithTag("pager").performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertStillOn(MemoViewMode.OUTLINER)
        // And a swipe after a zero-width pass behaves the same.
        measureAtZeroWidthAndBack()
        composeRule.onNodeWithTag("pager").performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertStillOn(MemoViewMode.MEMO)
    }
}

package io.github.cragcoffee.memoripple

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.ui.memos.MEMO_VIEW_MODES
import io.github.cragcoffee.memoripple.ui.memos.MemoViewModeTabs
import io.github.cragcoffee.memoripple.ui.theme.MemoRippleTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Three words on the rail must fit a phone: the Galaxy S20 is 360dp wide, and the words are
 * メモ / アウトライナー / ノート. They are laid side by side and must neither overlap nor run
 * past the right edge, with nothing to scroll.
 */
class MemoViewModeTabsWidthTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun memoOutlinerAndNoteStandSideBySideInsideAGalaxyS20Width() {
        composeRule.setContent {
            MemoRippleTheme {
                Box(Modifier.width(360.dp).testTag("rail")) {
                    val pagerState = rememberPagerState(pageCount = { MEMO_VIEW_MODES.size })
                    MemoViewModeTabs(pagerState = pagerState, onSelectPage = {})
                }
            }
        }

        val rail = composeRule.onNodeWithTag("rail").getUnclippedBoundsInRoot()
        val memo = composeRule.onNodeWithTag("memo_view_memo").getUnclippedBoundsInRoot()
        val outliner = composeRule.onNodeWithTag("memo_view_outliner").getUnclippedBoundsInRoot()
        val note = composeRule.onNodeWithTag("memo_view_note").getUnclippedBoundsInRoot()

        composeRule.onNodeWithTag("memo_view_outliner").assertIsDisplayed()
        assertTrue("メモ runs into アウトライナー", memo.right <= outliner.left)
        assertTrue("アウトライナー runs into ノート", outliner.right <= note.left)
        assertTrue("ノート ends at ${note.right}, rail at ${rail.right}", note.right <= rail.right)
        assertTrue(memo.top == outliner.top && outliner.top == note.top)
    }
}

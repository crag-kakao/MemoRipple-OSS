package io.github.cragcoffee.memoripple

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.height
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.domain.memos.MemoSortMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 並べた順: in selection mode a long press picks a card up and carries it across its page; on
 * release the order is written and the page turns to 並べた順, which survives a restart. The
 * outliner page has the same, under 「アウトライナーを選択」.
 */
@OptIn(ExperimentalTestApi::class)
class MemoOrderInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MemoRippleApplication

    @Before
    fun startFromAnEmptyWall() {
        runBlocking {
            application.database.clearAllTables()
            application.settingsRepository.resetToDefaults()
            application.settingsRepository.resetPlaybackStyle()
            application.settingsRepository.setAutoPlayOnLaunch(false)
            application.settingsRepository.setMemoSortMode("")
        }
    }

    @After
    fun releaseTheClock() {
        composeRule.mainClock.autoAdvance = true
        runBlocking { application.settingsRepository.setMemoSortMode("") }
    }

    @Test
    fun aCardCarriedOntoAnotherTakesItsPlaceAndThePageTurnsToTheHandsOrder() {
        val older = insert("古い", "本文", MemoKind.MEMO, updatedAt = 100)
        val newer = insert("新しい", "本文", MemoKind.MEMO, updatedAt = 200)
        awaitTag("memo_card_$newer")
        // Newest first: 新しい stands before 古い.
        assertTrue(composeRule.onNodeWithTag("memo_card_$newer").getBoundsInRoot().left <
            composeRule.onNodeWithTag("memo_card_$older").getBoundsInRoot().left)

        composeRule.onNodeWithTag("memo_card_$older").performTouchInput { longClick() }
        awaitTag("memo_select_$older")
        composeRule.onNodeWithTag("memo_select_$older").performClick()
        awaitTag("memo_compact_top_bar")

        // Carry 古い onto 新しい's slot and let go: the finger travels from one centre to the other.
        val from = composeRule.onNodeWithTag("memo_card_$older").getBoundsInRoot()
        val to = composeRule.onNodeWithTag("memo_card_$newer").getBoundsInRoot()
        val travel = with(composeRule.density) {
            Offset(((to.left + to.right) - (from.left + from.right)).toPx() / 2, ((to.top + to.bottom) - (from.top + from.bottom)).toPx() / 2)
        }
        composeRule.onNodeWithTag("memo_card_$older").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(center.x, center.y - 10f), delayMillis = 50)
            moveTo(center + travel, delayMillis = 200)
            up()
        }

        // Every card starts at sortIndex 0, so the write is seen by 新しい moving to 1 — waiting
        // for 古い at 0 would be satisfied before anything was written.
        composeRule.waitUntil(5_000) {
            runBlocking { application.database.memoDao().findById(newer)?.sortIndex } == 1
        }
        assertEquals(0, runBlocking { application.database.memoDao().findById(older)?.sortIndex })
        composeRule.waitUntil(5_000) {
            runBlocking { application.settingsRepository.memoSortMode.first() } == MemoSortMode.MANUAL.name
        }
        composeRule.onNodeWithText("手動で並べた順に切り替えました").assertIsDisplayed()
        // 並べた順 is how the wall is arranged, not a narrowing: no strip, no クリア.
        composeRule.onAllNodesWithTag("memo_active_filters").assertCountEquals(0)
        composeRule.waitUntil(5_000) {
            composeRule.onNodeWithTag("memo_card_$older").getBoundsInRoot().left <
                composeRule.onNodeWithTag("memo_card_$newer").getBoundsInRoot().left
        }
    }

    @Test
    fun anOutlineIsSelectedFromItsSheetAndCarriedOnItsOwnPage() {
        val older = insert("古い計画", "- 一", MemoKind.OUTLINE, updatedAt = 100)
        val newer = insert("新しい計画", "- 二", MemoKind.OUTLINE, updatedAt = 200)
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$older")

        composeRule.onNodeWithTag("outline_card_$older").performTouchInput { longClick() }
        awaitTag("memo_select_$older")
        composeRule.onNodeWithText("アウトライナーを選択").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_select_$older").performClick()
        awaitTag("memo_compact_top_bar")
        composeRule.onNodeWithTag("outline_card_$older").assertIsSelected()

        val rowHeight = composeRule.onNodeWithTag("outline_card_$older").getBoundsInRoot().height
        val dragPx = with(composeRule.density) { rowHeight.toPx() }
        composeRule.onNodeWithTag("outline_card_$older").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(center.x, center.y - dragPx), delayMillis = 200)
            up()
        }

        // As in the wall test above: every card starts at 0, so the write is seen by 新しい moving
        // to 1 — waiting for 古い at 0 was satisfied before anything was written (one failure of the
        // 4th full run of 2026-09-16).
        composeRule.waitUntil(5_000) {
            runBlocking { application.database.memoDao().findById(newer)?.sortIndex } == 1
        }
        assertEquals(0, runBlocking { application.database.memoDao().findById(older)?.sortIndex })
    }

    private fun insert(title: String, body: String, kind: MemoKind, updatedAt: Long): Long = runBlocking {
        application.database.memoDao().insert(
            MemoEntity(title = title, body = body, createdAt = updatedAt, updatedAt = updatedAt, kind = kind.storageId),
        )
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

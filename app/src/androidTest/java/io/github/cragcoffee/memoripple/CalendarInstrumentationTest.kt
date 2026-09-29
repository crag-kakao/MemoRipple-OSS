package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * カレンダー: one time axis over memos, outlines and journal entries — a view, never a copy.
 * A day lists what was created or written that day; each row opens its document the way the
 * rest of the app does (the memo navigator for memos and outlines, `journal/{id}` for a journal).
 */
class CalendarInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MemoRippleApplication

    private fun at(date: LocalDate, hour: Int, minute: Int): Long =
        application.timeProvider.toEpochMillis(LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)))

    @Before
    fun startEmpty() {
        runBlocking {
            application.database.clearAllTables()
            application.settingsRepository.setAutoPlayOnLaunch(false)
        }
    }

    @Test
    fun todayListsWhatWasMadeOrWrittenAndEachRowOpensItsDocument() {
        val today = application.timeProvider.currentLocalDate()
        val yesterday = today.minusDays(1)
        val lastMonth = today.minusMonths(1).withDayOfMonth(15)
        val (memoId, outlineId, journalA, journalB, oldMemo) = runBlocking {
            val memos = application.database.memoDao()
            val diary = application.database.diaryDao()
            listOf(
                memos.insert(MemoEntity(title = "MemoRipple AI案", body = "本文", createdAt = at(today, 9, 10), updatedAt = at(today, 9, 10))),
                memos.insert(MemoEntity(title = "Calendar再設計", body = "- 一\n- 二", createdAt = at(yesterday, 8, 0), updatedAt = at(today, 11, 40), kind = "outline")),
                diary.insert(DiaryEntryEntity(diaryDateEpochDay = today.toEpochDay(), body = "朝の記録", state = DiaryState.DRAFT, createdAt = at(today, 7, 30), updatedAt = at(today, 7, 30))),
                diary.insert(DiaryEntryEntity(diaryDateEpochDay = today.toEpochDay(), body = "今日の振り返り", state = DiaryState.DRAFT, createdAt = at(today, 18, 0), updatedAt = at(today, 18, 0))),
                memos.insert(MemoEntity(title = "先月のメモ", body = "古い", createdAt = at(lastMonth, 10, 0), updatedAt = at(lastMonth, 10, 0))),
            )
        }

        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitNode("timeline_list")

        // Today is selected; the four things that touched today are there, in time order, once each.
        awaitNode("timeline_item_journal_$journalA")
        composeRule.onNodeWithTag("timeline_item_memo_$memoId").assertIsDisplayed()
        composeRule.onNodeWithTag("timeline_item_outline_$outlineId").assertTextContains("更新", substring = true)
        composeRule.onNodeWithTag("timeline_list").performScrollToNode(hasTestTag("timeline_item_journal_$journalB"))
        composeRule.onNodeWithTag("timeline_item_journal_$journalB").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("timeline_item_memo_$oldMemo").fetchSemanticsNodes().isEmpty())

        // 作成 hides the outline (born yesterday); 更新 shows only it.
        composeRule.onNodeWithTag("timeline_filter_CREATED").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("timeline_item_outline_$outlineId").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("timeline_item_memo_$memoId").assertIsDisplayed()
        composeRule.onNodeWithTag("timeline_filter_UPDATED").performClick()
        awaitNode("timeline_item_outline_$outlineId")
        assertTrue(composeRule.onAllNodesWithTag("timeline_item_memo_$memoId").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("timeline_filter_ALL").performClick()
        awaitNode("timeline_item_memo_$memoId")

        // Routing: a memo opens the way the wall opens it, an outline in the outliner, a journal by id.
        composeRule.onNodeWithTag("timeline_item_memo_$memoId").performClick()
        awaitNode("memo_reading_view")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitNode("timeline_item_outline_$outlineId")
        composeRule.onNodeWithTag("timeline_item_outline_$outlineId").performClick()
        awaitNode("outliner_screen")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitNode("timeline_list")
        composeRule.onNodeWithTag("timeline_list").performScrollToNode(hasTestTag("timeline_item_journal_$journalB"))
        composeRule.onNodeWithTag("timeline_item_journal_$journalB").performClick()
        awaitNode("diary_body")
        composeRule.onNodeWithTag("diary_body").assertTextContains("今日の振り返り")
        composeRule.onNodeWithContentDescription("戻る").performClick()

        // The previous month holds the old memo on its own day (the month header is at the top of
        // a lazy list that came back scrolled).
        awaitNode("timeline_list")
        composeRule.onNodeWithTag("timeline_list").performScrollToNode(hasTestTag("timeline_previous_month"))
        composeRule.onNodeWithTag("timeline_previous_month").performClick()
        awaitNode("timeline_day_${lastMonth.toEpochDay()}")
        composeRule.onNodeWithTag("timeline_day_${lastMonth.toEpochDay()}").performClick()
        awaitNode("timeline_item_memo_$oldMemo")
    }

    @Test
    fun anEmptyMonthSaysSoAndTheDiaryPageStillWorks() {
        composeRule.onNodeWithTag("nav_calendar").performClick()
        // 「すべて」 is the month (2026-09-23), so an empty calendar says so about the month
        awaitNode("timeline_month_empty")
        // The old diary page is one tap away as 日記一覧 and comes back to the tab.
        composeRule.onNodeWithTag("open_journal_list").performClick()
        awaitNode("calendar_create_today")
        assertTrue(composeRule.onAllNodesWithTag("nav_calendar").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitNode("timeline_month_empty")
        composeRule.onNodeWithTag("nav_calendar").assertIsSelected()
    }

    private fun awaitNode(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

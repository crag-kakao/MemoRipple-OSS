package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.data.FutureDiaryCommentEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * カレンダー as a main tab (docs/CALENDAR_TAB_MIGRATION.md): the bottom bar is メモ / カレンダー,
 * and the Calendar holds every job the 日記 tab used to do — explicit journal creation for the
 * selected day, 過去の今日, the delivered-future-comment banner — while the old diary page stays
 * reachable as 日記一覧. Nothing is created by navigation alone.
 */
class CalendarTabInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MemoRippleApplication

    private val today: LocalDate get() = application.timeProvider.currentLocalDate()

    @Before
    fun startEmpty() {
        runBlocking {
            application.database.clearAllTables()
            application.settingsRepository.setAutoPlayOnLaunch(false)
        }
    }

    @Test
    fun theBarIsMemoAndCalendarAndTheCalendarOpensAsATabWithTheBarStillThere() {
        composeRule.onNodeWithTag("nav_memos").assertIsSelected()
        composeRule.onNodeWithTag("nav_calendar").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("nav_diary").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitNode("timeline_list")
        composeRule.onNodeWithTag("calendar_compact_top_bar").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_calendar").assertIsSelected()
        composeRule.onNodeWithTag("nav_memos").assertIsDisplayed()
        composeRule.onNodeWithTag("timeline_day_${today.toEpochDay()}").assertIsSelected()
        // No 戻る on a tab: the bar is the way out.
        assertTrue(composeRule.onAllNodesWithContentDescription("戻る").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theSelectedDayWritesAJournalExplicitlyAndSeveralADayEachBecomeARow() {
        composeRule.onNodeWithTag("nav_calendar").performClick()
        // 「すべて」 is the month (2026-09-23): an empty month, not an empty day
        awaitNode("timeline_month_empty")

        // Looking creates nothing; the button does, and each press makes a new entry by id.
        assertEquals(0, entriesToday())
        composeRule.onNodeWithTag("timeline_create_journal").assertTextContains("日記を書く", substring = true).performClick()
        awaitNode("diary_body")
        composeRule.onNodeWithTag("diary_body").performTextInput("一本目")
        composeRule.waitUntil(timeoutMillis = 5_000) { entriesToday() == 1 }
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitNode("timeline_create_journal")
        composeRule.onNodeWithTag("timeline_create_journal").performClick()
        awaitNode("diary_body")
        composeRule.onNodeWithTag("diary_body").performTextInput("二本目")
        composeRule.waitUntil(timeoutMillis = 5_000) { entriesToday() == 2 }
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithContentDescription("戻る").performClick()

        val ids = runBlocking { application.database.diaryDao().entriesForDate(today.toEpochDay()).map { it.id } }
        ids.forEach { awaitNode("timeline_item_journal_${it}") }
        assertEquals(2, ids.toSet().size)
        composeRule.onNodeWithTag("nav_calendar").assertIsSelected()
    }

    @Test
    fun aPastDayCanStillGetAJournalAndAFutureDayCannotBeChosen() {
        val yesterday = today.minusDays(1)
        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitNode("timeline_list")
        if (yesterday.month != today.month) {
            composeRule.onNodeWithTag("timeline_previous_month").performClick()
        }
        awaitNode("timeline_day_${yesterday.toEpochDay()}")
        composeRule.onNodeWithTag("timeline_day_${yesterday.toEpochDay()}").performClick()
        awaitNode("timeline_create_journal")
        composeRule.onNodeWithTag("timeline_create_journal").performClick()
        awaitNode("diary_body")
        composeRule.onNodeWithTag("diary_body").performTextInput("昨日のこと")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { application.database.diaryDao().entriesForDate(yesterday.toEpochDay()).size } == 1
        }
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithContentDescription("戻る").performClick()
        val id = runBlocking { application.database.diaryDao().entriesForDate(yesterday.toEpochDay()).single().id }
        // the month list holds it on its diary day, and again on the day it was written (更新), as the grid's dots do
        awaitNode("timeline_item_journal_${id}")
        composeRule.onNodeWithTag("timeline_item_journal_${id}").assertTextContains("昨日のこと", substring = true)
    }

    @Test
    fun pastTodayAndTheDeliveredBannerLiveOnTheCalendarTab() {
        val pastToday = LocalDate.of(today.year - 1, today.month, today.dayOfMonth)
        val (pastId, todayId) = runBlocking {
            val diary = application.database.diaryDao()
            val pastId = diary.insert(
                DiaryEntryEntity(diaryDateEpochDay = pastToday.toEpochDay(), body = "去年の同じ日", state = DiaryState.LOCKED, createdAt = 1, updatedAt = 1, lockedAt = 1),
            )
            val todayId = diary.insert(
                DiaryEntryEntity(diaryDateEpochDay = today.toEpochDay(), body = "未来コメントを受け取る日記", state = DiaryState.DRAFT, createdAt = 2, updatedAt = 2),
            )
            val now = application.timeProvider.nowMillis()
            application.database.futureDiaryCommentDao().insert(
                FutureDiaryCommentEntity(diaryEntryId = todayId, text = "未来から", sealedAt = now - 2_000, revealAt = now - 1_000, deliveredAt = now),
            )
            pastId to todayId
        }

        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitNode("future_delivery_banner")
        // The banner is the first thing on the tab; 過去の今日 follows the day's rows.
        composeRule.onNodeWithTag("future_delivery_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("timeline_item_journal_${todayId}").assertIsDisplayed()
        composeRule.onNodeWithTag("timeline_list").performScrollToNode(hasTestTag("past_today_${pastToday.toEpochDay()}"))
        composeRule.onNodeWithText("過去の今日").assertIsDisplayed()
        composeRule.onNodeWithTag("past_today_open_${pastToday.toEpochDay()}").performClick()
        awaitNode("diary_body")
        composeRule.onNodeWithTag("diary_body").assertTextContains("去年の同じ日")
        assertTrue(pastId > 0)
        composeRule.onNodeWithContentDescription("戻る").performClick()

        awaitNode("timeline_list")
        composeRule.onNodeWithTag("timeline_list").performScrollToNode(hasTestTag("receive_future_comment"))
        composeRule.onNodeWithTag("receive_future_comment").performClick()
        awaitNode("future_comment_stage")
    }

    @Test
    fun theOldDiaryPageIsReachableAsAListAndComesBackToTheTab() {
        val yesterday = today.minusDays(1)
        val id = runBlocking {
            application.database.diaryDao().insert(
                DiaryEntryEntity(diaryDateEpochDay = yesterday.toEpochDay(), body = "昨日の日記", state = DiaryState.DRAFT, createdAt = 1, updatedAt = 1),
            )
        }
        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitNode("open_journal_list")
        composeRule.onNodeWithTag("open_journal_list").performClick()
        awaitNode("diary_list")
        // A secondary screen now: no bar, a 戻る, and the list still lists past journals.
        assertTrue(composeRule.onAllNodesWithTag("nav_calendar").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("diary_list").performScrollToNode(hasTestTag("diary_card_$id"))
        composeRule.onNodeWithTag("diary_card_$id").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitNode("timeline_list")
        composeRule.onNodeWithTag("nav_calendar").assertIsSelected()
    }

    @Test
    fun backFromTheCalendarTabLandsOnMemoAndRecreationKeepsTheTab() {
        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitNode("timeline_list")
        composeRule.activityRule.scenario.recreate()
        awaitNode("timeline_list")
        composeRule.onNodeWithTag("nav_calendar").assertIsSelected()
        composeRule.onNodeWithTag("timeline_day_${today.toEpochDay()}").assertIsSelected()

        Espresso.pressBack()
        awaitNode("create_memo")
        composeRule.onNodeWithTag("nav_memos").assertIsSelected()
    }

    private fun entriesToday(): Int =
        runBlocking { application.database.diaryDao().entriesForDate(today.toEpochDay()).size }

    private fun awaitNode(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

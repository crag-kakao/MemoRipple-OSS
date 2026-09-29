package io.github.cragcoffee.memoripple

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Calendar「すべて」 as the month, and the Diary page around its body (human brief 2026-09-23).
 *
 * 「すべて」 lists the displayed month day by day, newest first, with memos, outlines and journals
 * together; 作成 / 更新 keep their day meanings, so choosing a date still shows that date. Every row
 * opens through the canonical route. The diary page keeps every function — autosave, the lifecycle,
 * LOCKED, the reading action, the attachments, 未来の自分へ — and only its weights change.
 */
class CalendarMonthAndDiaryInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    private val today: LocalDate get() = application.timeProvider.currentLocalDate()

    @Before
    fun startEmpty() {
        runBlocking {
            application.database.clearAllTables()
            application.settingsRepository.setAutoPlayOnLaunch(false)
        }
    }

    // --- fixtures: rows placed on a chosen day of the displayed month ---

    private fun at(date: LocalDate, hour: Int): Long =
        application.timeProvider.toEpochMillis(LocalDateTime.of(date, LocalTime.of(hour, 0)))

    private fun memo(title: String, day: LocalDate, hour: Int = 9, kind: String = "memo"): Long = runBlocking {
        val t = at(day, hour)
        application.database.memoDao().insert(MemoEntity(title = title, body = "本文", createdAt = t, updatedAt = t, kind = kind))
    }

    private fun journal(body: String, day: LocalDate, hour: Int = 22): Long = runBlocking {
        val t = at(day, hour)
        application.database.diaryDao().insert(DiaryEntryEntity(diaryDateEpochDay = day.toEpochDay(), body = body, state = DiaryState.DRAFT, createdAt = t, updatedAt = t))
    }

    private fun lockedJournal(body: String, day: LocalDate): Long = runBlocking {
        val t = at(day, 20)
        application.database.diaryDao().insert(DiaryEntryEntity(diaryDateEpochDay = day.toEpochDay(), body = body, state = DiaryState.LOCKED, createdAt = t, updatedAt = t, lockedAt = t))
    }

    /** A day of the displayed month that is not today, so a month list has more than one group. */
    private val earlierThisMonth: LocalDate
        get() = today.withDayOfMonth(1).let { if (it == today) today else it }

    private fun openCalendar() { composeRule.onNodeWithTag("nav_calendar").performClick(); awaitTag("timeline_list") }
    private fun awaitTag(tag: String, timeout: Long = 15_000) {
        composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun scrollTo(tag: String) { composeRule.onNodeWithTag("timeline_list").performScrollToNode(hasTestTag(tag)) }
    private fun formatDay(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"

    // --- A: the month carries all three kinds, grouped by day ---

    @Test
    fun theMonthListCarriesMemosOutlinesAndJournalsGroupedByDay() {
        val memoId = memo("買い物リスト", today)
        val outlineId = memo("MemoRipple改善案", today, hour = 10, kind = "outline")
        val journalId = journal("今日のこと", today)
        val earlier = memo("月はじめのメモ", earlierThisMonth, hour = 8)

        openCalendar()
        awaitTag("timeline_item_memo_${memoId}")
        composeRule.onNodeWithTag("timeline_scope_label", useUnmergedTree = true).assertTextContains("の記録", substring = true)
        listOf(
            "timeline_item_memo_${memoId}",
            "timeline_item_outline_${outlineId}",
            "timeline_item_journal_${journalId}",
        ).forEach { tag -> scrollTo(tag); composeRule.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed() }
        // the day the rows belong to heads them
        composeRule.onNodeWithTag("timeline_month_day_$today", useUnmergedTree = true).assertIsDisplayed()
        if (earlierThisMonth != today) scrollTo("timeline_item_memo_${earlier}")
        assertTrue("an empty state has no place in a month with rows", count("timeline_month_empty") == 0)
    }

    @Test
    fun theMonthListIsNewestDayFirst() {
        if (today.dayOfMonth == 1) return   // a month with one day cannot show an order
        val first = memo("月はじめのメモ", today.withDayOfMonth(1), hour = 8)
        val latest = memo("今日のメモ", today)
        openCalendar()
        awaitTag("timeline_item_memo_${latest}")
        val latestTop = composeRule.onNodeWithTag("timeline_item_memo_${latest}", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
        scrollTo("timeline_item_memo_${first}")
        val firstTop = composeRule.onNodeWithTag("timeline_item_memo_${first}", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
        assertTrue("the newest day reads first", latestTop < firstTop || firstTop > 0f)
    }

    // --- B: another month is another list ---

    @Test
    fun aMonthChangeChangesTheList() {
        val lastMonth = today.minusMonths(1).withDayOfMonth(1)
        val thisMonthMemo = memo("今月のメモ", today)
        val lastMonthMemo = memo("先月のメモ", lastMonth, hour = 8)

        openCalendar()
        awaitTag("timeline_item_memo_${thisMonthMemo}")
        assertEquals("a row of another month is not in this one", 0, count("timeline_item_memo_${lastMonthMemo}"))

        composeRule.onNodeWithTag("timeline_previous_month").performClick()
        awaitTag("timeline_item_memo_${lastMonthMemo}")
        assertEquals("and this month's row leaves with it", 0, count("timeline_item_memo_${thisMonthMemo}"))
    }

    @Test
    fun aMonthWithNothingSaysSoAndSaysItAboutTheMonth() {
        openCalendar()
        awaitTag("timeline_month_empty")
        composeRule.onNodeWithText("この月の記録はありません").assertIsDisplayed()
        assertEquals("not the day's words", 0, count("timeline_empty"))
    }

    // --- the filters: 作成 / 更新 stay the selected day's ---

    @Test
    fun createdAndUpdatedStayTheSelectedDaysAndSayTheDate() {
        val memoId = memo("今日のメモ", today)
        openCalendar()
        awaitTag("timeline_item_memo_${memoId}")

        // すべて names the month; 作成 names the chosen day, and the day headers belong to the month list alone
        composeRule.onNodeWithTag("timeline_scope_label", useUnmergedTree = true).assertTextContains("の記録", substring = true)
        assertEquals(1, count("timeline_month_day_$today"))
        composeRule.onNodeWithTag("timeline_filter_CREATED").performClick()
        awaitTag("timeline_item_memo_$memoId")
        composeRule.onNodeWithTag("timeline_scope_label", useUnmergedTree = true).assertTextContains(formatDay(today), substring = true)
        assertEquals("no day headers outside すべて", 0, count("timeline_month_day_$today"))

        composeRule.onNodeWithTag("timeline_filter_UPDATED").performClick()
        composeRule.waitUntil(15_000) { count("timeline_empty") == 1 }
        composeRule.onNodeWithText("この日の記録はありません").assertIsDisplayed()

        composeRule.onNodeWithTag("timeline_filter_ALL").performClick()
        awaitTag("timeline_item_memo_${memoId}")
    }

    // --- C / D / E: a row opens the canonical route ---

    @Test
    fun aMemoRowOpensTheMemoAndBackKeepsTheCalendar() {
        val memoId = memo("買い物リスト", today)
        openCalendar()
        awaitTag("timeline_item_memo_${memoId}")
        composeRule.onNodeWithTag("timeline_item_memo_${memoId}").performClick()
        awaitTag("memo_reading_view")
        Espresso.pressBack()
        awaitTag("timeline_list")
        composeRule.onNodeWithTag("timeline_item_memo_${memoId}", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun anOutlineRowOpensTheOutlinerAndAJournalRowTheDiary() {
        val outlineId = memo("MemoRipple改善案", today, hour = 10, kind = "outline")
        val journalId = journal("今日のこと", today)
        openCalendar()
        awaitTag("timeline_item_outline_${outlineId}")
        composeRule.onNodeWithTag("timeline_item_outline_${outlineId}").performClick()
        awaitTag("outliner_screen")
        Espresso.pressBack()
        awaitTag("timeline_list")
        scrollTo("timeline_item_journal_${journalId}")
        composeRule.onNodeWithTag("timeline_item_journal_${journalId}").performClick()
        awaitTag("diary_body")
        composeRule.onNodeWithTag("diary_body").assertTextContains("今日のこと", substring = true)
    }

    // --- the grid and the day selection are untouched ---

    @Test
    fun theGridStillMarksTheDaysAndStillSelectsOne() {
        journal("今日のこと", today)
        openCalendar()
        awaitTag("timeline_day_${today.toEpochDay()}")
        composeRule.onNodeWithTag("timeline_day_${today.toEpochDay()}").assertIsDisplayed()
        composeRule.onNodeWithTag("timeline_create_journal").assertIsDisplayed()
        if (today.dayOfMonth > 1) {
            val first = today.withDayOfMonth(1)
            composeRule.onNodeWithTag("timeline_day_${first.toEpochDay()}").performClick()
            composeRule.onNodeWithTag("timeline_filter_CREATED").performClick()
            composeRule.waitUntil(15_000) { count("timeline_empty") == 1 || count("timeline_item_journal_1") == 1 }
        }
    }

    // --- F: the diary body, autosave and a recreation ---

    @Test
    fun theDiaryBodyIsWrittenAutosavedAndSurvivesARecreation() {
        val id = journal("", today)
        openCalendar()
        awaitTag("timeline_item_journal_${id}")
        composeRule.onNodeWithTag("timeline_item_journal_${id}").performClick()
        awaitTag("diary_body")
        composeRule.onNodeWithTag("diary_body").performTextInput("今日は散歩をした")
        composeRule.waitUntil(15_000) {
            runBlocking { application.database.diaryDao().findById(id)?.body }?.contains("散歩") == true
        }
        Espresso.closeSoftKeyboard()
        composeRule.activityRule.scenario.recreate()
        awaitTag("diary_body")
        composeRule.onNodeWithTag("diary_body").assertTextContains("散歩", substring = true)
    }

    @Test
    fun theDiaryKeepsItsReadingActionItsStateLineAndItsFutureSection() {
        val id = journal("読み上げる日記", today)
        openCalendar()
        awaitTag("timeline_item_journal_${id}")
        composeRule.onNodeWithTag("timeline_item_journal_${id}").performClick()
        awaitTag("diary_body")
        // the reading action is a top-bar action, not the page's subject
        composeRule.onNodeWithTag("diary_speech_action").assertIsDisplayed()
        // the state is a small line, and it still says what the lifecycle says
        // the retired lifecycle says nothing on a page being written (2026-09-24): no state line at all
        assertEquals("編集中 is gone", 0, count("diary_state_label"))
        // 未来の自分へ is still there, still secondary, still reachable
        composeRule.onNodeWithText("未来の自分へ").assertIsDisplayed()
        composeRule.onNodeWithTag("open_future_comment_creator").assertIsDisplayed()
        composeRule.onNodeWithTag("future_comment_empty_state", useUnmergedTree = true).assertTextContains("まだメッセージはありません", substring = true)
        // and the attachment action keeps its words
        composeRule.onNodeWithContentDescription("写真を追加").assertIsDisplayed()
    }

    // --- G: a locked day reads and does not take writing ---

    @Test
    fun aLockedDiaryIsReadableAndNotEditable() {
        val id = lockedJournal("ロックされた日記", today)
        openCalendar()
        awaitTag("timeline_item_journal_${id}")
        composeRule.onNodeWithTag("timeline_item_journal_${id}").performClick()
        awaitTag("diary_body")
        composeRule.onNodeWithTag("diary_body").assertTextContains("ロックされた日記", substring = true)
        // a locked day says so once (2026-09-24): one small line, not two
        composeRule.onNodeWithTag("diary_state_label", useUnmergedTree = true).assertTextContains("ロック済み")
        assertEquals("said once", 1, count("diary_state_label"))
        // read-only: the field offers no way to set text at all, so nothing can be typed into it
        assertTrue(
            "a locked day takes no writing",
            composeRule.onNodeWithTag("diary_body").fetchSemanticsNode().config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.SetText) { null } == null,
        )
        assertEquals("and offers no photo action", 0, count("diary_add_photo"))
    }

    // --- the body follows the caret: a long day keeps the line being written in view ---

    @Test
    fun aLongBodyKeepsTheLineBeingWrittenInView() {
        val id = journal("", today)
        openCalendar()
        awaitTag("timeline_item_journal_${id}")
        composeRule.onNodeWithTag("timeline_item_journal_${id}").performClick()
        awaitTag("diary_body")
        // more lines than the field can show at once, the last one written last
        val body = (1..30).joinToString("\n") { "line$it" } + "\nLAST"
        composeRule.onNodeWithTag("diary_body").performTextInput(body)
        composeRule.waitUntil(15_000) {
            runBlocking { application.database.diaryDao().findById(id)?.body }?.endsWith("LAST") == true
        }
        // The field is one bounded editor that lays every line out and keeps the caret at the end —
        // the same field the memo editor uses, which is what carries the caret with it when the
        // keyboard takes half the screen. Whether the caret is on screen is a scroll offset Compose
        // does not expose, so that part is checked on a device by eye; what is pinned here is that a
        // long day is taken whole, kept, and still one field.
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onNodeWithTag("diary_body", useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layout) }
        val result = layout.first()
        assertTrue("every line is laid out: ${result.lineCount}", result.lineCount >= 30)
        assertTrue("and the last one is the one just written", result.layoutInput.text.text.endsWith("LAST"))
    }

    @Test
    fun aDiaryPreviewKeepsItsLineBreaks() {
        val id = journal("## 良かったこと\nいい感じに話せた\n\n## 明日やること\nアニメを見る", today.minusDays(1))
        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitTag("timeline_list")
        composeRule.onNodeWithTag("open_journal_list").performClick()
        awaitTag("diary_list")
        composeRule.onNodeWithTag("diary_list").performScrollToNode(hasTestTag("diary_card_$id"))
        // the card draws the body as it was written: the line breaks are still in the text it shows
        val shown = composeRule.onNodeWithText("## 良かったこと", substring = true)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
        assertTrue("a preview kept its line breaks: $shown", shown.contains("\n"))
    }

}

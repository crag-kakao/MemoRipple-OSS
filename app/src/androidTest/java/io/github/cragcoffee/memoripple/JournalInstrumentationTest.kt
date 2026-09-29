package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Journal entries (HANDOFF §16.18): several a day, each with its own id and route, written like
 * a memo. The date groups them on the shelf and in the day's list; nothing locks by itself,
 * and leaving an entry unchanged leaves its `updatedAt` alone.
 */
class JournalInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MemoRippleApplication

    @Before
    fun startEmpty() {
        runBlocking {
            application.database.clearAllTables()
            application.settingsRepository.setAutoPlayOnLaunch(false)
        }
    }

    @Test
    fun aDayHoldsSeveralEntriesEachOpenedByItsIdAndTheDayListMakesAnother() {
        val today = application.timeProvider.currentLocalDate().toEpochDay()
        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitNode("timeline_create_journal")
        composeRule.onNodeWithTag("timeline_create_journal").performClick()
        awaitNode("diary_body")
        composeRule.onNodeWithTag("diary_body").performTextInput("朝の記録")
        composeRule.onNodeWithContentDescription("戻る").performClick()

        // The tab offers another for today; the second entry is its own document and its own row.
        awaitNode("timeline_create_journal")
        composeRule.onNodeWithTag("timeline_create_journal").performClick()
        awaitNode("diary_body")
        composeRule.onNodeWithTag("diary_body").performTextInput("夜の振り返り")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        val (morning, evening) = runBlocking {
            application.database.diaryDao().entriesForDate(today).map { it.id }
        }
        awaitNode("timeline_item_journal_$morning")
        composeRule.onNodeWithTag("timeline_item_journal_$evening").assertExists()

        // The day's list still lives on 日記一覧: each row opens its entry; the button at the end
        // makes a third.
        composeRule.onNodeWithTag("open_journal_list").performClick()
        awaitNode("calendar_open_day")
        composeRule.onNodeWithText("2件の日記").assertIsDisplayed()
        composeRule.onNodeWithTag("calendar_open_day").performClick()
        awaitNode("journal_entry_$morning")
        composeRule.onNodeWithTag("journal_entry_$evening").assertIsDisplayed()
        composeRule.onNodeWithTag("journal_entry_$morning").performClick()
        awaitNode("diary_body")
        composeRule.onNodeWithTag("diary_body").assertTextContains("朝の記録")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitNode("journal_day_create")
        composeRule.onNodeWithTag("journal_day_create").performScrollTo().performClick()
        awaitNode("diary_body")
        composeRule.onNodeWithTag("diary_body").performTextInput("三本目")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { application.database.diaryDao().entriesForDate(today).size } == 3
        }
    }

    @Test
    fun leavingAnEntryUnchangedDoesNotMoveItsUpdatedAt() {
        val today = application.timeProvider.currentLocalDate().toEpochDay()
        val id = runBlocking {
            application.database.diaryDao().insert(
                DiaryEntryEntity(diaryDateEpochDay = today, body = "そのまま", state = DiaryState.FINALIZED, createdAt = 1, updatedAt = 1, finalizedAt = 1),
            )
        }
        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitNode("timeline_item_journal_$id")
        composeRule.onNodeWithTag("timeline_item_journal_$id").performClick()
        awaitNode("diary_body")
        composeRule.onNodeWithTag("diary_body").assertTextContains("そのまま")
        // FINALIZED is an ordinary editable entry now: no lock notice, no lifecycle button.
        assertTrue(composeRule.onAllNodesWithTag("diary_state_label").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitNode("timeline_item_journal_$id")
        val stored = runBlocking { requireNotNull(application.database.diaryDao().findById(id)) }
        assertEquals(1L, stored.updatedAt)
        assertEquals(DiaryState.FINALIZED, stored.state)
    }

    @Test
    fun yesterdaysEntryStaysEditableAndALockedOneStaysReadOnly() {
        val today = application.timeProvider.currentLocalDate().toEpochDay()
        val (yesterday, locked) = runBlocking {
            val dao = application.database.diaryDao()
            dao.insert(DiaryEntryEntity(diaryDateEpochDay = today - 1, body = "昨日の下書き", state = DiaryState.DRAFT, createdAt = 1, updatedAt = 1)) to
                dao.insert(DiaryEntryEntity(diaryDateEpochDay = today - 2, body = "古いロック", state = DiaryState.LOCKED, createdAt = 2, updatedAt = 2, lockedAt = 2))
        }
        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitNode("open_journal_list")
        composeRule.onNodeWithTag("open_journal_list").performClick()
        awaitNode("diary_list")
        scrollShelfTo("diary_card_$yesterday")
        composeRule.onNodeWithTag("diary_card_$yesterday").performClick()
        awaitNode("diary_body")
        assertTrue(composeRule.onAllNodesWithTag("diary_state_label").fetchSemanticsNodes().isEmpty())
        // The caret lands wherever the field puts it; what matters is that yesterday's entry took the words.
        composeRule.onNodeWithTag("diary_body").performTextInput("今日書き足す")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val body = runBlocking { application.database.diaryDao().findById(yesterday)?.body }.orEmpty()
            body.contains("昨日の下書き") && body.contains("今日書き足す")
        }
        composeRule.onNodeWithContentDescription("戻る").performClick()

        awaitNode("diary_list")
        scrollShelfTo("diary_card_$locked")
        composeRule.onNodeWithTag("diary_card_$locked").performClick()
        awaitNode("diary_state_label")
        composeRule.onNodeWithTag("diary_body").assertTextContains("古いロック")
    }

    private fun scrollShelfTo(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("diary_list").performScrollToNode(androidx.compose.ui.test.hasTestTag(tag))
            }.isSuccess
        }
        awaitNode(tag)
    }

    private fun awaitNode(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

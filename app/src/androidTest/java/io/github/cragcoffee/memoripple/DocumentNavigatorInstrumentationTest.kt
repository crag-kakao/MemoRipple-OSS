package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * One navigator for every document: a memo opens in the editor, an outline in the outliner, a
 * journal by id — from the calendar's rows (a DocumentRef each) and from the diary list (the
 * journal route used to be spelled there). The routes are the navigator's business alone.
 */
class DocumentNavigatorInstrumentationTest {
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
    fun eachKindOpensOnItsOwnScreenFromTheCalendarAndAJournalFromTheDiaryList() {
        val now = application.timeProvider.nowMillis()
        val today = application.timeProvider.currentLocalDate()
        val (memoId, outlineId, journalId) = runBlocking {
            val memos = application.database.memoDao()
            Triple(
                memos.insert(MemoEntity(title = "境界のメモ", body = "本文", createdAt = now, updatedAt = now)),
                memos.insert(MemoEntity(title = "境界のアウトライン", body = "- 一", createdAt = now, updatedAt = now, kind = "outline")),
                application.database.diaryDao().insert(DiaryEntryEntity(diaryDateEpochDay = today.toEpochDay(), body = "境界の日記", state = DiaryState.DRAFT, createdAt = now, updatedAt = now)),
            )
        }
        composeRule.onNodeWithTag("nav_calendar").performClick()
        awaitTag("timeline_item_memo_$memoId")
        composeRule.onNodeWithTag("timeline_item_memo_$memoId").performClick()
        awaitTag("memo_reading_view")
        composeRule.onNodeWithContentDescription("戻る").performClick()

        awaitTag("timeline_list")
        composeRule.onNodeWithTag("timeline_list").performScrollToNode(hasTestTag("timeline_item_outline_$outlineId"))
        composeRule.onNodeWithTag("timeline_item_outline_$outlineId").performClick()
        awaitTag("outliner_screen")
        composeRule.onNodeWithContentDescription("戻る").performClick()

        awaitTag("timeline_list")
        composeRule.onNodeWithTag("timeline_list").performScrollToNode(hasTestTag("timeline_item_journal_$journalId"))
        composeRule.onNodeWithTag("timeline_item_journal_$journalId").performClick()
        awaitTag("diary_body")
        composeRule.onNodeWithTag("diary_body").assertTextContains("境界の日記")
        composeRule.onNodeWithContentDescription("戻る").performClick()

        // The diary list (日記一覧) opens the same journal by id through the same navigator.
        awaitTag("open_journal_list")
        composeRule.onNodeWithTag("open_journal_list").performClick()
        awaitTag("calendar_open_diary")
        composeRule.onNodeWithTag("calendar_open_diary").performClick()
        awaitTag("diary_body")
        composeRule.onNodeWithTag("diary_body").assertTextContains("境界の日記")
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

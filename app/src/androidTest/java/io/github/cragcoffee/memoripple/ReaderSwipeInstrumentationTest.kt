package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.NoteEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * ノートの閲覧モード: a swipe from right to left turns to the next episode, from left to right to
 * the previous one — the page follows the finger like a book — and 設定 can switch it off.
 */
@OptIn(ExperimentalTestApi::class)
class ReaderSwipeInstrumentationTest {
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
        }
    }

    @After
    fun releaseTheClock() {
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun aSwipeTurnsToTheNextEpisodeAndBackAgain() {
        openReaderOnTheFirstEpisode()
        composeRule.onNodeWithTag("note_reader_body").performTouchInput { swipeLeft() }
        awaitText("手紙")
        composeRule.onNodeWithTag("note_reader_body").performTouchInput { swipeRight() }
        awaitText("港の灯")
        assertTrue(true)
    }

    @Test
    fun theSettingIsOnByDefaultAndOffStopsTheSwipe() {
        openReaderOnTheFirstEpisode()
        // Off, from 設定: the row exists, on by default.
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("note_detail_top_bar")
        composeRule.onNodeWithContentDescription("閉じる").performClick()
        awaitTag("memo_view_note")
        composeRule.onNodeWithContentDescription("メモの整理と設定").performClick()
        composeRule.onNodeWithText("設定").performClick()
        awaitTag("settings_list")
        composeRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("setting_reader_swipe_turns"))
        composeRule.onNodeWithTag("reader_swipe_turns_switch").assertIsOn().performClick()
        composeRule.onNodeWithTag("reader_swipe_turns_switch").assertIsOff()
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("memo_view_note")
        composeRule.onNodeWithTag("memo_view_note").performClick()
        awaitTag("open_note_$noteId")
        composeRule.onNodeWithTag("open_note_$noteId").performClick()
        awaitTag("note_episode_$first")
        composeRule.onNodeWithTag("note_episode_$first").performClick()
        awaitTag("note_reader_body")
        composeRule.onNodeWithTag("note_reader_body").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        // Still the first episode: the swipe is off, the buttons remain.
        composeRule.onNodeWithText("港の灯").assertIsDisplayed()
        composeRule.onNodeWithTag("reader_next").assertIsDisplayed()
    }

    private var noteId = 0L
    private var first = 0L
    private var second = 0L

    private fun openReaderOnTheFirstEpisode() {
        runBlocking {
            noteId = application.database.noteDao().insert(
                NoteEntity(title = "夜明け前に君と", coverColor = "plum", createdAt = 1, updatedAt = 1),
            )
            first = application.database.memoDao().insert(
                MemoEntity(title = "港の灯", body = "一話の本文。", createdAt = 1, updatedAt = 1),
            )
            second = application.database.memoDao().insert(
                MemoEntity(title = "手紙", body = "二話の本文。", createdAt = 2, updatedAt = 2),
            )
            application.database.noteDao().placeEpisode(first, noteId, null, 0)
            application.database.noteDao().placeEpisode(second, noteId, null, 1)
        }
        awaitTag("memo_view_note")
        composeRule.onNodeWithTag("memo_view_note").performClick()
        awaitTag("open_note_$noteId")
        composeRule.onNodeWithTag("open_note_$noteId").performClick()
        awaitTag("note_episode_$first")
        composeRule.onNodeWithTag("note_episode_$first").performClick()
        awaitTag("note_reader_body")
        awaitText("港の灯")
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

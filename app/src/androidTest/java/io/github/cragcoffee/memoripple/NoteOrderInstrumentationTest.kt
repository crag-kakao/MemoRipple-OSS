package io.github.cragcoffee.memoripple

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.down
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.height
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.NoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * ノートの並べた順: 「ノートを選択」 on a note's sheet lets a long press carry a note up or down
 * the list; on release the order is written and kept; Back leaves the mode.
 */
@OptIn(ExperimentalTestApi::class)
class NoteOrderInstrumentationTest {
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
    fun anEpisodeRowSlidesWhileCarriedAndTheOrderIsWrittenOnRelease() {
        // Inside a note, the rows are arranged the way the shortcut-bar list is: the carried row
        // floats, the others slide aside at once, and nothing is written until the finger lets go.
        val ids = runBlocking {
            val noteId = application.database.noteDao().insert(
                NoteEntity(title = "夜明け前に君と", coverColor = "plum", createdAt = 1, updatedAt = 1),
            )
            val first = application.database.memoDao().insert(
                MemoEntity(title = "港の灯", body = "一話", createdAt = 1, updatedAt = 1),
            )
            val second = application.database.memoDao().insert(
                MemoEntity(title = "手紙", body = "二話", createdAt = 2, updatedAt = 2),
            )
            application.database.noteDao().placeEpisode(first, noteId, null, 0)
            application.database.noteDao().placeEpisode(second, noteId, null, 1)
            Triple(noteId, first, second)
        }
        val (noteId, first, second) = ids
        awaitTag("memo_view_note")
        composeRule.onNodeWithTag("memo_view_note").performClick()
        awaitTag("open_note_$noteId")
        composeRule.onNodeWithTag("open_note_$noteId").performClick()
        awaitTag("note_episode_$first")
        composeRule.onNodeWithTag("note_arrange_mode").performClick()
        awaitTag("note_drag_episode_$second")
        fun sortIndexOf(memoId: Long) = runBlocking { application.database.memoDao().findById(memoId)?.episodeOrder }
        val before = sortIndexOf(second)

        val rowHeight = composeRule.onNodeWithTag("note_episode_$first").getBoundsInRoot().height
        composeRule.onNodeWithTag("note_drag_episode_$second").performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y - with(composeRule.density) { (rowHeight * 1.2f).toPx() }), delayMillis = 200)
        }
        // Carried past the first row, 手紙 already stands above it — while the store is untouched.
        composeRule.waitUntil(5_000) {
            composeRule.onNodeWithTag("note_episode_$second").getBoundsInRoot().top <
                composeRule.onNodeWithTag("note_episode_$first").getBoundsInRoot().top
        }
        assertEquals(before, sortIndexOf(second))

        composeRule.onNodeWithTag("note_drag_episode_$second").performTouchInput { up() }
        composeRule.waitUntil(5_000) { sortIndexOf(second) != before }
        assertTrue(comesBefore("note_episode_$second", "note_episode_$first"))
    }

    private fun comesBefore(firstTag: String, secondTag: String): Boolean {
        val a = composeRule.onNodeWithTag(firstTag).getBoundsInRoot()
        val b = composeRule.onNodeWithTag(secondTag).getBoundsInRoot()
        return if (a.top != b.top) a.top < b.top else a.left < b.left
    }

    @Test
    fun aNoteCarriedAboveAnotherKeepsThePlaceAndBackLeavesTheMode() {
        val older = insert("古い本", updatedAt = 100)
        val newer = insert("新しい本", updatedAt = 200)
        awaitTag("memo_view_note")
        composeRule.onNodeWithTag("memo_view_note").performClick()
        awaitTag("note_row_$older")
        assertTrue(composeRule.onNodeWithTag("note_row_$newer").getBoundsInRoot().top <
            composeRule.onNodeWithTag("note_row_$older").getBoundsInRoot().top)

        composeRule.onNodeWithTag("open_note_$older").performTouchInput { longClick() }
        awaitTag("note_sheet_select_$older")
        composeRule.onNodeWithText("ノートを選択").assertIsDisplayed()
        composeRule.onNodeWithTag("note_sheet_select_$older").performClick()
        awaitTag("note_arranging_bar")

        // The note is carried by its ＝ handle (2026-09-28); a tap on the row picks it instead.
        // on screen, not only composed: the handle stands at the right edge of a page that may still be sliding in
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithTag("note_drag_handle_$older").assertIsDisplayed() }.isSuccess }
        val rowHeight = composeRule.onNodeWithTag("note_row_$older").getBoundsInRoot().height
        val dragPx = with(composeRule.density) { rowHeight.toPx() }
        composeRule.onNodeWithTag("note_drag_handle_$older").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(center.x, center.y - dragPx), delayMillis = 200)
            up()
        }

        // Every note starts at sortIndex 0, so the write is seen by 新しい本 moving to 1 — waiting
        // for 古い本 at 0 was satisfied before anything was written (the one failure of the
        // full-suite run of 2026-09-16).
        composeRule.waitUntil(5_000) {
            runBlocking { application.database.noteDao().findById(newer)?.sortIndex } == 1
        }
        assertEquals(0, runBlocking { application.database.noteDao().findById(older)?.sortIndex })
        assertEquals(listOf(older, newer), runBlocking { application.database.noteDao().observeSummaries().first().map { it.id } })

        Espresso.pressBack()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("note_arranging_bar").fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag("note_row_$older").assertIsDisplayed()
        composeRule.onAllNodesWithTag("note_arranging_bar").assertCountEquals(0)
    }

    private fun insert(title: String, updatedAt: Long): Long = runBlocking {
        application.database.noteDao().insert(
            NoteEntity(title = title, coverColor = "sky", createdAt = updatedAt, updatedAt = updatedAt),
        )
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

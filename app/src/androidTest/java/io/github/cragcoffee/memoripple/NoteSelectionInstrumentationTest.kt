package io.github.cragcoffee.memoripple

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.down
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * ノートを選択 (2026-09-28): the sheet's ノートを選択 opens a picking page where a tap picks or lets go of a note,
 * the ＝ handle carries one to a new place (and leaves the picks alone), and the picked ones answer a trash
 * button in the + button's place — the sheet's own ノートを削除, asked the same way, for all of them at once.
 */
@OptIn(ExperimentalTestApi::class)
class NoteSelectionInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    @Before
    fun startFromAnEmptyWall() {
        runBlocking {
            application.database.clearAllTables()
            application.settingsRepository.resetToDefaults()
            application.settingsRepository.setAutoPlayOnLaunch(false)
        }
    }

    @After
    fun releaseTheClock() { composeRule.mainClock.autoAdvance = true }

    private fun note(title: String, updatedAt: Long): Long = runBlocking {
        application.database.noteDao().insert(NoteEntity(title = title, coverColor = "sky", createdAt = updatedAt, updatedAt = updatedAt))
    }
    private fun episode(noteId: Long, title: String): Long = runBlocking {
        val memoId = application.database.memoDao().insert(MemoEntity(title = title, body = "本文", createdAt = 1, updatedAt = 1))
        application.database.noteDao().placeEpisode(memoId, noteId, null, 0)
        memoId
    }
    private fun exists(noteId: Long) = runBlocking { application.database.noteDao().findById(noteId) != null }
    private fun order() = runBlocking { application.database.noteDao().observeSummaries().first().map { it.id } }
    private fun awaitTag(tag: String) = composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitGone(tag: String) = composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    /** On screen, not only composed: the pager keeps the note page composed while it is still sliding in, and the ＝ handle stands at its right edge. */
    private fun awaitShown(tag: String) = composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess }
    private fun awaitText(text: String) = composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /** The note page, then the sheet of [held], then its ノートを選択. */
    private fun openPicking(held: Long) {
        awaitTag("memo_view_note")
        composeRule.onNodeWithTag("memo_view_note").performClick()
        awaitTag("open_note_$held")
        composeRule.onNodeWithTag("open_note_$held").performTouchInput { longClick() }
        awaitTag("note_sheet_select_$held")
        composeRule.onNodeWithTag("note_sheet_select_$held").performClick()
        awaitTag("note_arranging_bar")
        awaitShown("note_drag_handle_$held")
    }

    @Test
    fun aTapPicksAndLetsGoTheCountIsTheTitleAndTheTrashButtonTakesThePlusButtonsPlace() {
        val a = note("A", 300)
        val b = note("B", 200)
        val c = note("C", 100)
        openPicking(a)
        // 0 picked
        composeRule.onNodeWithText("ノートを選択").assertIsDisplayed()
        composeRule.onNodeWithTag("create_note").assertIsDisplayed()
        composeRule.onAllNodesWithTag("trash_selected_notes").assertCountEquals(0)
        composeRule.onNodeWithTag("note_drag_handle_$a").assertIsDisplayed()
        // one ＝ per note, each its own accessible element
        composeRule.onAllNodesWithContentDescription("長押ししてノートを並べ替え").assertCountEquals(3)
        // 1 picked
        composeRule.onNodeWithTag("open_note_$a").performClick()
        awaitText("1件を選択")
        composeRule.onNodeWithTag("note_row_$a").assertIsSelected()
        composeRule.onNodeWithTag("trash_selected_notes").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("選択したノートを削除").assertIsDisplayed()
        composeRule.onAllNodesWithTag("create_note").assertCountEquals(0)
        // 3 picked
        composeRule.onNodeWithTag("open_note_$b").performClick()
        composeRule.onNodeWithTag("open_note_$c").performClick()
        awaitText("3件を選択")
        composeRule.onNodeWithTag("trash_selected_notes").assertIsDisplayed()
        // a picked one tapped again is let go
        composeRule.onNodeWithTag("open_note_$b").performClick()
        awaitText("2件を選択")
        composeRule.onNodeWithTag("note_row_$b").assertIsNotSelected()
        // all let go: the title and the + button come back, still picking
        composeRule.onNodeWithTag("open_note_$a").performClick()
        composeRule.onNodeWithTag("open_note_$c").performClick()
        awaitText("ノートを選択")
        awaitTag("create_note")
        composeRule.onAllNodesWithTag("trash_selected_notes").assertCountEquals(0)
        // nothing was opened on the way: still the picking page
        composeRule.onNodeWithTag("note_arranging_bar").assertIsDisplayed()
        // Back leaves the page, as before
        Espresso.pressBack()
        awaitGone("note_arranging_bar")
        awaitTag("create_note")
    }

    @Test
    fun theHandleCarriesANoteToANewPlaceItStaysThereAndThePicksStay() {
        val a = note("A", 300)
        val b = note("B", 200)
        val c = note("C", 100)
        openPicking(a)
        composeRule.onNodeWithTag("open_note_$a").performClick()
        composeRule.onNodeWithTag("open_note_$b").performClick()
        awaitText("2件を選択")
        assertEquals(listOf(a, b, c), order())
        // C carried by its handle above both
        awaitShown("note_drag_handle_$c")
        val rowHeight = composeRule.onNodeWithTag("note_row_$c").getBoundsInRoot().height
        val dragPx = with(composeRule.density) { rowHeight.toPx() } * 2.2f
        composeRule.onNodeWithTag("note_drag_handle_$c").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(center.x, center.y - dragPx), delayMillis = 300)
            up()
        }
        composeRule.waitUntil(5_000) { order().first() == c }
        assertEquals(listOf(c, a, b), order())
        // the picks are untouched by the carry
        composeRule.onNodeWithText("2件を選択").assertIsDisplayed()
        composeRule.onNodeWithTag("note_row_$a").assertIsSelected()
        composeRule.onNodeWithTag("note_row_$b").assertIsSelected()
        composeRule.onNodeWithTag("note_row_$c").assertIsNotSelected()
    }

    @Test
    fun theTrashButtonAsksAsTheSheetDoesAndDeletesOnlyThePickedNotesTheirEpisodesStayMemos() {
        val a = note("A", 300)
        val b = note("B", 200)
        val c = note("C", 100)
        val episodeOfA = episode(a, "Aの第1話")
        openPicking(a)
        composeRule.onNodeWithTag("open_note_$a").performClick()
        composeRule.onNodeWithTag("open_note_$b").performClick()
        awaitText("2件を選択")
        // a cancelled question deletes nothing
        composeRule.onNodeWithTag("trash_selected_notes").performClick()
        awaitTag("note_delete_selected")
        composeRule.onNodeWithText("ノートを削除しますか？").assertIsDisplayed()
        composeRule.onNodeWithText("やめる").performClick()
        awaitGone("note_delete_selected")
        assertEquals(true, exists(a)); assertEquals(true, exists(b))
        composeRule.onNodeWithText("2件を選択").assertIsDisplayed()
        // confirmed: A and B go, C stays; A's episode is a memo again
        composeRule.onNodeWithTag("trash_selected_notes").performClick()
        awaitTag("note_delete_selected_confirm")
        composeRule.onNodeWithTag("note_delete_selected_confirm").performClick()
        composeRule.waitUntil(5_000) { !exists(a) && !exists(b) }
        assertEquals(true, exists(c))
        val released = runBlocking { application.database.memoDao().findById(episodeOfA) }
        assertNotNull(released)
        assertNull(released!!.noteId)
        // the picks end; the page stays, with its title and the + button
        awaitText("ノートを選択")
        awaitTag("create_note")
        composeRule.onAllNodesWithTag("trash_selected_notes").assertCountEquals(0)
        awaitText("2件のノートを削除しました")
        awaitGone("note_row_$a")
        composeRule.onNodeWithTag("note_row_$c").assertIsDisplayed()
    }

    @Test
    fun theChevronStillOpensAndClosesAndTheSheetsSingleDeleteStays() {
        val a = note("A", 200)
        val b = note("B", 100)
        val ep = episode(a, "Aの第1話")
        awaitTag("memo_view_note")
        composeRule.onNodeWithTag("memo_view_note").performClick()
        awaitTag("toggle_note_$a")
        // outside picking: the chevron opens the episodes, the row opens the note as always
        composeRule.onNodeWithTag("toggle_note_$a").performClick()
        awaitTag("episode_row_$ep")
        composeRule.onNodeWithTag("toggle_note_$a").performClick()
        awaitGone("episode_row_$ep")
        // while picking, the chevron is not shown at all (the S26 review, 2026-09-28): the row picks, the ＝ carries
        openPicking(a)
        composeRule.onAllNodesWithTag("toggle_note_$a").assertCountEquals(0)
        composeRule.onAllNodesWithTag("toggle_note_$b").assertCountEquals(0)
        composeRule.onNodeWithText("ノートを選択").assertIsDisplayed()
        Espresso.pressBack()
        awaitGone("note_arranging_bar")
        // the sheet's ノートを削除 is still there and asks the same question
        composeRule.onNodeWithTag("open_note_$b").performTouchInput { longClick() }
        awaitTag("note_sheet_delete_$b")
        composeRule.onNodeWithTag("note_sheet_delete_$b").performClick()
        awaitTag("note_delete_from_list_confirm")
        composeRule.onNodeWithTag("note_delete_from_list_confirm").performClick()
        composeRule.waitUntil(5_000) { !exists(b) }
        assertEquals(true, exists(a))
    }
}

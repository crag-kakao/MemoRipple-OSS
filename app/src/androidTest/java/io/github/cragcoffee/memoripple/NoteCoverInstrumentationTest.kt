package io.github.cragcoffee.memoripple

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.NoteEntity
import io.github.cragcoffee.memoripple.domain.notes.NoteCoverPaint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * ノートの表紙: the one sheet, reached from the shelf as well as from the note, offers a picture
 * and a colour of the writer's own. A colour worth keeping goes to マイカラー and is picked from
 * there for the next note.
 */
class NoteCoverInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MemoRippleApplication

    @Before
    fun startFromAnEmptyShelf() {
        runBlocking {
            application.database.clearAllTables()
            application.settingsRepository.resetToDefaults()
            application.settingsRepository.setMyCoverColors(emptyList())
            application.settingsRepository.setAutoPlayOnLaunch(false)
        }
    }

    @Test
    fun aCoverIsGivenAPhotoOrAColourOfOnesOwnFromTheShelf() {
        val (first, second) = runBlocking {
            val dao = application.database.noteDao()
            dao.insert(NoteEntity(title = "夜明け前に君と", subtitle = "", coverColor = "plum", createdAt = 1, updatedAt = 1)) to
                dao.insert(NoteEntity(title = "港の話", subtitle = "", coverColor = "plum", createdAt = 2, updatedAt = 2))
        }

        composeRule.onNodeWithTag("memo_view_note").performClick()
        openCoverSheetFor(first)

        // The picture is offered here, where the note is held, not only on its own page.
        composeRule.onNodeWithTag("note_cover_pick_photo").performScrollTo().assertIsDisplayed()

        // A colour of one's own: three sliders, then the cover is painted with it.
        composeRule.onNodeWithTag("note_cover_hue").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }
        composeRule.onNodeWithTag("note_cover_saturation")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(70f) }
        composeRule.onNodeWithTag("note_cover_lightness")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
        val own = NoteCoverPaint.Custom(NoteCoverPaint.fromHsl(20f, 0.7f, 0.5f))

        // Kept for later before it is used, so the next note can wear the same one.
        composeRule.onNodeWithTag("note_cover_custom_save").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { application.settingsRepository.myCoverColors.first() } == listOf(own.argb)
        }
        composeRule.onNodeWithTag("note_cover_custom_apply").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            coverOf(first) == own.storageId
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("note_cover_color_sheet").fetchSemanticsNodes().isEmpty()
        }

        // The second note takes the saved colour with one tap.
        openCoverSheetFor(second)
        composeRule.onNodeWithTag("note_cover_my_0").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { coverOf(second) == own.storageId }

        // Holding a saved colour lets it go; the covers already painted with it keep it.
        openCoverSheetFor(second)
        composeRule.onNodeWithTag("note_cover_my_0").performScrollTo()
            .performTouchInput { longClick() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { application.settingsRepository.myCoverColors.first() }.isEmpty()
        }
        assertEquals(own.storageId, coverOf(second))
    }

    private fun openCoverSheetFor(noteId: Long) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("open_note_$noteId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("open_note_$noteId").performTouchInput { longClick() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("note_sheet_cover_$noteId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("note_sheet_cover_$noteId").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("note_cover_color_sheet").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun coverOf(noteId: Long): String? = runBlocking {
        application.database.noteDao().observeSummaries().first()
            .firstOrNull { it.id == noteId }?.coverColor
    }
}

package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.swipeUp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.NoteEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 分割表示: opening, referencing, swapping, and closing — the working session end to end. */
@RunWith(AndroidJUnit4::class)
class SplitWorkspaceInstrumentationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun application(): MemoRippleApplication =
        composeRule.activity.application as MemoRippleApplication

    private var primaryId = 0L
    private var referenceId = 0L
    private var outlineId = 0L
    private var noteId = 0L
    private var secondEpisodeId = 0L

    @Before
    fun seed() {
        runBlocking {
            application().database.clearAllTables()
            application().settingsRepository.setAutoPlayOnLaunch(false)
            application().splitWorkspaceSession.close()
            val dao = application().database.memoDao()
            primaryId = dao.insert(
                MemoEntity(title = "書く方", body = "上で書く本文。", createdAt = 1, updatedAt = 4),
            )
            referenceId = dao.insert(
                MemoEntity(title = "資料メモ", body = "参照される本文。", createdAt = 1, updatedAt = 3),
            )
            outlineId = dao.insert(
                MemoEntity(
                    title = "構成案",
                    body = "■ 序章\n始まりの説明。\n■ 結び\n終わりの説明。",
                    createdAt = 1,
                    updatedAt = 2,
                ),
            )
            noteId = application().database.noteDao().insert(
                NoteEntity(title = "参照ノート", coverColor = "teal", createdAt = 1, updatedAt = 1),
            )
            val episode = dao.insert(
                MemoEntity(title = "第一話", body = "一話目の本文。", createdAt = 1, updatedAt = 1),
            )
            application().database.noteDao().placeEpisode(episode, noteId, null, 0)
            secondEpisodeId = dao.insert(
                MemoEntity(title = "第二話", body = "二話目の本文。", createdAt = 1, updatedAt = 1),
            )
            application().database.noteDao().placeEpisode(secondEpisodeId, noteId, null, 1)
        }
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** An existing memo opens reading now; step back to the pen when the page is up. */
    private fun leaveReadingModeIfShown() {
        runCatching {
            composeRule.waitUntil(timeoutMillis = 3_000) {
                composeRule.onAllNodesWithTag("reading_edit").fetchSemanticsNodes().isNotEmpty() ||
                    composeRule.onAllNodesWithTag("memo_body").fetchSemanticsNodes().isNotEmpty()
            }
        }
        if (composeRule.onAllNodesWithTag("reading_edit").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("reading_edit").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("memo_body").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private fun openPrimaryEditor() {
        awaitTag("memo_card_$primaryId")
        composeRule.onNodeWithTag("memo_card_$primaryId").performClick()
        leaveReadingModeIfShown()
        awaitTag("memo_editor_more")
    }

    private fun openSplitPicker() {
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        awaitTag("memo_editor_split")
        composeRule.onNodeWithTag("memo_editor_split").performClick()
        awaitTag("split_reference_picker")
    }

    @Test
    fun aMemoReferenceOpensAndTheEditorKeepsWriting() {
        openPrimaryEditor()
        openSplitPicker()
        // The memo open above is not offered below.
        composeRule.onAllNodesWithTag("split_pick_memo_$primaryId").assertCountEquals(0)
        composeRule.onNodeWithTag("split_pick_memo_$referenceId").performClick()

        awaitTag("split_reference_pane")
        composeRule.onNodeWithTag("split_reference_header")
            .assertContentDescriptionContains("資料メモ", substring = true)
        composeRule.onNodeWithText("参照される本文。").assertIsDisplayed()
        // The editor above still holds its own words.
        composeRule.onNodeWithTag("memo_body").assertTextContains("上で書く本文。", substring = true)
        composeRule.onNodeWithTag("split_divider").assertIsDisplayed()
    }

    @Test
    fun aNoteAndAnOutlineCanStandBelow() {
        openPrimaryEditor()
        openSplitPicker()
        composeRule.onNodeWithTag("split_picker_kind_note").performClick()
        awaitTag("split_pick_note_$noteId")
        composeRule.onNodeWithTag("split_pick_note_$noteId").performClick()
        awaitTag("split_reference_note")
        composeRule.onNodeWithText("一話目の本文。").assertIsDisplayed()

        composeRule.onNodeWithTag("split_change_reference").performClick()
        awaitTag("split_reference_picker")
        composeRule.onNodeWithTag("split_picker_kind_outline").performClick()
        awaitTag("split_pick_memo_$outlineId")
        composeRule.onNodeWithTag("split_pick_memo_$outlineId").performClick()
        awaitTag("split_reference_outline")
        composeRule.onNodeWithText("序章").assertIsDisplayed()
        // A heading opens to the prose beneath it.
        composeRule.onNodeWithTag("split_outline_heading_0").performClick()
        composeRule.onNodeWithText("始まりの説明。").assertIsDisplayed()
    }

    @Test
    fun editingTheReferenceSwapsThePanes() {
        openPrimaryEditor()
        openSplitPicker()
        composeRule.onNodeWithTag("split_pick_memo_$referenceId").performClick()
        awaitTag("split_reference_pane")

        composeRule.onNodeWithTag("split_edit_reference").performClick()
        leaveReadingModeIfShown()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("参照される本文。").fetchSemanticsNodes().size >= 2 ||
                composeRule.onAllNodesWithTag("memo_body").fetchSemanticsNodes().isNotEmpty()
        }
        // The reference memo is now the one being written…
        composeRule.onNodeWithTag("memo_body")
            .assertTextContains("参照される本文。", substring = true)
        // …and the old primary waits below.
        awaitTag("split_reference_pane")
        composeRule.onNodeWithTag("split_reference_header")
            .assertContentDescriptionContains("書く方", substring = true)
    }

    @Test
    fun backClosesTheSplitBeforeLeavingTheScreen() {
        openPrimaryEditor()
        openSplitPicker()
        composeRule.onNodeWithTag("split_pick_memo_$referenceId").performClick()
        awaitTag("split_reference_pane")

        Espresso.pressBack()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("split_reference_pane").fetchSemanticsNodes().isEmpty()
        }
        // Still on the editor, words intact.
        composeRule.onNodeWithTag("memo_body").assertTextContains("上で書く本文。", substring = true)
    }

    @Test
    fun theSplitSurvivesRecreation() {
        openPrimaryEditor()
        openSplitPicker()
        composeRule.onNodeWithTag("split_pick_memo_$referenceId").performClick()
        awaitTag("split_reference_pane")

        composeRule.activityRule.scenario.recreate()

        awaitTag("split_reference_pane")
        composeRule.onNodeWithTag("split_reference_header")
            .assertContentDescriptionContains("資料メモ", substring = true)
    }

    @Test
    fun closingFromTheHeaderLeavesTheEditorWhole() {
        openPrimaryEditor()
        openSplitPicker()
        composeRule.onNodeWithTag("split_pick_memo_$referenceId").performClick()
        awaitTag("split_reference_pane")

        composeRule.onNodeWithTag("split_close").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("split_reference_pane").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("memo_body").assertTextContains("上で書く本文。", substring = true)
        assertEquals(null, application().splitWorkspaceSession.resumeFor(primaryId))
    }

    @Test
    fun aLinkInsideTheReferenceReAimsTheReferenceItself() {
        runBlocking {
            application().database.memoDao().updateContent(
                referenceId,
                "資料メモ",
                "参照される本文。[[構成案]]も見よ。",
                6,
            )
        }
        openPrimaryEditor()
        openSplitPicker()
        composeRule.onNodeWithTag("split_pick_memo_$referenceId").performClick()
        awaitTag("split_reference_memo")

        // The chip turns clickable once the wall list has resolved the link's target.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("split_reference_link_構成案") and hasClickAction())
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("split_reference_link_構成案").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("序章", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The writing above never moved.
        composeRule.onNodeWithTag("memo_body").assertTextContains("上で書く本文。", substring = true)
        // And the memo the pane turned to was only read — never overwritten by what the
        // pane held a moment before.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                application().database.memoDao().findById(outlineId)
                    ?.let { it.title == "構成案" && it.body.contains("序章") } == true
            }
        }
    }

    @Test
    fun theNoteReferenceJumpsStraightToAnEpisode() {
        openPrimaryEditor()
        openSplitPicker()
        composeRule.onNodeWithTag("split_picker_kind_note").performClick()
        composeRule.onNodeWithTag("split_pick_note_$noteId").performClick()
        awaitTag("split_reference_note")

        // The episode's own name opens the table of contents; one tap lands on another episode.
        composeRule.onNodeWithTag("split_note_jump").performClick()
        awaitTag("split_note_jump_$secondEpisodeId")
        composeRule.onNodeWithTag("split_note_jump_$secondEpisodeId").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("二話目の本文。").fetchSemanticsNodes().isNotEmpty()
        }
        // The editor above never moved.
        composeRule.onNodeWithTag("memo_body").assertTextContains("上で書く本文。", substring = true)
    }

    @Test
    fun theReferenceItselfAcceptsWriting() {
        openPrimaryEditor()
        openSplitPicker()
        composeRule.onNodeWithTag("split_pick_memo_$referenceId").performClick()
        awaitTag("split_reference_memo")

        composeRule.onNodeWithTag("split_reference_body").performTextInput("追記した一文。")
        // The debounce settles, then Room holds the sentence.
        composeRule.waitUntil(timeoutMillis = 8_000) {
            runBlocking {
                application().database.memoDao().findById(referenceId)
                    ?.body?.contains("追記した一文。") == true
            }
        }
        // The editor above kept its own words.
        composeRule.onNodeWithTag("memo_body").assertTextContains("上で書く本文。", substring = true)
    }

    @Test
    fun referenceScrollLeavesTheEditorAlone() {
        runBlocking {
            application().database.memoDao().updateContent(
                referenceId,
                "資料メモ",
                (1..60).joinToString("\n") { "資料の行 $it。" },
                5,
            )
        }
        openPrimaryEditor()
        openSplitPicker()
        composeRule.onNodeWithTag("split_pick_memo_$referenceId").performClick()
        awaitTag("split_reference_memo")

        composeRule.onNodeWithTag("split_reference_memo").performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        // The editor above did not move or change.
        composeRule.onNodeWithTag("memo_body").assertTextContains("上で書く本文。", substring = true)
        composeRule.onNodeWithTag("memo_title").assertTextContains("書く方", substring = true)
    }
}

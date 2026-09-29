package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * アウトライナー as a place of its own on the home screen: the third word between メモ and
 * ノート, listing only what was made there (kind = outline — never a memo that happens to be
 * written as one), a button that makes an outline and opens it in the outliner straight away,
 * and a Back that lands on the same page. A memo still opens in the editor, and the editor's
 * development-only door to the outliner is gone.
 */
@OptIn(ExperimentalTestApi::class)
class OutlinerHomeInstrumentationTest {
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
            application.settingsRepository.clearOutlinerFolds()
        }
    }

    @After
    fun releaseTheClock() {
        composeRule.mainClock.autoAdvance = true
        runBlocking { application.settingsRepository.clearOutlinerFolds() }
    }

    @Test
    fun theHomeOffersMemoOutlinerAndNoteInThatOrderAndAllThreeFitTheScreen() {
        awaitTag("memo_view_outliner")

        composeRule.onNodeWithTag("memo_view_memo").assertIsSelected()
        composeRule.onNodeWithTag("memo_view_outliner").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_view_note").assertIsDisplayed()
        assertTrue(comesBefore("memo_view_memo", "memo_view_outliner"))
        assertTrue(comesBefore("memo_view_outliner", "memo_view_note"))
        // Three words on one rail, none pushed past the edge.
        val root = composeRule.onRoot().getBoundsInRoot()
        val note = composeRule.onNodeWithTag("memo_view_note").getUnclippedBoundsInRoot()
        assertTrue("ノート ends at ${note.right}, screen at ${root.right}", note.right <= root.right)
        val memo = composeRule.onNodeWithTag("memo_view_memo").getUnclippedBoundsInRoot()
        assertEquals(memo.top, note.top)
    }

    @Test
    fun theOutlinerPageShowsOnlyOutlinesAndAMemoWrittenLikeOneIsNotOne() {
        val memoId = insert("骨組みのメモ", "- 項目\n  - 子", MemoKind.MEMO)
        val outlineId = insert("計画", "- 一\n  - 二", MemoKind.OUTLINE)
        awaitTag("memo_card_$memoId")
        composeRule.onAllNodesWithTag("memo_card_$outlineId").assertCountEquals(0)

        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$outlineId")

        composeRule.onAllNodesWithTag("outline_card_$memoId").assertCountEquals(0)
        composeRule.onNodeWithTag("create_outline").assertIsDisplayed()
        composeRule.onAllNodesWithTag("create_memo").assertCountEquals(0)
    }

    @Test
    fun anEmptyOutlinerPageSaysSoAndKeepsItsButton() {
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()

        awaitTag("outliner_empty_state")
        composeRule.onNodeWithTag("create_outline").assertIsDisplayed()
    }

    @Test
    fun theButtonMakesAnOutlineOpensItAndBackLandsOnTheOutlinerPageNotTheWall() {
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("create_outline")

        composeRule.onNodeWithTag("create_outline").performClick()

        // Straight into the outliner, with a first line ready for the words.
        awaitTag("outliner_screen")
        awaitTag("outliner_node_1")
        composeRule.onNodeWithTag("outliner_node_1").assertIsFocused()
        composeRule.onNodeWithTag("outliner_node_1").performTextInput("一\n二")
        composeRule.onNodeWithTag("outliner_node_2").assertTextEquals("二")

        composeRule.onNodeWithContentDescription("戻る").performClick()

        // Back is the outliner page, and the outline stands on it.
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").assertIsSelected()
        val outline = runBlocking { application.memoRepository.observeOutlineDocuments("").first().single() }
        assertEquals("outline", outline.kind)
        assertEquals("- 一\n- 二", outline.body)
        awaitTag("outline_card_${outline.id}")

        // Not on the wall.
        composeRule.onNodeWithTag("memo_view_memo").performClick()
        awaitTag("memo_view_memo")
        composeRule.onAllNodesWithTag("memo_card_${outline.id}").assertCountEquals(0)

        // And it opens again where it was made.
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_${outline.id}")
        composeRule.onNodeWithTag("outline_card_${outline.id}").performClick()
        awaitTag("outliner_node_2")
        composeRule.onNodeWithTag("outliner_node_1").assertTextEquals("一")
        composeRule.onNodeWithTag("outliner_node_2").assertTextEquals("二")
    }

    @Test
    fun aNewOutlineLeftEmptyIsNotKeptAndAnExistingEmptyOneGetsNoMarker() {
        // Human decision 2026-09-25 (docs/OUTLINE_PHOTO_ROWS.md §8): a new outline left with
        // nothing in it is as if it had never been made — it used to stay as an empty outline.
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("create_outline")
        composeRule.onNodeWithTag("create_outline").performClick()
        awaitTag("outliner_node_1")

        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("memo_view_outliner")
        composeRule.waitUntil(5_000) { runBlocking { application.memoRepository.observeOutlineDocuments("").first() }.isEmpty() }

        // An empty outline that already existed stays, and the line offered for writing — never
        // written — forces no marker into its body.
        val existing = insert("", "", MemoKind.OUTLINE)
        awaitTag("outline_card_$existing")
        composeRule.onNodeWithTag("outline_card_$existing").performClick()
        awaitTag("outliner_node_1")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("memo_view_outliner")
        val outline = runBlocking { application.memoRepository.observeOutlineDocuments("").first().single() }
        assertEquals(existing, outline.id)
        assertEquals("", outline.body)
        assertEquals("outline", outline.kind)
    }

    @Test
    fun theSearchNarrowsTheOutlinerPageToItsOwnOutlines() {
        val plan = insert("計画", "- 一", MemoKind.OUTLINE)
        val shopping = insert("買い物", "- 牛乳", MemoKind.OUTLINE)
        insert("計画のメモ", "計画について", MemoKind.MEMO)
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$shopping")

        composeRule.onNodeWithTag("memo_search").performTextInput("計画")

        awaitTag("outline_card_$plan")
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("outline_card_$shopping").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onAllNodesWithTag("outline_card_$plan").assertCountEquals(1)
    }

    @Test
    fun onlyTheTappedLineOfAnOutlineIsATextField() {
        val outlineId = insert("計画", "- 一\n  - 二\n- 三", MemoKind.OUTLINE)
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$outlineId")
        composeRule.onNodeWithTag("outline_card_$outlineId").performClick()
        awaitTag("outliner_node_3")

        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        composeRule.onNodeWithTag("outliner_node_2").performClick()
        composeRule.onNodeWithTag("outliner_node_2").assertIsFocused()
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
    }

    @Test
    fun aMemoStillOpensInTheEditorWhoseMenuNoLongerHasTheDevelopmentDoor() {
        val memoId = insert("ふつうのメモ", "- 項目\n  - 子", MemoKind.MEMO)
        awaitTag("memo_card_$memoId")

        composeRule.onNodeWithTag("memo_card_$memoId").performClick()

        awaitTag("memo_editor_more")
        composeRule.onAllNodesWithTag("outliner_screen").assertCountEquals(0)
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        awaitTag("memo_editor_trash")
        composeRule.onAllNodesWithTag("memo_editor_open_outliner").assertCountEquals(0)
    }

    private fun insert(title: String, body: String, kind: MemoKind): Long = runBlocking {
        application.database.memoDao().insert(
            MemoEntity(
                title = title,
                body = body,
                createdAt = 1_783_000_000_000L,
                updatedAt = 1_783_000_000_000L,
                kind = kind.storageId,
            ),
        )
    }

    private fun comesBefore(firstTag: String, secondTag: String): Boolean {
        val first = composeRule.onNodeWithTag(firstTag).getUnclippedBoundsInRoot()
        val second = composeRule.onNodeWithTag(secondTag).getUnclippedBoundsInRoot()
        return first.left < second.left
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Whatever door a memo is opened through, a memo lands in the editor and an outline in the
 * outliner: a `[[title]]` chip, a backlink chip, a search hit on the outliner page, the
 * archive, and an outline brought back from the trash. `[[title]]` still resolves by title;
 * only where the resolved memo goes is decided by its kind.
 */
@OptIn(ExperimentalTestApi::class)
class MemoOpenRoutingInstrumentationTest {
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
    }

    @Test
    fun aLinkChipOpensAnOutlineInTheOutlinerAndAMemoInTheEditor() {
        insert("計画", "- 一\n  - 二", MemoKind.OUTLINE)
        insert("買い物", "牛乳", MemoKind.MEMO)
        val source = insert("元", "[[計画]] と [[買い物]]", MemoKind.MEMO)
        openFromTheWall(source)
        awaitTag("memo_links")

        composeRule.onNodeWithTag("memo_link_out_計画").performClick()

        awaitTag("outliner_screen")
        composeRule.onNodeWithTag("outliner_node_1").assertTextEquals("一")
        composeRule.onAllNodesWithTag("memo_body").assertCountEquals(0)

        // Back to the memo that linked, and its other link is a memo: the editor.
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        awaitTag("memo_links")
        composeRule.onNodeWithTag("memo_link_out_買い物").performClick()
        awaitEditorOf("買い物")
        composeRule.onAllNodesWithTag("outliner_screen").assertCountEquals(0)
    }

    @Test
    fun aBacklinkChipOpensTheOutlineThatLinksHereInTheOutliner() {
        val plan = insert("計画", "- [[買い物]] を見る\n  - 二", MemoKind.OUTLINE)
        val shopping = insert("買い物", "牛乳", MemoKind.MEMO)
        openFromTheWall(shopping)
        awaitTag("memo_backlink_$plan")

        composeRule.onNodeWithTag("memo_backlink_$plan").performClick()

        awaitTag("outliner_screen")
        composeRule.onNodeWithTag("outliner_node_2").assertTextEquals("二")

        // One screen was opened, not two: Back is the memo that was linked to, at once.
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        awaitTag("memo_editor_more")
        composeRule.onAllNodesWithTag("outliner_screen").assertCountEquals(0)
    }

    @Test
    fun aSearchHitOnTheOutlinerPageOpensInTheOutliner() {
        val plan = insert("計画", "- 一", MemoKind.OUTLINE)
        insert("買い物", "- 牛乳", MemoKind.OUTLINE)
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$plan")
        composeRule.onNodeWithTag("memo_search").performTextInput("計画")
        Espresso.closeSoftKeyboard()
        awaitTag("outline_card_$plan")

        composeRule.onNodeWithTag("outline_card_$plan").performClick()

        awaitTag("outliner_screen")
        composeRule.onNodeWithTag("outliner_node_1").assertTextEquals("一")
    }

    @Test
    fun theArchiveOpensEachKindWhereItIsEdited() {
        val plan = insert("計画", "- 一", MemoKind.OUTLINE, archivedAt = 5)
        val memo = insert("棚上げ", "本文", MemoKind.MEMO, archivedAt = 6)
        awaitTag("memo_top_menu")
        composeRule.onNodeWithTag("memo_top_menu").performClick()
        awaitTag("open_archive")
        composeRule.onNodeWithTag("open_archive").performClick()
        awaitTag("archive_memo_$plan")

        composeRule.onNodeWithTag("archive_memo_$plan").performClick()
        awaitTag("outliner_screen")
        composeRule.onNodeWithTag("outliner_node_1").assertTextEquals("一")

        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        awaitTag("archive_memo_$memo")
        composeRule.onNodeWithTag("archive_memo_$memo").performClick()
        awaitEditorOf("棚上げ")
        composeRule.onAllNodesWithTag("outliner_screen").assertCountEquals(0)
    }

    @Test
    fun anOutlineBroughtBackFromTheTrashReturnsToItsPageAndOpensThere() {
        val plan = insert("計画", "- 一", MemoKind.OUTLINE, trashedAt = 5)
        awaitTag("memo_top_menu")
        composeRule.onNodeWithTag("memo_top_menu").performClick()
        awaitTag("open_trash")
        composeRule.onNodeWithTag("open_trash").performClick()
        awaitTag("restore_$plan")
        composeRule.onNodeWithTag("restore_$plan").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("trash_memo_$plan").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithContentDescription("戻る").performClick()

        awaitTag("memo_view_outliner")
        composeRule.onAllNodesWithTag("memo_card_$plan").assertCountEquals(0)
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$plan")
        composeRule.onNodeWithTag("outline_card_$plan").performClick()
        awaitTag("outliner_screen")
        composeRule.onNodeWithTag("outliner_node_1").assertTextEquals("一")
    }

    private fun openFromTheWall(memoId: Long) {
        awaitTag("memo_card_$memoId")
        composeRule.onNodeWithTag("memo_card_$memoId").performClick()
        // An existing memo opens reading; the links sit with the pen.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("reading_edit").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("memo_body").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithTag("reading_edit").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("reading_edit").performClick()
        }
        awaitTag("memo_body")
    }

    private fun awaitEditorOf(title: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("memo_reading_view").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("memo_body").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("memo_editor_more").assertIsDisplayed()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("memo_title").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("memo_reading_view").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun insert(
        title: String,
        body: String,
        kind: MemoKind,
        archivedAt: Long? = null,
        trashedAt: Long? = null,
    ): Long = runBlocking {
        application.database.memoDao().insert(
            MemoEntity(
                title = title,
                body = body,
                createdAt = 1_783_000_000_000L,
                updatedAt = 1_783_000_000_000L,
                archivedAt = archivedAt,
                trashedAt = trashedAt,
                kind = kind.storageId,
            ),
        )
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

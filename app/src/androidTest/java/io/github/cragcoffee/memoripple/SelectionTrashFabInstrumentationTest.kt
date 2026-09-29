package io.github.cragcoffee.memoripple

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.down
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.height
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The selection's trash button (2026-09-28): on メモ and アウトライナー, while one or more items are picked, the
 * + button's place holds a trash button that does what the selection menu's ゴミ箱へ移動 does — the same call,
 * so the items go to the trash (never deleted), the others stay, the selection ends, and the undo snackbar
 * comes back with them. ノート is left as it was (the
 * condition itself excludes it; its tab cannot be reached while a selection holds the bar).
 */
@OptIn(ExperimentalTestApi::class)
class SelectionTrashFabInstrumentationTest {
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

    private fun insert(title: String, kind: MemoKind, at: Long): Long = runBlocking {
        application.database.memoDao().insert(
            MemoEntity(title = title, body = "- $title", createdAt = at, updatedAt = at, kind = kind.storageId),
        )
    }
    private fun trashedAt(id: Long): Long? = runBlocking { application.database.memoDao().findById(id)!!.trashedAt }
    private fun awaitTag(tag: String) = composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitGone(tag: String) = composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    private fun awaitText(text: String) = composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /** Holding a card asks about it; its 選択 starts the selection with that one. */
    private fun startSelectionWith(cardTag: String, id: Long) {
        composeRule.onNodeWithTag(cardTag).performTouchInput { longClick() }
        awaitTag("memo_select_$id")
        composeRule.onNodeWithTag("memo_select_$id").performClick()
        awaitText("1件を選択")
    }

    @Test
    fun onTheMemoPageTheTrashButtonTakesThePlusButtonsPlaceWhileMemosArePicked() {
        val a = insert("A", MemoKind.MEMO, 3)
        val b = insert("B", MemoKind.MEMO, 2)
        val c = insert("C", MemoKind.MEMO, 1)
        awaitTag("memo_card_$a")
        // no selection: the + button
        composeRule.onNodeWithTag("create_memo").assertIsDisplayed()
        composeRule.onAllNodesWithTag("trash_selected_memos").assertCountEquals(0)

        startSelectionWith("memo_card_$a", a)
        composeRule.onNodeWithTag("trash_selected_memos").assertIsDisplayed()
        composeRule.onAllNodesWithTag("create_memo").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("選択したメモをゴミ箱へ移動").assertIsDisplayed()

        composeRule.onNodeWithTag("memo_card_$b").performClick()
        awaitText("2件を選択")
        composeRule.onNodeWithTag("trash_selected_memos").assertIsDisplayed()

        // picking both off ends the selection: the + button is back
        composeRule.onNodeWithTag("memo_card_$a").performClick()
        composeRule.onNodeWithTag("memo_card_$b").performClick()
        awaitTag("create_memo")
        composeRule.onAllNodesWithTag("trash_selected_memos").assertCountEquals(0)

        // the trash button moves exactly the picked memos to the trash; the other stays
        startSelectionWith("memo_card_$a", a)
        composeRule.onNodeWithTag("memo_card_$b").performClick()
        awaitText("2件を選択")
        composeRule.onNodeWithTag("trash_selected_memos").performClick()
        awaitGone("memo_card_$a")
        awaitGone("memo_card_$b")
        assertNotNull(trashedAt(a))
        assertNotNull(trashedAt(b))
        assertNull(trashedAt(c))
        composeRule.onNodeWithTag("memo_card_$c").assertIsDisplayed()
        // the selection has ended, and the + button is back
        awaitTag("create_memo")
        composeRule.onAllNodesWithTag("trash_selected_memos").assertCountEquals(0)
        // the same undo as the menu's ゴミ箱へ移動
        awaitText("2件をゴミ箱へ移動しました")
        composeRule.onNodeWithText("元に戻す").performClick()
        awaitTag("memo_card_$a")
        awaitTag("memo_card_$b")
        assertNull(trashedAt(a))
        assertNull(trashedAt(b))
    }

    @Test
    fun onTheMemoPageTheSelectionMenusTrashStillWorks() {
        val a = insert("A", MemoKind.MEMO, 2)
        val b = insert("B", MemoKind.MEMO, 1)
        awaitTag("memo_card_$a")
        startSelectionWith("memo_card_$a", a)
        composeRule.onNodeWithTag("memo_bulk_menu").performClick()
        awaitTag("bulk_trash")
        composeRule.onNodeWithTag("bulk_trash").performClick()
        awaitGone("memo_card_$a")
        assertNotNull(trashedAt(a))
        assertNull(trashedAt(b))
        awaitTag("create_memo")
    }

    @Test
    fun aSelectionAskedForWithNothingPickedShowsNeitherButton() {
        val a = insert("A", MemoKind.MEMO, 1)
        awaitTag("memo_card_$a")
        composeRule.onNodeWithTag("memo_overflow").performClick()
        awaitTag("request_memo_selection")
        composeRule.onNodeWithTag("request_memo_selection").performClick()
        awaitText("0件を選択")
        composeRule.onAllNodesWithTag("trash_selected_memos").assertCountEquals(0)
        composeRule.onAllNodesWithTag("create_memo").assertCountEquals(0)
        composeRule.onNodeWithTag("memo_card_$a").performClick()
        awaitText("1件を選択")
        composeRule.onNodeWithTag("trash_selected_memos").assertIsDisplayed()
    }

    @Test
    fun onTheOutlinerPageTheTrashButtonTakesThePlusButtonsPlaceWhileOutlinesArePicked() {
        val a = insert("計画A", MemoKind.OUTLINE, 3)
        val b = insert("計画B", MemoKind.OUTLINE, 2)
        val c = insert("計画C", MemoKind.OUTLINE, 1)
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$a")
        composeRule.onNodeWithTag("create_outline").assertIsDisplayed()
        composeRule.onAllNodesWithTag("trash_selected_outlines").assertCountEquals(0)

        startSelectionWith("outline_card_$a", a)
        composeRule.onNodeWithTag("trash_selected_outlines").assertIsDisplayed()
        composeRule.onAllNodesWithTag("create_outline").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("選択したアウトラインをゴミ箱へ移動").assertIsDisplayed()

        composeRule.onNodeWithTag("outline_card_$b").performClick()
        awaitText("2件を選択")
        composeRule.onNodeWithTag("trash_selected_outlines").assertIsDisplayed()

        composeRule.onNodeWithTag("outline_card_$a").performClick()
        composeRule.onNodeWithTag("outline_card_$b").performClick()
        awaitTag("create_outline")
        composeRule.onAllNodesWithTag("trash_selected_outlines").assertCountEquals(0)

        startSelectionWith("outline_card_$a", a)
        composeRule.onNodeWithTag("outline_card_$b").performClick()
        awaitText("2件を選択")
        composeRule.onNodeWithTag("trash_selected_outlines").performClick()
        awaitGone("outline_card_$a")
        awaitGone("outline_card_$b")
        assertNotNull(trashedAt(a))
        assertNotNull(trashedAt(b))
        assertNull(trashedAt(c))
        composeRule.onNodeWithTag("outline_card_$c").assertIsDisplayed()
        awaitTag("create_outline")
        composeRule.onAllNodesWithTag("trash_selected_outlines").assertCountEquals(0)
        awaitText("2件をゴミ箱へ移動しました")
        composeRule.onNodeWithText("元に戻す").performClick()
        awaitTag("outline_card_$a")
        assertNull(trashedAt(a))
        assertNull(trashedAt(b))
    }

    @Test
    fun onTheOutlinerPageTheSelectionMenusTrashStillWorks() {
        val a = insert("計画A", MemoKind.OUTLINE, 2)
        val b = insert("計画B", MemoKind.OUTLINE, 1)
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$a")
        startSelectionWith("outline_card_$a", a)
        composeRule.onNodeWithTag("memo_bulk_menu").performClick()
        awaitTag("bulk_trash")
        composeRule.onNodeWithTag("bulk_trash").performClick()
        awaitGone("outline_card_$a")
        assertNotNull(trashedAt(a))
        assertNull(trashedAt(b))
        awaitTag("create_outline")
    }
    /** The S26 review (2026-09-28): carrying an outline while outlines are picked kept clearing the picks after each carry. */
    @Test
    fun onTheOutlinerPageCarryingAnOutlineKeepsThePicks() {
        val a = insert("計画A", MemoKind.OUTLINE, 3)
        val b = insert("計画B", MemoKind.OUTLINE, 2)
        val c = insert("計画C", MemoKind.OUTLINE, 1)
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$a")
        startSelectionWith("outline_card_$a", a)
        composeRule.onNodeWithTag("outline_card_$b").performClick()
        awaitText("2件を選択")
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithTag("outline_card_$c").assertIsDisplayed() }.isSuccess }
        val height = composeRule.onNodeWithTag("outline_card_$c").getBoundsInRoot().height
        val dragPx = with(composeRule.density) { height.toPx() } * 2.4f
        composeRule.onNodeWithTag("outline_card_$c").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(center.x, center.y - dragPx), delayMillis = 300)
            up()
        }
        // the carry is written (C first) ...
        composeRule.waitUntil(5_000) { runBlocking { application.database.memoDao().findById(c)!!.sortIndex } < runBlocking { application.database.memoDao().findById(a)!!.sortIndex } }
        composeRule.waitForIdle()
        // ... and the picks stay
        composeRule.onNodeWithText("2件を選択").assertIsDisplayed()
        composeRule.onNodeWithTag("outline_card_$a").assertIsSelected()
        composeRule.onNodeWithTag("outline_card_$b").assertIsSelected()
        composeRule.onNodeWithTag("trash_selected_outlines").assertIsDisplayed()
        assertEquals(null, runBlocking { application.database.memoDao().findById(c)!!.trashedAt })
    }
}

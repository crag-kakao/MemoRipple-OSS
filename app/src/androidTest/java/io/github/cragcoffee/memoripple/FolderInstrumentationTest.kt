package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.FolderEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * フォルダ on the home: a section above the documents of the メモ and アウトライナー pages, one
 * tree shared by both kinds. Folders are made from the page's ⋮ menu, opened by a tap, left by
 * Back one level at a time (the root is where the normal Back begins), renamed / moved /
 * deleted from a hold; a document is filed from its own hold sheet. The outline *inside* a
 * document never appears here.
 */
@OptIn(ExperimentalTestApi::class)
class FolderInstrumentationTest {
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
    fun aFolderIsMadeFromTheMenuOpenedByATapAndLeftWithBack() {
        awaitTag("memo_overflow")

        createFolderFromMenu("開発")

        val id = folderId("開発")
        composeRule.onNodeWithTag("folder_row_$id").assertIsDisplayed()
        composeRule.onNodeWithTag("folder_row_$id").performClick()
        awaitTag("folder_crumb_current")
        composeRule.onNodeWithTag("folder_crumb_current").assertTextEquals("開発")
        composeRule.onNodeWithTag("folder_empty_state").assertIsDisplayed()
        composeRule.onAllNodesWithTag("folder_row_$id").assertCountEquals(0)

        Espresso.pressBack()

        awaitTag("folder_row_$id")
        composeRule.onAllNodesWithTag("folder_crumb_current").assertCountEquals(0)
    }

    @Test
    fun nestedFoldersShowTheWholeBreadcrumbAndBackClimbsOneLevelAtATime() {
        val dev = insertFolder("開発", null)
        val android = insertFolder("Android", dev)
        val memoRipple = insertFolder("MemoRipple", android)
        awaitTag("folder_row_$dev")

        composeRule.onNodeWithTag("folder_row_$dev").performClick()
        awaitTag("folder_row_$android")
        composeRule.onNodeWithTag("folder_row_$android").performClick()
        awaitTag("folder_row_$memoRipple")
        composeRule.onNodeWithTag("folder_row_$memoRipple").performClick()
        awaitTag("folder_crumb_$android")

        composeRule.onNodeWithTag("folder_crumb_root").assertIsDisplayed()
        composeRule.onNodeWithTag("folder_crumb_$dev").assertIsDisplayed()
        composeRule.onNodeWithTag("folder_crumb_$android").assertIsDisplayed()
        composeRule.onNodeWithTag("folder_crumb_current").assertTextEquals("MemoRipple")
        // The same place on the outliner page: one tree, both kinds.
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("folder_crumb_current")
        composeRule.onNodeWithTag("folder_crumb_current").assertTextEquals("MemoRipple")
        composeRule.onNodeWithTag("memo_view_memo").performClick()

        Espresso.pressBack()
        awaitTag("folder_row_$memoRipple")
        composeRule.onNodeWithTag("folder_crumb_current").assertTextEquals("Android")
        composeRule.onNodeWithTag("folder_crumb_$dev").performClick()
        awaitTag("folder_row_$android")
        composeRule.onNodeWithTag("folder_crumb_current").assertTextEquals("開発")
        Espresso.pressBack()
        awaitTag("folder_row_$dev")
        composeRule.onAllNodesWithTag("folder_crumb_current").assertCountEquals(0)
    }

    @Test
    fun aFolderIsRenamedMovedAndDeletedWithoutLosingWhatItHeld() {
        val dev = insertFolder("開発", null)
        val android = insertFolder("Android", dev)
        val projects = insertFolder("Projects", null)
        val memo = insert("仕様", "本文", MemoKind.MEMO, folderId = dev)
        awaitTag("folder_row_$dev")

        // Rename.
        composeRule.onNodeWithTag("folder_row_$dev").performTouchInput { longClick() }
        awaitTag("folder_rename_$dev")
        composeRule.onNodeWithTag("folder_rename_$dev").performClick()
        awaitTag("folder_name_input")
        composeRule.onNodeWithTag("folder_name_input").performTextInput("Dev")
        composeRule.onNodeWithTag("folder_name_confirm").performClick()
        composeRule.waitUntil(5_000) { runBlocking { application.folderRepository.findById(dev)?.name } == "開発Dev" }

        // Move Android from 開発 to Projects, then back to the root.
        composeRule.onNodeWithTag("folder_row_$dev").performClick()
        awaitTag("folder_row_$android")
        composeRule.onNodeWithTag("folder_row_$android").performTouchInput { longClick() }
        awaitTag("folder_move_$android")
        composeRule.onNodeWithTag("folder_move_$android").performClick()
        awaitTag("folder_picker")
        composeRule.onAllNodesWithTag("folder_pick_$android").assertCountEquals(0)
        composeRule.onNodeWithTag("folder_pick_$projects").performClick()
        composeRule.waitUntil(5_000) { runBlocking { application.folderRepository.findById(android)?.parentFolderId } == projects }
        composeRule.onAllNodesWithTag("folder_row_$android").assertCountEquals(0)
        Espresso.pressBack()
        awaitTag("folder_row_$projects")
        composeRule.onNodeWithTag("folder_row_$projects").performClick()
        awaitTag("folder_row_$android")
        composeRule.onNodeWithTag("folder_row_$android").performTouchInput { longClick() }
        awaitTag("folder_move_$android")
        composeRule.onNodeWithTag("folder_move_$android").performClick()
        awaitTag("folder_pick_root")
        composeRule.onNodeWithTag("folder_pick_root").performClick()
        composeRule.waitUntil(5_000) { runBlocking { application.folderRepository.findById(android)?.parentFolderId } == null }
        Espresso.pressBack()
        awaitTag("folder_row_$android")

        // Delete 開発Dev: its memo comes up to the root; nothing is lost.
        composeRule.onNodeWithTag("folder_row_$dev").performTouchInput { longClick() }
        awaitTag("folder_delete_$dev")
        composeRule.onNodeWithTag("folder_delete_$dev").performClick()
        awaitTag("folder_delete_confirm")
        composeRule.onNodeWithTag("folder_delete_confirm").performClick()
        composeRule.waitUntil(5_000) { runBlocking { application.folderRepository.findById(dev) } == null }
        awaitTag("memo_card_$memo")
        assertNull(runBlocking { application.memoRepository.findById(memo)?.folderId })
        assertEquals("本文", runBlocking { application.memoRepository.findById(memo)?.body })
    }

    @Test
    fun aMemoAndAnOutlineAreFiledIntoAFolderAndBackToTheRootFromTheirHoldSheets() {
        val dev = insertFolder("開発", null)
        val memo = insert("仕様", "本文", MemoKind.MEMO)
        val outline = insert("計画", "- 一", MemoKind.OUTLINE)
        awaitTag("memo_card_$memo")

        composeRule.onNodeWithTag("memo_card_$memo").performTouchInput { longClick() }
        awaitTag("memo_move_folder_$memo")
        composeRule.onNodeWithTag("memo_move_folder_$memo").performClick()
        awaitTag("folder_pick_$dev")
        composeRule.onNodeWithTag("folder_pick_$dev").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("memo_card_$memo").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(dev, runBlocking { application.memoRepository.findById(memo)?.folderId })
        composeRule.onNodeWithTag("folder_row_$dev").performClick()
        awaitTag("memo_card_$memo")

        // The outline, on its own page, into the same folder — and back to the root.
        Espresso.pressBack()
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$outline")
        composeRule.onNodeWithTag("outline_card_$outline").performTouchInput { longClick() }
        awaitTag("memo_move_folder_$outline")
        composeRule.onNodeWithTag("memo_move_folder_$outline").performClick()
        awaitTag("folder_pick_$dev")
        composeRule.onNodeWithTag("folder_pick_$dev").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("outline_card_$outline").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("folder_row_$dev").performClick()
        awaitTag("outline_card_$outline")
        composeRule.onNodeWithTag("outline_card_$outline").performTouchInput { longClick() }
        awaitTag("memo_move_folder_$outline")
        composeRule.onNodeWithTag("memo_move_folder_$outline").performClick()
        awaitTag("folder_pick_root")
        composeRule.onNodeWithTag("folder_pick_root").performClick()
        composeRule.waitUntil(5_000) { runBlocking { application.memoRepository.findById(outline)?.folderId } == null }
        assertEquals("- 一", runBlocking { application.memoRepository.findById(outline)?.body })
    }

    @Test
    fun aMemoAndAnOutlineMadeInsideAFolderBelongToIt() {
        val dev = insertFolder("開発", null)
        awaitTag("folder_row_$dev")
        composeRule.onNodeWithTag("folder_row_$dev").performClick()
        awaitTag("folder_crumb_current")

        composeRule.onNodeWithTag("create_memo").performClick()
        awaitTag("memo_body")
        composeRule.onNodeWithTag("memo_body").performTextInput("フォルダの中のメモ")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("folder_crumb_current")
        val memo = runBlocking { application.memoRepository.observeStandaloneMemos("").first().single() }
        assertEquals(dev, memo.folderId)
        awaitTag("memo_card_${memo.id}")

        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("create_outline")
        composeRule.onNodeWithTag("create_outline").performClick()
        awaitTag("outliner_node_1")
        composeRule.onNodeWithTag("outliner_node_1").performTextInput("一")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("folder_crumb_current")
        val outline = runBlocking { application.memoRepository.observeOutlineDocuments("").first().single() }
        assertEquals(dev, outline.folderId)
        assertEquals("outline", outline.kind)
        awaitTag("outline_card_${outline.id}")

        // Neither is on the root page.
        Espresso.pressBack()
        awaitTag("folder_row_$dev")
        composeRule.onAllNodesWithTag("outline_card_${outline.id}").assertCountEquals(0)
    }

    @Test
    fun theSearchReachesTheWholeKindNotOnlyTheOpenFolder() {
        val dev = insertFolder("開発", null)
        val outline = insert("計画", "- 一", MemoKind.OUTLINE)
        awaitTag("folder_row_$dev")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$outline")
        composeRule.onNodeWithTag("folder_row_$dev").performClick()
        awaitTag("folder_empty_state")

        composeRule.onNodeWithTag("memo_search").performTextInput("計画")

        awaitTag("outline_card_$outline")
    }

    @Test
    fun everyCrumbSitsOnTheSameLineAsTheCurrentFolderName() {
        val dev = insertFolder("開発", null)
        val android = insertFolder("Android", dev)
        awaitTag("folder_row_$dev")
        composeRule.onNodeWithTag("folder_row_$dev").performClick()
        awaitTag("folder_row_$android")
        composeRule.onNodeWithTag("folder_row_$android").performClick()
        awaitTag("folder_crumb_$dev")

        // The tappable crumbs are 48dp tall, but their names must be drawn on the current
        // folder's line, not stuck to the top of the touch target.
        val current = composeRule.onNodeWithTag("folder_crumb_current").getBoundsInRoot()
        val currentCenter = (current.top + current.bottom) / 2
        listOf("folder_crumb_root_label", "folder_crumb_${dev}_label").forEach { tag ->
            val label = composeRule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()
            val labelCenter = (label.top + label.bottom) / 2
            assertTrue("$tag drawn at $labelCenter, current name at $currentCenter", abs((labelCenter - currentCenter).value) <= 1f)
        }
    }

    @Test
    fun aDeepPathKeepsTheCurrentFolderNameInView() {
        // Seven long names overflow the breadcrumb on any phone; the trail must have scrolled
        // to its end so the folder the reader is standing in is the one they can see.
        var parent: Long? = null
        repeat(7) { depth -> parent = insertFolder("とても長いフォルダ名の階層 ${depth + 1}", parent) }
        (1..7).forEach { depth ->
            val id = folderId("とても長いフォルダ名の階層 $depth")
            awaitTag("folder_row_$id")
            composeRule.onNodeWithTag("folder_row_$id").performClick()
        }
        awaitTag("folder_crumb_current")

        composeRule.onNodeWithTag("folder_crumb_current").assertIsDisplayed()
    }

    @Test
    fun selectedMemosAndOutlinesAreFiledTogetherFromTheSelectionMenu() {
        // フォルダへ移動 on the selection's ⋮: every selected card goes to the picked folder at once —
        // memos on the メモ page, outlines on the アウトライナー page, the same menu and the same picker.
        val dev = insertFolder("開発", null)
        val memoA = insert("仕様", "本文", MemoKind.MEMO)
        val memoB = insert("議事", "本文", MemoKind.MEMO)
        val outline = insert("計画", "- 一", MemoKind.OUTLINE)
        awaitTag("memo_card_$memoA")

        composeRule.onNodeWithTag("memo_card_$memoA").performTouchInput { longClick() }
        awaitTag("memo_select_$memoA")
        composeRule.onNodeWithTag("memo_select_$memoA").performClick()
        awaitTag("memo_bulk_menu")
        composeRule.onNodeWithTag("memo_card_$memoB").performClick()
        composeRule.onNodeWithTag("memo_bulk_menu").performClick()
        awaitTag("bulk_move_folder")
        composeRule.onNodeWithTag("bulk_move_folder").performClick()
        awaitTag("folder_pick_$dev")
        composeRule.onNodeWithTag("folder_pick_$dev").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking {
                application.database.memoDao().findById(memoA)?.folderId == dev &&
                    application.database.memoDao().findById(memoB)?.folderId == dev
            }
        }
        // The selection is over once the cards have gone to their folder.
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("memo_bulk_menu").fetchSemanticsNodes().isEmpty() }

        // The outliner page: the same door.
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$outline")
        composeRule.onNodeWithTag("outline_card_$outline").performTouchInput { longClick() }
        awaitTag("memo_select_$outline")
        composeRule.onNodeWithTag("memo_select_$outline").performClick()
        awaitTag("memo_bulk_menu")
        composeRule.onNodeWithTag("memo_bulk_menu").performClick()
        awaitTag("bulk_move_folder")
        composeRule.onNodeWithTag("bulk_move_folder").performClick()
        awaitTag("folder_pick_$dev")
        composeRule.onNodeWithTag("folder_pick_$dev").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { application.database.memoDao().findById(outline)?.folderId } == dev
        }
    }

    private fun createFolderFromMenu(name: String) {
        composeRule.onNodeWithTag("memo_overflow").performClick()
        awaitTag("overflow_new_folder")
        composeRule.onNodeWithTag("overflow_new_folder").performClick()
        awaitTag("folder_name_input")
        composeRule.onNodeWithTag("folder_name_input").performTextInput(name)
        composeRule.onNodeWithTag("folder_name_confirm").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { application.folderRepository.observeFolders().first() }.any { it.name == name }
        }
    }

    private fun folderId(name: String): Long =
        runBlocking { application.folderRepository.observeFolders().first() }.first { it.name == name }.id

    private fun insertFolder(name: String, parent: Long?): Long = runBlocking {
        application.database.folderDao().insert(
            FolderEntity(name = name, parentFolderId = parent, createdAt = 1, updatedAt = 1),
        )
    }

    private fun insert(title: String, body: String, kind: MemoKind, folderId: Long? = null): Long = runBlocking {
        application.database.memoDao().insert(
            MemoEntity(
                title = title, body = body, createdAt = 1_783_000_000_000L, updatedAt = 1_783_000_000_000L,
                kind = kind.storageId, folderId = folderId,
            ),
        )
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

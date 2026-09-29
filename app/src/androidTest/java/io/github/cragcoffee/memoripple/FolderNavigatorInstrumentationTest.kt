package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.FolderEntity
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
 * The folder navigator (docs/FOLDER_NAVIGATOR_AUDIT.md): the wall's drawer shows the folder
 * tree as rows that expand and collapse, indented by depth, the open folder marked; a tap on a
 * row shows that folder's documents on the wall. Folders are where documents are kept — the
 * outline inside a document never appears here.
 */
@OptIn(ExperimentalTestApi::class)
class FolderNavigatorInstrumentationTest {
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
            application.settingsRepository.setAutoPlayOnLaunch(false)
        }
    }

    @After
    fun releaseTheClock() {
        composeRule.mainClock.autoAdvance = true
    }

    private data class Sample(val dev: Long, val memoRipple: Long, val ai: Long, val calendar: Long, val launcher: Long, val art: Long, val personal: Long)

    // 開発 / MemoRipple / {AI設計, Calendar設計}; 開発 / CharacterLauncher; 創作; 個人
    private fun seedSample(): Sample {
        val dev = insertFolder("開発", null)
        val memoRipple = insertFolder("MemoRipple", dev)
        return Sample(
            dev = dev,
            memoRipple = memoRipple,
            ai = insertFolder("AI設計", memoRipple),
            calendar = insertFolder("Calendar設計", memoRipple),
            launcher = insertFolder("CharacterLauncher", dev),
            art = insertFolder("創作", null),
            personal = insertFolder("個人", null),
        )
    }

    @Test
    fun rootsShowThenAChildAndAGrandchildExpandIndentedAndCollapseHidesThem() {
        val s = seedSample()
        openNavigator()
        // Roots only, and only the ones with children carry a chevron.
        listOf(s.dev, s.art, s.personal).forEach { awaitTag("navigator_folder_$it") }
        assertTrue(composeRule.onAllNodesWithTag("navigator_folder_${s.memoRipple}").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("navigator_toggle_${s.dev}").assertContentDescriptionContains("展開", substring = true)
        assertTrue(composeRule.onAllNodesWithTag("navigator_toggle_${s.art}").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag("navigator_toggle_${s.dev}").performClick()
        awaitTag("navigator_folder_${s.memoRipple}")
        composeRule.onNodeWithTag("navigator_folder_${s.launcher}").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("navigator_folder_${s.ai}").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("navigator_toggle_${s.dev}").assertContentDescriptionContains("折りたたむ", substring = true)

        composeRule.onNodeWithTag("navigator_toggle_${s.memoRipple}").performClick()
        awaitTag("navigator_folder_${s.ai}")
        composeRule.onNodeWithTag("navigator_folder_${s.calendar}").assertIsDisplayed()
        // Depth is indentation: root < child < grandchild.
        val rootLeft = composeRule.onNodeWithTag("navigator_folder_${s.dev}").getUnclippedBoundsInRoot().left
        val childLeft = composeRule.onNodeWithTag("navigator_folder_${s.memoRipple}").getUnclippedBoundsInRoot().left
        val grandLeft = composeRule.onNodeWithTag("navigator_folder_${s.ai}").getUnclippedBoundsInRoot().left
        assertTrue("$rootLeft < $childLeft < $grandLeft", rootLeft < childLeft && childLeft < grandLeft)

        // Collapsing 開発 hides the child and the grandchild together.
        composeRule.onNodeWithTag("navigator_toggle_${s.dev}").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("navigator_folder_${s.memoRipple}").fetchSemanticsNodes().isEmpty() }
        assertTrue(composeRule.onAllNodesWithTag("navigator_folder_${s.ai}").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("navigator_folder_${s.art}").assertIsDisplayed()
    }

    @Test
    fun aTapShowsThatFolderOnTheWallMarksItSelectedAndASiblingTapSwitches() {
        val s = seedSample()
        val inAi = insert("AI設計のメモ", "本文", MemoKind.MEMO, s.ai)
        val inCalendar = insert("Calendar設計のメモ", "本文", MemoKind.MEMO, s.calendar)
        openNavigator()
        awaitTag("navigator_folder_${s.dev}")
        composeRule.onNodeWithTag("navigator_toggle_${s.dev}").performClick()
        awaitTag("navigator_toggle_${s.memoRipple}")
        composeRule.onNodeWithTag("navigator_toggle_${s.memoRipple}").performClick()
        awaitTag("navigator_folder_${s.ai}")
        composeRule.onNodeWithTag("navigator_folder_${s.ai}").performClick()

        // The drawer closes and the wall stands in AI設計: its memo, its crumb.
        awaitNavigatorClosed()
        awaitTag("memo_card_$inAi")
        composeRule.onNodeWithTag("folder_crumb_current").assertTextContains("AI設計", substring = true)
        assertTrue(composeRule.onAllNodesWithTag("memo_card_$inCalendar").fetchSemanticsNodes().isEmpty())

        // Reopened, the navigator still shows the path and marks the open folder.
        openNavigator()
        awaitTag("navigator_folder_${s.ai}")
        composeRule.onNodeWithTag("navigator_folder_${s.ai}").assertIsSelected()
        composeRule.onNodeWithTag("navigator_folder_${s.calendar}").assertIsNotSelected()
        composeRule.onNodeWithTag("navigator_folder_${s.calendar}").performClick()
        awaitTag("memo_card_$inCalendar")
        composeRule.onNodeWithTag("folder_crumb_current").assertTextContains("Calendar設計", substring = true)
        assertTrue(composeRule.onAllNodesWithTag("memo_card_$inAi").fetchSemanticsNodes().isEmpty())

        // すべて takes the wall back to the root, where the root folders are cards again.
        openNavigator()
        awaitTag("navigator_root")
        composeRule.onNodeWithTag("navigator_root").performClick()
        awaitTag("folder_row_${s.dev}")
        assertTrue(composeRule.onAllNodesWithTag("folder_crumb_current").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun anEmptyFolderAndAFiveDeepChainStayUsable() {
        val a = insertFolder("一", null)
        val b = insertFolder("二", a)
        val c = insertFolder("三", b)
        val d = insertFolder("四", c)
        val e = insertFolder("五", d)
        openNavigator()
        awaitTag("navigator_folder_$a")
        listOf(a, b, c, d).forEach { id ->
            composeRule.onNodeWithTag("memo_drawer_list").performScrollToNode(hasTestTag("navigator_toggle_$id"))
            composeRule.onNodeWithTag("navigator_toggle_$id").performClick()
        }
        composeRule.onNodeWithTag("memo_drawer_list").performScrollToNode(hasTestTag("navigator_folder_$e"))
        // The deepest name is still readable: the row keeps some width for it.
        val bounds = composeRule.onNodeWithTag("navigator_folder_$e").getUnclippedBoundsInRoot()
        assertTrue("row width ${bounds.right - bounds.left}", (bounds.right - bounds.left).value > 120f)
        composeRule.onNodeWithTag("navigator_folder_$e").performClick()
        awaitTag("folder_empty_state")
        composeRule.onNodeWithTag("folder_crumb_current").assertTextContains("五", substring = true)
    }

    @Test
    fun expansionAndTheOpenFolderSurviveRecreationAndBackClosesTheNavigatorBeforeClimbing() {
        val s = seedSample()
        openNavigator()
        awaitTag("navigator_folder_${s.dev}")
        composeRule.onNodeWithTag("navigator_toggle_${s.dev}").performClick()
        awaitTag("navigator_folder_${s.memoRipple}")
        composeRule.onNodeWithTag("navigator_folder_${s.memoRipple}").performClick()
        awaitTag("folder_crumb_current")
        composeRule.onNodeWithTag("folder_crumb_current").assertTextContains("MemoRipple", substring = true)

        composeRule.activityRule.scenario.recreate()
        awaitTag("folder_crumb_current")
        composeRule.onNodeWithTag("folder_crumb_current").assertTextContains("MemoRipple", substring = true)
        openNavigator()
        awaitTag("navigator_folder_${s.memoRipple}")
        composeRule.onNodeWithTag("navigator_folder_${s.memoRipple}").assertIsSelected()
        composeRule.onNodeWithTag("navigator_toggle_${s.dev}").assertContentDescriptionContains("折りたたむ", substring = true)

        // Back with the navigator open closes it and nothing else; the next Back climbs to 開発;
        // the one after that leaves the folders — the wall is at the root.
        Espresso.pressBack()
        awaitNavigatorClosed()
        composeRule.onNodeWithTag("folder_crumb_current").assertTextContains("MemoRipple", substring = true)
        Espresso.pressBack()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("folder_crumb_current").fetchSemanticsNodes()
                .any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { t: androidx.compose.ui.text.AnnotatedString -> t.text.contains("開発") } == true }
        }
        Espresso.pressBack()
        awaitTag("folder_row_${s.dev}")
        assertTrue(composeRule.onAllNodesWithTag("folder_crumb_current").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun renameMoveAndDeleteWithPromotionKeepTheTreeRight() {
        val s = seedSample()
        openNavigator()
        awaitTag("navigator_folder_${s.dev}")
        composeRule.onNodeWithTag("navigator_toggle_${s.dev}").performClick()
        awaitTag("navigator_folder_${s.memoRipple}")

        // Rename from the navigator row's hold sheet.
        composeRule.onNodeWithTag("navigator_folder_${s.memoRipple}").performTouchInput { longClick() }
        awaitTag("folder_rename_${s.memoRipple}")
        composeRule.onNodeWithTag("folder_rename_${s.memoRipple}").performClick()
        awaitTag("folder_name_input")
        composeRule.onNodeWithTag("folder_name_input").performTextInput("Ripple")
        composeRule.onNodeWithTag("folder_name_confirm").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("navigator_folder_${s.memoRipple}").fetchSemanticsNodes()
                .any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { t: androidx.compose.ui.text.AnnotatedString -> t.text.contains("Ripple") } == true }
        }

        // Move CharacterLauncher under 創作: it leaves 開発 and appears under 創作 once expanded.
        // (The hold closed the navigator for the sheet; open it again for the next hold.)
        openNavigator()
        awaitTag("navigator_folder_${s.launcher}")
        composeRule.onNodeWithTag("navigator_folder_${s.launcher}").performTouchInput { longClick() }
        awaitTag("folder_move_${s.launcher}")
        composeRule.onNodeWithTag("folder_move_${s.launcher}").performClick()
        awaitTag("folder_pick_${s.art}")
        composeRule.onNodeWithTag("folder_pick_${s.art}").performClick()
        composeRule.waitUntil(5_000) { runBlocking { application.database.folderDao().findById(s.launcher)?.parentFolderId } == s.art }
        openNavigator()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("navigator_toggle_${s.art}").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("navigator_toggle_${s.art}").performClick()
        awaitTag("navigator_folder_${s.launcher}")
        val artLeft = composeRule.onNodeWithTag("navigator_folder_${s.art}").getUnclippedBoundsInRoot().left
        val launcherLeft = composeRule.onNodeWithTag("navigator_folder_${s.launcher}").getUnclippedBoundsInRoot().left
        assertTrue(artLeft < launcherLeft)

        // Delete MemoRipple: AI設計 and Calendar設計 are promoted under 開発 and show there.
        openNavigator()
        awaitTag("navigator_folder_${s.memoRipple}")
        composeRule.onNodeWithTag("navigator_folder_${s.memoRipple}").performTouchInput { longClick() }
        awaitTag("folder_delete_${s.memoRipple}")
        composeRule.onNodeWithTag("folder_delete_${s.memoRipple}").performClick()
        awaitTag("folder_delete_confirm")
        composeRule.onNodeWithTag("folder_delete_confirm").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("navigator_folder_${s.memoRipple}").fetchSemanticsNodes().isEmpty() }
        openNavigator()
        awaitTag("navigator_folder_${s.ai}")
        composeRule.onNodeWithTag("navigator_folder_${s.calendar}").assertIsDisplayed()
        val devLeft = composeRule.onNodeWithTag("navigator_folder_${s.dev}").getUnclippedBoundsInRoot().left
        val aiLeft = composeRule.onNodeWithTag("navigator_folder_${s.ai}").getUnclippedBoundsInRoot().left
        assertTrue(devLeft < aiLeft)
        assertEquals(s.dev, runBlocking { application.database.folderDao().findById(s.ai)?.parentFolderId })
    }

    @Test
    fun threeHundredRootFoldersScrollInsideTheDrawer() {
        val ids = (1..300).map { insertFolder("フォルダ%03d".format(it), null) }
        openNavigator()
        awaitTag("navigator_folder_${ids.first()}")
        composeRule.onNodeWithTag("memo_drawer_list").performScrollToNode(hasTestTag("navigator_folder_${ids.last()}"))
        composeRule.onNodeWithTag("navigator_folder_${ids.last()}").assertIsDisplayed()
        composeRule.onNodeWithTag("navigator_folder_${ids.last()}").performClick()
        awaitTag("folder_crumb_current")
        composeRule.onNodeWithTag("folder_crumb_current").assertTextContains("フォルダ300", substring = true)
    }

    // The sheet stays composed while closed (translated off-screen), so "closed" is "not displayed".
    private fun awaitNavigatorClosed() {
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("memo_drawer").assertIsNotDisplayed() }.isSuccess
        }
    }

    private fun openNavigator() {
        composeRule.onNodeWithContentDescription("メモの整理と設定").performClick()
        awaitTag("memo_drawer")
    }

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

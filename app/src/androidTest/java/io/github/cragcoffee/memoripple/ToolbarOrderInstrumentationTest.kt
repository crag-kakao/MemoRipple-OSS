package io.github.cragcoffee.memoripple

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.domain.memos.EditorToolbarArrangement
import io.github.cragcoffee.memoripple.domain.memos.EditorToolbarItem
import io.github.cragcoffee.memoripple.domain.memos.EditorToolbarOrder
import io.github.cragcoffee.memoripple.domain.memos.EditorToolbarSurface
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** ショートカットバーの並び替え: the setting rearranges the bar, and the bar wears it. */
class ToolbarOrderInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun application(): MemoRippleApplication =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as MemoRippleApplication

    @Before
    fun resetBeforeTest() {
        runBlocking {
            application().database.clearAllTables()
            application().settingsRepository.resetToDefaults()
            application().settingsRepository.setAutoPlayOnLaunch(false)
            // Device-local, deliberately outside resetToDefaults — put back by hand.
            application().settingsRepository.setEditorToolbarOrder("")
            application().settingsRepository.setEditorToolbarNoteOrder("")
            application().settingsRepository.setEditorToolbarTwoRows(false)
        }
    }

    @org.junit.After
    fun leaveTheDeviceDefaultsBehind() {
        // Device-local, outside resetToDefaults: hand the original arrangements back so a
        // class running after this one meets the bar it expects.
        runBlocking {
            application().settingsRepository.setEditorToolbarOrder("")
            application().settingsRepository.setEditorToolbarNoteOrder("")
            application().settingsRepository.setEditorToolbarTwoRows(false)
        }
    }

    /** The memo bar as stored, read through the domain rather than by matching its text. */
    private fun memoArrangement(): EditorToolbarArrangement = EditorToolbarOrder.decodeArrangement(
        runBlocking { application().settingsRepository.editorToolbarOrder.first() },
        EditorToolbarSurface.MEMO,
    )

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theShortcutBarWearsTheArrangedOrder() {
        // コメントリンク moved to the very front; everything else keeps its place.
        runBlocking { application().settingsRepository.setEditorToolbarOrder("comment_link") }

        composeRule.onNodeWithTag("create_memo").performClick()
        awaitTag("memo_body")
        composeRule.onNodeWithTag("memo_body").performTextInput("並び")
        awaitTag("toolbar_comment_link")

        val commentLink = composeRule.onNodeWithTag("toolbar_comment_link")
            .getUnclippedBoundsInRoot()
        val photo = composeRule.onNodeWithTag("toolbar_add_photo").getUnclippedBoundsInRoot()
        assertTrue(commentLink.left < photo.left)
    }

    @Test
    fun theNoteBarKeepsItsOwnArrangementAndItsOwnTools() {
        // A memo arrangement must not reach the note bar; the note list shows note tools only.
        runBlocking {
            application().settingsRepository.setEditorToolbarOrder("comment_link")
            application().settingsRepository.setEditorToolbarNoteOrder("template")
        }
        val (noteId, episodeId) = runBlocking {
            val now = System.currentTimeMillis()
            val note = application().database.noteDao().insert(
                io.github.cragcoffee.memoripple.data.NoteEntity(
                    title = "並びの本",
                    coverColor = "plum",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val memoId = application().database.memoDao().insert(
                io.github.cragcoffee.memoripple.data.MemoEntity(
                    title = "第一話",
                    body = "本文。",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            application().database.noteDao().placeEpisode(memoId, note, null, 0)
            note to memoId
        }

        // The note settings list carries note tools only.
        if (composeRule.onAllNodesWithTag("memo_top_menu").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("memo_top_menu").performClick()
            composeRule.onNodeWithTag("memo_open_settings").performClick()
        } else {
            composeRule.onNodeWithContentDescription("設定").performClick()
        }
        awaitTag("settings_list")
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_toolbar_order_note"))
        composeRule.onNodeWithTag("setting_toolbar_order_note").performClick()
        awaitTag("toolbar_order_row_template")
        assertTrue(
            composeRule.onAllNodesWithTag("toolbar_order_row_task")
                .fetchSemanticsNodes().isEmpty(),
        )
        val template = composeRule.onNodeWithTag("toolbar_order_row_template")
            .getUnclippedBoundsInRoot()
        val photo = composeRule.onNodeWithTag("toolbar_order_row_photo")
            .getUnclippedBoundsInRoot()
        assertTrue(template.top < photo.top)

        // And the episode's bar wears the note arrangement, not the memo one.
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("memo_view_note")
        composeRule.onNodeWithTag("memo_view_note").performClick()
        awaitTag("toggle_note_$noteId")
        composeRule.onNodeWithTag("toggle_note_$noteId").performClick()
        awaitTag("episode_row_$episodeId")
        composeRule.onNodeWithTag("episode_row_$episodeId").performClick()
        awaitTag("reader_edit")
        composeRule.onNodeWithTag("reader_edit").performClick()
        awaitTag("memo_body")
        composeRule.onNodeWithTag("memo_body").performTextInput("追記")
        awaitTag("toolbar_template")
        val templateButton = composeRule.onNodeWithTag("toolbar_template")
            .getUnclippedBoundsInRoot()
        val photoButton = composeRule.onNodeWithTag("toolbar_add_photo")
            .getUnclippedBoundsInRoot()
        assertTrue(templateButton.left < photoButton.left)
    }

    @Test
    fun theSettingsListRearrangesByDragAndComesBackWhole() {
        if (composeRule.onAllNodesWithTag("memo_top_menu").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("memo_top_menu").performClick()
            composeRule.onNodeWithTag("memo_open_settings").performClick()
        } else {
            composeRule.onNodeWithContentDescription("設定").performClick()
        }
        awaitTag("settings_list")
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_toolbar_order_memo"))
        composeRule.onNodeWithTag("setting_toolbar_order_memo").performClick()
        awaitTag("toolbar_order_row_photo")

        // 元に戻す・やり直し rides its handle above 写真を追加.
        composeRule.onNodeWithTag("toolbar_order_handle_history", useUnmergedTree = true)
            .performTouchInput {
            swipe(
                start = center,
                end = Offset(center.x, center.y - 400f),
                durationMillis = 400,
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val photo = composeRule.onNodeWithTag("toolbar_order_row_photo")
                .getUnclippedBoundsInRoot()
            val history = composeRule.onNodeWithTag("toolbar_order_row_history")
                .getUnclippedBoundsInRoot()
            history.top < photo.top
        }

        // The arrangement is persisted, not merely drawn — the write lands asynchronously
        // after the drag ends, so it is awaited rather than read once.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            memoArrangement().order.first() == EditorToolbarItem.HISTORY
        }

        // One press hands the original order back.
        composeRule.onNodeWithTag("toolbar_order_reset").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val photo = composeRule.onNodeWithTag("toolbar_order_row_photo")
                .getUnclippedBoundsInRoot()
            val history = composeRule.onNodeWithTag("toolbar_order_row_history")
                .getUnclippedBoundsInRoot()
            photo.top < history.top
        }
    }

    @Test
    fun theTwoRowBarNamesItsRowsAndAUnitCrossesBetweenThem() {
        runBlocking { application().settingsRepository.setEditorToolbarTwoRows(true) }
        if (composeRule.onAllNodesWithTag("memo_top_menu").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("memo_top_menu").performClick()
            composeRule.onNodeWithTag("memo_open_settings").performClick()
        } else {
            composeRule.onNodeWithContentDescription("設定").performClick()
        }
        awaitTag("settings_list")
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_toolbar_order_memo"))
        composeRule.onNodeWithTag("setting_toolbar_order_memo").performClick()
        awaitTag("toolbar_order_heading_upper")

        // The rows are named, and start where the bar has always drawn them: the writing
        // aids above the divider, the outline machinery below it.
        val upperHeading = composeRule.onNodeWithTag("toolbar_order_heading_upper")
            .getUnclippedBoundsInRoot()
        val lowerHeading = composeRule.onNodeWithTag("toolbar_order_heading_lower")
            .getUnclippedBoundsInRoot()
        val photo = composeRule.onNodeWithTag("toolbar_order_row_photo")
            .getUnclippedBoundsInRoot()
        assertTrue(upperHeading.top < photo.top)
        assertTrue(photo.top < lowerHeading.top)
        // チェックボックス rides in the lower row, so it stands under that heading — it lives
        // below the fold, so the list is scrolled to it before either is measured.
        composeRule.onNodeWithTag("toolbar_order_list")
            .performScrollToNode(hasTestTag("toolbar_order_row_task"))
        assertTrue(
            composeRule.onNodeWithTag("toolbar_order_heading_lower")
                .getUnclippedBoundsInRoot().top <
                composeRule.onNodeWithTag("toolbar_order_row_task")
                    .getUnclippedBoundsInRoot().top,
        )

        // 流れ方 stands last in the upper row; one pull past the divider hands it to the lower.
        composeRule.onNodeWithTag("toolbar_order_list")
            .performScrollToNode(hasTestTag("toolbar_order_row_flow_marks"))
        composeRule.onNodeWithTag("toolbar_order_handle_flow_marks", useUnmergedTree = true)
            .performTouchInput {
                swipe(
                    start = center,
                    end = Offset(center.x, center.y + 200f),
                    durationMillis = 400,
                )
            }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            memoArrangement().isLower(EditorToolbarItem.FLOW_MODIFIERS)
        }

        // The bar itself now flies it in the lower row, beneath 太字.
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("create_memo")
        composeRule.onNodeWithTag("create_memo").performClick()
        awaitTag("memo_body")
        composeRule.onNodeWithTag("memo_body").performTextInput("二段")
        awaitTag("toolbar_flow_left")
        // The bar redraws when the stored arrangement reaches it, so the rows are awaited
        // rather than read the instant the chips appear.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onNodeWithTag("toolbar_bold").getUnclippedBoundsInRoot().top <
                composeRule.onNodeWithTag("toolbar_flow_left").getUnclippedBoundsInRoot().top
        }
    }

    @Test
    fun aHiddenToolLeavesTheBarAndComesBackToItsOwnPlace() {
        if (composeRule.onAllNodesWithTag("memo_top_menu").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("memo_top_menu").performClick()
            composeRule.onNodeWithTag("memo_open_settings").performClick()
        } else {
            composeRule.onNodeWithContentDescription("設定").performClick()
        }
        awaitTag("settings_list")
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_toolbar_order_memo"))
        composeRule.onNodeWithTag("setting_toolbar_order_memo").performClick()
        awaitTag("toolbar_order_hide_highlight")

        composeRule.onNodeWithTag("toolbar_order_hide_highlight").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            memoArrangement().isHidden(EditorToolbarItem.HIGHLIGHT)
        }

        // The bar itself drops it, and its neighbours stay.
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("create_memo")
        composeRule.onNodeWithTag("create_memo").performClick()
        awaitTag("memo_body")
        composeRule.onNodeWithTag("memo_body").performTextInput("隠す")
        awaitTag("toolbar_bold")
        assertTrue(
            composeRule.onAllNodesWithTag("toolbar_highlight").fetchSemanticsNodes().isEmpty(),
        )

        // Shown again, it returns where it stood: still right after 太字.
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("create_memo")
        if (composeRule.onAllNodesWithTag("memo_top_menu").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("memo_top_menu").performClick()
            composeRule.onNodeWithTag("memo_open_settings").performClick()
        } else {
            composeRule.onNodeWithContentDescription("設定").performClick()
        }
        awaitTag("settings_list")
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_toolbar_order_memo"))
        composeRule.onNodeWithTag("setting_toolbar_order_memo").performClick()
        awaitTag("toolbar_order_hide_highlight")
        composeRule.onNodeWithTag("toolbar_order_hide_highlight").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            !memoArrangement().isHidden(EditorToolbarItem.HIGHLIGHT)
        }

        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("create_memo")
        composeRule.onNodeWithTag("create_memo").performClick()
        awaitTag("memo_body")
        composeRule.onNodeWithTag("memo_body").performTextInput("戻す")
        awaitTag("toolbar_highlight")
        val bold = composeRule.onNodeWithTag("toolbar_bold").getUnclippedBoundsInRoot()
        val highlight = composeRule.onNodeWithTag("toolbar_highlight").getUnclippedBoundsInRoot()
        assertTrue(bold.left < highlight.left)
    }
}

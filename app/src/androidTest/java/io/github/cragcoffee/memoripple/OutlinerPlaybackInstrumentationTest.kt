package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The outliner carries what the memo and note editors carry above and below the writing:
 * the ⋮ actions, the comments, コメントを流す playback, and 読み上げ — the same view model, the
 * same sheets, on the outline's own screen.
 */
@OptIn(ExperimentalTestApi::class)
class OutlinerPlaybackInstrumentationTest {
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
    fun theOutlinerCarriesTheActionsCommentsPlaybackAndSpeech() {
        val memoId = openOutliner("- 一\n- 二")

        // ⋮: the memo's own actions, starting with 上部に固定.
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        awaitTag("memo_editor_pin")
        composeRule.onNodeWithTag("memo_editor_pin").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { application.database.memoDao().findById(memoId)?.isPinned } == true
        }

        // Comments: the sheet opens from the bar's end and a comment is added to this memo.
        composeRule.onNodeWithTag("open_user_comments").assertIsEnabled().performClick()
        awaitTag("user_comment_input")
        composeRule.onNodeWithTag("user_comments_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("user_comment_input").performTextInput("新機能")
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("add_user_comment").assertIsEnabled() }.isSuccess
        }
        composeRule.onNodeWithTag("add_user_comment").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { application.memoCommentRepository.observeForMemo(memoId).first() }.size == 1
        }
        dismissCommentsSheet()

        // Playback: ▷ flows the comments over the outline; ■ ends it.
        awaitTag("play_work_comments")
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("play_work_comments").assertIsEnabled() }.isSuccess
        }
        // The clock is held, as the memo tests hold it: on a free-running test clock a flight
        // of seconds is over in a blink. ▷ first lets the keyboard fall, so frames are stepped
        // until the flight has begun.
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("play_work_comments").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onAllNodesWithTag("pause_work_comments").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("再生を終了").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.autoAdvance = true
        awaitTag("play_work_comments")

        // 読み上げ: the speaker opens the same dialog as the memo editor's.
        composeRule.onNodeWithTag("memo_speech_action").performClick()
        awaitTag("memo_speech_dialog")
        assertTrue(memoId > 0)
    }

    @Test
    fun theMenuOffersTheMemosOtherActionsAndReadingModeShowsTheOutlineAsAPage() {
        val memoId = openOutliner("- [ ] 一\n- 二")

        // ⋮ carries the memo's whole menu: the four from before and the six that were missing.
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        awaitTag("memo_editor_pin")
        listOf(
            "memo_editor_archive", "memo_editor_split", "memo_editor_reading_mode",
            "memo_editor_save_template", "memo_editor_copy_all", "memo_editor_share",
            "memo_editor_export", "memo_editor_export_pdf", "memo_editor_trash",
        ).forEach { tag -> composeRule.onNodeWithTag(tag).assertExists() }

        // 閲覧モード: the outline reads as the memo's page — the task box is there and tappable —
        // and 編集 returns to the lines.
        composeRule.onNodeWithTag("memo_editor_reading_mode").performClick()
        awaitTag("reading_task_0")
        composeRule.onAllNodesWithTag("outliner_node_1").assertCountEquals(0)
        composeRule.onNodeWithTag("reading_task_0").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { application.database.memoDao().findById(memoId)?.body?.startsWith("- [x] 一") } == true
        }
        composeRule.onNodeWithTag("reading_edit").performClick()
        awaitTag("outliner_node_1")

        // テンプレートとして保存 keeps the outline's body under the given name.
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        awaitTag("memo_editor_save_template")
        composeRule.onNodeWithTag("memo_editor_save_template").performClick()
        awaitTag("template_name_field")
        composeRule.onNodeWithTag("template_name_field").performTextInput("段取り")
        composeRule.onNodeWithTag("template_save_confirm").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { application.templateRepository.templates.first() }.any { it.body.startsWith("- [x] 一") }
        }

        // 分割表示 opens the same picker as the memo's.
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        awaitTag("memo_editor_split")
        composeRule.onNodeWithTag("memo_editor_split").performClick()
        awaitTag("split_reference_picker")
        assertTrue(memoId > 0)
    }

    @Test
    fun theMemoBarStandsWhereTheOutlinersDoes() {
        // The outliner's bar set the measure: its pinned コメント button ends the same distance
        // from the screen's edge on the memo editor.
        awaitTag("create_memo")
        composeRule.onNodeWithTag("create_memo").performClick()
        awaitTag("memo_body")
        composeRule.onNodeWithTag("memo_body").performClick()
        awaitTag("open_user_comments")
        val memoRight = composeRule.onNodeWithTag("open_user_comments").getUnclippedBoundsInRoot().right
        composeRule.onNodeWithContentDescription("戻る").performClick()
        openOutliner("- 一")
        awaitTag("open_user_comments")
        val outlinerRight = composeRule.onNodeWithTag("open_user_comments").getUnclippedBoundsInRoot().right
        assertTrue("memo bar ends at $memoRight, the outliner's at $outlinerRight", (memoRight - outlinerRight).value.let { kotlin.math.abs(it) } < 1f)
    }

    @Test
    fun readingModeKeepsTheEditButtonOnScreenAndThePageInItsGutters() {
        openOutliner("- [ ] 一\n- 二")
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        awaitTag("memo_editor_reading_mode")
        composeRule.onNodeWithTag("memo_editor_reading_mode").performClick()
        awaitTag("reading_task_0")
        // The outline's page draws 「・□」 like its lines; the memo's page keeps its plain box.
        composeRule.onNodeWithTag("reading_task_dot_0", useUnmergedTree = true).assertIsDisplayed()
        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        val edit = composeRule.onNodeWithTag("reading_edit").getUnclippedBoundsInRoot()
        // 編集 stands whole on the screen, clear of its edge.
        assertTrue("編集 ends at ${edit.right}, the screen at ${root.right}", edit.right <= root.right - 8.dp)
        // The page keeps the memo's gutters: a bullet is not pressed against the left edge.
        val firstLine = composeRule.onNodeWithTag("reading_task_0").getUnclippedBoundsInRoot()
        assertTrue("the page starts at ${firstLine.left}", firstLine.left - root.left >= 20.dp)
    }

    private fun dismissCommentsSheet() {
        repeat(2) {
            if (composeRule.onAllNodesWithTag("user_comments_sheet").fetchSemanticsNodes().isEmpty()) return
            androidx.test.espresso.Espresso.pressBack()
            runCatching {
                composeRule.waitUntil(3_000) {
                    composeRule.onAllNodesWithTag("user_comments_sheet").fetchSemanticsNodes().isEmpty()
                }
            }
        }
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodesWithTag("user_comments_sheet").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun openOutliner(body: String): Long {
        val memoId = runBlocking {
            application.database.memoDao().insert(
                MemoEntity(title = "", body = body, createdAt = 1, updatedAt = 1, kind = MemoKind.OUTLINE.storageId),
            )
        }
        awaitTag("memo_view_outliner")
        composeRule.onNodeWithTag("memo_view_outliner").performClick()
        awaitTag("outline_card_$memoId")
        composeRule.onNodeWithTag("outline_card_$memoId").performClick()
        awaitTag("outliner_node_1")
        return memoId
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

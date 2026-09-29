package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoCommentEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * コメントリンク end to end: assigning a number from the comment's held menu, the badge writing
 * its marker at the caret, the delete warning counting the body's markers, and — in 閲覧モード —
 * the reading voice flowing the linked comment as it passes the marker, in both the range-report
 * path and the no-range PIECES fallback.
 */
class CommentLinkInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun application(): MemoRippleApplication =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as MemoRippleApplication

    @Before
    fun resetBeforeTest() {
        DeterministicTextToSpeechGateway.emitRanges = true
        runBlocking {
            application().database.clearAllTables()
            application().settingsRepository.resetToDefaults()
            application().settingsRepository.setAutoPlayOnLaunch(false)
            // The device-local auto-detect flag sits outside resetToDefaults on purpose.
            application().settingsRepository.setSpeechRangeSupport("")
        }
    }

    @After
    fun restoreGateway() {
        DeterministicTextToSpeechGateway.emitRanges = true
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openMemo(memoId: Long) {
        awaitTag("memo_card_$memoId")
        composeRule.onNodeWithTag("memo_card_$memoId").performClick()
        awaitTag("memo_editor_more")
    }

    /** An existing memo opens reading by default; the editing flows step back to the pen. */
    private fun openMemoForEditing(memoId: Long) {
        openMemo(memoId)
        awaitTag("reading_edit")
        composeRule.onNodeWithTag("reading_edit").performClick()
        awaitTag("memo_body")
    }

    private fun enterReadingMode() {
        if (composeRule.onAllNodesWithTag("memo_reading_view").fetchSemanticsNodes().isNotEmpty()) {
            return
        }
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        composeRule.onNodeWithTag("memo_editor_reading_mode").performClick()
        awaitTag("memo_reading_view")
    }

    @Test
    fun linkIsAssignedReleasedReassignedAndTheBadgeWritesTheMarker() {
        val (memoId, commentId) = runBlocking {
            val now = System.currentTimeMillis()
            val id = application().database.memoDao().insert(
                MemoEntity(title = "連携", body = "本文です。", createdAt = now, updatedAt = now),
            )
            val comment = application().database.memoCommentDao().insert(
                MemoCommentEntity(memoId = id, text = "ここ好き", createdAt = now),
            )
            id to comment
        }
        openMemoForEditing(memoId)
        composeRule.onNodeWithTag("open_user_comments").performClick()
        awaitTag("user_comment_row_$commentId")

        // Holding the comment offers コメントリンク; taking it hangs the R1 badge on the row.
        composeRule.onNodeWithTag("user_comment_row_$commentId").performTouchInput { longClick() }
        awaitTag("comment_action_link")
        composeRule.onNodeWithTag("comment_action_link").performClick()
        awaitTag("comment_link_badge_$commentId")
        composeRule.onNodeWithTag("comment_link_badge_$commentId")
            .assertTextContains("R1")

        // A linked comment offers the release instead; releasing takes the badge away.
        composeRule.onNodeWithTag("user_comment_row_$commentId").performTouchInput { longClick() }
        awaitTag("comment_action_unlink")
        composeRule.onNodeWithTag("comment_action_unlink").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("comment_link_badge_$commentId")
                .fetchSemanticsNodes().isEmpty()
        }

        // Linking again numbers from the memo's live maximum — nothing left, so R1 again.
        composeRule.onNodeWithTag("user_comment_row_$commentId").performTouchInput { longClick() }
        awaitTag("comment_action_link")
        composeRule.onNodeWithTag("comment_action_link").performClick()
        awaitTag("comment_link_badge_$commentId")

        // The badge is the door into the body: tapping it closes the sheet and writes the
        // marker where the caret stands.
        composeRule.onNodeWithTag("comment_link_badge_$commentId", useUnmergedTree = true)
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("user_comments_sheet").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("memo_body").assertTextContains("[R1]", substring = true)
    }

    @Test
    fun deletingALinkedCommentWarnsAboutItsMarkersAndLeavesThemStanding() {
        val (memoId, commentId) = runBlocking {
            val now = System.currentTimeMillis()
            val id = application().database.memoDao().insert(
                MemoEntity(
                    title = "警告",
                    body = "前半[R1]と後半[R1]。",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val comment = application().database.memoCommentDao().insert(
                MemoCommentEntity(memoId = id, text = "消える声", createdAt = now, linkNo = 1),
            )
            id to comment
        }
        openMemoForEditing(memoId)
        composeRule.onNodeWithTag("open_user_comments").performClick()
        awaitText("消える声")

        composeRule.onNodeWithText("消える声").performTouchInput { longClick() }
        awaitTag("comment_action_delete")
        composeRule.onNodeWithTag("comment_action_delete").performClick()
        awaitTag("comment_delete_link_warning")
        composeRule.onNodeWithTag("comment_delete_link_warning")
            .assertTextContains("2箇所", substring = true)
        composeRule.onNodeWithText("削除").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("消える声").fetchSemanticsNodes().isEmpty()
        }

        // The body keeps its markers; only the comment is gone.
        composeRule.onNodeWithTag("memo_body").assertTextContains("[R1]", substring = true)
    }

    @Test
    fun readingModeHidesTheMarkerAndSpeechFlowsTheLinkedCommentPast() {
        val memoId = seedLinkedMemo()
        openMemo(memoId)
        enterReadingMode()

        // The marker string never reaches the page.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("[R1]", substring = true)
                .fetchSemanticsNodes().isEmpty()
        }

        // The deterministic engine reports every character; passing the marker's offset
        // fires the linked comment onto the stage.
        startSpeechAndAwaitFlowingComment()
    }

    @Test
    fun theNoRangeFallbackCutsAtTheMarkerAndStillFires() {
        // A device that never reports ranges: the flag remembers, the engine stays silent
        // about positions, and the body is spoken in pieces cut at the markers.
        DeterministicTextToSpeechGateway.emitRanges = false
        runBlocking { application().settingsRepository.setSpeechRangeSupport("no") }

        val memoId = seedLinkedMemo()
        openMemo(memoId)
        enterReadingMode()

        startSpeechAndAwaitFlowingComment()
    }

    /**
     * Starts the reading and steps the frozen clock frame by frame until the comment stream
     * reports itself playing — the flowing layer clears its own semantics, so the transport
     * control turning into 一時停止 is what says the linked comment is on the stage. The clock
     * is held and walked by hand, the way the playback tests drive it, because free-running
     * test time races past the whole flight between polls.
     */
    private fun startSpeechAndAwaitFlowingComment() {
        composeRule.onNodeWithTag("memo_speech_action").performClick()
        awaitTag("start_memo_speech")
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithTag("start_memo_speech").performClick()
            var playing = false
            repeat(120) {
                if (!playing) {
                    composeRule.mainClock.advanceTimeByFrame()
                    playing = composeRule.onAllNodesWithTag("pause_work_comments")
                        .fetchSemanticsNodes().isNotEmpty()
                }
            }
            org.junit.Assert.assertTrue("linked comment never flowed", playing)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun anExistingMemoOpensReadingAndADoubleTapReturnsThePen() {
        val memoId = runBlocking {
            val now = System.currentTimeMillis()
            application().database.memoDao().insert(
                MemoEntity(title = "既読", body = "開いたら読む。", createdAt = now, updatedAt = now),
            )
        }
        openMemo(memoId)

        // Not the editor: the page. The pen is a double tap away.
        awaitTag("memo_reading_view")
        org.junit.Assert.assertTrue(
            composeRule.onAllNodesWithTag("memo_body").fetchSemanticsNodes().isEmpty(),
        )

        // The ⋮ menu names the door out, not the room it is in.
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        awaitText("編集モード")
        androidx.test.espresso.Espresso.pressBack()

        composeRule.onNodeWithTag("memo_reading_view").performTouchInput { doubleClick() }
        awaitTag("memo_body")
    }

    @Test
    fun theToolbarPickerLinksACommentAndWritesItsMarker() {
        val (memoId, commentId) = runBlocking {
            val now = System.currentTimeMillis()
            val id = application().database.memoDao().insert(
                MemoEntity(title = "差込", body = "本文のことば。", createdAt = now, updatedAt = now),
            )
            val comment = application().database.memoCommentDao().insert(
                MemoCommentEntity(memoId = id, text = "差し込む声", createdAt = now),
            )
            id to comment
        }
        openMemoForEditing(memoId)
        composeRule.onNodeWithTag("memo_body").performClick()
        awaitTag("toolbar_comment_link")
        composeRule.onNodeWithTag("toolbar_comment_link").performScrollTo().performClick()
        awaitTag("comment_link_pick_$commentId")
        composeRule.onNodeWithTag("comment_link_pick_$commentId").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("memo_body")
                    .assertTextContains("[R1]", substring = true)
            }.isSuccess
        }
    }

    @Test
    fun readingAloudFollowsTheSpokenLine() {
        val memoId = runBlocking {
            val now = System.currentTimeMillis()
            application().database.memoDao().insert(
                MemoEntity(
                    title = "追従",
                    body = "一行目のことば。\n二行目のことば。",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        openMemo(memoId)
        enterReadingMode()

        composeRule.onNodeWithTag("memo_speech_action").performClick()
        awaitTag("start_memo_speech")
        composeRule.onNodeWithTag("start_memo_speech").performClick()
        // The deterministic engine reports every character up front, so the cursor lands on
        // the last spoken line and stays there while the voice holds the floor.
        awaitTag("reading_follow_1")
    }

    @Test
    fun blankLinesInTheSourceKeepTheirAirOnTheReadingPage() {
        val memoId = runBlocking {
            val now = System.currentTimeMillis()
            application().database.memoDao().insert(
                MemoEntity(
                    title = "空白",
                    body = "一つ目。\n\n\n二つ目。",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        openMemo(memoId)
        awaitTag("memo_reading_view")

        // Two blank lines stood before the fourth source line; the page keeps that much air.
        composeRule.onNodeWithTag("reading_air_3", useUnmergedTree = true)
            .assertHeightIsAtLeast(40.dp)
    }

    @Test
    fun aDoubleTapDuringPlaybackStaysOnThePage() {
        val memoId = seedLinkedMemo()
        openMemo(memoId)
        enterReadingMode()

        // The voice takes the floor and — with the deterministic engine — never yields it.
        composeRule.onNodeWithTag("memo_speech_action").performClick()
        awaitTag("start_memo_speech")
        composeRule.onNodeWithTag("start_memo_speech").performClick()
        composeRule.waitForIdle()

        // A double tap now belongs to the playback, not to the pen.
        composeRule.onNodeWithTag("memo_reading_view").performTouchInput { doubleClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("memo_reading_view").assertExists()
        org.junit.Assert.assertTrue(
            composeRule.onAllNodesWithTag("memo_body").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun tappingALineWhileTheVoiceReadsJumpsToANewPass() {
        val memoId = runBlocking {
            val now = System.currentTimeMillis()
            val id = application().database.memoDao().insert(
                MemoEntity(
                    title = "跳ぶ",
                    body = "頭のことば。\n[R1]あとのことば。",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            application().database.memoCommentDao().insert(
                MemoCommentEntity(memoId = id, text = "跳んだ声", createdAt = now, linkNo = 1),
            )
            id
        }
        openMemo(memoId)
        enterReadingMode()

        composeRule.onNodeWithTag("memo_speech_action").performClick()
        awaitTag("start_memo_speech")
        // Free-running test time races past a whole flight between polls, so the clock is
        // held and walked by hand around each firing, the way the other playback tests do.
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithTag("start_memo_speech").performClick()
            var flowing = false
            repeat(120) {
                if (!flowing) {
                    composeRule.mainClock.advanceTimeByFrame()
                    flowing = composeRule.onAllNodesWithTag("pause_work_comments")
                        .fetchSemanticsNodes().isNotEmpty()
                }
            }
            org.junit.Assert.assertTrue("first pass never fired", flowing)

            // Let the flight finish so the stage is free again.
            composeRule.mainClock.autoAdvance = true
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodesWithTag("play_work_comments")
                    .fetchSemanticsNodes().isNotEmpty()
            }

            // A tap on the marker's line while the voice is engaged is a fresh pass: the
            // marker fires again — and the page never falls out of 閲覧モード.
            composeRule.mainClock.autoAdvance = false
            composeRule.onNodeWithText("あとのことば。").performClick()
            var refired = false
            repeat(120) {
                if (!refired) {
                    composeRule.mainClock.advanceTimeByFrame()
                    refired = composeRule.onAllNodesWithTag("pause_work_comments")
                        .fetchSemanticsNodes().isNotEmpty()
                }
            }
            org.junit.Assert.assertTrue("the jump never fired the marker again", refired)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.onNodeWithTag("memo_reading_view").assertExists()
        org.junit.Assert.assertTrue(
            composeRule.onAllNodesWithTag("memo_body").fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun seedLinkedMemo(): Long = runBlocking {
        val now = System.currentTimeMillis()
        val id = application().database.memoDao().insert(
            MemoEntity(
                title = "読みながら",
                body = "序文のことば。[R1]あとがきのことば。",
                createdAt = now,
                updatedAt = now,
            ),
        )
        application().database.memoCommentDao().insert(
            MemoCommentEntity(memoId = id, text = "流れる声", createdAt = now, linkNo = 1),
        )
        id
    }
}

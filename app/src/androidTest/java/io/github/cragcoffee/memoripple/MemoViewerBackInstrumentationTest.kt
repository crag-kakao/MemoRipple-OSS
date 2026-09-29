package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.speech.SpeechStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 閲覧モードの戻る連打: leaving a memo is asked for twice — a second tap on 戻る, or a second
 * system Back — while the editor is still on screen fading out. The first request has
 * already popped the editor; the second must find nothing left to pop, and the wall
 * beneath must still be standing. Before the guard, the second request popped the wall
 * itself and left the app with an empty navigation host.
 *
 * The exit transition is frozen deliberately (`mainClock.autoAdvance = false`) so the second
 * request lands in exactly the window a fast thumb reaches on a device: the editor still
 * composed, its 戻る still tappable, its BackHandler still registered.
 */
class MemoViewerBackInstrumentationTest {
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
        application.speechController.stop()
    }

    @Test
    fun aSecondTapOnBackDuringTheExitLeavesTheWallStanding() {
        val memoId = openMemoInReadingMode()
        leaveOnceAndFreezeTheExit(memoId)

        composeRule.onNodeWithContentDescription("戻る").performClick()

        assertWallStandsAlone(memoId)
    }

    @Test
    fun threeTapsOnBackDuringTheExitLeaveTheWallStanding() {
        val memoId = openMemoInReadingMode()
        leaveOnceAndFreezeTheExit(memoId)

        repeat(2) { composeRule.onNodeWithContentDescription("戻る").performClick() }

        assertWallStandsAlone(memoId)
    }

    @Test
    fun oneSystemBackFromReadingModeReturnsToTheWall() {
        val memoId = openMemoInReadingMode()

        Espresso.pressBack()

        assertWallStandsAlone(memoId)
    }

    @Test
    fun aSecondTapOnBackAfterASystemBackLeavesTheWallStanding() {
        val memoId = openMemoInReadingMode()
        // The system Back takes the editor's BackHandler route through the same save-then-pop;
        // the thumb then lands on the fading 戻る. (A second *system* Back in that window is
        // different: the popped editor's handler is lifecycle-disabled, so it falls through
        // to the Activity and closes the app — platform behaviour, not pinned here.)
        composeRule.mainClock.autoAdvance = false
        Espresso.pressBack()
        awaitWallWhileFrozen(memoId)
        composeRule.onAllNodesWithContentDescription("戻る").assertCountEquals(1)

        composeRule.onNodeWithContentDescription("戻る").performClick()

        assertWallStandsAlone(memoId)
    }

    @Test
    fun aDoubleBackWhileTheVoiceReadsStopsItAndLeavesTheWallStanding() {
        val memoId = openMemoInReadingMode()
        composeRule.onNodeWithTag("memo_speech_action").performClick()
        composeRule.onNodeWithTag("start_memo_speech").performClick()
        composeRule.waitUntil(5_000) {
            application.speechController.state.value.status == SpeechStatus.SPEAKING
        }

        leaveOnceAndFreezeTheExit(memoId)
        composeRule.onNodeWithContentDescription("戻る").performClick()

        assertWallStandsAlone(memoId)
        assertEquals(SpeechStatus.IDLE, application.speechController.state.value.status)
    }

    private fun openMemoInReadingMode(): Long {
        val memoId = runBlocking {
            application.database.memoDao().insert(
                MemoEntity(
                    title = "戻る連打",
                    body = "一行目\n二行目\n三行目",
                    createdAt = 1_783_000_000_000L,
                    updatedAt = 1_783_000_000_000L,
                ),
            )
        }
        awaitTag("memo_card_$memoId")
        composeRule.onNodeWithTag("memo_card_$memoId").performClick()
        awaitTag("memo_reading_view")
        return memoId
    }

    /**
     * One 戻る with the clock held: the save lands and the editor is popped, the wall comes
     * back into composition on the next frame, and the exit fade has barely begun — the
     * editor is still on screen, its 戻る still there. That is the window the second
     * request hits. (With the clock running, the wait would play the whole fade out before
     * looking, and there would be nothing left to tap.)
     */
    private fun leaveOnceAndFreezeTheExit(memoId: Long) {
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitWallWhileFrozen(memoId)
        composeRule.onAllNodesWithContentDescription("戻る").assertCountEquals(1)
    }

    /**
     * The save runs on a real IO thread before the pop, so the clock is stepped one frame
     * at a time (the way waitUntil itself does under auto-advance) until the wall is
     * composed — a handful of frames into a fade of several hundred milliseconds.
     */
    private fun awaitWallWhileFrozen(memoId: Long) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (composeRule.onAllNodesWithTag("memo_card_$memoId").fetchSemanticsNodes().isEmpty()) {
            check(System.nanoTime() < deadline) { "The wall never came back after 戻る" }
            composeRule.mainClock.advanceTimeByFrame()
            Thread.sleep(10)
        }
    }

    private fun assertWallStandsAlone(memoId: Long) {
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("memo_card_$memoId").assertIsDisplayed()
        composeRule.onNodeWithTag("create_memo").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("戻る").assertCountEquals(0)
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

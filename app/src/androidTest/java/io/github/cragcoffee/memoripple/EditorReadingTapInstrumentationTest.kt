package io.github.cragcoffee.memoripple

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 閲覧モード's doors to the pen (human, 2026-09-22): a single tap on the title, a double tap on a
 * written line — both with the keyboard on the field tapped; a double tap on an empty place of
 * the page opens writing as before, with no field taken.
 */
class EditorReadingTapInstrumentationTest {
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

    private fun caretAt(offset: Int) = SemanticsMatcher("caret at $offset") { node ->
        node.config.getOrNull(SemanticsProperties.TextSelectionRange) == TextRange(offset)
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openReading(): Long {
        val id = runBlocking {
            val now = System.currentTimeMillis()
            application.database.memoDao().insert(MemoEntity(title = "読む題", body = "一行目の本文\n二行目の本文", createdAt = now, updatedAt = now))
        }
        awaitTag("memo_card_$id")
        composeRule.onNodeWithTag("memo_card_$id").performClick()
        awaitTag("memo_reading_view")
        assertTrue(composeRule.onAllNodesWithTag("memo_body").fetchSemanticsNodes().isEmpty())
        return id
    }

    @Test
    fun aSingleTapOnTheTitleOpensWritingOnTheTitle() {
        openReading()
        composeRule.onNodeWithTag("memo_title_reading_tap").performClick()
        awaitTag("memo_body")
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("memo_reading_view").fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag("memo_title").assertIsFocused()
        // the caret waits where the writing continues: the end of the title
        composeRule.onNodeWithTag("memo_title").assert(caretAt("読む題".length))
    }

    @Test
    fun aDoubleTapOnAWrittenLineOpensWritingOnTheBody() {
        openReading()
        composeRule.onNodeWithText("二行目の本文").performTouchInput { doubleClick() }
        awaitTag("memo_body")
        composeRule.onNodeWithTag("memo_body").assertIsFocused()
        composeRule.onNodeWithTag("memo_title").assertIsNotFocused()
        // the caret waits at the end of the tapped line, not its head
        composeRule.onNodeWithTag("memo_body").assert(caretAt("一行目の本文\n二行目の本文".length))
    }

    @Test
    fun aDoubleTapOnAnEmptyPlaceOpensWritingAsBeforeWithNoFieldTaken() {
        openReading()
        composeRule.onNodeWithTag("memo_reading_view").performTouchInput {
            doubleClick(Offset(width / 2f, height - 20f))
        }
        awaitTag("memo_body")
        composeRule.onNodeWithTag("memo_body").assertIsNotFocused()
        composeRule.onNodeWithTag("memo_title").assertIsNotFocused()
    }
}

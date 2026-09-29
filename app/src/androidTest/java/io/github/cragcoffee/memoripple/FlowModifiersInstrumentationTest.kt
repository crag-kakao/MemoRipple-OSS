package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 行末修飾子: the 流れ方 chips write them, and the reading page never shows them. */
class FlowModifiersInstrumentationTest {
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
            application().settingsRepository.setEditorToolbarOrder("")
            application().settingsRepository.setEditorToolbarNoteOrder("")
        }
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theFlowChipsWriteAndReplaceLineEndTokens() {
        composeRule.onNodeWithTag("create_memo").performClick()
        awaitTag("memo_body")
        composeRule.onNodeWithTag("memo_body").performTextInput("流れることば")
        awaitTag("toolbar_flow_top")
        composeRule.onNodeWithTag("toolbar_flow_top").performScrollTo().performClick()
        composeRule.onNodeWithTag("memo_body")
            .assertTextContains("流れることば ↑", substring = true)

        // A rival of the same kind replaces; a different kind stands beside it.
        composeRule.onNodeWithTag("toolbar_flow_bottom").performScrollTo().performClick()
        composeRule.onNodeWithTag("memo_body")
            .assertTextContains("流れることば ↓", substring = true)
        composeRule.onNodeWithTag("toolbar_flow_large").performScrollTo().performClick()
        composeRule.onNodeWithTag("memo_body")
            .assertTextContains("流れることば ↓ +", substring = true)
        // The chip labels themselves say ↑, so the body's own text is what must be clean.
        val bodyText = composeRule.onNodeWithTag("memo_body").fetchSemanticsNode()
            .config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)
            ?.text.orEmpty()
        assertTrue(!bodyText.contains("↑"))
    }

    @Test
    fun theEndlessChipsWriteTheirOwnKindAndReplaceTheirKin() {
        composeRule.onNodeWithTag("create_memo").performClick()
        awaitTag("memo_body")
        composeRule.onNodeWithTag("memo_body").performTextInput("終わらないことば")
        awaitTag("toolbar_flow_pin_top")

        // 完全固定 is a pin, so it replaces the three-second pin rather than joining it.
        composeRule.onNodeWithTag("toolbar_flow_top").performScrollTo().performClick()
        composeRule.onNodeWithTag("toolbar_flow_pin_top").performScrollTo().performClick()
        composeRule.onNodeWithTag("memo_body")
            .assertTextContains("終わらないことば ↑↑", substring = true)

        // ループ is a different question, so it stands beside the pin.
        composeRule.onNodeWithTag("toolbar_flow_loop").performScrollTo().performClick()
        composeRule.onNodeWithTag("memo_body")
            .assertTextContains("終わらないことば ↑↑ ↺", substring = true)
    }

    @Test
    fun theReadingPageHidesTheModifiers() {
        val memoId = runBlocking {
            val now = System.currentTimeMillis()
            application().database.memoDao().insert(
                MemoEntity(
                    title = "修飾",
                    body = "! 大事な知らせ ↑ ++ {赤}",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        awaitTag("memo_card_$memoId")
        composeRule.onNodeWithTag("memo_card_$memoId").performClick()
        awaitTag("memo_reading_view")
        composeRule.onNodeWithText("大事な知らせ").assertExists()
        assertTrue(
            composeRule.onAllNodesWithText("↑", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            composeRule.onAllNodesWithText("{赤}", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }
}

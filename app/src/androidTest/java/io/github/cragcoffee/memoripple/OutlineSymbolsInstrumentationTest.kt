package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * アウトライン記号セット: 設定 chooses what the bar inserts, recognition accepts every set,
 * and a checkbox flips inside its line's own set.
 */
class OutlineSymbolsInstrumentationTest {
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
            application().settingsRepository.setOutlineSymbolSet("")
            application().settingsRepository.setOutlineChipLabel("")
        }
    }

    @After
    fun leaveTheDeviceDefaultsBehind() {
        runBlocking {
            application().settingsRepository.setOutlineSymbolSet("")
            application().settingsRepository.setOutlineChipLabel("")
        }
    }

    /** What the body field shows (its drawn text, task notation as boxes). */
    private fun bodyStartsWith(prefix: String): Boolean =
        composeRule.onAllNodesWithTag("memo_body").fetchSemanticsNodes().any { node ->
            node.config.getOrNull(SemanticsProperties.EditableText)?.text?.startsWith(prefix) == true ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(prefix) } == true
        }

    private fun awaitBody(prefix: String) {
        runCatching { composeRule.waitUntil(timeoutMillis = 5_000) { bodyStartsWith(prefix) } }.onFailure {
            val seen = composeRule.onAllNodesWithTag("memo_body").fetchSemanticsNodes().map { node ->
                "editable='${node.config.getOrNull(SemanticsProperties.EditableText)?.text?.take(20)}' text=${node.config.getOrNull(SemanticsProperties.Text)?.map { it.text.take(20) }}"
            }
            throw AssertionError("body should start with '$prefix'; the field carries $seen", it)
        }
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aRoleIsChosenOnItsOwnAndTheRowWearsTheMix() {
        if (composeRule.onAllNodesWithTag("memo_top_menu").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("memo_top_menu").performClick()
            composeRule.onNodeWithTag("memo_open_settings").performClick()
        } else {
            composeRule.onNodeWithContentDescription("設定").performClick()
        }
        awaitTag("settings_list")
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_outline_symbols"))
        composeRule.onNodeWithTag("setting_outline_symbols")
            .assertTextContains("■", substring = true)
            .performClick()
        awaitTag("outline_symbols_dialog")

        // 見出しだけ◆へ — the other roles keep their standard glyphs.
        composeRule.onNodeWithTag("outline_symbol_heading_japanese").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("setting_outline_symbols")
                    .assertTextContains("◆", substring = true)
                    .assertTextContains("-", substring = true)
            }.isSuccess
        }

        // The flow explanation stands open above the chips, no tap needed, and it
        // speaks in the glyphs actually chosen.
        composeRule.onNodeWithTag("outline_symbols_flow_detail")
            .assertTextContains("見出し", substring = true)
            .assertTextContains("◆", substring = true)
    }

    @Test
    fun theChipsWearWhicheverFaceTheWriterChose() {
        runBlocking {
            application().settingsRepository.setOutlineSymbolSet("heading:japanese")
        }
        if (composeRule.onAllNodesWithTag("memo_top_menu").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("memo_top_menu").performClick()
            composeRule.onNodeWithTag("memo_open_settings").performClick()
        } else {
            composeRule.onNodeWithContentDescription("設定").performClick()
        }
        awaitTag("settings_list")
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_outline_symbols"))
        composeRule.onNodeWithTag("setting_outline_symbols").performClick()
        awaitTag("outline_chip_label_word")

        // The choices wear what they choose, in the writer's own glyph.
        composeRule.onNodeWithTag("outline_chip_label_symbol_and_word")
            .assertTextContains("◆ 見出し", substring = true)
        composeRule.onNodeWithTag("outline_chip_label_symbol")
            .assertTextContains("◆", substring = true)
        composeRule.onNodeWithTag("outline_chip_label_word").performClick()

        // 言葉だけ: the bar's chip drops the glyph but still writes it.
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("create_memo")
        composeRule.onNodeWithTag("create_memo").performClick()
        awaitTag("memo_body")
        composeRule.onNodeWithTag("memo_body").performTextInput("章の名")
        awaitTag("outline_helper_0")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                // 言葉だけ: the chip's whole face is the word, glyph and all gone.
                composeRule.onNodeWithTag("outline_helper_0").assertTextEquals("見出し")
            }.isSuccess
        }
        composeRule.onNodeWithTag("outline_helper_0").performScrollTo().performClick()
        composeRule.onNodeWithTag("memo_body").assertTextContains("◆ 章の名", substring = true)
    }

    @Test
    fun aMixedChoiceWritesEachRoleInItsOwnGlyph() {
        runBlocking {
            application().settingsRepository.setOutlineSymbolSet("heading:japanese|item:standard")
        }
        composeRule.onNodeWithTag("create_memo").performClick()
        awaitTag("memo_body")
        composeRule.onNodeWithTag("memo_body").performTextInput("章の名")
        awaitTag("outline_helper_0")
        composeRule.onNodeWithTag("outline_helper_0").performScrollTo().performClick()
        composeRule.onNodeWithTag("memo_body").assertTextContains("◆ 章の名", substring = true)
        // 項目 stays standard: toggling the item chip replaces ◆ with the plain dash.
        composeRule.onNodeWithTag("outline_helper_1").performScrollTo().performClick()
        composeRule.onNodeWithTag("memo_body").assertTextContains("- 章の名", substring = true)
    }

    @Test
    fun theChosenSetWritesItsGlyphsAndTheLineStillFlows() {
        runBlocking { application().settingsRepository.setOutlineSymbolSet("emoji") }
        composeRule.onNodeWithTag("create_memo").performClick()
        awaitTag("memo_body")
        composeRule.onNodeWithTag("memo_body").performTextInput("章のなまえ")
        awaitTag("outline_helper_0")
        composeRule.onNodeWithTag("outline_helper_0").performScrollTo().performClick()
        composeRule.onNodeWithTag("memo_body").assertTextContains("📌 章のなまえ", substring = true)

        // The checkbox cycle writes the chosen set too: box, then its own set's check.
        composeRule.onNodeWithTag("toolbar_task").performScrollTo().performClick()
        composeRule.onNodeWithTag("memo_body").assertTextContains("☐ 章のなまえ", substring = true)
        composeRule.onNodeWithTag("toolbar_task").performClick()
        composeRule.onNodeWithTag("memo_body").assertTextContains("✅ 章のなまえ", substring = true)
    }

    @Test
    fun aJapaneseCheckboxTappedOnThePageChecksInItsOwnSet() {
        runBlocking { application().settingsRepository.setOutlineSymbolSet("japanese") }
        val memoId = runBlocking {
            val now = System.currentTimeMillis()
            application().database.memoDao().insert(
                MemoEntity(title = "買う", body = "☐ 牛乳", createdAt = now, updatedAt = now),
            )
        }
        awaitTag("memo_card_$memoId")
        composeRule.onNodeWithTag("memo_card_$memoId").performClick()
        // An existing memo opens reading; the box is tappable right there.
        awaitTag("reading_task_0")
        // The box is a glyph beside the words — the whole row is the target — not a 48dp button.
        val boxWidth = composeRule.onNodeWithTag("reading_task_box_0", useUnmergedTree = true).getBoundsInRoot().width
        // A memo's page has no bullet before its box; only the outliner's does.
        composeRule.onAllNodesWithTag("reading_task_dot_0", useUnmergedTree = true).assertCountEquals(0)
        assertTrue("box $boxWidth should be glyph-sized", boxWidth <= 24.dp)
        composeRule.onNodeWithTag("reading_task_0").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                application().database.memoDao().findById(memoId)?.body?.startsWith("☑ ") == true
            }
        }
        assertTrue(true)
    }

    @Test
    fun aBoxTappedWhileEditingChecksTheLineWithoutMovingTheCaretThere() {
        val memoId = runBlocking {
            val now = System.currentTimeMillis()
            application().database.memoDao().insert(
                MemoEntity(title = "買う", body = "- [ ] 牛乳\n買い足す", createdAt = now, updatedAt = now),
            )
        }
        awaitTag("memo_card_$memoId")
        composeRule.onNodeWithTag("memo_card_$memoId").performClick()
        awaitTag("reading_edit")
        composeRule.onNodeWithTag("reading_edit").performClick()
        awaitTag("memo_body")
        // The box is the first glyph of the first line; a tap on it ticks the line, the way
        // the reading page does, instead of only parking the caret beside it.
        composeRule.onNodeWithTag("memo_body").performTouchInput {
            click(Offset(with(density) { 8.dp.toPx() }, with(density) { 12.dp.toPx() }))
        }
        // The field speaks in its drawn text: the notation shows as the box it means.
        awaitBody("☑ 牛乳")
        // A second tap unticks it again.
        composeRule.onNodeWithTag("memo_body").performTouchInput {
            click(Offset(with(density) { 8.dp.toPx() }, with(density) { 12.dp.toPx() }))
        }
        awaitBody("☐ 牛乳")
    }
}

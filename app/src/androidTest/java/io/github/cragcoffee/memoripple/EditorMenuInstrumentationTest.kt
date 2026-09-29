package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The ⋮ menu's optional rows: 全てのテキストをコピー and PDFで保存 leave when 設定 says so. */
class EditorMenuInstrumentationTest {
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
            application().settingsRepository.setEditorMenuCopyAll(true)
            application().settingsRepository.setEditorMenuPdfExport(true)
            application().settingsRepository.setEditorExportDocx(false)
        }
    }

    @org.junit.After
    fun leaveTheDeviceDefaultsBehind() {
        // These are device-local and outside resetToDefaults; a class that flips them must
        // hand the defaults back, or the next class inherits a stranger's menu.
        runBlocking {
            application().settingsRepository.setEditorMenuCopyAll(true)
            application().settingsRepository.setEditorMenuPdfExport(true)
            application().settingsRepository.setEditorExportDocx(false)
        }
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openMemoMenu(memoId: Long) {
        awaitTag("memo_card_$memoId")
        composeRule.onNodeWithTag("memo_card_$memoId").performClick()
        awaitTag("memo_editor_more")
        composeRule.onNodeWithTag("memo_editor_more").performClick()
        awaitTag("memo_editor_export")
    }

    private fun seedMemo(): Long = runBlocking {
        val now = System.currentTimeMillis()
        application().database.memoDao().insert(
            MemoEntity(title = "献立", body = "本文のことば。", createdAt = now, updatedAt = now),
        )
    }

    @Test
    fun theMenuCarriesCopyAllAndPdfByDefault() {
        openMemoMenu(seedMemo())
        composeRule.onNodeWithTag("memo_editor_copy_all").assertExists()
        composeRule.onNodeWithTag("memo_editor_export_pdf").assertExists()
        composeRule.onNodeWithText("Markdownで書き出す").assertExists()
    }

    @Test
    fun theExportRowWearsWordWhenAsked() {
        runBlocking { application().settingsRepository.setEditorExportDocx(true) }
        openMemoMenu(seedMemo())
        composeRule.onNodeWithText("Word (.docx)で書き出す").assertExists()
        assertTrue(
            composeRule.onAllNodesWithText("Markdownで書き出す").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun switchedOffInSettingsBothRowsLeaveTheMenu() {
        runBlocking {
            application().settingsRepository.setEditorMenuCopyAll(false)
            application().settingsRepository.setEditorMenuPdfExport(false)
        }
        openMemoMenu(seedMemo())
        assertTrue(
            composeRule.onAllNodesWithTag("memo_editor_copy_all").fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            composeRule.onAllNodesWithTag("memo_editor_export_pdf")
                .fetchSemanticsNodes().isEmpty(),
        )
        // The rest of the menu stands as always.
        composeRule.onNodeWithTag("memo_editor_export").assertExists()
        composeRule.onNodeWithTag("memo_editor_share").assertExists()
    }
}

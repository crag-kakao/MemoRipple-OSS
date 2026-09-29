package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.PickerDrill.drillToTemplate
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.TemplateFolder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The ＋ picker as an entrance, and template folders (human brief 2026-09-22), no model, offline:
 * A the root shows only pins / recents / folder rows → 記録 opens inside the sheet → back to the root;
 * B 自分のテンプレート → a custom folder → a template runs; C the editor's folder → 保存 → the picker
 * shows it in that folder; D deleting a folder keeps its templates as 未分類; E a pin survives a
 * folder move. F (Think / search / create) lives in the existing classes.
 */
class TemplatePickerFoldersInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication
    private val review = "starter-daily-review"

    @Before
    fun startEmptyNoModelOffline() {
        runBlocking {
            application.database.clearAllTables()
            application.templateRepository.replaceAll(emptyList())
            application.templateFolderRepository.replaceAll(emptyList())
            application.recentTemplateRepository.clear()
            application.settingsRepository.setAutoPlayOnLaunch(false)
            application.settingsRepository.setLastChatConversationId(null)
            application.settingsRepository.clearPinnedTemplates()
            application.aiOrchestrator.release()
        }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0; TestGuards.reset()
        TestAiSelection.descriptor = null
        TestGuards.online = false
    }

    @After
    fun tidy() {
        runBlocking {
            application.aiOrchestrator.release()
            application.settingsRepository.setLastChatConversationId(null)
            application.settingsRepository.clearPinnedTemplates()
            application.templateRepository.replaceAll(emptyList())
            application.templateFolderRepository.replaceAll(emptyList())
            application.recentTemplateRepository.clear()
        }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0; TestGuards.reset()
    }

    private fun templates() = runBlocking { application.templateRepository.current() }
    private fun folders() = runBlocking { application.templateFolderRepository.current() }
    private fun pins() = runBlocking { application.settingsRepository.pinnedTemplateIds.first() }
    private fun conversations() = runBlocking { application.chatHistoryRepository.conversations().first() }
    private fun transcript(id: Long) = runBlocking { application.chatHistoryRepository.messages(id).first() }
    private fun openChat() { composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input") }
    private fun openPicker() { composeRule.onNodeWithTag("chat_plus").performClick(); awaitTag("chat_template_picker") }
    private fun scrollTo(tag: String) { composeRule.onNodeWithTag("chat_template_list").performScrollToNode(hasTestTag(tag)) }
    private fun awaitTag(tag: String, timeout: Long = 15_000) { composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
    private fun awaitGone(tag: String, timeout: Long = 15_000) { composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() } }
    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun awaitQuestion(text: String) {
        composeRule.waitUntil(15_000) { conversations().singleOrNull()?.let { c -> transcript(c.id).lastOrNull { it.role == ChatRole.ASSISTANT }?.text?.contains(text) } == true }
    }

    // --- A: the root is an entrance; 記録 opens inside the sheet; back returns to the root ---

    @Test
    fun theRootShowsFoldersNotTemplatesAndAFolderOpensInsideTheSheet() {
        openChat()
        openPicker()
        listOf("chat_template_folder_record", "chat_template_folder_think", "chat_template_folder_search", "chat_template_folder_mine").forEach { scrollTo(it); composeRule.onNodeWithTag(it, useUnmergedTree = true).assertIsDisplayed() }
        assertEquals("no starter row at the root", 0, count("chat_template_item_$review"))
        assertEquals("no pins, no section", 0, count("chat_template_section_pinned"))
        assertEquals("no recents, no section", 0, count("chat_template_section_recent"))
        composeRule.onNodeWithTag("chat_template_folder_record").assertTextContains("4", substring = true)
        scrollTo("chat_template_create"); composeRule.onNodeWithTag("chat_template_create", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("chat_template_folder_record").performClick()
        awaitTag("chat_template_folder_back")
        listOf("starter-daily-review", "starter-meeting-memo", "starter-idea-memo", "starter-project-log").forEach { scrollTo("chat_template_item_$it"); composeRule.onNodeWithTag("chat_template_item_$it", useUnmergedTree = true).assertIsDisplayed() }
        assertEquals("a Think starter is not in 記録", 0, count("chat_template_item_starter-think-idea"))
        assertEquals("still the one sheet", 1, count("chat_template_picker"))
        composeRule.onNodeWithTag("chat_template_folder_back").performClick()
        awaitTag("chat_template_folder_record")
        assertEquals(0, count("chat_template_item_$review"))
        // the system Back on a folder page also returns to the root, and on the root closes the sheet
        composeRule.onNodeWithTag("chat_template_folder_search").performClick()
        awaitTag("chat_template_folder_back")
        androidx.test.espresso.Espresso.pressBack()
        awaitTag("chat_template_folder_search")
        androidx.test.espresso.Espresso.pressBack()
        awaitGone("chat_template_picker")
    }

    // --- B: 自分のテンプレート → a custom folder → a template runs ---

    @Test
    fun aCustomFolderOpensAndItsTemplateRuns() {
        runBlocking {
            application.templateFolderRepository.save(TemplateFolder("f-work", "仕事", 0))
            application.templateRepository.save(MemoTemplate("u-weekly", "週報", "# 週報", folderId = "f-work"))
            application.templateRepository.save(MemoTemplate("u-loose", "ひとこと", "…"))
        }
        openChat()
        openPicker()
        scrollTo("chat_template_folder_mine")
        composeRule.onNodeWithTag("chat_template_folder_mine").assertTextContains("2", substring = true)
        composeRule.onNodeWithTag("chat_template_folder_mine").performClick()
        awaitTag("chat_template_folder_mine_f-work")
        composeRule.onNodeWithTag("chat_template_folder_mine_f-work").assertTextContains("仕事", substring = true)
        composeRule.onNodeWithTag("chat_template_folder_mine_none").assertTextContains("未分類", substring = true)
        composeRule.onNodeWithTag("chat_template_folder_mine_f-work").performClick()
        awaitTag("chat_template_item_u-weekly")
        assertEquals(0, count("chat_template_item_u-loose"))
        composeRule.onNodeWithTag("chat_template_item_u-weekly").performClick()
        awaitTag("chat_ai_preview_template")
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        awaitGone("chat_ai_preview_template")
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- C: the editor's folder → 保存 → the picker shows it in that folder ---

    @Test
    fun theEditorPicksAFolderAndThePickerShowsItThere() {
        runBlocking { application.templateFolderRepository.save(TemplateFolder("f-read", "読書", 0)) }
        openChat()
        openPicker()
        scrollTo("chat_template_create")
        composeRule.onNodeWithTag("chat_template_create", useUnmergedTree = true).performClick()
        awaitTag("template_editor")
        composeRule.onNodeWithTag("template_editor_name").performTextInput("読書メモ")
        composeRule.onNodeWithTag("template_editor_folder").assertTextContains("未分類", substring = true)
        composeRule.onNodeWithTag("template_editor_folder").performClick()
        awaitTag("template_editor_folder_f-read")
        composeRule.onNodeWithTag("template_editor_folder_f-read").performClick()
        composeRule.onNodeWithTag("template_editor_folder").assertTextContains("読書", substring = true)
        repeat(3) { composeRule.onNodeWithTag("template_editor_next").performClick(); composeRule.waitForIdle() }
        awaitTag("template_editor_body")
        composeRule.onNodeWithTag("template_editor_body").performTextInput("本の名前")
        composeRule.onNodeWithTag("template_editor_next").performClick()
        awaitTag("template_editor_save")
        composeRule.onNodeWithTag("template_editor_save").performClick()
        awaitTag("chat_input")
        val saved = templates().single()
        assertEquals("f-read", saved.folderId)
        openPicker()
        composeRule.drillToTemplate(saved.id, application)
        composeRule.onNodeWithTag("chat_template_item_${saved.id}").assertIsDisplayed()
    }

    // --- D: deleting a folder keeps its templates, now 未分類 ---

    @Test
    fun deletingAFolderKeepsItsTemplatesAsUnclassified() {
        runBlocking {
            application.templateFolderRepository.save(TemplateFolder("f-dev", "開発", 0))
            application.templateRepository.save(MemoTemplate("u-log", "作業ログ", "…", folderId = "f-dev"))
        }
        composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input")
        composeRule.onNodeWithTag("chat_overflow").performClick()
        composeRule.onNodeWithTag("chat_open_templates").performClick()
        awaitTag("templates_folders")
        composeRule.onNodeWithTag("templates_folders").performClick()
        awaitTag("template_folders_list")
        composeRule.onNodeWithTag("template_folder_delete_f-dev").performClick()
        awaitTag("template_folder_delete_confirm")
        composeRule.onNodeWithTag("template_folder_delete_confirm").performClick()
        composeRule.waitUntil(5_000) { folders().isEmpty() }
        val kept = templates().single()
        assertEquals("u-log", kept.id)
        assertNull(kept.folderId)
        // and the folder screen can make one again, renamed, and reordered
        composeRule.onNodeWithTag("template_folders_add").performClick()
        awaitTag("template_folder_name_field")
        composeRule.onNodeWithTag("template_folder_name_field").performTextInput("企画")
        composeRule.onNodeWithTag("template_folder_name_confirm").performClick()
        composeRule.waitUntil(5_000) { folders().map { it.name } == listOf("企画") }
        composeRule.onNodeWithTag("template_folders_add").performClick()
        awaitTag("template_folder_name_field")
        composeRule.onNodeWithTag("template_folder_name_field").performTextInput("日記")
        composeRule.onNodeWithTag("template_folder_name_confirm").performClick()
        composeRule.waitUntil(5_000) { folders().map { it.name } == listOf("企画", "日記") }
        val second = folders()[1].id
        composeRule.onNodeWithTag("template_folder_up_$second").performClick()
        composeRule.waitUntil(5_000) { folders().map { it.name } == listOf("日記", "企画") }
        composeRule.onNodeWithTag("template_folder_rename_$second").performClick()
        awaitTag("template_folder_name_field")
        composeRule.onNodeWithTag("template_folder_name_field").performTextClearance()
        composeRule.onNodeWithTag("template_folder_name_field").performTextInput("日記の記録")
        composeRule.onNodeWithTag("template_folder_name_confirm").performClick()
        composeRule.waitUntil(5_000) { folders().map { it.name } == listOf("日記の記録", "企画") }
    }

    // --- E: a pin survives a folder move; the pinned row runs from the root ---

    @Test
    fun aPinSurvivesAFolderMoveAndRunsFromTheRoot() {
        runBlocking {
            application.templateFolderRepository.save(TemplateFolder("f-a", "A", 0))
            application.templateRepository.save(MemoTemplate("u-pin", "朝のことば", "おはよう"))
        }
        openChat()
        openPicker()
        composeRule.drillToTemplate("u-pin", application)
        composeRule.onNodeWithTag("chat_template_item_u-pin").performTouchInput { longClick() }
        awaitTag("chat_template_pin_u-pin")
        composeRule.onNodeWithTag("chat_template_pin_u-pin").performClick()
        composeRule.waitUntil(5_000) { pins() == listOf("u-pin") }
        // moved into a folder from the store (the editor's path is journey C)
        runBlocking { application.templateRepository.save(templates().single().copy(folderId = "f-a")) }
        composeRule.waitUntil(5_000) { templates().single().folderId == "f-a" }
        assertEquals("the pin stays", listOf("u-pin"), pins())
        // the sheet's own back arrow, one page at a time (the system Back is journey A's; Espresso's root picker can lose the dialog window's focus mid-walk)
        composeRule.onNodeWithTag("chat_template_folder_back").performClick()   // 未分類 → 自分のテンプレート
        awaitTag("chat_template_folder_mine_none")
        composeRule.onNodeWithTag("chat_template_folder_back").performClick()   // → the root
        awaitTag("chat_template_section_pinned")
        composeRule.onNodeWithTag("chat_template_pinned_u-pin").performClick()
        awaitTag("chat_ai_preview_template")
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        awaitGone("chat_ai_preview_template")
    }

    // --- the root caps pins and recents at three; a fourth pin is behind すべて表示 ---

    @Test
    fun theRootCapsPinsAtThreeAndOffersTheRest() {
        runBlocking { listOf("starter-daily-review", "starter-meeting-memo", "starter-idea-memo", "starter-project-log").forEach { application.settingsRepository.setTemplatePinned(it, true) } }
        openChat()
        openPicker()
        awaitTag("chat_template_section_pinned")
        assertEquals(1, count("chat_template_pinned_starter-daily-review"))
        assertEquals("the fourth pin is not on the root", 0, count("chat_template_pinned_starter-project-log"))
        scrollTo("chat_template_pinned_all")
        composeRule.onNodeWithTag("chat_template_pinned_all").performClick()
        awaitTag("chat_template_pinned_starter-project-log")
        composeRule.onNodeWithTag("chat_template_folder_back").performClick()
        awaitTag("chat_template_folder_record")
    }
}

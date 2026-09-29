package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.PickerDrill.pickTemplateThroughFolders
import io.github.cragcoffee.memoripple.data.FolderResult
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The Human Review's two remarks on 「メモを選択」 (2026-09-27, docs/CHAT_MEMO_CONTEXT.md §7):
 * the memo picker opens on the folder chosen with 「フォルダを選択」 — named and really filtered — and,
 * while a memo is selected, what the chat would make as a new memo is appended to the end of that
 * memo instead (the Append preview, the one confirmation, a Conflict when it moved), with no new
 * memo; a new chat with nothing selected still makes one. No model anywhere.
 */
class ChatMemoFolderAndCreateInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    @Before
    fun startEmptyNoModelOffline() {
        runBlocking {
            application.database.clearAllTables()
            application.templateRepository.replaceAll(emptyList())
            application.recentTemplateRepository.clear()
            application.settingsRepository.setAutoPlayOnLaunch(false)
            application.settingsRepository.setLastChatConversationId(null)
            application.settingsRepository.setChatCreateFolderId(null)
            application.chatMemoSelectionStore.clear()
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
            application.settingsRepository.setChatCreateFolderId(null)
            application.chatMemoSelectionStore.clear()
            application.templateRepository.replaceAll(emptyList())
            application.recentTemplateRepository.clear()
        }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0; TestGuards.reset()
    }

    private fun folder(name: String): Long = runBlocking { (application.folderRepository.create(name, null) as FolderResult.Done).id }
    private fun memo(title: String, body: String, folderId: Long? = null): Long = runBlocking {
        application.memoRepository.save(existing = null, title = title, body = body, now = application.timeProvider.nowMillis(), folderId = folderId)!!.id
    }
    private fun body(id: Long) = runBlocking { application.database.memoDao().findById(id)!!.body }
    private fun memoCount() = runBlocking { application.database.memoDao().allIds().size }
    private fun conversations() = runBlocking { application.chatHistoryRepository.conversations().first() }
    private fun transcript(id: Long) = runBlocking { application.chatHistoryRepository.messages(id).first() }

    private fun openChat() { composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input") }
    private fun awaitTag(tag: String, timeout: Long = 15_000) { composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun send(text: String) {
        composeRule.onNodeWithTag("chat_input").performTextClearance()
        composeRule.onNodeWithTag("chat_input").performTextInput(text)
        composeRule.onNodeWithTag("chat_send").performClick()
    }
    private fun chooseFolder(id: Long) {
        composeRule.onNodeWithTag("chat_folder_chip").performClick()
        awaitTag("chat_folder_choice_$id")
        composeRule.onNodeWithTag("chat_folder_choice_$id").performClick()
        composeRule.waitUntil(5_000) { runBlocking { application.settingsRepository.chatCreateFolderId.first() } == id }
    }
    private fun selectMemo(id: Long) {
        composeRule.onNodeWithTag("chat_memo_chip").performClick()
        awaitTag("chat_memo_picker")
        // the picker may open on a folder; choose among everything to reach any memo
        if (count("chat_memo_choice_$id") == 0 && count("chat_memo_picker_folder") > 0) {
            composeRule.onNodeWithTag("chat_memo_picker_folder").performClick()
            awaitTag("chat_memo_picker_folder_all")
            composeRule.onNodeWithTag("chat_memo_picker_folder_all").performClick()
        }
        awaitTag("chat_memo_choice_$id")
        composeRule.onNodeWithTag("chat_memo_choice_$id").performClick()
        awaitTag("chat_memo_selected")
    }
    /** The home's メモ cell: 何をメモしますか？ — answered with [text]. */
    private fun makeAMemoFromTheHome(text: String) {
        awaitTag("chat_home_shortcut_memo")
        composeRule.onNodeWithTag("chat_home_shortcut_memo").performClick()
        composeRule.waitUntil(15_000) { conversations().singleOrNull()?.let { c -> transcript(c.id).lastOrNull { it.role == ChatRole.ASSISTANT }?.text?.contains("何をメモしますか") } == true }
        send(text)
    }

    // --- 1: the picker opens on the chosen folder ---

    @Test
    fun thePickerOpensOnTheChosenFolderNamedAndFiltered() {
        val aa = folder("ああ")
        val inside = memo("中のメモ", "x", folderId = aa)
        val outside = memo("外のメモ", "y")
        openChat()
        chooseFolder(aa)
        composeRule.onNodeWithTag("chat_memo_chip").performClick()
        awaitTag("chat_memo_picker")
        composeRule.onNodeWithTag("chat_memo_picker_folder").assertTextContains("ああ", substring = true)
        awaitTag("chat_memo_choice_$inside")
        assertEquals("only the folder's memos", 0, count("chat_memo_choice_$outside"))
    }

    @Test
    fun withNoFolderChosenThePickerOpensOnEveryFolder() {
        val aa = folder("ああ")
        val inside = memo("中のメモ", "x", folderId = aa)
        val outside = memo("外のメモ", "y")
        openChat()
        composeRule.onNodeWithTag("chat_memo_chip").performClick()
        awaitTag("chat_memo_picker")
        composeRule.onNodeWithTag("chat_memo_picker_folder").assertTextContains("すべてのフォルダ", substring = true)
        awaitTag("chat_memo_choice_$inside")
        awaitTag("chat_memo_choice_$outside")
    }

    @Test
    fun switchingTheSheetToEveryFolderShowsTheWholeListFromItsTop() {
        // found on the S20 (2026-09-27): the list kept its place at the folder's first memo, the newer ones out of view
        val aa = folder("ああ")
        // enough memos in the folder to fill the sheet, as on the S20 (a list that cannot scroll hides nothing)
        (1..24).forEach { memo("フォルダのメモ$it", "x", folderId = aa) }
        val inside = memo("中のメモ", "x", folderId = aa)
        val newest = memo("外のメモ", "y")   // made last: the top of the whole list
        openChat()
        chooseFolder(aa)
        composeRule.onNodeWithTag("chat_memo_chip").performClick()
        awaitTag("chat_memo_choice_$inside")
        composeRule.onNodeWithTag("chat_memo_picker_folder").performClick()
        awaitTag("chat_memo_picker_folder_all")
        composeRule.onNodeWithTag("chat_memo_picker_folder_all").performClick()
        awaitTag("chat_memo_choice_$newest")
        composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithTag("chat_memo_choice_$newest").assertIsDisplayed() }.isSuccess }
        composeRule.onNodeWithTag("chat_memo_choice_$newest").assertIsDisplayed()
    }

    // --- 2: with a memo selected, making a memo appends to it ---

    @Test
    fun theHomesMemoQuestionAppendsToTheSelectedMemoAndMakesNoNewMemoEvenWithAFolderChosen() {
        val aa = folder("ああ")
        val selected = memo("買い物", "牛乳")
        openChat()
        chooseFolder(aa)
        selectMemo(selected)
        val before = memoCount()
        makeAMemoFromTheHome("卵")
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("買い物", substring = true)
        assertEquals("no create preview", 0, count("chat_ai_preview_create"))
        assertEquals("nothing before the confirmation", "牛乳", body(selected))
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        composeRule.waitUntil(10_000) { body(selected) == "牛乳\n卵" }
        assertEquals("no new memo", before, memoCount())
        assertEquals(0, TestAiRuntime.loads)
    }

    @Test
    fun aCreateTemplateIsAppendedToTheSelectedMemo() {
        runBlocking { application.templateRepository.replaceAll(listOf(MemoTemplate("qa-note", "QAメモ", "テンプレ本文"))) }
        val selected = memo("買い物", "牛乳")
        openChat()
        selectMemo(selected)
        val before = memoCount()
        composeRule.pickTemplateThroughFolders("qa-note", application)
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("テンプレ本文", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        composeRule.waitUntil(10_000) { body(selected) == "牛乳\nテンプレ本文" }
        assertEquals(before, memoCount())
    }

    @Test
    fun theSelectedMemoWrittenAfterThePreviewIsAConflict() {
        val selected = memo("買い物", "牛乳")
        openChat()
        selectMemo(selected)
        makeAMemoFromTheHome("卵")
        awaitTag("chat_ai_preview_append")
        runBlocking { application.memoRepository.save(application.database.memoDao().findById(selected), "買い物", "牛乳\n別の手", application.timeProvider.nowMillis() + 1_000) }
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        awaitTag("chat_ai_write_conflict")
        assertEquals("牛乳\n別の手", body(selected))
    }

    @Test
    fun aNewChatWithNothingSelectedStillMakesANewMemo() {
        val selected = memo("買い物", "牛乳")
        openChat()
        selectMemo(selected)
        send("こんにちは")   // the first conversation, bound to the memo
        composeRule.waitUntil(15_000) { conversations().isNotEmpty() && transcript(conversations().single().id).any { it.role == ChatRole.ASSISTANT } }
        // a new chat: its own scope, nothing selected
        runBlocking { application.settingsRepository.setLastChatConversationId(null) }
        awaitTag("chat_memo_chip")
        val before = memoCount()
        awaitTag("chat_home_shortcut_memo")
        composeRule.onNodeWithTag("chat_home_shortcut_memo").performClick()
        composeRule.waitUntil(15_000) { conversations().size == 2 }
        send("卵")
        awaitTag("chat_ai_preview_create")
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        composeRule.waitUntil(10_000) { memoCount() == before + 1 }
        assertEquals("the selected memo of the other conversation is untouched", "牛乳", body(selected))
        assertTrue(conversations().size == 2)
    }
}

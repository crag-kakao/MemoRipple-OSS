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
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.data.FolderResult
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
 * The chat's folder (human decision 2026-09-22): a chip above the input chooses one of the wall's
 * folders; every document the chat creates goes there — a CREATE template (A), a Think save (F), a
 * conversation saved as a memo (C), an AI CREATE with a model (G) — the preview naming it; with
 * no choice everything is as before (B); a folder deleted while chosen falls back to the root (D);
 * the choice survives a restart (E); a journal keeps no folder (H).
 */
class ChatDestinationInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication
    private val meeting = "starter-meeting-memo"
    private val review = "starter-daily-review"
    private val todayTasks = "starter-think-today-tasks"

    @Before
    fun startEmptyNoModelOffline() {
        runBlocking {
            application.database.clearAllTables()
            application.templateRepository.replaceAll(emptyList())
            application.recentTemplateRepository.clear()
            application.settingsRepository.setAutoPlayOnLaunch(false)
            application.settingsRepository.setLastChatConversationId(null)
            application.settingsRepository.setChatCreateFolderId(null)
            application.aiOrchestrator.release()
        }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0; TestGuards.reset()
        TestAiSelection.descriptor = null
        TestGuards.online = false
    }

    @After
    fun tidy() {
        TestAiRuntime.loadGate?.complete(Unit); TestAiRuntime.generateGate?.complete(Unit)
        runBlocking {
            application.aiOrchestrator.release()
            application.settingsRepository.setLastChatConversationId(null)
            application.settingsRepository.setChatCreateFolderId(null)
            application.templateRepository.replaceAll(emptyList())
            application.recentTemplateRepository.clear()
        }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0; TestGuards.reset()
    }

    private fun folder(name: String, parentId: Long? = null): Long = runBlocking { (application.folderRepository.create(name, parentId) as FolderResult.Done).id }
    private fun memoIds() = runBlocking { application.database.memoDao().allIds().toSet() }
    private fun memoFolder(id: Long) = runBlocking { application.database.memoDao().findById(id)!!.folderId }
    private fun journals() = runBlocking { application.database.diaryDao().observeAll().first() }
    private fun conversations() = runBlocking { application.chatHistoryRepository.conversations().first() }
    private fun transcript(id: Long) = runBlocking { application.chatHistoryRepository.messages(id).first() }
    private fun chosen() = runBlocking { application.settingsRepository.chatCreateFolderId.first() }

    private fun openChat() { composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input") }
    private fun send(text: String) {
        composeRule.onNodeWithTag("chat_input").performTextClearance()
        composeRule.onNodeWithTag("chat_input").performTextInput(text)
        composeRule.onNodeWithTag("chat_send").performClick()
    }
    private fun pickTemplate(id: String) { composeRule.pickTemplateThroughFolders(id, application) }
    private fun awaitTag(tag: String, timeout: Long = 15_000) { composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
    private fun awaitGone(tag: String, timeout: Long = 15_000) { composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() } }
    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun awaitQuestion(text: String) {
        composeRule.waitUntil(15_000) { conversations().singleOrNull()?.let { c -> transcript(c.id).lastOrNull { it.role == ChatRole.ASSISTANT }?.text?.contains(text) } == true }
    }
    private fun choose(folderId: Long) {
        composeRule.onNodeWithTag("chat_folder_chip").performClick()
        awaitTag("chat_folder_choice_$folderId")
        composeRule.onNodeWithTag("chat_folder_choice_$folderId").performClick()
        composeRule.waitUntil(5_000) { chosen() == folderId }
    }
    private fun confirmOnce() {
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        composeRule.onAllNodesWithTag("chat_ai_preview_confirm").fetchSemanticsNodes().firstOrNull()?.let { runCatching { composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick() } }
    }
    private fun proposal(intent: String, kind: String? = null, text: String? = null): String {
        fun s(v: String?) = if (v == null) "null" else "\"" + v.replace("\"", "\\\"") + "\""
        return "{\"intent\":${s(intent)},\"query\":null,\"targetRef\":null,\"targetName\":null,\"documentKind\":${s(kind)},\"text\":${s(text)},\"templateId\":null,\"dateToken\":null,\"missingFields\":[]}"
    }

    // --- A: a folder chosen → a CREATE template's preview names it → the memo is in it; the ＋ and 送る sit inside the pill ---

    @Test
    fun aChosenFolderTakesACreateTemplatesMemo() {
        val work = folder("仕事")
        openChat()
        composeRule.onNodeWithTag("chat_folder_chip").assertTextContains("フォルダを選択", substring = true)
        choose(work)
        composeRule.onNodeWithTag("chat_folder_chip").assertTextContains("仕事", substring = true)
        val before = memoIds()
        pickTemplate(meeting)
        awaitQuestion("会議名は？")
        send("定例会")
        awaitQuestion("参加者は？")
        composeRule.onNodeWithTag("chat_answer_skip", useUnmergedTree = true).performClick()
        awaitQuestion("何について話しましたか？")
        composeRule.onNodeWithTag("chat_answer_skip", useUnmergedTree = true).performClick()
        awaitQuestion("何が決まりましたか？")
        composeRule.onNodeWithTag("chat_answer_skip", useUnmergedTree = true).performClick()
        awaitQuestion("次にやることは？")
        composeRule.onNodeWithTag("chat_answer_skip", useUnmergedTree = true).performClick()
        awaitTag("chat_ai_preview_template")
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("保存先", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("仕事", substring = true)
        confirmOnce()
        awaitTag("memo_reading_view")
        val made = (memoIds() - before).single()
        assertEquals(work, memoFolder(made))
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- B: no choice → no 保存先 → the memo at the root, as before ---

    @Test
    fun noChoiceLeavesEverythingAsBefore() {
        folder("仕事")
        openChat()
        val before = memoIds()
        pickTemplate(meeting)
        awaitQuestion("会議名は？")
        send("定例会")
        repeat(4) { awaitTag("chat_answer_skip"); composeRule.onNodeWithTag("chat_answer_skip", useUnmergedTree = true).performClick(); composeRule.waitForIdle() }
        awaitTag("chat_ai_preview_template")
        assertEquals("no 保存先 line without a choice", 0, count("chat_ai_preview_folder"))
        confirmOnce()
        awaitTag("memo_reading_view")
        assertNull(memoFolder((memoIds() - before).single()))
    }

    // --- C: the conversation saved as a memo goes to the folder ---

    @Test
    fun aConversationSavedAsAMemoGoesToTheFolder() {
        val ideas = folder("アイデア")
        openChat()
        pickTemplate(review)
        awaitQuestion("今日の良かったことは？")
        composeRule.onNodeWithTag("chat_answer_cancel", useUnmergedTree = true).performClick()
        awaitGone("chat_answer_options")
        choose(ideas)
        val before = memoIds()
        composeRule.onNodeWithTag("chat_overflow").performClick()
        composeRule.onNodeWithTag("chat_save_conversation").performClick()
        awaitTag("chat_ai_preview_create")
        composeRule.onNodeWithTag("chat_ai_preview_create").assertTextContains("アイデア", substring = true)
        confirmOnce()
        awaitTag("memo_reading_view")
        assertEquals(ideas, memoFolder((memoIds() - before).single()))
    }

    // --- D: the chosen folder is deleted → the chip resets, the memo goes to the root ---

    @Test
    fun aDeletedFolderFallsBackToTheRoot() {
        val gone = folder("消える")
        openChat()
        choose(gone)
        runBlocking { application.folderRepository.delete(gone) }
        composeRule.waitUntil(5_000) { chosen() == null }
        composeRule.onNodeWithTag("chat_folder_chip").assertTextContains("フォルダを選択", substring = true)
        val before = memoIds()
        pickTemplate(meeting)
        awaitQuestion("会議名は？")
        send("定例会")
        repeat(4) { awaitTag("chat_answer_skip"); composeRule.onNodeWithTag("chat_answer_skip", useUnmergedTree = true).performClick(); composeRule.waitForIdle() }
        awaitTag("chat_ai_preview_template")
        assertEquals(0, count("chat_ai_preview_folder"))
        confirmOnce()
        awaitTag("memo_reading_view")
        assertNull(memoFolder((memoIds() - before).single()))
    }

    // --- E: the choice survives a restart; 「フォルダなし（通常）」 clears it (the chip has no × — 2026-09-22 21:00) ---

    @Test
    fun theChoiceSurvivesARestartAndClears() {
        val work = folder("仕事")
        openChat()
        choose(work)
        composeRule.activityRule.scenario.onActivity { it.viewModelStore.clear() }
        composeRule.activityRule.scenario.recreate()
        awaitTag("chat_input")
        composeRule.onNodeWithTag("chat_folder_chip").assertTextContains("仕事", substring = true)
        assertEquals("no × to hit by accident", 0, count("chat_folder_clear"))
        composeRule.onNodeWithTag("chat_folder_chip").performClick()
        awaitTag("chat_folder_choice_none")
        composeRule.onNodeWithTag("chat_folder_choice_none").assertTextContains("通常", substring = true)
        composeRule.onNodeWithTag("chat_folder_choice_none").performClick()
        composeRule.waitUntil(5_000) { chosen() == null }
        composeRule.onNodeWithTag("chat_folder_chip").assertTextContains("フォルダを選択", substring = true)
    }

    // --- a folder made from the menu itself, chosen at once, and the wall has it ---

    @Test
    fun aFolderIsMadeFromTheMenuAndChosenAtOnce() {
        openChat()
        composeRule.onNodeWithTag("chat_folder_chip").performClick()
        awaitTag("chat_folder_new")
        composeRule.onNodeWithTag("chat_folder_new").performClick()
        awaitTag("chat_folder_new_input")
        composeRule.onNodeWithTag("chat_folder_new_input").performTextInput("読書")
        composeRule.onNodeWithTag("chat_folder_new_confirm").performClick()
        composeRule.waitUntil(5_000) { runBlocking { application.folderRepository.observeFolders().first() }.any { it.name == "読書" } }
        val made = runBlocking { application.folderRepository.observeFolders().first() }.first { it.name == "読書" }
        composeRule.waitUntil(5_000) { chosen() == made.id }
        composeRule.onNodeWithTag("chat_folder_chip").assertTextContains("読書", substring = true)
        val before = memoIds()
        pickTemplate(meeting)
        awaitQuestion("会議名は？")
        send("定例会")
        repeat(4) { awaitTag("chat_answer_skip"); composeRule.onNodeWithTag("chat_answer_skip", useUnmergedTree = true).performClick(); composeRule.waitForIdle() }
        awaitTag("chat_ai_preview_template")
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("読書", substring = true)
        confirmOnce()
        awaitTag("memo_reading_view")
        assertEquals(made.id, memoFolder((memoIds() - before).single()))
    }

    // --- F + H: a Think save goes to the folder; a journal starter keeps no folder ---

    @Test
    fun aThinkSaveGoesToTheFolderAndAJournalKeepsNone() {
        val work = folder("仕事")
        openChat()
        choose(work)
        val before = memoIds()
        pickTemplate(todayTasks)
        awaitQuestion("今日やる必要があることを、思いつくまま教えてください。")
        send("買い物")
        repeat(3) { awaitTag("chat_answer_skip"); composeRule.onNodeWithTag("chat_answer_skip", useUnmergedTree = true).performClick(); composeRule.waitForIdle() }
        awaitTag("chat_think_result")
        composeRule.onNodeWithTag("chat_think_save").performClick()
        awaitTag("chat_ai_preview_template")
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("仕事", substring = true)
        confirmOnce()
        awaitTag("memo_reading_view")
        assertEquals(work, memoFolder((memoIds() - before).single()))
        // a journal: the chosen folder changes nothing (back from the opened memo to the same conversation)
        androidx.test.espresso.Espresso.pressBack()
        // Back from the opened document is the conversation again; tapping チャット here would now be a reselect (its home, docs/BOTTOM_NAV_RESELECT.md).
        awaitTag("chat_input")
        pickTemplate(review)
        awaitQuestion("今日の良かったことは？")
        send("進んだ")
        repeat(2) { awaitTag("chat_answer_skip"); composeRule.onNodeWithTag("chat_answer_skip", useUnmergedTree = true).performClick(); composeRule.waitForIdle() }
        awaitTag("chat_ai_preview_template")
        assertEquals("a journal names no folder", 0, count("chat_ai_preview_folder"))
        confirmOnce()
        composeRule.waitUntil(15_000) { journals().size == 1 }
        assertEquals(work, memoFolder((memoIds() - before).single()))
    }

    // --- G: with a model, an AI CREATE takes the folder too ---

    @Test
    fun withAModelAnAiCreateTakesTheFolder() {
        TestAiSelection.descriptor = TestAiSelection.default; TestGuards.online = true
        val work = folder("仕事")
        openChat()
        choose(work)
        val before = memoIds()
        TestAiRuntime.answers.add(proposal("CREATE", kind = "MEMO", text = "散歩のメモ"))
        send("散歩のメモを作って")
        awaitTag("chat_ai_preview_create")
        composeRule.onNodeWithTag("chat_ai_preview_create").assertTextContains("仕事", substring = true)
        confirmOnce()
        awaitTag("memo_reading_view")
        assertEquals(work, memoFolder((memoIds() - before).single()))
        assertEquals(1, TestAiRuntime.loads)
    }
}

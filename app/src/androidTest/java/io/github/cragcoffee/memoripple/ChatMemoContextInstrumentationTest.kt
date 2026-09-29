package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.withTransaction
import io.github.cragcoffee.memoripple.data.FolderResult
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 「メモを選択」 (docs/CHAT_MEMO_CONTEXT.md, human decisions 2026-09-26): beside 「フォルダを選択」, a chip
 * points the conversation at one existing memo. The selection is a context, never a write
 * authority — it stands where the conversation's anchor stands, so 「このメモを開いて」, 「『…』を追記して」
 * and 「追記して」 read it through the unchanged pipeline (preview, Human Confirmation, the version
 * check), with no model. It is kept per conversation (ids in a preference), goes with its
 * conversation, and lets go of a memo that is no longer a memo the chat may point at.
 */
class ChatMemoContextInstrumentationTest {
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
        TestAiRuntime.loadGate?.complete(Unit); TestAiRuntime.generateGate?.complete(Unit)
        runBlocking {
            application.aiOrchestrator.release()
            application.settingsRepository.setLastChatConversationId(null)
            application.settingsRepository.setChatCreateFolderId(null)
            application.chatMemoSelectionStore.clear()
        }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0; TestGuards.reset()
    }

    // --- fixtures ---

    private fun memo(title: String, body: String, kind: MemoKind = MemoKind.MEMO, folderId: Long? = null): Long = runBlocking {
        val saved = application.memoRepository.save(existing = null, title = title, body = body, now = application.timeProvider.nowMillis(), kind = kind, folderId = folderId)!!
        saved.id
    }
    private fun find(id: Long): MemoEntity? = runBlocking { application.database.memoDao().findById(id) }
    private fun everything(): String = runBlocking {
        val memos = application.database.backupDao().readMemos().sortedBy { it.id }.joinToString("\n") { "${it.id}|${it.title}|${it.body}|${it.updatedAt}|${it.folderId}|${it.archivedAt}|${it.trashedAt}" }
        val blocks = application.database.backupDao().readMemoContentBlocks().sortedWith(compareBy({ it.memoId }, { it.id })).joinToString("\n") { "${it.memoId}|${it.id}|${it.text}" }
        memos + "\n--\n" + blocks
    }
    private fun folder(name: String): Long = runBlocking { (application.folderRepository.create(name, null) as FolderResult.Done).id }
    private fun conversations() = runBlocking { application.chatHistoryRepository.conversations().first() }
    private fun transcript(id: Long) = runBlocking { application.chatHistoryRepository.messages(id).first() }
    private fun selections() = runBlocking { application.chatMemoSelectionStore.selections.first() }

    // --- driving the chat ---

    private fun openChat() { composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input") }
    private fun awaitTag(tag: String, timeout: Long = 15_000) { composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
    private fun awaitGone(tag: String, timeout: Long = 15_000) { composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() } }
    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun send(text: String) {
        composeRule.onNodeWithTag("chat_input").performTextClearance()
        composeRule.onNodeWithTag("chat_input").performTextInput(text)
        composeRule.onNodeWithTag("chat_send").performClick()
    }
    private fun selectMemo(id: Long) {
        composeRule.onNodeWithTag("chat_memo_chip").performClick()
        awaitTag("chat_memo_picker")
        // the sheet opens on the chosen folder (2026-09-27); a memo outside it is reached through すべてのフォルダ, as a user would
        if (count("chat_memo_choice_$id") == 0 && count("chat_memo_picker_folder") > 0) {
            composeRule.onNodeWithTag("chat_memo_picker_folder").performClick()
            awaitTag("chat_memo_picker_folder_all")
            composeRule.onNodeWithTag("chat_memo_picker_folder_all").performClick()
        }
        awaitTag("chat_memo_choice_$id")
        composeRule.onNodeWithTag("chat_memo_choice_$id").performClick()
        awaitTag("chat_memo_selected")
    }
    private fun awaitQuestion(text: String) {
        composeRule.waitUntil(15_000) { conversations().singleOrNull()?.let { c -> transcript(c.id).lastOrNull { it.role == ChatRole.ASSISTANT }?.text?.contains(text) } == true }
    }
    /** A message that makes the conversation and asks nothing of the documents: with no model, the setup card. */
    private fun startConversation(text: String = "こんにちは") {
        val before = conversations().map { it.id }.toSet()
        send(text)
        // the ask is over only when its answer is in the transcript (it also remembers the conversation by then)
        composeRule.waitUntil(15_000) {
            conversations().firstOrNull { it.id !in before }?.let { c -> transcript(c.id).any { it.role == ChatRole.ASSISTANT } } == true
        }
        composeRule.waitForIdle()
    }

    // --- the chip and the picker ---

    @Test
    fun theHomeShowsBothChipsAndThePickerOffersActiveMemosOnly() {
        val memoId = memo("買い物", "牛乳\n卵")
        val outline = memo("計画", "- 旅行", kind = MemoKind.OUTLINE)
        val archived = memo("しまった", "x").also { runBlocking { application.memoRepository.archive(it) } }
        val trashed = memo("捨てた", "y").also { runBlocking { application.memoRepository.moveToTrash(it) } }
        openChat()
        composeRule.onNodeWithTag("chat_folder_chip").assertTextContains("フォルダを選択", substring = true)
        composeRule.onNodeWithTag("chat_memo_chip").assertTextContains("メモを選択", substring = true)
        composeRule.onNodeWithTag("chat_memo_chip").performClick()
        awaitTag("chat_memo_picker")
        awaitTag("chat_memo_choice_$memoId")
        composeRule.onNodeWithTag("chat_memo_choice_$memoId").assertTextContains("牛乳", substring = true)
        assertEquals("no outline", 0, count("chat_memo_choice_$outline"))
        assertEquals("no archive", 0, count("chat_memo_choice_$archived"))
        assertEquals("no trash", 0, count("chat_memo_choice_$trashed"))
    }

    @Test
    fun choosingNamesTheMemoWritesNothingAndTheCrossLetsGo() {
        val memoId = memo("買い物", "牛乳")
        openChat()
        val before = everything()
        selectMemo(memoId)
        composeRule.onNodeWithTag("chat_memo_selected").assertTextContains("買い物", substring = true)
        assertEquals("choosing is not writing", before, everything())
        composeRule.onNodeWithTag("chat_memo_clear").performClick()
        awaitTag("chat_memo_chip")
        composeRule.onNodeWithTag("chat_memo_chip").assertTextContains("メモを選択", substring = true)
        assertEquals(before, everything())
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- OPEN and APPEND on the selected memo, with no model ---

    @Test
    fun thisMemoOpensTheSelectedMemo() {
        val memoId = memo("買い物", "牛乳")
        memo("別のメモ", "x")
        openChat()
        selectMemo(memoId)
        send("このメモを開いて")
        awaitTag("memo_reading_view")
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("牛乳", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, TestAiRuntime.loads)
    }

    @Test
    fun aTargetlessAppendPreviewsOnTheSelectedMemoAndConfirmAppendsToItsEndOnly() {
        val chosen = memo("買い物", "牛乳")
        val other = memo("別のメモ", "そのまま")
        openChat()
        selectMemo(chosen)
        val before = everything()
        send("『卵』を追記して")
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("買い物", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("卵", substring = true)
        assertEquals("nothing before the confirmation", before, everything())
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        composeRule.waitUntil(10_000) { find(chosen)!!.body != "牛乳" }
        assertEquals("牛乳\n卵", find(chosen)!!.body)
        assertEquals("そのまま", find(other)!!.body)
        assertEquals(0, TestAiRuntime.loads)
    }

    @Test
    fun aBareAppendAsksWhatToAppendAndThenPreviewsOnTheSelectedMemo() {
        val chosen = memo("買い物", "牛乳")
        openChat()
        selectMemo(chosen)
        send("追記して")
        awaitQuestion("何を追記しますか？")
        send("パン")
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("買い物", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        composeRule.waitUntil(10_000) { find(chosen)!!.body == "牛乳\nパン" }
        assertEquals(0, TestAiRuntime.loads)
    }

    @Test
    fun aMemoChangedAfterThePreviewIsAConflictAndNothingIsAppended() {
        val chosen = memo("買い物", "牛乳")
        openChat()
        selectMemo(chosen)
        send("『卵』を追記して")
        awaitTag("chat_ai_preview_append")
        // another hand writes the memo while the preview is on screen
        runBlocking { application.memoRepository.save(find(chosen), "買い物", "牛乳\n別の手", application.timeProvider.nowMillis() + 1_000) }
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        awaitTag("chat_ai_write_conflict")
        assertEquals("the other hand's words, and no silent retry", "牛乳\n別の手", find(chosen)!!.body)
    }

    @Test
    fun anExplicitlyNamedMemoWinsOverTheSelection() {
        val chosen = memo("買い物", "牛乳")
        val named = memo("日誌", "朝")
        openChat()
        selectMemo(chosen)
        send("日誌に『昼』を追記して")
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("日誌", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        composeRule.waitUntil(10_000) { find(named)!!.body == "朝\n昼" }
        assertEquals("牛乳", find(chosen)!!.body)
    }

    // --- the folder and the memo keep their jobs ---

    @Test
    fun theFolderIsForCreatingAndTheMemoForAppending() {
        val work = folder("仕事")
        val chosen = memo("買い物", "牛乳")
        openChat()
        composeRule.onNodeWithTag("chat_folder_chip").performClick()
        awaitTag("chat_folder_choice_$work")
        composeRule.onNodeWithTag("chat_folder_choice_$work").performClick()
        composeRule.waitUntil(5_000) { runBlocking { application.settingsRepository.chatCreateFolderId.first() } == work }
        selectMemo(chosen)
        send("『卵』を追記して")
        awaitTag("chat_ai_preview_append")
        assertEquals("an append names no 保存先", 0, count("chat_ai_preview_folder"))
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        composeRule.waitUntil(10_000) { find(chosen)!!.body == "牛乳\n卵" }
        assertNull("the memo stays where it is", find(chosen)!!.folderId)
        // a confirmed write opens the memo it wrote; back to the chat
        awaitTag("memo_reading_view")
        androidx.test.espresso.Espresso.pressBack()
        awaitTag("chat_input")
        // with the memo let go (×), a new memo goes to the chosen folder — while a memo is selected, making a memo
        // appends to it instead (the Human Review, 2026-09-27; ChatMemoFolderAndCreateInstrumentationTest)
        composeRule.onNodeWithTag("chat_memo_clear").performClick()
        awaitTag("chat_memo_chip")
        send("メモを作って")
        awaitTag("chat_ai_preview_create")
        composeRule.onNodeWithTag("chat_ai_preview_create").assertTextContains("保存先", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_create").assertTextContains("仕事", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_create").assertTextContains("（空のまま作成）", substring = true)
    }

    // --- per conversation, and only as long as the memo is one ---

    @Test
    fun theSelectionSurvivesRecreationWithoutAnyPendingWrite() {
        val chosen = memo("買い物", "牛乳")
        openChat()
        selectMemo(chosen)
        startConversation()
        val conversation = conversations().single().id
        composeRule.waitUntil(5_000) { selections()[conversation] == chosen }
        composeRule.activityRule.scenario.recreate()
        awaitTag("chat_memo_selected")
        composeRule.onNodeWithTag("chat_memo_selected").assertTextContains("買い物", substring = true)
        assertEquals(0, count("chat_ai_preview_append"))
    }

    @Test
    fun aNewChatHoldsItsChoiceUntilItsFirstMessageBindsIt() {
        val chosen = memo("買い物", "牛乳")
        openChat()
        selectMemo(chosen)
        assertTrue("nothing is stored before there is a conversation", selections().isEmpty())
        startConversation()
        val conversation = conversations().single().id
        composeRule.waitUntil(5_000) { selections() == mapOf(conversation to chosen) }
    }

    @Test
    fun conversationsKeepTheirOwnSelections() {
        val a = memo("買い物", "牛乳")
        val b = memo("日誌", "朝")
        openChat()
        selectMemo(a)
        startConversation()
        val first = conversations().single().id
        // a new chat: nothing selected there
        runBlocking { application.settingsRepository.setLastChatConversationId(null) }
        awaitTag("chat_memo_chip")
        composeRule.onNodeWithTag("chat_memo_chip").assertTextContains("メモを選択", substring = true)
        selectMemo(b)
        startConversation("またこんにちは")
        val second = conversations().first { it.id != first }.id
        composeRule.waitUntil(5_000) { selections() == mapOf(first to a, second to b) }
        // back to the first: its own memo, not the second's
        runBlocking { application.settingsRepository.setLastChatConversationId(first) }
        composeRule.waitUntil(10_000) { runCatching { composeRule.onNodeWithTag("chat_memo_selected").assertTextContains("買い物", substring = true) }.isSuccess }
    }

    @Test
    fun deletingAConversationTakesItsSelectionWithIt() {
        val chosen = memo("買い物", "牛乳")
        openChat()
        selectMemo(chosen)
        startConversation()
        val conversation = conversations().single().id
        composeRule.waitUntil(5_000) { selections()[conversation] == chosen }
        runBlocking { application.chatHistoryRepository.delete(conversation) }
        composeRule.waitUntil(5_000) { conversation !in selections() }
    }

    /** A selected memo taken away by [takeAway] lets the selection go: the chip asks again, the conversation's entry is gone. */
    private fun theSelectionLetsGoWhen(takeAway: (Long) -> Unit) {
        val chosen = memo("選んだメモ", "本文")
        openChat()
        selectMemo(chosen)
        startConversation()
        val conversation = conversations().single().id
        composeRule.waitUntil(5_000) { selections()[conversation] == chosen }
        takeAway(chosen)
        awaitTag("chat_memo_chip")
        composeRule.onNodeWithTag("chat_memo_chip").assertTextContains("メモを選択", substring = true)
        composeRule.waitUntil(5_000) { conversation !in selections() }
    }

    @Test
    fun aTrashedMemoLetsTheSelectionGo() = theSelectionLetsGoWhen { id -> runBlocking { application.memoRepository.moveToTrash(id) } }

    @Test
    fun anArchivedMemoLetsTheSelectionGo() = theSelectionLetsGoWhen { id -> runBlocking { application.memoRepository.archive(id) } }

    /**
     * A memo that no longer exists at all — what a restore or any other path can leave a stored selection pointing at
     * (the app itself deletes only from the trash, which the trash case already covers): the row goes by plain SQL.
     */
    @Test
    fun aDeletedMemoLetsTheSelectionGo() = theSelectionLetsGoWhen { id ->
        // inside a Room transaction, as every repository write and a restore are — so the observers hear it
        runBlocking { application.database.withTransaction { application.database.openHelper.writableDatabase.execSQL("DELETE FROM memos WHERE id = ?", arrayOf<Any>(id)) } }
    }
}

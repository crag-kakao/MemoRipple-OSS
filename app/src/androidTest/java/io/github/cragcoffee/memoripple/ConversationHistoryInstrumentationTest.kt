package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
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
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Phase 8 journeys 1–6 (docs/AI_CONVERSATION_HISTORY.md) on the real screen, database and
 * pipeline with the scripted runtime: a transcript that persists, 「2番目を開いて」 through the
 * latest results, 「それに追記して」 and 「さっきのメモに追記して」 through the anchor, a new
 * conversation with nothing carried over, process death that keeps the transcript and drops the
 * preview with zero writes.
 */
class ConversationHistoryInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication
    private val history get() = application.chatHistoryRepository
    private val today: LocalDate get() = application.timeProvider.currentLocalDate()

    @Before
    fun startEmptyAndScripted() {
        runBlocking {
            application.database.clearAllTables()
            application.settingsRepository.setAutoPlayOnLaunch(false)
            application.settingsRepository.setLastChatConversationId(null)
            application.aiOrchestrator.release()
        }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0
        TestGuards.fastPathEnabled = false   // this class exercises the model pipeline; its plain sentences must reach the generator (docs/CHAT_FAST_PATH.md)
    }

    @After
    fun unload() {
        TestGuards.fastPathEnabled = true   // hand the product default back to the suite
        TestAiRuntime.loadGate?.complete(Unit); TestAiRuntime.generateGate?.complete(Unit)
        runBlocking { application.aiOrchestrator.release(); application.settingsRepository.setLastChatConversationId(null) }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0
    }

    private fun at(date: LocalDate, hour: Int): Long = application.timeProvider.toEpochMillis(LocalDateTime.of(date, java.time.LocalTime.of(hour, 0)))
    private fun memo(title: String, body: String): Long = runBlocking {
        val t = at(today, 9); application.database.memoDao().insert(MemoEntity(title = title, body = body, createdAt = t, updatedAt = t, kind = "memo"))
    }
    private fun journal(body: String, date: LocalDate, hour: Int): Long = runBlocking {
        application.database.diaryDao().insert(DiaryEntryEntity(diaryDateEpochDay = date.toEpochDay(), body = body, state = DiaryState.DRAFT, createdAt = at(date, hour), updatedAt = at(date, hour)))
    }
    private fun memoBody(id: Long) = runBlocking { application.database.memoDao().findById(id)!!.body }
    private fun memoIds() = runBlocking { application.database.memoDao().allIds().toSet() }
    private fun snapshot(): String = runBlocking {
        val dao = application.database.memoDao()
        val memos = dao.allIds().sorted().map { id -> dao.findById(id)!!.let { "memo|${it.id}|${it.title}|${it.body}|${it.updatedAt}" } }
        val journals = application.database.diaryDao().observeAll().first().sortedBy { it.id }.map { "journal|${it.id}|${it.body}|${it.updatedAt}" }
        (memos + journals).joinToString("\n")
    }
    private fun conversations() = runBlocking { history.conversations().first() }
    private fun transcript(id: Long) = runBlocking { history.messages(id).first() }

    private fun proposal(intent: String, query: String? = null, targetRef: String? = null, targetName: String? = null, documentKind: String? = null, text: String? = null, dateToken: String? = null, missing: List<String> = emptyList()): String {
        fun s(v: String?) = if (v == null) "null" else "\"" + v.replace("\"", "\\\"") + "\""
        return "{\"intent\":${s(intent)},\"query\":${s(query)},\"targetRef\":${s(targetRef)},\"targetName\":${s(targetName)},\"documentKind\":${s(documentKind)}," +
            "\"text\":${s(text)},\"templateId\":null,\"dateToken\":${s(dateToken)},\"missingFields\":[${missing.joinToString(",") { s(it) }}]}"
    }
    private fun script(vararg answers: String) { TestAiRuntime.answers.addAll(answers) }

    private fun openChat() { composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input") }
    private fun aiMode() { awaitTag("chat_input") }   // no mode since the Chat UI redesign (2026-09-21): the chat input is the AI input
    private fun ask(text: String) {
        composeRule.onNodeWithTag("chat_input").performTextClearance()
        composeRule.onNodeWithTag("chat_input").performTextInput(text)
        composeRule.onNodeWithTag("chat_send").performClick()
    }
    private fun awaitTag(tag: String, timeout: Long = 15_000) {
        composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitGone(tag: String, timeout: Long = 15_000) {
        composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }
    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun awaitMessages(id: Long, n: Int) { composeRule.waitUntil(15_000) { transcript(id).size >= n } }

    // --- RED 1, 2, 15: a conversation exists only once a message is sent; search makes none ---

    @Test
    fun openingTheChatMakesNoConversationTheFirstMessageDoes() {
        memo("散歩のメモ", "本文")
        openChat()
        composeRule.onNodeWithTag("chat_new_conversation").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_history").assertIsDisplayed()
        assertTrue("no conversation yet", conversations().isEmpty())
        // typing and clearing a draft, or leaving and returning, creates nothing either
        composeRule.onNodeWithTag("chat_input").performTextInput("散歩"); composeRule.onNodeWithTag("chat_input").performTextClearance()
        // UI review 2026-09-21: the bottom navigation gives way while the keyboard is up on the chat — put it away first
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        awaitTag("nav_memos")
        composeRule.onNodeWithTag("nav_memos").performClick(); composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input")
        assertTrue(conversations().isEmpty())
        script(proposal("SEARCH", query = "散歩"))
        ask("散歩を探して")
        awaitTag("chat_ai_search_results")
        val c = conversations().single()
        assertEquals("散歩を探して", c.title)
        awaitMessages(c.id, 2)
        val t = transcript(c.id)
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), t.map { it.role })
        assertEquals("散歩を探して", t[0].text)
        assertTrue(t[1].text, t[1].text.contains("1件"))
        assertFalse("no raw JSON in the history", t.any { it.text.contains("intent") || it.text.contains("{") })
        assertEquals(c.id, runBlocking { application.settingsRepository.lastChatConversationId.first() })
    }

    // --- Journey 1 (RED 9, 10, 45): the transcript is on screen and survives recreation; the result card does not ---

    @Test
    fun theTranscriptShowsOnScreenAndSurvivesRecreationWhileTheResultCardDoesNot() {
        memo("散歩のメモ", "本文")
        openChat(); aiMode()
        script(proposal("SEARCH", query = "散歩"))
        ask("散歩を探して")
        awaitTag("chat_ai_search_results")
        val c = conversations().single()
        awaitMessages(c.id, 2)
        val (u, a) = transcript(c.id)
        awaitTag("chat_message_${a.id}")
        composeRule.onNodeWithTag("chat_message_${u.id}").assertTextContains("散歩を探して", substring = true)
        composeRule.onNodeWithTag("chat_message_${a.id}").assertTextContains("1件", substring = true)
        // a plain recreation keeps the view model (and its result); the process-death shape drops the result and keeps the transcript
        composeRule.activityRule.scenario.recreate()
        awaitTag("chat_message_${a.id}")
        composeRule.onNodeWithTag("chat_message_${u.id}").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.viewModelStore.clear() }
        composeRule.activityRule.scenario.recreate()
        awaitTag("chat_message_${a.id}")
        composeRule.onNodeWithTag("chat_message_${u.id}").assertIsDisplayed()
        assertEquals("the ephemeral result list is gone", 0, count("chat_ai_search_results"))
        assertEquals(1, conversations().size)
    }

    // --- Journey 2 (RED 16–20): SEARCH → 「2番目を開いて」 ---

    @Test
    fun theSecondOfTheLatestResultsOpens() {
        val d = today.minusDays(1)
        val first = journal("散歩の記録", d, 20); val second = journal("買い物の記録", d, 18); journal("会議の記録", d, 9)
        openChat(); aiMode()
        script(proposal("SEARCH", query = "記録", dateToken = "yesterday"))
        ask("昨日の記録を探して")
        awaitTag("chat_ai_search_results")
        awaitTag("chat_result_journal_$first")
        val c = conversations().single()
        val ctx = runBlocking { history.context(c.id) }
        assertEquals("the shown order is the stored order", 3, ctx.latestResults.size)
        val shownSecond = ctx.latestResults[1].ref.id
        script(proposal("OPEN", missing = listOf("targetName")))
        ask("2番目を開いて")
        awaitTag("diary_body")
        assertTrue(shownSecond == second || shownSecond == first)   // updatedAt DESC → the 18:00 entry is second
        assertEquals(second, shownSecond)
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_input")
        awaitMessages(c.id, 4)
        assertTrue(transcript(c.id)[3].text.contains("買い物の記録"))
        assertEquals("the opened journal is the anchor now", second, runBlocking { history.context(c.id) }.lastDocumentAnchor?.id)
    }

    // --- Journey 3 (RED 23–25, 30): OPEN → 「それに追記して」 → preview → cancel → nothing written ---

    @Test
    fun itAppendsToTheLastOpenedDocumentThroughAPreviewAndACancelWritesNothing() {
        val dev = memo("MemoRipple開発", "## 進捗")
        openChat(); aiMode()
        script(proposal("OPEN", targetName = "MemoRipple開発"))
        ask("MemoRipple開発を開いて")
        awaitTag("memo_reading_view")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_input")
        val before = snapshot()
        script(proposal("APPEND", text = "完了", missing = listOf("targetName")))
        ask("それに『完了』を追記して")
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("MemoRipple開発", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        awaitGone("chat_ai_preview_append")
        assertEquals(before, snapshot())
        assertEquals("## 進捗", memoBody(dev))
        val c = conversations().single()
        awaitMessages(c.id, 5)
        assertTrue(transcript(c.id).last().text.contains("キャンセル"))
        script(proposal("APPEND", text = "完了", missing = listOf("targetName")))
        ask("それに『完了』を追記して")
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
    }

    // --- Journey 4 (RED 26, 29, 32, 33): CREATE confirm → 「さっきのメモに追記して」 → preview → confirm → one append ---

    @Test
    fun theMemoJustCreatedIsTheAnchorAndOneConfirmationAppendsOnce() {
        openChat(); aiMode()
        val before = memoIds()
        script(proposal("CREATE", documentKind = "MEMO", text = "買い物リスト"))
        ask("買い物リストというメモを作って")
        awaitTag("chat_ai_preview_create")
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        awaitTag("memo_reading_view")
        val created = (memoIds() - before).single()
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_ai_write_success")
        composeRule.onNodeWithTag("chat_ai_dismiss").performClick()
        script(proposal("APPEND", text = "追記", missing = listOf("targetName")))
        ask("さっきのメモに「追記」を追加して")
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("買い物リスト", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        composeRule.onAllNodesWithTag("chat_ai_preview_confirm").fetchSemanticsNodes().firstOrNull()?.let { runCatching { composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick() } }
        awaitTag("memo_reading_view")
        assertEquals("買い物リスト\n追記", memoBody(created))
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_ai_write_success")
        assertEquals("exactly once", "買い物リスト\n追記", memoBody(created))
        val c = conversations().single()
        awaitMessages(c.id, 6)
        val texts = transcript(c.id).map { it.text }
        assertTrue(texts.any { it.contains("作成しました") } && texts.any { it.contains("追記しました") })
    }

    // --- Journey 5 (RED 11, 12, 28): a new conversation carries nothing over; the old one reopens whole ---

    @Test
    fun aNewConversationHasNoContextAndTheOldOneReopensFromTheHistoryList() {
        val first = memo("MemoRipple開発", "a"); memo("買い物", "b")
        openChat(); aiMode()
        script(proposal("SEARCH", query = "開発"))
        ask("開発を探して")
        awaitTag("chat_result_memo_$first")
        val old = conversations().single()
        awaitMessages(old.id, 2)
        // UI review 2026-09-21: 新しいチャット is its own stage; it clears the remembered conversation and makes none
        composeRule.onNodeWithTag("chat_new_conversation").performClick()
        awaitTag("chat_stage")
        awaitGone("chat_ai_search_results")
        assertEquals("no empty conversation is made by the button alone", 1, conversations().size)
        composeRule.waitUntil(15_000) { runBlocking { application.settingsRepository.lastChatConversationId.first() } == null }
        script(proposal("OPEN", missing = listOf("targetName")))
        ask("2番目を開いて")
        awaitTag("chat_ai_needs_information")
        assertEquals(0, count("memo_reading_view"))
        val fresh = conversations().first { it.id != old.id }
        assertEquals("2番目を開いて", fresh.title)
        script(proposal("APPEND", text = "x", missing = listOf("targetName")))
        ask("それに『x』を追記して")
        awaitTag("chat_ai_needs_information")
        composeRule.onNodeWithTag("chat_overflow").performClick()
        awaitTag("chat_open_history")
        composeRule.onNodeWithTag("chat_open_history").performClick()
        awaitTag("chat_history_screen")
        composeRule.onNodeWithTag("chat_history_row_${old.id}").assertTextContains("開発を探して", substring = true)
        composeRule.onNodeWithTag("chat_history_row_${fresh.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_history_row_${old.id}").performClick()
        awaitTag("chat_input")
        val (u, a) = transcript(old.id)
        awaitTag("chat_message_${a.id}")
        composeRule.onNodeWithTag("chat_message_${u.id}").assertTextContains("開発を探して", substring = true)
        assertEquals(old.id, runBlocking { application.settingsRepository.lastChatConversationId.first() })
        // the old conversation's results are back: 「1番目を開いて」 opens MemoRipple開発
        script(proposal("OPEN", missing = listOf("targetName")))
        ask("1番目を開いて")
        awaitTag("memo_reading_view")
    }

    // --- RED 13, 14: delete from the list, with a confirmation; documents untouched ---

    @Test
    fun deletingAConversationFromTheListAsksAndRemovesOnlyThatConversation() {
        val dev = memo("MemoRipple開発", "## 進捗")
        openChat(); aiMode()
        script(proposal("OPEN", targetName = "MemoRipple開発"))
        ask("MemoRipple開発を開いて")
        awaitTag("memo_reading_view")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_input")
        val c = conversations().single()
        awaitMessages(c.id, 2)
        // the menu icon opens the history drawer (2026-09-21 evening); the full screen with its delete buttons is behind 履歴を管理
        composeRule.onNodeWithTag("chat_history").performClick()
        awaitTag("chat_drawer")
        composeRule.onNodeWithTag("chat_drawer_manage", useUnmergedTree = true).performClick()
        awaitTag("chat_history_screen")
        composeRule.onNodeWithTag("chat_history_delete_${c.id}").performClick()
        awaitTag("chat_history_delete_dialog")
        composeRule.onNodeWithTag("chat_history_delete_cancel").performClick()
        assertEquals(1, conversations().size)
        composeRule.onNodeWithTag("chat_history_delete_${c.id}").performClick()
        awaitTag("chat_history_delete_dialog")
        composeRule.onNodeWithTag("chat_history_delete_confirm").performClick()
        composeRule.waitUntil(15_000) { conversations().isEmpty() }
        assertTrue(transcript(c.id).isEmpty())
        awaitTag("chat_history_empty")
        assertEquals("## 進捗", memoBody(dev))
        assertEquals(setOf(dev), memoIds())
        composeRule.onNodeWithTag("chat_history_back").performClick()
        awaitTag("chat_input")
        assertEquals("nothing left to show", 0, count("chat_transcript_row"))
        assertNull(runBlocking { application.settingsRepository.lastChatConversationId.first() })
    }

    // --- Journey 6 (RED 45–48): process death keeps the transcript, drops the preview, writes nothing ---

    @Test
    fun processDeathKeepsTheTranscriptDropsThePreviewAndWritesNothing() {
        val dev = memo("MemoRipple開発", "## 進捗")
        openChat(); aiMode()
        script(proposal("OPEN", targetName = "MemoRipple開発"))
        ask("MemoRipple開発を開いて")
        awaitTag("memo_reading_view")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_input")
        script(proposal("APPEND", targetName = "MemoRipple開発", text = "完了"))
        ask("MemoRipple開発に『完了』を追記して")
        awaitTag("chat_ai_preview_append")
        val c = conversations().single()
        awaitMessages(c.id, 4)
        val before = snapshot()
        composeRule.activityRule.scenario.onActivity { it.viewModelStore.clear() }
        composeRule.activityRule.scenario.recreate()
        awaitTag("chat_input")
        val messages = transcript(c.id)
        awaitTag("chat_message_${messages.last().id}")
        assertEquals(4, messages.size)
        assertEquals(0, count("chat_ai_preview_append"))
        assertEquals(0, count("chat_ai_preview_confirm"))
        assertEquals(before, snapshot())
        assertEquals("## 進捗", memoBody(dev))
        // the anchor is still the opened memo: 「それに追記して」 makes a fresh preview, still unconfirmed
        script(proposal("APPEND", text = "完了", missing = listOf("targetName")))
        ask("それに『完了』を追記して")
        awaitTag("chat_ai_preview_append")
        assertEquals("## 進捗", memoBody(dev))
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
    }
}

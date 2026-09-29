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
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The Fast Path's journeys A–G (docs/CHAT_FAST_PATH.md), all but F with **no model installed**:
 * an unmistakable sentence works without a setup card and without a load; a write still stops at
 * its preview and the one confirmation; an ambiguous sentence still gets the setup card; with a
 * model an ordinary sentence still takes the AI route; the transcript reads the same either way.
 */
class ChatFastPathInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication
    private val today: LocalDate get() = application.timeProvider.currentLocalDate()

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
            application.templateRepository.replaceAll(emptyList())
            application.recentTemplateRepository.clear()
        }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0; TestGuards.reset()
    }

    private fun at(date: LocalDate, hour: Int): Long = application.timeProvider.toEpochMillis(LocalDateTime.of(date, java.time.LocalTime.of(hour, 0)))
    private fun memo(title: String, body: String): Long = runBlocking {
        val t = at(today, 9); application.database.memoDao().insert(MemoEntity(title = title, body = body, createdAt = t, updatedAt = t, kind = "memo"))
    }
    private fun journal(body: String, date: LocalDate): Long = runBlocking {
        application.database.diaryDao().insert(DiaryEntryEntity(diaryDateEpochDay = date.toEpochDay(), body = body, state = DiaryState.DRAFT, createdAt = at(date, 8), updatedAt = at(date, 8)))
    }
    private fun memoIds() = runBlocking { application.database.memoDao().allIds().toSet() }
    private fun memoBody(id: Long) = runBlocking { application.database.memoDao().findById(id)!!.body }
    private fun snapshot(): String = runBlocking {
        val dao = application.database.memoDao()
        val memos = dao.allIds().sorted().map { id -> dao.findById(id)!!.let { "memo|${it.id}|${it.title}|${it.body}|${it.updatedAt}" } }
        val js = application.database.diaryDao().observeAll().first().sortedBy { it.id }.map { "journal|${it.id}|${it.body}|${it.updatedAt}" }
        (memos + js).joinToString("\n")
    }
    private fun conversations() = runBlocking { application.chatHistoryRepository.conversations().first() }
    private fun transcript(id: Long) = runBlocking { application.chatHistoryRepository.messages(id).first() }
    private fun openChat() { composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input") }
    private fun send(text: String) {
        composeRule.onNodeWithTag("chat_input").performTextClearance()
        composeRule.onNodeWithTag("chat_input").performTextInput(text)
        composeRule.onNodeWithTag("chat_send").performClick()
    }
    private fun awaitTag(tag: String, timeout: Long = 15_000) { composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
    private fun awaitGone(tag: String, timeout: Long = 15_000) { composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() } }
    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun confirmOnce() {
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        composeRule.onAllNodesWithTag("chat_ai_preview_confirm").fetchSemanticsNodes().firstOrNull()?.let { runCatching { composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick() } }
    }
    private fun proposal(intent: String, query: String? = null): String {
        fun s(v: String?) = if (v == null) "null" else "\"" + v.replace("\"", "\\\"") + "\""
        return "{\"intent\":${s(intent)},\"query\":${s(query)},\"targetRef\":null,\"targetName\":null,\"documentKind\":null,\"text\":null,\"templateId\":null,\"dateToken\":null,\"missingFields\":[]}"
    }

    // --- A: no model → 「昨日の日記を開いて」 → no setup card, the journal opens, load 0 ---

    @Test
    fun aFastOpenWorksWithNoModelAndNoSetupCard() {
        journal("昨日のこと", today.minusDays(1)); journal("今日のこと", today)
        openChat()
        send("昨日の日記を開いて")
        awaitTag("diary_body")
        composeRule.onNodeWithTag("diary_body").assertTextContains("昨日のこと", substring = true)
        assertEquals(0, TestAiRuntime.loads)
        // the transcript reads like any other: the ask, then the opened line — once each
        val c = conversations().single()
        val lines = transcript(c.id).map { "${it.role.name}:${it.text}" }
        assertEquals(lines.toString(), 1, lines.count { it == "USER:昨日の日記を開いて" })
        assertEquals(lines.toString(), 1, lines.count { it.startsWith("ASSISTANT:") && it.contains("開きました") })
        assertTrue(lines.toString(), lines.none { it.contains("Fast") })
    }

    // --- B: no model → 「旅行を探して」 → result cards, load 0 ---

    @Test
    fun bFastSearchWorksWithNoModel() {
        val trip = memo("旅行の計画", "沖縄")
        memo("買い物リスト", "牛乳")
        openChat()
        send("旅行を探して")
        awaitTag("chat_ai_search_results")
        composeRule.onNodeWithTag("chat_result_memo_$trip").assertIsDisplayed()
        assertEquals(0, count("chat_ai_model_unavailable"))
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- C: no model → 「メモを作って」 → the preview → cancel → write 0; then confirm → exactly one ---

    @Test
    fun cFastCreateStopsAtThePreviewAndOneConfirmationMakesOne() {
        openChat()
        val before = snapshot()
        send("メモを作って")
        awaitTag("chat_ai_preview_create")
        assertEquals(before, snapshot())
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        awaitGone("chat_ai_preview_create")
        assertEquals(before, snapshot())
        val ids = memoIds()
        send("メモを作って")
        awaitTag("chat_ai_preview_create")
        confirmOnce()
        awaitTag("memo_body")   // an empty new memo opens writing (the standing rule)
        assertEquals(1, (memoIds() - ids).size)
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- D: no model → the explicit APPEND → the preview → cancel → write 0 ---

    @Test
    fun dFastAppendStopsAtThePreviewAndACancelWritesNothing() {
        memo("MemoRipple開発", "## 進捗")
        openChat()
        val before = snapshot()
        send("『MemoRipple開発』に『Folder対応完了』を追記して")
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("MemoRipple開発", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("Folder対応完了", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        awaitGone("chat_ai_preview_append")
        assertEquals(before, snapshot())
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- E: no model → an ambiguous sentence → the setup card, as before ---

    @Test
    fun eAmbiguousWithNoModelStillGetsTheSetupCard() {
        openChat()
        send("昨日のことをなんかいい感じにして")
        awaitTag("chat_ai_model_unavailable")
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- F: a model → an ordinary sentence → the AI route, unchanged ---

    @Test
    fun fWithAModelAnOrdinarySentenceTakesTheAiRoute() {
        TestAiSelection.descriptor = TestAiSelection.default; TestGuards.online = true
        memo("散歩のメモ", "本文")
        openChat()
        TestAiRuntime.answers.add(proposal("SEARCH", query = "散歩"))
        send("散歩について何かあったっけ")
        awaitTag("chat_ai_search_results")
        assertEquals("the AI route loaded once", 1, TestAiRuntime.loads)
    }

    // --- G: OPEN by name → 「それに…を追記して」 rides the anchor → the preview → cancel; load 0 throughout ---

    @Test
    fun gAReferentAppendRidesTheAnchorWithNoModel() {
        val dev = memo("MemoRipple開発", "## 進捗")
        openChat()
        send("『MemoRipple開発』を開いて")
        awaitTag("memo_reading_view")
        androidx.test.espresso.Espresso.pressBack()
        // Back from the opened document is the conversation again; tapping チャット here would now be a reselect (its home, docs/BOTTOM_NAV_RESELECT.md).
        awaitTag("chat_input")
        val before = snapshot()
        send("それに『会話テスト』を追記して")
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("MemoRipple開発", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        awaitGone("chat_ai_preview_append")
        assertEquals(before, snapshot())
        assertEquals("## 進捗", memoBody(dev))
        assertEquals(0, TestAiRuntime.loads)
    }
}

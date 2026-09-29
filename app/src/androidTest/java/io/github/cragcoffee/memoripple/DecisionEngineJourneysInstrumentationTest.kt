package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * DecisionEngine journeys (Phase 3, docs/DECISION_ENGINE.md, human brief 2026-09-23). Between the
 * Fast Path and the model sits a pure, deterministic decision: an ordinal over the shown results
 * opens with zero loads (A); a demonstrative append without a body asks 「何を追記しますか？」 and the
 * answer fills only that slot, to the same preview and cancel (B); an explicit demonstrative
 * append rides the context to its preview (C); competing candidates become fixed chips, never a
 * silent pick (D); no context and a conversational sentence keep the setup card (E); with a model
 * the conversational sentence still reaches the generation route and only then loads (F); and a
 * process death mid-clarification keeps the transcript, drops the pending question and writes
 * nothing (G).
 */
class DecisionEngineJourneysInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    @Before
    fun cleanChat() {
        TestModelServer.reset(); TestGuards.reset(); TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0
        TestIdleClock.reset()
        runBlocking {
            application.database.clearAllTables()
            application.templateRepository.replaceAll(emptyList())
            application.settingsRepository.setAutoPlayOnLaunch(false)
            application.settingsRepository.setLastChatConversationId(null)
            application.aiOrchestrator.release()
            application.settingsRepository.setSelectedAiModelId(null)
        }
        TestAiRuntime.reset()
    }

    @After
    fun tidy() {
        TestAiRuntime.loadGate?.complete(Unit); TestAiRuntime.generateGate?.complete(Unit)
        TestIdleClock.reset()
        runBlocking { application.aiOrchestrator.release() }
        TestModelServer.reset(); TestGuards.reset(); TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0
    }

    private fun openChat() { composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input") }
    private fun send(text: String) {
        composeRule.onNodeWithTag("chat_input").performTextInput(text)
        composeRule.onNodeWithTag("chat_send").performClick()
    }
    private fun awaitTag(tag: String) {
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitGone(tag: String) {
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    }
    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    private fun memo(title: String, updatedAt: Long = 1, createdAt: Long = 1): Long =
        runBlocking { application.database.memoDao().insert(MemoEntity(title = title, body = "本文", createdAt = createdAt, updatedAt = updatedAt, kind = "memo")) }
    private fun yesterdayMillis(): Long = java.time.LocalDate.now().minusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).plusHours(12).toInstant().toEpochMilli()
    private fun memoIds() = runBlocking { application.database.memoDao().allIds() }.toSet()
    private fun conversations() = runBlocking { application.chatHistoryRepository.conversations().first() }
    private fun transcript(id: Long) = runBlocking { application.chatHistoryRepository.messages(id).first() }
    private fun awaitQuestion(text: String) {
        composeRule.waitUntil(15_000) { conversations().firstOrNull()?.let { c -> transcript(c.id).lastOrNull { it.role == ChatRole.ASSISTANT }?.text?.contains(text) } == true }
    }
    // --- A: no model → shown results → 「2番目を開いて」 opens the right one with zero loads ---

    @Test
    fun anOrdinalOpensTheSecondShownResultWithNoModel() {
        TestAiSelection.descriptor = null
        memo("散歩の記録", updatedAt = 30)
        val second = memo("散歩の予定", updatedAt = 20)
        memo("散歩のメモ", updatedAt = 10)
        openChat()
        send("散歩を探して")   // the Fast Path's plain search shows all three, newest first
        awaitTag("chat_ai_search_results")
        send("2番目を開いて")
        awaitTag("memo_reading_view")
        val conversation = conversations().single()
        assertEquals("the second shown result became the anchor", second, runBlocking { application.chatHistoryRepository.context(conversation.id) }.lastDocumentAnchor?.id)
        assertEquals(0, TestAiRuntime.loads)
        assertEquals(0, TestAiRuntime.requests)
    }

    // --- B: no model → open → 「それに追記して」 → the fixed question → the answer → preview → cancel ---

    @Test
    fun aDemonstrativeAppendAsksForTheBodyAndTheAnswerFillsOnlyThatSlot() {
        TestAiSelection.descriptor = null
        memo("開発メモ")
        openChat()
        send("『開発メモ』を開いて")
        awaitTag("memo_reading_view")
        Espresso.pressBack()
        // Back from the opened document is the conversation again; tapping チャット here would now be a reselect (its home, docs/BOTTOM_NAV_RESELECT.md).
        awaitTag("chat_input")
        val before = memoIds()
        send("それに追記して")
        awaitQuestion("何を追記しますか？")
        assertEquals("the question costs no model", 0, TestAiRuntime.loads)
        send("Folder対応完了")
        awaitTag("chat_ai_preview_cancel")
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        awaitGone("chat_ai_preview_cancel")
        assertEquals(before, memoIds())
        assertEquals(0, TestAiRuntime.loads)
        assertEquals(0, TestAiRuntime.requests)
    }

    // --- C: no model → open → explicit demonstrative append with a body → preview → cancel ---

    @Test
    fun anExplicitDemonstrativeAppendStopsAtItsPreviewWithNoModel() {
        TestAiSelection.descriptor = null
        memo("開発メモ")
        openChat()
        send("『開発メモ』を開いて")
        awaitTag("memo_reading_view")
        Espresso.pressBack()
        // Back from the opened document is the conversation again; tapping チャット here would now be a reselect (its home, docs/BOTTOM_NAV_RESELECT.md).
        awaitTag("chat_input")
        val before = memoIds()
        send("それに『完了』を追記して")
        awaitTag("chat_ai_preview_cancel")
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        awaitGone("chat_ai_preview_cancel")
        assertEquals(before, memoIds())
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- D: competing candidates become chips; a pick continues safely; nothing is chosen alone ---

    @Test
    fun competingTargetsBecomeChoicesAndThePickContinuesToThePreview() {
        TestAiSelection.descriptor = null
        val y = yesterdayMillis()
        memo("散歩の記録", updatedAt = y + 30, createdAt = y); memo("買い物の記録", updatedAt = y + 20, createdAt = y); memo("会議の記録", updatedAt = y + 10, createdAt = y)
        openChat()
        send("昨日の記録を探して")   // the decision's dated SEARCH: three results shown, no model
        awaitTag("chat_ai_search_results")
        val before = memoIds()
        send("『完了』を追記して")
        awaitQuestion("どの記録に追記しますか？")
        awaitTag("chat_clarify_choices")
        assertEquals("no auto-selection happened", 0, count("chat_ai_preview_cancel"))
        composeRule.onNodeWithTag("chat_clarify_choice_2").performClick()
        awaitTag("chat_ai_preview_cancel")
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        awaitGone("chat_ai_preview_cancel")
        assertEquals(before, memoIds())
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- E: no context, a conversational sentence → the setup card as before ---

    @Test
    fun aConversationalSentenceWithNoModelStillMeetsTheSetupCard() {
        TestAiSelection.descriptor = null
        openChat()
        send("今日は疲れた")
        awaitTag("chat_ai_model_unavailable")
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- F: with a model, the conversational sentence reaches the generation route and only then loads ---

    @Test
    fun withAModelTheConversationalSentenceStillGoesToGeneration() {
        memo("散歩のメモ")
        openChat()
        TestAiRuntime.answers.add("{\"intent\":\"SEARCH\",\"query\":\"散歩\",\"targetRef\":null,\"targetName\":null,\"documentKind\":null,\"text\":null,\"templateId\":null,\"dateToken\":null,\"missingFields\":[]}")
        send("散歩について何かあったっけ")
        awaitTag("chat_ai_search_results")
        assertEquals("the decision passed and the model ran", 1, TestAiRuntime.loads)
        assertEquals(1, TestAiRuntime.requests)
    }

    // --- G: process death mid-clarification — the transcript stays, the pending question is gone, write 0 ---

    @Test
    fun processDeathDuringAClarificationDropsThePendingStateAndWritesNothing() {
        TestAiSelection.descriptor = null
        memo("開発メモ")
        openChat()
        send("『開発メモ』を開いて")
        awaitTag("memo_reading_view")
        Espresso.pressBack()
        // Back from the opened document is the conversation again; tapping チャット here would now be a reselect (its home, docs/BOTTOM_NAV_RESELECT.md).
        awaitTag("chat_input")
        val before = memoIds()
        send("それに追記して")
        awaitQuestion("何を追記しますか？")
        val conversation = conversations().single()
        val lines = transcript(conversation.id).size
        // a process death is a fresh view model over the same store: the pending clarification is memory only
        val revived = io.github.cragcoffee.memoripple.ui.chat.ChatViewModel(
            null, application.aiOrchestrator, application.chatHistoryRepository, application.lastConversationStore,
        )
        runBlocking { withTimeout(10_000) { revived.uiState.first { it.transcript.size >= lines } } }
        assertEquals("the transcript survived whole", lines, revived.uiState.value.transcript.size)
        revived.updateInput("Folder対応完了")
        revived.ask()
        // with the pending state gone, the words are not a body: no preview, no write — the ask meets the no-model card
        runBlocking { withTimeout(10_000) { revived.uiState.first { it.ai.result != null } } }
        assertTrue("${revived.uiState.value.ai.result}", revived.uiState.value.ai.result is io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult.ModelUnavailable)
        assertEquals(before, memoIds())
        assertEquals(0, TestAiRuntime.loads)
    }
}

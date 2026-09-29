package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.ai.models.DownloadRequest
import io.github.cragcoffee.memoripple.domain.ai.models.InstallState
import io.github.cragcoffee.memoripple.domain.ai.models.SelectOutcome
import io.github.cragcoffee.memoripple.domain.ai.runtime.MemoryPressure
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
 * AI Resource Controller journeys (Phase 2, docs/AI_RESOURCE_CONTROLLER.md, human brief
 * 2026-09-23). The generation model is a leased resource: nothing but a genuine generative
 * request loads it (journeys A, B); a warm model answers again without a reload (C); the
 * controller's idle clock — advanced by the test clock, never by a real sleep — unloads it (D);
 * memory pressure defers or cancels by the standing Phase 3 amendment (E, F); Power Saver only
 * shrinks the budget (G); the Fast Path neither loads nor keeps anything alive (A, H);
 * a model switch unloads the old and loads the new lazily (I); the background alone unloads
 * nothing — background plus the idle timeout does (J).
 */
class AiResourceJourneysInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication
    private val a get() = TestModelServer.A_ID
    private val b get() = TestModelServer.B_ID

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
        TestAiRuntime.reset()   // the release above may have counted an unload of a leftover model
    }

    @After
    fun tidy() {
        TestAiRuntime.loadGate?.complete(Unit); TestAiRuntime.generateGate?.complete(Unit)
        TestIdleClock.reset()
        runBlocking {
            application.aiOrchestrator.release()
            application.modelManager.cancelAll()
            listOf(a, b).forEach { application.modelManager.delete(it) }
            application.settingsRepository.setSelectedAiModelId(null)
            application.settingsRepository.setLastChatConversationId(null)
        }
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
    private fun memo(title: String): Long = runBlocking { application.database.memoDao().insert(MemoEntity(title = title, body = "本文", createdAt = 1, updatedAt = 1, kind = "memo")) }
    private fun memoIds() = runBlocking { application.database.memoDao().allIds() }.toSet()
    private fun proposal(intent: String, query: String? = null, documentKind: String? = null, text: String? = null): String {
        fun s(v: String?) = if (v == null) "null" else "\"" + v.replace("\"", "\\\"") + "\""
        return "{\"intent\":${s(intent)},\"query\":${s(query)},\"targetRef\":null,\"targetName\":null,\"documentKind\":${s(documentKind)},\"text\":${s(text)},\"templateId\":null,\"dateToken\":null,\"missingFields\":[]}"
    }
    private fun installAndSelect(id: String) {
        assertEquals(DownloadRequest.Started, runBlocking { application.modelManager.requestDownload(id) })
        runBlocking { withTimeout(60_000) { application.modelManager.states.first { it.getValue(id) is InstallState.Installed } } }
        assertEquals(SelectOutcome.Selected, runBlocking { application.modelManager.select(id) })
    }

    // --- A: no model → the Fast Path answers, and nothing is created or loaded ---

    @Test
    fun aFastSearchWithNoModelLoadsNothing() {
        TestAiSelection.descriptor = null
        memo("散歩のメモ")
        openChat()
        send("散歩を探して")
        awaitTag("chat_ai_search_results")
        assertEquals(0, TestAiRuntime.loads)
        assertEquals(0, TestAiRuntime.requests)
    }

    // --- B: model selected → opening the chat loads nothing; the first genuine ask loads once ---

    @Test
    fun openingTheChatLoadsNothingAndTheFirstGenerativeAskLoadsOnce() {
        memo("散歩のメモ")
        openChat()
        composeRule.onNodeWithTag("chat_input").assertIsDisplayed()
        assertEquals("the chat screen costs no load", 0, TestAiRuntime.loads)
        TestAiRuntime.answers.add(proposal("SEARCH", query = "散歩"))
        send("散歩について何かあったっけ")
        awaitTag("chat_ai_search_results")
        assertEquals(1, TestAiRuntime.loads)
    }

    // --- C: a second conversational ask inside the warm window reloads nothing ---

    @Test
    fun aWarmSecondAskReloadsNothing() {
        memo("散歩のメモ")
        openChat()
        TestAiRuntime.answers.add(proposal("SEARCH", query = "散歩"))
        send("散歩について何かあったっけ")
        awaitTag("chat_ai_search_results")
        TestAiRuntime.answers.add(proposal("SEARCH", query = "散歩"))
        send("他に散歩の話はあったかな")
        composeRule.waitUntil(15_000) { TestAiRuntime.requests >= 2 }
        awaitTag("chat_ai_search_results")
        assertEquals("warm reuse", 1, TestAiRuntime.loads)
        assertEquals(0, TestAiRuntime.unloads)
    }

    // --- D: the idle deadline (the test clock, no real sleep) unloads exactly once ---

    @Test
    fun theIdleTimeoutUnloadsTheWarmModelOnce() {
        memo("散歩のメモ")
        openChat()
        TestAiRuntime.answers.add(proposal("SEARCH", query = "散歩"))
        send("散歩について何かあったっけ")
        awaitTag("chat_ai_search_results")
        composeRule.waitUntil(15_000) { TestIdleClock.pending() == 1 }
        assertTrue(TestIdleClock.advance())
        composeRule.waitUntil(15_000) { TestAiRuntime.unloads == 1 }
        assertEquals(1, TestAiRuntime.loads)
        assertEquals("nothing further is pending", 0, TestIdleClock.pending())
    }

    // --- E: LOW during an active generation defers the unload until the answer is done ---

    @Test
    fun lowMemoryDuringAGenerationDefersTheUnloadUntilCompletion() {
        memo("散歩のメモ")
        openChat()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        TestAiRuntime.generateGate = gate
        TestAiRuntime.answers.add(proposal("SEARCH", query = "散歩"))
        send("散歩について何かあったっけ")
        composeRule.waitUntil(15_000) { TestAiRuntime.requests == 1 }
        runBlocking { application.aiOrchestrator.onMemoryPressure(MemoryPressure.LOW) }
        assertEquals("nothing cancelled mid-answer", 0, TestAiRuntime.unloads)
        gate.complete(Unit)
        awaitTag("chat_ai_search_results")
        composeRule.waitUntil(15_000) { TestAiRuntime.unloads == 1 }
        assertEquals(1, TestAiRuntime.loads)
    }

    // --- F: CRITICAL during an active generation cancels, writes nothing, unloads ---

    @Test
    fun criticalMemoryDuringAGenerationCancelsAndWritesNothing() {
        openChat()
        val before = memoIds()
        TestAiRuntime.generateGate = kotlinx.coroutines.CompletableDeferred()
        TestAiRuntime.answers.add(proposal("CREATE", documentKind = "MEMO", text = "買い物リスト"))
        send("買い物のメモを用意しておいてほしいな")
        composeRule.waitUntil(15_000) { TestAiRuntime.requests == 1 }
        runBlocking { application.aiOrchestrator.onMemoryPressure(MemoryPressure.CRITICAL) }
        composeRule.waitUntil(15_000) { TestAiRuntime.unloads >= 1 }
        awaitTag("chat_ai_runtime_error")
        assertEquals("no write from a cancelled generation", before, memoIds())
        assertEquals(0, composeRule.onAllNodesWithTag("chat_ai_preview_create").fetchSemanticsNodes().size)
    }

    // --- G: Power Saver → the ECO budget, the same model ---

    @Test
    fun powerSaverShrinksTheBudgetAndSwitchesNoModel() {
        TestGuards.powerSaver = true
        memo("散歩のメモ")
        openChat()
        TestAiRuntime.answers.add(proposal("SEARCH", query = "散歩"))
        send("散歩について何かあったっけ")
        awaitTag("chat_ai_search_results")
        assertEquals(1, TestAiRuntime.loads)
        assertEquals(io.github.cragcoffee.memoripple.domain.ai.resource.GenerationBudget.REDUCED.maxOutputTokens, TestAiRuntime.lastMaxTokens)
        assertEquals(TestAiSelection.default.id, TestAiRuntime.lastLoadedModelId)
    }

    // --- H: fast asks while the model is warm never extend the idle deadline ---

    @Test
    fun fastAsksDoNotKeepTheWarmModelAlive() {
        memo("散歩のメモ")
        openChat()
        TestAiRuntime.answers.add(proposal("SEARCH", query = "散歩"))
        send("散歩について何かあったっけ")
        awaitTag("chat_ai_search_results")
        composeRule.waitUntil(15_000) { TestIdleClock.pending() == 1 }
        val conversation = runBlocking { application.chatHistoryRepository.conversations().first() }.single()
        fun lines() = runBlocking { application.chatHistoryRepository.messages(conversation.id).first() }.size
        val base = lines()
        repeat(3) { i ->
            send("散歩を探して")
            // each fast ask adds its USER line and its answer line to the transcript (the card is the current result)
            composeRule.waitUntil(15_000) { lines() >= base + (i + 1) * 2 }
        }
        assertEquals("the fast traffic re-armed no timer", 1, TestIdleClock.pending())
        assertEquals("the fast traffic generated nothing", 1, TestAiRuntime.requests)
        assertTrue(TestIdleClock.advance())
        composeRule.waitUntil(15_000) { TestAiRuntime.unloads == 1 }
    }

    // --- I: switching the selected model unloads the old and loads the new only on the next ask ---

    @Test
    fun switchingModelsUnloadsTheOldAndLazyLoadsTheNew() {
        TestAiSelection.useProduct(application)
        installAndSelect(a)
        memo("散歩のメモ")
        openChat()
        TestAiRuntime.answers.add(proposal("SEARCH", query = "散歩"))
        send("散歩について何かあったっけ")
        awaitTag("chat_ai_search_results")
        assertEquals(1, TestAiRuntime.loads)
        assertEquals(a, TestAiRuntime.lastLoadedModelId)
        // install and choose the other model: the old unloads, the new is not loaded yet
        assertEquals(DownloadRequest.Started, runBlocking { application.modelManager.requestDownload(b) })
        runBlocking { withTimeout(60_000) { application.modelManager.states.first { it.getValue(b) is InstallState.Installed } } }
        assertEquals(SelectOutcome.Selected, runBlocking { application.modelManager.select(b) })
        composeRule.waitUntil(15_000) { TestAiRuntime.unloads == 1 }
        assertEquals("the switch loads nothing", 1, TestAiRuntime.loads)
        TestAiRuntime.answers.add(proposal("SEARCH", query = "散歩"))
        send("他に散歩の話はあったかな")
        composeRule.waitUntil(15_000) { TestAiRuntime.loads == 2 }
        awaitTag("chat_ai_search_results")
        assertEquals(b, TestAiRuntime.lastLoadedModelId)
    }

    // --- J: the background alone unloads nothing; background + the idle deadline does ---

    @Test
    fun theBackgroundAloneDoesNotUnloadAndTheIdleDeadlineStillDoes() {
        memo("散歩のメモ")
        openChat()
        TestAiRuntime.answers.add(proposal("SEARCH", query = "散歩"))
        send("散歩について何かあったっけ")
        awaitTag("chat_ai_search_results")
        composeRule.waitUntil(15_000) { TestIdleClock.pending() == 1 }
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals("backgrounding is not an unload", 0, TestAiRuntime.unloads)
        assertTrue(TestIdleClock.advance())
        composeRule.waitUntil(15_000) { TestAiRuntime.unloads == 1 }
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }
}

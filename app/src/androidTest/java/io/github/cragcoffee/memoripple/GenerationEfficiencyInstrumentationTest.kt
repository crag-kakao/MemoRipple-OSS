package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.ai.models.DownloadRequest
import io.github.cragcoffee.memoripple.domain.ai.models.InstallState
import io.github.cragcoffee.memoripple.domain.ai.models.SelectOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Phase 4B journeys (docs/GENERATION_EFFICIENCY.md, human brief 2026-09-23) over the scripted
 * runtime: the chat keys every generation by its conversation (A, B, C), a conversation switch
 * changes the key so nothing foreign could be reused (D), the Fast Path and a CERTAIN decision
 * never reach the generation timing path (E, F), an idle unload forgets the key so the next ask
 * is cold (G), and a model switch likewise (H). The real prefix reuse is measured by the S20
 * benchmark smoke; here the wiring is what is proven.
 */
class GenerationEfficiencyInstrumentationTest {
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
        TestAiRuntime.reset()
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
    private fun memo(title: String, updatedAt: Long = 1): Long =
        runBlocking { application.database.memoDao().insert(MemoEntity(title = title, body = "本文", createdAt = 1, updatedAt = updatedAt, kind = "memo")) }
    private fun proposal(intent: String, query: String? = null): String {
        fun s(v: String?) = if (v == null) "null" else "\"" + v.replace("\"", "\\\"") + "\""
        return "{\"intent\":${s(intent)},\"query\":${s(query)},\"targetRef\":null,\"targetName\":null,\"documentKind\":null,\"text\":null,\"templateId\":null,\"dateToken\":null,\"missingFields\":[]}"
    }
    private fun conversations() = runBlocking { application.chatHistoryRepository.conversations().first() }
    private fun askConversational(text: String) {
        TestAiRuntime.answers.add(proposal("SEARCH", query = "散歩"))
        send(text)
        val before = TestAiRuntime.requests
        composeRule.waitUntil(15_000) { TestAiRuntime.requests > before || composeRule.onAllNodesWithTag("chat_ai_search_results").fetchSemanticsNodes().isNotEmpty() }
        awaitTag("chat_ai_search_results")
    }

    // --- A + B: a cold ask loads and keys the generation by its conversation; the warm second ask keeps the key and loads nothing ---

    @Test
    fun coldThenWarmAsksShareOneConversationKeyAndLoadOnce() {
        memo("散歩のメモ")
        openChat()
        askConversational("散歩について何かあったっけ")
        assertEquals(1, TestAiRuntime.loads)
        val first = TestAiRuntime.cacheKeys.single()
        assertTrue("the key names the conversation", first != null && first.contains(conversations().single().id.toString()))
        askConversational("他に散歩の話はあったかな")
        assertEquals("warm: no reload", 1, TestAiRuntime.loads)
        assertEquals(2, TestAiRuntime.cacheKeys.size)
        assertEquals("the same conversation keeps the same key — the prefix may be reused", first, TestAiRuntime.cacheKeys.last())
    }

    // --- D: switching the conversation changes the key — no foreign prefix could be reused ---

    @Test
    fun switchingConversationsChangesTheKey() {
        memo("散歩のメモ")
        openChat()
        askConversational("散歩について何かあったっけ")
        val first = TestAiRuntime.cacheKeys.single()
        composeRule.onNodeWithTag("chat_new_conversation").performClick()
        awaitTag("chat_input")
        askConversational("散歩について何かあったっけ")
        assertEquals(2, TestAiRuntime.cacheKeys.size)
        assertNotEquals("another conversation is another identity", first, TestAiRuntime.cacheKeys.last())
        assertEquals("still the warm model", 1, TestAiRuntime.loads)
    }

    // --- E + F: the Fast Path and a CERTAIN decision never enter the generation timing path ---

    @Test
    fun fastAndCertainAsksNeverReachTheGenerator() {
        memo("散歩の記録", updatedAt = 30); memo("散歩の予定", updatedAt = 20)
        openChat()
        send("散歩を探して")
        awaitTag("chat_ai_search_results")
        send("2番目を開いて")
        awaitTag("memo_reading_view")
        assertEquals(0, TestAiRuntime.requests)
        assertEquals(0, TestAiRuntime.loads)
        assertTrue(TestAiRuntime.cacheKeys.isEmpty())
    }

    // --- G: the idle unload forgets everything; the next ask loads again (cold, a full evaluation) ---

    @Test
    fun anIdleUnloadMakesTheNextAskCold() {
        memo("散歩のメモ")
        openChat()
        askConversational("散歩について何かあったっけ")
        composeRule.waitUntil(15_000) { TestIdleClock.pending() == 1 }
        assertTrue(TestIdleClock.advance())
        composeRule.waitUntil(15_000) { TestAiRuntime.unloads == 1 }
        assertNull("the scripted runtime forgot its key on unload", TestAiRuntime.current!!.cacheKey())
        askConversational("他に散歩の話はあったかな")
        assertEquals("cold again", 2, TestAiRuntime.loads)
    }

    // --- H: a model switch unloads, so the key is gone with the old model ---

    @Test
    fun aModelSwitchInvalidatesTheCache() {
        TestAiSelection.useProduct(application)
        assertEquals(DownloadRequest.Started, runBlocking { application.modelManager.requestDownload(a) })
        runBlocking { withTimeout(60_000) { application.modelManager.states.first { it.getValue(a) is InstallState.Installed } } }
        assertEquals(SelectOutcome.Selected, runBlocking { application.modelManager.select(a) })
        memo("散歩のメモ")
        openChat()
        askConversational("散歩について何かあったっけ")
        assertEquals(1, TestAiRuntime.loads)
        assertEquals(DownloadRequest.Started, runBlocking { application.modelManager.requestDownload(b) })
        runBlocking { withTimeout(60_000) { application.modelManager.states.first { it.getValue(b) is InstallState.Installed } } }
        assertEquals(SelectOutcome.Selected, runBlocking { application.modelManager.select(b) })
        composeRule.waitUntil(15_000) { TestAiRuntime.unloads == 1 }
        assertNull(TestAiRuntime.current!!.cacheKey())
        askConversational("他に散歩の話はあったかな")
        assertEquals(2, TestAiRuntime.loads)
        assertEquals(b, TestAiRuntime.lastLoadedModelId)
        assertNotEquals("a new model is a new identity", TestAiRuntime.cacheKeys.first(), TestAiRuntime.cacheKeys.last())
    }
}

package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The user's UI/UX review remarks of 2026-09-21 on the real screen: a new chat opens as its own
 * stage without the bottom bar and Back returns to the tab; the model switch — 「AIモデルなし」 stops
 * the AI (the runtime unloads, free text shows the setup card, templates still work) and picking
 * the installed model brings it back; the assistant meta row (timing, copy) after an AI answer.
 */
class ChatUiReviewFixInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication
    private val a get() = TestModelServer.A_ID

    @Before
    fun releaseShapedAndEmpty() {
        TestModelServer.reset(); TestGuards.reset(); TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0
        TestGuards.fastPathEnabled = false   // this class counts loads / unloads / timing on the model pipeline; its plain sentences must reach the generator (docs/CHAT_FAST_PATH.md)
        runBlocking {
            application.database.clearAllTables()
            application.templateRepository.replaceAll(emptyList())
            application.settingsRepository.setAutoPlayOnLaunch(false)
            application.settingsRepository.setLastChatConversationId(null)
            application.aiOrchestrator.release()
            application.modelManager.cancelAll()
            application.settingsRepository.setSelectedAiModelId(null)
            listOf(a, TestModelServer.B_ID).forEach { application.modelManager.delete(it) }
            application.modelManager.refresh()
        }
        TestAiSelection.useProduct(application)
    }

    @After
    fun tidy() {
        TestGuards.fastPathEnabled = true   // hand the product default back to the suite
        TestAiRuntime.loadGate?.complete(Unit); TestAiRuntime.generateGate?.complete(Unit)
        TestModelServer.reset(); TestGuards.reset(); TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0
        runBlocking {
            application.aiOrchestrator.release()
            application.modelManager.cancelAll()
            listOf(a, TestModelServer.B_ID).forEach { application.modelManager.delete(it) }
            application.settingsRepository.setSelectedAiModelId(null)
            application.settingsRepository.setLastChatConversationId(null)
        }
    }

    private fun installAndSelect(id: String) {
        assertEquals(DownloadRequest.Started, runBlocking { application.modelManager.requestDownload(id) })
        runBlocking { withTimeout(60_000) { application.modelManager.states.first { it.getValue(id) is InstallState.Installed } } }
        assertEquals(SelectOutcome.Selected, runBlocking { application.modelManager.select(id) })
    }
    private fun memo(title: String): Long = runBlocking { application.database.memoDao().insert(MemoEntity(title = title, body = "本文", createdAt = 1, updatedAt = 1, kind = "memo")) }
    private fun proposal(intent: String, query: String? = null, targetName: String? = null): String {
        fun s(v: String?) = if (v == null) "null" else "\"" + v.replace("\"", "\\\"") + "\""
        return "{\"intent\":${s(intent)},\"query\":${s(query)},\"targetRef\":null,\"targetName\":${s(targetName)},\"documentKind\":null,\"text\":null,\"templateId\":null,\"dateToken\":null,\"missingFields\":[]}"
    }
    private fun openChat() { composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input") }
    private fun send(text: String) {
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

    // --- a new chat is its own stage; Back returns to the tab ---

    @Test
    fun aNewChatOpensAsAStageWithoutTheBottomBarAndBackReturnsToTheTab() {
        openChat()
        composeRule.onNodeWithTag("nav_chat").assertIsSelected()
        composeRule.onNodeWithTag("chat_new_conversation").performClick()
        awaitTag("chat_stage")
        assertEquals("no bottom bar on the stage", 0, count("nav_chat"))
        composeRule.onNodeWithTag("chat_stage_back").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_input").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_plus").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_title").assertTextContains("チャット", substring = true)
        assertTrue("nothing created by opening the stage", runBlocking { application.chatHistoryRepository.conversations().first() }.isEmpty())
        Espresso.pressBack()
        awaitTag("nav_chat")
        composeRule.onNodeWithTag("nav_chat").assertIsSelected()
        composeRule.onNodeWithTag("chat_input").assertIsDisplayed()
        // the stage's conversation becomes the tab's current one
        composeRule.onNodeWithTag("chat_new_conversation").performClick()
        awaitTag("chat_stage")
        memo("散歩のメモ")
        installAndSelect(a)
        TestAiRuntime.answers.add(proposal("SEARCH", query = "散歩"))
        send("散歩を探して")
        awaitTag("chat_ai_search_results")
        val c = runBlocking { application.chatHistoryRepository.conversations().first() }.single()
        composeRule.onNodeWithTag("chat_stage_back").performClick()
        awaitTag("nav_chat")
        composeRule.onNodeWithTag("chat_title").assertTextContains(c.title, substring = true)
    }

    // --- the model switch: none stops the AI; the installed model brings it back ---

    @Test
    fun choosingNoModelStopsTheAiAndChoosingTheInstalledModelBringsItBack() {
        installAndSelect(a)
        memo("散歩のメモ")
        openChat()
        composeRule.onNodeWithTag("chat_subtitle", useUnmergedTree = true).assertTextContains(TestModelServer.catalog.first { it.modelId == a }.displayName, substring = true)
        TestAiRuntime.answers.add(proposal("SEARCH", query = "散歩"))
        send("散歩を探して")
        awaitTag("chat_ai_search_results")
        assertEquals(1, TestAiRuntime.loads)
        composeRule.onNodeWithTag("chat_model_button").performClick()
        awaitTag("chat_model_none")
        composeRule.onNodeWithTag("chat_model_option_$a").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_model_none").performClick()
        awaitTag("chat_ai_hint")
        composeRule.onNodeWithTag("chat_subtitle", useUnmergedTree = true).assertTextContains("AIモデルなし", substring = true)
        composeRule.waitUntil(15_000) { TestAiRuntime.unloads >= 1 }
        assertEquals("the selection is cleared", null, runBlocking { application.settingsRepository.selectedAiModelId.first() })
        send("散歩を探して")
        awaitTag("chat_ai_model_unavailable")
        assertEquals("nothing loaded again", 1, TestAiRuntime.loads)
        composeRule.onNodeWithTag("chat_ai_dismiss").performClick()
        composeRule.onNodeWithTag("chat_plus").performClick()
        awaitTag("chat_template_picker")
        Espresso.pressBack()
        awaitGone("chat_template_picker")
        composeRule.onNodeWithTag("chat_model_button").performClick()
        awaitTag("chat_model_option_$a")
        composeRule.onNodeWithTag("chat_model_option_$a").performClick()
        awaitGone("chat_ai_hint")
        assertEquals(a, runBlocking { application.settingsRepository.selectedAiModelId.first() })
        assertEquals("selecting never loads", 1, TestAiRuntime.loads)
        TestAiRuntime.answers.add(proposal("SEARCH", query = "散歩"))
        send("散歩を探して")
        awaitTag("chat_ai_search_results")
        assertEquals(2, TestAiRuntime.loads)
    }

    // --- the assistant meta row: timing numbers and a copy action after an AI answer; none for a template run ---

    @Test
    fun anAiAnswerShowsItsTimingAndACopyActionUnderTheAssistantLine() {
        installAndSelect(a)
        memo("散歩のメモ")
        openChat()
        TestAiRuntime.answers.add(proposal("SEARCH", query = "散歩"))
        send("散歩を探して")
        awaitTag("chat_ai_search_results")
        awaitTag("chat_ai_timing")
        composeRule.onNodeWithTag("chat_ai_timing").assertTextContains("トークン/秒", substring = true)
        composeRule.onNodeWithTag("chat_ai_timing").assertTextContains("TTFT", substring = true)
        val c = runBlocking { application.chatHistoryRepository.conversations().first() }.single()
        composeRule.waitUntil(15_000) { runBlocking { application.chatHistoryRepository.messages(c.id).first() }.size >= 2 }
        val assistant = runBlocking { application.chatHistoryRepository.messages(c.id).first() }.last()
        awaitTag("chat_message_copy_${assistant.id}")
        composeRule.onNodeWithTag("chat_message_copy_${assistant.id}").performClick()
    }
}

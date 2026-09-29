package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.ai.ModelProfiles
import io.github.cragcoffee.memoripple.domain.documents.DocumentQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Phase 8 S20 smoke (docs/AI_CONVERSATION_HISTORY.md): two turns on the real model — the test's own
 * temporary memo opened by name, then 「それに『会話テスト』を追記して」 through the anchor to a preview,
 * cancelled. Short on purpose. The conversation it makes it deletes; no existing document is
 * touched. Runs only with `-e llmModelPath …` (skipped otherwise).
 */
class ConversationS20SmokeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    private var scenario: ActivityScenario<MainActivity>? = null
    private var developerFile: File? = null
    private var tempMemo: Long? = null
    private val conversationsBefore = HashSet<Long>()

    @Before
    fun nameTheModel() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("llmModelPath")
        assumeTrue("no llmModelPath injected: the conversation smoke is skipped", !path.isNullOrBlank())
        val modelId = args.getString("llmModelId") ?: ModelProfiles.qwen3_4bInstruct2507.descriptor.id
        val dir = File(application.noBackupFilesDir, "models").also { it.mkdirs() }
        developerFile = File(dir, "developer.properties").also { it.writeText("modelId=$modelId\npath=$path\n") }
        runBlocking {
            application.settingsRepository.setAutoPlayOnLaunch(false)
            conversationsBefore += application.chatHistoryRepository.conversations().first().map { it.id }
            application.settingsRepository.setLastChatConversationId(null)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun removeWhatWasMadeHere() {
        runBlocking {
            application.aiOrchestrator.release()
            application.chatHistoryRepository.conversations().first().filter { it.id !in conversationsBefore }.forEach { application.chatHistoryRepository.delete(it.id) }
            application.settingsRepository.setLastChatConversationId(null)
            tempMemo?.let { application.memoRepository.moveToTrash(it); application.database.memoDao().deletePermanently(it) }
        }
        developerFile?.delete()
        scenario?.close()
    }

    private fun log(message: String) = android.util.Log.i("ConversationSmoke", message)
    private fun awaitTag(tag: String, timeoutMillis: Long = 15_000) {
        composeRule.waitUntil(timeoutMillis) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private val cards = listOf("chat_ai_preview_append", "chat_ai_candidates", "chat_ai_needs_information", "chat_ai_not_found", "chat_ai_invalid", "chat_ai_unknown", "chat_ai_runtime_error", "chat_ai_search_results", "chat_ai_opened", "chat_ai_model_unavailable", "chat_ai_thermal_blocked", "memo_reading_view")
    private fun awaitCard(timeoutMillis: Long): String {
        var seen = ""
        composeRule.waitUntil(timeoutMillis) {
            seen = cards.firstOrNull { composeRule.onAllNodesWithTag(it, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }.orEmpty()
            seen.isNotEmpty()
        }
        return seen
    }
    private fun ask(text: String) {
        composeRule.onNodeWithTag("chat_input").performTextClearance()
        composeRule.onNodeWithTag("chat_input").performTextInput(text)
        composeRule.onNodeWithTag("chat_send").performClick()
    }
    private fun thermal(): Int = (application.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus

    @Test
    fun twoTurnsOpenThenAppendToItThroughTheAnchorAndAPreviewOnly() {
        log("thermal before: ${thermal()}")
        val existing = runBlocking { application.documentAccess.search(DocumentQuery(text = "MemoRipple開発", limit = 5)) }
        assumeTrue("the device already holds a document named like the fixture; not touching it", existing.none { it.title.trim() == "MemoRipple開発" })
        val now = application.timeProvider.nowMillis()
        tempMemo = runBlocking { application.database.memoDao().insert(MemoEntity(title = "MemoRipple開発", body = "## 進捗", createdAt = now, updatedAt = now, kind = "memo")) }
        val idsBefore = runBlocking { application.database.memoDao().allIds().toSet() }

        awaitTag("nav_chat"); composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input")
        awaitTag("chat_input")

        var t0 = System.currentTimeMillis()
        ask("MemoRipple開発を開いて")
        var card = awaitCard(180_000)
        log("turn 1 OPEN → $card in ${System.currentTimeMillis() - t0} ms")
        assertEquals("memo_reading_view", card)
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_input")

        t0 = System.currentTimeMillis()
        ask("それに『会話テスト』を追記して")
        card = awaitCard(180_000)
        log("turn 2 それに追記 → $card in ${System.currentTimeMillis() - t0} ms")
        assertEquals("chat_ai_preview_append", card)
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("MemoRipple開発", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("会話テスト", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()

        assertEquals("nothing written", "## 進捗", runBlocking { application.database.memoDao().findById(tempMemo!!)!!.body })
        assertEquals("no other document touched", idsBefore, runBlocking { application.database.memoDao().allIds().toSet() })
        val made = runBlocking { application.chatHistoryRepository.conversations().first().filter { it.id !in conversationsBefore } }
        assertEquals(1, made.size)
        val transcript = runBlocking { application.chatHistoryRepository.messages(made.single().id).first() }
        log("transcript: ${transcript.size} messages, title length ${made.single().title.length}")
        assertTrue(transcript.size >= 4)
        log("thermal after: ${thermal()}")
    }
}

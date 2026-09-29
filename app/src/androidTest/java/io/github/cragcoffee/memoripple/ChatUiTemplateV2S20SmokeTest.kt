package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.ai.ModelProfiles
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentQuery
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.TemplateAction
import io.github.cragcoffee.memoripple.domain.memos.TemplateField
import io.github.cragcoffee.memoripple.domain.memos.TemplateFieldType
import io.github.cragcoffee.memoripple.domain.memos.TemplateSearchSpec
import io.github.cragcoffee.memoripple.domain.memos.TemplateTargetSpec
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
 * The S20 smoke of the Chat UI redesign + Template v2 (docs/CHAT_UI_TEMPLATE_V2.md): the new chat,
 * the template picker, one deterministic template run (a SEARCH template, then an APPEND template
 * to the test's own temporary memo — preview, cancelled), one AI free-text query on the real model.
 * Its own templates and memo are removed afterwards; no existing document is touched. Runs only
 * with `-e llmModelPath …` (skipped otherwise).
 */
class ChatUiTemplateV2S20SmokeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    private var scenario: ActivityScenario<MainActivity>? = null
    private var developerFile: File? = null
    private var tempMemo: Long? = null
    private var templatesBefore: List<MemoTemplate> = emptyList()
    private val conversationsBefore = HashSet<Long>()
    private val searchTemplate = MemoTemplate(id = "smoke-search", name = "MemoRipple開発を探す", body = "", action = TemplateAction.SEARCH, searchSpec = TemplateSearchSpec("MemoRipple開発", null, setOf(DocumentKind.MEMO)))
    private val appendTemplate = MemoTemplate(id = "smoke-append", name = "開発ログへ追記", body = "- {{entry}}", action = TemplateAction.APPEND, targetSpec = TemplateTargetSpec.Named("MemoRipple開発"), fields = listOf(TemplateField("entry", "内容", TemplateFieldType.TEXT, required = true)))

    @Before
    fun nameTheModel() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("llmModelPath")
        assumeTrue("no llmModelPath injected: the Chat UI / Template v2 smoke is skipped", !path.isNullOrBlank())
        val modelId = args.getString("llmModelId") ?: ModelProfiles.qwen3_4bInstruct2507.descriptor.id
        val dir = File(application.noBackupFilesDir, "models").also { it.mkdirs() }
        developerFile = File(dir, "developer.properties").also { it.writeText("modelId=$modelId\npath=$path\n") }
        runBlocking {
            application.settingsRepository.setAutoPlayOnLaunch(false)
            templatesBefore = application.templateRepository.current()
            application.templateRepository.replaceAll(listOf(searchTemplate, appendTemplate) + templatesBefore)
            conversationsBefore += application.chatHistoryRepository.conversations().first().map { it.id }
            application.settingsRepository.setLastChatConversationId(null)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun removeWhatWasMadeHere() {
        runBlocking {
            application.aiOrchestrator.release()
            application.templateRepository.replaceAll(templatesBefore)
            application.chatHistoryRepository.conversations().first().filter { it.id !in conversationsBefore }.forEach { application.chatHistoryRepository.delete(it.id) }
            application.settingsRepository.setLastChatConversationId(null)
            tempMemo?.let { application.memoRepository.moveToTrash(it); application.database.memoDao().deletePermanently(it) }
        }
        developerFile?.delete()
        scenario?.close()
    }

    private fun log(message: String) = android.util.Log.i("TemplateV2Smoke", message)
    private fun awaitTag(tag: String, timeoutMillis: Long = 15_000) {
        composeRule.waitUntil(timeoutMillis) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    private val cards = listOf("chat_ai_preview_append", "chat_ai_candidates", "chat_ai_needs_information", "chat_ai_not_found", "chat_ai_invalid", "chat_ai_unknown", "chat_ai_runtime_error", "chat_ai_search_results", "chat_ai_opened", "chat_ai_model_unavailable", "chat_ai_thermal_blocked", "memo_reading_view", "chat_ai_preview_create")
    private fun awaitCard(timeoutMillis: Long): String {
        var seen = ""
        composeRule.waitUntil(timeoutMillis) {
            seen = cards.firstOrNull { count(it) > 0 }.orEmpty()
            seen.isNotEmpty()
        }
        return seen
    }
    private fun thermal(): Int = (application.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus

    @Test
    fun theNewChatThePickerOneTemplateRunAndOneAiQueryOnTheRealModel() {
        log("thermal before: ${thermal()}")
        val existing = runBlocking { application.documentAccess.search(DocumentQuery(text = "MemoRipple開発", limit = 5)) }
        assumeTrue("the device already holds a document named like the fixture; not touching it", existing.none { it.title.trim() == "MemoRipple開発" })
        val now = application.timeProvider.nowMillis()
        tempMemo = runBlocking { application.database.memoDao().insert(MemoEntity(title = "MemoRipple開発", body = "## 進捗", createdAt = now, updatedAt = now, kind = "memo")) }
        val idsBefore = runBlocking { application.database.memoDao().allIds().toSet() }

        // 1. the new chat: no search controls, the bar, the picker
        awaitTag("nav_chat"); composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input")
        assertEquals(0, count("chat_mode_switch") + count("chat_chip_today") + count("chat_command_new_memo"))
        composeRule.onNodeWithTag("chat_plus").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_plus").performClick()
        awaitTag("chat_template_picker")
        composeRule.onNodeWithTag("chat_template_item_smoke-search", useUnmergedTree = true).assertIsDisplayed()
        log("picker: ${runBlocking { application.templateRepository.current() }.size} templates listed")

        // 2. a SEARCH template — deterministic, no model
        composeRule.onNodeWithTag("chat_template_item_smoke-search").performClick()
        awaitTag("chat_ai_search_results")
        awaitTag("chat_result_memo_$tempMemo")
        log("SEARCH template → results, runtime ${application.aiOrchestrator.runtimeState()}")

        // 3. an APPEND template to the temporary memo — one question (§16) → preview → cancel
        composeRule.onNodeWithTag("chat_plus").performClick()
        awaitTag("chat_template_picker")
        composeRule.onNodeWithTag("chat_template_item_smoke-append").performClick()
        composeRule.waitUntil(15_000) { runBlocking { application.chatHistoryRepository.conversations().first().filter { it.id !in conversationsBefore } }.any { c -> runBlocking { application.chatHistoryRepository.messages(c.id).first() }.any { it.text.contains("内容を入力してください") } } }
        composeRule.onNodeWithTag("chat_input").performTextInput("テンプレート実機テスト")
        composeRule.onNodeWithTag("chat_send").performClick()
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("MemoRipple開発", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("- テンプレート実機テスト", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        assertEquals("nothing written", "## 進捗", runBlocking { application.database.memoDao().findById(tempMemo!!)!!.body })
        log("APPEND template → preview → cancelled, runtime ${application.aiOrchestrator.runtimeState()}")

        // 4. one AI free-text query on the real model
        val t0 = System.currentTimeMillis()
        composeRule.onNodeWithTag("chat_input").performTextClearance()
        composeRule.onNodeWithTag("chat_input").performTextInput("MemoRipple開発を開いて")
        composeRule.onNodeWithTag("chat_send").performClick()
        val card = awaitCard(180_000)
        log("AI free text OPEN → $card in ${System.currentTimeMillis() - t0} ms")
        assertEquals("memo_reading_view", card)
        androidx.test.espresso.Espresso.pressBack()
        awaitTag("chat_input")

        assertEquals("no other document touched", idsBefore, runBlocking { application.database.memoDao().allIds().toSet() })
        val made = runBlocking { application.chatHistoryRepository.conversations().first().filter { it.id !in conversationsBefore } }
        assertTrue(made.size == 1)
        log("transcript: ${runBlocking { application.chatHistoryRepository.messages(made.single().id).first() }.size} messages; thermal after: ${thermal()}")
    }
}

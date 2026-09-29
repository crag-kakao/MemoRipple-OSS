package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.ai.ModelProfiles
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
import io.github.cragcoffee.memoripple.domain.documents.DocumentQuery
import io.github.cragcoffee.memoripple.domain.documents.DocumentReadResult
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
 * The Phase 3 device smoke (docs/AI_CHAT_PREVIEW.md §smoke): the real product UI, the real
 * runtime and a real model, on whatever data the device holds — **nothing is cleared, inserted
 * or written**. It runs only when the operator injects a model:
 *
 *   am instrument -w -r -e class io.github.cragcoffee.memoripple.AiChatPreviewSmokeTest \
 *      -e llmModelPath /data/local/tmp/llmbench/models/<file>.gguf -e llmModelId qwen3-4b-instruct-2507 \
 *      io.github.cragcoffee.memoripple.test/io.github.cragcoffee.memoripple.MemoRippleTestRunner
 *
 * Without the argument it is skipped. With it, the test application uses the product
 * orchestrator, and the model is named through the debug-only developer file the product's
 * selection reads; the file is removed afterwards.
 */
class AiChatPreviewSmokeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    private var scenario: ActivityScenario<MainActivity>? = null
    private var developerFile: File? = null

    @Before
    fun nameTheModelForTheProductSelection() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("llmModelPath")
        assumeTrue("no llmModelPath injected: the AI chat smoke is skipped", !path.isNullOrBlank())
        val modelId = args.getString("llmModelId") ?: ModelProfiles.qwen3_4bInstruct2507.descriptor.id
        val dir = File(application.noBackupFilesDir, "models").also { it.mkdirs() }
        developerFile = File(dir, "developer.properties").also { it.writeText("modelId=$modelId\npath=$path\n") }
        runBlocking { application.settingsRepository.setAutoPlayOnLaunch(false) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun unloadAndForget() {
        runBlocking { application.aiOrchestrator.release() }
        developerFile?.delete()
        scenario?.close()
    }

    /** Titles, bodies and versions of everything the AI could reach — compared, never logged. */
    private fun snapshot(): String = runBlocking {
        val dao = application.database.memoDao()
        val memos = dao.allIds().sorted().map { id -> dao.findById(id)!!.let { "memo|${it.id}|${it.title}|${it.body}|${it.updatedAt}" } }
        val journals = application.database.diaryDao().observeAll().first().sortedBy { it.id }.map { "journal|${it.id}|${it.body}|${it.updatedAt}|${it.state}" }
        val templates = application.templateRepository.current().map { "template|${it.id}|${it.name}|${it.body}" }
        (memos + journals + templates).joinToString("\n")
    }

    private fun ask(text: String) {
        composeRule.onNodeWithTag("chat_input").performTextClearance()
        composeRule.onNodeWithTag("chat_input").performTextInput(text)
        composeRule.onNodeWithTag("chat_send").performClick()
    }

    private val cards = listOf(
        "chat_ai_search_results", "chat_ai_opened", "chat_ai_candidates", "chat_ai_preview_create", "chat_ai_preview_append",
        "chat_ai_preview_template", "chat_ai_needs_information", "chat_ai_unknown", "chat_ai_invalid", "chat_ai_not_found",
        "chat_ai_thermal_blocked", "chat_ai_model_unavailable", "chat_ai_runtime_error",
    )

    private fun awaitCard(timeoutMillis: Long): String {
        var seen = ""
        composeRule.waitUntil(timeoutMillis) {
            seen = cards.firstOrNull { composeRule.onAllNodesWithTag(it, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }.orEmpty()
            seen.isNotEmpty()
        }
        return seen
    }

    private fun log(message: String) = android.util.Log.i("AiChatSmoke", message)

    @Test
    fun threeAsksThroughTheRealModelAndTheDatabaseIsUntouched() {
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithTag("nav_chat").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("nav_chat").performClick()
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isNotEmpty() }
        val before = snapshot()
        val caseCTargets = runBlocking { application.documentAccess.search(DocumentQuery(text = "MemoRipple開発", limit = 20)) }
        log("case C candidates on this device: ${caseCTargets.size}")

        // 1. SEARCH: 「昨日の日記を探して」 → inference → the existing result rows
        var t0 = System.currentTimeMillis()
        ask("昨日の日記を探して")
        val first = awaitCard(180_000)
        log("ask 1 → $first in ${System.currentTimeMillis() - t0} ms; rows=${composeRule.onAllNodesWithTag("chat_result", useUnmergedTree = true).fetchSemanticsNodes().size}")
        assertEquals("chat_ai_search_results", first)
        assertEquals(RuntimeState.READY, application.aiOrchestrator.runtimeState())

        // 2. case C: 「MemoRipple開発に『Folder対応完了』を追記して」 → a preview at most, never a write
        t0 = System.currentTimeMillis()
        ask("MemoRipple開発に『Folder対応完了』を追記して")
        val second = awaitCard(180_000)
        log("ask 2 → $second in ${System.currentTimeMillis() - t0} ms")
        assertTrue("a write must stop at its preview or a safe refusal: $second", second != "chat_ai_opened")
        if (caseCTargets.isEmpty()) assertTrue("no such document on this device → a safe refusal, never a preview: $second", second in setOf("chat_ai_not_found", "chat_ai_needs_information", "chat_ai_invalid", "chat_ai_unknown"))
        if (second == "chat_ai_preview_append") {
            composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("Folder対応完了", substring = true)
            composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()   // never confirmed on the device's own data
        }
        assertEquals("the database is byte-identical after the preview", before, snapshot())
        caseCTargets.forEach { s ->
            val now = runBlocking { application.documentAccess.get(s.ref) } as DocumentReadResult.Found
            assertEquals(s.updatedAt, now.content.summary.updatedAt)
        }

        // 3. an input the intents do not cover → a safe stop
        t0 = System.currentTimeMillis()
        ask("今日の天気を教えて")
        val third = awaitCard(180_000)
        log("ask 3 → $third in ${System.currentTimeMillis() - t0} ms")
        assertTrue("safe stop, not a preview and not an open: $third", third !in setOf("chat_ai_opened", "chat_ai_preview_create", "chat_ai_preview_append", "chat_ai_preview_template"))
        assertEquals(before, snapshot())

        // for the record (the log, never the screen): the raw answers behind the three asks
        val rt = application.localModelRuntimeHolder.runtime
        if (rt.state() == RuntimeState.READY) {
            val gen = io.github.cragcoffee.memoripple.domain.ai.runtime.StructuredIntentGenerator(rt, io.github.cragcoffee.memoripple.data.ai.AssetPromptAssets(application), io.github.cragcoffee.memoripple.data.ai.androidThermalGate(application))
            listOf("昨日の日記を探して", "MemoRipple開発に『Folder対応完了』を追記して", "今日の天気を教えて").forEach { text ->
                val out = runBlocking { gen.generate(text, io.github.cragcoffee.memoripple.domain.ai.AiResultContext.EMPTY) }
                log("raw[$text] = " + when (out) {
                    is io.github.cragcoffee.memoripple.domain.ai.runtime.IntentGeneration.Proposed -> "${out.raw} (${out.totalMillis} ms, ${out.generatedTokens} tok, ttft ${out.ttftMillis} ms)"
                    is io.github.cragcoffee.memoripple.domain.ai.runtime.IntentGeneration.Refused -> "refused ${out.reason}"
                })
            }
        }

        // Phase 2 (docs/AI_RESOURCE_CONTROLLER.md): leaving the screen keeps the model warm; the
        // controller's idle clock or an explicit release unloads — the smoke releases explicitly
        runBlocking { application.aiOrchestrator.release() }
        composeRule.waitUntil(60_000) { application.aiOrchestrator.runtimeState() == RuntimeState.UNLOADED }
        log("unloaded after the explicit release (keep-warm otherwise; the idle clock owns the routine unload)")
        assertEquals(before, snapshot())
    }
}

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
import io.github.cragcoffee.memoripple.data.ai.ModelProfiles
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
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
 * The Phase 4 device smoke (docs/AI_CONFIRMED_WRITE.md §verification): the real UI, runtime and
 * model, on the device's own data — and **the only document written is one this test makes**:
 * a fresh memo created through AI → preview → confirm, then appended through AI → preview →
 * confirm (if the model names it), then removed by id. No existing document is touched; the
 * memo and journal tables are compared before and after. Skipped without `-e llmModelPath`.
 *
 *   am instrument -w -r -e class io.github.cragcoffee.memoripple.AiConfirmedWriteSmokeTest \
 *      -e llmModelPath /data/local/tmp/llmbench/models/<file>.gguf -e llmModelId qwen3-4b-instruct-2507 \
 *      io.github.cragcoffee.memoripple.test/io.github.cragcoffee.memoripple.MemoRippleTestRunner
 */
class AiConfirmedWriteSmokeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    private var scenario: ActivityScenario<MainActivity>? = null
    private var developerFile: File? = null
    private val madeHere = ArrayList<Long>()

    @Before
    fun nameTheModel() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("llmModelPath")
        assumeTrue("no llmModelPath injected: the confirmed-write smoke is skipped", !path.isNullOrBlank())
        val modelId = args.getString("llmModelId") ?: ModelProfiles.qwen3_4bInstruct2507.descriptor.id
        val dir = File(application.noBackupFilesDir, "models").also { it.mkdirs() }
        developerFile = File(dir, "developer.properties").also { it.writeText("modelId=$modelId\npath=$path\n") }
        runBlocking { application.settingsRepository.setAutoPlayOnLaunch(false) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun removeWhatWasMadeHereAndForget() {
        runBlocking {
            application.aiOrchestrator.release()
            madeHere.forEach { application.memoRepository.moveToTrash(it); application.database.memoDao().deletePermanently(it) }
        }
        developerFile?.delete()
        scenario?.close()
    }

    private fun snapshot(): String = runBlocking {
        val dao = application.database.memoDao()
        val memos = dao.allIds().sorted().map { id -> dao.findById(id)!!.let { "memo|${it.id}|${it.title}|${it.body}|${it.updatedAt}" } }
        val journals = application.database.diaryDao().observeAll().first().sortedBy { it.id }.map { "journal|${it.id}|${it.body}|${it.updatedAt}|${it.state}" }
        (memos + journals).joinToString("\n")
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

    private fun awaitTag(tag: String, timeoutMillis: Long = 15_000) {
        composeRule.waitUntil(timeoutMillis) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun log(message: String) = android.util.Log.i("AiWriteSmoke", message)

    @Test
    fun aMemoIsMadeAndAppendedOnlyThroughConfirmationAndNothingElseChanges() {
        awaitTag("nav_chat")
        composeRule.onNodeWithTag("nav_chat").performClick()
        awaitTag("chat_input")
        awaitTag("chat_input")
        val before = snapshot()
        val idsBefore = runBlocking { application.database.memoDao().allIds().toSet() }
        val marker = "AI動作確認 " + System.currentTimeMillis() % 100_000

        // 1. CREATE through the real model → preview → confirm → the memo opens
        var t0 = System.currentTimeMillis()
        ask("新しいメモに『$marker』と書いて")
        val first = awaitCard(180_000)
        log("ask 1 → $first in ${System.currentTimeMillis() - t0} ms")
        assertEquals("a create preview from the real model", "chat_ai_preview_create", first)
        assertEquals("nothing written by the preview", before, snapshot())
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("memo_reading_view", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("memo_body", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val made = runBlocking { application.database.memoDao().allIds().toSet() - idsBefore }
        assertEquals("exactly one memo made", 1, made.size)
        madeHere += made
        val body = runBlocking { application.database.memoDao().findById(made.single())!!.body }
        log("created memo body='$body'")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_ai_write_success")

        // 2. APPEND to that memo through the real model — a preview and a confirm if the model names it, a safe stop otherwise
        t0 = System.currentTimeMillis()
        ask("${marker}に『追記テスト』を追記して")
        val second = awaitCard(180_000)
        log("ask 2 → $second in ${System.currentTimeMillis() - t0} ms")
        if (second == "chat_ai_preview_append") {
            composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains(marker, substring = true)
            composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
            awaitTag("memo_reading_view")
            val appended = runBlocking { application.database.memoDao().findById(made.single())!!.body }
            log("appended body='$appended'")
            assertTrue(appended.endsWith("追記テスト"))
            composeRule.onNodeWithContentDescription("戻る").performClick()
            awaitTag("chat_ai_write_success")
        } else {
            assertTrue("a safe stop, never an open or a write elsewhere: $second", second in setOf("chat_ai_needs_information", "chat_ai_not_found", "chat_ai_ambiguous", "chat_ai_candidates", "chat_ai_invalid", "chat_ai_unknown"))
        }
        // only the memo made here differs from the snapshot
        val afterWithoutOurs = snapshot().lines().filterNot { line -> made.any { line.startsWith("memo|$it|") } }.joinToString("\n")
        assertEquals("no other document changed", before, afterWithoutOurs)

        composeRule.waitUntil(60_000) { application.aiOrchestrator.runtimeState() == RuntimeState.UNLOADED }
        log("unloaded after leaving the AI mode")
    }
}

package io.github.cragcoffee.memoripple

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
 * The Phase 6 device smoke (docs/AI_TARGET_RESOLUTION.md): case C through the real model on the
 * S20. The test makes **one temporary memo of its own** with a unique title, asks the case C
 * sentence about it, expects the append preview even when the model drops the target (the
 * deterministic assist names it), presses キャンセル (never a write), and removes its memo. No
 * existing document is touched. Runs only with `-e llmModelPath …` (skipped otherwise).
 */
class AiTargetResolutionSmokeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    private var scenario: ActivityScenario<MainActivity>? = null
    private var developerFile: File? = null
    private var tempMemo: Long? = null

    @Before
    fun nameTheModelAndMakeTheTemporaryMemo() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("llmModelPath")
        assumeTrue("no llmModelPath injected: the target-resolution smoke is skipped", !path.isNullOrBlank())
        val modelId = args.getString("llmModelId") ?: ModelProfiles.qwen3_4bInstruct2507.descriptor.id
        val dir = File(application.noBackupFilesDir, "models").also { it.mkdirs() }
        developerFile = File(dir, "developer.properties").also { it.writeText("modelId=$modelId\npath=$path\n") }
        runBlocking { application.settingsRepository.setAutoPlayOnLaunch(false) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun removeTheTemporaryMemoAndForget() {
        runBlocking {
            application.aiOrchestrator.release()
            tempMemo?.let { application.memoRepository.moveToTrash(it); application.database.memoDao().deletePermanently(it) }
        }
        developerFile?.delete()
        scenario?.close()
    }

    private fun log(message: String) = android.util.Log.i("AiTargetSmoke", message)

    private fun awaitTag(tag: String, timeoutMillis: Long = 15_000) {
        composeRule.waitUntil(timeoutMillis) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private val cards = listOf("chat_ai_preview_append", "chat_ai_candidates", "chat_ai_needs_information", "chat_ai_not_found", "chat_ai_invalid", "chat_ai_unknown", "chat_ai_runtime_error", "chat_ai_search_results", "chat_ai_opened", "chat_ai_model_unavailable", "chat_ai_thermal_blocked")

    private fun awaitCard(timeoutMillis: Long): String {
        var seen = ""
        composeRule.waitUntil(timeoutMillis) {
            seen = cards.firstOrNull { composeRule.onAllNodesWithTag(it, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }.orEmpty()
            seen.isNotEmpty()
        }
        return seen
    }

    @Test
    fun caseCReachesTheAppendPreviewOfTheTemporaryMemoEvenWhenTheModelDropsTheTarget() {
        // the exact fixture title — the shape in which Qwen drops the target (Phase 3–5 raw answers); only when the device has no such document
        val existing = runBlocking { application.documentAccess.search(io.github.cragcoffee.memoripple.domain.documents.DocumentQuery(text = "MemoRipple開発", limit = 5)) }
        assumeTrue("the device already holds a document named like the fixture; not touching it", existing.none { it.title.trim() == "MemoRipple開発" })
        val title = "MemoRipple開発"
        val now = application.timeProvider.nowMillis()
        tempMemo = runBlocking { application.database.memoDao().insert(MemoEntity(title = title, body = "## 進捗", createdAt = now, updatedAt = now, kind = "memo")) }
        val bodyBefore = runBlocking { application.database.memoDao().findById(tempMemo!!)!!.body }
        val idsBefore = runBlocking { application.database.memoDao().allIds().toSet() }

        awaitTag("nav_chat")
        composeRule.onNodeWithTag("nav_chat").performClick()
        awaitTag("chat_input")
        awaitTag("chat_input")

        val t0 = System.currentTimeMillis()
        composeRule.onNodeWithTag("chat_input").performTextClearance()
        composeRule.onNodeWithTag("chat_input").performTextInput("${title}に『Folder対応完了』を追記して")
        composeRule.onNodeWithTag("chat_send").performClick()
        val card = awaitCard(180_000)
        log("case C on '$title' → $card in ${System.currentTimeMillis() - t0} ms")
        assertEquals("the append preview of the temporary memo", "chat_ai_preview_append", card)
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains(title, substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("Folder対応完了", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()   // never confirmed on the device
        assertEquals("nothing written", bodyBefore, runBlocking { application.database.memoDao().findById(tempMemo!!)!!.body })
        assertEquals("no other document touched", idsBefore, runBlocking { application.database.memoDao().allIds().toSet() })
        assertTrue(true)
        log("done")
    }
}

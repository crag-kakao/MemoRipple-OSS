package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.ai.ModelProfiles
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
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
 * Phase 7 (docs/CHAT_V1_RELEASE_READINESS.md §15): the short final regression on the S20 with the
 * real model — the AI mode opens on its input (the model is there), one SEARCH, one target-assist
 * preview cancelled, one CREATE confirmed on a memo of this test's own, the unload on leaving the
 * mode, the search mode, the models screen. Short on purpose (the S20 reaches thermal SEVERE in
 * minutes of continuous inference). Every document it makes it removes; no existing document is
 * used as a fixture. Runs only with `-e llmModelPath …` (skipped otherwise).
 */
class ChatV1S20SmokeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    private var scenario: ActivityScenario<MainActivity>? = null
    private var developerFile: File? = null
    private val madeHere = ArrayList<Long>()
    private val nonce = (System.currentTimeMillis() % 100_000).toString()

    @Before
    fun nameTheModel() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("llmModelPath")
        assumeTrue("no llmModelPath injected: the Chat v1 S20 smoke is skipped", !path.isNullOrBlank())
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

    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun log(message: String) = android.util.Log.i("ChatV1Smoke", message)
    private fun thermal(): Int = (application.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus

    @Test
    fun theShortChatV1RegressionOnTheRealModel() {
        log("thermal before: ${thermal()}")
        val before = snapshot()
        // a target of this test's own for the assist, only when the device has no such document
        val existing = runBlocking { application.documentAccess.search(DocumentQuery(text = "MemoRipple開発", limit = 5)) }
        assumeTrue("the device already holds a document named like the fixture; not touching it", existing.none { it.title.trim() == "MemoRipple開発" })
        val now = application.timeProvider.nowMillis()
        val target = runBlocking { application.database.memoDao().insert(MemoEntity(title = "MemoRipple開発", body = "## 進捗", createdAt = now, updatedAt = now, kind = "memo")) }
        madeHere += target

        // 1. the AI mode opens on its input: the model is there (the developer file), no setup card
        awaitTag("nav_chat")
        composeRule.onNodeWithTag("nav_chat").performClick()
        awaitTag("chat_input")
        awaitTag("chat_input")
        assertEquals("no setup card with a model present", 0, count("chat_ai_model_unavailable"))

        // 2. one SEARCH
        var t0 = System.currentTimeMillis()
        ask("昨日の日記を探して")
        var card = awaitCard(180_000)
        log("SEARCH → $card in ${System.currentTimeMillis() - t0} ms, runtime ${application.aiOrchestrator.runtimeState()}")
        assertEquals("chat_ai_search_results", card)   // the result rows carry no 閉じる; the next ask replaces them

        // 3. the target-assist preview, cancelled
        t0 = System.currentTimeMillis()
        ask("MemoRipple開発に『Folder対応完了』を追記して")
        card = awaitCard(180_000)
        log("case C → $card in ${System.currentTimeMillis() - t0} ms")
        assertEquals("chat_ai_preview_append", card)
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("MemoRipple開発", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        assertEquals("nothing written by the cancelled preview", "## 進捗", runBlocking { application.database.memoDao().findById(target)!!.body })

        // 4. one safe CREATE, confirmed, on a title of this test's own
        val title = "Chat v1 smoke $nonce"
        val memosBefore = runBlocking { application.database.memoDao().allIds().toSet() }
        t0 = System.currentTimeMillis()
        ask("${title}というメモを作って")
        card = awaitCard(180_000)
        log("CREATE → $card in ${System.currentTimeMillis() - t0} ms")
        assertEquals("chat_ai_preview_create", card)
        assertEquals("nothing written by the preview", memosBefore, runBlocking { application.database.memoDao().allIds().toSet() })
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        awaitTag("memo_reading_view", 30_000)
        val made = runBlocking { application.database.memoDao().allIds().toSet() - memosBefore }
        assertEquals("exactly one document made", 1, made.size)
        madeHere += made
        log("confirmed create: memo ${made.single()}")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_ai_write_success")
        composeRule.onNodeWithTag("chat_ai_dismiss").performClick()

        // 5. an explicit release unloads (no mode to leave since the Chat UI redesign; the idle timer and memory pressure still apply)
        awaitTag("chat_input")
        runBlocking { application.aiOrchestrator.release() }
        composeRule.waitUntil(30_000) { application.aiOrchestrator.runtimeState() == RuntimeState.UNLOADED }
        log("after release: runtime ${application.aiOrchestrator.runtimeState()}")

        // 6. the models screen and its status, from the chat's overflow
        composeRule.onNodeWithTag("chat_overflow").performClick()
        awaitTag("chat_open_settings")
        composeRule.onNodeWithTag("chat_open_settings").performClick()
        awaitTag("settings_list")
        composeRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_ai_models"))
        composeRule.onNodeWithTag("settings_ai_models").performClick()
        awaitTag("ai_models_screen")
        composeRule.onNodeWithTag("ai_model_state_${ModelProfiles.qwen3_4bInstruct2507.descriptor.id}").assertIsDisplayed()
        log("models screen: ${application.modelManager.states.value}")
        composeRule.onNodeWithTag("ai_models_back").performClick()

        // the only documents that changed are this test's own
        val after = snapshot().lines().filterNot { line -> madeHere.any { line.startsWith("memo|$it|") } }.joinToString("\n")
        assertTrue("no existing document touched", after == before)
        log("thermal after: ${thermal()}")
    }
}

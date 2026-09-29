package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.ai.models.InstallState
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Local LLM Phase 5 (docs/AI_MODEL_MANAGEMENT.md): the Local AI モデル screen over the real manager,
 * store, downloader and DataStore — with a local fixture server and tiny model bodies in place of
 * the multi-GB files, and the device guards under test control. Nothing here downloads a real
 * model, and nothing here touches a document.
 */
class AiModelManagementInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    private val a get() = TestModelServer.A_ID
    private val b get() = TestModelServer.B_ID

    @Before
    fun cleanModelsAndGuards() {
        TestModelServer.reset()
        TestGuards.reset()
        runBlocking {
            application.database.clearAllTables()
            application.aiOrchestrator.release()
            application.modelManager.cancelAll()
            application.settingsRepository.setSelectedAiModelId(null)
            listOf(a, b).forEach { application.modelManager.delete(it) }
            application.modelManager.refresh()
        }
        TestAiRuntime.reset()
    }

    @After
    fun tidy() {
        TestModelServer.reset()
        TestGuards.reset()
        runBlocking { application.modelManager.cancelAll(); listOf(a, b).forEach { application.modelManager.delete(it) }; application.settingsRepository.setSelectedAiModelId(null) }
    }

    private fun modelDir(id: String) = File(application.noBackupFilesDir, "models/$id")

    private fun openModels() {
        composeRule.onNodeWithTag("nav_chat").performClick()
        awaitTag("chat_overflow")
        composeRule.onNodeWithTag("chat_overflow").performClick()
        awaitTag("chat_open_settings")
        composeRule.onNodeWithTag("chat_open_settings").performClick()
        awaitTag("settings_list")
        composeRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_ai_models"))
        composeRule.onNodeWithTag("settings_ai_models").performClick()
        awaitTag("ai_models_screen")
    }

    private fun state(id: String): InstallState = application.modelManager.states.value.getValue(id)

    // --- RED 32–39: the screen lists both candidates neutrally with size, status, actions; nothing is selected ---

    @Test
    fun theSettingsRowOpensTheScreenWhichListsBothCandidatesWithoutARankingOrADefault() {
        openModels()
        composeRule.onNodeWithTag("ai_models_intro").assertTextContains("選択", substring = true)   // read before the scroll below takes the intro out of the lazy list
        composeRule.onNodeWithTag("ai_model_$a").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_model_$b").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Balanced").assertIsDisplayed()
        composeRule.onNodeWithText("Safety-oriented").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_model_status_$a", useUnmergedTree = true).assertTextContains("未ダウンロード", substring = true)
        composeRule.onNodeWithTag("ai_model_action_$a").assertTextContains("ダウンロード", substring = true)
        assertEquals(0, composeRule.onAllNodesWithTag("ai_model_selected_$a", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("ai_model_selected_$b", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("ai_model_select_$a", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertNull(runBlocking { application.settingsRepository.selectedAiModelId.first() })
    }

    // --- RED 19, 35, 8, 39, 4: metered asks first; progress; verified install; then the user selects ---

    @Test
    fun aMeteredDownloadAsksFirstThenProgressesVerifiesInstallsAndOnlyAnExplicitTapSelects() {
        TestGuards.metered = true
        TestModelServer.throttleMillisPerChunk = 40
        openModels()
        composeRule.onNodeWithTag("ai_model_action_$a", useUnmergedTree = true).performClick()
        awaitTag("ai_model_metered_dialog")
        composeRule.onNodeWithText("モバイルデータでダウンロードしますか？").assertIsDisplayed()
        assertEquals(InstallState.NotInstalled, state(a))
        composeRule.onNodeWithTag("ai_model_metered_cancel").performClick()
        awaitGone("ai_model_metered_dialog")
        assertEquals(InstallState.NotInstalled, state(a))
        composeRule.onNodeWithTag("ai_model_action_$a", useUnmergedTree = true).performClick()
        awaitTag("ai_model_metered_dialog")
        composeRule.onNodeWithTag("ai_model_metered_confirm").performClick()
        awaitTag("ai_model_progress_$a")
        composeRule.onNodeWithTag("ai_model_action_$a").assertTextContains("キャンセル", substring = true)
        composeRule.waitUntil(30_000) { state(a) is InstallState.Installed }
        awaitTag("ai_model_select_$a")
        composeRule.onNodeWithTag("ai_model_status_$a", useUnmergedTree = true).assertTextContains("ダウンロード済み", substring = true)
        assertTrue(File(modelDir(a), "model.gguf").exists())
        assertFalse(File(modelDir(a), "model.gguf.part").exists())
        assertNull("installing never selects", runBlocking { application.settingsRepository.selectedAiModelId.first() })
        assertEquals(0, composeRule.onAllNodesWithTag("ai_model_selected_$a", useUnmergedTree = true).fetchSemanticsNodes().size)

        composeRule.onNodeWithTag("ai_model_select_$a", useUnmergedTree = true).performClick()
        awaitTag("ai_model_selected_$a")
        assertEquals(a, runBlocking { application.settingsRepository.selectedAiModelId.first() })
        assertEquals("selecting loads nothing", 0, TestAiRuntime.loads)
    }

    // --- RED 14, 15, 36, 37: cancel keeps the part and installs nothing; resume finishes it ---

    @Test
    fun cancelKeepsThePartInstallsNothingAndResumeFinishesFromWhereItStopped() {
        TestModelServer.throttleMillisPerChunk = 60
        openModels()
        composeRule.onNodeWithTag("ai_model_action_$a", useUnmergedTree = true).performClick()
        awaitTag("ai_model_progress_$a")
        composeRule.waitUntil(10_000) { (state(a) as? InstallState.Downloading)?.bytes?.let { it > 0 } == true }
        composeRule.onNodeWithTag("ai_model_action_$a", useUnmergedTree = true).performClick()   // キャンセル
        composeRule.waitUntil(10_000) { state(a) !is InstallState.Downloading }
        val partial = state(a)
        assertTrue("$partial", partial is InstallState.Partial)
        assertFalse(File(modelDir(a), "model.gguf").exists())
        assertTrue(File(modelDir(a), "model.gguf.part").length() > 0)
        composeRule.onNodeWithTag("ai_model_action_$a").assertTextContains("再開", substring = true)
        TestModelServer.throttleMillisPerChunk = 0
        composeRule.onNodeWithTag("ai_model_action_$a", useUnmergedTree = true).performClick()
        composeRule.waitUntil(30_000) { state(a) is InstallState.Installed }
        assertTrue(TestModelServer.requests.any { it.contains("Range: bytes=") })
        assertTrue(File(modelDir(a), "model.gguf").readBytes().contentEquals(TestModelServer.bodyA))
    }

    // --- RED 9, 10, 37: a wrong hash is a failure with a retry, never an install ---

    @Test
    fun aCorruptDownloadIsRejectedShownAsFailedAndRetryable() {
        TestModelServer.serveWrongBodyFor = a
        openModels()
        composeRule.onNodeWithTag("ai_model_action_$a", useUnmergedTree = true).performClick()
        composeRule.waitUntil(30_000) { state(a) is InstallState.Failed }
        composeRule.onNodeWithTag("ai_model_status_$a", useUnmergedTree = true).assertTextContains("検証", substring = true)
        composeRule.onNodeWithTag("ai_model_action_$a").assertTextContains("再試行", substring = true)
        assertFalse(File(modelDir(a), "model.gguf").exists())
        assertFalse(File(modelDir(a), "model.gguf.part").exists())
        assertEquals(0, composeRule.onAllNodesWithTag("ai_model_select_$a", useUnmergedTree = true).fetchSemanticsNodes().size)
        TestModelServer.serveWrongBodyFor = null
        composeRule.onNodeWithTag("ai_model_action_$a", useUnmergedTree = true).performClick()
        composeRule.waitUntil(30_000) { state(a) is InstallState.Installed }
    }

    // --- RED 17, 18: storage and CPU guards stop the download before a byte moves ---

    @Test
    fun insufficientStorageLowBatteryAndAnUnsupportedCpuAreExplainedAndNothingIsDownloaded() {
        TestGuards.usableBytes = 10
        openModels()
        composeRule.onNodeWithTag("ai_model_action_$a", useUnmergedTree = true).performClick()
        awaitTag("ai_model_message")
        composeRule.onNodeWithText("空き容量", substring = true).assertIsDisplayed()
        assertEquals(InstallState.NotInstalled, state(a))
        TestGuards.reset()
        TestGuards.batteryPercent = 5
        composeRule.onNodeWithTag("ai_model_message_dismiss").performClick()
        awaitGone("ai_model_message")
        composeRule.onNodeWithTag("ai_model_action_$a", useUnmergedTree = true).performClick()
        awaitTag("ai_model_message")
        composeRule.onNodeWithText("バッテリー", substring = true).assertIsDisplayed()
        assertEquals(InstallState.NotInstalled, state(a))
        assertTrue(TestModelServer.requests.isEmpty())

        TestGuards.reset()
        TestGuards.supported = false
        composeRule.activityRule.scenario.recreate()
        awaitTag("ai_models_screen")
        composeRule.onNodeWithTag("ai_models_unsupported").assertIsDisplayed().assertTextContains("この端末では", substring = true)
        composeRule.onNodeWithTag("ai_model_action_$a").assertIsNotEnabled()
        assertTrue(TestModelServer.requests.isEmpty())
    }

    // --- RED 22–25, 38, 20: delete asks; the selected model is unloaded, deselected, then removed; documents untouched ---

    @Test
    fun deletingTheSelectedModelAsksUnloadsClearsTheSelectionRemovesTheFilesAndLeavesDocumentsAlone() {
        val memo = runBlocking { application.database.memoDao().insert(MemoEntity(title = "残るメモ", body = "本文", createdAt = 1, updatedAt = 1, kind = "memo")) }
        openModels()
        composeRule.onNodeWithTag("ai_model_action_$a", useUnmergedTree = true).performClick()
        composeRule.waitUntil(30_000) { state(a) is InstallState.Installed }
        awaitTag("ai_model_select_$a")
        composeRule.onNodeWithTag("ai_model_select_$a", useUnmergedTree = true).performClick()
        awaitTag("ai_model_selected_$a")
        // pretend the model is loaded in the runtime (the scripted runtime): a delete must unload first
        runBlocking { TestAiRuntime().also { TestAiRuntime.current = it }.load(TestAiSelection.default) }
        composeRule.onNodeWithTag("ai_model_delete_$a", useUnmergedTree = true).performClick()
        awaitTag("ai_model_delete_dialog")
        composeRule.onNodeWithText("メモや日記は削除されません", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("ai_model_delete_cancel").performClick()
        awaitGone("ai_model_delete_dialog")
        assertTrue(File(modelDir(a), "model.gguf").exists())
        composeRule.onNodeWithTag("ai_model_delete_$a", useUnmergedTree = true).performClick()
        awaitTag("ai_model_delete_dialog")
        composeRule.onNodeWithTag("ai_model_delete_confirm").performClick()
        composeRule.waitUntil(10_000) { state(a) == InstallState.NotInstalled }
        assertFalse(modelDir(a).exists())
        assertNull(runBlocking { application.settingsRepository.selectedAiModelId.first() })
        assertEquals(0, composeRule.onAllNodesWithTag("ai_model_selected_$a", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(RuntimeState.UNLOADED, application.aiOrchestrator.runtimeState())
        assertEquals("本文", runBlocking { application.database.memoDao().findById(memo)!!.body })
        assertEquals(1, runBlocking { application.database.memoDao().allIds().size })
    }

    // --- RED 5, 21: two installed, one selected; switching unloads and loads nothing ---

    @Test
    fun twoInstalledModelsOneSelectedAndSwitchingUnloadsWithoutLoading() {
        openModels()
        composeRule.onNodeWithTag("ai_model_action_$a", useUnmergedTree = true).performClick()
        composeRule.waitUntil(30_000) { state(a) is InstallState.Installed }
        composeRule.onNodeWithTag("ai_model_action_$b", useUnmergedTree = true).performScrollTo().performClick()
        composeRule.waitUntil(30_000) { state(b) is InstallState.Installed }
        awaitTag("ai_model_select_$a")
        composeRule.onNodeWithTag("ai_model_select_$a", useUnmergedTree = true).performClick()
        awaitTag("ai_model_selected_$a")
        val loaded = TestAiRuntime().also { TestAiRuntime.current = it }
        runBlocking { loaded.load(TestAiSelection.default) }
        composeRule.onNodeWithTag("ai_model_select_$b", useUnmergedTree = true).performScrollTo().performClick()
        awaitTag("ai_model_selected_$b")
        assertEquals(0, composeRule.onAllNodesWithTag("ai_model_selected_$a", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(b, runBlocking { application.settingsRepository.selectedAiModelId.first() })
        composeRule.waitUntil(10_000) { application.aiOrchestrator.runtimeState() == RuntimeState.UNLOADED }
        assertEquals("the switch loads nothing", 1, TestAiRuntime.loads)
        assertTrue(File(modelDir(a), "model.gguf").exists() && File(modelDir(b), "model.gguf").exists())
    }

    // --- RED 40, 41: チャット explains the missing model and points to the settings; search still works ---

    @Test
    fun theChatExplainsAMissingSelectionOnAFreeTextSendAndOpensTheSettingsWhileThePlusStillWorks() {
        TestAiSelection.descriptor = null
        runBlocking { application.database.memoDao().insert(MemoEntity(title = "散歩のメモ", body = "本文", createdAt = 1, updatedAt = 1, kind = "memo")) }
        composeRule.onNodeWithTag("nav_chat").performClick()
        awaitTag("chat_input")
        // Chat UI redesign (2026-09-21): with nothing selected the chat still opens on its input; the hint says free text needs a model
        composeRule.onNodeWithTag("chat_ai_hint").assertTextContains("Local AIモデル", substring = true)
        // a conversational sentence: the Fast Path declines it, so free text still needs the model (docs/CHAT_FAST_PATH.md)
        composeRule.onNodeWithTag("chat_input").performTextInput("散歩について何かあったっけ")
        composeRule.onNodeWithTag("chat_send").performClick()
        awaitTag("chat_ai_model_unavailable")
        composeRule.onNodeWithTag("chat_ai_model_unavailable").assertTextContains("テンプレート", substring = true)
        composeRule.onNodeWithTag("chat_ai_open_settings").assertTextContains("AIモデルを設定", substring = true)
        composeRule.onNodeWithTag("chat_ai_open_settings").performClick()
        awaitTag("ai_models_screen")
        composeRule.onNodeWithTag("ai_model_$a").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_models_back").performClick()
        awaitTag("chat_input")
        composeRule.onNodeWithTag("chat_plus").performClick()
        awaitTag("chat_template_picker")
        TestAiSelection.reset()
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitGone(tag: String) {
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }
}

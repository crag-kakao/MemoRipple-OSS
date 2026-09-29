package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.PickerDrill.drillToTemplate
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.ai.models.DownloadRequest
import io.github.cragcoffee.memoripple.domain.ai.models.InstallFailure
import io.github.cragcoffee.memoripple.domain.ai.models.InstallState
import io.github.cragcoffee.memoripple.domain.ai.models.SelectOutcome
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeFailure
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Local LLM Phase 7 (docs/CHAT_V1_RELEASE_READINESS.md): the Chat v1 smoke matrix on a
 * release-shaped path — the **product selection** over the real store and the real preference
 * (no developer file), the real manager over the loopback fixture, the scripted runtime for the
 * model itself. Journeys A–M: first use, search without a model, the AI verbs, conflict,
 * ambiguity, thermal, offline, deletion, process death, retry.
 */
class ChatV1ReleaseReadinessInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    private val SEARCH_TEMPLATE_ID = "t-walk"
    private val a get() = TestModelServer.A_ID
    private val b get() = TestModelServer.B_ID
    private val today: LocalDate get() = application.timeProvider.currentLocalDate()

    @Before
    fun releaseShapedAndEmpty() {
        TestModelServer.reset(); TestGuards.reset(); TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0
        TestGuards.fastPathEnabled = false   // this class exercises the model pipeline; its plain sentences must reach the generator (docs/CHAT_FAST_PATH.md)
        runBlocking {
            application.database.clearAllTables()
            application.templateRepository.replaceAll(emptyList())
            application.settingsRepository.setAutoPlayOnLaunch(false)
            application.aiOrchestrator.release()
            application.modelManager.cancelAll()
            application.settingsRepository.setSelectedAiModelId(null)
            listOf(a, b).forEach { application.modelManager.delete(it) }
            application.modelManager.refresh()
        }
        runBlocking {
            application.templateRepository.replaceAll(listOf(io.github.cragcoffee.memoripple.domain.memos.MemoTemplate(id = SEARCH_TEMPLATE_ID, name = "散歩を探す", body = "", action = io.github.cragcoffee.memoripple.domain.memos.TemplateAction.SEARCH, searchSpec = io.github.cragcoffee.memoripple.domain.memos.TemplateSearchSpec("散歩", null, setOf(DocumentKind.MEMO, DocumentKind.OUTLINE, DocumentKind.JOURNAL)))))
        }
        TestAiSelection.useProduct(application)
    }

    @After
    fun tidy() {
        TestGuards.fastPathEnabled = true   // hand the product default back to the suite
        TestModelServer.reset(); TestGuards.reset(); TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0
        runBlocking {
            application.aiOrchestrator.release()
            application.modelManager.cancelAll()
            listOf(a, b).forEach { application.modelManager.delete(it) }
            application.settingsRepository.setSelectedAiModelId(null)
            application.templateRepository.replaceAll(emptyList())
        }
    }

    // --- fixtures ---

    private fun at(date: LocalDate, hour: Int): Long = application.timeProvider.toEpochMillis(LocalDateTime.of(date, java.time.LocalTime.of(hour, 0)))
    private fun memo(title: String, body: String, kind: String = "memo"): Long = runBlocking {
        val t = at(today, 9)
        application.database.memoDao().insert(MemoEntity(title = title, body = body, createdAt = t, updatedAt = t, kind = kind))
    }
    private fun journal(body: String, date: LocalDate): Long = runBlocking {
        application.database.diaryDao().insert(DiaryEntryEntity(diaryDateEpochDay = date.toEpochDay(), body = body, state = DiaryState.DRAFT, createdAt = at(date, 20), updatedAt = at(date, 20)))
    }
    private fun memoBody(id: Long) = runBlocking { application.database.memoDao().findById(id)!!.body }
    private fun memoCount() = runBlocking { application.database.memoDao().allIds().size }
    private fun snapshot(): String = runBlocking {
        val dao = application.database.memoDao()
        val memos = dao.allIds().sorted().map { id -> dao.findById(id)!!.let { "memo|${it.id}|${it.title}|${it.body}|${it.updatedAt}" } }
        val journals = application.database.diaryDao().observeAll().first().sortedBy { it.id }.map { "journal|${it.id}|${it.body}|${it.updatedAt}" }
        (memos + journals).joinToString("\n")
    }
    private fun modelFile(id: String) = File(application.noBackupFilesDir, "models/$id/model.gguf")
    private fun state(id: String): InstallState = application.modelManager.states.value.getValue(id)
    private fun install(id: String) {
        assertEquals(DownloadRequest.Started, runBlocking { application.modelManager.requestDownload(id) })
        runBlocking { withTimeout(60_000) { application.modelManager.states.first { it.getValue(id) is InstallState.Installed } } }
    }
    private fun select(id: String) { assertEquals(SelectOutcome.Selected, runBlocking { application.modelManager.select(id) }) }
    private fun installAndSelect(id: String) { install(id); select(id) }
    private fun selectedId(): String? = runBlocking { application.settingsRepository.selectedAiModelId.first() }

    private fun proposal(intent: String, query: String? = null, targetRef: String? = null, targetName: String? = null, documentKind: String? = null, text: String? = null, dateToken: String? = null, missing: List<String> = emptyList()): String {
        fun s(v: String?) = if (v == null) "null" else "\"" + v.replace("\"", "\\\"") + "\""
        return "{\"intent\":${s(intent)},\"query\":${s(query)},\"targetRef\":${s(targetRef)},\"targetName\":${s(targetName)},\"documentKind\":${s(documentKind)}," +
            "\"text\":${s(text)},\"templateId\":null,\"dateToken\":${s(dateToken)},\"missingFields\":[${missing.joinToString(",") { s(it) }}]}"
    }
    private fun script(vararg answers: String) { TestAiRuntime.answers.addAll(answers) }

    // --- screen ---

    private fun openChat() { composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input") }
    // Chat UI redesign (2026-09-21): no mode — the chat opens on its input; free text needs a model, templates do not
    private fun aiMode() { awaitTag("chat_input") }
    private fun aiModeReady() { awaitTag("chat_input") }
    /** With no usable model the chat still shows its input and a hint; the setup card is the *result* of a free-text send. */
    private fun awaitUnavailable() {
        awaitTag("chat_input")
        if (count("chat_ai_model_unavailable") == 0) { awaitTag("chat_ai_hint"); ask("昨日の日記を探して") }
        awaitTag("chat_ai_model_unavailable")
    }
    private fun ask(text: String) {
        composeRule.onNodeWithTag("chat_input").performTextClearance()
        composeRule.onNodeWithTag("chat_input").performTextInput(text)
        composeRule.onNodeWithTag("chat_send").performClick()
    }
    /** Search without a model: a SEARCH template from the plus button (Template v2) answers in the conversation. */
    private fun searchWorks(id: Long) {
        awaitTag("chat_input")
        composeRule.onNodeWithTag("chat_plus").performClick()
        awaitTag("chat_template_picker")
        composeRule.drillToTemplate(SEARCH_TEMPLATE_ID, application)
        composeRule.onNodeWithTag("chat_template_item_$SEARCH_TEMPLATE_ID").performClick()
        awaitTag("chat_result_memo_$id")
    }
    private fun awaitTag(tag: String, timeout: Long = 15_000) {
        composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitGone(tag: String, timeout: Long = 15_000) {
        composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }
    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun awaitText(tag: String, text: String, timeout: Long = 15_000, unmerged: Boolean = false) {
        composeRule.waitUntil(timeout) {
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().any { node ->
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text.contains(text) } == true
            }
        }
    }

    // --- A (RED 1, 2, 4): fresh → setup card → install → select → back → usable ---

    @Test
    fun aFreshChatShowsTheHintAndTheSetupCardOnAFreeTextSendAndAfterInstallAndSelectTheAiIsUsable() {
        journal("散歩の記録", today.minusDays(1))
        openChat()
        awaitUnavailable()
        composeRule.onNodeWithTag("chat_ai_model_unavailable").assertTextContains("Local AIモデルが必要", substring = true)
        composeRule.onNodeWithTag("chat_ai_model_unavailable").assertTextContains("テンプレートはそのまま使えます", substring = true)
        composeRule.onNodeWithTag("chat_ai_open_settings").assertTextContains("AIモデルを設定", substring = true)
        composeRule.onNodeWithTag("chat_ai_open_settings").performClick()
        awaitTag("ai_models_screen")
        composeRule.onNodeWithTag("ai_model_action_$a", useUnmergedTree = true).performClick()
        awaitTag("ai_model_select_$a", 60_000)
        composeRule.onNodeWithTag("ai_model_state_$a").assertTextContains("ダウンロード済み", substring = true)
        assertEquals(0, count("ai_model_selected_$a"))
        composeRule.onNodeWithTag("ai_model_select_$a", useUnmergedTree = true).performClick()
        awaitTag("ai_model_selected_$a")
        assertEquals(a, selectedId())
        composeRule.onNodeWithTag("ai_models_back").performClick()
        // back in the chat the availability is checked again: the hint is gone; the earlier card is put away and the ask works
        awaitTag("chat_input")
        composeRule.waitUntil(15_000) { count("chat_ai_hint") == 0 }
        composeRule.onNodeWithTag("chat_ai_dismiss").performClick()
        awaitGone("chat_ai_model_unavailable")
        script(proposal("SEARCH", query = "散歩", dateToken = "yesterday"))
        ask("昨日の日記を探して")
        awaitTag("chat_ai_search_results")
        assertEquals(1, TestAiRuntime.loads)
    }

    // --- B (RED 3): search never needs a model ---

    @Test
    fun searchWorksWithNoModelAtAll() {
        val id = memo("散歩のメモ", "本文")
        openChat()
        searchWorks(id)
        assertEquals("a template search loads nothing", 0, TestAiRuntime.loads)
        awaitUnavailable()
        searchWorks(id)
    }

    // --- RED 5: installed but not selected is not a model ---

    @Test
    fun anInstalledButUnselectedModelLeavesTheAiUnavailableWithTheSelectionWording() {
        install(a)
        openChat()
        awaitUnavailable()
        composeRule.onNodeWithTag("chat_ai_model_unavailable").assertTextContains("Local AIモデルが必要", substring = true)
        assertEquals(0, TestAiRuntime.loads)
        assertEquals(null, selectedId())
    }

    // --- RED 6, 8: the selected file vanished → missing; the selection is kept, nothing else is picked ---

    @Test
    fun aSelectedModelWhoseFileVanishedIsMissingAndTheSelectionIsKept() {
        installAndSelect(a)
        install(b)
        assertTrue(modelFile(a).delete())
        openChat()
        awaitUnavailable()
        composeRule.onNodeWithTag("chat_ai_model_unavailable").assertTextContains("見つかりません", substring = true)
        composeRule.onNodeWithTag("chat_ai_open_settings").assertIsDisplayed()
        assertEquals("no silent switch to the other installed model", a, selectedId())
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- RED 7, 8: a corrupt file is unavailable before any load; the models screen says so ---

    @Test
    fun aSelectedModelWithACorruptFileIsUnavailableBeforeAnyLoadAndTheScreenShowsItFailed() {
        installAndSelect(a)
        install(b)
        modelFile(a).writeBytes(ByteArray(4321))
        openChat()
        awaitUnavailable()
        composeRule.onNodeWithTag("chat_ai_model_unavailable").assertTextContains("壊れています", substring = true)
        assertEquals(0, TestAiRuntime.loads)
        assertEquals(a, selectedId())
        composeRule.onNodeWithTag("chat_ai_open_settings").performClick()
        awaitTag("ai_models_screen")
        composeRule.waitUntil(15_000) { state(a) is InstallState.Failed }
        assertEquals(InstallState.Failed(InstallFailure.CORRUPT_FILE, 0), state(a))
        awaitText("ai_model_status_$a", "壊れています", unmerged = true)   // the state flow reaches the screen a frame after the manager
        composeRule.onNodeWithTag("ai_model_state_$a").assertTextContains("失敗", substring = true)
        // a retry replaces the bad file with a verified download
        composeRule.onNodeWithTag("ai_model_action_$a", useUnmergedTree = true).performClick()
        awaitTag("ai_model_delete_$a", 60_000)
        composeRule.waitUntil(60_000) { state(a) is InstallState.Installed }
        assertEquals(TestModelServer.bodyA.size.toLong(), modelFile(a).length())
    }

    // --- RED 9, 10: an unsupported CPU — no download, no AI, everything else works ---

    @Test
    fun anUnsupportedCpuDisablesDownloadsAndTheAiModeWhileSearchAndTheAppWork() {
        TestGuards.supported = false
        val id = memo("散歩のメモ", "本文")
        openChat()
        awaitUnavailable()
        composeRule.onNodeWithTag("chat_ai_model_unavailable").assertTextContains("この端末では", substring = true)
        assertEquals(0, count("chat_ai_open_settings"))
        searchWorks(id)
        composeRule.onNodeWithTag("chat_overflow").performClick()
        awaitTag("chat_open_settings")
        composeRule.onNodeWithTag("chat_open_settings").performClick()
        awaitTag("settings_list")
        composeRule.onNodeWithTag("settings_list").performScrollToNode(androidx.compose.ui.test.hasTestTag("settings_ai_models"))
        composeRule.onNodeWithTag("settings_ai_models").performClick()
        awaitTag("ai_models_screen")
        composeRule.onNodeWithTag("ai_models_unsupported").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_model_action_$a", useUnmergedTree = true).assertIsNotEnabled()
        composeRule.onNodeWithTag("ai_model_action_$b", useUnmergedTree = true).performScrollTo().assertIsNotEnabled()
        assertEquals(DownloadRequest.UnsupportedDevice, runBlocking { application.modelManager.requestDownload(a) })
        assertFalse(modelFile(a).parentFile?.exists() == true)
    }

    // --- K (RED 11): offline with an installed model the AI works ---

    @Test
    fun offlineWithAnInstalledSelectedModelTheAiStillWorks() {
        installAndSelect(a)
        journal("散歩の記録", today.minusDays(1))
        TestGuards.online = false
        openChat()
        aiModeReady()
        script(proposal("SEARCH", query = "散歩", dateToken = "yesterday"))
        ask("昨日の日記を探して")
        awaitTag("chat_ai_search_results")
        assertEquals(1, TestAiRuntime.requests)
    }

    // --- L (RED 12): offline with no model — no download starts, search works ---

    @Test
    fun offlineWithNoModelTheDownloadDoesNotStartAndSearchWorks() {
        TestGuards.online = false
        TestGuards.metered = true
        val id = memo("散歩のメモ", "本文")
        openChat()
        awaitUnavailable()
        composeRule.onNodeWithTag("chat_ai_open_settings").performClick()
        awaitTag("ai_models_screen")
        composeRule.onNodeWithTag("ai_model_action_$a", useUnmergedTree = true).performClick()
        awaitTag("ai_model_message")
        composeRule.onNodeWithText("ネットワークに接続していないため", substring = true).assertIsDisplayed()
        assertEquals("no metered question either", 0, count("ai_model_metered_dialog"))
        assertEquals(InstallState.NotInstalled, state(a))
        assertFalse(File(application.noBackupFilesDir, "models/$a/model.gguf.part").exists())
        assertTrue(TestModelServer.requests.isEmpty())
        composeRule.onNodeWithTag("ai_models_back").performClick()
        awaitTag("chat_input")
        searchWorks(id)
    }

    // --- RED 16, 17: process death during a preview — the preview and its ticket are gone, nothing was written ---

    @Test
    fun processDeathDuringAPreviewLosesThePreviewAndTheTicketAndWritesNothing() {
        installAndSelect(a)
        val target = memo("MemoRipple開発", "本文")
        val before = snapshot()
        openChat()
        aiModeReady()
        script(proposal("APPEND", targetName = "MemoRipple開発", text = "Folder対応完了"))
        ask("MemoRipple開発に『Folder対応完了』を追記して")
        awaitTag("chat_ai_preview_append")
        assertEquals(before, snapshot())
        // the process dies: the view models are gone, the saved instance state (mode, input) survives
        composeRule.activityRule.scenario.onActivity { it.viewModelStore.clear() }
        composeRule.activityRule.scenario.recreate()
        awaitTag("chat_input")
        // the send cleared the draft (Chat UI redesign); the user's line is in the transcript, which survives
        val c = runBlocking { application.chatHistoryRepository.conversations().first() }.single()
        val userLine = runBlocking { application.chatHistoryRepository.messages(c.id).first() }.first()
        awaitTag("chat_message_${userLine.id}")
        composeRule.onNodeWithTag("chat_message_${userLine.id}").assertTextContains("MemoRipple開発に『Folder対応完了』を追記して", substring = true)
        assertEquals("the preview did not survive", 0, count("chat_ai_preview_append"))
        assertEquals(0, count("chat_ai_preview_confirm"))
        assertEquals("nothing was written by the death or the restore", before, snapshot())
        assertEquals("本文", memoBody(target))
    }

    // --- J (RED 18): thermal SEVERE refuses the generation and the mode recovers ---

    @Test
    fun aHotDeviceRefusesTheGenerationWithTheUnifiedWordingAndRecoversWhenCool() {
        installAndSelect(a)
        journal("散歩の記録", today.minusDays(1))
        openChat()
        aiModeReady()
        TestThermal.status = 3
        ask("昨日の日記を探して")
        awaitTag("chat_ai_thermal_blocked")
        composeRule.onNodeWithTag("chat_ai_thermal_blocked").assertTextContains("端末が熱くなっているため、AIを一時停止しています", substring = true)
        composeRule.onNodeWithTag("chat_ai_thermal_blocked").assertTextContains("テンプレートはそのまま使えます", substring = true)
        assertEquals(0, TestAiRuntime.requests)
        assertEquals(0, TestAiRuntime.loads)
        TestThermal.status = 0
        composeRule.onNodeWithTag("chat_ai_dismiss").performClick()
        script(proposal("SEARCH", query = "散歩", dateToken = "yesterday"))
        ask("昨日の日記を探して")
        awaitTag("chat_ai_search_results")
    }

    // --- RED 21, 22: retry is one tap, once ---

    @Test
    fun aLoadFailureAndAGenerationFailureAreRetriedByOneTapEachAndNeverByThemselves() {
        installAndSelect(a)
        journal("散歩の記録", today.minusDays(1))
        openChat()
        aiModeReady()
        TestAiRuntime.loadFailure = RuntimeFailure.ENGINE_ERROR
        ask("昨日の日記を探して")
        awaitTag("chat_ai_runtime_error")
        composeRule.onNodeWithTag("chat_ai_runtime_error").assertTextContains("読み込めませんでした", substring = true)
        composeRule.onNodeWithTag("chat_ai_retry").assertTextContains("再試行", substring = true)
        assertEquals("exactly one attempt", 1, TestAiRuntime.loads)
        TestAiRuntime.loadFailure = null
        // nothing scripted: the retry loads, then the generation fails
        composeRule.onNodeWithTag("chat_ai_retry").performClick()
        awaitText("chat_ai_runtime_error", "応答できませんでした")   // the scripted load is instant: the card is replaced within a frame
        assertEquals(2, TestAiRuntime.loads)
        assertEquals(1, TestAiRuntime.requests)
        script(proposal("SEARCH", query = "散歩", dateToken = "yesterday"))
        composeRule.onNodeWithTag("chat_ai_retry").performClick()
        awaitTag("chat_ai_search_results")
        assertEquals("the loaded model was reused", 2, TestAiRuntime.loads)
        assertEquals(2, TestAiRuntime.requests)
    }

    // --- H (RED 23): a conflict offers a new preview, never a re-run ---

    @Test
    fun aConflictOffersANewPreviewAndNeverRerunsTheOldTicket() {
        installAndSelect(a)
        val target = memo("MemoRipple開発", "old")
        openChat()
        aiModeReady()
        script(proposal("APPEND", targetName = "MemoRipple開発", text = "Folder対応完了"))
        ask("MemoRipple開発に『Folder対応完了』を追記して")
        awaitTag("chat_ai_preview_append")
        runBlocking { application.database.memoDao().updateContent(target, "MemoRipple開発", "old + edited elsewhere", at(today, 12)) }
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        awaitTag("chat_ai_write_conflict")
        assertEquals("old + edited elsewhere", memoBody(target))
        composeRule.onNodeWithTag("chat_ai_reconfirm").assertTextContains("もう一度確認する", substring = true)
        script(proposal("APPEND", targetName = "MemoRipple開発", text = "Folder対応完了"))
        composeRule.onNodeWithTag("chat_ai_reconfirm").performClick()
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("old + edited elsewhere", substring = true)
        assertEquals("a new inference, not a re-run", 2, TestAiRuntime.requests)
        assertEquals("still nothing written", "old + edited elsewhere", memoBody(target))
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        awaitTag("memo_reading_view")
        assertEquals("old + edited elsewhere\nFolder対応完了", memoBody(target))
    }

    // --- M (RED 24): deleting the selected model puts the chat back on the setup card; search works ---

    @Test
    fun deletingTheSelectedModelReturnsTheChatToTheSetupCardAndSearchWorks() {
        installAndSelect(a)
        val id = memo("散歩のメモ", "本文")
        openChat()
        aiModeReady()
        script(proposal("SEARCH", query = "散歩"))
        ask("散歩を探して")
        awaitTag("chat_ai_search_results")
        assertEquals(1, TestAiRuntime.loads)
        composeRule.onNodeWithTag("chat_overflow").performClick()
        awaitTag("chat_open_settings")
        composeRule.onNodeWithTag("chat_open_settings").performClick()
        awaitTag("settings_list")
        composeRule.onNodeWithTag("settings_list").performScrollToNode(androidx.compose.ui.test.hasTestTag("settings_ai_models"))
        composeRule.onNodeWithTag("settings_ai_models").performClick()
        awaitTag("ai_models_screen")
        composeRule.onNodeWithTag("ai_model_delete_$a", useUnmergedTree = true).performClick()
        awaitTag("ai_model_delete_dialog")
        composeRule.onNodeWithTag("ai_model_delete_confirm").performClick()
        composeRule.waitUntil(15_000) { state(a) == InstallState.NotInstalled }
        assertEquals(null, selectedId())
        assertEquals("the runtime was unloaded", 1, TestAiRuntime.unloads)
        composeRule.onNodeWithTag("ai_models_back").performClick()
        awaitTag("settings_list")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitUnavailable()
        searchWorks(id)
    }

    // --- C, D, E, F, G, I: the verbs on an installed, selected model ---

    @Test
    fun theChatV1JourneyOnAnInstalledModelSearchOpenCaseCCreateAppendAndAmbiguity() {
        installAndSelect(a)
        val yesterday = journal("散歩の記録", today.minusDays(1))
        val dev = memo("MemoRipple開発", "## 進捗")
        val twinA = memo("週次レビュー", "a"); val twinB = memo("週次レビュー", "b", kind = "outline")
        val memosBefore = memoCount()
        openChat()
        aiModeReady()

        // C: AI SEARCH
        script(proposal("SEARCH", query = "散歩", dateToken = "yesterday"))
        ask("昨日の日記を探して")
        awaitTag("chat_ai_search_results")
        awaitTag("chat_result_journal_$yesterday")

        // D: OPEN unique
        script(proposal("OPEN", targetName = "MemoRipple開発"))
        ask("MemoRipple開発を開いて")
        awaitTag("memo_reading_view")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_input")

        // E: case C with the target dropped by the model → assist → preview → cancel
        val before = snapshot()
        script(proposal("APPEND", text = "Folder対応完了", missing = listOf("targetName")))
        ask("MemoRipple開発に『Folder対応完了』を追記して")
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("MemoRipple開発", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        awaitGone("chat_ai_preview_append")
        assertEquals(before, snapshot())

        // F: CREATE → preview → confirm → exactly one document
        script(proposal("CREATE", documentKind = "MEMO", text = "買い物リスト"))
        ask("買い物リストというメモを作って")
        awaitTag("chat_ai_preview_create")
        assertEquals(memosBefore, memoCount())
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        awaitTag("memo_reading_view")
        assertEquals(memosBefore + 1, memoCount())
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_ai_write_success")
        composeRule.onNodeWithTag("chat_ai_dismiss").performClick()

        // G: APPEND → preview → confirm → exactly one append
        script(proposal("APPEND", targetName = "MemoRipple開発", text = "Folder対応完了"))
        ask("MemoRipple開発に『Folder対応完了』を追記して")
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        awaitTag("memo_reading_view")
        assertEquals("## 進捗\nFolder対応完了", memoBody(dev))
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_ai_write_success")
        assertEquals("exactly once", "## 進捗\nFolder対応完了", memoBody(dev))
        composeRule.onNodeWithTag("chat_ai_dismiss").performClick()

        // I: Ambiguous → choose a candidate
        script(proposal("OPEN", targetName = "週次レビュー"))
        ask("週次レビューを開いて")
        awaitTag("chat_ai_candidates")
        assertEquals(2, count("chat_ai_candidate"))
        composeRule.onNodeWithTag("chat_ai_candidate_outline_$twinB").performClick()
        awaitTag("outliner_screen")
        assertEquals(memosBefore + 1, memoCount())
        assertEquals("a", memoBody(twinA))
        assertEquals("the model was loaded once for the whole journey", 1, TestAiRuntime.loads)
    }
}

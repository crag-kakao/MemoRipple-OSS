package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeFailure
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Local LLM Phase 3 (docs/AI_CHAT_PREVIEW.md): チャット gains an AI mode beside the unchanged
 * search mode. The model is a scripted runtime inside the test application; everything after it
 * — the parser, the validator, the resolver, the policy, the boundary, the navigator — is real.
 * SEARCH and a unique OPEN run; every write stops at a preview with cancel only, and the
 * database is byte-identical afterwards; every refusal is explained without touching anything.
 */
class AiChatPreviewInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    private val today: LocalDate get() = application.timeProvider.currentLocalDate()

    @Before
    fun startEmptyAndScripted() {
        runBlocking {
            application.database.clearAllTables()
            application.templateRepository.replaceAll(emptyList())
            application.settingsRepository.setAutoPlayOnLaunch(false)
            application.aiOrchestrator.release()
        }
        TestAiRuntime.reset()
        TestAiSelection.reset()
        TestThermal.status = 0
        TestGuards.fastPathEnabled = false   // this class exercises the model pipeline; its plain sentences must reach the generator (docs/CHAT_FAST_PATH.md)
    }

    @After
    fun unloadAndUnscript() {
        TestGuards.fastPathEnabled = true   // hand the product default back to the suite
        // a gated ask left open by a failed assertion would hold the orchestrator's lock and hang release(): open the gates first
        TestAiRuntime.loadGate?.complete(Unit); TestAiRuntime.generateGate?.complete(Unit)
        runBlocking { application.aiOrchestrator.release() }
        TestAiRuntime.reset()
        TestAiSelection.reset()
        TestThermal.status = 0
    }

    private fun at(date: LocalDate, hour: Int): Long =
        application.timeProvider.toEpochMillis(LocalDateTime.of(date, java.time.LocalTime.of(hour, 0)))

    private fun memo(title: String, body: String, kind: String = "memo", createdAt: Long = at(today, 9)): Long = runBlocking {
        application.database.memoDao().insert(MemoEntity(title = title, body = body, createdAt = createdAt, updatedAt = createdAt, kind = kind))
    }

    private fun journal(body: String, date: LocalDate, state: DiaryState = DiaryState.DRAFT, updatedAt: Long = at(date, 20)): Long = runBlocking {
        application.database.diaryDao().insert(DiaryEntryEntity(diaryDateEpochDay = date.toEpochDay(), body = body, state = state, createdAt = updatedAt, updatedAt = updatedAt))
    }

    /** Every row a write could touch, as text: memos, journals, templates. Equal strings = an untouched database. */
    private fun snapshot(): String = runBlocking {
        val dao = application.database.memoDao()
        val memos = dao.allIds().sorted().map { id -> dao.findById(id)!!.let { "memo|${it.id}|${it.title}|${it.body}|${it.updatedAt}|${it.kind}" } }
        val journals = application.database.diaryDao().observeAll().first().sortedBy { it.id }.map { "journal|${it.id}|${it.diaryDateEpochDay}|${it.body}|${it.updatedAt}|${it.state}" }
        val templates = application.templateRepository.current().map { "template|${it.id}|${it.name}|${it.body}" }
        (memos + journals + templates).joinToString("\n")
    }

    private fun openChat() {
        composeRule.onNodeWithTag("nav_chat").performClick()
        awaitTag("chat_input")
    }

    /** Chat UI redesign (2026-09-21): there is no mode — the chat's input is the AI input. Kept so the journeys read as before. */
    private fun aiMode() { awaitTag("chat_input") }

    private fun ask(text: String) {
        composeRule.onNodeWithTag("chat_input").performTextClearance()
        composeRule.onNodeWithTag("chat_input").performTextInput(text)
        composeRule.onNodeWithTag("chat_send").performClick()
    }

    private fun script(vararg answers: String) { TestAiRuntime.answers.addAll(answers) }

    private fun proposal(intent: String, query: String? = null, targetRef: String? = null, targetName: String? = null, documentKind: String? = null, text: String? = null, templateId: String? = null, dateToken: String? = null): String {
        fun s(v: String?) = if (v == null) "null" else "\"" + v.replace("\"", "\\\"") + "\""
        return "{\"intent\":${s(intent)},\"query\":${s(query)},\"targetRef\":${s(targetRef)},\"targetName\":${s(targetName)}," +
            "\"documentKind\":${s(documentKind)},\"text\":${s(text)},\"templateId\":${s(templateId)},\"dateToken\":${s(dateToken)},\"missingFields\":[]}"
    }

    private fun resultCount(): Int = composeRule.onAllNodesWithTag("chat_result", useUnmergedTree = true).fetchSemanticsNodes().size

    // --- RED 30, 31: the draft survives recreation; no result does (Chat UI redesign 2026-09-21: the search mode and its chips are gone) ---

    @Test
    fun theDraftSurvivesRecreationButNoResultDoes() {
        memo("MemoRipple開発", "本文")
        openChat()
        assertEquals("no mode switch, no chips, no fixed actions", 0, composeRule.onAllNodesWithTag("chat_mode_ai").fetchSemanticsNodes().size + composeRule.onAllNodesWithTag("chat_chip_today").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("chat_input").performTextInput("MemoRipple開発を開いて")
        script(proposal("OPEN", targetName = "存在しない"))
        composeRule.onNodeWithTag("chat_send").performClick()
        awaitTag("chat_ai_not_found")
        // the send cleared the draft; a new draft survives a recreation, the result card does not survive a process death
        composeRule.onNodeWithTag("chat_input").performTextInput("次の依頼")
        composeRule.activityRule.scenario.recreate()
        awaitTag("chat_input")
        composeRule.onNodeWithTag("nav_chat").assertIsSelected()
        composeRule.onNodeWithTag("chat_input").assertTextContains("次の依頼")
        composeRule.activityRule.scenario.onActivity { it.viewModelStore.clear() }
        composeRule.activityRule.scenario.recreate()
        awaitTag("chat_input")
        assertEquals(0, composeRule.onAllNodesWithTag("chat_ai_not_found").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("chat_input").assertTextContains("次の依頼")
    }

    // --- RED 4, 10, 11: an AI search runs through the boundary and reuses the result rows ---

    @Test
    fun anAiSearchRunsThroughTheBoundaryAndRendersTheExistingResultRows() {
        val yesterday = journal("昨日の散歩", today.minusDays(1))
        journal("今日の散歩", today)
        memo("散歩のメモ", "本文")
        openChat()
        aiMode()
        script(proposal("SEARCH", documentKind = "JOURNAL", dateToken = "YESTERDAY"))
        ask("昨日の日記を探して")
        awaitTag("chat_result_journal_$yesterday")
        assertEquals(1, resultCount())
        composeRule.onNodeWithTag("chat_ai_search_results").assertIsDisplayed()
        assertEquals(1, TestAiRuntime.loads)
        assertTrue(TestAiRuntime.lastUserMessage().contains("昨日の日記を探して"))
    }

    // --- RED 12, 42: a unique OPEN navigates; Back returns to チャット in AI mode ---

    @Test
    fun aUniqueOpenNavigatesToTheDocumentAndBackReturnsToTheAiMode() {
        memo("MemoRipple開発", "開発の本文")
        openChat()
        aiMode()
        script(proposal("OPEN", targetName = "MemoRipple開発"))
        ask("MemoRipple開発を開いて")
        awaitTag("memo_reading_view")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_input")
        composeRule.onNodeWithTag("nav_chat").assertIsSelected()
    }

    // --- RED 13, 14: an ambiguous OPEN opens nothing; the chosen candidate opens ---

    @Test
    fun anAmbiguousOpenListsTheCandidatesOpensNoneAndAChoiceOpensThatOne() {
        // the same exact title twice: the resolver prefers an exact title match, so only equals compete
        val a = memo("MemoRipple開発", "A")
        val b = memo("MemoRipple開発", "B")
        openChat()
        aiMode()
        script(proposal("OPEN", targetName = "MemoRipple開発"))
        ask("MemoRipple開発を開いて")
        awaitTag("chat_ai_candidates")
        composeRule.onNodeWithTag("chat_ai_candidate_memo_$a").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_ai_candidate_memo_$b").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTag("memo_reading_view").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("chat_ai_candidate_memo_$b").performClick()
        awaitTag("memo_reading_view")
        assertEquals("the choice is an app-side step, not a second generation", 1, TestAiRuntime.requests)
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_input")
        assertEquals(0, composeRule.onAllNodesWithTag("chat_ai_candidates").fetchSemanticsNodes().size)
    }

    // --- RED 15–17, 43: refusals are explained, safely, and nothing crashes ---

    @Test
    fun needsInformationUnknownInvalidAndMalformedAnswersAreExplainedSafely() {
        journal("確定した日", today.minusDays(10), state = DiaryState.LOCKED)
        openChat()
        aiMode()
        val before = snapshot()

        script(proposal("APPEND", text = "買い物"))
        ask("これに追記して")
        awaitTag("chat_ai_needs_information")
        composeRule.onNodeWithTag("chat_ai_needs_information").assertTextContains("追記先が分かりません", substring = true)

        script(proposal("UNKNOWN"))
        ask("今日の天気は？")
        awaitTag("chat_ai_unknown")

        script(proposal("DELETE", targetName = "確定した日"))
        ask("確定した日を消して")
        awaitTag("chat_ai_unknown")

        script(proposal("APPEND", targetName = "確定した日", text = "追記"))
        ask("確定した日に追記して")
        awaitTag("chat_ai_invalid")
        composeRule.onNodeWithTag("chat_ai_invalid").assertTextContains("追記できません", substring = true)

        script("{\"intent\": \"APPEND\", \"targetRef\": \"12345\"}")
        ask("これに追記して")
        awaitTag("chat_ai_runtime_error")
        composeRule.onNodeWithTag("chat_ai_runtime_error").assertTextContains("理解できませんでした", substring = true)

        script("not json at all")
        ask("これに追記して")
        awaitTag("chat_ai_runtime_error")

        assertEquals(0, composeRule.onAllNodesWithTag("chat_ai_preview_cancel").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("chat_ai_preview_confirm").fetchSemanticsNodes().size)
        assertEquals(before, snapshot())
    }

    // --- RED 18–24, 36, 52: case C stops at the append preview and the database is byte-identical ---

    @Test
    fun caseCShowsAnAppendPreviewAndUntilConfirmedTheDatabaseIsByteIdentical() {
        val target = memo("MemoRipple開発", "## 進捗\n- Chat v0 完了")
        memo("別のメモ", "本文")
        journal("昨日", today.minusDays(1))
        runBlocking { application.templateRepository.replaceAll(listOf(MemoTemplate("t1", "週次レビュー", "## 今週\n- "))) }
        val before = snapshot()
        openChat()
        aiMode()
        script(proposal("APPEND", targetName = "MemoRipple開発", text = "Folder対応完了"))
        ask("MemoRipple開発に『Folder対応完了』を追記して")
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("MemoRipple開発", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("Folder対応完了", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("Chat v0 完了", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_version", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("chat_ai_preview_cancel").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_ai_preview_confirm").assertIsDisplayed()   // Phase 4: offered, not pressed here
        // the preview is on screen and nothing has moved
        assertEquals(before, snapshot())
        assertEquals("## 進捗\n- Chat v0 完了", runBlocking { application.database.memoDao().findById(target)!!.body })

        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        awaitGone("chat_ai_preview_append")
        assertEquals(before, snapshot())
    }

    @Test
    fun createAndTemplatePreviewsShowWhatWouldBeMadeAndMakeNothing() {
        runBlocking { application.templateRepository.replaceAll(listOf(MemoTemplate("t1", "週次レビュー", "## 今週\n- \n## 来週\n- "))) }
        val before = snapshot()
        openChat()
        aiMode()
        script(proposal("CREATE", documentKind = "MEMO", text = "買い物リスト"))
        ask("新しいメモに買い物リストを作って")
        awaitTag("chat_ai_preview_create")
        composeRule.onNodeWithTag("chat_ai_preview_create").assertTextContains("メモ", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_create").assertTextContains("買い物リスト", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        awaitGone("chat_ai_preview_create")

        script(proposal("CREATE", documentKind = "JOURNAL", dateToken = "YESTERDAY"))
        ask("昨日の日記を作って")
        awaitTag("chat_ai_preview_create")
        composeRule.onNodeWithTag("chat_ai_preview_create").assertTextContains("日記", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        awaitGone("chat_ai_preview_create")

        script(proposal("USE_TEMPLATE", templateId = "週次レビュー"))
        ask("週次レビューのテンプレでメモを作って")
        awaitTag("chat_ai_preview_template")
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("週次レビュー", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("来週", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_confirm").assertIsDisplayed()   // Phase 4: offered, not pressed here
        assertEquals(before, snapshot())
        assertEquals(0, runBlocking { application.database.memoDao().allIds().size })
        assertEquals(0, runBlocking { application.database.diaryDao().observeAll().first().size })
    }

    // --- RED 25, 41: Back closes the preview, then the candidates, then leaves for メモ ---

    @Test
    fun backClosesThePreviewThenTheCandidatesAndOnlyThenLeavesForMemos() {
        memo("MemoRipple開発", "A")
        memo("MemoRipple開発", "B")
        openChat()
        aiMode()
        script(proposal("CREATE", documentKind = "MEMO", text = "買い物"))
        ask("新しいメモに買い物を作って")
        awaitTag("chat_ai_preview_create")
        Espresso.pressBack()
        awaitGone("chat_ai_preview_create")
        composeRule.onNodeWithTag("chat_input").assertIsDisplayed()

        script(proposal("OPEN", targetName = "MemoRipple開発"))
        ask("MemoRipple開発を開いて")
        awaitTag("chat_ai_candidates")
        Espresso.pressBack()
        awaitGone("chat_ai_candidates")
        composeRule.onNodeWithTag("chat_input").assertIsDisplayed()

        Espresso.pressBack()
        awaitTag("nav_memos")
        composeRule.onNodeWithTag("nav_memos").assertIsSelected()
    }

    // --- RED 5–7, 44: no model, an unsupported CPU and a hot device are quiet; search still works ---

    @Test
    fun noModelAnUnsupportedCpuAndAHotDeviceAreExplainedAndSearchStillWorks() {
        val id = memo("散歩のメモ", "本文")
        openChat()
        aiMode()

        // Phase 7 / Chat UI redesign: a free-text ask that finds no model shows the setup card as its result; the input and the plus stay
        TestAiSelection.descriptor = null
        ask("昨日の日記を探して")
        awaitTag("chat_ai_model_unavailable")
        composeRule.onNodeWithTag("chat_ai_model_unavailable").assertTextContains("テンプレート", substring = true)
        composeRule.onNodeWithTag("chat_ai_open_settings").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_input").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_plus").assertIsDisplayed()
        assertEquals(0, TestAiRuntime.loads)
        TestAiSelection.reset()
        composeRule.onNodeWithTag("chat_ai_dismiss").performClick()
        awaitTag("chat_input")

        TestAiRuntime.loadFailure = RuntimeFailure.MODEL_FILE_MISSING
        ask("昨日の日記を探して")
        awaitTag("chat_ai_model_unavailable")
        composeRule.onNodeWithTag("chat_ai_model_unavailable").assertTextContains("見つかりません", substring = true)
        TestAiRuntime.loadFailure = null
        composeRule.onNodeWithTag("chat_ai_dismiss").performClick()
        awaitTag("chat_input")

        TestAiRuntime.loadFailure = RuntimeFailure.UNSUPPORTED_DEVICE
        ask("昨日の日記を探して")
        awaitTag("chat_ai_model_unavailable")
        composeRule.onNodeWithTag("chat_ai_model_unavailable").assertTextContains("この端末では", substring = true)
        assertEquals("no settings shortcut for a CPU nothing can fix", 0, composeRule.onAllNodesWithTag("chat_ai_open_settings").fetchSemanticsNodes().size)
        TestAiRuntime.loadFailure = null
        composeRule.onNodeWithTag("chat_ai_dismiss").performClick()
        awaitTag("chat_input")

        TestThermal.status = 3
        ask("昨日の日記を探して")
        awaitTag("chat_ai_thermal_blocked")
        composeRule.onNodeWithTag("chat_ai_thermal_blocked").assertTextContains("一時停止", substring = true)
        assertEquals(0, TestAiRuntime.requests)
        TestThermal.status = 0

        TestAiRuntime.loadFailure = RuntimeFailure.ENGINE_ERROR
        ask("昨日の日記を探して")
        awaitTag("chat_ai_runtime_error")
        composeRule.onNodeWithTag("chat_ai_runtime_error").assertTextContains("読み込めませんでした", substring = true)
        TestAiRuntime.loadFailure = null
        composeRule.onNodeWithTag("chat_ai_dismiss").performClick()
        // the chat itself stays usable throughout: the input, the plus, the templates (Chat UI redesign 2026-09-21)
        composeRule.onNodeWithTag("chat_input").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_plus").assertIsDisplayed()
        assertTrue(id > 0)
    }

    // --- RED 8, 9: the loading and the thinking states are shown while the model works ---

    @Test
    fun loadingAndThinkingAreShownWhileTheModelWorksAndTheResultReplacesThem() {
        val id = memo("散歩のメモ", "本文")
        openChat()
        aiMode()
        val loadGate = CompletableDeferred<Unit>()
        val generateGate = CompletableDeferred<Unit>()
        TestAiRuntime.loadGate = loadGate
        TestAiRuntime.generateGate = generateGate
        script(proposal("SEARCH", query = "散歩"))
        ask("散歩を探して")
        awaitTag("chat_ai_status_loading")
        composeRule.onNodeWithTag("chat_ai_status_loading").assertTextContains("読み込んでいます", substring = true)
        loadGate.complete(Unit)
        awaitTag("chat_ai_status_generating")
        composeRule.onNodeWithTag("chat_ai_status_generating").assertTextContains("処理しています", substring = true)   // Phase 7 wording: the model is not a person
        generateGate.complete(Unit)
        awaitTag("chat_result_memo_$id")
        assertEquals(0, composeRule.onAllNodesWithTag("chat_ai_status_generating").fetchSemanticsNodes().size)
    }

    // --- RED 51, amended by Phase 2 (docs/AI_RESOURCE_CONTROLLER.md, human brief 2026-09-23): the screen's
    // life no longer owns the model — clearing the chat's view model leaves a warm model warm (no reload on
    // the next ask), and nothing stays resident because the controller's idle deadline still unloads it ---

    @Test
    fun clearingTheChatsViewModelKeepsTheWarmModelAndTheIdleDeadlineStillUnloadsIt() {
        memo("散歩のメモ", "本文")
        openChat()
        script(proposal("SEARCH", query = "散歩"))
        ask("散歩を探して")
        awaitTag("chat_ai_search_results")
        assertEquals(RuntimeState.READY, TestAiRuntime.current!!.state())
        composeRule.waitUntil(10_000) { TestIdleClock.pending() == 1 }
        composeRule.activityRule.scenario.onActivity { it.viewModelStore.clear() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals("the screen going away unloads nothing", 0, TestAiRuntime.unloads)
        assertEquals(RuntimeState.READY, application.aiOrchestrator.runtimeState())
        assertTrue(TestIdleClock.advance())
        composeRule.waitUntil(10_000) { TestAiRuntime.unloads >= 1 && TestAiRuntime.current!!.state() == RuntimeState.UNLOADED }
        assertEquals(RuntimeState.UNLOADED, application.aiOrchestrator.runtimeState())
    }

    // --- RED 26: the shown results are the model's context for the next request, and only that ---

    @Test
    fun theShownResultsAreTheContextOfTheNextRequestAndAShownRefOpensWithoutASearch() {
        val a = memo("散歩のメモ", "A")
        val b = memo("散歩の計画", "B")
        openChat()
        aiMode()
        script(proposal("SEARCH", query = "散歩"))
        ask("散歩を探して")
        awaitTag("chat_result_memo_$a")
        awaitTag("chat_result_memo_$b")
        assertTrue(TestAiRuntime.lastUserMessage().contains("(なし)"))

        script(proposal("OPEN", targetRef = "result_2"))
        ask("2番目を開いて")
        awaitTag("memo_reading_view")
        val message = TestAiRuntime.lastUserMessage()
        assertTrue(message, message.contains("result_1: 散歩のメモ") && message.contains("result_2: 散歩の計画"))
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_input")
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitGone(tag: String) {
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }
}

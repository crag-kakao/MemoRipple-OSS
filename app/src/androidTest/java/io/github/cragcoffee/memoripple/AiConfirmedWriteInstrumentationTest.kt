package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
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
 * Local LLM Phase 4 (docs/AI_CONFIRMED_WRITE.md): a previewed CREATE / APPEND / USE_TEMPLATE runs
 * only when the user presses the confirm button, exactly once, through the real boundary; a
 * cancel writes nothing; a document that moved after the preview is a conflict and stays as it
 * is; a double tap writes once; the result opens the document and Back returns to チャット.
 * The model is the scripted runtime; everything after it is real.
 */
class AiConfirmedWriteInstrumentationTest {
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
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0
        TestGuards.fastPathEnabled = false   // this class exercises the model pipeline; its plain sentences must reach the generator (docs/CHAT_FAST_PATH.md)
    }

    @After
    fun unloadAndUnscript() {
        TestGuards.fastPathEnabled = true   // hand the product default back to the suite
        runBlocking { application.aiOrchestrator.release() }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0
    }

    private fun at(date: LocalDate, hour: Int): Long =
        application.timeProvider.toEpochMillis(LocalDateTime.of(date, java.time.LocalTime.of(hour, 0)))

    private fun memo(title: String, body: String, kind: String = "memo", createdAt: Long = at(today, 9)): Long = runBlocking {
        application.database.memoDao().insert(MemoEntity(title = title, body = body, createdAt = createdAt, updatedAt = createdAt, kind = kind))
    }

    private fun journal(body: String, date: LocalDate, state: DiaryState = DiaryState.DRAFT, updatedAt: Long = at(date, 20)): Long = runBlocking {
        application.database.diaryDao().insert(DiaryEntryEntity(diaryDateEpochDay = date.toEpochDay(), body = body, state = state, createdAt = updatedAt, updatedAt = updatedAt))
    }

    private fun memoBody(id: Long): String = runBlocking { application.database.memoDao().findById(id)!!.body }
    private fun memoCount(): Int = runBlocking { application.database.memoDao().allIds().size }
    private fun journalCount(): Int = runBlocking { application.database.diaryDao().observeAll().first().size }

    private fun snapshot(): String = runBlocking {
        val dao = application.database.memoDao()
        val memos = dao.allIds().sorted().map { id -> dao.findById(id)!!.let { "memo|${it.id}|${it.title}|${it.body}|${it.updatedAt}|${it.kind}" } }
        val journals = application.database.diaryDao().observeAll().first().sortedBy { it.id }.map { "journal|${it.id}|${it.diaryDateEpochDay}|${it.body}|${it.updatedAt}|${it.state}" }
        val templates = application.templateRepository.current().map { "template|${it.id}|${it.name}|${it.body}" }
        (memos + journals + templates).joinToString("\n")
    }

    private fun openChatAi() {
        composeRule.onNodeWithTag("nav_chat").performClick()
        awaitTag("chat_input")
        awaitTag("chat_input")
    }

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

    private fun confirm() = composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
    private fun cancel() = composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()

    // --- RED 1–7, 53: every preview shows cancel + confirm; nothing moves until confirm; cancel writes nothing ---

    @Test
    fun everyPreviewOffersCancelAndConfirmAndACancelWritesNothing() {
        val target = memo("MemoRipple開発", "## 進捗")
        runBlocking { application.templateRepository.replaceAll(listOf(MemoTemplate("t1", "週次レビュー", "## 今週\n- "))) }
        val before = snapshot()
        openChatAi()

        script(proposal("CREATE", documentKind = "MEMO", text = "買い物リスト"))
        ask("新しいメモに買い物リストを作って")
        awaitTag("chat_ai_preview_create")
        composeRule.onNodeWithTag("chat_ai_preview_confirm").assertIsDisplayed().assertTextContains("作成", substring = true)
        assertEquals(before, snapshot())
        cancel()
        awaitGone("chat_ai_preview_create")
        assertEquals(before, snapshot())

        script(proposal("APPEND", targetName = "MemoRipple開発", text = "Folder対応完了"))
        ask("MemoRipple開発に『Folder対応完了』を追記して")
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_confirm").assertIsDisplayed().assertTextContains("追記", substring = true)
        assertEquals(before, snapshot())
        cancel()
        awaitGone("chat_ai_preview_append")
        assertEquals(before, snapshot())
        assertEquals("## 進捗", memoBody(target))

        script(proposal("USE_TEMPLATE", templateId = "週次レビュー"))
        ask("週次レビューのテンプレでメモを作って")
        awaitTag("chat_ai_preview_template")
        composeRule.onNodeWithTag("chat_ai_preview_confirm").assertIsDisplayed().assertTextContains("作成", substring = true)
        cancel()
        awaitGone("chat_ai_preview_template")
        assertEquals(before, snapshot())
        // the input is ready for the next ask after a cancel (a send clears the draft since the Chat UI redesign); the result context is gone
        composeRule.onNodeWithTag("chat_input").assertIsDisplayed()
    }

    // --- RED 8, 11, 14, 17, 54: confirm CREATE once, opens the created memo, a double tap makes one ---

    @Test
    fun confirmingACreateMakesOneMemoOpensItAndADoubleTapMakesNoSecond() {
        openChatAi()
        script(proposal("CREATE", documentKind = "MEMO", text = "買い物リスト"))
        ask("新しいメモに買い物リストを作って")
        awaitTag("chat_ai_preview_create")
        assertEquals(0, memoCount())
        confirm()
        // a second tap lands on the executing state, or on a state that has no confirm at all
        composeRule.onAllNodesWithTag("chat_ai_preview_confirm").fetchSemanticsNodes().firstOrNull()?.let { runCatching { confirm() } }
        awaitTag("memo_reading_view")
        assertEquals(1, memoCount())
        assertEquals("買い物リスト", memoBody(runBlocking { application.database.memoDao().allIds().single() }))
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_ai_write_success")
        composeRule.onNodeWithTag("nav_chat").assertIsSelected()
        assertEquals(0, composeRule.onAllNodesWithTag("chat_ai_preview_confirm").fetchSemanticsNodes().size)
        assertEquals(1, memoCount())
    }

    @Test
    fun anEmptyCreateOpensTheEditorWritingAsTheExistingRuleSays() {
        openChatAi()
        script(proposal("CREATE", documentKind = "MEMO"))
        ask("新しいメモを作って")
        awaitTag("chat_ai_preview_create")
        composeRule.onNodeWithTag("chat_ai_preview_create").assertTextContains("空のまま作成", substring = true)
        confirm()
        awaitTag("memo_body")
        assertEquals(1, memoCount())
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_input")
    }

    // --- RED 9, 12, 15: confirm APPEND once with the preview's version, opens the target ---

    @Test
    fun confirmingAnAppendWritesOnceWithThePreviewsVersionAndOpensTheTarget() {
        val target = memo("MemoRipple開発", "## 進捗\n- Chat v0 完了")
        memo("別のメモ", "x")
        openChatAi()
        script(proposal("APPEND", targetName = "MemoRipple開発", text = "Folder対応完了"))
        ask("MemoRipple開発に『Folder対応完了』を追記して")
        awaitTag("chat_ai_preview_append")
        confirm()
        composeRule.onAllNodesWithTag("chat_ai_preview_confirm").fetchSemanticsNodes().firstOrNull()?.let { runCatching { confirm() } }
        awaitTag("memo_reading_view")
        assertEquals("## 進捗\n- Chat v0 完了\nFolder対応完了", memoBody(target))
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_ai_write_success")
        composeRule.onNodeWithTag("chat_ai_write_success").assertTextContains("追記しました", substring = true)
        assertEquals("exactly one append", "## 進捗\n- Chat v0 完了\nFolder対応完了", memoBody(target))
        assertEquals(2, memoCount())
    }

    @Test
    fun anAppendToAJournalAndToAnOutlineGoThroughTheSameConfirmation() {
        val j = journal("散歩", today.minusDays(1))
        val o = memo("計画", "- 一", kind = "outline")
        openChatAi()
        script(proposal("APPEND", targetName = "散歩", text = "夕方も歩いた"))
        ask("散歩に追記して")
        awaitTag("chat_ai_preview_append")
        confirm()
        awaitTag("diary_body")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_ai_write_success")
        assertEquals("散歩\n夕方も歩いた", runBlocking { application.database.diaryDao().findById(j)!!.body })

        script(proposal("APPEND", targetName = "計画", text = "二"))
        ask("計画に二を追記して")
        awaitTag("chat_ai_preview_append")
        confirm()
        awaitTag("outliner_screen")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_ai_write_success")
        assertTrue(memoBody(o).contains("二"))
    }

    // --- RED 10, 13, 16: confirm USE_TEMPLATE once, opens the created memo ---

    @Test
    fun confirmingATemplateMakesOneMemoFromTheBodyAndOpensIt() {
        runBlocking { application.templateRepository.replaceAll(listOf(MemoTemplate("t1", "週次レビュー", "## 今週\n- \n## 来週\n- "))) }
        openChatAi()
        script(proposal("USE_TEMPLATE", templateId = "週次レビュー"))
        ask("週次レビューのテンプレでメモを作って")
        awaitTag("chat_ai_preview_template")
        confirm()
        composeRule.onAllNodesWithTag("chat_ai_preview_confirm").fetchSemanticsNodes().firstOrNull()?.let { runCatching { confirm() } }
        awaitTag("memo_reading_view")
        assertEquals(1, memoCount())
        val id = runBlocking { application.database.memoDao().allIds().single() }
        assertEquals("## 今週\n- \n## 来週\n- ", memoBody(id))
        assertEquals(1, runBlocking { application.templateRepository.current().size })
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_ai_write_success")
        assertEquals(1, memoCount())
    }

    // --- RED 18–20, 53: a document that moved after the preview is a conflict; nothing written, nothing retried ---

    @Test
    fun aDocumentEditedAfterThePreviewIsAConflictAndStaysAsItWas() {
        val target = memo("MemoRipple開発", "old")
        openChatAi()
        script(proposal("APPEND", targetName = "MemoRipple開発", text = "追記"))
        ask("MemoRipple開発に追記して")
        awaitTag("chat_ai_preview_append")
        // edited elsewhere (the editor, a restore, another device) after the preview was built
        runBlocking { application.database.memoDao().updateContent(target, "MemoRipple開発", "old + edited elsewhere", at(today, 12)) }
        val before = snapshot()
        confirm()
        awaitTag("chat_ai_write_conflict")
        composeRule.onNodeWithTag("chat_ai_write_conflict").assertTextContains("内容が変更されたため", substring = true)
        assertEquals(before, snapshot())
        assertEquals("old + edited elsewhere", memoBody(target))
        assertEquals(0, composeRule.onAllNodesWithTag("chat_ai_preview_confirm").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("memo_reading_view").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("chat_ai_dismiss").performClick()
        awaitGone("chat_ai_write_conflict")
        assertEquals(before, snapshot())
    }

    // --- RED 21, 22: LOCKED never reaches a preview; READ_ONLY at execution writes nothing ---

    @Test
    fun aJournalLockedAfterThePreviewIsReadOnlyAtExecutionAndUntouched() {
        val j = journal("その日", today.minusDays(2))
        openChatAi()
        script(proposal("APPEND", targetName = "その日", text = "追記"))
        ask("その日に追記して")
        awaitTag("chat_ai_preview_append")
        runBlocking { application.database.diaryDao().findById(j)!!.let { application.database.diaryDao().update(it.copy(state = DiaryState.LOCKED)) } }
        val before = snapshot()
        confirm()
        awaitTag("chat_ai_write_read_only")
        assertEquals(before, snapshot())
        assertEquals("その日", runBlocking { application.database.diaryDao().findById(j)!!.body })

        journal("確定", today.minusDays(10), state = DiaryState.LOCKED)
        script(proposal("APPEND", targetName = "確定", text = "追記"))
        ask("確定に追記して")
        awaitTag("chat_ai_invalid")
        assertEquals(0, composeRule.onAllNodesWithTag("chat_ai_preview_confirm").fetchSemanticsNodes().size)
    }

    // --- RED 24: a target removed after the preview is not found; nothing made ---

    @Test
    fun aTargetRemovedAfterThePreviewIsNotFoundAtExecution() {
        val target = memo("MemoRipple開発", "a")
        openChatAi()
        script(proposal("APPEND", targetName = "MemoRipple開発", text = "b"))
        ask("MemoRipple開発に追記して")
        awaitTag("chat_ai_preview_append")
        runBlocking { application.memoRepository.moveToTrash(target); application.database.memoDao().deletePermanently(target) }   // gone for good, as the trash does it
        confirm()
        awaitTag("chat_ai_write_not_found")
        assertEquals(0, memoCount())
    }

    // --- RED 26–29: reads and refusals never show a confirm ---

    @Test
    fun readsAndRefusalsNeverOfferAConfirm() {
        val id = memo("散歩のメモ", "本文")
        openChatAi()
        script(proposal("SEARCH", query = "散歩"))
        ask("散歩を探して")
        awaitTag("chat_result_memo_$id")
        assertEquals(0, composeRule.onAllNodesWithTag("chat_ai_preview_confirm").fetchSemanticsNodes().size)
        script(proposal("UNKNOWN"))
        ask("天気")
        awaitTag("chat_ai_unknown")
        assertEquals(0, composeRule.onAllNodesWithTag("chat_ai_preview_confirm").fetchSemanticsNodes().size)
        script(proposal("OPEN", targetName = "散歩のメモ"))
        ask("散歩のメモを開いて")
        awaitTag("memo_reading_view")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_input")
        assertEquals(1, memoCount())
    }

    // --- RED 37, 38: Back closes the preview (no write); then top-level Back → メモ ---

    @Test
    fun backClosesThePreviewWithoutWritingAndThenLeavesForMemos() {
        openChatAi()
        script(proposal("CREATE", documentKind = "MEMO", text = "x"))
        ask("新しいメモにxを作って")
        awaitTag("chat_ai_preview_create")
        Espresso.pressBack()
        awaitGone("chat_ai_preview_create")
        assertEquals(0, memoCount())
        Espresso.pressBack()
        awaitTag("nav_memos")
        composeRule.onNodeWithTag("nav_memos").assertIsSelected()
        assertEquals(0, memoCount())
    }

    // --- RED 39–41: the model may be gone while the user decides; the write runs without it ---

    @Test
    fun theModelIsUnloadedWhileWaitingAndTheWriteRunsWithoutReloadingItThenTheContextIsClean() {
        val target = memo("MemoRipple開発", "a")
        openChatAi()
        script(proposal("APPEND", targetName = "MemoRipple開発", text = "b"))
        ask("MemoRipple開発に追記して")
        awaitTag("chat_ai_preview_append")
        runBlocking { application.aiOrchestrator.release() }
        assertEquals(RuntimeState.UNLOADED, application.aiOrchestrator.runtimeState())
        val loads = TestAiRuntime.loads
        TestThermal.status = 3
        confirm()
        awaitTag("memo_reading_view")
        assertEquals("a\nb", memoBody(target))
        assertEquals(loads, TestAiRuntime.loads)
        assertEquals(RuntimeState.UNLOADED, application.aiOrchestrator.runtimeState())
        TestThermal.status = 0
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_ai_write_success")
        // the next ask starts with an empty context
        script(proposal("SEARCH", query = "b"))
        ask("bを探して")
        awaitTag("chat_result_memo_$target")
        assertTrue(TestAiRuntime.lastUserMessage().contains("(なし)"))
    }

    // --- RED 48: two journals on the same day stay two documents ---

    @Test
    fun twoJournalsOnTheSameDayAreTwoConfirmedCreates() {
        openChatAi()
        repeat(2) {
            script(proposal("CREATE", documentKind = "JOURNAL", dateToken = "TODAY", text = "散歩した"))   // a journal left blank is released, as always
            ask("今日の日記に散歩したと書いて")
            awaitTag("chat_ai_preview_create")
            confirm()
            awaitTag("diary_body")
            composeRule.onNodeWithContentDescription("戻る").performClick()
            awaitTag("chat_ai_write_success")
        }
        assertEquals(2, journalCount())
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitGone(tag: String) {
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }
}

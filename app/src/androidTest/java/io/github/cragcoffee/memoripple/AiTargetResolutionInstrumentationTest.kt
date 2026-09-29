package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Local LLM Phase 6 (docs/AI_TARGET_RESOLUTION.md): case C on the real database with the model
 * dropping `targetName` (the scripted runtime answers APPEND + text only, as Qwen did on the
 * S20). The deterministic assist yields the name, the real Resolver searches it: unique → the
 * append preview → one confirmed write; several → candidates; none → a safe stop; a document
 * that moved → conflict. Nothing is written before the confirmation.
 */
class AiTargetResolutionInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    private val today: LocalDate get() = application.timeProvider.currentLocalDate()

    @Before
    fun startEmptyAndScripted() {
        runBlocking {
            application.database.clearAllTables()
            application.settingsRepository.setAutoPlayOnLaunch(false)
            application.aiOrchestrator.release()
        }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0
        TestGuards.fastPathEnabled = false   // this class exercises the model pipeline; its plain sentences must reach the generator (docs/CHAT_FAST_PATH.md)
    }

    @After
    fun unload() {
        TestGuards.fastPathEnabled = true   // hand the product default back to the suite
        runBlocking { application.aiOrchestrator.release() }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0
    }

    private fun at(date: LocalDate, hour: Int): Long = application.timeProvider.toEpochMillis(LocalDateTime.of(date, java.time.LocalTime.of(hour, 0)))

    private fun memo(title: String, body: String, kind: String = "memo", createdAt: Long = at(today, 9)): Long = runBlocking {
        application.database.memoDao().insert(MemoEntity(title = title, body = body, createdAt = createdAt, updatedAt = createdAt, kind = kind))
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

    private fun openChatAi() {
        composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input")
        awaitTag("chat_input")
    }

    private fun ask(text: String) {
        composeRule.onNodeWithTag("chat_input").performTextClearance()
        composeRule.onNodeWithTag("chat_input").performTextInput(text)
        composeRule.onNodeWithTag("chat_send").performClick()
    }

    /** What Qwen answered on the S20 for case C: the intent and the text, no target, the gap admitted. */
    private val caseCAnswer = "{\"intent\":\"APPEND\",\"query\":null,\"targetRef\":null,\"targetName\":null,\"documentKind\":null,\"text\":\"Folder対応完了\",\"templateId\":null,\"dateToken\":null,\"missingFields\":[\"targetName\"]}"
    private val openAnswer = "{\"intent\":\"OPEN\",\"query\":null,\"targetRef\":null,\"targetName\":null,\"documentKind\":null,\"text\":null,\"templateId\":null,\"dateToken\":null,\"missingFields\":[\"targetName\"]}"
    private val caseC = "MemoRipple開発に『Folder対応完了』を追記して"

    @Test
    fun caseCWithNoModelTargetPreviewsTheUniqueDocumentAndOneConfirmationAppendsOnce() {
        val target = memo("MemoRipple開発", "## 進捗\n- Chat v0 完了")
        memo("別のメモ", "MemoRipple開発の話")
        journal("昨日の散歩", today.minusDays(1))
        val before = snapshot()
        openChatAi()
        TestAiRuntime.answers.add(caseCAnswer)
        ask(caseC)
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("MemoRipple開発", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("Folder対応完了", substring = true)
        assertEquals("nothing written by the preview", before, snapshot())
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        composeRule.onAllNodesWithTag("chat_ai_preview_confirm").fetchSemanticsNodes().firstOrNull()?.let { runCatching { composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick() } }
        awaitTag("memo_reading_view")
        assertEquals("## 進捗\n- Chat v0 完了\nFolder対応完了", memoBody(target))
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_ai_write_success")
        assertEquals("exactly once", "## 進捗\n- Chat v0 完了\nFolder対応完了", memoBody(target))
        assertEquals(2, memoCount())
    }

    @Test
    fun caseCWithTwoDocumentsOfThatTitleListsCandidatesAndOpensNothing() {
        memo("MemoRipple開発", "a"); memo("MemoRipple開発", "b", kind = "outline")
        val before = snapshot()
        openChatAi()
        TestAiRuntime.answers.add(caseCAnswer)
        ask(caseC)
        awaitTag("chat_ai_candidates")
        assertEquals(2, composeRule.onAllNodesWithTag("chat_ai_candidate", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("chat_ai_preview_append").fetchSemanticsNodes().size)
        assertEquals(before, snapshot())
    }

    @Test
    fun caseCWithNoSuchDocumentStopsSafelyAndCreatesNothing() {
        memo("別のメモ", "本文")
        val before = snapshot()
        openChatAi()
        TestAiRuntime.answers.add(caseCAnswer)
        ask(caseC)
        awaitTag("chat_ai_not_found")
        composeRule.onNodeWithTag("chat_ai_not_found").assertTextContains("見つかりません", substring = true)
        assertEquals(before, snapshot())
        assertEquals(1, memoCount())
    }

    @Test
    fun caseCWhoseTargetMovedAfterThePreviewIsAConflictAndStaysAsItWas() {
        val target = memo("MemoRipple開発", "old")
        openChatAi()
        TestAiRuntime.answers.add(caseCAnswer)
        ask(caseC)
        awaitTag("chat_ai_preview_append")
        runBlocking { application.database.memoDao().updateContent(target, "MemoRipple開発", "old + edited elsewhere", at(today, 12)) }
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        awaitTag("chat_ai_write_conflict")
        assertEquals("old + edited elsewhere", memoBody(target))
    }

    @Test
    fun anOpenWithNoModelTargetOpensTheUniqueDocumentAndAnUnusableNameStaysAQuestion() {
        memo("MemoRipple開発", "開発の本文")
        openChatAi()
        TestAiRuntime.answers.add(openAnswer)
        ask("MemoRipple開発を開いて")
        awaitTag("memo_reading_view")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_input")
        // Phase 8: the memo just opened is the conversation's anchor, so 「前のやつ」 names it by rule (no guess); without an anchor it stays a question (ConversationOrchestratorTest)
        TestAiRuntime.answers.add(openAnswer)
        ask("前のやつを開いて")
        awaitTag("memo_reading_view")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_input")
        TestAiRuntime.answers.add(caseCAnswer)
        ask("今日は疲れたので日記に追記して")
        awaitTag("chat_ai_needs_information")
        composeRule.onNodeWithTag("chat_ai_needs_information").assertTextContains("追記先が分かりません", substring = true)
        assertEquals(1, memoCount())
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}

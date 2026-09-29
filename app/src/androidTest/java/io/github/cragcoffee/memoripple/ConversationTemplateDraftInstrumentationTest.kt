package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.PickerDrill.pickTemplateThroughFolders
import io.github.cragcoffee.memoripple.PickerDrill.drillToTemplate
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.memos.TemplateFlow
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
 * 「この会話からテンプレートを作成」 journeys A–F (human brief 2026-09-22), no model, offline: two
 * question → answer pairs become a draft the one editor opens with two fields (A: back saves
 * nothing; B: 保存 → the picker offers and runs it); a conversation with no pairs gets one safe
 * message and no editor (C); result cards (D) and a Think result (E) are never questions; the
 * whole path needs no model (F, every journey).
 */
class ConversationTemplateDraftInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication
    private val today: LocalDate get() = application.timeProvider.currentLocalDate()
    private val review = "starter-daily-review"
    private val yesterdayJournal = "starter-yesterday-journal"
    private val todayTasks = "starter-think-today-tasks"

    @Before
    fun startEmptyNoModelOffline() {
        runBlocking {
            application.database.clearAllTables()
            application.templateRepository.replaceAll(emptyList())
            application.recentTemplateRepository.clear()
            application.settingsRepository.setAutoPlayOnLaunch(false)
            application.settingsRepository.setLastChatConversationId(null)
            application.settingsRepository.clearPinnedTemplates()
            application.aiOrchestrator.release()
        }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0; TestGuards.reset()
        TestAiSelection.descriptor = null
        TestGuards.online = false
    }

    @After
    fun tidy() {
        TestAiRuntime.loadGate?.complete(Unit); TestAiRuntime.generateGate?.complete(Unit)
        runBlocking {
            application.aiOrchestrator.release()
            application.settingsRepository.setLastChatConversationId(null)
            application.templateRepository.replaceAll(emptyList())
            application.recentTemplateRepository.clear()
        }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0; TestGuards.reset()
    }

    private fun at(date: LocalDate, hour: Int): Long = application.timeProvider.toEpochMillis(LocalDateTime.of(date, java.time.LocalTime.of(hour, 0)))
    private fun journal(body: String, date: LocalDate): Long = runBlocking {
        application.database.diaryDao().insert(DiaryEntryEntity(diaryDateEpochDay = date.toEpochDay(), body = body, state = DiaryState.DRAFT, createdAt = at(date, 8), updatedAt = at(date, 8)))
    }
    private fun templates() = runBlocking { application.templateRepository.current() }
    private fun conversations() = runBlocking { application.chatHistoryRepository.conversations().first() }
    private fun transcript(id: Long) = runBlocking { application.chatHistoryRepository.messages(id).first() }

    private fun openChat() { composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input") }
    private fun send(text: String) {
        composeRule.onNodeWithTag("chat_input").performTextClearance()
        composeRule.onNodeWithTag("chat_input").performTextInput(text)
        composeRule.onNodeWithTag("chat_send").performClick()
    }
    private fun openPicker() { composeRule.onNodeWithTag("chat_plus").performClick(); awaitTag("chat_template_picker") }
    private fun scrollTo(tag: String) { composeRule.onNodeWithTag("chat_template_list").performScrollToNode(hasTestTag(tag)) }
    private fun pickTemplate(id: String) { composeRule.pickTemplateThroughFolders(id, application) }
    private fun awaitTag(tag: String, timeout: Long = 15_000) { composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
    private fun awaitGone(tag: String, timeout: Long = 15_000) { composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() } }
    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun awaitQuestion(text: String) {
        try {
            composeRule.waitUntil(15_000) { conversations().singleOrNull()?.let { c -> transcript(c.id).lastOrNull { it.role == ChatRole.ASSISTANT }?.text?.contains(text) } == true }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("no question 「$text」; the transcript: ${conversations().singleOrNull()?.let { c -> transcript(c.id).map { "${it.role}:${it.text}" } }}", e)
        }
        val c = conversations().single(); val q = transcript(c.id).last { it.role == ChatRole.ASSISTANT }
        awaitTag("chat_message_${q.id}")
    }
    private fun draftFromOverflow() {
        composeRule.onNodeWithTag("chat_overflow").performClick()
        awaitTag("chat_template_from_conversation")
        composeRule.onNodeWithTag("chat_template_from_conversation").performClick()
    }
    /** The top-bar back walks one step back at a time; from the first step it leaves without saving. */
    private fun leaveEditor() {
        repeat(5) {
            if (composeRule.onAllNodesWithTag("chat_input", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) return
            composeRule.onNodeWithTag("template_editor_back").performClick()
            composeRule.waitForIdle()
        }
        awaitTag("chat_input")
    }
    private fun fieldCount(): Int {
        var n = 0
        while (runCatching { composeRule.onNodeWithTag("template_editor").performScrollToNode(hasTestTag("template_editor_field_remove_$n")) }.isSuccess) n++
        return n
    }
    private fun toFieldsStep() {
        awaitTag("template_editor")
        composeRule.onNodeWithTag("template_editor_next").performClick()
        awaitTag("template_editor_step_1")
        composeRule.onNodeWithTag("template_editor_next").performClick()
        awaitTag("template_editor_step_2")
    }

    /** 今日の振り返り: two questions answered, the third left open (the session cancelled by Back). */
    private fun twoPairs() {
        pickTemplate(review)
        awaitQuestion("今日の良かったことは？")
        send("開発が進んだ")
        awaitQuestion("うまくいかなかったことは？")
        send("UIが崩れた")
        awaitQuestion("明日やることは？")
        androidx.test.espresso.Espresso.pressBack()
        awaitGone("chat_answer_options")
    }

    // --- A: two pairs → the editor with two fields, the name and 整理する prefilled → back → nothing saved ---

    @Test
    fun twoPairsOpenTheEditorWithTwoFieldsAndBackSavesNothing() {
        openChat()
        twoPairs()
        draftFromOverflow()
        awaitTag("template_editor")
        composeRule.onNodeWithTag("template_editor_name").assertTextContains("今日の振り返り", substring = true)
        toFieldsStep()
        assertEquals("two fields", 2, fieldCount())
        composeRule.onNodeWithTag("template_editor_field_label_0").assertTextContains("今日の良かったこと", substring = true)
        composeRule.onNodeWithTag("template_editor_field_question_0").assertTextContains("今日の良かったことは？", substring = true)
        composeRule.onNodeWithTag("template_editor_field_label_1").assertTextContains("うまくいかなかったこと", substring = true)
        leaveEditor()
        assertEquals("nothing saved", 0, templates().size)
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- B: the same → 保存 → the picker offers it under 整理・壁打ち and runs it ---

    @Test
    fun theDraftSavedInTheEditorIsUsableFromThePicker() {
        openChat()
        twoPairs()
        draftFromOverflow()
        toFieldsStep()
        composeRule.onNodeWithTag("template_editor_next").performClick()
        awaitTag("template_editor_step_3")
        composeRule.onNodeWithTag("template_editor_body").assertTextContains("⟦今日の良かったこと⟧", substring = true)
        composeRule.onNodeWithTag("template_editor_next").performClick()
        awaitTag("template_editor_step_4")
        composeRule.onNodeWithTag("template_editor_save").performClick()
        awaitTag("chat_input")
        val saved = templates().single()
        assertEquals(TemplateFlow.THINK, saved.flow)
        assertEquals(listOf("今日の良かったことは？", "うまくいかなかったことは？"), saved.fields.map { it.question })
        assertTrue(saved.fields.all { it.default.isEmpty() })
        openPicker()
        composeRule.drillToTemplate(saved.id, application)
        composeRule.onNodeWithTag("chat_template_item_${saved.id}").performClick()
        awaitQuestion("今日の良かったことは？")
        composeRule.onNodeWithTag("chat_answer_cancel", useUnmergedTree = true).performClick()
        awaitGone("chat_answer_options")
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- C: no pairs → one safe message, no editor ---

    @Test
    fun aConversationWithNoPairsGetsOneMessageAndNoEditor() {
        openChat()
        send("散歩について何かあったっけ")   // no model, and the Fast Path declines it: a failure card, no question
        awaitTag("chat_ai_model_unavailable")
        draftFromOverflow()
        awaitTag("chat_template_draft_none")
        composeRule.onNodeWithTag("chat_template_draft_none").assertTextContains("テンプレートにできる質問が見つかりませんでした", substring = true)
        assertEquals(0, count("template_editor"))
        assertEquals(0, templates().size)
    }

    // --- D: result cards in the conversation are ignored; the pair after them is the draft ---

    @Test
    fun resultCardsAreNotQuestions() {
        journal("昨日のこと", today.minusDays(1))
        openChat()
        pickTemplate(yesterdayJournal)
        awaitTag("chat_ai_search_results")
        pickTemplate(review)
        awaitQuestion("今日の良かったことは？")
        send("進んだ")
        awaitQuestion("うまくいかなかったことは？")
        androidx.test.espresso.Espresso.pressBack()
        awaitGone("chat_answer_options")
        draftFromOverflow()
        toFieldsStep()
        assertEquals(1, fieldCount())
        composeRule.onNodeWithTag("template_editor_field_question_0").assertTextContains("今日の良かったことは？", substring = true)
        leaveEditor()
        assertEquals(0, templates().size)
    }

    // --- E: a finished Think conversation: its four questions are the draft; the result line is not ---

    @Test
    fun aThinkResultIsNotAQuestionButItsQuestionsAre() {
        openChat()
        pickTemplate(todayTasks)
        awaitQuestion("今日やる必要があることを、思いつくまま教えてください。")
        send("買い物")
        awaitQuestion("その中で、今日中に終わらせたいものはどれですか？")
        send("買い物")
        awaitQuestion("最初に取りかかるものは何ですか？")
        send("リスト")
        awaitQuestion("困りそうなことはありますか？")
        composeRule.onNodeWithTag("chat_answer_skip", useUnmergedTree = true).performClick()
        awaitTag("chat_think_result")
        composeRule.onNodeWithTag("chat_think_done").performClick()
        awaitGone("chat_think_result")
        draftFromOverflow()
        awaitTag("template_editor")
        composeRule.onNodeWithTag("template_editor_name").assertTextContains("今日やること整理", substring = true)
        toFieldsStep()
        assertEquals(4, fieldCount())
        composeRule.onNodeWithTag("template_editor").performScrollToNode(hasTestTag("template_editor_field_label_0"))
        composeRule.onNodeWithTag("template_editor_field_label_0").assertTextContains("今日やる必要があること", substring = true)
        composeRule.onNodeWithTag("template_editor").performScrollToNode(hasTestTag("template_editor_field_question_3"))
        composeRule.onNodeWithTag("template_editor_field_question_3").assertTextContains("困りそうなことはありますか？", substring = true)
        leaveEditor()
        assertEquals(0, templates().size)
        assertEquals(0, TestAiRuntime.loads)
    }
}

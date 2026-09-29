package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.PickerDrill.pickTemplateThroughFolders
import io.github.cragcoffee.memoripple.PickerDrill.drillToTemplate
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.TemplateField
import io.github.cragcoffee.memoripple.domain.memos.TemplateFieldType
import io.github.cragcoffee.memoripple.domain.memos.TemplateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Review Batch 2 journeys (human brief 2026-09-22), no model, offline: A 修正 on a Think result
 * changes one answer and renders again with nothing written; B 「＋ テンプレートを作成」 from the
 * picker makes a Think template in the one editor and the picker runs it; C a pin makes the
 * ピン留め section, an unpin removes it, and it survives a process restart; D / E 「この会話をメモとして
 * 保存」 previews, cancels with nothing changed, confirms exactly one memo; F a Think save after a
 * fix uses the new answer through the CREATE pipeline.
 */
class ReviewBatch2InstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication
    private val today: LocalDate get() = application.timeProvider.currentLocalDate()
    private val todayTasks = "starter-think-today-tasks"
    private val review = "starter-daily-review"

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
            application.settingsRepository.clearPinnedTemplates()
            application.templateRepository.replaceAll(emptyList())
            application.recentTemplateRepository.clear()
        }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0; TestGuards.reset()
    }

    private fun at(date: LocalDate, hour: Int): Long = application.timeProvider.toEpochMillis(LocalDateTime.of(date, java.time.LocalTime.of(hour, 0)))
    private fun memoIds() = runBlocking { application.database.memoDao().allIds().toSet() }
    private fun memoBody(id: Long) = runBlocking { application.database.memoDao().findById(id)!!.body }
    private fun snapshot(): String = runBlocking {
        val dao = application.database.memoDao()
        val memos = dao.allIds().sorted().map { id -> dao.findById(id)!!.let { "memo|${it.id}|${it.title}|${it.body}|${it.updatedAt}" } }
        val js = application.database.diaryDao().observeAll().first().sortedBy { it.id }.map { "journal|${it.id}|${it.body}|${it.updatedAt}" }
        (memos + js).joinToString("\n")
    }
    private fun conversations() = runBlocking { application.chatHistoryRepository.conversations().first() }
    private fun transcript(id: Long) = runBlocking { application.chatHistoryRepository.messages(id).first() }
    private fun pins() = runBlocking { application.settingsRepository.pinnedTemplateIds.first() }

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
    private fun lines() = transcript(conversations().single().id).map { "${it.role.name}:${it.text}" }

    /** 今日やること整理 answered to its result. */
    private fun thinkToResult() {
        pickTemplate(todayTasks)
        awaitQuestion("今日やる必要があることを、思いつくまま教えてください。")
        send("買い物")
        awaitQuestion("その中で、今日中に終わらせたいものはどれですか？")
        send("買い物")
        awaitQuestion("最初に取りかかるものは何ですか？")
        send("リストを書く")
        awaitQuestion("困りそうなことはありますか？")
        composeRule.onNodeWithTag("chat_answer_skip", useUnmergedTree = true).performClick()
        awaitTag("chat_think_result")
    }

    // --- A: 修正 → one field → a new answer → the result again → 終了 → zero writes ---

    @Test
    fun aFixAsksOneQuestionAgainAndRendersTheResultAgainWithNothingWritten() {
        openChat()
        val before = snapshot()
        thinkToResult()
        composeRule.onNodeWithTag("chat_think_edit").performClick()
        awaitTag("chat_edit_list")
        composeRule.onNodeWithText("修正する項目を選んでください").assertIsDisplayed()
        // labels, never keys
        listOf("やること", "今日中に終わらせたいこと", "最初にやること", "気になること").forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        assertEquals(0, count("chat_edit_field_field_1"))
        composeRule.onNodeWithTag("chat_edit_field_first_step").assertTextContains("リストを書く", substring = true)
        composeRule.onNodeWithTag("chat_edit_field_first_step").performClick()
        awaitQuestion("最初に取りかかるものは何ですか？")
        assertEquals("nothing written while fixing", before, snapshot())
        send("財布を出す")
        awaitTag("chat_think_result")
        val results = lines().filter { it.startsWith("ASSISTANT:整理すると") }
        assertEquals("the old result stays, the new one follows", 2, results.size)
        assertTrue(results.last(), results.last().contains("## 最初にやること\n財布を出す"))
        assertFalse(results.last(), results.last().contains("リストを書く"))
        assertTrue("the other answers are kept", results.last().contains("## やること\n買い物"))
        composeRule.onNodeWithTag("chat_think_done").performClick()
        awaitGone("chat_think_result")
        assertEquals(before, snapshot())
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- F: a Think save after a fix goes through the CREATE pipeline with the new answer ---

    @Test
    fun aSaveAfterAFixUsesTheNewAnswerThroughThePreviewAndTheOneConfirm() {
        openChat()
        val before = memoIds()
        thinkToResult()
        composeRule.onNodeWithTag("chat_think_edit").performClick()
        awaitTag("chat_edit_list")
        composeRule.onNodeWithTag("chat_edit_field_tasks").performClick()
        awaitQuestion("今日やる必要があることを、思いつくまま教えてください。")
        send("買い物\n掃除")
        awaitTag("chat_think_result")
        composeRule.onNodeWithTag("chat_think_save").performClick()
        awaitTag("chat_ai_preview_template")
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("掃除", substring = true)
        assertEquals(before, memoIds())
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        composeRule.onAllNodesWithTag("chat_ai_preview_confirm").fetchSemanticsNodes().firstOrNull()?.let { runCatching { composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick() } }
        awaitTag("memo_reading_view")
        val made = memoIds() - before
        assertEquals(1, made.size)
        assertTrue(memoBody(made.single()).contains("## やること\n買い物\n掃除"))
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- B: ＋ → テンプレートを作成 → a minimal Think template in the one editor → back to the chat → the picker runs it ---

    @Test
    fun theQuickCreateMakesAThinkTemplateInTheOneEditorAndThePickerRunsIt() {
        openChat()
        openPicker()
        scrollTo("chat_template_create")
        composeRule.onNodeWithTag("chat_template_create", useUnmergedTree = true).performClick()
        awaitTag("template_editor")
        composeRule.onNodeWithTag("template_editor_name").performTextInput("読書の振り返り")
        composeRule.onNodeWithTag("template_editor_next").performClick()
        awaitTag("template_editor_step_1")
        composeRule.onNodeWithTag("template_editor_action_THINK").performClick()
        composeRule.onNodeWithTag("template_editor_next").performClick()
        awaitTag("template_editor_step_2")
        composeRule.onNodeWithTag("template_editor_add_field").performClick()
        composeRule.onNodeWithTag("template_editor_field_label_0").performTextInput("本")
        composeRule.onNodeWithTag("template_editor_field_question_0").performTextInput("何を読んだ？")
        composeRule.onNodeWithTag("template_editor_next").performClick()
        awaitTag("template_editor_step_3")
        composeRule.onNodeWithTag("template_editor_insert_field").performClick()
        composeRule.onNodeWithTag("template_editor_insert_0").performClick()
        composeRule.onNodeWithTag("template_editor_next").performClick()
        awaitTag("template_editor_step_4")
        composeRule.onNodeWithTag("template_editor_save").performClick()
        // back on the chat, the new template is in 整理・壁打ち and runs
        awaitTag("chat_input")
        val saved = runBlocking { application.templateRepository.current().single() }
        assertEquals(TemplateFlow.THINK, saved.flow)
        openPicker()
        composeRule.drillToTemplate(saved.id, application)
        composeRule.onNodeWithTag("chat_template_item_${saved.id}").performClick()
        awaitQuestion("何を読んだ？")
        composeRule.onNodeWithTag("chat_answer_cancel", useUnmergedTree = true).performClick()
        awaitGone("chat_answer_options")
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- C: a long press pins → the ピン留め section, not shown twice; unpin → the section goes; pins survive a restart ---

    @Test
    fun aPinMakesTheSectionAnUnpinRemovesItAndPinsSurviveARestart() {
        val custom = MemoTemplate("u-custom", "朝のことば", "おはよう")
        runBlocking { application.templateRepository.save(custom) }
        openChat()
        openPicker()
        assertEquals("no pinned section yet", 0, count("chat_template_section_pinned"))
        composeRule.drillToTemplate(review, application)
        composeRule.onNodeWithTag("chat_template_item_$review").performTouchInput { longClick() }
        awaitTag("chat_template_pin_$review")
        composeRule.onNodeWithTag("chat_template_pin_$review").performClick()
        composeRule.waitUntil(5_000) { pins().toSet() == setOf(review) }
        composeRule.onNodeWithTag("chat_template_folder_back").performClick()
        awaitTag("chat_template_section_pinned")
        scrollTo("chat_template_section_pinned")
        composeRule.onNodeWithTag("chat_template_pinned_$review", useUnmergedTree = true).assertIsDisplayed()
        // a custom template pins too
        composeRule.drillToTemplate("u-custom", application)
        composeRule.onNodeWithTag("chat_template_item_u-custom").performTouchInput { longClick() }
        awaitTag("chat_template_pin_u-custom")
        composeRule.onNodeWithTag("chat_template_pin_u-custom").performClick()
        composeRule.waitUntil(5_000) { pins().toSet() == setOf(review, "u-custom") }
        // a restart keeps them
        composeRule.onNodeWithTag("chat_template_picker").performTouchInput { swipeDownToDismiss() }
        composeRule.activityRule.scenario.onActivity { it.viewModelStore.clear() }
        composeRule.activityRule.scenario.recreate()
        awaitTag("chat_input")
        openPicker()
        awaitTag("chat_template_section_pinned")
        composeRule.onNodeWithTag("chat_template_pinned_u-custom", useUnmergedTree = true).assertIsDisplayed()
        // unpin both → the section disappears
        composeRule.onNodeWithTag("chat_template_pinned_$review").performTouchInput { longClick() }
        awaitTag("chat_template_unpin_$review")
        composeRule.onNodeWithTag("chat_template_unpin_$review").performClick()
        composeRule.waitUntil(5_000) { pins().toSet() == setOf("u-custom") }
        composeRule.onNodeWithTag("chat_template_pinned_u-custom").performTouchInput { longClick() }
        awaitTag("chat_template_unpin_u-custom")
        composeRule.onNodeWithTag("chat_template_unpin_u-custom").performClick()
        composeRule.waitUntil(5_000) { pins().isEmpty() }
        awaitGone("chat_template_section_pinned")
        composeRule.drillToTemplate(review, application)
        composeRule.onNodeWithTag("chat_template_item_$review", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("chat_template_folder_back").performClick()
        awaitTag("chat_template_folder_record")
        // an orphan pin is ignored
        runBlocking { application.settingsRepository.setTemplatePinned("gone-template", true) }
        composeRule.waitUntil(5_000) { pins().toSet() == setOf("gone-template") }
        assertEquals(0, count("chat_template_section_pinned"))
    }

    private fun androidx.compose.ui.test.TouchInjectionScope.swipeDownToDismiss() { swipe(start = androidx.compose.ui.geometry.Offset(width / 2f, 20f), end = androidx.compose.ui.geometry.Offset(width / 2f, height - 20f), durationMillis = 200) }

    // --- D / E: 「この会話をメモとして保存」 → the preview → cancel → nothing; again → confirm → exactly one memo ---

    @Test
    fun theConversationIsPreviewedThenCancelledThenSavedAsExactlyOneMemo() {
        openChat()
        pickTemplate(review)
        awaitQuestion("今日の良かったことは？")
        composeRule.onNodeWithTag("chat_answer_cancel", useUnmergedTree = true).performClick()
        awaitGone("chat_answer_options")
        val before = snapshot()
        val c = conversations().single()
        composeRule.onNodeWithTag("chat_overflow").performClick()
        composeRule.onNodeWithTag("chat_save_conversation").performClick()
        awaitTag("chat_ai_preview_create")
        composeRule.onNodeWithTag("chat_ai_preview_create").assertTextContains("# ${c.title}", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_create").assertTextContains("## あなた", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_create").assertTextContains("## MemoRipple", substring = true)
        assertEquals("the preview wrote nothing", before, snapshot())
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        awaitGone("chat_ai_preview_create")
        assertEquals(before, snapshot())
        val ids = memoIds()
        composeRule.onNodeWithTag("chat_overflow").performClick()
        composeRule.onNodeWithTag("chat_save_conversation").performClick()
        awaitTag("chat_ai_preview_create")
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        composeRule.onAllNodesWithTag("chat_ai_preview_confirm").fetchSemanticsNodes().firstOrNull()?.let { runCatching { composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick() } }
        awaitTag("memo_reading_view")
        val made = memoIds() - ids
        assertEquals("exactly one memo, one confirmation", 1, made.size)
        val body = memoBody(made.single())
        assertTrue(body, body.startsWith("# ${c.title}\n"))
        assertTrue(body, body.contains("## あなた\n今日の振り返り\n"))
        assertTrue(body, body.contains("## MemoRipple\n今日の振り返りを始めます。"))
        listOf("result_", "{{", "USER:", "ASSISTANT:", "ms/", "PendingWrite").forEach { assertFalse("the memo carries $it", body.contains(it)) }
        assertEquals("the conversation itself is unchanged", c.title, conversations().first { it.id == c.id }.title)
        assertEquals(0, TestAiRuntime.loads)
    }

    @Test
    fun anEmptyConversationHasNothingToSave() {
        openChat()
        composeRule.onNodeWithTag("chat_overflow").performClick()
        assertEquals("no conversation: the entry is absent or disabled", 0, composeRule.onAllNodesWithTag("chat_save_conversation").fetchSemanticsNodes().count { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == null })
    }
}

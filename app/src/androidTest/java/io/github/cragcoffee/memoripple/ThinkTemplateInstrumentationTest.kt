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
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessageKind
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
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
 * Think templates, journeys A–F (docs/THINK_TEMPLATES.md, human brief 2026-09-22) with no model
 * and no network: a Think starter asks one question at a time and ends in a deterministic result
 * inside the conversation with 「メモとして保存」 / 「終了」; nothing is written until the user saves,
 * and a save is the ordinary preview and the one confirmation; a process death drops the session
 * and keeps the transcript; the record and search starters are as they were; a custom Think
 * template made by the user runs the same way.
 */
class ThinkTemplateInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication
    private val today: LocalDate get() = application.timeProvider.currentLocalDate()

    private val todayTasks = "starter-think-today-tasks"
    private val idea = "starter-think-idea"
    private val plan = "starter-think-plan"
    private val project = "starter-think-project"
    private val review = "starter-daily-review"
    private val yesterdayJournal = "starter-yesterday-journal"

    @Before
    fun startEmptyNoModelOffline() {
        runBlocking {
            application.database.clearAllTables()
            application.templateRepository.replaceAll(emptyList())
            application.recentTemplateRepository.clear()
            application.settingsRepository.setAutoPlayOnLaunch(false)
            application.settingsRepository.setLastChatConversationId(null)
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
    private fun recents() = runBlocking { application.recentTemplateRepository.recent.first().map { it.templateId } }

    private fun openChat() { composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input") }
    private fun send(text: String) {
        composeRule.onNodeWithTag("chat_input").performTextClearance()
        composeRule.onNodeWithTag("chat_input").performTextInput(text)
        composeRule.onNodeWithTag("chat_send").performClick()
    }
    private fun openPicker() { composeRule.onNodeWithTag("chat_plus").performClick(); awaitTag("chat_template_picker") }
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

    // --- A: no model → ＋ → 今日やること整理 → one question at a time → the result → 終了 → zero writes ---

    @Test
    fun aThinkStarterAsksThenShowsItsResultAndEndsWithNothingWritten() {
        openChat()
        val before = snapshot()
        openPicker()
        listOf("chat_template_folder_record", "chat_template_folder_think", "chat_template_folder_search").forEach { composeRule.onNodeWithTag("chat_template_list").performScrollToNode(hasTestTag(it)); composeRule.onNodeWithTag(it, useUnmergedTree = true).assertIsDisplayed() }
        composeRule.drillToTemplate(todayTasks, application)
        composeRule.onNodeWithTag("chat_template_item_$todayTasks").performClick()
        awaitQuestion("今日やる必要があることを、思いつくまま教えてください。")
        assertEquals("やめる on the first question", 1, count("chat_answer_cancel"))
        assertEquals("no form", 0, count("chat_template_form"))
        send("MemoRipple開発\n買い物")
        awaitQuestion("その中で、今日中に終わらせたいものはどれですか？")
        assertEquals("やめる only on the first", 0, count("chat_answer_cancel"))
        assertEquals("one question at a time: no result yet", 0, count("chat_think_result"))
        send("MemoRipple開発")
        awaitQuestion("最初に取りかかるものは何ですか？")
        send("Chat UI確認")
        awaitQuestion("困りそうなことはありますか？")
        assertEquals("nothing written while asking", before, snapshot())
        composeRule.onNodeWithTag("chat_answer_skip", useUnmergedTree = true).performClick()
        awaitTag("chat_think_result")
        assertEquals("no preview: a result", 0, count("chat_ai_preview_template"))
        val result = lines().last()
        assertTrue(result, result.startsWith("ASSISTANT:整理すると、こんな内容です。"))
        assertTrue(result, result.contains("今日やること - $today") && result.contains("MemoRipple開発") && result.contains("買い物") && result.contains("Chat UI確認") && result.contains("（なし）"))
        assertFalse("nothing internal in the transcript", result.contains("{{") || result.contains("tasks") || result.contains("first_step"))
        assertEquals("the result is a result line, not a write event", ChatMessageKind.RESULT, transcript(conversations().single().id).last().kind)
        composeRule.onNodeWithTag("chat_think_done").performClick()
        awaitGone("chat_think_result")
        assertEquals("zero writes", before, snapshot())
        assertEquals(0, TestAiRuntime.loads)
        assertEquals(listOf(todayTasks), recents())
    }

    // --- B: アイデア壁打ち → the result → メモとして保存 → the preview → cancel → nothing changed ---

    @Test
    fun aSaveOpensThePreviewAndACancelWritesNothing() {
        openChat()
        val before = snapshot()
        pickTemplate(idea)
        awaitQuestion("どんなアイデアですか？")
        send("音声メモ")
        awaitQuestion("何を解決したいですか？")
        send("手が離せないときの記録")
        awaitQuestion("誰が使うものですか？")
        send("自分")
        awaitQuestion("面白いと思う点はどこですか？")
        send("歩きながら考えられる")
        awaitQuestion("気になっている問題はありますか？")
        composeRule.onNodeWithTag("chat_answer_skip", useUnmergedTree = true).performClick()
        awaitQuestion("次に試すなら何をしますか？")
        send("録音ボタンを置く")
        awaitTag("chat_think_result")
        composeRule.onNodeWithTag("chat_think_save").performClick()
        awaitTag("chat_ai_preview_template")
        // the card reads by field (2026-09-24); the saved body keeps its own shape
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("アイデア", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("歩きながら考えられる", substring = true)
        assertEquals("the preview wrote nothing", before, snapshot())
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        awaitGone("chat_ai_preview_template")
        assertEquals(before, snapshot())
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- C: 企画整理 → the result → save → confirm → exactly one memo; a second tap is no second write ---

    @Test
    fun aConfirmedSaveMakesExactlyOneMemo() {
        openChat()
        val before = memoIds()
        pickTemplate(plan)
        awaitQuestion("何を企画していますか？")
        send("読書会")
        awaitQuestion("その目的は何ですか？")
        send("本の話をする場")
        awaitQuestion("誰に使ってほしいですか？")
        send("友人")
        awaitQuestion("一番大事な価値は何ですか？")
        send("続けやすさ")
        awaitQuestion("似たものとの違いは何ですか？")
        send("月一回、短く")
        awaitQuestion("実現するうえでの課題は何ですか？")
        send("場所")
        awaitQuestion("次に決めることは何ですか？")
        send("日程")
        awaitTag("chat_think_result")
        assertEquals("the result wrote nothing", before, memoIds())
        composeRule.onNodeWithTag("chat_think_save").performClick()
        awaitTag("chat_ai_preview_template")
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        composeRule.onAllNodesWithTag("chat_ai_preview_confirm").fetchSemanticsNodes().firstOrNull()?.let { runCatching { composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick() } }
        awaitTag("memo_reading_view")
        val made = memoIds() - before
        assertEquals("exactly one memo, one confirmation", 1, made.size)
        val body = memoBody(made.single())
        assertTrue(body, body.startsWith("企画 - 読書会"))
        assertTrue(body, body.contains("## 課題\n場所"))
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- D: a process death midway keeps the transcript, drops the session, writes nothing ---

    @Test
    fun aProcessDeathMidwayKeepsTheTranscriptDropsTheSessionAndWritesNothing() {
        openChat()
        val before = snapshot()
        pickTemplate(project)
        awaitQuestion("どのプロジェクトについて整理しますか？")
        send("MemoRipple")
        awaitQuestion("今どこまで進んでいますか？")
        val c = conversations().single()
        val n = transcript(c.id).size
        composeRule.activityRule.scenario.onActivity { it.viewModelStore.clear() }
        composeRule.activityRule.scenario.recreate()
        awaitTag("chat_input")
        assertEquals("the transcript stays", n, transcript(c.id).size)
        awaitTag("chat_message_${transcript(c.id).last().id}")
        assertEquals("the session is gone", 0, count("chat_answer_options"))
        assertEquals(0, count("chat_think_result"))
        send("ここまで")   // free text with no model, not an answer
        awaitTag("chat_ai_model_unavailable")
        assertEquals(before, snapshot())
        assertEquals(0, count("chat_ai_preview_template"))
    }

    // --- E / F: the record starter and the search starter are as they were ---

    @Test
    fun theRecordAndSearchStartersAreUnchanged() {
        val y = journal("昨日のこと", today.minusDays(1)); journal("今日のこと", today)
        openChat()
        val before = memoIds()
        pickTemplate(review)
        awaitQuestion("今日の良かったことは？")
        send("進んだ")
        awaitQuestion("うまくいかなかったことは？")
        composeRule.onNodeWithTag("chat_answer_skip", useUnmergedTree = true).performClick()
        awaitQuestion("明日やることは？")
        send("続き")
        awaitTag("chat_ai_preview_template")
        assertEquals("a record starter goes to its preview, never a Think result", 0, count("chat_think_result"))
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        awaitGone("chat_ai_preview_template")
        assertEquals(before, memoIds())
        openPicker()
        composeRule.drillToTemplate(yesterdayJournal, application)
        composeRule.onNodeWithTag("chat_template_item_$yesterdayJournal").performClick()
        awaitTag("chat_ai_search_results")
        composeRule.onNodeWithTag("chat_result_journal_$y").assertIsDisplayed()
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- a custom Think template (the editor's 整理する) runs the same way ---

    @Test
    fun aCustomThinkTemplateRunsLikeAStarter() {
        val reading = MemoTemplate(
            id = "u-reading", name = "読書の振り返り", body = "読書 - {{book}}\n\n## 印象に残ったこと\n{{impression}}\n\n## 次に調べたいこと\n{{next}}\n",
            flow = TemplateFlow.THINK,
            fields = listOf(
                TemplateField("book", "本", TemplateFieldType.TEXT, required = true, question = "何を読んだ？"),
                TemplateField("impression", "印象に残ったこと", TemplateFieldType.MULTILINE, question = "印象に残ったことは？"),
                TemplateField("next", "次に調べたいこと", TemplateFieldType.TEXT, question = "次に調べたいことは？"),
            ),
        )
        runBlocking { application.templateRepository.save(reading) }
        openChat()
        openPicker()
        composeRule.drillToTemplate("u-reading", application)
        composeRule.onNodeWithTag("chat_template_item_u-reading").performClick()
        awaitQuestion("何を読んだ？")
        send("羅生門")
        awaitQuestion("印象に残ったことは？")
        send("門の下")
        awaitQuestion("次に調べたいことは？")
        send("作者の年譜")
        awaitTag("chat_think_result")
        val result = lines().last()
        assertTrue(result, result.contains("読書 - 羅生門") && result.contains("作者の年譜"))
        composeRule.onNodeWithTag("chat_think_done").performClick()
        awaitGone("chat_think_result")
        assertEquals(listOf("u-reading"), recents())
    }
}

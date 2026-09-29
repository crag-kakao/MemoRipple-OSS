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
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.TemplateField
import io.github.cragcoffee.memoripple.domain.memos.TemplateFieldType
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
 * Template = conversation script, journeys A–G (docs/CHAT_UI_TEMPLATE_V2.md §16) with no model
 * and no network: a starter asks one question at a time inside the conversation and the user
 * answers through the message input or the chips; the answers become the preview and one
 * confirmed document; a SEARCH starter runs at once; an APPEND starter asks its target first and
 * shows the candidates; a process death drops the session but keeps the transcript and writes
 * nothing; DATE / BOOLEAN / CHOICE answer as chips; with a model the AI free text is unchanged.
 */
class ConversationTemplateFlowInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication
    private val today: LocalDate get() = application.timeProvider.currentLocalDate()

    private val review = "starter-daily-review"
    private val meeting = "starter-meeting-memo"
    private val yesterdayJournal = "starter-yesterday-journal"
    private val projectLog = "starter-project-log"

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
    private fun memo(title: String, body: String): Long = runBlocking {
        val t = at(today, 9); application.database.memoDao().insert(MemoEntity(title = title, body = body, createdAt = t, updatedAt = t, kind = "memo"))
    }
    private fun journal(body: String, date: LocalDate): Long = runBlocking {
        application.database.diaryDao().insert(DiaryEntryEntity(diaryDateEpochDay = date.toEpochDay(), body = body, state = DiaryState.DRAFT, createdAt = at(date, 8), updatedAt = at(date, 8)))
    }
    private fun memoIds() = runBlocking { application.database.memoDao().allIds().toSet() }
    private fun memoBody(id: Long) = runBlocking { application.database.memoDao().findById(id)!!.body }
    private fun journals() = runBlocking { application.database.diaryDao().observeAll().first() }
    private fun snapshot(): String = runBlocking {
        val dao = application.database.memoDao()
        val memos = dao.allIds().sorted().map { id -> dao.findById(id)!!.let { "memo|${it.id}|${it.title}|${it.body}|${it.updatedAt}" } }
        val js = application.database.diaryDao().observeAll().first().sortedBy { it.id }.map { "journal|${it.id}|${it.body}|${it.updatedAt}" }
        (memos + js).joinToString("\n")
    }
    private fun conversations() = runBlocking { application.chatHistoryRepository.conversations().first() }
    private fun transcript(id: Long) = runBlocking { application.chatHistoryRepository.messages(id).first() }

    private fun proposal(intent: String, query: String? = null): String {
        fun s(v: String?) = if (v == null) "null" else "\"" + v.replace("\"", "\\\"") + "\""
        return "{\"intent\":${s(intent)},\"query\":${s(query)},\"targetRef\":null,\"targetName\":null,\"documentKind\":null,\"text\":null,\"templateId\":null,\"dateToken\":null,\"missingFields\":[]}"
    }
    private fun openChat() { composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input") }
    private fun send(text: String) {
        composeRule.onNodeWithTag("chat_input").performTextClearance()
        composeRule.onNodeWithTag("chat_input").performTextInput(text)
        composeRule.onNodeWithTag("chat_send").performClick()
    }
    private fun pickTemplate(id: String) { composeRule.pickTemplateThroughFolders(id, application) }
    private fun awaitTag(tag: String, timeout: Long = 15_000) { composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
    private fun awaitGone(tag: String, timeout: Long = 15_000) { composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() } }
    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    /** The newest assistant line of the one conversation, once it says [text]. */
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

    // --- A: no model → ＋ → 今日の振り返り → one question at a time → the preview → cancel → nothing changed ---

    @Test
    fun aStarterAsksOneQuestionAtATimeAndACancelledPreviewWritesNothing() {
        openChat()
        val before = snapshot()
        pickTemplate(review)
        awaitQuestion("今日の良かったことは？")
        assertEquals("no form, ever", 0, count("chat_template_form"))
        assertEquals("a text question waits for the input", 0, count("chat_answer_option_TODAY"))
        assertEquals("やめる on the first question", 1, count("chat_answer_cancel"))
        send("MemoRippleのChatが進んだ")
        awaitQuestion("うまくいかなかったことは？")
        assertEquals("no やめる from the second question on", 0, count("chat_answer_cancel"))
        assertEquals("スキップ stays on an optional question", 1, count("chat_answer_skip"))
        send("UIがまだ分かりづらかった")
        awaitQuestion("明日やることは？")
        send("Chat UIを修正する")
        awaitTag("chat_ai_preview_template")
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("MemoRippleのChatが進んだ", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("Chat UIを修正する", substring = true)
        assertEquals(before, snapshot())
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        awaitGone("chat_ai_preview_template")
        assertEquals(before, snapshot())
        val t = lines()
        assertEquals("USER:今日の振り返り", t.first())
        assertTrue(t.toString(), t.count { it.startsWith("USER:") } == 4 && t.any { it == "USER:UIがまだ分かりづらかった" })
        assertTrue("the questions are assistant lines", t.any { it.startsWith("ASSISTANT:") && it.contains("うまくいかなかったことは？") })
        assertTrue("the cancel is a line", t.last().contains("キャンセル"))
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- B: no model → 会議メモ → answers, a skip, the preview → confirm → exactly one memo ---

    @Test
    fun aStarterCreateThroughQuestionsConfirmsExactlyOneMemo() {
        openChat()
        val before = memoIds()
        pickTemplate(meeting)
        awaitQuestion("会議名は？")
        send("定例会")
        awaitQuestion("参加者は？")
        composeRule.onNodeWithTag("chat_answer_skip", useUnmergedTree = true).performClick()
        awaitQuestion("何について話しましたか？")
        send("リリース計画")
        awaitQuestion("何が決まりましたか？")
        send("9月末に出す")
        awaitQuestion("次にやることは？")
        send("テストを書く")
        awaitTag("chat_ai_preview_template")
        // the card reads by field (2026-09-24); the Markdown it will write is unchanged and checked below
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("会議名", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("9月末に出す", substring = true)
        assertEquals(before, memoIds())
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        composeRule.onAllNodesWithTag("chat_ai_preview_confirm").fetchSemanticsNodes().firstOrNull()?.let { runCatching { composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick() } }
        awaitTag("memo_reading_view")
        val made = memoIds() - before
        assertEquals("exactly one memo, one confirmation", 1, made.size)
        assertTrue(memoBody(made.single()).startsWith("# 定例会"))
        assertEquals(0, TestAiRuntime.loads)
        val t = lines()
        assertTrue(t.toString(), t.any { it == "USER:スキップ" })
    }

    // --- C: no model → 昨日の日記を探す → at once ---

    @Test
    fun aSearchStarterWithNoQuestionRunsAtOnce() {
        val y = journal("昨日のこと", today.minusDays(1)); journal("今日のこと", today)
        openChat()
        pickTemplate(yesterdayJournal)
        awaitTag("chat_ai_search_results")
        composeRule.onNodeWithTag("chat_result_journal_$y").assertIsDisplayed()
        assertEquals(0, count("chat_answer_options"))
        val t = lines()
        assertTrue(t.toString(), t.any { it.startsWith("ASSISTANT:") && it.contains("探します") })
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- D: no model → プロジェクトログ → the target question with candidates → the questions → the append preview → cancel ---

    @Test
    fun anAppendStarterAsksItsTargetShowsTheCandidatesThenItsQuestionsAndACancelWritesNothing() {
        val dev = memo("MemoRipple開発", "## 進捗"); memo("MemoRipple広報", "x")
        openChat()
        val before = snapshot()
        pickTemplate(projectLog)
        awaitQuestion("どのプロジェクトですか？")
        send("MemoRipple")
        awaitTag("chat_target_candidate_memo_$dev")
        composeRule.onNodeWithTag("chat_target_candidate_memo_$dev").performClick()
        awaitQuestion("今日やったことは？")
        send("フォルダ対応")
        awaitQuestion("困っていることはありますか？")
        composeRule.onNodeWithTag("chat_answer_skip", useUnmergedTree = true).performClick()
        awaitQuestion("次にやることは？")
        send("テスト")
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("MemoRipple開発", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("フォルダ対応", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        awaitGone("chat_ai_preview_append")
        assertEquals(before, snapshot())
        assertEquals("## 進捗", memoBody(dev))
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- E: a model → free text → the ordinary AI flow ---

    @Test
    fun withAModelFreeTextGoesThroughTheAiAsBefore() {
        TestAiSelection.descriptor = TestAiSelection.default; TestGuards.online = true
        memo("散歩のメモ", "本文")
        openChat()
        TestAiRuntime.answers.add(proposal("SEARCH", query = "散歩"))
        // a conversational sentence: the Fast Path declines it, so the AI route is what runs (docs/CHAT_FAST_PATH.md)
        send("散歩について何かあったっけ")
        awaitTag("chat_ai_search_results")
        assertEquals(1, TestAiRuntime.loads)
        awaitTag("chat_ai_timing")
    }

    // --- F: a process death mid-flow → the transcript stays, the session is gone, nothing is written ---

    @Test
    fun aProcessDeathDuringTheQuestionsKeepsTheTranscriptDropsTheSessionAndWritesNothing() {
        openChat()
        val before = snapshot()
        pickTemplate(review)
        awaitQuestion("今日の良かったことは？")
        send("進んだ")
        awaitQuestion("うまくいかなかったことは？")
        val c = conversations().single()
        val n = transcript(c.id).size
        // a process death: the view model goes with the process — cleared here, then the activity recreated (the Phase 8 fixture)
        composeRule.activityRule.scenario.onActivity { it.viewModelStore.clear() }
        composeRule.activityRule.scenario.recreate()
        awaitTag("chat_input")
        assertEquals("the transcript stays", n, transcript(c.id).size)
        awaitTag("chat_message_${transcript(c.id).last().id}")
        assertEquals("no options: the session is gone", 0, count("chat_answer_options"))
        send("直す")   // not an answer any more: free text with no model
        awaitTag("chat_ai_model_unavailable")
        assertEquals(before, snapshot())
        assertEquals(0, count("chat_ai_preview_template"))
    }

    // --- G: DATE / BOOLEAN / CHOICE answer as chips; the editing of one answer before the confirmation ---

    @Test
    fun dateBooleanAndChoiceAnswerAsChipsAndOneAnswerCanBeChangedBeforeTheConfirmation() {
        val t = MemoTemplate(
            id = "t-chips", name = "チップ", body = "日付: {{d}}\n完了: {{b}}\n種類: {{c}}\nメモ: {{m}}",
            fields = listOf(
                TemplateField("d", "日付", TemplateFieldType.DATE, question = "日付はいつですか？"),
                TemplateField("b", "完了", TemplateFieldType.BOOLEAN, question = "完了していますか？"),
                TemplateField("c", "種類", TemplateFieldType.CHOICE, choices = listOf("仕事", "個人"), question = "どの種類ですか？"),
                TemplateField("m", "メモ", TemplateFieldType.TEXT, required = true),
            ),
        )
        runBlocking { application.templateRepository.save(t) }
        openChat()
        pickTemplate("t-chips")
        awaitQuestion("日付はいつですか？")
        composeRule.onNodeWithTag("chat_answer_option_YESTERDAY", useUnmergedTree = true).performClick()
        awaitQuestion("完了していますか？")
        composeRule.onNodeWithTag("chat_answer_option_true", useUnmergedTree = true).performClick()
        awaitQuestion("どの種類ですか？")
        composeRule.onNodeWithTag("chat_answer_option_仕事", useUnmergedTree = true).performClick()
        awaitQuestion("メモを入力してください")   // no question written: the label asks
        send("一言")
        awaitTag("chat_ai_preview_template")
        val yesterday = today.minusDays(1)
        // the card reads by field (2026-09-24): each answer in the user's words, not the Markdown
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("昨日", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("完了", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("仕事", substring = true)
        // change one answer: the summary lists them, the chosen one is asked again, the preview returns with the new value
        composeRule.onNodeWithTag("chat_ai_preview_edit").performClick()
        awaitTag("chat_edit_field_c")
        composeRule.onNodeWithTag("chat_edit_field_c").performClick()
        awaitQuestion("どの種類ですか？")
        composeRule.onNodeWithTag("chat_answer_option_個人", useUnmergedTree = true).performClick()
        awaitTag("chat_ai_preview_template")
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("個人", substring = true)
        assertEquals(0, TestAiRuntime.loads)
        val lines = lines()
        assertTrue(lines.toString(), lines.any { it == "USER:昨日" } && lines.any { it == "USER:はい" } && lines.any { it == "USER:個人" })
    }
}

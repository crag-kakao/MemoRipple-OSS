package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.PickerDrill.pickTemplateThroughFolders
import io.github.cragcoffee.memoripple.PickerDrill.drillToTemplate
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.TemplateAction
import io.github.cragcoffee.memoripple.domain.memos.TemplateDateToken
import io.github.cragcoffee.memoripple.domain.memos.TemplateField
import io.github.cragcoffee.memoripple.domain.memos.TemplateFieldType
import io.github.cragcoffee.memoripple.domain.memos.TemplateSearchSpec
import io.github.cragcoffee.memoripple.domain.memos.TemplateTargetSpec
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
 * Chat UI redesign + Template v2 journeys A–F (docs/CHAT_UI_TEMPLATE_V2.md) on the real screen,
 * database and pipeline: a conversation-first chat with no search controls; templates that run
 * with no model through the plus button — CREATE to a preview and one confirmation, SEARCH to
 * result cards in the conversation, APPEND to a preview that a cancel leaves unwritten; the AI
 * free text still works when a model is there and 「2番目」 follows; the history keeps template
 * events.
 */
class ChatUiTemplateV2InstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication
    private val today: LocalDate get() = application.timeProvider.currentLocalDate()

    private val meeting = MemoTemplate(
        id = "t-meeting", name = "会議メモ", body = "# {{meeting_name}}\n\n日付: {{date}}\n\n## 議題\n{{agenda}}", description = "会議のひな形",
        fields = listOf(
            TemplateField("meeting_name", "会議名", TemplateFieldType.TEXT, required = true),
            TemplateField("date", "日付", TemplateFieldType.DATE, default = "TODAY"),
            TemplateField("agenda", "議題", TemplateFieldType.MULTILINE, default = "（未定）"),
        ),
    )
    private val weekly = MemoTemplate(id = "t-weekly", name = "今週のMemoRipple", body = "", action = TemplateAction.SEARCH, searchSpec = TemplateSearchSpec("MemoRipple", TemplateDateToken.THIS_WEEK, setOf(DocumentKind.MEMO, DocumentKind.OUTLINE, DocumentKind.JOURNAL)))
    private val devLog = MemoTemplate(id = "t-devlog", name = "開発ログへ追記", body = "- {{entry}}", action = TemplateAction.APPEND, targetSpec = TemplateTargetSpec.Named("MemoRipple開発"), fields = listOf(TemplateField("entry", "内容", TemplateFieldType.TEXT, required = true)))
    private val legacy = MemoTemplate("t-legacy", "週次レビュー", "## 今週\n- ")

    @Before
    fun startEmptyNoModel() {
        runBlocking {
            application.database.clearAllTables()
            application.templateRepository.replaceAll(listOf(meeting, weekly, devLog, legacy))
            application.settingsRepository.setAutoPlayOnLaunch(false)
            application.settingsRepository.setLastChatConversationId(null)
            application.aiOrchestrator.release()
        }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0
        TestAiSelection.descriptor = null   // no model unless a journey says so
    }

    @After
    fun tidy() {
        TestAiRuntime.loadGate?.complete(Unit); TestAiRuntime.generateGate?.complete(Unit)
        runBlocking { application.aiOrchestrator.release(); application.settingsRepository.setLastChatConversationId(null); application.templateRepository.replaceAll(emptyList()) }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0
    }

    private fun at(date: LocalDate, hour: Int): Long = application.timeProvider.toEpochMillis(LocalDateTime.of(date, java.time.LocalTime.of(hour, 0)))
    private fun memo(title: String, body: String): Long = runBlocking {
        val t = at(today, 9); application.database.memoDao().insert(MemoEntity(title = title, body = body, createdAt = t, updatedAt = t, kind = "memo"))
    }
    private fun journal(body: String, date: LocalDate, hour: Int): Long = runBlocking {
        application.database.diaryDao().insert(DiaryEntryEntity(diaryDateEpochDay = date.toEpochDay(), body = body, state = DiaryState.DRAFT, createdAt = at(date, hour), updatedAt = at(date, hour)))
    }
    private fun memoBody(id: Long) = runBlocking { application.database.memoDao().findById(id)!!.body }
    private fun memoIds() = runBlocking { application.database.memoDao().allIds().toSet() }
    private fun snapshot(): String = runBlocking {
        val dao = application.database.memoDao()
        val memos = dao.allIds().sorted().map { id -> dao.findById(id)!!.let { "memo|${it.id}|${it.title}|${it.body}|${it.updatedAt}" } }
        val journals = application.database.diaryDao().observeAll().first().sortedBy { it.id }.map { "journal|${it.id}|${it.body}|${it.updatedAt}" }
        (memos + journals).joinToString("\n")
    }
    private fun conversations() = runBlocking { application.chatHistoryRepository.conversations().first() }
    private fun transcript(id: Long) = runBlocking { application.chatHistoryRepository.messages(id).first() }

    private fun proposal(intent: String, query: String? = null, targetRef: String? = null, targetName: String? = null, documentKind: String? = null, text: String? = null, dateToken: String? = null, missing: List<String> = emptyList()): String {
        fun s(v: String?) = if (v == null) "null" else "\"" + v.replace("\"", "\\\"") + "\""
        return "{\"intent\":${s(intent)},\"query\":${s(query)},\"targetRef\":${s(targetRef)},\"targetName\":${s(targetName)},\"documentKind\":${s(documentKind)}," +
            "\"text\":${s(text)},\"templateId\":null,\"dateToken\":${s(dateToken)},\"missingFields\":[${missing.joinToString(",") { s(it) }}]}"
    }

    private fun openChat() { composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input") }
    private fun send(text: String) {
        composeRule.onNodeWithTag("chat_input").performTextClearance()
        composeRule.onNodeWithTag("chat_input").performTextInput(text)
        composeRule.onNodeWithTag("chat_send").performClick()
    }
    private fun pickTemplate(id: String) { composeRule.pickTemplateThroughFolders(id, application) }
    private fun awaitTag(tag: String, timeout: Long = 15_000) {
        composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitGone(tag: String, timeout: Long = 15_000) {
        composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }
    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun awaitMessages(id: Long, n: Int) { composeRule.waitUntil(15_000) { transcript(id).size >= n } }
    /** Template = conversation script (§16): the newest assistant line of the one conversation, once it asks [text]. */
    private fun awaitQuestion(text: String) {
        composeRule.waitUntil(15_000) { conversations().singleOrNull()?.let { c -> transcript(c.id).lastOrNull { it.role == ChatRole.ASSISTANT }?.text?.contains(text) } == true }
        awaitTag("chat_message_${transcript(conversations().single().id).last { it.role == ChatRole.ASSISTANT }.id}")
    }
    private fun skip() { composeRule.onNodeWithTag("chat_answer_skip", useUnmergedTree = true).performClick() }

    // --- Journey A (RED 1–9): the chat opens as a conversation — no search controls, a quiet empty state, the plus and the input ---

    @Test
    fun theChatOpensAsAConversationWithNoSearchControls() {
        openChat()
        composeRule.onNodeWithTag("nav_chat").assertIsSelected()
        listOf("chat_mode_switch", "chat_mode_search", "chat_mode_ai", "chat_chip_today", "chat_kind_ALL", "chat_command_new_memo", "chat_command_open_calendar", "chat_result_count", "chat_ai_model_unavailable").forEach {
            assertEquals("no $it on the chat top", 0, count(it))
        }
        composeRule.onNodeWithTag("chat_empty").assertIsDisplayed()
        composeRule.onNodeWithText("MemoRippleに何を頼みますか？").assertIsDisplayed()
        assertTrue("two or three examples, not a wall", count("chat_example") in 2..3)
        composeRule.onNodeWithTag("chat_plus").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_send").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_history").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_new_conversation").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_title").assertTextContains("チャット", substring = true)
        // no model: the input stays; a one-line hint offers the setup, nothing replaces the screen
        composeRule.onNodeWithTag("chat_ai_hint").assertTextContains("Local AIモデル", substring = true)
        composeRule.onNodeWithTag("chat_plus").performClick()
        awaitTag("chat_template_picker")
        // the picker is an entrance (2026-09-22): the user's own sit under 自分のテンプレート → 未分類; each is reached by drilling
        listOf("t-meeting", "t-weekly", "t-devlog", "t-legacy").forEach { composeRule.drillToTemplate(it, application); composeRule.onNodeWithTag("chat_template_item_$it", useUnmergedTree = true).assertIsDisplayed(); repeat(2) { composeRule.onNodeWithTag("chat_template_folder_back").performClick() } }
        composeRule.onNodeWithTag("chat_template_list").performScrollToNode(hasTestTag("chat_template_create"))
        composeRule.onNodeWithTag("chat_template_create", useUnmergedTree = true).assertIsDisplayed()
        Espresso.pressBack()
        awaitGone("chat_template_picker")
        assertTrue("no conversation from opening or from the picker", conversations().isEmpty())
    }

    // --- RED 11: a free-text send with no model shows the setup card as a result, and nothing else changes ---

    @Test
    fun aFreeTextSendWithNoModelShowsTheSetupCardInTheConversationOnly() {
        openChat()
        // a conversational sentence: the Fast Path declines it (「昨日の日記を探して」 would now run without a model; docs/CHAT_FAST_PATH.md)
        send("昨日のことを思い出したいんだけど")
        awaitTag("chat_ai_model_unavailable")
        composeRule.onNodeWithTag("chat_ai_open_settings").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_input").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_plus").assertIsDisplayed()
        assertEquals(0, TestAiRuntime.loads)
        composeRule.onNodeWithTag("chat_ai_open_settings").performClick()
        awaitTag("ai_models_screen")
        composeRule.onNodeWithTag("ai_models_back").performClick()
        awaitTag("chat_input")
    }

    // --- Journey B (RED 10, 13, 15–17, 46): no model → + → CREATE template → form → preview → confirm → one document ---

    @Test
    fun aCreateTemplateRunsWithNoModelThroughTheFormThePreviewAndOneConfirmation() {
        openChat()
        val before = memoIds()
        // one question at a time (§16): a required field waits for words; an optional one may be skipped and keeps its default
        pickTemplate("t-meeting")
        awaitQuestion("会議名を入力してください")
        assertEquals("no form", 0, count("chat_template_form"))
        send("定例会")
        awaitQuestion("日付を入力してください")
        skip()
        awaitQuestion("議題を入力してください")
        skip()
        awaitTag("chat_ai_preview_template")
        // the card reads by field now (2026-09-24); the body it will write is checked after the confirm
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("会議名", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("未入力", substring = true)
        assertEquals("nothing written by the preview", before, memoIds())
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        composeRule.onAllNodesWithTag("chat_ai_preview_confirm").fetchSemanticsNodes().firstOrNull()?.let { runCatching { composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick() } }
        awaitTag("memo_reading_view")
        val made = memoIds() - before
        assertEquals("exactly one document", 1, made.size)
        assertTrue(memoBody(made.single()).startsWith("# 定例会"))
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_ai_write_success")
        assertEquals(0, TestAiRuntime.loads)
        val c = conversations().single()
        awaitMessages(c.id, 3)
        val t = transcript(c.id)
        assertEquals(ChatRole.USER, t[0].role)
        assertTrue(t[0].text.contains("会議メモ"))
        assertTrue(t.last().text.contains("作成しました"))
    }

    // --- RED 25: a legacy template still makes a memo from its body, with no form ---

    @Test
    fun aLegacyTemplateRunsAsACreateMemoWithNoForm() {
        openChat()
        val before = memoIds()
        pickTemplate("t-legacy")
        awaitTag("chat_ai_preview_template")
        assertEquals(0, count("chat_template_form"))
        // a legacy template has no fields, so the card still shows the body it will write
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("## 今週", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        awaitTag("memo_reading_view")
        assertEquals("## 今週\n- ", memoBody((memoIds() - before).single()))
    }

    // --- Journey C (RED 12, 37): a SEARCH template answers in the conversation, without a model or a search screen ---

    @Test
    fun aSearchTemplateAnswersWithResultCardsInTheConversation() {
        val hit = memo("MemoRipple開発", "## 進捗"); memo("買い物", "牛乳")
        openChat()
        pickTemplate("t-weekly")
        awaitTag("chat_ai_search_results")
        awaitTag("chat_result_memo_$hit")
        assertEquals(0, count("chat_result_memo_${(memoIds() - hit).single()}"))
        assertEquals(0, TestAiRuntime.loads)
        composeRule.onNodeWithTag("chat_input").assertIsDisplayed()
        val c = conversations().single()
        awaitMessages(c.id, 2)
        assertTrue(transcript(c.id).last().text.contains("1件"))
        // the result card opens its document and Back returns to the chat
        composeRule.onNodeWithTag("chat_result_memo_$hit").performClick()
        awaitTag("memo_reading_view")
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_input")
    }

    // --- Journey D (RED 14, 38, 39): an APPEND template previews the named target; a cancel writes nothing ---

    @Test
    fun anAppendTemplatePreviewsTheNamedTargetAndACancelLeavesTheDatabaseUnchanged() {
        val dev = memo("MemoRipple開発", "## 進捗")
        openChat()
        val before = snapshot()
        pickTemplate("t-devlog")
        awaitQuestion("内容を入力してください")
        send("会話テスト")
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("MemoRipple開発", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("- 会話テスト", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        awaitGone("chat_ai_preview_append")
        assertEquals(before, snapshot())
        assertEquals("## 進捗", memoBody(dev))
        val c = conversations().single()
        awaitMessages(c.id, 3)
        assertTrue(transcript(c.id).last().text.contains("キャンセル"))
        // a target that does not exist is a safe stop
        runBlocking { application.memoRepository.moveToTrash(dev); application.database.memoDao().deletePermanently(dev) }   // a permanent delete needs the trash first
        pickTemplate("t-devlog")
        awaitQuestion("内容を入力してください")
        send("x")
        awaitTag("chat_ai_not_found")
    }

    // --- Journey E (RED 18–21): with a model, free text works as before and 「2番目」 follows ---

    @Test
    fun withAModelFreeTextSearchWorksAndAnOrdinalFollowUpOpensTheSecondResult() {
        TestAiSelection.reset()
        val d = today.minusDays(1)
        journal("散歩の記録", d, 20); val second = journal("買い物の記録", d, 18); journal("会議の記録", d, 9)
        openChat()
        assertEquals("no hint with a model", 0, count("chat_ai_hint"))
        // Phase 3 (docs/DECISION_ENGINE.md): the dated search and the ordinal follow-up are now the
        // DecisionEngine's — deterministic, zero generations — so nothing is scripted for them
        send("昨日の記録を探して")
        awaitTag("chat_ai_search_results")
        send("2番目を開いて")
        awaitTag("diary_body")
        assertEquals("the dated search and the ordinal cost no generation", 0, TestAiRuntime.requests)
        composeRule.onNodeWithContentDescription("戻る").performClick()
        awaitTag("chat_input")
        val c = conversations().single()
        assertEquals(second, runBlocking { application.chatHistoryRepository.context(c.id) }.lastDocumentAnchor?.id)
        // a create and an append by free text still stop at their previews
        TestAiRuntime.answers.add(proposal("CREATE", documentKind = "MEMO", text = "買い物リスト"))
        send("買い物リストというメモを作って")
        awaitTag("chat_ai_preview_create")
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        val dev = memo("MemoRipple開発", "## 進捗")
        TestAiRuntime.answers.add(proposal("APPEND", targetName = "MemoRipple開発", text = "完了"))
        send("MemoRipple開発に『完了』を追記して")
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        awaitTag("memo_reading_view")
        assertEquals("## 進捗\n完了", memoBody(dev))
    }

    // --- Journey F (RED 22, 46): history → reopen → the template events are there; a new chat starts empty ---

    @Test
    fun theHistoryReopensAConversationWithItsTemplateEventsAndANewChatStartsEmpty() {
        memo("MemoRipple開発", "## 進捗")
        openChat()
        pickTemplate("t-weekly")
        awaitTag("chat_ai_search_results")
        val c = conversations().single()
        awaitMessages(c.id, 2)
        assertEquals("今週のMemoRipple", c.title)
        // UI review 2026-09-21: 新しいチャット is its own stage; the history is in its overflow
        composeRule.onNodeWithTag("chat_new_conversation").performClick()
        awaitTag("chat_stage")
        awaitGone("chat_ai_search_results")
        awaitTag("chat_empty")
        composeRule.onNodeWithTag("chat_overflow").performClick()
        awaitTag("chat_open_history")
        composeRule.onNodeWithTag("chat_open_history").performClick()
        awaitTag("chat_history_screen")
        composeRule.onNodeWithTag("chat_history_row_${c.id}").performClick()
        awaitTag("chat_input")
        val u = transcript(c.id).first(); val a = transcript(c.id).last { it.text.contains("件") }   // §16: 「…を探します。」 precedes the count
        awaitTag("chat_message_${a.id}")
        composeRule.onNodeWithTag("chat_message_${u.id}").assertTextContains("今週のMemoRipple", substring = true)
        composeRule.onNodeWithTag("chat_message_${a.id}").assertTextContains("1件", substring = true)
        composeRule.onNodeWithTag("chat_title").assertTextContains("今週のMemoRipple", substring = true)
        assertEquals("the ephemeral cards are not rebuilt from history", 0, count("chat_ai_search_results"))
    }

    // --- RED 44, 45: the editor makes a template with no model; the picker offers it at once ---

    @Test
    fun theTemplateEditorSavesANewCreateTemplateWithFieldsAndThePickerOffersIt() {
        openChat()
        composeRule.onNodeWithTag("chat_plus").performClick()
        awaitTag("chat_template_picker")
        composeRule.onNodeWithTag("chat_template_list").performScrollToNode(hasTestTag("chat_template_create"))
        composeRule.onNodeWithTag("chat_template_create", useUnmergedTree = true).performClick()
        // Template first-class (2026-09-21): the editor is stepwise and asks for no key — a field is named, and inserted into the body by that name
        awaitTag("template_editor")
        composeRule.onNodeWithTag("template_editor_name").performTextInput("読書メモ")
        composeRule.onNodeWithTag("template_editor_next").performClick()
        awaitTag("template_editor_step_1")
        composeRule.onNodeWithTag("template_editor_next").performClick()
        awaitTag("template_editor_step_2")
        composeRule.onNodeWithTag("template_editor_add_field").performClick()
        awaitTag("template_editor_field_label_0")
        composeRule.onNodeWithTag("template_editor_field_label_0").performTextInput("書名")
        composeRule.onNodeWithTag("template_editor_next").performClick()
        awaitTag("template_editor_step_3")
        composeRule.onNodeWithTag("template_editor_body").performTextInput("# ")
        composeRule.onNodeWithTag("template_editor_insert_field").performClick()
        awaitTag("template_editor_insert_0")
        composeRule.onNodeWithTag("template_editor_insert_0").performClick()
        composeRule.onNodeWithTag("template_editor_next").performClick()
        awaitTag("template_editor_step_4")
        composeRule.onNodeWithTag("template_editor_save").performClick()
        awaitTag("chat_input")
        val saved = runBlocking { application.templateRepository.current() }.first { it.name == "読書メモ" }
        assertEquals(listOf("書名"), saved.fields.map { it.label })
        assertTrue(saved.body.startsWith("# {{${saved.fields.single().key}}}"))
        assertEquals(TemplateAction.CREATE, saved.action)
        composeRule.onNodeWithTag("chat_plus").performClick()
        awaitTag("chat_template_picker")
        composeRule.drillToTemplate(saved.id, application)
        composeRule.onNodeWithTag("chat_template_item_${saved.id}", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- RED 47, 48: process death — the transcript stays, the preview goes, nothing is written ---

    @Test
    fun processDeathDuringATemplatePreviewKeepsTheTranscriptDropsThePreviewAndWritesNothing() {
        openChat()
        val before = memoIds()
        pickTemplate("t-meeting")
        awaitQuestion("会議名を入力してください")
        send("定例会")
        awaitQuestion("日付を入力してください")
        skip()
        awaitQuestion("議題を入力してください")
        skip()
        awaitTag("chat_ai_preview_template")
        val c = conversations().single()
        awaitMessages(c.id, 2)
        composeRule.activityRule.scenario.onActivity { it.viewModelStore.clear() }
        composeRule.activityRule.scenario.recreate()
        awaitTag("chat_input")
        awaitTag("chat_message_${transcript(c.id).last().id}")
        assertEquals(0, count("chat_ai_preview_template"))
        assertEquals(0, count("chat_ai_preview_confirm"))
        assertEquals(0, count("chat_template_form"))
        assertEquals(before, memoIds())
    }
}

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
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.PickerDrill.pickTemplateThroughFolders
import io.github.cragcoffee.memoripple.PickerDrill.drillToTemplate
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplatePolicy
import io.github.cragcoffee.memoripple.domain.memos.TemplateAction
import io.github.cragcoffee.memoripple.domain.memos.TemplateFile
import io.github.cragcoffee.memoripple.domain.memos.TemplateField
import io.github.cragcoffee.memoripple.domain.memos.TemplateFieldType
import io.github.cragcoffee.memoripple.domain.memos.TemplateImport
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
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
 * "AI optional, Template first-class" journeys A–G (docs/CHAT_UI_TEMPLATE_V2.md §14) on the real
 * screen, database and pipeline with **no model file and no network**: the six starters from ＋,
 * a SEARCH answered as cards, a CREATE through the form to one confirmed memo, an APPEND with a
 * target chosen at run time cancelled with the database untouched, a custom template made in the
 * stepwise editor and run, an export → delete → import round trip, the free-text setup card that
 * offers the templates, and — with a model — the AI free text unchanged.
 */
class NoAiTemplateJourneysInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication
    private val today: LocalDate get() = application.timeProvider.currentLocalDate()

    private val meeting = "starter-meeting-memo"
    private val yesterdayJournal = "starter-yesterday-journal"
    private val projectLog = "starter-project-log"
    private val thisWeek = "starter-this-week"

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
        TestAiSelection.descriptor = null   // no model unless a journey says so
        TestGuards.online = false           // and no network: a template needs none
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
    private fun snapshot(): String = runBlocking {
        val dao = application.database.memoDao()
        val memos = dao.allIds().sorted().map { id -> dao.findById(id)!!.let { "memo|${it.id}|${it.title}|${it.body}|${it.updatedAt}" } }
        val journals = application.database.diaryDao().observeAll().first().sortedBy { it.id }.map { "journal|${it.id}|${it.body}|${it.updatedAt}" }
        (memos + journals).joinToString("\n")
    }
    private fun conversations() = runBlocking { application.chatHistoryRepository.conversations().first() }
    private fun transcript(id: Long) = runBlocking { application.chatHistoryRepository.messages(id).first() }
    private fun recents() = runBlocking { application.recentTemplateRepository.recent.first() }.map { it.templateId }

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
    private fun openPicker() { composeRule.onNodeWithTag("chat_plus").performClick(); awaitTag("chat_template_picker") }
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

    // --- A: no model → ＋ → 昨日の日記を探す → result cards in the conversation ---

    @Test
    fun aStarterSearchAnswersWithCardsAndNoModelIsTouched() {
        val yesterday = journal("昨日のこと", today.minusDays(1))
        journal("今日のこと", today)
        openChat()
        composeRule.onNodeWithTag("chat_ai_hint").assertIsDisplayed()
        openPicker()
        // the picker is an entrance (2026-09-22): three built-in folders and 自分のテンプレート at the root; a starter sits in its folder
        listOf("chat_template_folder_record", "chat_template_folder_think", "chat_template_folder_search", "chat_template_folder_mine").forEach { composeRule.onNodeWithTag("chat_template_list").performScrollToNode(hasTestTag(it)); composeRule.onNodeWithTag(it, useUnmergedTree = true).assertIsDisplayed() }
        listOf("starter-daily-review", meeting, "starter-idea-memo", projectLog, thisWeek, yesterdayJournal).forEach {
            composeRule.drillToTemplate(it, application)
            composeRule.onNodeWithTag("chat_template_item_$it").assertIsDisplayed()
            composeRule.onNodeWithTag("chat_template_folder_back").performClick()
        }
        assertEquals("no recents yet", 0, count("chat_template_section_recent"))
        composeRule.drillToTemplate(yesterdayJournal, application)
        composeRule.onNodeWithTag("chat_template_item_$yesterdayJournal").performClick()
        awaitTag("chat_ai_search_results")
        composeRule.onNodeWithTag("chat_result_journal_$yesterday").assertIsDisplayed()
        assertEquals("only yesterday's", 1, count("chat_result"))
        assertEquals(0, TestAiRuntime.loads)
        val c = conversations().single()
        awaitMessages(c.id, 2)
        assertEquals("昨日の日記を探す", transcript(c.id).first().text)
        assertTrue(transcript(c.id).last().text.contains("1件"))
        // the run is remembered as recent, by id
        assertEquals(listOf(yesterdayJournal), recents())
        openPicker()
        composeRule.onNodeWithTag("chat_template_section_recent", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("chat_template_recent_$yesterdayJournal", useUnmergedTree = true).assertIsDisplayed()
    }

    // --- B: no model → ＋ → 会議メモ → the form (a required field asked for by its label) → preview → one confirmed memo; recents in order ---

    @Test
    fun aStarterCreateRunsThroughTheFormThePreviewAndOneConfirmationWithNoModel() {
        openChat()
        val before = memoIds()
        // one question at a time (§16)
        pickTemplate(meeting)
        awaitQuestion("会議名は？")
        send("定例会")
        awaitQuestion("参加者は？")
        send("A、B")
        awaitQuestion("何について話しましたか？"); skip()
        awaitQuestion("何が決まりましたか？"); skip()
        awaitQuestion("次にやることは？"); skip()
        awaitTag("chat_ai_preview_template")
        // the card reads by field (2026-09-24); the Markdown it will write is checked after the confirm
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("会議名", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("A、B", substring = true)
        assertEquals(before, memoIds())
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        awaitTag("memo_reading_view")
        val made = memoIds() - before
        assertEquals(1, made.size)
        assertTrue(memoBody(made.single()).startsWith("# 定例会"))
        assertEquals(0, TestAiRuntime.loads)
        Espresso.pressBack()
        awaitTag("chat_input")
        val c = conversations().single()
        awaitMessages(c.id, 3)
        assertEquals("会議メモ", transcript(c.id).first().text)
        // a second run: the recents list is newest first, ids only
        pickTemplate(thisWeek)
        awaitTag("chat_ai_search_results")
        assertEquals(listOf(thisWeek, meeting), recents())
        openPicker()
        composeRule.onNodeWithTag("chat_template_recent_$thisWeek", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("chat_template_recent_$meeting", useUnmergedTree = true).assertIsDisplayed()
    }

    // --- C: no model → ＋ → プロジェクトログ → the target asked at run time → preview → cancel → nothing changed ---

    @Test
    fun aStarterAppendAsksItsTargetPreviewsAndACancelLeavesTheDatabaseUntouched() {
        val dev = memo("MemoRipple開発", "## 進捗")
        openChat()
        val before = snapshot()
        pickTemplate(projectLog)
        awaitQuestion("どのプロジェクトですか？")
        send("MemoRipple開発")   // one hit: taken as the target
        awaitQuestion("今日やったことは？")
        send("フォルダ対応完了")
        awaitQuestion("困っていることはありますか？"); skip()
        awaitQuestion("次にやることは？"); skip()
        awaitTag("chat_ai_preview_append")
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("MemoRipple開発", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_append").assertTextContains("フォルダ対応完了", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        awaitGone("chat_ai_preview_append")
        assertEquals(before, snapshot())
        assertEquals("## 進捗", memoBody(dev))
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- D: a custom template through the stepwise editor (labels, no keys; fields inserted into the body; preview) → run ---

    @Test
    fun aCustomTemplateIsMadeStepByStepWithFieldsInsertedByLabelPreviewedSavedAndRun() {
        openChat()
        openPicker()
        composeRule.onNodeWithTag("chat_template_create", useUnmergedTree = true).performClick()
        awaitTag("template_editor")
        composeRule.onNodeWithTag("template_editor_step_0").assertIsDisplayed()
        composeRule.onNodeWithTag("template_editor_name").performTextInput("読書メモ")
        composeRule.onNodeWithTag("template_editor_next").performClick()
        awaitTag("template_editor_step_1")
        composeRule.onNodeWithTag("template_editor_action_CREATE").assertIsDisplayed()
        composeRule.onNodeWithTag("template_editor_next").performClick()
        awaitTag("template_editor_step_2")
        composeRule.onNodeWithTag("template_editor_add_field").performClick()
        awaitTag("template_editor_field_label_0")
        composeRule.onNodeWithTag("template_editor_field_label_0").performTextInput("書名")
        composeRule.onNodeWithTag("template_editor_field_required_0").performClick()
        composeRule.onNodeWithTag("template_editor_add_field").performClick()
        awaitTag("template_editor_field_label_1")
        composeRule.onNodeWithTag("template_editor_field_label_1").performTextInput("感想")
        composeRule.onNodeWithTag("template_editor").performScrollToNode(hasTestTag("template_editor_field_type_1_MULTILINE"))
        composeRule.onNodeWithTag("template_editor_field_type_1_MULTILINE").performClick()
        assertEquals("no key is asked for", 0, count("template_editor_field_key_0"))
        composeRule.onNodeWithTag("template_editor_next").performClick()
        awaitTag("template_editor_step_3")
        composeRule.onNodeWithTag("template_editor_body").performTextInput("# ")
        composeRule.onNodeWithTag("template_editor_insert_field").performClick()
        awaitTag("template_editor_insert_0")
        composeRule.onNodeWithTag("template_editor_insert_0").performClick()
        composeRule.onNodeWithTag("template_editor_body").assertTextContains("書名", substring = true)
        composeRule.onNodeWithTag("template_editor_next").performClick()
        awaitTag("template_editor_step_4")
        composeRule.onNodeWithTag("template_editor_preview").assertTextContains("（書名）", substring = true)
        composeRule.onNodeWithTag("template_editor_save").performClick()
        awaitTag("chat_input")
        val saved = runBlocking { application.templateRepository.current() }.first { it.name == "読書メモ" }
        assertEquals(listOf("書名", "感想"), saved.fields.map { it.label })
        assertEquals(listOf(TemplateFieldType.TEXT, TemplateFieldType.MULTILINE), saved.fields.map { it.type })
        assertTrue("the body carries the key, not the label", saved.body.contains("{{${saved.fields[0].key}}}") && !saved.body.contains("書名"))
        assertEquals(TemplateAction.CREATE, saved.action)
        val before = memoIds()
        pickTemplate(saved.id)
        awaitQuestion("書名を入力してください")
        send("星の王子さま")
        awaitQuestion("感想を入力してください"); skip()
        awaitTag("chat_ai_preview_template")
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("星の王子さま", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_confirm").performClick()
        awaitTag("memo_reading_view")
        assertEquals(1, (memoIds() - before).size)
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- E: export → delete → import → the same v2 definition (the file round trip the settings screen drives) ---

    @Test
    fun aV2TemplateSurvivesExportDeleteAndImportUnchanged() {
        val custom = MemoTemplate(
            id = "t-custom", name = "振り返り", body = "## 良かったこと\n{{good}}\n", description = "夜に", action = TemplateAction.CREATE, documentKind = DocumentKind.JOURNAL,
            fields = listOf(TemplateField("good", "良かったこと", TemplateFieldType.MULTILINE, required = true, default = ""), TemplateField("mood", "気分", TemplateFieldType.CHOICE, choices = listOf("良い", "普通"), default = "普通")),
        )
        runBlocking { application.templateRepository.save(custom) }
        val exported = TemplateFile.export(runBlocking { application.templateRepository.current() })
        runBlocking { application.templateRepository.delete(custom.id) }
        assertTrue(runBlocking { application.templateRepository.current() }.none { it.id == custom.id })
        val imported = TemplateFile.import(exported) as TemplateImport.Ready
        runBlocking { application.templateRepository.replaceAll(MemoTemplatePolicy.upsertAll(application.templateRepository.current(), imported.templates)) }
        val back = runBlocking { application.templateRepository.current() }.first { it.id == custom.id }
        assertEquals(custom.copy(createdAt = back.createdAt, updatedAt = back.updatedAt), back)
        openChat()
        openPicker()
        composeRule.drillToTemplate("t-custom", application)
        composeRule.onNodeWithTag("chat_template_item_t-custom").assertIsDisplayed()
    }

    // --- F: free text with no model → the setup card offers the setup and the templates; a matching name is suggested, never run ---

    @Test
    fun freeTextWithNoModelShowsTheSetupCardThatOffersTheTemplatesAndSuggestsAMatchingOne() {
        openChat()
        // a conversational sentence: the Fast Path declines it, and with no model the setup card
        // answers (「昨日の日記を探して」 would now fast-match and run without one; docs/CHAT_FAST_PATH.md)
        send("昨日のことを思い出したいんだけど")
        awaitTag("chat_ai_model_unavailable")
        composeRule.onNodeWithTag("chat_ai_model_unavailable").assertTextContains("Local AIモデルが必要", substring = true)
        composeRule.onNodeWithTag("chat_ai_open_settings").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_ai_use_templates").performClick()
        awaitTag("chat_template_picker")
        Espresso.pressBack()
        awaitGone("chat_template_picker")
        assertEquals(0, TestAiRuntime.loads)
        composeRule.onNodeWithTag("chat_ai_dismiss").performClick()
        val before = memoIds()
        send("会議メモを作りたい")
        awaitTag("chat_template_suggestion")
        composeRule.onNodeWithTag("chat_template_suggestion").assertTextContains("会議メモ", substring = true)
        assertEquals("a suggestion runs nothing", 0, count("chat_answer_options"))
        assertEquals(before, memoIds())
        composeRule.onNodeWithTag("chat_template_suggestion_use").performClick()
        awaitQuestion("会議名は？")   // the script starts; nothing written
        assertEquals(before, memoIds())
    }

    // --- G: a model installed → free text SEARCH → the normal conversation flow, templates still beside it ---

    @Test
    fun withAModelFreeTextStillGoesThroughTheAiAndTemplatesStayBesideIt() {
        TestAiSelection.descriptor = TestAiSelection.default
        TestGuards.online = true
        memo("散歩のメモ", "本文")
        openChat()
        TestAiRuntime.answers.add(proposal("SEARCH", query = "散歩"))
        // a conversational sentence: the Fast Path declines it, so the AI route is what runs (docs/CHAT_FAST_PATH.md)
        send("散歩について何かあったっけ")
        awaitTag("chat_ai_search_results")
        assertEquals(1, TestAiRuntime.loads)
        assertEquals(1, count("chat_result"))
        val c = conversations().single()
        awaitMessages(c.id, 2)
        assertEquals(0, count("chat_template_suggestion"))
        pickTemplate(thisWeek)
        awaitTag("chat_ai_search_results")
        assertEquals("the template needed no second load", 1, TestAiRuntime.loads)
        assertEquals(listOf(thisWeek), recents())
    }
}

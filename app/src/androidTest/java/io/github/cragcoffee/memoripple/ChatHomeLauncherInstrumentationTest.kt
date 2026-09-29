package io.github.cragcoffee.memoripple

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import io.github.cragcoffee.memoripple.PickerDrill.drillToTemplate
import io.github.cragcoffee.memoripple.PickerDrill.pickTemplateThroughFolders
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.StarterTemplates
import io.github.cragcoffee.memoripple.domain.memos.TemplateFolder
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
 * Chat Home Launcher journeys (human brief 2026-09-23 and the user's review of the same day) on
 * the real screen, database and pipeline **with no model file**: the four fixed entrances on a new
 * chat; 「＋ 追加」 choosing a template for the home instead of running it; a cell starting its
 * ordinary script, Think included; a long press renaming a cell or taking it off the home; a long
 * press in the picker deleting one of the user's own templates after a confirmation; a folder move
 * that changes nothing; the grid going as soon as the conversation has a line and returning on a
 * new chat; and 探す answering deterministically with zero model loads.
 */
class ChatHomeLauncherInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication
    private val today: LocalDate get() = application.timeProvider.currentLocalDate()

    private val thinkIdea = "starter-think-idea"
    private val meeting = "starter-meeting-memo"

    @Before
    fun startEmptyAndWithNoModel() {
        runBlocking {
            application.database.clearAllTables()
            application.templateRepository.replaceAll(emptyList())
            application.templateFolderRepository.replaceAll(emptyList())
            application.recentTemplateRepository.clear()
            application.settingsRepository.clearPinnedTemplates()
            application.settingsRepository.clearHomeShortcuts()
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
            application.settingsRepository.clearPinnedTemplates()
            application.settingsRepository.clearHomeShortcuts()
            application.settingsRepository.setLastChatConversationId(null)
            application.templateRepository.replaceAll(emptyList())
            application.templateFolderRepository.replaceAll(emptyList())
            application.recentTemplateRepository.clear()
        }
        TestAiRuntime.reset(); TestAiSelection.reset(); TestThermal.status = 0; TestGuards.reset()
    }

    // --- helpers ---

    private fun at(date: LocalDate, hour: Int): Long = application.timeProvider.toEpochMillis(LocalDateTime.of(date, java.time.LocalTime.of(hour, 0)))
    private fun memo(title: String, body: String): Long = runBlocking {
        val t = at(today, 9); application.database.memoDao().insert(MemoEntity(title = title, body = body, createdAt = t, updatedAt = t, kind = "memo"))
    }
    private fun journal(body: String, date: LocalDate): Long = runBlocking {
        application.database.diaryDao().insert(DiaryEntryEntity(diaryDateEpochDay = date.toEpochDay(), body = body, state = DiaryState.DRAFT, createdAt = at(date, 8), updatedAt = at(date, 8)))
    }
    private fun memoIds() = runBlocking { application.database.memoDao().allIds().toSet() }
    private fun home(id: String) = runBlocking { application.settingsRepository.addHomeShortcut(id) }
    private fun unhome(id: String) = runBlocking { application.settingsRepository.removeHomeShortcut(id) }
    private fun homeIds() = runBlocking { application.settingsRepository.homeShortcuts.first() }.map { it.templateId }
    private fun saveTemplate(t: MemoTemplate) = runBlocking { application.templateRepository.replaceAll(application.templateRepository.current().filterNot { it.id == t.id } + t) }
    private fun conversations() = runBlocking { application.chatHistoryRepository.conversations().first() }

    private fun openChat() { composeRule.onNodeWithTag("nav_chat").performClick(); awaitTag("chat_input") }
    private fun newChat() { composeRule.onNodeWithTag("chat_new_conversation").performClick(); awaitTag("chat_input") }
    private fun send(text: String) {
        composeRule.onNodeWithTag("chat_input").performTextClearance()
        composeRule.onNodeWithTag("chat_input").performTextInput(text)
        composeRule.onNodeWithTag("chat_send").performClick()
    }
    private fun tapShortcut(tag: String) { awaitTag(tag); composeRule.onNodeWithTag(tag, useUnmergedTree = true).performClick() }
    private fun awaitTag(tag: String, timeout: Long = 15_000) {
        composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitGone(tag: String, timeout: Long = 15_000) {
        composeRule.waitUntil(timeout) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }
    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    /** Cells are tagged by their template id, so a template cell is counted by the prefix. */
    private fun countPrefix(prefix: String) = composeRule.onAllNodes(SemanticsMatcher("tag starts with $prefix") { node -> node.config.getOrElseNullable(SemanticsProperties.TestTag) { null }?.startsWith(prefix) == true }, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun awaitText(text: String) {
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("chat_transcript_row", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                conversations().firstOrNull()?.let { c -> runBlocking { application.chatHistoryRepository.messages(c.id).first() }.any { it.text.contains(text) } } == true
        }
    }

    // --- A: a new chat shows the four fixed entrances ---

    @Test
    fun aNewChatOffersTheFourFixedShortcuts() {
        openChat()
        composeRule.onNodeWithTag("chat_empty").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_home_launcher", useUnmergedTree = true).assertIsDisplayed()
        listOf("memo", "search", "journal", "organize").forEach { name ->
            composeRule.onNodeWithTag("chat_home_shortcut_$name", useUnmergedTree = true).assertIsDisplayed().assertHasClickAction()
        }
        // with nothing pinned the grid ends with the entrance that adds one, and invents no empty slot
        composeRule.onNodeWithTag("chat_home_shortcut_add", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("nothing on the home yet", 0, countPrefix("chat_home_shortcut_template_"))
        // the examples stay, below the grid
        assertTrue("two or three examples, still", count("chat_example") in 2..3)
        val grid = composeRule.onNodeWithTag("chat_home_launcher", useUnmergedTree = true).getBoundsInRoot()
        val firstExample = composeRule.onAllNodesWithTag("chat_example", useUnmergedTree = true)[0].getBoundsInRoot()
        assertTrue("the launcher reads before the examples", grid.top.value <= firstExample.top.value)
        assertEquals("no model was touched", 0, TestAiRuntime.loads)
    }

    @Test
    fun everyShortcutSaysWhatItIsAndIsBigEnoughToHit() {
        openChat()
        awaitTag("chat_home_launcher")
        listOf("chat_home_shortcut_memo" to "メモ", "chat_home_shortcut_search" to "探す", "chat_home_shortcut_journal" to "日記", "chat_home_shortcut_organize" to "整理").forEach { (tag, label) ->
            val node = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
            node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
            val description = node.fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.joinToString(" ").orEmpty()
            assertTrue("$tag does not say $label: $description", description.contains(label))
            val b = node.getUnclippedBoundsInRoot()
            val w = b.right.value - b.left.value
            val h = b.bottom.value - b.top.value
            assertTrue("$tag is smaller than a touch target: $w x $h", w >= 47.5f && h >= 47.5f)
        }
    }

    // --- B: a template put on the home becomes a shortcut and runs the ordinary script ---

    @Test
    fun aTemplateOnTheHomeBecomesAShortcutThatStartsItsConversationScript() {
        home(meeting)
        openChat()
        awaitTag("chat_home_shortcut_template_$meeting")
        composeRule.onNodeWithTag("chat_home_shortcut_template_$meeting", useUnmergedTree = true).assertIsDisplayed().assertHasClickAction()
        tapShortcut("chat_home_shortcut_template_$meeting")
        // the same one-question-at-a-time script the ＋ picker starts
        awaitText("会議名")
        awaitGone("chat_home_launcher")
        assertEquals("no model was touched", 0, TestAiRuntime.loads)
        assertEquals("nothing was written", 0, memoIds().size)
    }

    // --- C: a Think template on the home starts Think ---

    @Test
    fun aThinkTemplateOnTheHomeStartsTheExistingThinkFlow() {
        home(thinkIdea)
        openChat()
        tapShortcut("chat_home_shortcut_template_$thinkIdea")
        awaitText("どんなアイデアですか")
        assertEquals(0, TestAiRuntime.loads)
        assertEquals("Think writes nothing until the save", 0, memoIds().size)
    }

    // --- D / E: a folder move keeps the cell; taking it off the home removes it ---

    @Test
    fun aFolderMoveKeepsTheShortcutAndRemovingItTakesTheCellAway() {
        val mine = MemoTemplate(id = "u-home", name = "朝の支度", body = "本文")
        saveTemplate(mine)
        home("u-home")
        openChat()
        awaitTag("chat_home_shortcut_template_u-home")

        runBlocking {
            application.templateFolderRepository.replaceAll(listOf(TemplateFolder("f-work", "仕事", 0)))
            application.templateRepository.replaceAll(listOf(mine.copy(folderId = "f-work")))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_home_shortcut_template_u-home", useUnmergedTree = true).assertIsDisplayed()

        unhome("u-home")
        awaitGone("chat_home_shortcut_template_u-home")
        composeRule.onNodeWithTag("chat_home_shortcut_memo", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aDeletedTemplateLeavesNoBrokenCell() {
        val mine = MemoTemplate(id = "u-gone", name = "消えるテンプレート", body = "本文")
        saveTemplate(mine)
        home("u-gone")
        openChat()
        awaitTag("chat_home_shortcut_template_u-gone")
        runBlocking { application.templateRepository.replaceAll(emptyList()) }
        awaitGone("chat_home_shortcut_template_u-gone")
        // the orphan id is simply skipped: the grid is the fixed four and 追加 again
        composeRule.onNodeWithTag("chat_home_launcher", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("chat_home_shortcut_add", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun moreThanFourChosenTemplatesDoNotOverflowTheGrid() {
        listOf("starter-daily-review", meeting, "starter-idea-memo", thinkIdea, "starter-this-week", "starter-think-plan").forEach { home(it) }
        openChat()
        awaitTag("chat_home_launcher")
        composeRule.waitUntil(15_000) { countPrefix("chat_home_shortcut_template_") == 4 }
        assertEquals("at most four reach the home", 4, countPrefix("chat_home_shortcut_template_"))
        assertEquals("and the grid is full, so no 追加", 0, count("chat_home_shortcut_add"))
    }

    // --- F / G: the grid belongs to an empty conversation ---

    @Test
    fun theLauncherGoesAsSoonAsTheConversationHasALineAndComesBackOnANewChat() {
        memo("会議の記録", "本文")
        openChat()
        awaitTag("chat_home_launcher")
        send("会議を探して")
        awaitGone("chat_home_launcher")
        assertEquals("the empty state went with it", 0, count("chat_empty"))
        // the history drawer over a conversation that has lines: still no grid behind it
        composeRule.onNodeWithTag("chat_history").performClick()
        awaitTag("chat_drawer")
        val existing = conversations().first()
        composeRule.onNodeWithTag("chat_drawer_row_${existing.id}", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        assertEquals("a conversation with lines never shows the grid", 0, count("chat_home_launcher"))
        // 新しいチャット brings it back
        awaitTag("chat_new_conversation")
        newChat()
        awaitTag("chat_home_launcher")
        composeRule.onNodeWithTag("chat_home_shortcut_memo", useUnmergedTree = true).assertIsDisplayed()
    }

    // --- H: 探す with no model, deterministic, zero loads ---

    @Test
    fun theSearchShortcutAsksOneQuestionAndAnswersWithNoModel() {
        memo("ラーメンの記録", "本文")
        memo("会議の記録", "本文")
        openChat()
        tapShortcut("chat_home_shortcut_search")
        awaitText("何を探しますか")
        send("ラーメン")
        awaitTag("chat_ai_search_results")
        assertEquals("only the one", 1, count("chat_result"))
        assertEquals("no model was loaded", 0, TestAiRuntime.loads)
    }

    @Test
    fun theMemoShortcutAsksOneQuestionAndStopsAtThePreviewWithNothingWritten() {
        openChat()
        val before = memoIds()
        tapShortcut("chat_home_shortcut_memo")
        awaitText("何をメモしますか")
        send("買い物リスト")
        awaitTag("chat_ai_preview_create")
        assertEquals("a preview writes nothing", before, memoIds())
        composeRule.onNodeWithTag("chat_ai_preview_cancel").performClick()
        assertEquals("and a cancel writes nothing either", before, memoIds())
        assertEquals(0, TestAiRuntime.loads)
    }

    @Test
    fun theJournalShortcutStartsTheBuiltInReviewTemplate() {
        openChat()
        tapShortcut("chat_home_shortcut_journal")
        awaitText("今日の良かったことは")
        assertEquals(0, TestAiRuntime.loads)
        assertEquals("nothing is written before the preview and the confirmation", 0, runBlocking { application.database.diaryDao().observeAll().first() }.size)
    }

    @Test
    fun theOrganizeShortcutOpensThePickerAtTheThinkFolder() {
        openChat()
        tapShortcut("chat_home_shortcut_organize")
        awaitTag("chat_template_picker")
        awaitTag("chat_template_item_$thinkIdea")
        composeRule.onNodeWithTag("chat_template_item_$thinkIdea", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, TestAiRuntime.loads)
    }

    // --- 「＋ 追加」: the same list, shown to choose from, and a tap puts a template on the home ---

    @Test
    fun theAddEntranceChoosesATemplateForTheHomeInsteadOfRunningIt() {
        memo("会議の記録", "本文")
        openChat()
        tapShortcut("chat_home_shortcut_add")
        awaitTag("chat_template_picker")
        // it says what it is, and a row says what a tap does — never 作成する there
        composeRule.onNodeWithTag("chat_template_picker_title", useUnmergedTree = true).assertTextContains("ホームに追加", substring = true)
        composeRule.onNodeWithTag("chat_template_adding_note", useUnmergedTree = true).assertIsDisplayed()
        composeRule.drillToTemplate(meeting, application)
        composeRule.onNodeWithTag("chat_template_add_home_$meeting", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("chat_template_item_$meeting").performClick()
        // the template went to the home; it did not run
        awaitTag("chat_home_shortcut_template_$meeting")
        assertEquals(listOf(meeting), homeIds())
        assertEquals("no conversation was started", 0, conversations().size)
        assertEquals(0, TestAiRuntime.loads)
        // asked for again, the row now says it is already there
        tapShortcut("chat_home_shortcut_add")
        composeRule.drillToTemplate(meeting, application)
        composeRule.onNodeWithTag("chat_template_on_home_$meeting", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theLowerPlusStillRunsATemplateAndNeverAddsOne() {
        openChat()
        composeRule.pickTemplateThroughFolders(thinkIdea, application)
        awaitText("どんなアイデアですか")
        assertEquals("the lower ＋ runs, it does not add", emptyList<String>(), homeIds())
    }

    // --- a long press on a cell: its name, and taking it off the home ---

    @Test
    fun aLongPressRenamesAHomeCellWithoutTouchingTheTemplate() {
        home(meeting)
        openChat()
        awaitTag("chat_home_shortcut_template_$meeting")
        composeRule.onNodeWithTag("chat_home_shortcut_template_$meeting", useUnmergedTree = true).performTouchInput { longClick() }
        awaitTag("chat_home_shortcut_rename_$meeting")
        composeRule.onNodeWithTag("chat_home_shortcut_rename_$meeting", useUnmergedTree = true).performClick()
        awaitTag("chat_home_shortcut_rename_dialog")
        composeRule.onNodeWithTag("chat_home_shortcut_rename_input").performTextInput("朝会")
        composeRule.onNodeWithTag("chat_home_shortcut_rename_confirm").performClick()
        composeRule.waitUntil(15_000) { runBlocking { application.settingsRepository.homeShortcuts.first() }.firstOrNull()?.label == "朝会" }
        composeRule.onNodeWithTag("chat_home_shortcut_template_$meeting", useUnmergedTree = true).assertContentDescriptionEquals("朝会")
        // the template itself is untouched, and the cell still starts its script
        assertEquals("会議メモ", runBlocking { application.templateRepository.current() }.firstOrNull { it.id == meeting }?.name ?: StarterTemplates.find(meeting)!!.name)
        tapShortcut("chat_home_shortcut_template_$meeting")
        awaitText("会議名")
    }

    @Test
    fun aLongPressTakesACellOffTheHomeAndLeavesTheTemplateAlone() {
        home(meeting)
        openChat()
        awaitTag("chat_home_shortcut_template_$meeting")
        composeRule.onNodeWithTag("chat_home_shortcut_template_$meeting", useUnmergedTree = true).performTouchInput { longClick() }
        awaitTag("chat_home_shortcut_remove_$meeting")
        composeRule.onNodeWithTag("chat_home_shortcut_remove_$meeting", useUnmergedTree = true).performClick()
        awaitGone("chat_home_shortcut_template_$meeting")
        assertEquals(emptyList<String>(), homeIds())
        // the template is still in the picker
        composeRule.onNodeWithTag("chat_plus").performClick()
        composeRule.drillToTemplate(meeting, application)
        composeRule.onNodeWithTag("chat_template_item_$meeting").assertIsDisplayed()
    }

    @Test
    fun theFixedFourHaveNoMenuOfTheirOwn() {
        openChat()
        awaitTag("chat_home_shortcut_memo")
        composeRule.onNodeWithTag("chat_home_shortcut_memo", useUnmergedTree = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertEquals("the fixed four are not the user's to change", 0, count("chat_home_shortcut_menu"))
    }

    // --- a long press in the picker deletes one of the user's own templates ---

    @Test
    fun aLongPressDeletesTheUsersOwnTemplateAfterAConfirmationAndTakesItsHomeCellWithIt() {
        val mine = MemoTemplate(id = "u-del", name = "消すテンプレート", body = "本文")
        saveTemplate(mine)
        home("u-del")
        openChat()
        awaitTag("chat_home_shortcut_template_u-del")
        composeRule.onNodeWithTag("chat_plus").performClick()
        composeRule.drillToTemplate("u-del", application)
        composeRule.onNodeWithTag("chat_template_item_u-del", useUnmergedTree = true).performTouchInput { longClick() }
        awaitTag("chat_template_delete_u-del")
        composeRule.onNodeWithTag("chat_template_delete_u-del", useUnmergedTree = true).performClick()
        awaitTag("chat_template_delete_dialog")
        composeRule.onNodeWithTag("chat_template_delete_confirm").performClick()
        composeRule.waitUntil(15_000) { runBlocking { application.templateRepository.current() }.none { it.id == "u-del" } }
        composeRule.waitUntil(15_000) { homeIds().isEmpty() }
        assertEquals("nothing else was written", 0, memoIds().size)
    }

    @Test
    fun aStarterIsNeverOfferedForDeletion() {
        openChat()
        composeRule.onNodeWithTag("chat_plus").performClick()
        composeRule.drillToTemplate(meeting, application)
        composeRule.onNodeWithTag("chat_template_item_$meeting", useUnmergedTree = true).performTouchInput { longClick() }
        awaitTag("chat_template_pin_$meeting")
        assertEquals("a starter is code, not the user's to delete", 0, count("chat_template_delete_$meeting"))
    }

    @Test
    fun cancellingTheDeletionKeepsTheTemplate() {
        val mine = MemoTemplate(id = "u-keep", name = "残すテンプレート", body = "本文")
        saveTemplate(mine)
        openChat()
        composeRule.onNodeWithTag("chat_plus").performClick()
        composeRule.drillToTemplate("u-keep", application)
        composeRule.onNodeWithTag("chat_template_item_u-keep", useUnmergedTree = true).performTouchInput { longClick() }
        awaitTag("chat_template_delete_u-keep")
        composeRule.onNodeWithTag("chat_template_delete_u-keep", useUnmergedTree = true).performClick()
        awaitTag("chat_template_delete_dialog")
        composeRule.onNodeWithText("キャンセル").performClick()
        composeRule.waitForIdle()
        assertTrue("it is still there", runBlocking { application.templateRepository.current() }.any { it.id == "u-keep" })
    }

    // --- the launcher survives a process recreation, because its list does ---

    @Test
    fun theGridIsTheSameAfterARecreation() {
        home(meeting)
        openChat()
        awaitTag("chat_home_shortcut_template_$meeting")
        composeRule.activityRule.scenario.recreate()
        awaitTag("chat_home_launcher")
        composeRule.onNodeWithTag("chat_home_shortcut_template_$meeting", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("chat_home_shortcut_memo", useUnmergedTree = true).assertIsDisplayed()
    }

    // --- 「修正」 (2026-09-24): pressable rows for a template, and one more ask for a memo ---

    @Test
    fun theFixChooserIsPressableRowsWithTheCurrentValueUnderEachName() {
        openChat()
        composeRule.onNodeWithTag("chat_plus").performClick()
        composeRule.drillToTemplate(meeting, application)
        composeRule.onNodeWithTag("chat_template_item_$meeting").performClick()
        // answer the first question and skip the rest, so some fields are empty
        awaitText("会議名")
        send("定例会")
        repeat(4) {
            composeRule.waitUntil(15_000) { count("chat_answer_skip") == 1 }
            composeRule.onNodeWithTag("chat_answer_skip", useUnmergedTree = true).performClick()
        }
        awaitTag("chat_ai_preview_template")
        // the preview reads by field: the names of the template, and 未入力 where nothing was said
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("会議名", substring = true)
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("未入力", substring = true)

        composeRule.onNodeWithTag("chat_ai_preview_edit").performClick()
        awaitTag("chat_edit_list")
        composeRule.onNodeWithText("修正する項目を選んでください").assertIsDisplayed()
        // every field is one row: a button to a screen reader, saying its name and what it holds
        val row = composeRule.onNodeWithTag("chat_edit_field_meeting_name", useUnmergedTree = true)
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
        val description = row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
        assertTrue("the row says its name and value: $description", description.contains("会議名") && description.contains("定例会"))
        val bounds = row.getUnclippedBoundsInRoot()
        assertTrue("the whole row is a target", (bounds.bottom - bounds.top).value >= 47.5f)
        // a skipped field reads 未入力 here too, never （スキップ）
        val skipped = composeRule.onNodeWithTag("chat_edit_field_participants", useUnmergedTree = true)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
        assertTrue("a skipped field reads 未入力: $skipped", skipped.contains("未入力"))

        // choosing one asks that question again and the preview comes back with the new answer
        composeRule.onNodeWithTag("chat_edit_field_meeting_name", useUnmergedTree = true).performClick()
        awaitText("会議名は？")
        send("臨時会")
        awaitTag("chat_ai_preview_template")
        composeRule.onNodeWithTag("chat_ai_preview_template").assertTextContains("臨時会", substring = true)
        assertEquals("nothing was written by any of it", 0, memoIds().size)
        assertEquals(0, TestAiRuntime.loads)
    }

    @Test
    fun aMemoAskedForInOneQuestionCanBeAskedAgain() {
        openChat()
        tapShortcut("chat_home_shortcut_memo")
        awaitText("何をメモしますか")
        send("かいもの")
        awaitTag("chat_ai_preview_create")
        composeRule.onNodeWithTag("chat_ai_preview_create").assertTextContains("かいもの", substring = true)

        composeRule.onNodeWithTag("chat_ai_preview_edit").performClick()
        // the same question again, with what was answered ready to be changed
        composeRule.waitUntil(15_000) { count("chat_ai_preview_create") == 0 }
        composeRule.onNodeWithTag("chat_input").assertTextContains("かいもの", substring = true)
        composeRule.onNodeWithTag("chat_input").performTextClearance()
        composeRule.onNodeWithTag("chat_input").performTextInput("かいものリスト")
        composeRule.onNodeWithTag("chat_send").performClick()
        awaitTag("chat_ai_preview_create")
        composeRule.onNodeWithTag("chat_ai_preview_create").assertTextContains("かいものリスト", substring = true)
        assertEquals("still nothing written", 0, memoIds().size)
        assertEquals(0, TestAiRuntime.loads)
    }
}

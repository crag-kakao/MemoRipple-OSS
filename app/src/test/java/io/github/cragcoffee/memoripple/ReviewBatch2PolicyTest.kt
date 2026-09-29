package io.github.cragcoffee.memoripple

import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.PinnedTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review Batch 2 (human brief 2026-09-22): 修正 on the Think result, 「＋ テンプレートを作成」 in the ＋
 * picker reaching the one editor, template pins as ids in a preference, and 「この会話をメモとして保存」
 * through the ordinary preview and the one confirm — with no runtime, no network, no DAO on any of
 * the four paths, and nothing internal shown or saved.
 */
class ReviewBatch2PolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun dir(path: String): File = File(path).let { if (it.isDirectory) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val main = "src/main/java/io/github/cragcoffee/memoripple"

    @Test
    fun theThinkResultOffersAFixThatAsksTheFieldsQuestionAgainAndRendersAgain() {
        val screen = text("$main/ui/chat/ChatScreen.kt")
        val card = screen.substringAfter("private fun ThinkResultCard(").substringBefore("\n}\n")
        listOf("chat_think_edit", "chat_think_save", "chat_think_done").forEach { assertTrue("the card has $it", card.contains(it)) }
        assertTrue("修正 before 保存 before 終了", card.indexOf("chat_think_edit") < card.indexOf("chat_think_save") && card.indexOf("chat_think_save") < card.indexOf("chat_think_done"))
        assertTrue("the field choice is the existing list, opened for a Think result too", screen.contains("ai.result is AiInteractionResult.WritePreview || ai.result is AiInteractionResult.ThinkResult"))
        // the chooser is a list of pressable rows now (2026-09-24), not blue words
        assertTrue(screen.contains("\"修正する項目を選んでください\""))
        assertTrue("each field is a row, not a link", screen.contains("Role.Button") && screen.contains("KeyboardArrowRight"))
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        val edit = vm.substringAfter("fun editAnswer(").substringBefore("\n    }\n")
        listOf("runTemplate(", "interact(", "execute(", "generate(", "load(", "confirm").forEach { assertFalse("editAnswer touches $it", edit.contains(it)) }
        assertTrue("the edited answer goes through the same advance → completeThink", vm.contains("private suspend fun completeThink("))
    }

    @Test
    fun thePickerCreatesThroughTheOneEditorAndPinsByIdOnly() {
        val screen = text("$main/ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("chat_template_create") && screen.contains("\"＋ テンプレートを作成\""))
        assertEquals("one template editor in the app", 1, dir("$main/ui").walkTopDown().filter { it.extension == "kt" }.count { it.readText().contains("fun TemplateEditorScreen(") })
        assertTrue("the picker's create goes to the editor route", screen.contains("onCreate = { onClosePicker(); onEditTemplate(null) }"))
        listOf("chat_template_section_pinned", "chat_template_pinned_", "chat_template_pin_", "chat_template_unpin_", "\"ピン留め\"", "\"ピン留めを外す\"").forEach { assertTrue("pins: $it", screen.contains(it)) }
        assertTrue("the sections in the brief's order", screen.indexOf("chat_template_section_pinned") < screen.indexOf("chat_template_section_recent") && screen.indexOf("chat_template_section_recent") < screen.indexOf("chat_template_folder_record"))
        val settings = text("$main/data/SettingsRepository.kt")
        assertTrue("ids in a preference (a newline-joined list, so the pin order is kept)", settings.contains("stringPreferencesKey(\"chat_pinned_template_ids\")"))
        assertFalse("no template content in the preference", settings.contains("chat_pinned_templates_json"))
        assertFalse("the pins are not a Room table", text("$main/data/AppDatabase.kt").contains("pinned_template"))
        assertTrue(text("$main/data/AppDatabase.kt").contains("version = 29"))
        assertFalse("the pins are a UI preference, outside the portable backup", text("$main/backup/BackupDtos.kt").contains("pinnedTemplate"))
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("fun togglePinTemplate("))
    }

    @Test
    fun pinsAreIdsResolvedAgainstTheTemplatesAndNeverShownTwice() {
        val a = MemoTemplate("a", "A", "x"); val b = MemoTemplate("b", "B", "x"); val c = MemoTemplate("c", "C", "x")
        assertEquals(listOf(b, a), PinnedTemplates.resolve(listOf("b", "gone", "a"), listOf(a, b, c)))
        assertEquals("an orphan id is ignored", emptyList<MemoTemplate>(), PinnedTemplates.resolve(listOf("gone"), listOf(a)))
        assertEquals("pinned rows leave the other sections", listOf(c), PinnedTemplates.without(listOf(a, b, c), setOf("a", "b")))
        assertEquals(setOf("a"), PinnedTemplates.toggle(emptySet(), "a"))
        assertEquals(emptySet<String>(), PinnedTemplates.toggle(setOf("a"), "a"))
        assertEquals("no duplicate", setOf("a", "b"), PinnedTemplates.toggle(setOf("a", "b"), "c").let { PinnedTemplates.toggle(it, "c") })
    }

    @Test
    fun theConversationIsSavedThroughThePreviewAndTheOneConfirmWithNoModel() {
        val screen = text("$main/ui/chat/ChatScreen.kt")
        assertTrue("the entry is in the overflow, not beside the input", screen.contains("chat_save_conversation") && screen.contains("\"この会話をメモとして保存\""))
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        val save = vm.substringAfter("fun saveConversationAsMemo(").substringBefore("\n    }\n")
        assertTrue("the body is the deterministic export", save.contains("ConversationExport."))
        assertTrue("the ticket comes from the orchestrator's preview", save.contains("previewMemo("))
        listOf("execute(", "confirm", "interact(", "Dao", "insert(", "append(", "create(").forEach { assertFalse("the save touches $it", save.contains(it)) }
        val orchestrator = text("$main/domain/ai/AiOrchestrator.kt")
        val preview = orchestrator.substringAfter("override suspend fun previewMemo(").substringBefore("\n    }\n")
        listOf("runtime", "load(", "generate(", "thermal", "availability", "confirm", "executor.execute").forEach { assertFalse("previewMemo touches $it", preview.contains(it)) }
        assertTrue("the same policy, the same ticket", preview.contains("ExecutionPolicy.decide(") && preview.contains("PendingWrite(decision)"))
        assertEquals("still exactly one confirm() call", 1, Regex("\\.confirm\\(").findAll(orchestrator).count())
        val export = text("$main/domain/ai/conversation/ConversationExport.kt")
        listOf("LocalModelRuntime", "Dao", "java.net", "DocumentAccess", "timing", "AiTiming").forEach { assertFalse("the export touches $it", export.contains(it)) }
        assertFalse("the view model reaches no runtime, network or DAO", listOf("LocalModelRuntime", "Dao", "java.net", "DocumentAccess", "data.ai").any { vm.contains(it) })
    }

    @Test
    fun noNewLogLineCarriesContent() {
        val forbidden = listOf("title", "content", ".text", "message.", "transcript", "results.", "summary", "askedText", "userText", "DocumentRef", ".id}", "template.name", "values[", "label", "question", "answer", "body")
        val files = listOf(dir("$main/domain/ai"), dir("$main/domain/memos"), dir("$main/ui/chat"), dir("$main/ui/settings"), dir("$main/backup")).flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() }
        files.forEach { f ->
            f.readText().lines().filter { it.contains("Log.") || it.contains("onNote(\"") }.forEach { line ->
                forbidden.forEach { token -> assertFalse("${f.name}: a log line carries $token → $line", line.contains(token)) }
            }
        }
    }
}

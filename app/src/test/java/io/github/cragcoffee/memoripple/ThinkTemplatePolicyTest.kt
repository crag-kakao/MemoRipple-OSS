package io.github.cragcoffee.memoripple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Think templates (docs/THINK_TEMPLATES.md, human brief 2026-09-22) — the rules the sources keep:
 * Think is a *flow* of a template, never an action; it has no execution authority — the result
 * is rendered by the chat's deterministic engine, no runtime, no network, no summary model call;
 * a save is the user's tap and goes down the existing CREATE path (preview → the one confirm);
 * the picker groups 記録 / 整理・壁打ち / 探す; the editor offers 整理する and shows no THINK; the
 * backup carries the flow as a defaulted word an older reader ignores; nothing internal is logged.
 */
class ThinkTemplatePolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun dir(path: String): File = File(path).let { if (it.isDirectory) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val main = "src/main/java/io/github/cragcoffee/memoripple"

    @Test
    fun thinkIsAFlowNotAnActionAndTheStartersAreCodeAndNothingElse() {
        val model = text("$main/domain/memos/MemoTemplate.kt")
        assertEquals("the three actions stay the three actions", "enum class TemplateAction { CREATE, SEARCH, APPEND }", Regex("enum class TemplateAction \\{[^}]*\\}").find(model)!!.value)
        assertTrue(model.contains("enum class TemplateFlow { RECORD, THINK }"))
        assertTrue("the flow defaults", model.contains("val flow: TemplateFlow = TemplateFlow.RECORD"))
        val starters = text("$main/domain/memos/StarterTemplates.kt")
        listOf("thinkTodayTasks", "thinkIdea", "thinkPlan", "thinkProject").forEach { assertTrue("starter $it", starters.contains("val $it = MemoTemplate(")) }
        assertTrue("the picker's sections come from the template, not from a list of ids", text("$main/domain/memos/ThinkTemplates.kt").contains("fun sectionOf("))
        val memos = dir("$main/domain/memos").walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.joinToString("\n")
        listOf("javax.script", "Runtime.getRuntime", "java.net", "HttpURLConnection", "LocalModelRuntime", "AiOrchestrator", "Dao", "androidx.room").forEach { assertFalse("the template domain reaches $it", memos.contains(it)) }
    }

    @Test
    fun theResultIsRenderedByTheChatsEngineNeverByAModelAndNeverWritten() {
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("ThinkTemplates.result("))
        val think = vm.substringAfter("private suspend fun completeThink(").substringBefore("\n    }\n")
        listOf("runTemplate(", "ask(", "interact(", "execute(", "generate(", "load(", "confirm").forEach { assertFalse("the Think completion touches $it", think.contains(it)) }
        assertTrue("the result is remembered as recent", think.contains("recent?.record("))
        assertFalse("the view model reaches no runtime, network or DAO", listOf("LocalModelRuntime", "Dao", "java.net", "DocumentAccess", "data.ai").any { vm.contains(it) })
        // the save is the user's tap, and it goes down the CREATE path — the orchestrator's runTemplate, then the one confirm
        val save = vm.substringAfter("fun saveThinkResult(").substringBefore("\n    }\n")
        assertTrue(save.contains("executeTemplate(") && save.contains("save = true"))
        assertFalse("a Think result never writes by itself", save.contains("confirmWrite(") || save.contains("execute("))
        val orchestrator = text("$main/domain/ai/AiOrchestrator.kt")
        assertTrue("the result type exists beside the others", orchestrator.contains("data class ThinkResult("))
        assertFalse("the orchestrator never makes one", orchestrator.substringAfter("data class ThinkResult(").contains("AiInteractionResult.ThinkResult("))
        assertTrue("the runner asks every question of a Think template before a save can render", orchestrator.contains("TemplateFlow.THINK"))
        assertEquals("still exactly one confirm() call", 1, Regex("\\.confirm\\(").findAll(orchestrator).count())
        val runner = orchestrator.substringAfter("internal object TemplateRunner")
        listOf("runtime", "load(", "generate(", "thermal", "availability", "Network", "URL", "http").forEach { assertFalse("the runner touches $it", runner.contains(it)) }
        assertFalse("the session is never saved", Regex("savedStateHandle\\?\\.set\\(\"(session|template|think)").containsMatchIn(vm))
        assertEquals("only the draft is saved", setOf("KEY_INPUT"), Regex("const val (KEY_[A-Z_]+) = ").findAll(vm).map { it.groupValues[1] }.toSet())
    }

    @Test
    fun theScreenShowsTheResultWithSaveAndDoneAndGroupsThePicker() {
        val screen = text("$main/ui/chat/ChatScreen.kt")
        listOf("chat_think_result", "chat_think_save", "chat_think_done", "メモとして保存").forEach { assertTrue("the chat has $it", screen.contains(it)) }
        listOf("chat_template_folder_record", "chat_template_folder_think", "chat_template_folder_search").forEach { assertTrue("the picker has $it", screen.contains(it)) }
        listOf("\"記録\"", "\"整理・壁打ち\"", "\"探す\"").forEach { assertTrue("the picker says $it", screen.contains(it)) }
        assertFalse("no flat all-templates section", screen.contains("chat_template_section_all"))
        assertTrue(text("$main/ui/chat/AiWording.kt").contains("整理すると、こんな内容です。"))
        val shown = Regex("Text\\(\"([^\"]+)\"").findAll(screen).map { it.groupValues[1] }.toList()
        assertTrue("no THINK on screen", shown.none { it.contains("THINK") || it.contains("RECORD") })
    }

    @Test
    fun theEditorOffersToOrganiseWithoutATechnicalWordAndTheDayIsInsertable() {
        val editor = text("$main/ui/settings/TemplateEditorScreen.kt")
        assertTrue(editor.contains("\"整理する\""))
        assertTrue("a chip for the flow", editor.contains("template_editor_action_THINK") || editor.contains("template_editor_flow_think"))
        assertTrue("the day can be inserted by its label", editor.contains("今日の日付"))
        val shown = Regex("Text\\(\"([^\"]+)\"").findAll(editor).map { it.groupValues[1] }.toList() + Regex("label = \\{ Text\\(\"([^\"]+)\"").findAll(editor).map { it.groupValues[1] }.toList()
        listOf("THINK", "RECORD", "CREATE", "SEARCH", "APPEND", "flow", "{{").forEach { word -> assertTrue("the editor shows the word $word: ${shown.filter { it.contains(word) }}", shown.none { it.contains(word) }) }
    }

    @Test
    fun theBackupCarriesTheFlowAsADefaultedWordAtFormat19AndTheFileCarriesIt() {
        val dtos = text("$main/backup/BackupDtos.kt")
        assertTrue("Backup 20 since content blocks (2026-09-24)", dtos.contains("BACKUP_FORMAT_VERSION = 23"))
        val dto = dtos.substringAfter("data class TemplateBackupDto(").substringBefore("\n)")
        assertTrue("a defaulted word an older 19 reader ignores", dto.contains("val flow: String = \"record\""))
        val mapper = text("$main/backup/BackupMapper.kt")
        assertTrue(mapper.contains("\"think\"") && mapper.contains("flow"))
        assertTrue("Room 26 (content blocks, 2026-09-24)", text("$main/data/AppDatabase.kt").contains("version = 29"))
        assertTrue("the template file's version is unchanged — an older reader ignores the flow key", text("$main/domain/memos/TemplateFile.kt").contains("FILE_FORMAT_VERSION = 1"))
    }

    @Test
    fun noThinkLogLineCarriesAQuestionAnAnswerOrTheResult() {
        val forbidden = listOf("title", "content", ".text", "message.", "transcript", "results.", "summary", "askedText", "userText", "DocumentRef", ".id}", "template.name", "values[", "label", "question", "answer", "result.text", "rendered.")
        val files = listOf(dir("$main/domain/ai"), dir("$main/domain/memos"), dir("$main/ui/chat"), dir("$main/ui/settings"), dir("$main/backup")).flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() }
        files.forEach { f ->
            f.readText().lines().filter { it.contains("Log.") || it.contains("onNote(\"") }.forEach { line ->
                forbidden.forEach { token -> assertFalse("${f.name}: a log line carries $token → $line", line.contains(token)) }
            }
        }
    }
}

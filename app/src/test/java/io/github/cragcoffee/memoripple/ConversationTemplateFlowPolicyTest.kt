package io.github.cragcoffee.memoripple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Template = conversation script (docs/CHAT_UI_TEMPLATE_V2.md §16, human brief 2026-09-21): a
 * template runs as one question at a time inside the conversation — never as a form; the user
 * answers through the message input or the chips under the question; the session is ephemeral;
 * a field carries a question of its own; the six starters ask the brief's questions.
 */
class ConversationTemplateFlowPolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val main = "src/main/java/io/github/cragcoffee/memoripple"

    @Test
    fun aFieldCarriesAQuestionAndAScriptEngineOrdersThem() {
        val model = text("$main/domain/memos/MemoTemplate.kt")
        assertTrue("TemplateField.question", model.substringAfter("data class TemplateField(").substringBefore("\n)").contains("val question: String = \"\""))
        val script = text("$main/domain/memos/TemplateScript.kt")
        assertTrue(script.contains("object TemplateScript"))
        listOf("AskTarget", "Ask(", "Ready", "fun next(", "fun questionOf(").forEach { assertTrue("the script has $it", script.contains(it)) }
        assertTrue("a field without a question is asked by its label", script.contains("を入力してください") || script.contains("を教えてください"))
        val starters = text("$main/domain/memos/StarterTemplates.kt")
        listOf(
            "今日の良かったことは？", "うまくいかなかったことは？", "明日やることは？",
            "会議名は？", "参加者は？", "何について話しましたか？", "何が決まりましたか？", "次にやることは？",
            "どんなアイデアですか？", "何がきっかけでしたか？", "どこが面白いと思いますか？", "次に何を試しますか？",
            "どのプロジェクトですか？", "今日やったことは？", "困っていることはありますか？",
        ).forEach { assertTrue("starter question $it", starters.contains(it)) }
        assertTrue("at most twelve starters", Regex("MAX_STARTERS = ([0-9]+)").find(starters)!!.groupValues[1].toInt() <= 12)
    }

    @Test
    fun theChatRunsATemplateAsQuestionsNeverAsAForm() {
        val screen = text("$main/ui/chat/ChatScreen.kt")
        assertFalse("no field form in the chat", screen.contains("TemplateFormCard") || screen.contains("chat_template_form\"") || screen.contains("chat_template_field_"))
        listOf("chat_answer_options", "chat_answer_option_", "chat_answer_skip", "chat_answer_cancel", "chat_answer_pick_date", "chat_target_candidate", "chat_ai_preview_edit", "chat_edit_field_").forEach {
            assertTrue("the flow has $it", screen.contains(it))
        }
        assertTrue("the transcript breathes under the top bar", screen.contains("contentPadding = PaddingValues(top"))
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        listOf("data class TemplateSessionState(", "fun answerCurrent(", "fun answerOption(", "fun skipCurrent(", "fun cancelSession(", "fun editAnswer(").forEach { assertTrue("the view model has $it", vm.contains(it)) }
        assertFalse("the session is never saved", Regex("savedStateHandle\\?\\.set\\(\"(session|template)").containsMatchIn(vm))
        assertEquals("only the draft is saved", setOf("KEY_INPUT"), Regex("const val (KEY_[A-Z_]+) = ").findAll(vm).map { it.groupValues[1] }.toSet())
        assertFalse("the view model reaches no runtime, network or DAO", listOf("LocalModelRuntime", "Dao", "java.net", "DocumentAccess", "data.ai").any { vm.contains(it) })
        // the setup card offers the templates first
        val card = screen.substringAfter("private fun AiUnavailableCard").substringBefore("\n}")
        assertTrue(card.contains("chat_ai_use_templates") && card.contains("chat_ai_open_settings"))
        assertTrue("timing belongs to an AI answer only", vm.contains("onTiming") && !vm.substringAfter("fun runTemplate(").substringBefore("fun openConsumed").contains("timing ="))
    }

    @Test
    fun theEditorAsksForAQuestionAndPreviewsTheOrder() {
        val editor = text("$main/ui/settings/TemplateEditorScreen.kt")
        assertTrue(editor.contains("template_editor_field_question_"))
        assertTrue(editor.contains("\"質問\"") || editor.contains("\"質問（"))
        assertTrue("the order of questions is previewed", editor.contains("template_editor_questions"))
        val shown = Regex("Text\\(\"([^\"]+)\"").findAll(editor).map { it.groupValues[1] }.toList() + Regex("label = \\{ Text\\(\"([^\"]+)\"").findAll(editor).map { it.groupValues[1] }.toList()
        listOf("CREATE", "SEARCH", "APPEND", "MEMO", "OUTLINE", "JOURNAL", "TEXT", "MULTILINE", "DATE", "CHOICE", "BOOLEAN", "キー", "JSON", "schema", "{{").forEach { word ->
            assertTrue("the editor shows the word $word", shown.none { it.contains(word) })
        }
    }

    @Test
    fun theQuestionTravelsInTheFileAndTheBackupAndTheStandingRulesHold() {
        val dtos = text("$main/backup/BackupDtos.kt")
        assertTrue("backup 19 carries the question", dtos.substringAfter("data class TemplateFieldBackupDto(").substringBefore("\n)").contains("val question: String = \"\""))
        assertTrue(dtos.contains("BACKUP_FORMAT_VERSION = 23"))
        assertTrue(text("$main/backup/BackupMapper.kt").contains("question"))
        assertTrue(text("$main/data/AppDatabase.kt").contains("version = 29"))
        val orchestrator = text("$main/domain/ai/AiOrchestrator.kt")
        val runner = orchestrator.substringAfter("internal object TemplateRunner")
        listOf("runtime", "load(", "generate(", "thermal", "availability", "Network", "URL", "http").forEach { assertFalse("the runner touches $it", runner.contains(it)) }
        assertEquals("still exactly one confirm() call", 1, Regex("\\.confirm\\(").findAll(orchestrator).count())
        val doc = listOf(File("../docs/CHAT_UI_TEMPLATE_V2.md"), File("docs/CHAT_UI_TEMPLATE_V2.md")).first { it.isFile }.readText()
        assertTrue("the principles are written down", doc.contains("Obsidian") && doc.contains("one question at a time"))
        val forbidden = listOf("title", "content", ".text", "message.", "transcript", "results.", "summary", "askedText", "userText", "DocumentRef", ".id}", "template.name", "values[", "label", "question", "answer")
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        vm.lines().filter { it.contains("Log.") }.forEach { line -> forbidden.forEach { token -> assertFalse("a log line carries $token → $line", line.contains(token)) } }
    }
}

package io.github.cragcoffee.memoripple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Chat UI redesign + Template v2 RED (docs/CHAT_UI_TEMPLATE_V2.md): the chat is a conversation
 * and nothing else — no search / AI switch, no chips, no fixed-action buttons; the plus button
 * opens templates; a template is declarative (no script, no expression) and runs through the same
 * preview / confirmation boundary; the AI stays optional; the standing rules hold.
 */
class ChatUiTemplateV2PolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun dir(path: String): File = File(path).let { if (it.isDirectory) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val main = "src/main/java/io/github/cragcoffee/memoripple"

    @Test
    fun theChatTopCarriesNoSearchControlsAndNoFixedActions() {
        val screen = text("$main/ui/chat/ChatScreen.kt")
        listOf("chat_mode_switch", "chat_mode_search", "chat_mode_ai", "chat_chip_", "chat_kind_", "chat_command_new_memo", "chat_command_new_outline", "chat_command_new_journal", "chat_command_open_calendar", "定型操作", "メモ・日記を検索…", "SegmentedButton").forEach {
            assertFalse("the chat still carries $it", screen.contains(it))
        }
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        listOf("ChatDatePreset", "ChatMode", "toggleKind", "selectDatePreset", "createTodayJournal", "KEY_QUERY", "KEY_KINDS", "KEY_DATE_PRESET", "KEY_MODE").forEach {
            assertFalse("the view model still carries $it", vm.contains(it))
        }
        assertEquals("only the draft is saved", setOf("KEY_INPUT"), Regex("const val (KEY_[A-Z_]+) = ").findAll(vm).map { it.groupValues[1] }.toSet())
        listOf("chat_plus", "chat_send", "chat_input", "chat_history", "chat_new_conversation", "chat_overflow", "chat_template_picker", "chat_template_create", "chat_answer_options").forEach {
            assertTrue("the chat has $it", screen.contains(it))
        }
        assertTrue("the empty state asks quietly", screen.contains("MemoRippleに何を頼みますか"))
    }

    @Test
    fun theAiIsOptionalTheTemplatePathNeedsNoModelAndTheSetupCardNeverReplacesTheScreen() {
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("fun runTemplate("))
        assertTrue("a free-text ask still reads the availability", vm.contains("refreshAvailability("))
        val screen = text("$main/ui/chat/ChatScreen.kt")
        assertTrue("the hint, not a replacement", screen.contains("chat_ai_hint"))
        val orchestrator = text("$main/domain/ai/AiOrchestrator.kt")
        assertTrue(orchestrator.contains("suspend fun runTemplate("))
        val runBody = orchestrator.substringAfter("override suspend fun runTemplate(").substringBefore("override suspend fun execute(")
        listOf("thermal", "runtime()", "rt.load(", "gen.generate(", "availability()").forEach { assertFalse("a template run touches $it", runBody.contains(it)) }
        assertEquals("still exactly one confirm() call", 1, Regex("\\.confirm\\(").findAll(orchestrator).count())
        assertEquals("one execute in the view model", 1, Regex("\\.execute\\(").findAll(vm).count())
    }

    @Test
    fun templatesAreDeclarativeAndNeverExecutable() {
        val memos = dir("$main/domain/memos").walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.joinToString("\n")
        listOf("javax.script", "ScriptEngine", "Runtime.getRuntime", "ProcessBuilder", "java.net.URL", "HttpURLConnection", "java.io.File(", "eval(", "Class.forName", "WebView", "loadUrl").forEach {
            assertFalse("templates bring $it", memos.contains(it))
        }
        assertTrue(memos.contains("{{"))
        assertTrue("one placeholder shape only", memos.contains("Regex(") && memos.contains("[a-z0-9_]"))
        assertTrue(memos.contains("FORMAT_VERSION"))
        assertTrue(text("$main/domain/memos/MemoTemplate.kt").contains("enum class TemplateAction { CREATE, SEARCH, APPEND }"))
        assertTrue(text("$main/domain/memos/MemoTemplate.kt").contains("enum class TemplateFieldType { TEXT, MULTILINE, DATE, CHOICE, BOOLEAN }"))
    }

    @Test
    fun theStorageAndTheBackupAreUnchangedRoom25Backup18() {
        val db = text("$main/data/AppDatabase.kt")
        assertTrue("templates stay in DataStore; no Room change", db.contains("version = 29"))
        assertFalse(db.contains("Template"))
        assertTrue(text("$main/backup/BackupDtos.kt").contains("BACKUP_FORMAT_VERSION = 23"))
        val dto = text("$main/backup/BackupDtos.kt").substringAfter("data class TemplateBackupDto(").substringBefore("\n)")
        assertEquals("the format-18 template DTO fields lead, unchanged (format 19 adds the v2 parts after them — Template first-class, 2026-09-21)", listOf("id", "name", "body"), Regex("val ([a-zA-Z]+):").findAll(dto).map { it.groupValues[1] }.toList().take(3))
        val repo = text("$main/data/TemplateRepository.kt")
        assertTrue(repo.contains("ignoreUnknownKeys = true"))
        assertFalse("no second store", repo.contains("import androidx.room") || repo.contains("Dao"))
    }

    @Test
    fun theTemplateFileIsVersionedValidatedAndPlainJson() {
        val f = text("$main/domain/memos/TemplateFile.kt")
        assertTrue(f.contains("memoripple_templates"))
        assertTrue(f.contains("FILE_FORMAT_VERSION = 1"))
        assertTrue("import validates every template", f.contains("TemplateValidation.problems("))
        assertTrue(f.contains("ignoreUnknownKeys = true"))
        val ui = dir("$main/ui").walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.joinToString("\n")
        assertTrue("import / export reach the settings through SAF", ui.contains("TemplateFile.export(") && ui.contains("TemplateFile.import("))
    }

    @Test
    fun theStandingRulesStillHold() {
        val intents = Regex("^    ([A-Z_]+),$", RegexOption.MULTILINE).findAll(text("$main/domain/ai/IntentProposal.kt").substringAfter("enum class AiIntent").substringBefore("}")).map { it.groupValues[1] }.toList()
        assertEquals(listOf("SEARCH", "OPEN", "CREATE", "APPEND", "USE_TEMPLATE", "UNKNOWN"), intents)
        assertEquals("v1", Regex("PROMPT_VERSION = \"([^\"]+)\"").find(text("$main/domain/ai/runtime/PromptAssets.kt"))!!.groupValues[1])
        val gradle = text("build.gradle.kts")
        assertTrue(gradle.contains("versionCode = 5") && gradle.contains("versionName = \"1.1.0\""))
        assertEquals(7, Regex("uses-permission android:name=\"android\\.permission\\.").findAll(text("src/main/AndroidManifest.xml")).count())
        val settings = text("$main/data/SettingsRepository.kt")
        assertEquals(setOf("ai_selected_model_id"), Regex("\"(ai_[a-z_]+)\"").findAll(settings).map { it.groupValues[1] }.toSet())
        // the search backend is untouched: the boundary and its query type
        assertTrue(text("$main/domain/documents/DocumentAccess.kt").contains("data class DocumentQuery("))
        val forbidden = listOf("title", "content", ".text", "message.", "transcript", "results.", "summary", "askedText", "userText", "DocumentRef", ".id}", "anchor.", "template.name", "values[")
        val files = listOf(dir("$main/domain/ai"), dir("$main/domain/memos"), dir("$main/data/ai"), dir("$main/ui/chat"), dir("$main/ui/settings")).flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() } + file("$main/MemoRippleApplication.kt")
        files.forEach { f ->
            f.readText().lines().filter { it.contains("Log.") || it.contains("onNote(\"") }.forEach { line ->
                forbidden.forEach { token -> assertFalse("${f.name}: a log line carries $token → $line", line.contains(token)) }
            }
        }
    }
}

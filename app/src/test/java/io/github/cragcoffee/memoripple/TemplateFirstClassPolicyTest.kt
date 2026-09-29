package io.github.cragcoffee.memoripple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "AI optional, Template first-class" (docs/CHAT_UI_TEMPLATE_V2.md §14, human brief 2026-09-21):
 * the chat is usable with no model through ＋ → template → form → preview → confirmation; six
 * built-in starters; recent templates as id + time; an editor that shows no key, schema or
 * action word; a backup that carries a v2 template whole (format 19); the runner never near a
 * runtime; nothing executable in a template.
 */
class TemplateFirstClassPolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun dir(path: String): File = File(path).let { if (it.isDirectory) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val main = "src/main/java/io/github/cragcoffee/memoripple"

    @Test
    fun sixBuiltInStartersLiveInTheDomainAsReadOnlyCode() {
        val starters = text("$main/domain/memos/StarterTemplates.kt")
        assertTrue(starters.contains("object StarterTemplates"))
        assertTrue("at most twelve (four Think starters joined the six)", Regex("MAX_STARTERS = ([0-9]+)").find(starters)!!.groupValues[1].toInt() <= 12)
        listOf("今日の振り返り", "会議メモ", "アイデアメモ", "プロジェクトログ", "今週の記録を探す", "昨日の日記を探す").forEach { assertTrue("starter $it", starters.contains(it)) }
        listOf("starter-daily-review", "starter-meeting-memo", "starter-idea-memo", "starter-project-log", "starter-this-week", "starter-yesterday-journal").forEach { assertTrue("id $it", starters.contains("\"$it\"")) }
        assertTrue("a starter is never written into the store by itself", !text("$main/MemoRippleApplication.kt").contains("StarterTemplates.all.forEach") && !text("$main/data/TemplateRepository.kt").contains("StarterTemplates"))
        assertTrue("the AI's template lookup sees the starters too", text("$main/data/TemplateLookupAdapter.kt").contains("StarterTemplates"))
    }

    @Test
    fun recentTemplatesAreIdsAndTimesOnlyInTheDataStore() {
        val domain = text("$main/domain/memos/RecentTemplates.kt")
        assertTrue(domain.contains("MAX_RECENT = 5"))
        val record = domain.substringAfter("data class RecentTemplate(").substringBefore(")")
        assertEquals("an id and a time — no body, no values", listOf("templateId", "lastUsedAt"), Regex("val ([a-zA-Z]+):").findAll(record).map { it.groupValues[1] }.toList())
        val data = text("$main/data/RecentTemplateRepository.kt")
        assertTrue(data.contains("recent_templates"))
        assertFalse("no Room for recents", data.contains("androidx.room") || data.contains("Dao"))
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        assertTrue("a run records the template as recent", vm.contains("recent") && vm.contains(".record("))
    }

    @Test
    fun thePickerIsRecentAllCreateAndTheFormSpeaksNoSchema() {
        val screen = text("$main/ui/chat/ChatScreen.kt")
        listOf("chat_template_section_recent", "chat_template_folder_record", "chat_template_folder_think", "chat_template_folder_search", "chat_template_recent_", "chat_template_item_", "chat_template_create", "chat_template_manage", "chat_template_starter_label").forEach {
            assertTrue("the picker has $it", screen.contains(it))
        }
        listOf("最近使ったテンプレート", "記録", "整理・壁打ち", "探す", "テンプレートを作成").forEach { assertTrue(screen.contains(it)) }
        // §16: a template asks one question at a time — the chips under the question, never a form
        listOf("chat_answer_option_TODAY", "chat_answer_option_true", "chat_answer_skip", "chat_answer_cancel").forEach { assertTrue("the flow has $it", screen.contains(it)) }
        listOf("TODAY / YESTERDAY / 2026-09-21", "targetSpec", "schema", "JSON", "キー", "TemplateFormCard").forEach { assertFalse("the chat shows $it", screen.contains(it)) }
        assertTrue("a field without a question is asked by its label", text("$main/domain/memos/TemplateScript.kt").contains("を入力してください"))
        // free text with no model: the card says so and offers the setup and the templates
        assertTrue(screen.contains("chat_ai_use_templates"))
        assertTrue(text("$main/ui/chat/AiWording.kt").contains("自由な文章での操作にはLocal AIモデルが必要です"))
        assertTrue("the empty state says the two entrances", screen.contains("＋からテンプレート"))
        // a deterministic suggestion, never a run: the user taps it and the form opens
        assertTrue(screen.contains("chat_template_suggestion") && screen.contains("chat_template_suggestion_use"))
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("suggestion"))
        assertFalse("a suggestion never runs a template", vm.substringAfter("fun suggest").substringBefore("fun ").contains("runTemplate("))
    }

    @Test
    fun theEditorIsStepwiseAndNeverShowsAKeyOrAnActionWord() {
        val editor = text("$main/ui/settings/TemplateEditorScreen.kt")
        listOf("template_editor_step_", "template_editor_next", "template_editor_prev", "template_editor_insert_field", "template_editor_insert_", "template_editor_preview", "template_editor_field_up_", "template_editor_field_down_", "template_editor_field_remove_", "template_editor_add_field", "template_editor_save").forEach {
            assertTrue("the editor has $it", editor.contains(it))
        }
        assertFalse("no key input", editor.contains("template_editor_field_key_"))
        listOf("基本情報", "操作", "入力項目", "内容", "確認").forEach { assertTrue("step $it", editor.contains("\"$it\"")) }
        listOf("作成する", "探す", "追記する", "メモ", "アウトライン", "日記", "1行", "複数行", "日付", "選択", "はい・いいえ").forEach { assertTrue("label $it", editor.contains("\"$it\"")) }
        val shown = Regex("Text\\(\"([^\"]+)\"").findAll(editor).map { it.groupValues[1] }.toList() + Regex("label = \\{ Text\\(\"([^\"]+)\"").findAll(editor).map { it.groupValues[1] }.toList()
        listOf("CREATE", "SEARCH", "APPEND", "MEMO", "OUTLINE", "JOURNAL", "TEXT", "MULTILINE", "DATE", "CHOICE", "BOOLEAN", "キー", "JSON", "schema", "targetSpec", "{{").forEach { word ->
            assertTrue("the editor shows the word $word: ${shown.filter { it.contains(word) }}", shown.none { it.contains(word) })
        }
        assertTrue("the body is shown with labels, not keys", text("$main/domain/memos/TemplateBodyDisplay.kt").contains("object TemplateBodyDisplay"))
        val templates = text("$main/ui/settings/PhraseLibraryScreens.kt")
        assertTrue(templates.contains("テンプレートを書き出す") && templates.contains("テンプレートを読み込む"))
        assertFalse(templates.contains("Text(\"JSON"))
        assertTrue("starters can be copied into the editor", templates.contains("settings_starter_"))
    }

    @Test
    fun theBackupCarriesAV2TemplateWholeAtFormat19AndStillReadsEveryOlderFile() {
        val dtos = text("$main/backup/BackupDtos.kt")
        assertTrue(dtos.contains("BACKUP_FORMAT_VERSION = 23"))
        assertTrue(dtos.contains("MIN_SUPPORTED_BACKUP_FORMAT_VERSION = 1"))
        val dto = dtos.substringAfter("data class TemplateBackupDto(").substringBefore("\n)")
        val fields = Regex("val ([a-zA-Z]+):").findAll(dto).map { it.groupValues[1] }.toList()
        assertEquals("the three format-18 fields first, unchanged", listOf("id", "name", "body"), fields.take(3))
        listOf("description", "action", "documentKind", "fields", "search", "target").forEach { assertTrue("the DTO carries $it", it in fields) }
        assertTrue("every new part defaults, so an 18 file decodes", Regex("val (description|action|documentKind|fields|search|target|targetQuestion|flow):[^=\\n]*= ").findAll(dto).count() == 8)
        assertFalse("the backup DTO is its own wire type, not the domain class", dtos.contains("domain.memos.TemplateField\n") || dto.contains("MemoTemplate"))
        assertTrue("the validator judges a template by its definition", text("$main/backup/BackupValidator.kt").contains("TemplateValidation.problems("))
        assertTrue("the mapper reads the format-18 shape and the v2 shape alike", text("$main/backup/BackupMapper.kt").contains("fun toTemplates("))
        assertTrue("Room 26 (content blocks, 2026-09-24)", text("$main/data/AppDatabase.kt").contains("version = 29"))
        assertEquals(setOf("ai_selected_model_id"), Regex("\"(ai_[a-z_]+)\"").findAll(text("$main/data/SettingsRepository.kt")).map { it.groupValues[1] }.toSet())
    }

    @Test
    fun aTemplateRunNeverTouchesARuntimeANetworkOrCode() {
        val orchestrator = text("$main/domain/ai/AiOrchestrator.kt")
        val runner = orchestrator.substringAfter("internal object TemplateRunner")
        listOf("runtime", "load(", "generate(", "thermal", "availability", "Network", "URL", "http").forEach { assertFalse("the runner touches $it", runner.contains(it)) }
        val runBody = orchestrator.substringAfter("override suspend fun runTemplate(").substringBefore("override suspend fun execute(")
        listOf("thermal", "runtime()", "rt.load(", "gen.generate(", "availability()", "deviceSupported").forEach { assertFalse("runTemplate touches $it", runBody.contains(it)) }
        val memos = dir("$main/domain/memos").walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.joinToString("\n")
        listOf("javax.script", "ScriptEngine", "Runtime.getRuntime", "ProcessBuilder", "java.net", "HttpURLConnection", "java.io.File(", "eval(", "Class.forName", "WebView", "loadUrl", "Intent(").forEach {
            assertFalse("templates bring $it", memos.contains(it))
        }
        assertEquals("still exactly one confirm() call", 1, Regex("\\.confirm\\(").findAll(orchestrator).count())
        val forbidden = listOf("title", "content", ".text", "message.", "transcript", "results.", "summary", "askedText", "userText", "DocumentRef", ".id}", "template.name", "values[", "label")
        val files = listOf(dir("$main/domain/ai"), dir("$main/domain/memos"), dir("$main/data"), dir("$main/ui/chat"), dir("$main/ui/settings"), dir("$main/backup")).flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() }
        files.forEach { f ->
            f.readText().lines().filter { it.contains("Log.") || it.contains("onNote(\"") }.forEach { line ->
                forbidden.forEach { token -> assertFalse("${f.name}: a log line carries $token → $line", line.contains(token)) }
            }
        }
    }
}

package io.github.cragcoffee.memoripple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「この会話からテンプレートを作成」 (human brief 2026-09-22) — the sources keep: the draft is made by
 * the domain rule from the transcript's kinds, never by a model; it is handed to the one template
 * editor and saved only by the editor; the chat writes no template; no runtime, network or DAO on
 * the path; the entry lives in the overflow; Room 25 and Backup 19 unchanged.
 */
class ConversationTemplateDraftPolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun dir(path: String): File = File(path).let { if (it.isDirectory) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val main = "src/main/java/io/github/cragcoffee/memoripple"

    @Test
    fun theDraftIsARuleOverTheTranscriptsKindsAndTouchesNothingElse() {
        val draft = text("$main/domain/ai/conversation/ConversationTemplateDraft.kt")
        assertTrue("questions are chosen by kind first", draft.contains("ChatMessageKind.TEXT") && draft.contains("ChatRole.ASSISTANT"))
        assertTrue("the flow defaults to THINK", draft.contains("flow = TemplateFlow.THINK"))
        listOf("LocalModelRuntime", "AiOrchestrator", "Dao", "java.net", "DocumentAccess", "TemplateRepository", "TemplateAction.APPEND", "TemplateAction.SEARCH", "default = ").forEach { assertFalse("the draft touches $it", draft.contains(it)) }
    }

    @Test
    fun theChatHandsTheDraftToTheOneEditorAndWritesNoTemplate() {
        val screen = text("$main/ui/chat/ChatScreen.kt")
        assertTrue("the entry is in the overflow", screen.contains("chat_template_from_conversation") && screen.contains("\"この会話からテンプレートを作成\""))
        assertTrue("the safe message when there is nothing to draft", screen.contains("テンプレートにできる質問が見つかりませんでした"))
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        val fn = vm.substringAfter("fun templateDraftFromConversation(").substringBefore("\n    }\n")
        assertTrue(fn.contains("ConversationTemplateDraft.from("))
        listOf("save(", "repository", "Repository", "execute(", "runTemplate(", "interact(", "previewMemo(").forEach { assertFalse("the draft path touches $it", fn.contains(it)) }
        assertFalse("the chat view model still reaches no store, runtime, network or DAO", listOf("TemplateRepository", "LocalModelRuntime", "Dao", "java.net", "DocumentAccess", "data.ai").any { vm.contains(it) })
        val app = text("$main/ui/MemoRippleApp.kt")
        assertTrue("the draft rides a hand-off to the editor route, not a route string", app.contains("templateDraftHandoff.offer(") && app.contains("Routes.templateEditor(null)"))
        val editor = text("$main/ui/settings/TemplateEditorScreen.kt")
        assertTrue("the route takes the hand-off as its initial template", editor.contains("templateDraftHandoff.take()"))
        assertEquals("still the one editor", 1, dir("$main/ui").walkTopDown().filter { it.extension == "kt" }.count { it.readText().contains("fun TemplateEditorScreen(") })
        assertTrue("saving is the editor's save, as before", editor.contains("onSave = { template -> scope.launch { repository.save(template)"))
    }

    @Test
    fun roomBackupAndTheTemplateFileAreUnchanged() {
        assertTrue(text("$main/data/AppDatabase.kt").contains("version = 29"))
        assertTrue(text("$main/backup/BackupDtos.kt").contains("BACKUP_FORMAT_VERSION = 23"))
        assertTrue(text("$main/domain/memos/TemplateFile.kt").contains("FILE_FORMAT_VERSION = 1"))
    }
}

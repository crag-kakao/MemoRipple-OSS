package io.github.cragcoffee.memoripple

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The chat's folder and the inset input bar (human decision 2026-09-22): the destination is the
 * user's choice above the input — one chat-wide preference, never the model's word, never a Room
 * column; it reaches a create only through the Resolver / the runner / previewMemo into
 * `DocumentCreate`; the preview names it; ＋ and 送る sit inside the input pill.
 */
class ChatDestinationPolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val main = "src/main/java/io/github/cragcoffee/memoripple"

    @Test
    fun theDestinationIsTheUsersOnePreferenceAndNeverTheModels() {
        val settings = text("$main/data/SettingsRepository.kt")
        assertTrue("one chat-wide preference, like the last conversation", settings.contains("longPreferencesKey(\"chat_create_folder_id\")"))
        assertTrue("Room 26 (content blocks, 2026-09-24)", text("$main/data/AppDatabase.kt").contains("version = 29"))
        assertFalse("no folder column on a chat table", text("$main/data/ChatHistoryEntities.kt").contains("folderId"))
        val proposal = text("$main/domain/ai/IntentProposal.kt")
        assertFalse("the model has no word for a folder", proposal.contains("folder"))
        assertFalse(text("$main/domain/ai/runtime/IntentProposalParser.kt").contains("folder"))
        val resolver = text("$main/domain/ai/Resolver.kt")
        assertTrue("the destination enters a create only here", resolver.contains("destination: CreateDestination?") && resolver.contains("DocumentCreate.Memo(destination?.folderId)"))
        assertTrue("a journal keeps no folder", resolver.contains("DocumentCreate.Journal(") && !resolver.contains("Journal(destination"))
        val orchestrator = text("$main/domain/ai/AiOrchestrator.kt")
        listOf("suspend fun interact(", "suspend fun runTemplate(", "suspend fun previewMemo(").forEach { fn ->
            assertTrue("$fn takes the destination", orchestrator.substringAfter(fn).substringBefore("): AiInteractionResult").contains("destination: CreateDestination?"))
        }
        assertTrue("the preview names the folder", text("$main/domain/ai/ExecutionPolicy.kt").contains("val folderName: String? = null"))
        assertTrue(text("$main/ui/chat/ChatScreen.kt").contains("\"保存先\""))
    }

    @Test
    fun theChipIsAboveTheInputAndTheBarIsOnePill() {
        val screen = text("$main/ui/chat/ChatScreen.kt")
        listOf("chat_folder_chip", "chat_folder_choice_none", "chat_folder_choice_", "\"フォルダを選択\"").forEach { assertTrue("the chat has $it", screen.contains(it)) }
        val bar = screen.substringAfter("private fun InputBar(").substringBefore("\n}\n")
        val pill = bar.indexOf("surfaceContainerHigh")
        assertTrue("＋ sits inside the pill", bar.indexOf("chat_plus") > pill)
        assertTrue("送る sits inside the pill, after the text", bar.indexOf("chat_send") > bar.indexOf("chat_input"))
        assertTrue("the chip row comes before the pill", bar.indexOf("chat_folder_chip") in 0 until pill)
        assertFalse("no microphone", bar.contains("Mic"))
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("fun selectDestination(") && vm.contains("destination = ") )
        assertFalse("the view model still reaches no store, runtime, network or DAO", listOf("FolderRepository", "TemplateRepository", "LocalModelRuntime", "Dao", "java.net", "DocumentAccess", "data.ai").any { vm.contains(it) })
        assertTrue("the choices come through a domain port", text("$main/domain/folders/FolderChoices.kt").contains("interface DocumentFolderChoices"))
        assertTrue("the one tree walk", text("$main/domain/folders/FolderChoices.kt").contains("FolderTree.flatten("))
        assertTrue("a vanished folder falls back to the root at the boundary too", text("$main/data/documents/RepositoryDocumentAccess.kt").contains("folderDao().findById("))
    }
}

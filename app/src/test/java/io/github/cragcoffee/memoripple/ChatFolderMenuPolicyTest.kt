package io.github.cragcoffee.memoripple

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The folder chip's three remarks (human, 2026-09-22 21:00): the menu's first row reads
 * 「フォルダなし（通常）」; the chip itself carries no × — a folder is changed by choosing another, so
 * nothing sits next to the name to be hit by accident; and a folder can be made from the menu,
 * under 「フォルダなし」, without going to the memo wall.
 */
class ChatFolderMenuPolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val main = "src/main/java/io/github/cragcoffee/memoripple"

    @Test
    fun theMenuSaysNormalAndTheChipHasNoClearIcon() {
        val screen = text("$main/ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("\"フォルダなし（通常）\""))
        assertFalse("the old wording is gone", screen.contains("（今まで通り）"))
        assertFalse("no × beside the chip's name", screen.contains("chat_folder_clear"))
        assertFalse(screen.contains("\"フォルダを外す\""))
        assertTrue("the chip still names the folder and opens the menu", screen.contains("chat_folder_chip") && screen.contains("chat_folder_choice_none"))
    }

    @Test
    fun aFolderIsMadeFromTheMenuThroughTheWallsOwnRepository() {
        val screen = text("$main/ui/chat/ChatScreen.kt")
        listOf("chat_folder_new", "chat_folder_new_dialog", "chat_folder_new_input", "chat_folder_new_confirm", "\"＋ 新しいフォルダ\"").forEach {
            assertTrue("the menu has $it", screen.contains(it))
        }
        assertTrue("it sits under フォルダなし", screen.indexOf("chat_folder_choice_none") < screen.indexOf("chat_folder_new"))
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        val create = vm.substringAfter("fun createDestinationFolder(").substringBefore("\n    }\n")
        assertTrue("the wall's own rule for a name", create.contains("FolderName.normalize("))
        assertTrue("made through the port, then chosen", create.contains("folders.create(") && create.contains("store.set("))
        assertFalse("the chat still reaches no repository or DAO", listOf("FolderRepository", "Dao", "AppDatabase").any { vm.contains(it) })
        val port = text("$main/domain/folders/FolderChoices.kt")
        assertTrue("the port can make one", port.contains("suspend fun create(name: String): Long?"))
        assertTrue("the data side is the wall's repository", text("$main/data/ChatHistoryRepository.kt").contains("folders.create(name, null)"))
    }
}

package io.github.cragcoffee.memoripple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UI/UX review, 2026-09-23 (the user's two remarks on the real screen):
 *
 * 1. the one-line hint above the input — the one shown when no model can answer free text —
 *    carries a **×** that closes it **for good**: one preference, written once, never written
 *    back, so the line never returns. Nothing is lost by closing it: the model name in the top
 *    bar still says 「AIモデルなし」 and leads to the models, and a free-text send with no model
 *    still answers with the setup card (the decided behaviour of §16.63);
 * 2. the conversation reads at the body size of the design system — 16 sp, not 14 — for the
 *    user's bubble and MemoRipple's lines alike.
 *
 * The chat keeps its boundaries: the screen takes a flag and a callback, the view model writes
 * through a domain store, and no DAO, Room or route string comes near either.
 */
class ChatHintAndTranscriptPolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val main = "src/main/java/io/github/cragcoffee/memoripple"

    @Test
    fun theHintCarriesADismissTheScreenAsksTheStateAndTheViewModelWritesOnePreference() {
        val screen = text("$main/ui/chat/ChatScreen.kt")
        assertTrue("the hint has a × to close it", screen.contains("chat_ai_hint_dismiss"))
        assertTrue("the × says what it does", screen.contains("この案内を閉じる"))
        assertTrue("the hint row asks whether it was closed", screen.contains("aiHintDismissed"))
        assertTrue("and hands the tap up", screen.contains("onDismissAiHint"))

        val vm = text("$main/ui/chat/ChatViewModel.kt")
        assertTrue("the state carries the flag", vm.contains("val aiHintDismissed: Boolean = false"))
        assertTrue("one action", vm.contains("fun dismissAiHint("))
        assertTrue("through the domain store", vm.contains("ChatHintStore"))
        listOf("dataStore", "DataStore", "SettingsRepository", "Dao", "Entity").forEach {
            assertFalse("the chat view model reaches for $it", vm.contains(it))
        }
    }

    @Test
    fun theDismissIsPermanentWrittenOnceAndNeverUndone() {
        val store = text("$main/domain/ai/conversation/ChatMessage.kt")
        assertTrue("the domain names the store", store.contains("interface ChatHintStore"))
        assertTrue("it is read as a flag", store.contains("val aiHintDismissed: Flow<Boolean>"))
        assertEquals(
            "the store can only close the hint — there is no re-open",
            1,
            Regex("suspend fun dismissAiHint\\(\\)").findAll(store).count(),
        )
        assertFalse("nothing in the domain re-opens it", store.contains("fun setAiHintDismissed("))

        val settings = text("$main/data/SettingsRepository.kt")
        assertTrue("one preference key", settings.contains("""stringPreferencesKey("chat_ai_hint_dismissed")"""))
        val write = settings.substringAfter("suspend fun dismissChatAiHint()").substringBefore("\n    }")
        assertTrue("it is written as closed", write.contains("\"true\""))
        listOf("\"false\"", "remove(Keys.CHAT_AI_HINT_DISMISSED)").forEach {
            assertFalse("the dismissal is undone by $it", write.contains(it))
        }
        val everywhere = listOf("$main/data/SettingsRepository.kt", "$main/ui/chat/ChatViewModel.kt", "$main/ui/chat/ChatScreen.kt", "$main/data/ChatHistoryRepository.kt").joinToString("\n") { text(it) }
        assertFalse("nothing writes the hint back on", everywhere.contains("CHAT_AI_HINT_DISMISSED] = \"false\""))
    }

    @Test
    fun theConversationReadsAtTheBodySizeOfTheDesignSystem() {
        val screen = text("$main/ui/chat/ChatScreen.kt")
        val row = screen.substringAfter("private fun TranscriptRow(").substringBefore("\n/**")
        assertEquals("both the user's bubble and MemoRipple's line use bodyLarge", 2, Regex("typography\\.bodyLarge").findAll(row).count())
        assertFalse("nothing in the transcript stays at bodyMedium", row.contains("typography.bodyMedium"))
        val theme = text("$main/ui/theme/Theme.kt")
        val bodyLarge = theme.substringAfter("bodyLarge = TextStyle(").substringBefore(")")
        assertTrue("bodyLarge is the 16 sp step of the system", bodyLarge.contains("fontSize = 16.sp"))
        assertTrue("with the line height Japanese needs", bodyLarge.contains("lineHeight = 26.sp"))
    }

    @Test
    fun theStorageContractIsUntouched() {
        assertTrue("Room 29", text("$main/data/AppDatabase.kt").contains("version = 29"))
        assertTrue("Backup 23", text("src/main/java/io/github/cragcoffee/memoripple/backup/BackupDtos.kt").contains("const val BACKUP_FORMAT_VERSION = 23"))
        assertFalse("a device preference stays out of the portable backup", text("src/main/java/io/github/cragcoffee/memoripple/backup/BackupDtos.kt").contains("chat_ai_hint_dismissed"))
    }
}

package io.github.cragcoffee.memoripple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Phase 8 RED (docs/AI_CONVERSATION_HISTORY.md): what conversation history may and may not be —
 * three chat tables and nothing else new in Room 25 with a real migration; nothing raw persisted;
 * no summarisation, RAG, embedding or vector store; prompt v1 and the grammar byte-identical;
 * the portable backup untouched at format 18; no conversation content in any log line; no
 * history authority over execution (the confirm boundary unchanged).
 */
class AiConversationPolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun dir(path: String): File = File(path).let { if (it.isDirectory) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val main = "src/main/java/io/github/cragcoffee/memoripple"

    /** The git blob id of a file (SHA-1 over "blob <size>\0<bytes>"), so an asset can be pinned to main's blob. */
    private fun blobId(path: String): String {
        val bytes = file(path).readBytes()
        val header = ("blob " + bytes.size + Char(0)).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-1").apply { update(header); update(bytes) }.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun roomIs25WithExactlyTheThreeChatTablesAndARealMigration() {
        val db = text("$main/data/AppDatabase.kt")
        assertTrue(db.contains("version = 29"))
        listOf("ChatConversationEntity", "ChatMessageEntity", "ChatResultRefEntity").forEach { assertTrue("entity $it registered", db.contains("$it::class")) }
        assertTrue("24→25 is a migration, not a rebuild", db.contains("MIGRATION_24_25"))
        val migration = db.substringAfter("val MIGRATION_24_25").substringBefore("val MIGRATION_")
        listOf("chat_conversations", "chat_messages", "chat_result_refs").forEach { assertTrue("migration creates $it", migration.contains("CREATE TABLE IF NOT EXISTS `$it`")) }
        assertFalse("no destructive migration anywhere", dir(main).walkTopDown().any { it.extension == "kt" && it.readText().contains("fallbackToDestructiveMigration") })
        assertFalse("chat tables never touch the document tables", migration.contains("DROP TABLE") || migration.contains("ALTER TABLE `memos`") || migration.contains("ALTER TABLE `diary_entries`"))
        assertTrue("25 is the last step of the shared chain", db.substringAfter("val MIGRATIONS").contains("MIGRATION_24_25"))
    }

    @Test
    fun nothingRawIsPersistedAndTheHistoryHoldsOnlyWhatTheUserSaw() {
        val entities = text("$main/data/ChatHistoryEntities.kt")
        listOf("raw", "json", "prompt", "confidence", "proposal", "developer", "stack", "pending", "ticket").forEach {
            assertFalse("the chat entities carry no $it column", Regex("val [a-zA-Z]*$it[a-zA-Z]*:", RegexOption.IGNORE_CASE).containsMatchIn(entities))
        }
        val roles = text("$main/domain/ai/conversation/ChatMessage.kt")
        assertTrue(roles.contains("enum class ChatRole { USER, ASSISTANT }"))
        assertFalse(roles.contains("SYSTEM"))
        // the safe context stores kind + id + the shown title; never a result_N label as an external id
        assertFalse("result_N is request-scoped, never a stored id", Regex("result_[0-9N]").containsMatchIn(entities))
        assertTrue(entities.contains("documentKind") && entities.contains("documentId"))
    }

    @Test
    fun noSummarisationRagEmbeddingOrVectorStoreAndAStrictWindow() {
        val sources = (dir("$main/domain/ai").walkTopDown() + dir("$main/ui/chat").walkTopDown()).filter { it.extension == "kt" }.map { it.readText() }.joinToString("\n") +
            listOf("ChatHistoryRepository.kt", "ChatDao.kt", "ChatHistoryEntities.kt").joinToString("\n") { text("$main/data/$it") }
        listOf("summarize(", "summarise(", "Summariz", "embedding", "Embedding", "vector", "Vector", "chunking", "retrieval", "Retrieval", "cosine", "faiss", "sqlite-vec", "ObjectBox").forEach {
            assertFalse("history brings $it", sources.contains(it))
        }
        assertFalse("history brings RAG", Regex("\\bRAG\\b").containsMatchIn(sources))
        val gradle = text("build.gradle.kts")
        listOf("androidx.paging", "objectbox", "tensorflow", "onnx", "sqlite-vec", "lucene").forEach { assertFalse("new dependency $it", gradle.contains(it)) }
        val budget = text("$main/domain/ai/conversation/ActiveContextBudget.kt")
        assertTrue(budget.contains("MAX_MESSAGES") && budget.contains("MAX_CHARS") && budget.contains("MAX_MESSAGE_CHARS"))
    }

    @Test
    fun promptV1AndTheGrammarAreByteIdenticalAndTheComposerOnlyAddsToTheUserTurn() {
        assertTrue("prompt v1 is main's blob", blobId("src/main/assets/ai/intent_system.v1.txt").startsWith("be38fb26"))
        assertTrue("the grammar is main's blob", blobId("src/main/assets/ai/intent_proposal.gbnf").startsWith("8788591f"))
        assertEquals("v1", Regex("PROMPT_VERSION = \"([^\"]+)\"").find(text("$main/domain/ai/runtime/PromptAssets.kt"))!!.groupValues[1])
        val composer = text("$main/domain/ai/conversation/ConversationPromptComposer.kt")
        assertFalse("the composer never rewrites the system prompt", composer.contains("intentSystemPrompt") || composer.contains("systemPrompt ="))
        val orchestrator = text("$main/domain/ai/AiOrchestrator.kt")
        assertEquals("still exactly one confirm() call", 1, Regex("\\.confirm\\(").findAll(orchestrator).count())
        val parseAt = orchestrator.indexOf("ConversationReferent.parse(")
        assertTrue("referents are read after the model and before the resolver", parseAt > orchestrator.indexOf("gen.generate(") && parseAt < orchestrator.lastIndexOf("resolver.resolve("))
    }

    @Test
    fun thePortableBackupStaysAtFormat18AndCarriesNoChatTable() {
        val dtos = text("$main/backup/BackupDtos.kt")
        assertTrue(dtos.contains("BACKUP_FORMAT_VERSION = 23"))
        val backup = dir("$main/backup").walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.joinToString("\n")
        listOf("chat_", "ChatConversation", "ChatMessage", "ChatResultRef", "chatDao", "conversation").forEach { assertFalse("the backup mentions $it", backup.contains(it)) }
        val export = dir("$main/portableexport").walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.joinToString("\n")
        listOf("ChatConversation", "ChatMessage", "chatDao", "chat_messages").forEach { assertFalse("the portable export mentions $it", export.contains(it)) }
        assertTrue(text("src/main/AndroidManifest.xml").contains("android:allowBackup=\"false\""))
    }

    @Test
    fun noConversationContentReachesALogLineAndTheKeysStayTheFive() {
        val forbidden = listOf("title", "content", ".text", "message.", "transcript", "results.", "summary", "askedText", "userText", "DocumentRef", ".id}", "anchor.")
        val files = listOf(dir("$main/domain/ai"), dir("$main/data/ai"), dir("$main/ui/chat"), dir("$main/ui/settings")).flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() } +
            file("$main/MemoRippleApplication.kt") + file("$main/data/ChatHistoryRepository.kt")
        files.forEach { f ->
            f.readText().lines().filter { it.contains("Log.") || it.contains("onNote(\"") }.forEach { line ->
                forbidden.forEach { token -> assertFalse("${f.name}: a log line carries $token → $line", line.contains(token)) }
            }
        }
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        assertEquals(setOf("KEY_INPUT") /* Chat UI redesign (human decision 2026-09-21): the chat is a conversation; only the draft is saved */, Regex("const val (KEY_[A-Z_]+) = ").findAll(vm).map { it.groupValues[1] }.toSet())
        assertFalse("nothing pending is ever saved", vm.contains("KEY_PENDING") || vm.contains("KEY_PREVIEW") || vm.contains("KEY_RESULT"))
        val settings = text("$main/data/SettingsRepository.kt")
        assertTrue("the current conversation is one light preference", settings.contains("\"chat_last_conversation_id\""))
        assertEquals(setOf("ai_selected_model_id"), Regex("\"(ai_[a-z_]+)\"").findAll(settings).map { it.groupValues[1] }.toSet())
    }

    @Test
    fun theStandingRulesStillHold() {
        val intents = Regex("^    ([A-Z_]+),$", RegexOption.MULTILINE).findAll(text("$main/domain/ai/IntentProposal.kt").substringAfter("enum class AiIntent").substringBefore("}")).map { it.groupValues[1] }.toList()
        assertEquals(listOf("SEARCH", "OPEN", "CREATE", "APPEND", "USE_TEMPLATE", "UNKNOWN"), intents)
        val gradle = text("build.gradle.kts")
        assertTrue(gradle.contains("versionCode = 5") && gradle.contains("versionName = \"1.1.0\""))
        val manifest = text("src/main/AndroidManifest.xml")
        assertEquals(7, Regex("uses-permission android:name=\"android\\.permission\\.").findAll(manifest).count())
        assertFalse("no background agent", dir("$main/domain/ai").walkTopDown().any { it.extension == "kt" && (it.readText().contains("WorkManager") || it.readText().contains("AlarmManager")) })
    }
}

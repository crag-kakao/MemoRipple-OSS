package io.github.cragcoffee.memoripple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Local LLM Phase 6 (docs/AI_TARGET_RESOLUTION.md): the deterministic assist has no execution
 * authority and uses no fuzzy, semantic or vector matching and no model confidence; prompt v1 is
 * untouched; the confirm boundary, the intent set, the search mode, the model management and the
 * persistence rules are what Phase 3–5 left them.
 */
class AiTargetResolutionPolicyTest {
    private fun dir(path: String): File = File(path).let { if (it.isDirectory) it else File("app/$path") }
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun sources(path: String) = dir(path).walkTopDown().filter { it.extension == "kt" }.associate { it.name to it.readText() }

    @Test
    fun noFuzzySemanticOrVectorMatchingAndNoConfidenceAnywhereInTheAiDomain() {
        val ai = sources("src/main/java/io/github/cragcoffee/memoripple/domain/ai")
        ai.forEach { (name, text) ->
            listOf("Levenshtein", "editDistance", "edit distance", "JaroWinkler", "cosine", "embedding", "Embedding", "vector", "Vector", "semantic search", "SemanticSearch", "similarity", "romaji", "phonetic").forEach {
                assertFalse("$name mentions $it", text.contains(it))
            }
        }
        val extractor = ai.getValue("TargetCandidateExtractor.kt")
        assertFalse(extractor.contains("confidence"))
        assertTrue("evidence, not a score", extractor.contains("CandidateSource"))
        assertFalse("no platform, no data layer", extractor.contains("import android") || extractor.contains("memoripple.data."))
        assertFalse("the extractor searches nothing and refers to nothing", extractor.contains("DocumentAccess") || extractor.contains("DocumentRef") || extractor.contains("suspend"))
        val gradle = file("build.gradle.kts").readText()
        listOf("lucene", "sqlite-vec", "objectbox", "tensorflow", "onnx", "mediapipe").forEach { assertFalse("new matching dependency $it", gradle.contains(it)) }
    }

    @Test
    fun promptV1AndTheGrammarAreByteIdenticalToPhaseZero() {
        val asset = file("src/main/assets/ai/intent_system.v1.txt").readBytes()
        val source = File("tools/llm-eval/prompts/v1/intent_system.txt").let { if (it.isFile) it else File("../tools/llm-eval/prompts/v1/intent_system.txt") }
        assertTrue("prompt v1 source exists", source.isFile)
        assertTrue("prompt v1 unchanged", asset.contentEquals(source.readBytes()))
        val grammar = file("src/main/assets/ai/intent_proposal.gbnf").readBytes()
        val grammarSource = File("tools/llm-eval/grammar/intent_proposal.gbnf").let { if (it.isFile) it else File("../tools/llm-eval/grammar/intent_proposal.gbnf") }
        assertTrue(grammar.contentEquals(grammarSource.readBytes()))
        assertEquals("v1", Regex("PROMPT_VERSION = \"([^\"]+)\"").find(sources("src/main/java/io/github/cragcoffee/memoripple/domain/ai/runtime").getValue("PromptAssets.kt"))!!.groupValues[1])
    }

    @Test
    fun theAssistSitsBeforeTheResolverAndOnlyThereAndTheConfirmBoundaryIsUnchanged() {
        val main = dir("src/main/java/io/github/cragcoffee/memoripple").walkTopDown().filter { it.extension == "kt" }.toList()
        val users = main.filter { it.readText().contains("TargetCandidateExtractor") }.map { it.name }.toSet()
        assertEquals(setOf("TargetCandidateExtractor.kt", "AiOrchestrator.kt"), users)
        val orchestrator = main.single { it.name == "AiOrchestrator.kt" }.readText()
        val assistAt = orchestrator.indexOf("TargetCandidateExtractor.assist(")
        val resolveAt = orchestrator.indexOf("resolver.resolve(")
        assertTrue("assist happens before the resolver", assistAt in 0 until resolveAt)
        assertEquals("one confirm() call", 1, Regex("\\.confirm\\(").findAll(orchestrator).count())
        val confirmCallers = main.filter { it.name != "ExecutionPolicy.kt" && it.readText().contains(".confirm(") }.map { it.name }
        assertEquals(listOf("AiOrchestrator.kt"), confirmCallers)
        // the assist runs only when the model gave no target
        assertTrue(orchestrator.contains("targetRef == null") && orchestrator.contains("targetName == null"))
    }

    @Test
    fun intentsSearchModeModelManagementAndPersistenceAreUnchanged() {
        val intents = Regex("^    ([A-Z_]+),$", RegexOption.MULTILINE).findAll(file("src/main/java/io/github/cragcoffee/memoripple/domain/ai/IntentProposal.kt").readText().substringBefore("companion object")).map { it.groupValues[1] }.toList()
        assertEquals(listOf("SEARCH", "OPEN", "CREATE", "APPEND", "USE_TEMPLATE", "UNKNOWN"), intents)
        val chat = sources("src/main/java/io/github/cragcoffee/memoripple/ui/chat")
        assertFalse("the search field is gone from the chat (Chat UI redesign, 2026-09-21)", chat.getValue("ChatScreen.kt").contains("メモ・日記を検索…"))
        val vm = chat.getValue("ChatViewModel.kt")
        assertEquals(setOf("KEY_INPUT") /* Chat UI redesign (human decision 2026-09-21): the chat is a conversation; only the draft is saved */, Regex("const val (KEY_[A-Z_]+) = ").findAll(vm).map { it.groupValues[1] }.toSet())
        assertFalse("no conversation history", vm.contains("ConversationHistory") || vm.contains("chatHistory") || vm.contains("messages ="))
        val db = file("src/main/java/io/github/cragcoffee/memoripple/data/AppDatabase.kt").readText()
        assertTrue(db.contains("version = 29"))   // Phase 8: the three chat history tables, human-approved 2026-09-20
        listOf("chat_sessions", "ai_", "ModelEntity", "ai_draft", "pending_write").forEach { assertFalse(db.contains(it)) }
        assertTrue(file("src/main/java/io/github/cragcoffee/memoripple/backup/BackupDtos.kt").readText().contains("BACKUP_FORMAT_VERSION = 23"))
        val settings = file("src/main/java/io/github/cragcoffee/memoripple/data/SettingsRepository.kt").readText()
        assertEquals(setOf("ai_selected_model_id"), Regex("\"(ai_[a-z_]+)\"").findAll(settings).map { it.groupValues[1] }.toSet())
        assertTrue(sources("src/main/java/io/github/cragcoffee/memoripple/domain/ai/models").keys.containsAll(listOf("CatalogEntry.kt", "ModelManager.kt")))
    }
}

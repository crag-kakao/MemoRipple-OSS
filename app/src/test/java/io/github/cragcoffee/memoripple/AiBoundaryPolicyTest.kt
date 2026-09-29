package io.github.cragcoffee.memoripple

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RED 39–41: the AI safety boundary is pure domain — no DAO, entity, Room, UI, route string,
 * llama.cpp or bench dependency (docs/AI_SAFE_INTENT_PIPELINE.md).
 */
class AiBoundaryPolicyTest {
    private val dir = sequenceOf(File("src/main/java/io/github/cragcoffee/memoripple/domain/ai"), File("app/src/main/java/io/github/cragcoffee/memoripple/domain/ai")).first { it.isDirectory }

    private fun sources(): Map<String, String> {
        val files = dir.listFiles { f -> f.extension == "kt" }!!.associate { it.name to it.readText() }
        assertTrue("domain/ai exists", files.isNotEmpty())
        return files
    }

    @Test
    fun theAiDomainImportsNoDaoEntityRoomUiOrRuntime() {
        sources().forEach { (name, text) ->
            listOf(
                "import io.github.cragcoffee.memoripple.data.", "Dao", "Entity", "androidx.room", "androidx.compose", "androidx.lifecycle",
                "io.github.cragcoffee.memoripple.ui.", "Routes.", "NavController", "navigate(", "editor/", "outliner/", "journal/", "memo-templates",
                "llama", "llmbench", "ggml", "System.loadLibrary", "DataStore",
            ).forEach { forbidden -> assertFalse("$name mentions $forbidden", text.contains(forbidden)) }
        }
    }

    @Test
    fun theAiDomainSpeaksOnlyDocumentBoundaryTypes() {
        val all = sources().values.joinToString("\n")
        listOf("DocumentAccess", "DocumentRef", "DocumentSummary", "DocumentQuery", "DocumentDateRange", "TimeProvider", "MemoTemplate").forEach {
            assertTrue("domain/ai uses $it", all.contains(it))
        }
        // the only writes are the boundary's create and append; no other write verb exists in the package
        assertFalse(all.contains("delete("))
        assertFalse(all.contains("replace("))
        assertFalse(all.contains("rename("))
    }

    @Test
    fun theChatUiReachesThePipelineThroughTheOrchestratorOnlyNeverAStageOfIt() {
        // Phase 1 and 2 kept Chat v0 away from domain/ai; Phase 3 connects it through one door.
        val chat = File("src/main/java/io/github/cragcoffee/memoripple/ui/chat").let { if (it.isDirectory) it else File("app/src/main/java/io/github/cragcoffee/memoripple/ui/chat") }
        val all = chat.listFiles { f -> f.extension == "kt" }!!.joinToString("\n") { it.readText() }
        assertTrue("Chat uses the orchestrator", all.contains("domain.ai.AiOrchestrator"))
        listOf("SemanticValidator", "Resolver(", "ExecutionPolicy", "CommandExecutor", "ConfirmedCommand", "IntentProposal(", "domain.ai.runtime.LocalModelRuntime").forEach {
            assertFalse("Chat calls a pipeline stage directly: $it", all.contains(it))
        }
    }
}

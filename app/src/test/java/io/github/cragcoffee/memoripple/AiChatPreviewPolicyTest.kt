package io.github.cragcoffee.memoripple

import io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult
import io.github.cragcoffee.memoripple.domain.ai.AiResultContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Local LLM Phase 3 (docs/AI_CHAT_PREVIEW.md): the チャット screen reaches the AI path through
 * the orchestrator and its result types only; it knows no generator, resolver, executor,
 * runtime, engine, model file, DAO, entity, Room, DataStore or route. What the model said, what
 * was resolved and what is previewed live in memory for one request — never in saved state,
 * DataStore or Room — and no chat or AI table exists.
 */
class AiChatPreviewPolicyTest {
    private fun dir(path: String): File = File(path).let { if (it.isDirectory) it else File("app/$path") }
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }

    private fun chatSources(): Map<String, String> =
        dir("src/main/java/io/github/cragcoffee/memoripple/ui/chat").listFiles { f -> f.extension == "kt" }!!.associate { it.name to it.readText() }

    @Test
    fun theChatUiTalksToTheOrchestratorAndItsResultsOnly() {
        val all = chatSources().values.joinToString("\n")
        listOf("AiOrchestrator", "AiInteractionResult", "CommandPreview", "AiProgress").forEach {
            assertTrue("chat uses $it", all.contains(it))
        }
        chatSources().forEach { (name, text) ->
            listOf(
                "StructuredIntentGenerator", "LocalIntentPipeline", "Resolver(", "CommandExecutor", "ExecutionPolicy", "ExecutionDecision",
                "ConfirmedCommand", ".confirm(", "LocalModelRuntime", "ModelProfiles", "ModelDescriptor", "IntentProposalParser",
                "io.github.cragcoffee.memoripple.data.", "llama", "gguf", "GGUF", "JNI", "loadLibrary", "System.load",
                "DataStore", "androidx.room", "Dao", "Entity", "Routes.", "editor/", "outliner/", "journal/", "memo-templates",
                "NavController", "navigate(", "Serializable", "Parcelable",
            ).forEach { forbidden -> assertFalse("$name mentions $forbidden", text.contains(forbidden)) }
        }
    }

    @Test
    fun theSavedStateCarriesTheModeAndTheInputsNothingTheModelSaidOrResolved() {
        val vm = chatSources().getValue("ChatViewModel.kt")
        val keys = Regex("const val (KEY_[A-Z_]+) = \"([a-zA-Z]+)\"").findAll(vm).map { it.groupValues[1] to it.groupValues[2] }.toMap()
        assertEquals(setOf("KEY_INPUT") /* Chat UI redesign (human decision 2026-09-21): the chat is a conversation; only the draft is saved */, keys.keys)
        // every write to the handle names one of the keys above — no result, preview, context or raw text goes in
        val writes = Regex("savedStateHandle\\?\\.set\\(([A-Z_]+),").findAll(vm).map { it.groupValues[1] }.toSet()
        assertTrue("saved-state writes $writes", writes.isNotEmpty() && writes.all { it in keys.keys })
        listOf("set(\"", "Bundle", "putParcelable", "putSerializable", "AiResultContext", "AiInteractionResult").forEach {
            val hits = Regex("savedStateHandle[^\\n]*" + Regex.escape(it)).findAll(vm).count()
            assertEquals("saved state must not carry $it", 0, hits)
        }
        assertFalse("the result context is not restorable", vm.contains("KEY_CONTEXT") || vm.contains("KEY_RESULT") || vm.contains("KEY_PREVIEW"))
    }

    @Test
    fun theAiTypesAreEphemeralByConstructionNotSerializableOrParcelable() {
        listOf(AiResultContext::class.java, AiInteractionResult.WritePreview::class.java, AiInteractionResult.SearchResults::class.java, AiInteractionResult.Ambiguous::class.java).forEach { c ->
            assertFalse("${c.simpleName} is Serializable", java.io.Serializable::class.java.isAssignableFrom(c))
            assertFalse("${c.simpleName} is Parcelable", c.interfaces.any { it.name.endsWith("Parcelable") })
        }
    }

    @Test
    fun noConversationHistoryAndNoAiTableRoom24Stands() {
        val db = file("src/main/java/io/github/cragcoffee/memoripple/data/AppDatabase.kt").readText()
        assertTrue(db.contains("version = 29"))   // Phase 8: chat_conversations / chat_messages / chat_result_refs are the history (human-approved 2026-09-20)
        listOf("chat_sessions", "ai_sessions", "ai_messages", "ai_drafts", "ai_results", "ChatEntity", "AiEntity", "ModelEntity").forEach {
            assertFalse("AppDatabase mentions $it", db.contains(it))
        }
        // Phase 8 keeps the transcript in Room (chat_messages), so the rule is about DataStore keys: none for a chat / AI history, message, session or draft
        val data = file("src/main/java/io/github/cragcoffee/memoripple/data/SettingsRepository.kt").readText()
        assertFalse("no chat / AI DataStore key", Regex("(?i)\"(chat|ai)_(history|message|session|draft)").containsMatchIn(data))
    }

    @Test
    fun theOrchestratorIsTheOnlyCallerOfTheExecutorAndOnlyForDirectReads() {
        val main = dir("src/main/java/io/github/cragcoffee/memoripple").walkTopDown().filter { it.extension == "kt" }.toList()
        val callers = main.filter { it.readText().contains("CommandExecutor(") || it.readText().contains(": CommandExecutor") }.map { it.name }.toSet()
        assertEquals(setOf("CommandExecutor.kt", "AiOrchestrator.kt", "AiAssembly.kt"), callers)
        val orchestrator = main.single { it.name == "AiOrchestrator.kt" }.readText()
        assertTrue(orchestrator.contains("ExecutionDecision.Direct"))
        // Phase 4: confirm() exists in exactly one place — the orchestrator's execute(PendingWrite) (AiConfirmedWritePolicyTest)
        main.filter { it.name != "ExecutionPolicy.kt" && it.name != "AiOrchestrator.kt" }.forEach { assertFalse("${it.name} calls confirm()", it.readText().contains(".confirm(")) }
    }

    @Test
    fun theNativeEngineIsNeverConstructedBeforeTheCpuFeatureCheck() {
        val app = file("src/main/java/io/github/cragcoffee/memoripple/MemoRippleApplication.kt").readText()
        assertFalse("the application builds the engine through the guarded factory", app.contains("LlamaCppEngine("))
        val constructors = dir("src/main/java/io/github/cragcoffee/memoripple").walkTopDown()
            .filter { it.extension == "kt" && it.readText().contains("LlamaCppEngine(") && it.name != "LlamaCppEngine.kt" }.toList()
        assertEquals(listOf("AiAssembly.kt"), constructors.map { it.name })
        val assembly = constructors.single().readText()
        assertTrue("the feature check precedes the constructor", assembly.indexOf("hasDotProduct") in 0 until assembly.indexOf("LlamaCppEngine("))
    }

    @Test
    fun theChatScreenStillOpensThroughTheNavigatorAndKeepsItsSearchWiring() {
        val app = file("src/main/java/io/github/cragcoffee/memoripple/ui/MemoRippleApp.kt").readText()
        assertTrue(app.contains("ChatRoute(") && app.contains("documentNavigator::open"))
        val screen = chatSources().getValue("ChatScreen.kt")
        assertFalse("the search field is gone from the chat (Chat UI redesign, 2026-09-21)", screen.contains("メモ・日記を検索…"))
        assertTrue("the input is the chat's own (「メッセージを入力」 since the UI review of 2026-09-21)", screen.contains("メッセージを入力"))
        assertTrue("a write preview offers cancel and one confirm (Phase 4)", screen.contains("chat_ai_preview_cancel") && screen.contains("chat_ai_preview_confirm"))
    }
}

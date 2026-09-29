package io.github.cragcoffee.memoripple

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DecisionEngine policy (Phase 3, docs/DECISION_ENGINE.md, human brief 2026-09-23), pinned at the
 * source level:
 *
 * - the engine is pure Kotlin and deterministic: no suspend, no I/O, no DAO / Room / boundary /
 *   platform import, no numeric confidence anywhere in the decision package;
 * - the order in the chat is Fast Path → decide → generation, and the decide door sits before
 *   the resource controller (a CERTAIN decision or a clarification can never acquire);
 * - the clarification is chat-internal and deterministic: its fixed questions carry no technical
 *   word (DecisionEngine, certainty, result_N, anchor, resolver) to the user;
 * - the pending clarification is ephemeral: no new saved-state key, nothing persisted.
 */
class DecisionEnginePolicyTest {
    private fun src(path: String): File = File(path).let { if (it.exists()) it else File("app/$path") }

    private val decisionDir = src("src/main/java/io/github/cragcoffee/memoripple/domain/ai/decision")
    private val orchestrator = src("src/main/java/io/github/cragcoffee/memoripple/domain/ai/AiOrchestrator.kt")
    private val chatViewModel = src("src/main/java/io/github/cragcoffee/memoripple/ui/chat/ChatViewModel.kt")

    @Test
    fun theEngineIsPureAndDeterministic() {
        assertTrue(decisionDir.isDirectory)
        val engine = File(decisionDir, "DecisionEngine.kt").readText()
        assertFalse("the engine never suspends", engine.contains("suspend fun decide"))
        listOf(
            "DocumentAccess", "DocumentSearch", "Resolver", "CommandExecutor", "PendingWrite", "ConfirmedCommand",
            "androidx.", "android.", "Room", "Dao", "DataStore", "SavedStateHandle", "kotlinx.coroutines",
            "Float", "Double", "confidence", "0.5", "score",
        ).forEach { forbidden ->
            assertFalse("DecisionEngine.kt must not carry $forbidden", engine.contains(forbidden))
        }
    }

    @Test
    fun theChatRoutesFastThenDecideThenGeneration() {
        val vm = chatViewModel.readText()
        val fast = vm.indexOf("interactFast")
        val decide = vm.indexOf("interactDecide")
        val generate = vm.indexOf("orchestrator.interact(")
        assertTrue("fast first", fast in 1 until decide)
        assertTrue("decide before the generation route", decide in 1 until generate)
    }

    @Test
    fun theDecideDoorNeverAcquiresTheModel() {
        val text = orchestrator.readText()
        val decideBody = text.substringAfter("override suspend fun interactDecide").substringBefore("override suspend fun completeDecision")
        val completeBody = text.substringAfter("override suspend fun completeDecision").substringBefore("private suspend fun settle")
        listOf(decideBody, completeBody).forEach { body ->
            assertFalse("the decide door acquires nothing", body.contains("acquireForGeneration"))
            assertFalse("the decide door loads nothing", body.contains(".load("))
            assertFalse("the decide door generates nothing", body.contains("generator"))
        }
    }

    @Test
    fun theFixedQuestionsCarryNoTechnicalWording() {
        val engine = File(decisionDir, "DecisionEngine.kt").readText()
        val questions = Regex("\"([^\"]*？)\"").findAll(engine).map { it.groupValues[1] }.toList()
        assertTrue("the fixed questions exist", questions.isNotEmpty())
        questions.forEach { q ->
            listOf("DecisionEngine", "certainty", "result_", "anchor", "resolver", "CERTAIN").forEach { word ->
                assertFalse("a question carries $word: $q", q.contains(word, ignoreCase = true))
            }
        }
    }

    @Test
    fun thePendingClarificationIsEphemeral() {
        val vm = chatViewModel.readText()
        // the one saved key stays the draft input; the pending clarification never reaches the saved state
        assertEquals(setOf("KEY_INPUT"), Regex("const val (KEY_[A-Z_]+) = ").findAll(vm).map { it.groupValues[1] }.toSet())
        assertFalse("the pending clarification is never saved", vm.contains("savedStateHandle?.set(\"clar") || vm.contains("KEY_CLAR"))
    }
}

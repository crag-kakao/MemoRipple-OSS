package io.github.cragcoffee.memoripple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Fast Path's boundaries (docs/CHAT_FAST_PATH.md, human brief 2026-09-22): it shortens only
 * the LLM's proposal generation — never the safety pipeline. The recognizer is a pure rule; the
 * fast route touches no runtime, no thermal, no DAO, no write; the chat tries it before the AI
 * and says nothing about routes to the user; logs carry lengths and route names, never content.
 */
class ChatFastPathPolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun dir(path: String): File = File(path).let { if (it.isDirectory) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val main = "src/main/java/io/github/cragcoffee/memoripple"

    @Test
    fun theRecognizerIsAPureRuleAndTheRouterOnlyRoutes() {
        val fast = dir("$main/domain/ai/fast").walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.joinToString("\n")
        assertTrue(fast.contains("object FastIntentRecognizer") && fast.contains("interface ChatRouter") && fast.contains("sealed interface ChatRoute"))
        listOf("LocalModelRuntime", "StructuredIntentGenerator", "Dao", "androidx.room", "java.net", "DocumentAccess", "CommandExecutor", "ConfirmedCommand", "PendingWrite", "LocalDate.now", "Clock.").forEach {
            assertFalse("the fast package touches $it", fast.contains(it))
        }
        assertTrue("dates stay tokens", fast.contains("DateToken.TODAY") && fast.contains("DateToken.YESTERDAY"))
        assertFalse("no destructive word becomes an intent", fast.contains("DELETE") || fast.contains("削除"))
    }

    @Test
    fun theFastRouteEntersTheSamePipelineAndNeverTheRuntime() {
        val orchestrator = text("$main/domain/ai/AiOrchestrator.kt")
        val fastBody = orchestrator.substringAfter("override suspend fun interactFast(").substringBefore("\n    }\n")
        listOf("rt.load(", "runtime()", ".generate(", "thermal", "availability()", "selection.").forEach { assertFalse("interactFast touches $it", fastBody.contains(it)) }
        assertTrue("the same settled tail as the model's proposal", orchestrator.contains("private suspend fun settle(") && fastBody.contains("settle("))
        assertTrue("run() settles through the same tail", orchestrator.substringAfter("private suspend fun run(").contains("settle("))
        assertEquals("still exactly one confirm() call", 1, Regex("\\.confirm\\(").findAll(orchestrator).count())
        // the resolver's date+kind OPEN is the one capability added, inside the pipeline
        assertTrue(text("$main/domain/ai/Resolver.kt").contains("dateToken") )
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        assertTrue("the chat tries the fast route before the AI", vm.indexOf("interactFast(") in 1 until vm.indexOf("orchestrator.interact("))
        assertFalse("the view model still reaches no runtime, network or DAO", listOf("LocalModelRuntime", "Dao", "java.net", "DocumentAccess", "data.ai").any { vm.contains(it) })
    }

    @Test
    fun theUserNeverSeesARouteAndLogsCarryNoContent() {
        listOf("$main/ui/chat/ChatScreen.kt", "$main/ui/chat/AiWording.kt").forEach { path ->
            val t = text(path)
            listOf("Fast Path", "FastPath", "FAST_", "高速経路", "ファストパス").forEach { assertFalse("$path shows $it", t.contains(it)) }
        }
        val forbidden = listOf("title", "content", ".text", "message.", "transcript", "results.", "summary", "askedText", "userText", "DocumentRef", ".id}", "template.name", "values[", "label", "question", "answer", "body", "query")
        val files = listOf(dir("$main/domain/ai"), dir("$main/ui/chat")).flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() }
        files.forEach { f ->
            f.readText().lines().filter { it.contains("Log.") || it.contains("onNote(\"") }.forEach { line ->
                forbidden.forEach { token -> assertFalse("${f.name}: a log line carries $token → $line", line.contains(token)) }
            }
        }
    }
}

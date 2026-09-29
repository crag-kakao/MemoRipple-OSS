package io.github.cragcoffee.memoripple

import io.github.cragcoffee.memoripple.domain.ai.PendingWrite
import io.github.cragcoffee.memoripple.domain.ai.WriteOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Local LLM Phase 4 (docs/AI_CONFIRMED_WRITE.md): the Human Confirmation boundary. `confirm()` is
 * called in exactly one place in main sources — the orchestrator's `execute(PendingWrite)` — and
 * that method is reached only from the チャット confirmation handler. No model, runtime, generator,
 * resolver, background callback or timer can confirm; nothing about a pending write is persisted.
 */
class AiConfirmedWritePolicyTest {
    private fun dir(path: String): File = File(path).let { if (it.isDirectory) it else File("app/$path") }
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun mainSources(): List<File> = dir("src/main/java/io/github/cragcoffee/memoripple").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun confirmIsCalledInTheOrchestratorsExecuteAndNowhereElse() {
        val callers = mainSources().filter { it.name != "ExecutionPolicy.kt" && it.readText().contains(".confirm(") }.map { it.name }
        assertEquals(listOf("AiOrchestrator.kt"), callers)
        val orchestrator = mainSources().single { it.name == "AiOrchestrator.kt" }.readText()
        assertEquals("one confirm() call", 1, Regex("\\.confirm\\(").findAll(orchestrator).count())
        val executeStart = orchestrator.indexOf("override suspend fun execute(pending: PendingWrite)")
        assertTrue("execute(PendingWrite) exists", executeStart >= 0)
        val confirmAt = orchestrator.indexOf(".confirm(")
        val nextFun = orchestrator.indexOf("\n    override", executeStart + 1).let { if (it < 0) orchestrator.length else it }
        assertTrue("confirm() lives inside execute(PendingWrite)", confirmAt in executeStart until nextFun)
        // the interaction path (interact → run) never confirms: the preview leaves as a ticket only
        val runStart = orchestrator.indexOf("private suspend fun run(")
        val runEnd = orchestrator.indexOf("\n    }", runStart)
        assertFalse(orchestrator.substring(runStart, runEnd).contains("confirm"))
    }

    @Test
    fun noStageBelowTheBoundaryAndNoBackgroundPathCanConfirmOrExecuteAWrite() {
        val below = mainSources().filter {
            it.path.contains("/domain/ai/runtime/") || it.path.contains("/data/ai/") || it.name in setOf("Resolver.kt", "SemanticValidator.kt", "IntentProposal.kt", "AiResultContext.kt")
        }
        assertTrue(below.size >= 8)
        below.forEach { f ->
            val t = f.readText()
            listOf(".confirm(", "ConfirmedCommand", "PendingWrite", "execute(", "documents.append", "documents.create").forEach { s ->
                assertFalse("${f.name} mentions $s", t.contains(s))
            }
        }
        // the application (memory pressure, lifecycle callbacks) never executes a write
        val app = file("src/main/java/io/github/cragcoffee/memoripple/MemoRippleApplication.kt").readText()
        listOf(".execute(", "PendingWrite", ".confirm(").forEach { assertFalse("application: $it", app.contains(it)) }
    }

    @Test
    fun theChatUiConfirmsThroughOneHandlerWithNoTimerAndNoAutomaticPath() {
        val chat = dir("src/main/java/io/github/cragcoffee/memoripple/ui/chat").listFiles { f -> f.extension == "kt" }!!.associate { it.name to it.readText() }
        val vm = chat.getValue("ChatViewModel.kt")
        val screen = chat.getValue("ChatScreen.kt")
        listOf(".confirm(", "ConfirmedCommand", "CommandExecutor", "ExecutionDecision", "ResolvedCommand", "delay(", "Timer", "postDelayed", "android.os.Handler").forEach {
            assertFalse("ChatViewModel mentions $it", vm.contains(it))
            assertFalse("ChatScreen mentions $it", screen.contains(it))
        }
        // exactly one execute call in the view model, inside confirmWrite(), and the screen's confirm button is the only caller
        assertEquals(1, Regex("\\.execute\\(").findAll(vm).count())
        val confirmStart = vm.indexOf("fun confirmWrite(")
        assertTrue(confirmStart >= 0)
        assertTrue(vm.indexOf(".execute(") > confirmStart)
        assertTrue("no execute in init / LaunchedEffect / recomposition", !screen.contains(".execute(") && !vm.substring(0, confirmStart).contains(".execute("))
        assertTrue(screen.contains("chat_ai_preview_confirm"))
        assertTrue("the confirm button is bound to the handler and nothing else", screen.contains("onConfirmWrite = viewModel::confirmWrite") && screen.contains("Button(onClick = onConfirm, enabled = !executing, modifier = Modifier.testTag(\"chat_ai_preview_confirm\"))"))
        assertEquals(1, Regex("chat_ai_preview_confirm").findAll(screen).count())
        assertFalse("no automatic confirmation on render", screen.contains("LaunchedEffect") && screen.substringAfter("LaunchedEffect").substringBefore("}").contains("onConfirmWrite"))
    }

    @Test
    fun nothingAboutAPendingWriteIsSavedAndNoConfirmationTableExists() {
        val vm = dir("src/main/java/io/github/cragcoffee/memoripple/ui/chat").listFiles { f -> f.name == "ChatViewModel.kt" }!!.single().readText()
        val keys = Regex("const val (KEY_[A-Z_]+) = ").findAll(vm).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("KEY_INPUT") /* Chat UI redesign (human decision 2026-09-21): the chat is a conversation; only the draft is saved */, keys)
        listOf("KEY_PENDING", "KEY_PREVIEW", "KEY_TICKET", "KEY_RESULT").forEach { assertFalse(vm.contains(it)) }
        assertFalse(java.io.Serializable::class.java.isAssignableFrom(PendingWrite::class.java))
        assertFalse(java.io.Serializable::class.java.isAssignableFrom(WriteOutcome.Success::class.java))
        val db = file("src/main/java/io/github/cragcoffee/memoripple/data/AppDatabase.kt").readText()
        assertTrue(db.contains("version = 29"))   // Phase 8: chat history tables; still nothing pending or confirmable is stored
        listOf("confirmation", "pending_write", "ai_draft", "ai_history").forEach { assertFalse("AppDatabase mentions $it", db.contains(it)) }
        val backup = file("src/main/java/io/github/cragcoffee/memoripple/backup/BackupDtos.kt").readText()
        assertTrue(backup.contains("BACKUP_FORMAT_VERSION = 23"))
    }

    @Test
    fun theWritePathIsTheOfficialOneAndReadsStayDirect() {
        val orchestrator = mainSources().single { it.name == "AiOrchestrator.kt" }.readText()
        assertTrue(orchestrator.contains("ExecutionDecision.Direct"))
        assertTrue(orchestrator.contains("RequiresConfirmation"))
        assertTrue("the ticket wraps the decision the policy made", orchestrator.contains("class PendingWrite"))
        // no confirm UI on reads: the screen has no confirm for search results or open
        val screen = dir("src/main/java/io/github/cragcoffee/memoripple/ui/chat").listFiles { f -> f.name == "ChatScreen.kt" }!!.single().readText()
        assertFalse(screen.contains("chat_ai_open_confirm") || screen.contains("chat_ai_search_confirm"))
    }
}

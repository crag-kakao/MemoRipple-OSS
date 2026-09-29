package io.github.cragcoffee.memoripple

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AI logging policy (human decision 2026-09-20, docs/AI_TARGET_RESOLUTION.md §4): production logs
 * never carry user content — no target name or candidate text, no memo / journal title, no user
 * input, no append text, no generated body, no raw model output, no DocumentRef or database id.
 * Every log statement and every developer note in the AI sources is scanned for those sources.
 */
class AiLoggingPolicyTest {
    private fun dir(path: String): File = File(path).let { if (it.isDirectory) it else File("app/$path") }
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }

    private val forbiddenInLogs = listOf(
        "userText", "candidate.text", "proposal.text", "proposal.targetName", "targetName", ".raw", "raw)", "askedText", "aiInput",
        "preview", "title", "body", "DocumentRef", ".ref", ".id}", "result.results", "results.", "summary", "text}", "text)",
    )

    private fun logLines(text: String): List<String> =
        text.lines().filter { it.contains("Log.") || it.contains("onNote(\"") || it.contains("log(\"") || it.contains("Timber.") }

    @Test
    fun noAiLogStatementInMainSourcesCarriesUserContent() {
        val files = listOf(
            dir("src/main/java/io/github/cragcoffee/memoripple/domain/ai"),
            dir("src/main/java/io/github/cragcoffee/memoripple/data/ai"),
            dir("src/main/java/io/github/cragcoffee/memoripple/ui/chat"),
            dir("src/main/java/io/github/cragcoffee/memoripple/ui/settings"),
        ).flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() } + file("src/main/java/io/github/cragcoffee/memoripple/MemoRippleApplication.kt")
        var scanned = 0
        files.forEach { f ->
            logLines(f.readText()).forEach { line ->
                scanned++
                forbiddenInLogs.forEach { token -> assertFalse("${f.name}: a log line carries $token → $line", line.contains(token)) }
            }
        }
        assertTrue("the AI code does log something (notes, memory pressure, failures)", scanned >= 3)
    }

    @Test
    fun theTargetAssistNoteIsRedactedToSourcePresenceAndLength() {
        val orchestrator = file("src/main/java/io/github/cragcoffee/memoripple/domain/ai/AiOrchestrator.kt").readText()
        val notes = orchestrator.lines().filter { it.contains("onNote(\"target assist") }
        assertTrue(notes.size >= 2)
        assertTrue(notes.any { it.contains("assistApplied=true") && it.contains("source=\${candidate.source}") && it.contains("candidateLength=\$candidateLength") })
        assertTrue(notes.any { it.contains("assistApplied=false") })
        notes.forEach { assertFalse("the note prints the candidate", it.contains("\${candidate.text}") || it.contains("candidate.text}")) }
        // the domain never formats a raw answer or the user's text into a note either
        assertFalse(orchestrator.lines().any { it.contains("onNote(") && (it.contains("userText") || it.contains(".raw")) })
    }

    @Test
    fun theViewModelLogsOnlyNotesAndFailureStagesNeverTheInputOrTheResult() {
        val vm = file("src/main/java/io/github/cragcoffee/memoripple/ui/chat/ChatViewModel.kt").readText()
        val logs = vm.lines().filter { it.contains("Log.") }
        assertTrue(logs.isNotEmpty())
        logs.forEach { line ->
            listOf("text", "aiInput", "result.preview", "askedText", "outcome.ref", "pendingOpen", "context").forEach { assertFalse("view model logs $it → $line", line.contains(it)) }
        }
        // RuntimeError / Failed details are runtime facts (a stage, an exception name, a byte count), not content: pinned in the orchestrator
        val orchestrator = file("src/main/java/io/github/cragcoffee/memoripple/domain/ai/AiOrchestrator.kt").readText()
        assertTrue(orchestrator.contains("(\${r.raw.length} chars)"))
        assertFalse(orchestrator.contains("\${r.raw}\"") || orchestrator.contains("\${r.raw} "))
    }
}

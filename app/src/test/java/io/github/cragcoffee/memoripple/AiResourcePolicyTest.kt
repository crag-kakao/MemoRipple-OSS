package io.github.cragcoffee.memoripple

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI Resource Controller policy (Phase 2, docs/AI_RESOURCE_CONTROLLER.md, human brief 2026-09-23),
 * pinned at the source level:
 *
 * - the controller owns the whole resource lifecycle — the orchestrator keeps no idle timer and
 *   no runtime field of its own, and the old screen-tied unload (ChatViewModel.onCleared →
 *   release) is gone: keep-warm survives leaving the chat, and only the controller's clock,
 *   memory, thermal or an explicit switch unloads;
 * - the Fast Path never touches the controller: no resource word in `domain/ai/fast`, and
 *   `interactFast` acquires nothing;
 * - the controller knows nothing of the pipeline: no intent, resolver, preview, document or
 *   store import in `domain/ai/resource`; nothing about resources is persisted (process death
 *   restarts Unloaded);
 * - no new manifest permission pays for the power / memory / thermal inputs.
 */
class AiResourcePolicyTest {
    private fun src(path: String): File = File(path).let { if (it.exists()) it else File("app/$path") }

    private val orchestrator = src("src/main/java/io/github/cragcoffee/memoripple/domain/ai/AiOrchestrator.kt")
    private val chatViewModel = src("src/main/java/io/github/cragcoffee/memoripple/ui/chat/ChatViewModel.kt")
    private val fastDir = src("src/main/java/io/github/cragcoffee/memoripple/domain/ai/fast")
    private val resourceDir = src("src/main/java/io/github/cragcoffee/memoripple/domain/ai/resource")
    private val manifest = src("src/main/AndroidManifest.xml")

    @Test
    fun theOrchestratorOwnsNoIdleTimerAndNoRuntimeLifecycleOfItsOwn() {
        val text = orchestrator.readText()
        assertFalse("the idle timer is the controller's", text.contains("idleJob"))
        assertFalse("the delay-based unload moved into the controller", text.contains("delay("))
        assertFalse("AiIdleUnload is gone", text.contains("AiIdleUnload"))
        assertTrue("the orchestrator goes through the controller", text.contains("resources.acquireForGeneration") || text.contains("resources\n        .acquireForGeneration"))
    }

    @Test
    fun theFastPathAcquiresNoResource() {
        fastDir.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            assertFalse("${f.name} must not know the resource controller", text.contains("resource", ignoreCase = true))
        }
        val body = orchestrator.readText().substringAfter("override suspend fun interactFast").substringBefore("private suspend fun settle")
        assertFalse("interactFast acquires nothing", body.contains("resources."))
        assertFalse("interactFast loads nothing", body.contains(".load("))
    }

    @Test
    fun leavingTheChatNoLongerUnloadsTheWarmModel() {
        val text = chatViewModel.readText()
        val cleared = text.substringAfter("override fun onCleared", missingDelimiterValue = "")
        assertFalse("onCleared must not release the model (keep warm; the controller's clock unloads)", cleared.substringBefore("}").contains("release("))
    }

    @Test
    fun theControllerKnowsNoPipelineAndPersistsNothing() {
        assertTrue("domain/ai/resource exists", resourceDir.isDirectory)
        resourceDir.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            listOf(
                "IntentProposal", "Resolver", "CommandPreview", "PendingWrite", "DocumentAccess", "DocumentRef",
                "SemanticValidator", "ExecutionPolicy", "androidx.", "android.", "DataStore", "SavedStateHandle", "Room",
            ).forEach { forbidden ->
                assertFalse("${f.name} imports/naming must not carry $forbidden", text.contains(forbidden))
            }
        }
    }

    @Test
    fun resourceLogLinesCarryCountsAndReasonsOnly() {
        val files = resourceDir.walkTopDown().filter { it.extension == "kt" }.toList()
        val logLines = files.flatMap { it.readText().lines() }.filter { it.contains("log(") || it.contains("Log.") }
        assertTrue("the controller logs its lifecycle", logLines.any { it.contains("AI_RESOURCE") })
        listOf("userText", "prompt", "body", "title", "answer", "message").forEach { token ->
            logLines.forEach { line -> assertFalse("a resource log line carries $token → $line", line.contains(token)) }
        }
    }

    @Test
    fun noNewPermissionPaysForTheResourceInputs() {
        val permissions = Regex("uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(manifest.readText()).map { it.groupValues[1] }.toSet()
        val allowed = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SHORT_SERVICE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
        )
        assertTrue("no permission beyond the standing set: ${permissions - allowed}", (permissions - allowed).isEmpty())
    }
}

package io.github.cragcoffee.memoripple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 7 RED (docs/CHAT_V1_RELEASE_READINESS.md): what a Chat v1 release must be, pinned to the
 * sources — the availability question before an input, every unavailable reason worded, the
 * offline guard, no cleartext or developer path in a release, no automatic retry, the version
 * untouched, the standing AI rules (no history, no destructive verb, Room 24 / Backup 18).
 */
class ChatV1ReleasePolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun dir(path: String): File = File(path).let { if (it.isDirectory) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val main = "src/main/java/io/github/cragcoffee/memoripple"

    @Test
    fun theAiModeAsksForAvailabilityBeforeItOffersAnInput() {
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        val screen = text("$main/ui/chat/ChatScreen.kt")
        val orchestrator = text("$main/domain/ai/AiOrchestrator.kt")
        assertTrue("the orchestrator answers availability without a runtime", orchestrator.contains("suspend fun availability(): ModelAvailability"))
        assertTrue(vm.contains("fun refreshAvailability("))
        assertTrue("the screen renders the unavailable card from the availability, not only from an ask", screen.contains("ModelAvailability.Unavailable"))
        val chatUi = dir("$main/ui/chat").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        listOf("NO_MODEL_CONFIGURED", "MODEL_FILE_MISSING", "MODEL_FILE_CORRUPT", "UNSUPPORTED_DEVICE").forEach { reason ->
            assertTrue("the chat UI words $reason", chatUi.contains("ModelUnavailableReason.$reason"))
        }
        assertTrue("the setup card names the next step", screen.contains("AIモデルを設定"))
        assertTrue("the AI models screen is one tap away from the chat", screen.contains("onOpenAiModels"))
        assertTrue("the availability is checked again when the screen comes back", screen.contains("LifecycleResumeEffect") || screen.contains("LifecycleEventEffect"))
    }

    @Test
    fun aRetryIsTheUsersTapAndNeverALoop() {
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        val screen = text("$main/ui/chat/ChatScreen.kt")
        assertTrue(vm.contains("fun retry("))
        assertFalse("no loop, no timer around an ask", vm.contains("while (") || vm.contains("repeat(") || vm.contains("delay(") || vm.contains("retryCount"))
        assertTrue(screen.contains("chat_ai_retry"))
        assertTrue("a conflict re-previews; it never re-executes", screen.contains("chat_ai_reconfirm"))
        val reconfirmLine = screen.lines().first { it.contains("chat_ai_reconfirm") }
        assertFalse(reconfirmLine.contains("onConfirmWrite"))
        assertEquals("still exactly one confirm() call", 1, Regex("\\.confirm\\(").findAll(text("$main/domain/ai/AiOrchestrator.kt")).count())
    }

    @Test
    fun theModelManagerKnowsOfflineAndCorruptAndTheScreenWordsBoth() {
        val store = text("$main/domain/ai/models/ModelStore.kt")
        val manager = text("$main/domain/ai/models/ModelManager.kt")
        val screen = text("$main/ui/settings/AiModelsScreen.kt")
        assertTrue(store.contains("data object Offline : DownloadRequest"))
        assertTrue(store.contains("CORRUPT_FILE"))
        assertTrue("offline is decided before the metered question", manager.indexOf("DownloadRequest.Offline") < manager.indexOf("NeedsMeteredConfirmation("))
        assertTrue(screen.contains("DownloadRequest.Offline") && screen.contains("InstallFailure.CORRUPT_FILE"))
        val guards = text("$main/data/ai/models/AndroidModelSupport.kt")
        assertTrue(guards.contains("override fun isOnline()"))
        listOf("registerNetworkCallback", "registerDefaultNetworkCallback", "BroadcastReceiver", "CONNECTIVITY_ACTION").forEach {
            assertFalse("the network state is read at the download decision only, never monitored: $it", guards.contains(it))
        }
    }

    @Test
    fun aReleaseBuildHasNoCleartextConfigNoDeveloperPathInTheUiAndNoTestEndpoint() {
        val mainManifest = text("src/main/AndroidManifest.xml")
        assertFalse(mainManifest.contains("networkSecurityConfig") || mainManifest.contains("usesCleartextTraffic"))
        assertFalse("no release manifest overlay reintroduces it", File("app/src/release/AndroidManifest.xml").exists() && File("app/src/release/AndroidManifest.xml").readText().contains("networkSecurityConfig"))
        assertTrue("the loopback config is debug-only", File("app/src/debug/res/xml/network_security_config.xml").isFile || File("src/debug/res/xml/network_security_config.xml").isFile)
        val downloader = text("$main/data/ai/models/HttpModelDownloader.kt")
        assertTrue(downloader.contains("allowLoopbackHttp: Boolean = false"))
        val app = text("$main/MemoRippleApplication.kt")
        assertFalse("the product never opts into loopback cleartext", app.contains("allowLoopbackHttp"))
        val ui = dir("$main/ui").walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.joinToString("\n")
        listOf("DeveloperPath", "developer.properties", "llmModelPath", "/data/local/tmp").forEach { assertFalse("ui mentions $it", ui.contains(it)) }
        val selection = text("$main/data/ai/AiAssembly.kt")
        assertTrue("the developer file is read only in a debug build", selection.contains("if (isDebugBuild && developerFile().isFile)"))
    }

    @Test
    fun noModelBytesAreEverPackagedAndTheVersionIsNotBumpedByThisPhase() {
        val packaged = listOf(dir("src/main/assets"), dir("src/main/res"), dir("src/main/cpp"), dir("src/main/jniLibs")).filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension.lowercase() == "gguf" }.toList() }
        assertEquals(emptyList<File>(), packaged)
        val gradle = text("build.gradle.kts")
        assertTrue("the version is the human's decision: 5 / 1.1.0 since Play showed versionCode 4 used by the closed test (2026-09-27)", gradle.contains("versionCode = 5") && gradle.contains("versionName = \"1.1.0\""))
        assertTrue("release shrinking stays on", gradle.contains("isMinifyEnabled = true"))
        assertFalse("no debug signing fallback for release", gradle.contains("signingConfigs.getByName(\"debug\")"))
    }

    @Test
    fun theStandingAiRulesStillHold() {
        val db = text("$main/data/AppDatabase.kt")
        assertTrue(db.contains("version = 29"))   // Phase 8: the three chat history tables, human-approved 2026-09-20
        listOf("chat_sessions", "ModelEntity", "ai_draft", "pending_write").forEach { assertFalse(db.contains(it)) }
        assertTrue(text("$main/backup/BackupDtos.kt").contains("BACKUP_FORMAT_VERSION = 23"))
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        assertEquals(setOf("KEY_INPUT") /* Chat UI redesign (human decision 2026-09-21): the chat is a conversation; only the draft is saved */, Regex("const val (KEY_[A-Z_]+) = ").findAll(vm).map { it.groupValues[1] }.toSet())
        assertFalse(vm.contains("ConversationHistory") || vm.contains("chatHistory"))
        val intents = Regex("^    ([A-Z_]+),$", RegexOption.MULTILINE).findAll(text("$main/domain/ai/IntentProposal.kt").substringAfter("enum class AiIntent").substringBefore("}")).map { it.groupValues[1] }.toList()
        assertEquals(listOf("SEARCH", "OPEN", "CREATE", "APPEND", "USE_TEMPLATE", "UNKNOWN"), intents)
        assertEquals("v1", Regex("PROMPT_VERSION = \"([^\"]+)\"").find(text("$main/domain/ai/runtime/PromptAssets.kt"))!!.groupValues[1])
        val orchestrator = text("$main/domain/ai/AiOrchestrator.kt")
        // Phase 2: the load lives in the resource controller; the invariant is unchanged — thermal is read before the acquire that could load
        val loadPoint = orchestrator.indexOf("resources.acquireForGeneration")
        assertTrue("thermal is checked before any load", loadPoint > 0 && orchestrator.indexOf("thermal.check()") < loadPoint)
        assertTrue("a confirmed write never consults the thermal gate", !orchestrator.substringAfter("override suspend fun execute(").substringBefore("override suspend fun release()").contains("thermal"))
    }
}

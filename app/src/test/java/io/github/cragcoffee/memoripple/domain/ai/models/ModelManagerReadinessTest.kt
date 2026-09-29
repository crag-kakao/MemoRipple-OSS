package io.github.cragcoffee.memoripple.domain.ai.models

import io.github.cragcoffee.memoripple.data.ai.models.FileModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 7 RED (docs/CHAT_V1_RELEASE_READINESS.md): a model file of the wrong length is never
 * `Installed`; offline, no download starts (and no metered question is asked); an installed
 * model needs no network to be selected.
 */
class ModelManagerReadinessTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val a = ByteArray(200_000) { (it % 13).toByte() }
    private val entryA = testEntry("model-a", a)
    private lateinit var root: File
    private lateinit var store: FileModelStore
    private val downloader = FakeDownloader().apply { bodies[entryA.source.url] = a }
    private val guards = FakeGuards()
    private val selection = FakeSelectedModelStore()
    private var unloads = 0
    private lateinit var manager: ModelManager

    @Before
    fun setUp() {
        root = tmp.newFolder("no_backup")
        store = FileModelStore(root)
        manager = ModelManager(catalog = listOf(entryA), store = store, downloader = downloader, guards = guards, selection = selection, unloadRuntime = { unloads++ }, scope = scope, safetyMarginBytes = 1_000_000)
        runBlocking { manager.refresh() }
    }

    @After
    fun tearDown() { runBlocking { manager.cancelAll() } }

    private fun state(id: String): InstallState = manager.states.value.getValue(id)
    private fun awaitState(id: String, predicate: (InstallState) -> Boolean): InstallState =
        runBlocking { withTimeout(10_000) { manager.states.first { predicate(it.getValue(id)) }.getValue(id) } }
    private fun installA() { assertEquals(DownloadRequest.Started, runBlocking { manager.requestDownload("model-a") }); awaitState("model-a") { it is InstallState.Installed } }
    private fun modelFile() = File(root, "models/model-a/model.gguf")

    // --- RED 7, 31: a wrong-length file is corrupt, not installed; select refuses it; a retry replaces it ---

    @Test
    fun anInstalledFileOfTheWrongLengthIsReportedCorruptAndCannotBeSelected() {
        installA()
        modelFile().writeBytes(ByteArray(1234))
        runBlocking { manager.refresh() }
        assertEquals(InstallState.Failed(InstallFailure.CORRUPT_FILE, 0), state("model-a"))
        assertEquals(SelectOutcome.NotInstalled, runBlocking { manager.select("model-a") })
        assertNull(manager.selectedModelId.value)
        assertTrue(selection.writes.isEmpty())
    }

    @Test
    fun aRetryOnACorruptFileRemovesItAndDownloadsAgainToAVerifiedInstall() {
        installA()
        modelFile().writeBytes(ByteArray(1234))
        runBlocking { manager.refresh() }
        assertEquals(DownloadRequest.Started, runBlocking { manager.requestDownload("model-a") })
        val end = awaitState("model-a") { it is InstallState.Installed || it is InstallState.Failed }
        assertEquals(InstallState.Installed(a.size.toLong()), end)
        assertEquals(a.size.toLong(), modelFile().length())
        assertEquals("a full download, not a resume of a corrupt file", 0L, downloader.offsets.last())
    }

    @Test
    fun aSelectedModelThatTurnsCorruptKeepsTheSelectionForTheUserToDecide() {
        installA()
        assertEquals(SelectOutcome.Selected, runBlocking { manager.select("model-a") })
        modelFile().writeBytes(ByteArray(1234))
        runBlocking { manager.refresh() }
        assertEquals(InstallState.Failed(InstallFailure.CORRUPT_FILE, 0), state("model-a"))
        assertEquals("the selection is the user's; nothing switches it silently", "model-a", manager.selectedModelId.value)
    }

    // --- RED 12, 11: offline ---

    @Test
    fun offlineNoDownloadStartsAndNoMeteredQuestionIsAsked() {
        guards.online = false
        guards.metered = true
        assertEquals(DownloadRequest.Offline, runBlocking { manager.requestDownload("model-a") })
        assertEquals(DownloadRequest.Offline, runBlocking { manager.requestDownload("model-a", allowMetered = true) })
        assertEquals(0, downloader.calls)
        assertEquals(InstallState.NotInstalled, state("model-a"))
        assertFalse(File(root, "models/model-a/model.gguf.part").exists())
    }

    @Test
    fun offlineAnInstalledModelIsStillSelectableAndStaysInstalled() {
        installA()
        guards.online = false
        runBlocking { manager.refresh() }
        assertEquals(InstallState.Installed(a.size.toLong()), state("model-a"))
        assertEquals(SelectOutcome.Selected, runBlocking { manager.select("model-a") })
        assertEquals("model-a", manager.selectedModelId.value)
    }

    @Test
    fun offlineAResumeOfAPartialDownloadIsRefusedTooAndThePartIsKept() {
        store.openPart("model-a", resume = false).use { it.write(a, 0, 50_000) }
        runBlocking { manager.refresh() }
        guards.online = false
        assertEquals(DownloadRequest.Offline, runBlocking { manager.requestDownload("model-a") })
        assertEquals(InstallState.Partial(50_000), state("model-a"))
    }
}

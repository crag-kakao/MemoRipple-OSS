package io.github.cragcoffee.memoripple.domain.ai.models

import io.github.cragcoffee.memoripple.data.ai.models.FileModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.OutputStream
import java.security.MessageDigest

/** A downloader fed from memory: bytes per url, an optional cut, a gate to hold a download open. */
class FakeDownloader : ModelDownloader {
    val bodies = HashMap<String, ByteArray>()
    var cutAfter: Long = -1
    var gate: CompletableDeferred<Unit>? = null
    var calls = 0
    val offsets = ArrayList<Long>()

    override suspend fun download(url: String, offset: Long, sink: OutputStream, onProgress: (Long, Long) -> Unit): DownloadEnd {
        calls++; offsets += offset
        val body = bodies[url] ?: return DownloadEnd.Failed("no such url")
        gate?.await()
        val end = if (cutAfter >= 0) minOf(body.size.toLong(), offset + cutAfter).toInt() else body.size
        var at = offset.toInt()
        while (at < end) {
            val n = minOf(1024, end - at)
            sink.write(body, at, n); at += n
            onProgress(at.toLong(), body.size.toLong())
            kotlinx.coroutines.yield()
        }
        return if (end == body.size) DownloadEnd.Complete(body.size.toLong()) else DownloadEnd.Failed("cut")
    }
}

class FakeGuards : DeviceGuards {
    var usable = 100L shl 30
    var metered = false
    var battery = 80
    var charging = false
    var supported = true
    var online = true
    override fun usableBytes() = usable
    override fun isOnline() = online
    override fun isMetered() = metered
    override fun batteryPercent() = battery
    override fun isCharging() = charging
    override fun supportsLocalInference() = supported
}

class FakeSelectedModelStore : SelectedModelStore {
    val state = MutableStateFlow<String?>(null)
    val writes = ArrayList<String?>()
    override val selected: Flow<String?> get() = state
    override suspend fun set(modelId: String?) { writes += modelId; state.value = modelId }
}

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun testEntry(id: String, bytes: ByteArray, url: String = "https://127.0.0.1/$id.gguf", category: ModelCategory = ModelCategory.BALANCED) = CatalogEntry(
    modelId = id, displayName = id, description = "test", category = category,
    approximateDownloadBytes = bytes.size.toLong(), approximateLoadedMemoryBytes = bytes.size * 2L,
    strengths = listOf("s"), tradeoffs = listOf("t"),
    source = ModelSource(repository = "test/$id", revision = "0".repeat(40), fileName = "$id.gguf", url = url, publisher = "test", publisherNote = "test"),
    expectedSha256 = sha256Hex(bytes), quantization = "Q4_K_M", minimumCpu = CpuRequirement.ARM_DOTPROD, contextSize = 4096,
    license = ModelLicense("Apache 2.0", "https://example.invalid/license"), runtimeProfileId = id,
)

/**
 * Phase 5 RED (docs/AI_MODEL_MANAGEMENT.md): the manager — guards before a byte moves, one
 * download at a time, `.part` until the hash matches, resume after an interruption, cancel and
 * retry, selection by id only with no default, delete in the safe order, and never a load.
 */
class ModelManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val a = ByteArray(200_000) { (it % 13).toByte() }
    private val b = ByteArray(120_000) { (it % 17).toByte() }
    private val entryA = testEntry("model-a", a)
    private val entryB = testEntry("model-b", b, category = ModelCategory.SAFETY_ORIENTED)
    private lateinit var store: FileModelStore
    private val downloader = FakeDownloader().apply { bodies[entryA.source.url] = a; bodies[entryB.source.url] = b }
    private val guards = FakeGuards()
    private val selection = FakeSelectedModelStore()
    private var unloads = 0
    private lateinit var manager: ModelManager

    @Before
    fun setUp() {
        store = FileModelStore(tmp.newFolder("no_backup"))
        manager = ModelManager(
            catalog = listOf(entryA, entryB), store = store, downloader = downloader, guards = guards, selection = selection,
            unloadRuntime = { unloads++ }, scope = scope, safetyMarginBytes = 1_000_000,
        )
        runBlocking { manager.refresh() }
    }

    @After
    fun tearDown() { runBlocking { manager.cancelAll() } }

    private fun state(id: String): InstallState = manager.states.value.getValue(id)
    private fun awaitState(id: String, predicate: (InstallState) -> Boolean): InstallState =
        runBlocking { withTimeout(10_000) { manager.states.first { predicate(it.getValue(id)) }.getValue(id) } }
    private fun installA() { assertEquals(DownloadRequest.Started, runBlocking { manager.requestDownload("model-a") }); awaitState("model-a") { it is InstallState.Installed } }

    // --- catalog state, no default (RED 3–7, 43, 44) ---

    @Test
    fun aFreshInstallHasEveryModelNotInstalledNothingSelectedAndNoDownloadStarts() {
        assertEquals(InstallState.NotInstalled, state("model-a"))
        assertEquals(InstallState.NotInstalled, state("model-b"))
        assertNull(manager.selectedModelId.value)
        assertEquals(0, downloader.calls)
        assertTrue(selection.writes.isEmpty())
    }

    @Test
    fun aPartialFileOnDiskIsPartialNotInstalledAndNothingResumesByItself() {
        store.openPart("model-a", resume = false).use { it.write(a, 0, 50_000) }
        runBlocking { manager.refresh() }
        assertEquals(InstallState.Partial(50_000), state("model-a"))
        assertEquals(0, downloader.calls)
    }

    @Test
    fun anInstalledFileOnDiskIsInstalledButNotSelected() {
        store.openPart("model-b", resume = false).use { it.write(b) }
        runBlocking { store.verifyAndCommit("model-b", entryB.expectedSha256) }
        runBlocking { manager.refresh() }
        assertEquals(InstallState.Installed(b.size.toLong()), state("model-b"))
        assertNull("installed ≠ selected", manager.selectedModelId.value)
    }

    // --- download: .part first, verify, commit (RED 8–13, 35) ---

    @Test
    fun aDownloadGoesDownloadingVerifyingInstalledAndWritesThePartFirst() {
        downloader.gate = CompletableDeferred()
        assertEquals(DownloadRequest.Started, runBlocking { manager.requestDownload("model-a") })
        awaitState("model-a") { it is InstallState.Downloading }
        assertTrue(store.files("model-a").partFile.exists() || store.partialBytes("model-a") == 0L)
        assertNull(store.installedBytes("model-a"))
        downloader.gate!!.complete(Unit)
        val done = awaitState("model-a") { it is InstallState.Installed }
        assertEquals(InstallState.Installed(a.size.toLong()), done)
        assertTrue(store.files("model-a").file.readBytes().contentEquals(a))
        assertFalse(store.files("model-a").partFile.exists())
        assertNull("installing never selects", manager.selectedModelId.value)
    }

    @Test
    fun progressIsReportedInBytesAgainstTheTotal() {
        val seen = ArrayList<InstallState.Downloading>()
        val job = scope.launchCollect(manager) { s -> (s["model-a"] as? InstallState.Downloading)?.let { seen += it } }
        installA()
        job.cancel()
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it.totalBytes == a.size.toLong() })
        assertTrue(seen.last().bytes <= a.size.toLong())
    }

    @Test
    fun aChecksumMismatchIsFailedNotInstalledAndThePartIsGone() {
        val wrong = entryA.copy(expectedSha256 = sha256Hex(b))
        manager = ModelManager(listOf(wrong, entryB), store, downloader, guards, selection, { unloads++ }, scope, safetyMarginBytes = 0)
        runBlocking { manager.refresh() }
        assertEquals(DownloadRequest.Started, runBlocking { manager.requestDownload("model-a") })
        val end = awaitState("model-a") { it is InstallState.Failed }
        assertEquals(InstallFailure.CHECKSUM_MISMATCH, (end as InstallState.Failed).reason)
        assertNull(store.installedBytes("model-a"))
        assertEquals(0L, store.partialBytes("model-a"))
        assertTrue(store.installedModelIds().isEmpty())
    }

    @Test
    fun anInterruptedDownloadKeepsThePartAndARetryResumesFromIt() {
        downloader.cutAfter = 70_000
        assertEquals(DownloadRequest.Started, runBlocking { manager.requestDownload("model-a") })
        val failed = awaitState("model-a") { it is InstallState.Failed } as InstallState.Failed
        assertEquals(InstallFailure.NETWORK, failed.reason)
        assertEquals(70_000L, failed.partialBytes)
        assertEquals(70_000L, store.partialBytes("model-a"))
        assertNull(store.installedBytes("model-a"))
        downloader.cutAfter = -1
        assertEquals(DownloadRequest.Started, runBlocking { manager.requestDownload("model-a") })
        awaitState("model-a") { it is InstallState.Installed }
        assertEquals(listOf(0L, 70_000L), downloader.offsets)
        assertTrue(store.files("model-a").file.readBytes().contentEquals(a))
    }

    @Test
    fun aProcessRestartWithAPartIsPartialAndTheNextRequestResumes() {
        store.openPart("model-a", resume = false).use { it.write(a, 0, 30_000) }
        manager = ModelManager(listOf(entryA, entryB), store, downloader, guards, selection, { unloads++ }, scope, safetyMarginBytes = 0)
        runBlocking { manager.refresh() }
        assertEquals(InstallState.Partial(30_000), state("model-a"))
        assertEquals(DownloadRequest.Started, runBlocking { manager.requestDownload("model-a") })
        awaitState("model-a") { it is InstallState.Installed }
        assertEquals(listOf(30_000L), downloader.offsets)
    }

    // --- cancel, retry, duplicates (RED 14–16) ---

    @Test
    fun cancelStopsTheDownloadKeepsThePartInstallsNothingAndLeavesTheSelectionAlone() {
        installB(); runBlocking { manager.select("model-b") }
        downloader.gate = CompletableDeferred()
        assertEquals(DownloadRequest.Started, runBlocking { manager.requestDownload("model-a") })
        awaitState("model-a") { it is InstallState.Downloading }
        runBlocking { manager.cancel("model-a") }
        val after = awaitState("model-a") { it !is InstallState.Downloading }
        assertTrue("$after", after is InstallState.Partial || after is InstallState.NotInstalled)
        assertNull(store.installedBytes("model-a"))
        assertEquals("model-b", manager.selectedModelId.value)
        downloader.gate!!.complete(Unit)
        // and a later request starts again
        downloader.gate = null
        assertEquals(DownloadRequest.Started, runBlocking { manager.requestDownload("model-a") })
        awaitState("model-a") { it is InstallState.Installed }
    }

    private fun installB() { assertEquals(DownloadRequest.Started, runBlocking { manager.requestDownload("model-b") }); awaitState("model-b") { it is InstallState.Installed } }

    @Test
    fun theSameModelIsNeverDownloadedTwiceAtOnceAndOnlyOneModelAtATime() {
        downloader.gate = CompletableDeferred()
        assertEquals(DownloadRequest.Started, runBlocking { manager.requestDownload("model-a") })
        awaitState("model-a") { it is InstallState.Downloading }
        assertEquals(DownloadRequest.AlreadyDownloading, runBlocking { manager.requestDownload("model-a") })
        assertEquals(DownloadRequest.AnotherDownloadRunning, runBlocking { manager.requestDownload("model-b") })
        // the job sets Downloading before it invokes the downloader: wait for the call rather than racing it
        runBlocking { withTimeout(5_000) { while (downloader.calls == 0) delay(5) } }
        assertEquals(1, downloader.calls)
        downloader.gate!!.complete(Unit)
        awaitState("model-a") { it is InstallState.Installed }
        assertEquals(DownloadRequest.AlreadyInstalled, runBlocking { manager.requestDownload("model-a") })
        assertEquals(DownloadRequest.UnknownModel, runBlocking { manager.requestDownload("gemma") })
    }

    // --- guards before a byte moves (RED 17–19) ---

    @Test
    fun insufficientStorageCountsTheRemainingBytesPlusTheMargin() {
        guards.usable = a.size + 999_999L   // one byte short of size + margin
        val r = runBlocking { manager.requestDownload("model-a") } as DownloadRequest.InsufficientStorage
        assertEquals(a.size + 1_000_000L, r.requiredBytes)
        assertEquals(guards.usable, r.availableBytes)
        assertEquals(0, downloader.calls)
        assertEquals(InstallState.NotInstalled, state("model-a"))
        // a partial file reduces what is still needed
        store.openPart("model-a", resume = false).use { it.write(a, 0, 100_000) }
        runBlocking { manager.refresh() }
        guards.usable = (a.size - 100_000) + 1_000_000L
        assertEquals(DownloadRequest.Started, runBlocking { manager.requestDownload("model-a") })
        awaitState("model-a") { it is InstallState.Installed }
    }

    @Test
    fun anUnsupportedCpuBlocksTheDownloadBeforeAnyByteAndSelectionStaysEmpty() {
        guards.supported = false
        assertEquals(DownloadRequest.UnsupportedDevice, runBlocking { manager.requestDownload("model-a") })
        assertEquals(0, downloader.calls)
        assertFalse(manager.deviceSupported)
    }

    @Test
    fun aMeteredNetworkAsksFirstAndProceedsOnlyWithTheExplicitAllowance() {
        guards.metered = true
        val ask = runBlocking { manager.requestDownload("model-a") } as DownloadRequest.NeedsMeteredConfirmation
        assertEquals(a.size.toLong(), ask.remainingBytes)
        assertEquals(0, downloader.calls)
        assertEquals(DownloadRequest.Started, runBlocking { manager.requestDownload("model-a", allowMetered = true) })
        awaitState("model-a") { it is InstallState.Installed }
    }

    @Test
    fun aLowBatteryWithoutAChargerDoesNotStartAChargerDoes() {
        guards.battery = 10
        assertEquals(DownloadRequest.LowBattery(10), runBlocking { manager.requestDownload("model-a") })
        assertEquals(0, downloader.calls)
        guards.charging = true
        assertEquals(DownloadRequest.Started, runBlocking { manager.requestDownload("model-a") })
        awaitState("model-a") { it is InstallState.Installed }
    }

    // --- selection (RED 4, 20, 21, 45, 46) ---

    @Test
    fun selectionIsPersistedByIdOnlyRequiresAnInstalledModelUnloadsTheRuntimeAndLoadsNothing() {
        assertEquals(SelectOutcome.NotInstalled, runBlocking { manager.select("model-a") })
        assertEquals(SelectOutcome.UnknownModel, runBlocking { manager.select("gemma") })
        installA()
        assertEquals(SelectOutcome.Selected, runBlocking { manager.select("model-a") })
        assertEquals("model-a", manager.selectedModelId.value)
        assertEquals(listOf<String?>("model-a"), selection.writes)
        assertEquals(1, unloads)
        installB()
        assertEquals(SelectOutcome.Selected, runBlocking { manager.select("model-b") })
        assertEquals("model-b", manager.selectedModelId.value)
        assertEquals(2, unloads)
        // no path, no url, no hash ever goes to the store — only the id
        selection.writes.filterNotNull().forEach { assertTrue(it, Regex("[a-z0-9-]+").matches(it)) }
        runBlocking { manager.clearSelection() }
        assertNull(manager.selectedModelId.value)
        assertEquals(3, unloads)
    }

    @Test
    fun theProductSelectionAnswersOnlyASelectedAndInstalledModel() {
        val product = ManagedModelSelection(manager) { id -> io.github.cragcoffee.memoripple.data.ai.ModelProfiles.all.firstOrNull { it.descriptor.id == id }?.descriptor ?: testDescriptor(id) }
        assertNull(runBlocking { product.selected() })
        installA()
        assertNull("installed but not selected", runBlocking { product.selected() })
        runBlocking { manager.select("model-a") }
        assertEquals("model-a", runBlocking { product.selected() }!!.id)
        runBlocking { manager.delete("model-a") }
        assertNull("deleted → nothing selected", runBlocking { product.selected() })
    }

    // --- delete in the safe order (RED 22–25) ---

    @Test
    fun deletingAnUnselectedModelRemovesItsFilesAndTouchesNeitherSelectionNorRuntime() {
        installA(); installB(); runBlocking { manager.select("model-a") }
        val unloadsBefore = unloads
        assertEquals(DeleteOutcome.Deleted(wasSelected = false), runBlocking { manager.delete("model-b") })
        assertEquals(InstallState.NotInstalled, state("model-b"))
        assertNull(store.installedBytes("model-b"))
        assertEquals("model-a", manager.selectedModelId.value)
        assertEquals(unloadsBefore, unloads)
        assertEquals(InstallState.Installed(a.size.toLong()), state("model-a"))
    }

    @Test
    fun deletingTheSelectedModelUnloadsFirstClearsTheSelectionThenRemovesTheFiles() {
        installA(); runBlocking { manager.select("model-a") }
        val order = ArrayList<String>()
        manager = ModelManager(listOf(entryA, entryB), store, downloader, guards, selection, { order += "unload" }, scope, 0)
        runBlocking { manager.refresh() }
        val spySelection = selection
        val outcome = runBlocking { manager.delete("model-a") }
        assertEquals(DeleteOutcome.Deleted(wasSelected = true), outcome)
        assertEquals(listOf("unload"), order)
        assertNull(spySelection.state.value)
        assertEquals(InstallState.NotInstalled, state("model-a"))
        assertFalse(store.files("model-a").directory.exists())
        assertEquals(DeleteOutcome.NotInstalled, runBlocking { manager.delete("model-a") })
    }

    @Test
    fun aModelBeingDownloadedIsNotDeletedUnderTheDownload() {
        downloader.gate = CompletableDeferred()
        runBlocking { manager.requestDownload("model-a") }
        awaitState("model-a") { it is InstallState.Downloading }
        assertEquals(DeleteOutcome.Downloading, runBlocking { manager.delete("model-a") })
        downloader.gate!!.complete(Unit)
        awaitState("model-a") { it is InstallState.Installed }
    }

    @Test
    fun theManagerKnowsNoDocumentTypeAtAll() {
        val src = java.io.File("src/main/java/io/github/cragcoffee/memoripple/domain/ai/models/ModelManager.kt").let { if (it.isFile) it else java.io.File("app/" + it.path) }.readText()
        listOf("DocumentAccess", "Dao", "Entity", "androidx.room", "MemoRepository", "DiaryRepository", "AppDatabase", "android.").forEach { assertFalse("manager mentions $it", src.contains(it)) }
    }
}

private fun testDescriptor(id: String) = io.github.cragcoffee.memoripple.domain.ai.runtime.ModelDescriptor(
    id = id, displayName = id, location = io.github.cragcoffee.memoripple.domain.ai.runtime.ModelLocation.Installed(id),
    architecture = "test", contextSize = 4096, quantization = "Q4_K_M", capabilities = emptySet(),
)

private fun CoroutineScope.launchCollect(manager: ModelManager, block: (Map<String, InstallState>) -> Unit) =
    launch { manager.states.collect { block(it) } }

package io.github.cragcoffee.memoripple.domain.ai.models

import io.github.cragcoffee.memoripple.domain.ai.ModelSelection
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Model management (docs/AI_MODEL_MANAGEMENT.md): the one place that downloads, verifies,
 * installs, selects and deletes catalog models. Rules:
 *
 * - nothing starts by itself — no download, no selection, no load: every verb is a user's tap;
 * - guards before a byte moves: CPU support, free space with a margin, battery, metered network;
 * - one download at a time; the same model never twice at once;
 * - a download is a `.part` until its SHA-256 matches; a mismatch is a failure and the part is
 *   removed; an interruption keeps the part and the next request resumes from it;
 * - installed ≠ selected: the user selects an installed model by id; switching or deleting the
 *   selected model unloads the runtime first and never loads the new one;
 * - no document, no database: only the model directory and the selected id.
 */
class ModelManager(
    private val catalog: List<CatalogEntry>,
    private val store: ModelStore,
    private val downloader: ModelDownloader,
    private val guards: DeviceGuards,
    private val selection: SelectedModelStore,
    private val unloadRuntime: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val safetyMarginBytes: Long = DEFAULT_SAFETY_MARGIN_BYTES,
    private val lowBatteryPercent: Int = DEFAULT_LOW_BATTERY_PERCENT,
) {
    private val _states = MutableStateFlow(catalog.associate { it.modelId to (InstallState.NotInstalled as InstallState) })
    val states: StateFlow<Map<String, InstallState>> = _states.asStateFlow()

    private val _selected = MutableStateFlow<String?>(null)
    val selectedModelId: StateFlow<String?> = _selected.asStateFlow()

    val entries: List<CatalogEntry> get() = catalog
    val deviceSupported: Boolean get() = guards.supportsLocalInference()

    private val lock = Mutex()
    private var active: Pair<String, Job>? = null

    /** Reads the disk and the selected id. Starts nothing. */
    suspend fun refresh() = lock.withLock {
        _selected.value = selection.selected.first()?.takeIf { id -> catalog.any { it.modelId == id } }
        catalog.forEach { e -> if (active?.first != e.modelId) setState(e.modelId, scan(e.modelId)) }
    }

    private fun scan(modelId: String): InstallState {
        store.installedBytes(modelId)?.let { bytes ->
            // Phase 7: an installed file is the verified download or it is nothing — a wrong length is corrupt, never Installed
            val expected = catalog.firstOrNull { it.modelId == modelId }?.approximateDownloadBytes
            return if (expected != null && bytes != expected) InstallState.Failed(InstallFailure.CORRUPT_FILE, 0) else InstallState.Installed(bytes)
        }
        val partial = store.partialBytes(modelId)
        return if (partial > 0) InstallState.Partial(partial) else InstallState.NotInstalled
    }

    suspend fun requestDownload(modelId: String, allowMetered: Boolean = false): DownloadRequest = lock.withLock {
        val entry = catalog.firstOrNull { it.modelId == modelId } ?: return DownloadRequest.UnknownModel
        val corrupt = store.installedBytes(modelId)?.let { it != entry.approximateDownloadBytes } ?: false
        if (store.installedBytes(modelId) != null && !corrupt) return DownloadRequest.AlreadyInstalled
        active?.let { (id, job) -> if (job.isActive) return if (id == modelId) DownloadRequest.AlreadyDownloading else DownloadRequest.AnotherDownloadRunning }
        if (!guards.supportsLocalInference()) return DownloadRequest.UnsupportedDevice
        if (!guards.isOnline()) return DownloadRequest.Offline
        // a corrupt install is replaced, never resumed: the user's retry removes it first (the selection is theirs and stays)
        if (corrupt) store.delete(modelId)
        val remaining = (entry.approximateDownloadBytes - store.partialBytes(modelId)).coerceAtLeast(0)
        val required = remaining + safetyMarginBytes
        val available = guards.usableBytes()
        if (available < required) return DownloadRequest.InsufficientStorage(required, available)
        if (guards.batteryPercent() < lowBatteryPercent && !guards.isCharging()) return DownloadRequest.LowBattery(guards.batteryPercent())
        if (guards.isMetered() && !allowMetered) return DownloadRequest.NeedsMeteredConfirmation(remaining)
        setState(modelId, InstallState.Checking)
        val job = scope.launch { run(entry) }
        active = modelId to job
        DownloadRequest.Started
    }

    private suspend fun run(entry: CatalogEntry) {
        val id = entry.modelId
        try {
            val partial = store.partialBytes(id)
            setState(id, InstallState.Downloading(partial, entry.approximateDownloadBytes))
            val end = store.openPart(id, resume = partial > 0).use { sink ->
                downloader.download(entry.source.url, partial, sink) { bytes, total ->
                    setState(id, InstallState.Downloading(bytes, if (total > 0) total else entry.approximateDownloadBytes))
                }
            }
            when (end) {
                is DownloadEnd.Failed -> setState(id, InstallState.Failed(InstallFailure.NETWORK, store.partialBytes(id)))
                is DownloadEnd.Complete -> {
                    setState(id, InstallState.Verifying)
                    when (val commit = store.verifyAndCommit(id, entry.expectedSha256)) {
                        is CommitResult.Committed -> setState(id, InstallState.Installed(commit.bytes))
                        CommitResult.Mismatch -> setState(id, InstallState.Failed(InstallFailure.CHECKSUM_MISMATCH, 0))
                        CommitResult.NoPart -> setState(id, InstallState.Failed(InstallFailure.STORAGE_IO, 0))
                    }
                }
            }
        } catch (c: CancellationException) {
            setState(id, scan(id))
            throw c
        } catch (t: Throwable) {
            setState(id, InstallState.Failed(InstallFailure.STORAGE_IO, store.partialBytes(id)))
        } finally {
            if (active?.first == id) active = null
        }
    }

    /** Stops the model's download, if running; the part stays for a later resume. */
    suspend fun cancel(modelId: String) {
        val job = active?.takeIf { it.first == modelId }?.second ?: return
        job.cancelAndJoin()
    }

    suspend fun cancelAll() { active?.second?.cancelAndJoin() }

    /** The user's choice, by id, of an installed model. Unloads the runtime; never loads. */
    suspend fun select(modelId: String): SelectOutcome = lock.withLock {
        if (catalog.none { it.modelId == modelId }) return SelectOutcome.UnknownModel
        if (scan(modelId) !is InstallState.Installed) return SelectOutcome.NotInstalled
        unloadRuntime()
        selection.set(modelId)
        _selected.value = modelId
        SelectOutcome.Selected
    }

    suspend fun clearSelection() = lock.withLock {
        unloadRuntime()
        selection.set(null)
        _selected.value = null
    }

    /** Unload (if selected) → clear the selection → remove the files. Never touches a document. */
    suspend fun delete(modelId: String): DeleteOutcome = lock.withLock {
        active?.let { (id, job) -> if (id == modelId && job.isActive) return DeleteOutcome.Downloading }
        val hadSomething = store.installedBytes(modelId) != null || store.partialBytes(modelId) > 0
        val wasSelected = _selected.value == modelId
        if (wasSelected) {
            unloadRuntime()
            selection.set(null)
            _selected.value = null
        }
        val removed = store.delete(modelId)
        setState(modelId, InstallState.NotInstalled)
        if (!hadSomething && !removed) DeleteOutcome.NotInstalled else DeleteOutcome.Deleted(wasSelected)
    }

    private fun setState(modelId: String, state: InstallState) {
        _states.update { it + (modelId to state) }
    }

    companion object {
        /** Beyond the file: the part's rename, the filesystem's own needs, and room for the app to keep working. */
        const val DEFAULT_SAFETY_MARGIN_BYTES: Long = 512L * 1024 * 1024
        const val DEFAULT_LOW_BATTERY_PERCENT: Int = 15
    }
}

/**
 * The runtime's view of the manager: the selected model, only if it is installed. Nothing
 * else on disk is ever picked (no unilateral default).
 */
class ManagedModelSelection(private val manager: ModelManager, private val descriptorFor: (String) -> ModelDescriptor?) : ModelSelection {
    override suspend fun selected(): ModelDescriptor? {
        val id = manager.selectedModelId.value ?: return null
        if (manager.states.value[id] !is InstallState.Installed) return null
        return descriptorFor(id)
    }
}

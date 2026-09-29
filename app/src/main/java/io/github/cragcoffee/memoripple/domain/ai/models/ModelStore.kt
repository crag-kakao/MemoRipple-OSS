package io.github.cragcoffee.memoripple.domain.ai.models

import java.io.File
import java.io.OutputStream

/** The files of one model under the store's root: the final file, and the part written during a download. */
data class ModelFiles(val directory: File, val file: File, val partFile: File)

/**
 * Where model files live (docs/AI_MODEL_MANAGEMENT.md §storage): one directory per model under
 * the no-backup root, the model file written as a `.part` first and committed only after the
 * SHA-256 matches. The interface is filesystem-only: no network, no platform, no document.
 */
interface ModelStore {
    /** Throws [IllegalArgumentException] for an id that is not path-safe. */
    fun files(modelId: String): ModelFiles
    /** The installed file's length, or null when the model is not installed (a part alone is not). */
    fun installedBytes(modelId: String): Long?
    /** The part's length, or 0. */
    fun partialBytes(modelId: String): Long
    /** A sink on the part: appended when [resume] is true, truncated otherwise. The caller closes it. */
    fun openPart(modelId: String, resume: Boolean): OutputStream
    /** Hashes the part; on a match renames it atomically over the final file, on a mismatch removes it. */
    suspend fun verifyAndCommit(modelId: String, expectedSha256: String): CommitResult
    /** Removes the model's directory (file, part, anything model-specific). True when something was removed. */
    suspend fun delete(modelId: String): Boolean
    fun usableBytes(): Long
    fun installedModelIds(): Set<String>
}

sealed interface CommitResult {
    data class Committed(val bytes: Long) : CommitResult
    data object Mismatch : CommitResult
    data object NoPart : CommitResult
}

/** Streams a URL into a sink from an offset, reporting absolute progress. Cancellation propagates. */
interface ModelDownloader {
    suspend fun download(url: String, offset: Long, sink: OutputStream, onProgress: (bytesSoFar: Long, totalBytes: Long) -> Unit): DownloadEnd
}

sealed interface DownloadEnd {
    data class Complete(val totalBytes: Long) : DownloadEnd
    data class Failed(val detail: String) : DownloadEnd
}

/** What the device says before a multi-GB download starts. The data layer reads the platform. */
interface DeviceGuards {
    fun usableBytes(): Long
    fun isMetered(): Boolean
    fun batteryPercent(): Int
    fun isCharging(): Boolean
    /** The shipped native runtime's CPU requirement (dotprod) — checked before any byte moves. */
    fun supportsLocalInference(): Boolean
    /** Phase 7: whether a network is there at all — read at the download decision, never monitored; offline, no download starts. */
    fun isOnline(): Boolean
}

/** The one persisted preference: the selected model id, or nothing. Never a path, url or hash. */
interface SelectedModelStore {
    val selected: kotlinx.coroutines.flow.Flow<String?>
    suspend fun set(modelId: String?)
}

/** A model's state as the screen shows it. Only [Installed] is usable by the runtime. */
sealed interface InstallState {
    data object NotInstalled : InstallState
    /** A part exists from an interrupted or cancelled download; resumable; not installed. */
    data class Partial(val bytes: Long) : InstallState
    data object Checking : InstallState
    data class Downloading(val bytes: Long, val totalBytes: Long) : InstallState
    data object Verifying : InstallState
    data class Installed(val bytes: Long) : InstallState
    data class Failed(val reason: InstallFailure, val partialBytes: Long) : InstallState
}

/** [CORRUPT_FILE]: an installed file whose length is not the verified download's (Phase 7) — never usable, replaced by a new download. */
enum class InstallFailure { NETWORK, CHECKSUM_MISMATCH, INSUFFICIENT_STORAGE, UNSUPPORTED_DEVICE, STORAGE_IO, CORRUPT_FILE }

sealed interface DownloadRequest {
    data object Started : DownloadRequest
    data class NeedsMeteredConfirmation(val remainingBytes: Long) : DownloadRequest
    data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) : DownloadRequest
    data class LowBattery(val percent: Int) : DownloadRequest
    data object UnsupportedDevice : DownloadRequest
    /** No network: nothing starts, nothing is asked (Phase 7). An installed model needs none. */
    data object Offline : DownloadRequest
    data object AlreadyDownloading : DownloadRequest
    data object AnotherDownloadRunning : DownloadRequest
    data object AlreadyInstalled : DownloadRequest
    data object UnknownModel : DownloadRequest
}

sealed interface SelectOutcome {
    data object Selected : SelectOutcome
    data object NotInstalled : SelectOutcome
    data object UnknownModel : SelectOutcome
}

sealed interface DeleteOutcome {
    data class Deleted(val wasSelected: Boolean) : DeleteOutcome
    data object NotInstalled : DeleteOutcome
    data object Downloading : DeleteOutcome
}

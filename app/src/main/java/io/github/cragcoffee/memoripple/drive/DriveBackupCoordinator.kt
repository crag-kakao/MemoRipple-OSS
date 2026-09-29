package io.github.cragcoffee.memoripple.drive

import android.app.PendingIntent
import android.content.Intent
import io.github.cragcoffee.memoripple.backup.BackupEngine
import io.github.cragcoffee.memoripple.backup.BackupInspectionResult
import io.github.cragcoffee.memoripple.backup.BackupRestoreResult
import io.github.cragcoffee.memoripple.backup.PreparedBackup
import io.github.cragcoffee.memoripple.backup.RestoreCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File

enum class DriveBackupAction { ENABLE_AUTO_BACKUP, BACKUP_NOW, RESTORE_FROM_DRIVE }

enum class DriveRuntimeStatus { IDLE, AUTHORIZING, BACKING_UP, DOWNLOADING, RESTORING }

data class DriveBackupUiState(
    val persisted: DriveBackupState = DriveBackupState(),
    val runtimeStatus: DriveRuntimeStatus = DriveRuntimeStatus.IDLE,
) {
    val isBusy: Boolean get() = runtimeStatus != DriveRuntimeStatus.IDLE
}

sealed interface DriveBackupActionResult {
    data object Success : DriveBackupActionResult
    data object SettingsRestoreFailed : DriveBackupActionResult
    data class RestoreReady(val candidate: RestoreCandidate) : DriveBackupActionResult
    data class AuthorizationRequired(val pendingIntent: PendingIntent) : DriveBackupActionResult
    data class Failed(val error: DriveBackupErrorKind) : DriveBackupActionResult
    data object Busy : DriveBackupActionResult
}

interface DriveBackupContent {
    suspend fun prepareBackup(): PreparedBackup
    suspend fun inspect(bytes: ByteArray): BackupInspectionResult
    suspend fun inspect(file: File): BackupInspectionResult = inspect(file.readBytes())
    suspend fun restore(candidate: RestoreCandidate): BackupRestoreResult
}

class BackupEngineDriveContent(private val engine: BackupEngine) : DriveBackupContent {
    override suspend fun prepareBackup(): PreparedBackup = engine.prepareBackup()
    override suspend fun inspect(bytes: ByteArray): BackupInspectionResult = engine.inspect(bytes)
    override suspend fun inspect(file: File): BackupInspectionResult = engine.inspect(file)
    override suspend fun restore(candidate: RestoreCandidate): BackupRestoreResult = engine.restore(candidate)
}

class DriveBackupCoordinator(
    private val stateStore: DriveBackupStateStore,
    private val authorization: DriveAuthorizationGateway,
    private val transport: DriveBackupTransport,
    private val content: DriveBackupContent,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val autoBackupDelayMillis: Long = DEFAULT_AUTO_BACKUP_DELAY_MILLIS,
) {
    val persistedState: StateFlow<DriveBackupState> = stateStore.state.stateIn(
        scope,
        SharingStarted.Eagerly,
        DriveBackupState(),
    )
    private val mutableRuntimeStatus = MutableStateFlow(DriveRuntimeStatus.IDLE)
    val runtimeStatus: StateFlow<DriveRuntimeStatus> = mutableRuntimeStatus

    private val operationMutex = Mutex()
    private val foreground = MutableStateFlow(false)
    private var autoBackupJob: Job? = null

    suspend fun setAutoBackupEnabled(enabled: Boolean) {
        stateStore.setAutoBackupEnabled(enabled)
        scheduleAutoBackup()
    }

    suspend fun recordChange() {
        stateStore.recordChange()
        scheduleAutoBackup()
    }

    fun onForeground() {
        foreground.value = true
        scheduleAutoBackup()
    }

    fun onBackground() {
        foreground.value = false
        autoBackupJob?.cancel()
        autoBackupJob = null
    }

    fun authorizationResultFromIntent(data: Intent?): DriveAuthorizationResult =
        authorization.resultFromIntent(data)

    suspend fun execute(
        action: DriveBackupAction,
        suppliedAccessToken: String? = null,
        interactive: Boolean = true,
    ): DriveBackupActionResult {
        if (!operationMutex.tryLock()) return DriveBackupActionResult.Busy
        try {
            mutableRuntimeStatus.value = DriveRuntimeStatus.AUTHORIZING
            val token = suppliedAccessToken ?: when (val result = authorization.authorize()) {
                is DriveAuthorizationResult.Authorized -> result.accessToken
                is DriveAuthorizationResult.ResolutionRequired -> {
                    stateStore.setError(DriveBackupErrorKind.NEEDS_AUTHORIZATION)
                    return if (interactive && result.pendingIntent != null) {
                        DriveBackupActionResult.AuthorizationRequired(result.pendingIntent)
                    } else {
                        DriveBackupActionResult.Failed(DriveBackupErrorKind.NEEDS_AUTHORIZATION)
                    }
                }
                DriveAuthorizationResult.Failed -> return fail(
                    DriveBackupErrorKind.AUTHORIZATION_FAILED,
                )
            }
            if (action == DriveBackupAction.ENABLE_AUTO_BACKUP) {
                stateStore.setAutoBackupEnabled(true)
            }
            return when (action) {
                DriveBackupAction.ENABLE_AUTO_BACKUP,
                DriveBackupAction.BACKUP_NOW,
                -> performBackup(token, interactive)
                DriveBackupAction.RESTORE_FROM_DRIVE -> performDownload(token, interactive)
            }
        } finally {
            mutableRuntimeStatus.value = DriveRuntimeStatus.IDLE
            operationMutex.unlock()
        }
    }

    suspend fun restore(candidate: RestoreCandidate): DriveBackupActionResult {
        if (!operationMutex.tryLock()) return DriveBackupActionResult.Busy
        return try {
            mutableRuntimeStatus.value = DriveRuntimeStatus.RESTORING
            when (content.restore(candidate)) {
                BackupRestoreResult.Success -> {
                    recordChange()
                    DriveBackupActionResult.Success
                }
                BackupRestoreResult.SettingsFailure -> {
                    recordChange()
                    DriveBackupActionResult.SettingsRestoreFailed
                }
                BackupRestoreResult.InvalidCandidate,
                BackupRestoreResult.RoomFailure,
                -> fail(DriveBackupErrorKind.RESTORE_FAILED)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            fail(DriveBackupErrorKind.RESTORE_FAILED)
        } finally {
            mutableRuntimeStatus.value = DriveRuntimeStatus.IDLE
            operationMutex.unlock()
        }
    }

    private suspend fun performBackup(
        initialToken: String,
        interactive: Boolean,
    ): DriveBackupActionResult {
        mutableRuntimeStatus.value = DriveRuntimeStatus.BACKING_UP
        val uploadGeneration = stateStore.state.first().changeGeneration
        val prepared = try {
            content.prepareBackup()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return fail(DriveBackupErrorKind.BACKUP_FAILED)
        }
        val cachedId = stateStore.state.first().remoteFileId
        var token = initialToken
        var result = transport.upload(token, cachedId, prepared.file)
        if (result is DriveTransportResult.Failure && result.cachedFileMissing) {
            stateStore.setRemoteFileId(null)
        }
        if (result.isUnauthorized()) {
            authorization.clearToken(token)
            when (val refreshed = authorization.authorize()) {
                is DriveAuthorizationResult.Authorized -> {
                    token = refreshed.accessToken
                    result = transport.upload(
                        token,
                        stateStore.state.first().remoteFileId,
                        prepared.file,
                    )
                }
                is DriveAuthorizationResult.ResolutionRequired -> {
                    stateStore.setError(DriveBackupErrorKind.NEEDS_AUTHORIZATION)
                    prepared.discard()
                    return if (interactive && refreshed.pendingIntent != null) {
                        DriveBackupActionResult.AuthorizationRequired(refreshed.pendingIntent)
                    } else {
                        DriveBackupActionResult.Failed(DriveBackupErrorKind.NEEDS_AUTHORIZATION)
                    }
                }
                DriveAuthorizationResult.Failed -> {
                    prepared.discard()
                    return fail(DriveBackupErrorKind.AUTHORIZATION_FAILED)
                }
            }
        }
        if (result is DriveTransportResult.Failure && result.cachedFileMissing) {
            stateStore.setRemoteFileId(null)
        }
        prepared.discard()
        return when (result) {
            is DriveTransportResult.Success -> {
                stateStore.recordUploadSuccess(
                    remoteFileId = result.value.id,
                    uploadedGeneration = uploadGeneration,
                    completedAt = nowMillis(),
                )
                if (stateStore.state.first().isDirty) scheduleAutoBackup()
                DriveBackupActionResult.Success
            }
            is DriveTransportResult.Failure -> fail(result.toErrorKind())
        }
    }

    private suspend fun performDownload(
        initialToken: String,
        interactive: Boolean,
    ): DriveBackupActionResult {
        mutableRuntimeStatus.value = DriveRuntimeStatus.DOWNLOADING
        val cachedId = stateStore.state.first().remoteFileId
        var token = initialToken
        var result = transport.download(token, cachedId)
        if (result is DriveTransportResult.Failure && result.cachedFileMissing) {
            stateStore.setRemoteFileId(null)
        }
        if (result.isUnauthorized()) {
            authorization.clearToken(token)
            when (val refreshed = authorization.authorize()) {
                is DriveAuthorizationResult.Authorized -> {
                    token = refreshed.accessToken
                    result = transport.download(token, stateStore.state.first().remoteFileId)
                }
                is DriveAuthorizationResult.ResolutionRequired -> {
                    stateStore.setError(DriveBackupErrorKind.NEEDS_AUTHORIZATION)
                    return if (interactive && refreshed.pendingIntent != null) {
                        DriveBackupActionResult.AuthorizationRequired(refreshed.pendingIntent)
                    } else {
                        DriveBackupActionResult.Failed(DriveBackupErrorKind.NEEDS_AUTHORIZATION)
                    }
                }
                DriveAuthorizationResult.Failed -> return fail(
                    DriveBackupErrorKind.AUTHORIZATION_FAILED,
                )
            }
        }
        if (result is DriveTransportResult.Failure && result.cachedFileMissing) {
            stateStore.setRemoteFileId(null)
        }
        return when (result) {
            is DriveTransportResult.Success -> {
                stateStore.setRemoteFileId(result.value.fileId)
                val inspection = content.inspect(result.value.file)
                result.value.discard()
                when (inspection) {
                    is BackupInspectionResult.Ready -> {
                        stateStore.setError(null)
                        DriveBackupActionResult.RestoreReady(inspection.candidate)
                    }
                    is BackupInspectionResult.Rejected -> fail(DriveBackupErrorKind.REMOTE_INVALID)
                }
            }
            is DriveTransportResult.Failure -> fail(result.toErrorKind())
        }
    }

    private suspend fun fail(error: DriveBackupErrorKind): DriveBackupActionResult.Failed {
        stateStore.setError(error)
        return DriveBackupActionResult.Failed(error)
    }

    @Synchronized
    private fun scheduleAutoBackup() {
        autoBackupJob?.cancel()
        autoBackupJob = null
        if (!foreground.value) return
        autoBackupJob = scope.launch {
            delay(autoBackupDelayMillis)
            val state = stateStore.state.first()
            if (foreground.value && state.autoBackupEnabled && state.isDirty) {
                execute(DriveBackupAction.BACKUP_NOW, interactive = false)
            }
        }
    }

    private fun DriveTransportResult<*>.isUnauthorized(): Boolean =
        this is DriveTransportResult.Failure && apiResult == DriveApiResult.Unauthorized

    private fun DriveTransportResult.Failure.toErrorKind(): DriveBackupErrorKind = when (apiResult) {
        DriveApiResult.Unauthorized -> DriveBackupErrorKind.NEEDS_AUTHORIZATION
        DriveApiResult.Forbidden -> DriveBackupErrorKind.FORBIDDEN
        DriveApiResult.NotFound -> DriveBackupErrorKind.REMOTE_NOT_FOUND
        DriveApiResult.NetworkFailure,
        DriveApiResult.ServerFailure,
        -> DriveBackupErrorKind.NETWORK
        DriveApiResult.InvalidResponse -> DriveBackupErrorKind.BACKUP_FAILED
        is DriveApiResult.Success -> DriveBackupErrorKind.BACKUP_FAILED
    }

    companion object {
        const val DEFAULT_AUTO_BACKUP_DELAY_MILLIS = 30_000L
    }
}

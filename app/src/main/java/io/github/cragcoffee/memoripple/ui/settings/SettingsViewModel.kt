package io.github.cragcoffee.memoripple.ui.settings

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.cragcoffee.memoripple.backup.BackupEngine
import io.github.cragcoffee.memoripple.backup.BackupFileReadResult
import io.github.cragcoffee.memoripple.backup.BackupInspectionError
import io.github.cragcoffee.memoripple.backup.BackupInspectionResult
import io.github.cragcoffee.memoripple.backup.BackupRestoreResult
import io.github.cragcoffee.memoripple.backup.PreparedBackup
import io.github.cragcoffee.memoripple.backup.RestoreCandidate
import io.github.cragcoffee.memoripple.backup.RestorePreview
import io.github.cragcoffee.memoripple.backup.SafBackupFileStore
import io.github.cragcoffee.memoripple.data.SettingsRepository
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.domain.settings.CommentSize
import io.github.cragcoffee.memoripple.domain.settings.PlaybackSpeed
import io.github.cragcoffee.memoripple.domain.settings.StageBackground
import io.github.cragcoffee.memoripple.domain.settings.ThemeMode
import io.github.cragcoffee.memoripple.drive.DriveAuthorizationResult
import io.github.cragcoffee.memoripple.drive.DriveBackupAction
import io.github.cragcoffee.memoripple.drive.DriveBackupActionResult
import io.github.cragcoffee.memoripple.drive.DriveBackupCoordinator
import io.github.cragcoffee.memoripple.drive.DriveBackupUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class BackupOperation {
    IDLE,
    PREPARING,
    WAITING_FOR_SAVE_LOCATION,
    WRITING,
    WAITING_FOR_SOURCE,
    READING,
    RESTORING,
}

data class SettingsBackupUiState(
    val operation: BackupOperation = BackupOperation.IDLE,
    val restorePreview: RestorePreview? = null,
    val message: String? = null,
) {
    val isBusy: Boolean get() = operation != BackupOperation.IDLE
}

sealed interface SettingsBackupEffect {
    data class CreateDocument(val suggestedFileName: String) : SettingsBackupEffect
    data object OpenDocument : SettingsBackupEffect
    data class LaunchDriveAuthorization(val pendingIntent: PendingIntent) : SettingsBackupEffect
}

private enum class RestoreSource { MANUAL, DRIVE }

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val backupEngine: BackupEngine,
    private val backupFileStore: SafBackupFileStore,
    private val driveBackupCoordinator: DriveBackupCoordinator,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings.Default,
    )

    private val mutableBackupState = MutableStateFlow(SettingsBackupUiState())
    val backupState: StateFlow<SettingsBackupUiState> = mutableBackupState
    val driveBackupState: StateFlow<DriveBackupUiState> = combine(
        driveBackupCoordinator.persistedState,
        driveBackupCoordinator.runtimeStatus,
        ::DriveBackupUiState,
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = DriveBackupUiState(),
    )

    private val mutableEffects = Channel<SettingsBackupEffect>(Channel.BUFFERED)
    val effects = mutableEffects.receiveAsFlow()

    private var preparedBackup: PreparedBackup? = null
    private var restoreCandidate: RestoreCandidate? = null
    private var restoreSource: RestoreSource? = null
    private var pendingDriveAction: DriveBackupAction? = null

    fun setTheme(value: ThemeMode) = viewModelScope.launch { repository.setTheme(value) }

    fun setPlaybackSpeedScale(scale: Float) {
        viewModelScope.launch { repository.setPlaybackSpeedScale(scale) }
    }

    fun setLongCommentReadability(enabled: Boolean) {
        viewModelScope.launch { repository.setLongCommentReadability(enabled) }
    }

    fun setCommentSizeScale(scale: Float) {
        viewModelScope.launch { repository.setCommentSizeScale(scale) }
    }

    fun setPlaybackSpeed(value: PlaybackSpeed) =
        viewModelScope.launch { repository.setPlaybackSpeed(value) }

    fun setCommentSize(value: CommentSize) =
        viewModelScope.launch { repository.setCommentSize(value) }

    fun setStageBackground(value: StageBackground) =
        viewModelScope.launch { repository.setStageBackground(value) }

    fun resetToDefaults() = viewModelScope.launch { repository.resetToDefaults() }

    fun enableDriveAutoBackup() = runDriveAction(DriveBackupAction.ENABLE_AUTO_BACKUP)

    fun disableDriveAutoBackup() = viewModelScope.launch {
        driveBackupCoordinator.setAutoBackupEnabled(false)
    }

    fun backupToDriveNow() = runDriveAction(DriveBackupAction.BACKUP_NOW)

    fun restoreFromDrive() = runDriveAction(DriveBackupAction.RESTORE_FROM_DRIVE)

    fun onDriveAuthorizationResult(resultCode: Int, data: Intent?) {
        val action = pendingDriveAction ?: return
        pendingDriveAction = null
        if (resultCode != Activity.RESULT_OK) {
            mutableBackupState.value = SettingsBackupUiState(
                message = "Google Driveへの接続をキャンセルしました。",
            )
            return
        }
        when (val authorization = driveBackupCoordinator.authorizationResultFromIntent(data)) {
            is DriveAuthorizationResult.Authorized -> runDriveAction(
                action,
                authorization.accessToken,
            )
            is DriveAuthorizationResult.ResolutionRequired -> {
                val pendingIntent = authorization.pendingIntent
                if (pendingIntent != null) {
                    pendingDriveAction = action
                    viewModelScope.launch {
                        mutableEffects.send(
                            SettingsBackupEffect.LaunchDriveAuthorization(pendingIntent),
                        )
                    }
                } else {
                    mutableBackupState.value = SettingsBackupUiState(
                        message = "Google Driveへ接続できませんでした。",
                    )
                }
            }
            DriveAuthorizationResult.Failed -> {
                mutableBackupState.value = SettingsBackupUiState(
                    message = "Google Driveへ接続できませんでした。",
                )
            }
        }
    }

    fun createBackup() {
        if (mutableBackupState.value.isBusy || restoreCandidate != null) return
        viewModelScope.launch {
            mutableBackupState.value = SettingsBackupUiState(operation = BackupOperation.PREPARING)
            try {
                val backup = backupEngine.prepareBackup()
                preparedBackup = backup
                mutableBackupState.value = SettingsBackupUiState(
                    operation = BackupOperation.WAITING_FOR_SAVE_LOCATION,
                )
                mutableEffects.send(SettingsBackupEffect.CreateDocument(backup.suggestedFileName))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableBackupState.value = SettingsBackupUiState(
                    message = "バックアップを作成できませんでした。",
                )
            }
        }
    }

    fun onBackupDestinationSelected(uri: Uri?) {
        val backup = preparedBackup
        if (uri == null || backup == null) {
            backup?.discard()
            preparedBackup = null
            mutableBackupState.value = SettingsBackupUiState()
            return
        }
        viewModelScope.launch {
            mutableBackupState.value = SettingsBackupUiState(operation = BackupOperation.WRITING)
            val written = backupFileStore.write(uri, backup.file)
            backup.discard()
            preparedBackup = null
            mutableBackupState.value = SettingsBackupUiState(
                message = if (written) {
                    "バックアップを保存しました。"
                } else {
                    "バックアップを保存できませんでした。"
                },
            )
        }
    }

    fun chooseRestoreFile() {
        if (mutableBackupState.value.isBusy || restoreCandidate != null) return
        mutableBackupState.value = SettingsBackupUiState(operation = BackupOperation.WAITING_FOR_SOURCE)
        viewModelScope.launch { mutableEffects.send(SettingsBackupEffect.OpenDocument) }
    }

    fun onRestoreSourceSelected(uri: Uri?) {
        if (uri == null) {
            mutableBackupState.value = SettingsBackupUiState()
            return
        }
        viewModelScope.launch {
            mutableBackupState.value = SettingsBackupUiState(operation = BackupOperation.READING)
            when (val file = backupFileStore.read(uri)) {
                is BackupFileReadResult.Success -> {
                    inspectRestoreFile(file.file)
                    file.discard()
                }
                BackupFileReadResult.TooLarge -> showRestoreError("バックアップファイルが大きすぎます。")
                BackupFileReadResult.Unavailable,
                BackupFileReadResult.IoFailure,
                -> showRestoreError("バックアップファイルを読み込めませんでした。")
            }
        }
    }

    fun cancelRestorePreview() {
        restoreCandidate?.discard()
        restoreCandidate = null
        restoreSource = null
        mutableBackupState.value = SettingsBackupUiState()
    }

    fun confirmRestore() {
        val candidate = restoreCandidate ?: return
        if (mutableBackupState.value.isBusy) return
        viewModelScope.launch {
            mutableBackupState.value = SettingsBackupUiState(operation = BackupOperation.RESTORING)
            try {
                val result = if (restoreSource == RestoreSource.DRIVE) {
                    when (driveBackupCoordinator.restore(candidate)) {
                        DriveBackupActionResult.Success -> BackupRestoreResult.Success
                        DriveBackupActionResult.SettingsRestoreFailed ->
                            BackupRestoreResult.SettingsFailure
                        else -> BackupRestoreResult.RoomFailure
                    }
                } else {
                    backupEngine.restore(candidate).also {
                        if (it == BackupRestoreResult.Success) driveBackupCoordinator.recordChange()
                    }
                }
                restoreCandidate = null
                restoreSource = null
                mutableBackupState.value = SettingsBackupUiState(
                    message = when (result) {
                        BackupRestoreResult.Success -> "バックアップを復元しました。"
                        BackupRestoreResult.SettingsFailure ->
                            "データは復元されましたが、設定を復元できませんでした。以前の設定を維持しています。"
                        BackupRestoreResult.InvalidCandidate,
                        BackupRestoreResult.RoomFailure,
                        -> "復元できませんでした。現在のデータは変更されていません。"
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                restoreCandidate = null
                restoreSource = null
                mutableBackupState.value = SettingsBackupUiState(
                    message = "復元できませんでした。現在のデータは変更されていません。",
                )
            }
        }
    }

    fun clearMessage() {
        mutableBackupState.value = mutableBackupState.value.copy(message = null)
    }

    override fun onCleared() {
        preparedBackup?.discard()
        restoreCandidate?.discard()
        super.onCleared()
    }

    private suspend fun inspectRestoreFile(file: java.io.File) {
        when (val inspection = backupEngine.inspect(file)) {
            is BackupInspectionResult.Ready -> {
                restoreCandidate = inspection.candidate
                restoreSource = RestoreSource.MANUAL
                mutableBackupState.value = SettingsBackupUiState(
                    restorePreview = inspection.candidate.preview,
                )
            }
            is BackupInspectionResult.Rejected -> {
                val message = when (inspection.error) {
                    BackupInspectionError.FILE_TOO_LARGE -> "バックアップファイルが大きすぎます。"
                    BackupInspectionError.UNSUPPORTED_VERSION ->
                        "このバージョンでは復元できないバックアップです。"
                    BackupInspectionError.INVALID_FILE,
                    BackupInspectionError.WRONG_FORMAT,
                    BackupInspectionError.INVALID_CONTENT,
                    -> "有効なMemoRippleバックアップではありません。"
                }
                showRestoreError(message)
            }
        }
    }

    private fun showRestoreError(message: String) {
        restoreCandidate = null
        restoreSource = null
        mutableBackupState.value = SettingsBackupUiState(message = message)
    }

    private fun runDriveAction(action: DriveBackupAction, accessToken: String? = null) {
        if (mutableBackupState.value.isBusy || restoreCandidate != null) return
        viewModelScope.launch {
            when (val result = driveBackupCoordinator.execute(action, accessToken)) {
                DriveBackupActionResult.Success -> {
                    mutableBackupState.value = SettingsBackupUiState(
                        message = if (action == DriveBackupAction.ENABLE_AUTO_BACKUP) {
                            "Google Driveの自動バックアップを有効にしました。"
                        } else {
                            "Google Driveへバックアップしました。"
                        },
                    )
                }
                DriveBackupActionResult.SettingsRestoreFailed -> {
                    mutableBackupState.value = SettingsBackupUiState(
                        message = "データは復元されましたが、設定を復元できませんでした。以前の設定を維持しています。",
                    )
                }
                is DriveBackupActionResult.RestoreReady -> {
                    restoreCandidate = result.candidate
                    restoreSource = RestoreSource.DRIVE
                    mutableBackupState.value = SettingsBackupUiState(
                        restorePreview = result.candidate.preview,
                    )
                }
                is DriveBackupActionResult.AuthorizationRequired -> {
                    pendingDriveAction = action
                    mutableEffects.send(
                        SettingsBackupEffect.LaunchDriveAuthorization(result.pendingIntent),
                    )
                }
                is DriveBackupActionResult.Failed -> {
                    mutableBackupState.value = SettingsBackupUiState(
                        message = result.error.manualMessage(action),
                    )
                }
                DriveBackupActionResult.Busy -> {
                    mutableBackupState.value = SettingsBackupUiState(
                        message = "別のバックアップ処理を実行中です。",
                    )
                }
            }
        }
    }

    companion object {
        fun factory(
            repository: SettingsRepository,
            backupEngine: BackupEngine,
            backupFileStore: SafBackupFileStore,
            driveBackupCoordinator: DriveBackupCoordinator,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(
                    repository,
                    backupEngine,
                    backupFileStore,
                    driveBackupCoordinator,
                ) as T
        }
    }
}

private fun io.github.cragcoffee.memoripple.drive.DriveBackupErrorKind.manualMessage(
    action: DriveBackupAction,
): String = when (this) {
    io.github.cragcoffee.memoripple.drive.DriveBackupErrorKind.NEEDS_AUTHORIZATION,
    io.github.cragcoffee.memoripple.drive.DriveBackupErrorKind.AUTHORIZATION_FAILED,
    -> "Google Driveへ接続できませんでした。"
    io.github.cragcoffee.memoripple.drive.DriveBackupErrorKind.FORBIDDEN ->
        "Google Driveへのアクセスが許可されていません。"
    io.github.cragcoffee.memoripple.drive.DriveBackupErrorKind.REMOTE_NOT_FOUND ->
        "Google Driveにバックアップがありません。"
    io.github.cragcoffee.memoripple.drive.DriveBackupErrorKind.REMOTE_INVALID ->
        "Google Driveのバックアップを読み込めませんでした。"
    io.github.cragcoffee.memoripple.drive.DriveBackupErrorKind.NETWORK ->
        "通信できませんでした。変更内容は次回のバックアップ対象として保持されています。"
    io.github.cragcoffee.memoripple.drive.DriveBackupErrorKind.RESTORE_FAILED ->
        "復元できませんでした。現在のデータは変更されていません。"
    io.github.cragcoffee.memoripple.drive.DriveBackupErrorKind.BACKUP_FAILED ->
        if (action == DriveBackupAction.RESTORE_FROM_DRIVE) {
            "Google Driveのバックアップを読み込めませんでした。"
        } else {
            "Google Driveへバックアップできませんでした。"
        }
}

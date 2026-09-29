package io.github.cragcoffee.memoripple.drive

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class DriveBackupErrorKind {
    NEEDS_AUTHORIZATION,
    AUTHORIZATION_FAILED,
    NETWORK,
    FORBIDDEN,
    REMOTE_NOT_FOUND,
    REMOTE_INVALID,
    BACKUP_FAILED,
    RESTORE_FAILED,
}

data class DriveBackupState(
    val autoBackupEnabled: Boolean = false,
    val remoteFileId: String? = null,
    val lastSuccessfulBackupAt: Long? = null,
    val changeGeneration: Long = 0,
    val lastUploadedGeneration: Long = -1,
    val lastError: DriveBackupErrorKind? = null,
) {
    val isDirty: Boolean get() = changeGeneration > lastUploadedGeneration
}

class DriveBackupStateStore(
    private val dataStore: DataStore<Preferences>,
) {
    val state: Flow<DriveBackupState> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::toState)
        .distinctUntilChanged()

    suspend fun setAutoBackupEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_BACKUP_ENABLED] = enabled }
    }

    suspend fun recordChange() {
        dataStore.edit { preferences ->
            preferences[Keys.CHANGE_GENERATION] =
                (preferences[Keys.CHANGE_GENERATION] ?: 0L) + 1L
        }
    }

    suspend fun recordUploadSuccess(
        remoteFileId: String,
        uploadedGeneration: Long,
        completedAt: Long,
    ) {
        dataStore.edit { preferences ->
            preferences[Keys.REMOTE_FILE_ID] = remoteFileId
            preferences[Keys.LAST_UPLOADED_GENERATION] = uploadedGeneration
            preferences[Keys.LAST_SUCCESSFUL_BACKUP_AT] = completedAt
            preferences.remove(Keys.LAST_ERROR)
        }
    }

    suspend fun setRemoteFileId(remoteFileId: String?) {
        dataStore.edit { preferences ->
            if (remoteFileId == null) preferences.remove(Keys.REMOTE_FILE_ID)
            else preferences[Keys.REMOTE_FILE_ID] = remoteFileId
        }
    }

    suspend fun setError(error: DriveBackupErrorKind?) {
        dataStore.edit { preferences ->
            if (error == null) preferences.remove(Keys.LAST_ERROR)
            else preferences[Keys.LAST_ERROR] = error.name
        }
    }

    private fun toState(preferences: Preferences): DriveBackupState = DriveBackupState(
        autoBackupEnabled = preferences[Keys.AUTO_BACKUP_ENABLED] ?: false,
        remoteFileId = preferences[Keys.REMOTE_FILE_ID],
        lastSuccessfulBackupAt = preferences[Keys.LAST_SUCCESSFUL_BACKUP_AT],
        changeGeneration = preferences[Keys.CHANGE_GENERATION] ?: 0L,
        lastUploadedGeneration = preferences[Keys.LAST_UPLOADED_GENERATION] ?: -1L,
        lastError = preferences[Keys.LAST_ERROR]?.let { stored ->
            DriveBackupErrorKind.entries.firstOrNull { it.name == stored }
        },
    )

    internal object Keys {
        val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
        val REMOTE_FILE_ID = stringPreferencesKey("remote_file_id")
        val LAST_SUCCESSFUL_BACKUP_AT = longPreferencesKey("last_successful_backup_at")
        val CHANGE_GENERATION = longPreferencesKey("change_generation")
        val LAST_UPLOADED_GENERATION = longPreferencesKey("last_uploaded_generation")
        val LAST_ERROR = stringPreferencesKey("last_error")
    }
}

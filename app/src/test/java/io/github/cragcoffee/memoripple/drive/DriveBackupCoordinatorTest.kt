package io.github.cragcoffee.memoripple.drive

import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.cragcoffee.memoripple.backup.BackupInspectionError
import io.github.cragcoffee.memoripple.backup.BackupInspectionResult
import io.github.cragcoffee.memoripple.backup.BackupPayloadDto
import io.github.cragcoffee.memoripple.backup.BackupRestoreResult
import io.github.cragcoffee.memoripple.backup.MemoRippleBackupDto
import io.github.cragcoffee.memoripple.backup.PreparedBackup
import io.github.cragcoffee.memoripple.backup.RestoreCandidate
import io.github.cragcoffee.memoripple.backup.RestorePreview
import io.github.cragcoffee.memoripple.backup.SettingsBackupDto
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DriveBackupCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DriveBackupStateStore
    private lateinit var auth: FakeAuthorization
    private lateinit var api: FakeDriveApi
    private lateinit var content: FakeBackupContent

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(temporaryFolder.root, "drive.preferences_pb")
        }
        store = DriveBackupStateStore(dataStore)
        auth = FakeAuthorization()
        api = FakeDriveApi()
        content = FakeBackupContent()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun alreadyAuthorizedUploadsExactPreparedBytesAndRecordsSuccess() = runBlocking {
        auth.results += DriveAuthorizationResult.Authorized("memory-token")
        val coordinator = coordinator(now = 4242L)

        val result = coordinator.execute(DriveBackupAction.BACKUP_NOW)

        assertEquals(DriveBackupActionResult.Success, result)
        assertArrayEquals(content.prepared.bytes, api.lastUploadedBytes)
        val state = store.state.first()
        assertEquals("created", state.remoteFileId)
        assertEquals(4242L, state.lastSuccessfulBackupAt)
        assertEquals(state.changeGeneration, state.lastUploadedGeneration)
    }

    @Test
    fun enablingAfterAuthorizationImmediatelyCreatesInitialBackup() = runBlocking {
        auth.results += DriveAuthorizationResult.Authorized("token")
        val coordinator = coordinator()

        val result = coordinator.execute(DriveBackupAction.ENABLE_AUTO_BACKUP)

        assertEquals(DriveBackupActionResult.Success, result)
        assertTrue(store.state.first().autoBackupEnabled)
        assertEquals(1, api.createCalls)
    }

    @Test
    fun authorizationFailureDoesNotTouchDriveOrSuccessMetadata() = runBlocking {
        auth.results += DriveAuthorizationResult.Failed

        val result = coordinator().execute(DriveBackupAction.BACKUP_NOW)

        assertEquals(
            DriveBackupActionResult.Failed(DriveBackupErrorKind.AUTHORIZATION_FAILED),
            result,
        )
        assertEquals(0, api.listCalls)
        assertNull(store.state.first().lastSuccessfulBackupAt)
    }

    @Test
    fun unauthorizedResponseClearsTokenAndSilentlyRetriesExactlyOnce() = runBlocking {
        auth.results += DriveAuthorizationResult.Authorized("expired")
        auth.results += DriveAuthorizationResult.Authorized("fresh")
        api.nextFailure = DriveApiResult.Unauthorized

        val result = coordinator().execute(DriveBackupAction.BACKUP_NOW)

        assertEquals(DriveBackupActionResult.Success, result)
        assertEquals(listOf("expired"), auth.clearedTokens)
        assertEquals(2, auth.authorizeCalls)
    }

    @Test
    fun automaticResolutionNeverProducesUiAndMarksNeedsAuthorization() = runBlocking {
        auth.results += DriveAuthorizationResult.ResolutionRequired(null)
        val coordinator = coordinator(autoDelay = 20L)
        store.setAutoBackupEnabled(true)
        coordinator.onForeground()
        coordinator.recordChange()

        delay(120L)

        assertEquals(DriveBackupErrorKind.NEEDS_AUTHORIZATION, store.state.first().lastError)
        assertEquals(0, api.listCalls)
    }

    @Test
    fun backgroundCancelsDebounceAndForegroundReschedulesDirtyBackup() = runBlocking {
        auth.results += DriveAuthorizationResult.Authorized("token")
        val coordinator = coordinator(autoDelay = 30L)
        store.setAutoBackupEnabled(true)
        coordinator.recordChange()
        coordinator.onBackground()
        delay(90L)
        assertEquals(0, api.createCalls)

        coordinator.onForeground()
        delay(120L)

        assertEquals(1, api.createCalls)
    }

    @Test
    fun changeDuringUploadRemainsDirtyAfterCapturedGenerationSucceeds() = runBlocking {
        auth.results += DriveAuthorizationResult.Authorized("token")
        api.onUpload = { store.recordChange() }
        val coordinator = coordinator()

        coordinator.execute(DriveBackupAction.BACKUP_NOW)

        val state = store.state.first()
        assertEquals(1L, state.changeGeneration)
        assertEquals(0L, state.lastUploadedGeneration)
        assertTrue(state.isDirty)
    }

    @Test
    fun serverFailurePreservesDirtyAndDoesNotAdvanceLastSuccess() = runBlocking {
        auth.results += DriveAuthorizationResult.Authorized("token")
        api.nextFailure = DriveApiResult.ServerFailure
        val coordinator = coordinator()
        coordinator.recordChange()

        val result = coordinator.execute(DriveBackupAction.BACKUP_NOW)

        assertEquals(DriveBackupActionResult.Failed(DriveBackupErrorKind.NETWORK), result)
        val state = store.state.first()
        assertTrue(state.isDirty)
        assertNull(state.lastSuccessfulBackupAt)
    }

    @Test
    fun forbiddenDoesNotRetryOrWidenAuthorization() = runBlocking {
        auth.results += DriveAuthorizationResult.Authorized("token")
        api.nextFailure = DriveApiResult.Forbidden

        val result = coordinator().execute(DriveBackupAction.BACKUP_NOW)

        assertEquals(DriveBackupActionResult.Failed(DriveBackupErrorKind.FORBIDDEN), result)
        assertEquals(1, auth.authorizeCalls)
        assertTrue(auth.clearedTokens.isEmpty())
    }

    @Test
    fun operationMutexRejectsOverlappingManualBackup() = runBlocking {
        auth.results += DriveAuthorizationResult.Authorized("token")
        content.prepareStarted = CompletableDeferred()
        content.allowPrepare = CompletableDeferred()
        val coordinator = coordinator()
        val first = async(Dispatchers.Default) {
            coordinator.execute(DriveBackupAction.BACKUP_NOW)
        }
        content.prepareStarted!!.await()

        val second = coordinator.execute(DriveBackupAction.BACKUP_NOW)

        assertEquals(DriveBackupActionResult.Busy, second)
        content.allowPrepare!!.complete(Unit)
        assertEquals(DriveBackupActionResult.Success, first.await())
    }

    @Test
    fun corruptRemoteIsRejectedBeforeRestoreAndDoesNotMutateLocalContent() = runBlocking {
        auth.results += DriveAuthorizationResult.Authorized("token")
        api.files += DriveRemoteFile("remote")
        content.inspection = BackupInspectionResult.Rejected(BackupInspectionError.INVALID_FILE)

        val result = coordinator().execute(DriveBackupAction.RESTORE_FROM_DRIVE)

        assertEquals(DriveBackupActionResult.Failed(DriveBackupErrorKind.REMOTE_INVALID), result)
        assertEquals(0, content.restoreCalls)
    }

    @Test
    fun validRemoteUsesInspectPreviewThenConfirmedRestoreAndMarksDirty() = runBlocking {
        auth.results += DriveAuthorizationResult.Authorized("token")
        api.files += DriveRemoteFile("remote")
        val candidate = candidate()
        content.inspection = BackupInspectionResult.Ready(candidate)
        val coordinator = coordinator()

        val preview = coordinator.execute(DriveBackupAction.RESTORE_FROM_DRIVE)
        val restored = coordinator.restore(
            (preview as DriveBackupActionResult.RestoreReady).candidate,
        )

        assertEquals(DriveBackupActionResult.Success, restored)
        assertEquals(1, content.restoreCalls)
        assertTrue(store.state.first().isDirty)
    }

    @Test
    fun disablingAutoBackupDoesNotDeleteRemoteMetadata() = runBlocking {
        store.recordUploadSuccess("remote", 0L, 1L)
        val coordinator = coordinator()
        coordinator.setAutoBackupEnabled(true)
        coordinator.setAutoBackupEnabled(false)

        val state = store.state.first()
        assertFalse(state.autoBackupEnabled)
        assertEquals("remote", state.remoteFileId)
    }

    private fun coordinator(now: Long = 100L, autoDelay: Long = 60_000L) =
        DriveBackupCoordinator(
            stateStore = store,
            authorization = auth,
            transport = DriveBackupTransport(api),
            content = content,
            scope = scope,
            nowMillis = { now },
            autoBackupDelayMillis = autoDelay,
        )

    private fun candidate(): RestoreCandidate = RestoreCandidate(
        document = MemoRippleBackupDto(
            format = "memorripple_backup",
            formatVersion = 2,
            exportedAt = 1,
            appVersionName = "test",
            appVersionCode = 1,
            payload = BackupPayloadDto(
                memos = emptyList(),
                memoComments = emptyList(),
                diaryEntries = emptyList(),
                futureDiaryComments = emptyList(),
                settings = SettingsBackupDto("system", "standard", "standard", "black"),
            ),
        ),
        preview = RestorePreview(1, 0, 0, 0, 0),
    )
}

private class FakeAuthorization : DriveAuthorizationGateway {
    val results = ArrayDeque<DriveAuthorizationResult>()
    val clearedTokens = mutableListOf<String>()
    var authorizeCalls = 0

    override suspend fun authorize(): DriveAuthorizationResult {
        authorizeCalls += 1
        return results.removeFirstOrNull() ?: DriveAuthorizationResult.Failed
    }

    override fun resultFromIntent(data: Intent?): DriveAuthorizationResult =
        results.removeFirstOrNull() ?: DriveAuthorizationResult.Failed

    override suspend fun clearToken(accessToken: String) {
        clearedTokens += accessToken
    }
}

private class FakeBackupContent : DriveBackupContent {
    val prepared = PreparedBackup(byteArrayOf(31, -117, 8), 1L, "backup.mrbackup")
    var inspection: BackupInspectionResult =
        BackupInspectionResult.Rejected(BackupInspectionError.INVALID_FILE)
    var restoreResult: BackupRestoreResult = BackupRestoreResult.Success
    var restoreCalls = 0
    var prepareStarted: CompletableDeferred<Unit>? = null
    var allowPrepare: CompletableDeferred<Unit>? = null

    override suspend fun prepareBackup(): PreparedBackup {
        prepareStarted?.complete(Unit)
        allowPrepare?.await()
        return prepared
    }

    override suspend fun inspect(bytes: ByteArray): BackupInspectionResult = inspection

    override suspend fun restore(candidate: RestoreCandidate): BackupRestoreResult {
        restoreCalls += 1
        return restoreResult
    }
}

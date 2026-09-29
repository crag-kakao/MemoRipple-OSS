package io.github.cragcoffee.memoripple.drive

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DriveBackupStateStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DriveBackupStateStore

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(temporaryFolder.root, "drive.preferences_pb")
        }
        store = DriveBackupStateStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun defaultsAreOptInAndDirtyUntilFirstBackup() = runBlocking {
        val state = store.state.first()

        assertFalse(state.autoBackupEnabled)
        assertNull(state.remoteFileId)
        assertNull(state.lastSuccessfulBackupAt)
        assertEquals(0L, state.changeGeneration)
        assertEquals(-1L, state.lastUploadedGeneration)
        assertTrue(state.isDirty)
    }

    @Test
    fun uploadCapturesOnlyItsGenerationAndLaterChangeStaysDirty() = runBlocking {
        store.recordChange()
        val uploadGeneration = store.state.first().changeGeneration
        store.recordChange()

        store.recordUploadSuccess("remote-1", uploadGeneration, 1234L)

        val state = store.state.first()
        assertEquals(2L, state.changeGeneration)
        assertEquals(1L, state.lastUploadedGeneration)
        assertEquals(1234L, state.lastSuccessfulBackupAt)
        assertTrue(state.isDirty)
    }

    @Test
    fun persistedPreferencesNeverContainTokensOrBackupBodies() = runBlocking {
        store.setAutoBackupEnabled(true)
        store.recordChange()
        store.recordUploadSuccess("remote-id", 1L, 999L)

        val keyNames = dataStore.data.first().asMap().keys.map { it.name }

        assertFalse(keyNames.any { it.contains("token", ignoreCase = true) })
        assertFalse(keyNames.any { it.contains("session", ignoreCase = true) })
        assertFalse(keyNames.any { it.contains("body", ignoreCase = true) })
        assertFalse(keyNames.any { it.contains("bytes", ignoreCase = true) })
    }
}

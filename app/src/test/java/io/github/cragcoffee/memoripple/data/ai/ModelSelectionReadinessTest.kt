package io.github.cragcoffee.memoripple.data.ai

import io.github.cragcoffee.memoripple.data.ai.models.FileModelStore
import io.github.cragcoffee.memoripple.domain.ai.ModelAvailability
import io.github.cragcoffee.memoripple.domain.ai.ModelUnavailableReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Phase 7 RED: the product selection answers *why* the AI is unavailable — nothing selected, the
 * selected file gone, the selected file the wrong length — before any runtime exists, and never
 * substitutes another installed model for the one the user chose.
 */
class ModelSelectionReadinessTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var noBackup: File
    private lateinit var store: FileModelStore
    private var selectedId: String? = null
    private val sizes = HashMap<String, Long>()

    private fun selection(debug: Boolean = false) = ProductModelSelection(noBackup, store, isDebugBuild = debug, expectedBytes = { sizes[it] }) { selectedId }

    private fun install(id: String, length: Int = 10) {
        val bytes = ByteArray(length) { 1 }
        store.openPart(id, resume = false).use { it.write(bytes) }
        runBlocking { store.verifyAndCommit(id, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }) }
        sizes[id] = length.toLong()
    }

    @Before
    fun dirs() {
        noBackup = tmp.newFolder("no_backup")
        store = FileModelStore(noBackup)
    }

    @Test
    fun nothingSelectedIsNoModelConfiguredEvenWhenSomethingIsInstalled() {
        install("qwen3-4b-instruct-2507")
        assertEquals(ModelAvailability.Unavailable(ModelUnavailableReason.NO_MODEL_CONFIGURED), runBlocking { selection().availability() })
        assertNull(runBlocking { selection().selected() })
    }

    @Test
    fun aSelectedInstalledModelOfTheExpectedLengthIsAvailable() {
        install("qwen3-4b-instruct-2507")
        selectedId = "qwen3-4b-instruct-2507"
        assertEquals(ModelAvailability.Available(ModelProfiles.qwen3_4bInstruct2507.descriptor), runBlocking { selection().availability() })
    }

    @Test
    fun aSelectedModelWhoseFileIsGoneIsMissingNotUnconfigured() {
        install("qwen3-4b-instruct-2507")
        selectedId = "qwen3-4b-instruct-2507"
        File(noBackup, "models/qwen3-4b-instruct-2507/model.gguf").delete()
        assertEquals(ModelAvailability.Unavailable(ModelUnavailableReason.MODEL_FILE_MISSING), runBlocking { selection().availability() })
        assertNull(runBlocking { selection().selected() })
    }

    @Test
    fun aSelectedModelOfTheWrongLengthIsCorruptAndNoOtherInstalledModelIsPickedInstead() {
        install("qwen3-4b-instruct-2507")
        install("ministral-3-3b-instruct-2512")
        selectedId = "qwen3-4b-instruct-2507"
        File(noBackup, "models/qwen3-4b-instruct-2507/model.gguf").writeBytes(ByteArray(7))
        assertEquals(ModelAvailability.Unavailable(ModelUnavailableReason.MODEL_FILE_CORRUPT), runBlocking { selection().availability() })
        assertNull("no silent fallback to the other installed model", runBlocking { selection().selected() })
    }

    @Test
    fun aSelectedIdOutsideTheCatalogIsNoModelConfigured() {
        selectedId = "gemma-3-1b"
        assertEquals(ModelAvailability.Unavailable(ModelUnavailableReason.NO_MODEL_CONFIGURED), runBlocking { selection().availability() })
    }

    @Test
    fun aModelWithNoKnownLengthIsCheckedForPresenceOnly() {
        install("qwen3-4b-instruct-2507")
        sizes.clear()
        selectedId = "qwen3-4b-instruct-2507"
        assertEquals(ModelAvailability.Available(ModelProfiles.qwen3_4bInstruct2507.descriptor), runBlocking { selection().availability() })
    }

    @Test
    fun aDeveloperPathThatDoesNotExistIsMissingInADebugBuildAndIgnoredInRelease() {
        File(noBackup, "models").mkdirs()
        File(noBackup, "models/developer.properties").writeText("modelId=qwen3-4b-instruct-2507\npath=${tmp.root}/nowhere.gguf\n")
        assertEquals(ModelAvailability.Unavailable(ModelUnavailableReason.MODEL_FILE_MISSING), runBlocking { selection(debug = true).availability() })
        assertEquals("release: the file is never read", ModelAvailability.Unavailable(ModelUnavailableReason.NO_MODEL_CONFIGURED), runBlocking { selection(debug = false).availability() })
    }
}

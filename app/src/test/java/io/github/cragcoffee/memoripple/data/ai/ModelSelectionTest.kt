package io.github.cragcoffee.memoripple.data.ai

import io.github.cragcoffee.memoripple.data.ai.models.FileModelStore
import io.github.cragcoffee.memoripple.data.ai.models.ModelCatalog
import io.github.cragcoffee.memoripple.domain.ai.runtime.LoadResult
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelFileResolver
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelLocation
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeFailure
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Phase 5 model selection (docs/AI_MODEL_MANAGEMENT.md): the runtime loads the model the user
 * **selected** and which is **installed** under `models/<id>/model.gguf` — never a candidate
 * that merely happens to be on disk (no unilateral default). A debug build may still name a
 * model and a developer path in `models/developer.properties`; a release build ignores it.
 */
class ModelSelectionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var noBackup: File
    private lateinit var store: FileModelStore
    private var selectedId: String? = null

    // Phase 7: the selection checks the installed length against the catalog; these fakes are 10 bytes long
    private fun selection(debug: Boolean = true) = ProductModelSelection(noBackup, store, isDebugBuild = debug, expectedBytes = { 10L }) { selectedId }

    private fun install(id: String) {
        val bytes = ByteArray(10) { 1 }
        store.openPart(id, resume = false).use { it.write(bytes) }
        runBlocking { store.verifyAndCommit(id, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }) }
    }

    @org.junit.Before
    fun dirs() {
        noBackup = tmp.newFolder("no_backup")
        store = FileModelStore(noBackup)
    }

    @Test
    fun nothingInstalledAndNothingSelectedIsNoModel() {
        assertNull(runBlocking { selection().selected() })
    }

    @Test
    fun anInstalledCandidateIsNotUsedUntilTheUserSelectsIt() {
        install("qwen3-4b-instruct-2507")
        assertNull("installed ≠ selected: no unilateral default", runBlocking { selection().selected() })
        selectedId = "qwen3-4b-instruct-2507"
        val d = runBlocking { selection().selected() }!!
        assertEquals(ModelProfiles.qwen3_4bInstruct2507.descriptor, d)
        assertEquals(ModelLocation.Installed("qwen3-4b-instruct-2507"), d.location)
        assertEquals(File(noBackup, "models/qwen3-4b-instruct-2507/model.gguf"), ModelFileResolver(noBackup, FileModelStore.MODEL_FILE).resolve(d.location))
    }

    @Test
    fun aSelectedModelThatIsNotInstalledIsNoModel() {
        selectedId = "ministral-3-3b-instruct-2512"
        assertNull(runBlocking { selection().selected() })
        install("ministral-3-3b-instruct-2512")
        assertEquals(ModelProfiles.ministral3_3bInstruct2512.descriptor, runBlocking { selection().selected() })
    }

    @Test
    fun aSelectedIdOutsideTheCatalogIsNoModel() {
        selectedId = "gemma-3-1b"
        assertNull(runBlocking { selection().selected() })
    }

    @Test
    fun theInstalledLocationRefusesAnUnsafeId() {
        listOf("../x", "a/b", "").forEach { bad ->
            assertTrue(runCatching { ModelFileResolver(noBackup, FileModelStore.MODEL_FILE).resolve(ModelLocation.Installed(bad)) }.isFailure)
        }
    }

    @Test
    fun aDeveloperFileNamesTheModelAndItsPathInADebugBuildOnly() {
        File(noBackup, "models").mkdirs()
        // Phase 7: a developer path must exist to be Available (a missing one is MODEL_FILE_MISSING, ModelSelectionReadinessTest)
        val developerModel = tmp.newFile("q.gguf")
        File(noBackup, "models/developer.properties").writeText("modelId=qwen3-4b-instruct-2507\npath=${developerModel.absolutePath}\n")
        val d = runBlocking { selection().selected() }!!
        assertEquals("qwen3-4b-instruct-2507", d.id)
        assertEquals(ModelLocation.DeveloperPath(developerModel.absolutePath), d.location)
        assertNull("a release build never reads the developer file", runBlocking { selection(debug = false).selected() })
        File(noBackup, "models/developer.properties").writeText("modelId=gemma-3-1b\n")
        assertNull("an unknown id in the developer file is no model, not a guess", runBlocking { selection().selected() })
    }

    @Test
    fun theCatalogAndTheProfilesAgreeOnIds() {
        assertEquals(ModelCatalog.all.map { it.modelId }.toSet(), ModelProfiles.all.map { it.descriptor.id }.toSet())
    }

    @Test
    fun anUnsupportedCpuGetsARuntimeThatRefusesAndNeverBuildsTheEngine() {
        val runtime = productLocalModelRuntime(noBackup, hasDotProduct = false) { error("the engine must not be constructed on an unsupported CPU") }
        assertEquals(RuntimeState.UNLOADED, runtime.state())
        val r = runBlocking { runtime.load(ModelProfiles.qwen3_4bInstruct2507.descriptor) }
        assertEquals(LoadResult.Failed(RuntimeFailure.UNSUPPORTED_DEVICE), r)
        assertEquals(RuntimeState.UNLOADED, runtime.state())
        runBlocking { runtime.unload() }
    }
}

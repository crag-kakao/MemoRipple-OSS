package io.github.cragcoffee.memoripple

import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.ai.models.ModelCatalog
import io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult
import io.github.cragcoffee.memoripple.domain.ai.AiResultContext
import io.github.cragcoffee.memoripple.domain.ai.models.DeleteOutcome
import io.github.cragcoffee.memoripple.domain.ai.models.DownloadRequest
import io.github.cragcoffee.memoripple.domain.ai.models.InstallState
import io.github.cragcoffee.memoripple.domain.ai.models.SelectOutcome
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The one real end-to-end of Phase 5 (docs/AI_MODEL_MANAGEMENT.md §12): the product manager
 * downloads Ministral 3 3B from the pinned URL into `models/<id>/model.gguf`, the SHA-256 matches,
 * the model is selected the way the user does it, one AI ask runs through the runtime **from the
 * installed file** (no developer path), and the model is deleted through the manager afterwards.
 * Nothing under `/data/local/tmp` and no document is touched. Runs only with:
 *
 *   am instrument -w -r -e class io.github.cragcoffee.memoripple.AiModelDownloadSmokeTest -e realDownload true \
 *      io.github.cragcoffee.memoripple.test/io.github.cragcoffee.memoripple.MemoRippleTestRunner
 *
 * and only on an unmetered network (a metered one is skipped, never paid for).
 */
class AiModelDownloadSmokeTest {
    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    private val entry = ModelCatalog.ministral3_3bInstruct2512
    private var installedHere = false

    private fun log(message: String) = android.util.Log.i("AiModelSmoke", message)

    @After
    fun removeWhatWasMadeHere() {
        runBlocking {
            application.aiOrchestrator.release()
            if (installedHere) application.modelManager.delete(entry.modelId)
        }
    }

    @Test
    fun ministralIsDownloadedVerifiedSelectedUsedOnceAndDeleted() {
        assumeTrue("real download only when asked for", InstrumentationRegistry.getArguments().getString("realDownload") == "true")
        val developerFile = File(application.noBackupFilesDir, "models/developer.properties")
        assumeTrue("no developer file may stand in for the installed model", !developerFile.exists())
        val manager = application.modelManager
        runBlocking { manager.refresh() }
        val phase0 = File("/data/local/tmp/llmbench/models").list()?.sorted().orEmpty()
        val docsBefore = runBlocking { application.database.memoDao().allIds().size to application.database.diaryDao().observeAll().first().size }
        assertEquals(InstallState.NotInstalled, manager.states.value.getValue(entry.modelId))
        assertNull(runBlocking { application.settingsRepository.selectedAiModelId.first() })

        val t0 = System.currentTimeMillis()
        val request = runBlocking { manager.requestDownload(entry.modelId, allowMetered = false) }
        assumeTrue("a metered network is never paid for by this test: $request", request !is DownloadRequest.NeedsMeteredConfirmation)
        assertEquals("$request", DownloadRequest.Started, request)
        installedHere = true
        var lastTenth = -1
        val final = runBlocking {
            withTimeout(25 * 60_000L) {
                manager.states.first { states ->
                    val s = states.getValue(entry.modelId)
                    if (s is InstallState.Downloading && s.totalBytes > 0) {
                        val tenth = (s.bytes * 10 / s.totalBytes).toInt()
                        if (tenth != lastTenth) { lastTenth = tenth; log("downloading ${s.bytes} / ${s.totalBytes} (${System.currentTimeMillis() - t0} ms)") }
                    }
                    s is InstallState.Installed || s is InstallState.Failed
                }.getValue(entry.modelId)
            }
        }
        log("download end → $final in ${System.currentTimeMillis() - t0} ms")
        assertTrue("$final", final is InstallState.Installed)
        val file = File(application.noBackupFilesDir, "models/${entry.modelId}/model.gguf")
        assertEquals(entry.approximateDownloadBytes, file.length())
        assertFalse(File(application.noBackupFilesDir, "models/${entry.modelId}/model.gguf.part").exists())

        assertEquals(SelectOutcome.Selected, runBlocking { manager.select(entry.modelId) })
        assertEquals(entry.modelId, runBlocking { application.settingsRepository.selectedAiModelId.first() })
        assertEquals("selecting loads nothing", RuntimeState.UNLOADED, application.aiOrchestrator.runtimeState())

        val t1 = System.currentTimeMillis()
        val result = runBlocking { application.aiOrchestrator.interact("昨日の日記を探して", AiResultContext.EMPTY) }
        log("ask through the installed file → ${result::class.simpleName} in ${System.currentTimeMillis() - t1} ms")
        assertTrue("$result", result is AiInteractionResult.SearchResults)
        assertEquals(RuntimeState.READY, application.aiOrchestrator.runtimeState())

        assertEquals(DeleteOutcome.Deleted(wasSelected = true), runBlocking { manager.delete(entry.modelId) })
        installedHere = false
        assertFalse(file.parentFile!!.exists())
        assertNull(runBlocking { application.settingsRepository.selectedAiModelId.first() })
        assertEquals(RuntimeState.UNLOADED, application.aiOrchestrator.runtimeState())
        assertEquals("the Phase 0 files are untouched", phase0, File("/data/local/tmp/llmbench/models").list()?.sorted().orEmpty())
        assertEquals("no document changed", docsBefore, runBlocking { application.database.memoDao().allIds().size to application.database.diaryDao().observeAll().first().size })
        log("deleted; done")
    }
}

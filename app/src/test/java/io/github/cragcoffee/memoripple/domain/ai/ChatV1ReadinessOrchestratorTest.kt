package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
import io.github.cragcoffee.memoripple.domain.ai.runtime.StatusThermalGate
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 7 RED (docs/CHAT_V1_RELEASE_READINESS.md): the orchestrator answers *whether the AI can be
 * asked at all* before a screen offers an input — without constructing the runtime — and a
 * selected model whose file is missing or corrupt is unavailable **before** any load; nothing
 * silently switches to another model; retrying is the user's tap, never a loop.
 */
class ChatV1ReadinessOrchestratorTest {
    private val time = FixedTime()
    private val docs = FakeDocumentAccess(time)
    private val runtime = FakeAiRuntime()
    private var thermalStatus = 0
    private var availability: ModelAvailability = ModelAvailability.Available(TEST_MODEL)
    private var runtimeCreated = 0
    private var deviceSupported = true
    private val templates = FakeTemplates(listOf(MemoTemplate("t1", "週次レビュー", "## 今週\n- \n## 来週\n- ")))

    private val selection = object : ModelSelection {
        override suspend fun selected() = (availability as? ModelAvailability.Available)?.model
        override suspend fun availability() = availability
    }

    private fun orchestrator() = LocalAiOrchestrator(
        runtime = { runtimeCreated++; runtime },
        selection = selection,
        thermal = StatusThermalGate { thermalStatus },
        assets = OrchestratorPromptAssets,
        resolver = Resolver(docs, templates, time),
        executor = CommandExecutor(docs),
        deviceSupported = { deviceSupported },
    )

    // --- RED 1, 4, 5: availability is a question a screen may ask before the input is shown ---

    @Test
    fun availabilityIsAnsweredWithoutCreatingTheRuntime() {
        val o = orchestrator()
        assertEquals(ModelAvailability.Available(TEST_MODEL), runBlocking { o.availability() })
        availability = ModelAvailability.Unavailable(ModelUnavailableReason.NO_MODEL_CONFIGURED)
        assertEquals(ModelAvailability.Unavailable(ModelUnavailableReason.NO_MODEL_CONFIGURED), runBlocking { o.availability() })
        assertEquals("the engine is never constructed to answer this", 0, runtimeCreated)
        assertEquals(RuntimeState.UNLOADED, o.runtimeState())
    }

    // --- RED 6, 7, 8: a missing or corrupt file stops before the load; no other model is picked ---

    @Test
    fun aSelectedModelWhoseFileIsMissingIsUnavailableBeforeAnyLoad() {
        availability = ModelAvailability.Unavailable(ModelUnavailableReason.MODEL_FILE_MISSING)
        val r = runBlocking { orchestrator().interact("昨日の日記を探して", AiResultContext.EMPTY) }
        assertEquals(AiInteractionResult.ModelUnavailable(ModelUnavailableReason.MODEL_FILE_MISSING), r)
        assertEquals(0, runtime.loads)
        assertEquals(0, runtimeCreated)
    }

    @Test
    fun aSelectedModelWhoseFileIsCorruptIsUnavailableBeforeAnyLoadAndNothingElseIsTried() {
        availability = ModelAvailability.Unavailable(ModelUnavailableReason.MODEL_FILE_CORRUPT)
        val o = orchestrator()
        assertEquals(AiInteractionResult.ModelUnavailable(ModelUnavailableReason.MODEL_FILE_CORRUPT), runBlocking { o.interact("昨日の日記を探して", AiResultContext.EMPTY) })
        assertEquals(ModelAvailability.Unavailable(ModelUnavailableReason.MODEL_FILE_CORRUPT), runBlocking { o.availability() })
        assertEquals("no load, no engine, no fallback", 0, runtime.loads + runtimeCreated)
        assertEquals(0, docs.searches)
    }

    // --- RED 9, 10: an unsupported CPU is known before the engine exists ---

    @Test
    fun anUnsupportedCpuIsUnavailableWithoutConstructingTheRuntime() {
        deviceSupported = false
        val o = orchestrator()
        assertEquals(ModelAvailability.Unavailable(ModelUnavailableReason.UNSUPPORTED_DEVICE), runBlocking { o.availability() })
        assertEquals(AiInteractionResult.ModelUnavailable(ModelUnavailableReason.UNSUPPORTED_DEVICE), runBlocking { o.interact("散歩を探して", AiResultContext.EMPTY) })
        assertEquals(0, runtimeCreated)
    }

    // --- RED 18: thermal blocks a generation, but is not an availability question (the input stays; the ask is refused) ---

    @Test
    fun aHotDeviceIsAvailableButRefusesTheGeneration() {
        thermalStatus = 3
        val o = orchestrator()
        assertEquals(ModelAvailability.Available(TEST_MODEL), runBlocking { o.availability() })
        assertEquals(AiInteractionResult.ThermalBlocked, runBlocking { o.interact("散歩を探して", AiResultContext.EMPTY) })
        assertEquals(0, runtime.loads)
    }

    // --- RED 21, 22: a failed load or generation is retried only by a new interact(); the runtime is reusable ---

    @Test
    fun aFailedLoadLeavesTheRuntimeReadyForTheUsersRetryAndNothingRetriesByItself() {
        runtime.loadFailure = io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeFailure.ENGINE_ERROR
        val o = orchestrator()
        val first = runBlocking { o.interact("散歩を探して", AiResultContext.EMPTY) }
        assertEquals(AiFailureStage.LOAD, (first as AiInteractionResult.RuntimeError).stage)
        assertEquals("one attempt per ask", 1, runtime.loads)
        runtime.loadFailure = null
        runtime.answers += proposalJson("SEARCH", query = "散歩")
        val second = runBlocking { o.interact("散歩を探して", AiResultContext.EMPTY) }
        assertEquals(true, second is AiInteractionResult.SearchResults)
        assertEquals(2, runtime.loads)
    }

    @Test
    fun aFailedGenerationIsRetriedOnlyByTheNextAsk() {
        val o = orchestrator()
        val first = runBlocking { o.interact("散歩を探して", AiResultContext.EMPTY) }   // no answer scripted → engine error
        assertEquals(AiFailureStage.GENERATION, (first as AiInteractionResult.RuntimeError).stage)
        assertEquals(1, runtime.requests.size)
        runtime.answers += proposalJson("SEARCH", query = "散歩")
        val second = runBlocking { o.interact("散歩を探して", AiResultContext.EMPTY) }
        assertEquals(true, second is AiInteractionResult.SearchResults)
        assertEquals(2, runtime.requests.size)
        assertEquals("the model was loaded once and reused", 1, runtime.loads)
    }
}

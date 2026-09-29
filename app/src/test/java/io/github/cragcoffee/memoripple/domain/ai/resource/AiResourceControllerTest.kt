package io.github.cragcoffee.memoripple.domain.ai.resource

import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationRequest
import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationResult
import io.github.cragcoffee.memoripple.domain.ai.runtime.LoadResult
import io.github.cragcoffee.memoripple.domain.ai.runtime.LocalModelRuntime
import io.github.cragcoffee.memoripple.domain.ai.runtime.MemoryPressure
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelCapability
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelDescriptor
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelLocation
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeFailure
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
import io.github.cragcoffee.memoripple.domain.ai.runtime.StatusThermalGate
import io.github.cragcoffee.memoripple.domain.ai.runtime.StopReason
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI Resource Controller Phase 2 (docs/AI_RESOURCE_CONTROLLER.md, human brief 2026-09-23): the
 * application-scoped owner of the generation model's lifecycle — lazy load, keep warm, idle
 * unload, memory / power / thermal policy, budget. The controller knows nothing of intents,
 * resolvers, previews or documents; a lease brackets exactly one generation.
 *
 * All timing runs on the test scheduler's virtual clock — no real sleeps.
 */
class AiResourceControllerTest {

    private class FakeRuntime : LocalModelRuntime {
        @Volatile var loads = 0
        @Volatile var unloads = 0
        @Volatile var state = RuntimeState.UNLOADED
        @Volatile var loadDelayMillis = 0L
        @Volatile var unloadDelayMillis = 0L
        @Volatile var failLoad: RuntimeFailure? = null
        @Volatile var lastLoaded: ModelDescriptor? = null

        override fun state() = state
        override suspend fun load(model: ModelDescriptor): LoadResult {
            loads++
            state = RuntimeState.LOADING
            if (loadDelayMillis > 0) delay(loadDelayMillis)
            failLoad?.let { state = RuntimeState.UNLOADED; return LoadResult.Failed(it) }
            lastLoaded = model
            state = RuntimeState.READY
            return LoadResult.Loaded(model, 7)
        }
        override suspend fun generate(request: GenerationRequest): GenerationResult {
            state = RuntimeState.GENERATING
            state = RuntimeState.READY
            return GenerationResult.Generated("{}", 1, 1, 1, 1, StopReason.END)
        }
        override suspend fun unload() {
            if (unloadDelayMillis > 0) delay(unloadDelayMillis)
            if (state != RuntimeState.UNLOADED) unloads++
            state = RuntimeState.UNLOADED
        }
    }

    private val model = ModelDescriptor(
        id = "qwen3-4b-instruct-2507", displayName = "Qwen", location = ModelLocation.Installed("qwen3-4b-instruct-2507"),
        architecture = "qwen3", contextSize = 4096, quantization = "Q4_K_M", capabilities = setOf(ModelCapability.JAPANESE),
    )
    private val modelB = model.copy(id = "ministral-3b-instruct-2512", location = ModelLocation.Installed("ministral-3b-instruct-2512"))

    private var runtimeCreated = 0
    private val runtime = FakeRuntime()

    private fun kotlinx.coroutines.test.TestScope.controller(
        powerSaver: () -> Boolean = { false },
        thermalStatus: () -> Int = { 0 },
        idle: IdleUnloadPolicy = IdleUnloadPolicy(timeoutMillis = 120_000, reducedTimeoutMillis = 30_000),
    ) = DefaultAiResourceController(
        runtime = { runtimeCreated++; runtime },
        scope = backgroundScope,
        idle = idle,
        thermal = StatusThermalGate(thermalStatus),
        powerSaver = powerSaver,
    )

    // --- Lazy load (RED 1, 9) ---

    @Test
    fun constructingTheControllerCreatesAndLoadsNothing() = runTest {
        val c = controller()
        assertEquals(0, runtimeCreated)
        assertEquals(0, runtime.loads)
        assertEquals(AiResourceState.Unloaded, c.state())
        assertEquals(RuntimeState.UNLOADED, c.runtimeState())
    }

    @Test
    fun theFirstAcquireLoadsExactlyOnceAndReportsLoading() = runTest {
        val c = controller()
        var loading = 0
        val lease = (c.acquireForGeneration(model) { loading++ } as AcquireOutcome.Acquired).lease
        assertEquals(1, runtime.loads)
        assertEquals(1, loading)
        assertSame(runtime, lease.runtime)
        assertEquals(RuntimeState.READY, c.runtimeState())
        lease.release()
    }

    // --- Keep warm (RED 10–13) ---

    @Test
    fun aSecondAcquireInsideTheWarmPeriodReloadsNothingAndReusesTheRuntime() = runTest {
        val c = controller()
        var loading = 0
        (c.acquireForGeneration(model) { loading++ } as AcquireOutcome.Acquired).lease.release()
        advanceTimeBy(60_000)   // inside the 120 s window
        val second = (c.acquireForGeneration(model) { loading++ } as AcquireOutcome.Acquired).lease
        assertEquals(1, runtime.loads)
        assertEquals(1, runtimeCreated)
        assertEquals("no second loading callback for a warm model", 1, loading)
        second.release()
    }

    @Test
    fun eachReleaseStartsAFreshIdleWindow() = runTest {
        val c = controller()
        (c.acquireForGeneration(model) as AcquireOutcome.Acquired).lease.release()
        advanceTimeBy(100_000)
        (c.acquireForGeneration(model) as AcquireOutcome.Acquired).lease.release()
        advanceTimeBy(100_000)   // 200 s after the first release, 100 s after the second
        assertEquals(0, runtime.unloads)
        advanceTimeBy(21_000)
        assertEquals(1, runtime.unloads)
    }

    // --- Idle unload (RED 14–15) ---

    @Test
    fun theIdleTimeoutUnloadsExactlyOnce() = runTest {
        val c = controller()
        (c.acquireForGeneration(model) as AcquireOutcome.Acquired).lease.release()
        advanceTimeBy(121_000)
        assertEquals(1, runtime.unloads)
        assertEquals(AiResourceState.Unloaded, c.state())
        assertEquals(UnloadReason.IDLE, c.metrics().lastUnloadReason)
        advanceTimeBy(600_000)
        assertEquals("the timer fires once", 1, runtime.unloads)
    }

    @Test
    fun anActiveLeaseIsNeverIdleUnloaded() = runTest {
        val c = controller()
        val lease = (c.acquireForGeneration(model) as AcquireOutcome.Acquired).lease
        advanceTimeBy(600_000)
        assertEquals(0, runtime.unloads)
        assertEquals(RuntimeState.READY, c.runtimeState())
        lease.release()
        advanceTimeBy(121_000)
        assertEquals(1, runtime.unloads)
    }

    @Test
    fun releaseIsIdempotentAndOneLeaseCannotHoldTheModelTwice() = runTest {
        val c = controller()
        val lease = (c.acquireForGeneration(model) as AcquireOutcome.Acquired).lease
        lease.release()
        lease.release()
        advanceTimeBy(121_000)
        assertEquals(1, runtime.unloads)
    }

    // --- Concurrency (RED 21–25) ---

    @Test
    fun simultaneousAcquiresLoadOnce() = runTest {
        runtime.loadDelayMillis = 1_000
        val c = controller()
        val a = async { c.acquireForGeneration(model) }
        val b = async { c.acquireForGeneration(model) }
        val leases = listOf(a.await(), b.await()).map { (it as AcquireOutcome.Acquired).lease }
        assertEquals(1, runtime.loads)
        assertEquals(1, runtimeCreated)
        leases.forEach { it.release() }
        advanceTimeBy(121_000)
        assertEquals("one idle unload after the last release", 1, runtime.unloads)
    }

    @Test
    fun theIdleTimerWaitsForTheLastActiveLease() = runTest {
        val c = controller()
        val first = (c.acquireForGeneration(model) as AcquireOutcome.Acquired).lease
        val second = (c.acquireForGeneration(model) as AcquireOutcome.Acquired).lease
        first.release()
        advanceTimeBy(600_000)
        assertEquals("a live lease holds the model", 0, runtime.unloads)
        second.release()
        advanceTimeBy(121_000)
        assertEquals(1, runtime.unloads)
    }

    @Test
    fun unloadAndLoadSerializeWithoutARace() = runTest {
        runtime.unloadDelayMillis = 500
        val c = controller()
        (c.acquireForGeneration(model) as AcquireOutcome.Acquired).lease.release()
        val unloading = launch { c.unload(UnloadReason.EXPLICIT) }
        val reacquire = async { c.acquireForGeneration(model) }
        unloading.join()
        val lease = (reacquire.await() as AcquireOutcome.Acquired).lease
        assertEquals(2, runtime.loads)
        assertEquals(1, runtime.unloads)
        assertEquals(RuntimeState.READY, c.runtimeState())
        lease.release()
    }

    @Test
    fun aDuplicateUnloadRunsTheRuntimeUnloadOnce() = runTest {
        val c = controller()
        (c.acquireForGeneration(model) as AcquireOutcome.Acquired).lease.release()
        c.unload(UnloadReason.EXPLICIT)
        c.unload(UnloadReason.EXPLICIT)
        assertEquals(1, runtime.unloads)
        assertEquals(1, c.metrics().unloads)
    }

    @Test
    fun switchingModelsUnloadsTheOldAndLoadsTheNewOnlyOnTheNextAcquire() = runTest {
        val c = controller()
        (c.acquireForGeneration(model) as AcquireOutcome.Acquired).lease.release()
        c.unload(UnloadReason.EXPLICIT)   // what the model manager's select does through release()
        assertEquals(1, runtime.unloads)
        assertEquals("nothing loads on the switch itself", 1, runtime.loads)
        val lease = (c.acquireForGeneration(modelB) as AcquireOutcome.Acquired).lease
        assertEquals(2, runtime.loads)
        assertEquals(modelB.id, runtime.lastLoaded?.id)
        lease.release()
    }

    // --- Memory pressure (RED 26–32) ---

    @Test
    fun lowPressureUnloadsAnIdleModelAtOnce() = runTest {
        val c = controller()
        (c.acquireForGeneration(model) as AcquireOutcome.Acquired).lease.release()
        c.onMemoryPressure(MemoryPressure.LOW)
        assertEquals(1, runtime.unloads)
        assertEquals(UnloadReason.MEMORY_LOW, c.metrics().lastUnloadReason)
    }

    @Test
    fun lowPressureDuringAnActiveLeaseDefersTheUnloadUntilTheRelease() = runTest {
        val c = controller()
        val lease = (c.acquireForGeneration(model) as AcquireOutcome.Acquired).lease
        c.onMemoryPressure(MemoryPressure.LOW)
        assertEquals("nothing cancelled mid-request", 0, runtime.unloads)
        assertEquals(RuntimeState.READY, c.runtimeState())
        lease.release()
        runCurrent()
        assertEquals("the deferred unload runs after the request", 1, runtime.unloads)
        assertEquals(UnloadReason.MEMORY_LOW, c.metrics().lastUnloadReason)
    }

    @Test
    fun criticalPressureUnloadsEvenWithAnActiveLeaseAndTheReleaseDoesNotDoubleFree() = runTest {
        val c = controller()
        val lease = (c.acquireForGeneration(model) as AcquireOutcome.Acquired).lease
        c.onMemoryPressure(MemoryPressure.CRITICAL)
        assertEquals(1, runtime.unloads)
        assertEquals(UnloadReason.MEMORY_CRITICAL, c.metrics().lastUnloadReason)
        lease.release()
        advanceTimeBy(600_000)
        assertEquals("the release after a critical unload frees nothing again", 1, runtime.unloads)
    }

    @Test
    fun moderatePressureDoesNothing() = runTest {
        val c = controller()
        (c.acquireForGeneration(model) as AcquireOutcome.Acquired).lease.release()
        c.onMemoryPressure(MemoryPressure.MODERATE)
        assertEquals(0, runtime.unloads)
    }

    @Test
    fun memoryPressureBeforeAnyRuntimeExistsCreatesNothing() = runTest {
        val c = controller()
        c.onMemoryPressure(MemoryPressure.CRITICAL)
        assertEquals(0, runtimeCreated)
        assertEquals(0, runtime.unloads)
    }

    // --- Power / thermal profile (RED 33–40) ---

    @Test
    fun powerSaverMakesTheProfileEcoAndTheLeaseCarriesTheReducedBudget() = runTest {
        var saver = false
        val c = controller(powerSaver = { saver })
        assertEquals(PerformanceProfile.NORMAL, c.currentProfile())
        saver = true
        assertEquals(PerformanceProfile.ECO, c.currentProfile())
        val lease = (c.acquireForGeneration(model) as AcquireOutcome.Acquired).lease
        assertEquals(PerformanceProfile.ECO, lease.profile)
        assertEquals(GenerationBudget.REDUCED, lease.budget)
        assertEquals("ECO switches no model", model.id, runtime.lastLoaded?.id)
        lease.release()
        saver = false
        assertEquals(PerformanceProfile.NORMAL, c.currentProfile())
    }

    @Test
    fun thermalThrottleOutranksEco() = runTest {
        var status = 0
        val c = controller(powerSaver = { true }, thermalStatus = { status })
        assertEquals(PerformanceProfile.ECO, c.currentProfile())
        status = 2
        assertEquals(PerformanceProfile.THERMAL_LIMITED, c.currentProfile())
    }

    @Test
    fun aDeferredMemoryUnloadOutranksThermalAndEco() = runTest {
        val c = controller(powerSaver = { true }, thermalStatus = { 2 })
        val lease = (c.acquireForGeneration(model) as AcquireOutcome.Acquired).lease
        c.onMemoryPressure(MemoryPressure.LOW)
        assertEquals(PerformanceProfile.LOW_MEMORY, c.currentProfile())
        lease.release()
    }

    @Test
    fun theReducedIdleTimeoutAppliesUnderEco() = runTest {
        var saver = true
        val c = controller(powerSaver = { saver })
        (c.acquireForGeneration(model) as AcquireOutcome.Acquired).lease.release()
        advanceTimeBy(31_000)   // past the 30 s reduced window, far from the 120 s normal one
        assertEquals(1, runtime.unloads)
        assertEquals(UnloadReason.IDLE, c.metrics().lastUnloadReason)
    }

    // --- Failure (RED: load failure → Failed state, no lease) ---

    @Test
    fun aLoadFailureIsRefusedAndTheStateDoesNotLieReady() = runTest {
        runtime.failLoad = RuntimeFailure.ENGINE_ERROR
        val c = controller()
        val outcome = c.acquireForGeneration(model)
        assertTrue(outcome is AcquireOutcome.Refused)
        assertEquals(RuntimeFailure.ENGINE_ERROR, (outcome as AcquireOutcome.Refused).reason)
        assertFalse(c.state() is AiResourceState.Ready)
        runtime.failLoad = null
        val lease = (c.acquireForGeneration(model) as AcquireOutcome.Acquired).lease
        assertEquals("a later acquire retries the load", 2, runtime.loads)
        lease.release()
    }

    // --- Metrics / observability ---

    @Test
    fun metricsCountLoadsUnloadsAndGenerations() = runTest {
        val c = controller()
        assertNull(c.metrics().lastUnloadReason)
        (c.acquireForGeneration(model) as AcquireOutcome.Acquired).lease.release()
        (c.acquireForGeneration(model) as AcquireOutcome.Acquired).lease.release()
        advanceTimeBy(121_000)
        val m = c.metrics()
        assertEquals(1, m.loads)
        assertEquals(1, m.unloads)
        assertEquals(2, m.generations)
        assertEquals(7L, m.lastLoadMillis)
        assertEquals(UnloadReason.IDLE, m.lastUnloadReason)
    }
}

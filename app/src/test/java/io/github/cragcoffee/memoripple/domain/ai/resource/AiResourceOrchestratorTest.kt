package io.github.cragcoffee.memoripple.domain.ai.resource

import io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult
import io.github.cragcoffee.memoripple.domain.ai.AiResultContext
import io.github.cragcoffee.memoripple.domain.ai.CommandExecutor
import io.github.cragcoffee.memoripple.domain.ai.FakeAiRuntime
import io.github.cragcoffee.memoripple.domain.ai.FakeDocumentAccess
import io.github.cragcoffee.memoripple.domain.ai.FakeTemplates
import io.github.cragcoffee.memoripple.domain.ai.FixedTime
import io.github.cragcoffee.memoripple.domain.ai.LocalAiOrchestrator
import io.github.cragcoffee.memoripple.domain.ai.ModelSelection
import io.github.cragcoffee.memoripple.domain.ai.OrchestratorPromptAssets
import io.github.cragcoffee.memoripple.domain.ai.Resolver
import io.github.cragcoffee.memoripple.domain.ai.TEST_MODEL
import io.github.cragcoffee.memoripple.domain.ai.proposalJson
import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationRequest
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
import io.github.cragcoffee.memoripple.domain.ai.runtime.StatusThermalGate
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.TemplateAction
import io.github.cragcoffee.memoripple.domain.memos.TemplateSearchSpec
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The orchestrator over the resource controller (Phase 2, docs/AI_RESOURCE_CONTROLLER.md): the
 * model loads only for a genuine generation, stays warm between asks, and is idle-unloaded on
 * the controller's clock; the Fast Path and the template runner never touch the controller and
 * never extend the model's life. All timing is the test scheduler's virtual clock.
 */
class AiResourceOrchestratorTest {
    private val time = FixedTime()
    private val docs = FakeDocumentAccess(time)
    private val runtime = FakeAiRuntime()
    private val templates = FakeTemplates(emptyList())

    private fun TestScope.build(
        powerSaver: () -> Boolean = { false },
        thermalStatus: () -> Int = { 0 },
        idle: IdleUnloadPolicy = IdleUnloadPolicy(timeoutMillis = 120_000, reducedTimeoutMillis = 30_000),
    ): Pair<LocalAiOrchestrator, DefaultAiResourceController> {
        val controller = DefaultAiResourceController(
            runtime = { runtime }, scope = backgroundScope, idle = idle,
            thermal = StatusThermalGate(thermalStatus), powerSaver = powerSaver,
        )
        val orchestrator = LocalAiOrchestrator(
            runtime = { runtime },
            selection = object : ModelSelection { override suspend fun selected() = TEST_MODEL },
            thermal = StatusThermalGate(thermalStatus),
            assets = OrchestratorPromptAssets,
            resolver = Resolver(docs, templates, time),
            executor = CommandExecutor(docs),
            resources = controller,
            documents = docs,
        )
        return orchestrator to controller
    }

    @Test
    fun theFirstAskLoadsOnceAndTheWarmSecondAskReloadsNothing() = runTest {
        val (o, _) = build()
        docs.memo(1, "散歩のメモ")
        runtime.answers += proposalJson("SEARCH", query = "散歩")
        assertTrue(o.interact("散歩について何かあったっけ", AiResultContext.EMPTY) is AiInteractionResult.SearchResults)
        assertEquals(1, runtime.loads)
        advanceTimeBy(60_000)
        runtime.answers += proposalJson("SEARCH", query = "散歩")
        assertTrue(o.interact("他に散歩の話はあったかな", AiResultContext.EMPTY) is AiInteractionResult.SearchResults)
        assertEquals("warm reuse", 1, runtime.loads)
        assertEquals(0, runtime.unloads)
    }

    @Test
    fun theIdleTimeoutUnloadsAfterTheLastGeneration() = runTest {
        val (o, c) = build()
        runtime.answers += proposalJson("SEARCH", query = "散歩")
        o.interact("散歩について何かあったっけ", AiResultContext.EMPTY)
        advanceTimeBy(121_000)
        assertEquals(1, runtime.unloads)
        assertEquals(UnloadReason.IDLE, c.metrics().lastUnloadReason)
        assertEquals(RuntimeState.UNLOADED, o.runtimeState())
    }

    @Test
    fun fastPathAsksNeitherLoadNorExtendTheIdleWindow() = runTest {
        val (o, _) = build()
        docs.memo(1, "散歩のメモ")
        // a fast ask with nothing loaded: no load, no runtime construction
        assertTrue(o.interactFast("散歩を探して", AiResultContext.EMPTY) is AiInteractionResult.SearchResults)
        assertEquals(0, runtime.loads)
        // load through a real generation, then keep asking fast — the idle deadline must not move
        runtime.answers += proposalJson("SEARCH", query = "散歩")
        o.interact("散歩について何かあったっけ", AiResultContext.EMPTY)
        advanceTimeBy(100_000)
        repeat(3) { assertTrue(o.interactFast("散歩を探して", AiResultContext.EMPTY) is AiInteractionResult.SearchResults) }
        advanceTimeBy(21_000)   // 121 s after the generation, fast traffic notwithstanding
        assertEquals("fast asks kept no model alive", 1, runtime.unloads)
    }

    @Test
    fun aTemplateRunExtendsNothingEither() = runTest {
        val (o, _) = build()
        runtime.answers += proposalJson("SEARCH", query = "散歩")
        o.interact("散歩について何かあったっけ", AiResultContext.EMPTY)
        advanceTimeBy(100_000)
        val t = MemoTemplate(id = "t", name = "検索", body = "", action = TemplateAction.SEARCH, searchSpec = TemplateSearchSpec(query = "散歩"))
        o.runTemplate(t, emptyMap())
        advanceTimeBy(21_000)
        assertEquals(1, runtime.unloads)
        assertEquals("the template loaded nothing", 1, runtime.loads)
    }

    @Test
    fun ecoShrinksTheOutputBudgetAndNormalKeepsTodays() = runTest {
        var saver = false
        val (o, _) = build(powerSaver = { saver })
        runtime.answers += proposalJson("SEARCH", query = "散歩")
        o.interact("散歩について何かあったっけ", AiResultContext.EMPTY)
        assertEquals(GenerationRequest.DEFAULT_MAX_TOKENS, runtime.requests.last().maxTokens)
        saver = true
        runtime.answers += proposalJson("SEARCH", query = "散歩")
        o.interact("散歩について何かあったっけ", AiResultContext.EMPTY)
        assertEquals(GenerationBudget.REDUCED.maxOutputTokens, runtime.requests.last().maxTokens)
    }

    @Test
    fun severeThermalStillBlocksBeforeAnythingLoads() = runTest {
        val (o, c) = build(thermalStatus = { 3 })
        assertEquals(AiInteractionResult.ThermalBlocked, o.interact("散歩について何かあったっけ", AiResultContext.EMPTY))
        assertEquals(0, runtime.loads)
        assertEquals(0, c.metrics().loads)
    }

    @Test
    fun releaseUnloadsThroughTheControllerAndCountsOnce() = runTest {
        val (o, c) = build()
        runtime.answers += proposalJson("SEARCH", query = "散歩")
        o.interact("散歩について何かあったっけ", AiResultContext.EMPTY)
        o.release()
        o.release()
        assertEquals(1, runtime.unloads)
        assertEquals(UnloadReason.EXPLICIT, c.metrics().lastUnloadReason)
    }
}

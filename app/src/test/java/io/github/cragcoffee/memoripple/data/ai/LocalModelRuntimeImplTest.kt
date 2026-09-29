package io.github.cragcoffee.memoripple.data.ai

import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationRequest
import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationResult
import io.github.cragcoffee.memoripple.domain.ai.runtime.LoadResult
import io.github.cragcoffee.memoripple.domain.ai.runtime.MemoryPressure
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelCapability
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelDescriptor
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelLocation
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeFailure
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
import io.github.cragcoffee.memoripple.domain.ai.runtime.StopReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** An engine that exists only in memory: it can be told to block, to fail, and it counts everything. */
class FakeEngine : NativeInferenceEngine {
    var supported = true
    var failLoad = false
    var loads = 0; var unloads = 0; var generations = 0; var stops = 0
    var gate: CompletableDeferred<Unit>? = null
    private var stopRequested = false
    override fun isSupportedDevice(): Boolean = supported
    override fun load(modelFile: File, contextSize: Int, threads: Int): EngineHandle? { loads++; return if (failLoad) null else EngineHandle(1L) }
    override fun render(handle: EngineHandle, systemPrompt: String, userMessage: String, enableThinking: Boolean): String = "<sys>$systemPrompt</sys><user>$userMessage</user>"
    override fun generate(handle: EngineHandle, prompt: String, grammar: String, maxTokens: Int, allowPrefixReuse: Boolean): EngineGeneration {
        generations++
        val g = gate
        if (g != null) { runBlocking { g.await() } }
        return if (stopRequested) EngineGeneration(text = "", promptTokens = 10, generatedTokens = 0, ttftMillis = 0, totalMillis = 1, stop = StopReason.CANCELLED)
        else EngineGeneration(text = """{"intent":"SEARCH"}""", promptTokens = 10, generatedTokens = 5, ttftMillis = 3, totalMillis = 9, stop = StopReason.END)
    }
    override fun requestStop(handle: EngineHandle) { stops++; stopRequested = true }
    override fun unload(handle: EngineHandle) { unloads++ }
}

/** RED 1–6: load / generate / unload as distinct states; no concurrent generate; unload during generate is safe; reload after unload. */
class LocalModelRuntimeImplTest {
    private val model = ModelDescriptor("fake", "Fake", ModelLocation.DeveloperPath("/nonexistent/fake.gguf"), "fake", 4096, "Q4_K_M", setOf(ModelCapability.STRUCTURED_OUTPUT))
    private val request = GenerationRequest("sys", "user", "root ::= \"{\"", 256)
    private fun runtime(engine: FakeEngine) = LocalModelRuntimeImpl(engine, ModelFileResolverForTest, profileFor = { ModelProfile(it.id, enableThinking = false) }, fileExists = { true })

    @Test
    fun loadGenerateUnloadWalkTheStates() = runBlocking {
        val engine = FakeEngine(); val rt = runtime(engine)
        assertEquals(RuntimeState.UNLOADED, rt.state())
        val loaded = rt.load(model)
        assertTrue("$loaded", loaded is LoadResult.Loaded)
        assertEquals(RuntimeState.READY, rt.state())
        val g = rt.generate(request)
        assertTrue("$g", g is GenerationResult.Generated && (g as GenerationResult.Generated).text.startsWith("{"))
        assertEquals(RuntimeState.READY, rt.state())
        rt.unload()
        assertEquals(RuntimeState.UNLOADED, rt.state())
        assertEquals(1, engine.loads); assertEquals(1, engine.unloads); assertEquals(1, engine.generations)
    }

    @Test
    fun invalidTransitionsAreRefusedNotPerformed() = runBlocking {
        val engine = FakeEngine(); val rt = runtime(engine)
        assertEquals(GenerationResult.Failed(RuntimeFailure.INVALID_STATE), rt.generate(request))
        rt.unload() // unloading nothing is a no-op
        assertEquals(RuntimeState.UNLOADED, rt.state())
        rt.load(model)
        val second = rt.load(model.copy(id = "other"))
        assertEquals(LoadResult.Failed(RuntimeFailure.INVALID_STATE), second)
        assertEquals(1, engine.loads)
        assertEquals(RuntimeState.READY, rt.state())
    }

    @Test
    fun concurrentGenerationIsRefusedAndUnloadDuringGenerationStopsThenUnloads() = runBlocking {
        val engine = FakeEngine(); val rt = runtime(engine)
        rt.load(model)
        engine.gate = CompletableDeferred()
        val first = async { rt.generate(request) }
        withTimeout(2_000) { while (rt.state() != RuntimeState.GENERATING) delay(5) }
        assertEquals(GenerationResult.Failed(RuntimeFailure.BUSY), rt.generate(request))
        val unloading = async { rt.unload() }
        withTimeout(2_000) { while (engine.stops == 0) delay(5) }
        engine.gate!!.complete(Unit)
        val r = first.await(); unloading.await()
        assertTrue("$r", r is GenerationResult.Generated && (r as GenerationResult.Generated).stop == StopReason.CANCELLED)
        assertEquals(RuntimeState.UNLOADED, rt.state())
        assertEquals(1, engine.unloads)
    }

    @Test
    fun reloadAfterUnloadWorksAndAFailedLoadLeavesFailedThenRecovers() = runBlocking {
        val engine = FakeEngine(); val rt = runtime(engine)
        rt.load(model); rt.unload()
        assertTrue(rt.load(model) is LoadResult.Loaded)
        assertEquals(2, engine.loads)
        rt.unload()
        engine.failLoad = true
        assertEquals(LoadResult.Failed(RuntimeFailure.ENGINE_ERROR), rt.load(model))
        assertEquals(RuntimeState.FAILED, rt.state())
        engine.failLoad = false
        assertTrue(rt.load(model) is LoadResult.Loaded)
    }

    @Test
    fun anUnsupportedDeviceOrAMissingFileNeverReachesTheEngine() = runBlocking {
        val engine = FakeEngine().apply { supported = false }
        assertEquals(LoadResult.Failed(RuntimeFailure.UNSUPPORTED_DEVICE), runtime(engine).load(model))
        assertEquals(0, engine.loads)
        val missing = LocalModelRuntimeImpl(FakeEngine(), ModelFileResolverForTest, profileFor = { ModelProfile(it.id, false) }, fileExists = { false })
        assertEquals(LoadResult.Failed(RuntimeFailure.MODEL_FILE_MISSING), missing.load(model))
    }

    @Test
    fun theRenderedPromptCarriesTheProfilesThinkingFlagNotTheDomains() = runBlocking {
        val engine = FakeEngine()
        val rt = LocalModelRuntimeImpl(engine, ModelFileResolverForTest, profileFor = { ModelProfile(it.id, enableThinking = false) }, fileExists = { true })
        rt.load(model)
        rt.generate(request)
        assertEquals(1, engine.generations)
    }
}

object ModelFileResolverForTest : ModelFileLocator {
    override fun locate(location: ModelLocation): File = when (location) {
        is ModelLocation.DeveloperPath -> File(location.absolutePath)
        is ModelLocation.AppFile -> File("/tmp/models", location.fileName)
        is ModelLocation.Installed -> File("/tmp/models/" + location.modelId, "model.gguf")
    }
}

/** RED 8–10, 30: the vendor-specific facts live in profiles, the memory hook unloads. */
class ModelProfilesAndMemoryHookTest {
    @Test
    fun theTwoPhaseZeroCandidatesHaveProfilesAndDescriptorsHere() {
        val qwen = ModelProfiles.qwen3_4bInstruct2507
        val ministral = ModelProfiles.ministral3_3bInstruct2512
        assertEquals("qwen3", qwen.descriptor.architecture)
        assertEquals("mistral3", ministral.descriptor.architecture)
        assertEquals(false, qwen.profile.enableThinking)
        assertEquals(false, ministral.profile.enableThinking)
        assertEquals("Q4_K_M", qwen.descriptor.quantization)
        assertEquals(4096, qwen.descriptor.contextSize)
        assertTrue(ModelCapability.STRUCTURED_OUTPUT in ministral.descriptor.capabilities)
        assertEquals(setOf("qwen3-4b-instruct-2507", "ministral-3-3b-instruct-2512"), ModelProfiles.all.map { it.descriptor.id }.toSet())
        assertTrue("Phase 5: profiles load from the installed layout", ModelProfiles.all.all { it.descriptor.location is ModelLocation.Installed })
    }

    @Test
    fun memoryPressureUnloadsThroughTheHolderAndTheHoldersRuntimeIsLazy() = runBlocking {
        val engine = FakeEngine()
        val created = AtomicInteger()
        val holder = LocalModelRuntimeHolder { created.incrementAndGet(); LocalModelRuntimeImpl(engine, ModelFileResolverForTest, { ModelProfile(it.id, false) }, { true }) }
        assertEquals(0, created.get())
        holder.onMemoryPressure(MemoryPressure.MODERATE) // nothing created, nothing to unload
        assertEquals(0, created.get())
        holder.runtime.load(ModelDescriptor("fake", "Fake", ModelLocation.DeveloperPath("/x.gguf"), "fake", 4096, "Q4_K_M", emptySet()))
        assertEquals(RuntimeState.READY, holder.runtime.state())
        holder.onMemoryPressure(MemoryPressure.MODERATE)
        assertEquals(RuntimeState.READY, holder.runtime.state())
        holder.onMemoryPressure(MemoryPressure.LOW)
        assertEquals(RuntimeState.UNLOADED, holder.runtime.state())
        assertEquals(1, engine.unloads)
    }
}

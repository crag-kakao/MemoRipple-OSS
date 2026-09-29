package io.github.cragcoffee.memoripple.data.ai

import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationCacheKey
import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationRequest
import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationResult
import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationRoute
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelCapability
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelDescriptor
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelLocation
import io.github.cragcoffee.memoripple.domain.ai.runtime.StopReason
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4B — cache identity and invalidation (docs/GENERATION_EFFICIENCY.md). A prefix may be
 * reused only inside one [GenerationCacheKey] — model, prompt version, grammar, conversation and
 * route all equal. The runtime tells the engine to reset before any generation whose key differs
 * from the last one (or that has none), and an unload forgets the key, so the next load starts
 * with a full evaluation. The token-level prefix match itself lives in the native bridge.
 */
class GenerationCacheTest {

    private open class RecordingEngine : NativeInferenceEngine {
        val calls = ArrayList<String>()
        override fun isSupportedDevice() = true
        override fun load(modelFile: File, contextSize: Int, threads: Int) = EngineHandle(1L)
        override fun render(handle: EngineHandle, systemPrompt: String, userMessage: String, enableThinking: Boolean) = "$systemPrompt\n$userMessage"
        override fun generate(handle: EngineHandle, prompt: String, grammar: String, maxTokens: Int, allowPrefixReuse: Boolean): EngineGeneration {
            calls += "generate(reuse=$allowPrefixReuse)"
            return EngineGeneration("{}", 10, 1, 5, 10, StopReason.END)
        }
        override fun resetCache(handle: EngineHandle) { calls += "reset" }
        override fun requestStop(handle: EngineHandle) = Unit
        override fun unload(handle: EngineHandle) { calls += "unload" }
    }

    private val model = ModelDescriptor("m", "M", ModelLocation.AppFile("m.gguf"), "test", 4096, "Q4", setOf(ModelCapability.STRUCTURED_OUTPUT))

    private fun runtime(engine: RecordingEngine) = LocalModelRuntimeImpl(
        engine = engine,
        files = object : ModelFileLocator { override fun locate(location: ModelLocation) = File("m.gguf") },
        profileFor = { ModelProfile(it.id, enableThinking = false) },
        fileExists = { true },
    )

    private fun request(key: String?) = GenerationRequest("s", "u", "root ::= \"x\"", cacheKey = key)

    @Test
    fun theKeyChangesWithEveryIdentityPart() {
        val base = GenerationCacheKey(modelId = "m", promptVersion = "v1", grammarHash = 7, conversationId = 1L, route = GenerationRoute.STRUCTURED_INTENT)
        assertEquals(base.value, base.copy().value)
        assertNotEquals(base.value, base.copy(modelId = "n").value)
        assertNotEquals(base.value, base.copy(promptVersion = "v2").value)
        assertNotEquals(base.value, base.copy(grammarHash = 8).value)
        assertNotEquals(base.value, base.copy(conversationId = 2L).value)
        assertTrue("the route is part of the identity", base.value.contains(GenerationRoute.STRUCTURED_INTENT.name))
    }

    @Test
    fun theSameKeyReusesAndADifferentKeyResetsFirst() = runBlocking {
        val engine = RecordingEngine()
        val rt = runtime(engine)
        rt.load(model)
        rt.generate(request("A"))
        rt.generate(request("A"))
        rt.generate(request("B"))
        rt.generate(request("B"))
        assertEquals(
            listOf("reset", "generate(reuse=true)", "generate(reuse=true)", "reset", "generate(reuse=true)", "generate(reuse=true)"),
            engine.calls,
        )
    }

    @Test
    fun noKeyMeansNoReuseAndAResetEveryTime() = runBlocking {
        val engine = RecordingEngine()
        val rt = runtime(engine)
        rt.load(model)
        rt.generate(request(null))
        rt.generate(request(null))
        assertEquals(listOf("reset", "generate(reuse=false)", "reset", "generate(reuse=false)"), engine.calls)
        assertNull(rt.cacheKey())
    }

    @Test
    fun anUnloadForgetsTheKeySoTheNextLoadStartsCold() = runBlocking {
        val engine = RecordingEngine()
        val rt = runtime(engine)
        rt.load(model)
        rt.generate(request("A"))
        assertEquals("A", rt.cacheKey())
        rt.unload()
        assertNull(rt.cacheKey())
        rt.load(model)
        rt.generate(request("A"))
        assertEquals("the same key after a reload still resets: the native context is new", listOf("reset", "generate(reuse=true)", "unload", "reset", "generate(reuse=true)"), engine.calls)
    }

    @Test
    fun aFailedGenerationLeavesNoKeyBehind() = runBlocking {
        val engine = object : RecordingEngine() {
            override fun generate(handle: EngineHandle, prompt: String, grammar: String, maxTokens: Int, allowPrefixReuse: Boolean): EngineGeneration {
                calls += "generate(reuse=$allowPrefixReuse)"
                return EngineGeneration("", 0, 0, 0, 0, StopReason.ERROR)
            }
        }
        val rt = runtime(engine)
        rt.load(model)
        val r = rt.generate(request("A"))
        assertTrue(r is GenerationResult.Generated && r.stop == StopReason.ERROR)
        assertNull("nothing is trusted after an error: the next request resets", rt.cacheKey())
    }
}

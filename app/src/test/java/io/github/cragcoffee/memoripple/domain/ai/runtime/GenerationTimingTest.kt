package io.github.cragcoffee.memoripple.domain.ai.runtime

import io.github.cragcoffee.memoripple.domain.ai.AiResultContext
import io.github.cragcoffee.memoripple.domain.ai.AiTiming
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationLine
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationWindow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4B — timing decomposition (docs/GENERATION_EFFICIENCY.md, human brief 2026-09-23). A
 * generation's time is no longer one TTFT number: the engine reports tokenization, prompt
 * evaluation, the reused prefix and the newly evaluated tokens; the generator reports the prompt
 * build and the prompt's size; the timing type carries all of it as numbers — never text.
 */
class GenerationTimingTest {

    private class TimedRuntime : LocalModelRuntime {
        var state = RuntimeState.READY
        val requests = ArrayList<GenerationRequest>()
        override fun state() = state
        override suspend fun load(model: ModelDescriptor) = LoadResult.Loaded(model, 1)
        override suspend fun generate(request: GenerationRequest): GenerationResult {
            requests += request
            return GenerationResult.Generated(
                text = "{\"intent\":\"SEARCH\",\"query\":\"散歩\",\"targetRef\":null,\"targetName\":null,\"documentKind\":null,\"text\":null,\"templateId\":null,\"dateToken\":null,\"missingFields\":[]}",
                promptTokens = 600, generatedTokens = 40, ttftMillis = 900, totalMillis = 1500, stop = StopReason.END,
                tokenizeMillis = 12, promptEvalMillis = 850, reusedPrefixTokens = 540, evaluatedPromptTokens = 60,
            )
        }
        override suspend fun unload() { state = RuntimeState.UNLOADED }
    }

    private object Assets : PromptAssets {
        override val promptVersion = "v1"
        override val intentSystemPrompt = "system"
        override val intentGrammar = "root ::= \"{\" \"}\""
    }

    @Test
    fun theEngineResultCarriesTheDecomposition() {
        val g = GenerationResult.Generated("{}", 600, 40, 900, 1500, StopReason.END, tokenizeMillis = 12, promptEvalMillis = 850, reusedPrefixTokens = 540, evaluatedPromptTokens = 60)
        assertEquals(12L, g.tokenizeMillis)
        assertEquals(850L, g.promptEvalMillis)
        assertEquals(540, g.reusedPrefixTokens)
        assertEquals(60, g.evaluatedPromptTokens)
        // the defaults describe a full evaluation: nothing reused, everything evaluated
        val full = GenerationResult.Generated("{}", 600, 40, 900, 1500, StopReason.END)
        assertEquals(0, full.reusedPrefixTokens)
        assertEquals(600, full.evaluatedPromptTokens)
    }

    @Test
    fun theGeneratorMeasuresThePromptBuildAndPassesTheDecompositionAndTheCacheKeyThrough() = runBlocking {
        val runtime = TimedRuntime()
        val generator = StructuredIntentGenerator(runtime, Assets, StatusThermalGate { 0 })
        val window = ConversationWindow(listOf(ConversationLine(ChatRole.USER, "こんにちは"), ConversationLine(ChatRole.ASSISTANT, "何を頼みますか")), truncated = false)
        val proposed = generator.generate("散歩について何かあったっけ", AiResultContext.EMPTY, window, cacheKey = "k1") as IntentGeneration.Proposed
        assertEquals("k1", runtime.requests.single().cacheKey)
        assertTrue("the prompt build is measured (≥ 0 ms)", proposed.promptBuildMillis >= 0)
        assertEquals(runtime.requests.single().userMessage.length, proposed.promptChars)
        assertEquals(12L, proposed.tokenizeMillis)
        assertEquals(850L, proposed.promptEvalMillis)
        assertEquals(540, proposed.reusedPrefixTokens)
        assertEquals(60, proposed.evaluatedPromptTokens)
        assertEquals(600, proposed.promptTokens)
    }

    @Test
    fun withoutACacheKeyTheRequestCarriesNone() = runBlocking {
        val runtime = TimedRuntime()
        val generator = StructuredIntentGenerator(runtime, Assets, StatusThermalGate { 0 })
        generator.generate("散歩について何かあったっけ", AiResultContext.EMPTY)
        assertNull(runtime.requests.single().cacheKey)
    }

    @Test
    fun theTimingTypeCarriesOnlyNumbers() {
        val t = AiTiming(promptTokens = 600, generatedTokens = 40, ttftMillis = 900, totalMillis = 1500, loadMillis = 4000, promptBuildMillis = 1, tokenizeMillis = 12, promptEvalMillis = 850, reusedPrefixTokens = 540, evaluatedPromptTokens = 60, promptChars = 2400)
        assertEquals(4000L, t.loadMillis)
        assertEquals(850L, t.promptEvalMillis)
        assertTrue("prompt tokens per second is derived from the evaluated tokens and the eval time", t.promptTokensPerSecond > 60.0)
        AiTiming::class.java.declaredFields.forEach { f ->
            assertTrue("${f.name} is a number, never text", f.type == Int::class.javaPrimitiveType || f.type == Long::class.javaPrimitiveType || f.type == Double::class.javaPrimitiveType)
        }
    }
}

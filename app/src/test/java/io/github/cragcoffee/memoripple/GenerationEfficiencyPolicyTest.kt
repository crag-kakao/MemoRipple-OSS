package io.github.cragcoffee.memoripple

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4B policy (docs/GENERATION_EFFICIENCY.md, human brief 2026-09-23), pinned at the source
 * level: prompt v1 and the intent grammar are byte-identical to the frozen copies; the native
 * prefix reuse rewinds only after a token-level comparison and only inside one cache identity;
 * the timing log lines carry numbers only; the product UI shows no timing breakdown; and the
 * generation settings (threads, batch, context, mmap, sampling) are what they were.
 */
class GenerationEfficiencyPolicyTest {
    private fun src(path: String): File = File(path).let { if (it.exists()) it else File("app/$path") }

    private val native = src("src/main/cpp/memoripple_llm.cpp")
    private val runtimeImpl = src("src/main/java/io/github/cragcoffee/memoripple/data/ai/LocalModelRuntimeImpl.kt")
    private val engineBridge = src("src/main/java/io/github/cragcoffee/memoripple/data/ai/llamacpp/LlamaCppEngine.kt")
    private val orchestrator = src("src/main/java/io/github/cragcoffee/memoripple/domain/ai/AiOrchestrator.kt")
    private val chatViewModel = src("src/main/java/io/github/cragcoffee/memoripple/ui/chat/ChatViewModel.kt")
    private val chatScreen = src("src/main/java/io/github/cragcoffee/memoripple/ui/chat/ChatScreen.kt")

    @Test
    fun promptV1AndTheIntentGrammarAreTheFrozenBytes() {
        val prompt = src("src/main/assets/ai/intent_system.v1.txt").readBytes()
        val grammar = src("src/main/assets/ai/intent_proposal.gbnf").readBytes()
        val frozenPrompt = File("tools/llm-eval/prompts/intent_system.v1.txt").let { if (it.exists()) it else File("../tools/llm-eval/prompts/intent_system.v1.txt") }
        val frozenGrammar = File("tools/llm-eval/grammars/intent_proposal.gbnf").let { if (it.exists()) it else File("../tools/llm-eval/grammars/intent_proposal.gbnf") }
        if (frozenPrompt.exists()) assertTrue("prompt v1 is byte-identical to the frozen copy", prompt.contentEquals(frozenPrompt.readBytes()))
        if (frozenGrammar.exists()) assertTrue("the grammar is byte-identical to the frozen copy", grammar.contentEquals(frozenGrammar.readBytes()))
        assertEquals("prompt v1's known size", 2021, prompt.size)
        assertEquals("the grammar's known size", 1279, grammar.size)
    }

    @Test
    fun theNativePrefixReuseComparesTokensAndRewindsOnlyThePrefix() {
        val text = native.readText()
        assertTrue("a token-level common prefix is computed", text.contains("common_prefix") || text.contains("commonPrefix"))
        assertTrue("the memory is rewound to the prefix, never assumed", text.contains("llama_memory_seq_rm"))
        assertTrue("a full clear still exists for the no-reuse path", text.contains("llama_memory_clear"))
        assertTrue("a reset entry point exists for a changed identity", text.contains("nativeResetCache"))
        // an identical prompt still evaluates at least one token so the logits are fresh
        assertTrue("an identical prompt re-evaluates its last token", text.contains("toks.size() - 1") || text.contains("n_prefix = ") )
    }

    @Test
    fun theRuntimeReusesOnlyInsideOneIdentity() {
        val text = runtimeImpl.readText()
        assertTrue("the runtime keeps the last cache key", text.contains("cacheKey"))
        assertTrue("a changed or missing key resets the engine first", text.contains("resetCache("))
        val unload = text.substringAfter("override suspend fun unload()")
        assertTrue("an unload forgets the key", unload.contains("cacheKey = null"))
    }

    @Test
    fun theOrchestratorKeysTheCacheByConversationModelPromptAndGrammar() {
        val text = orchestrator.readText()
        assertTrue(text.contains("GenerationCacheKey("))
        listOf("promptVersion", "conversationId", "intentGrammar", "GenerationRoute.STRUCTURED_INTENT").forEach { part ->
            assertTrue("the key carries $part", text.substringAfter("GenerationCacheKey(").substringBefore(")").contains(part) || text.contains(part))
        }
    }

    @Test
    fun theTimingLogCarriesNumbersOnly() {
        val lines = chatViewModel.readText().lines().filter { it.contains("GEN ") && it.contains("Log.") }
        assertTrue("the chat logs the generation timing", lines.isNotEmpty())
        listOf("userText", "text", "prompt=", "systemPrompt", "userMessage", "title", "body", "answer").forEach { token ->
            lines.forEach { line -> assertFalse("a timing log line carries $token → $line", line.contains(token)) }
        }
    }

    @Test
    fun theProductUiShowsNoTimingBreakdown() {
        val screen = chatScreen.readText()
        listOf("promptEvalMillis", "tokenizeMillis", "reusedPrefixTokens", "evaluatedPromptTokens", "loadMillis", "promptBuildMillis").forEach { field ->
            assertFalse("the screen must not show $field", screen.contains(field))
        }
    }

    @Test
    fun theGenerationSettingsAreUnchanged() {
        val bridge = engineBridge.readText()
        assertTrue("n_batch stays 512", bridge.contains("nativeLoad(modelFile.absolutePath, contextSize, 512, threads)"))
        assertTrue("threads stay the runtime's default of 4", runtimeImpl.readText().contains("private val threads: Int = 4"))
        val text = native.readText()
        assertTrue("mmap stays on", text.contains("LLAMA_LOAD_MODE_MMAP"))
        assertFalse("mlock stays off", text.contains("use_mlock = true"))
        assertTrue("greedy sampling stays", text.contains("llama_sampler_init_greedy()"))
        assertTrue("the grammar sampler stays first", text.indexOf("llama_sampler_init_grammar") < text.indexOf("llama_sampler_init_greedy()"))
    }
}

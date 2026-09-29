package io.github.cragcoffee.memoripple

import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.ai.AssetPromptAssets
import io.github.cragcoffee.memoripple.data.ai.LocalModelRuntimeImpl
import io.github.cragcoffee.memoripple.data.ai.ModelProfiles
import io.github.cragcoffee.memoripple.data.ai.NoBackupModelFileLocator
import io.github.cragcoffee.memoripple.data.ai.androidThermalGate
import io.github.cragcoffee.memoripple.data.ai.llamacpp.LlamaCppEngine
import io.github.cragcoffee.memoripple.domain.ai.AiIntent
import io.github.cragcoffee.memoripple.domain.ai.AiResultContext
import io.github.cragcoffee.memoripple.domain.ai.DateToken
import io.github.cragcoffee.memoripple.domain.ai.runtime.IntentGeneration
import io.github.cragcoffee.memoripple.domain.ai.runtime.LoadResult
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelLocation
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
import io.github.cragcoffee.memoripple.domain.ai.runtime.StructuredIntentGenerator
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Phase 2 runtime smoke (docs/LOCAL_LLM_RUNTIME.md §smoke): one command through the product
 * runtime — load → 「昨日の日記を探して」 → raw JSON → IntentProposal(SEARCH, JOURNAL, YESTERDAY) → unload.
 * No DocumentAccess call. It runs only when the operator injects a model:
 *
 *   am instrument -w -r -e class io.github.cragcoffee.memoripple.LocalLlmRuntimeSmokeTest \
 *      -e llmModelPath /data/local/tmp/llmbench/models/<file>.gguf -e llmModelId qwen3-4b-instruct-2507 \
 *      io.github.cragcoffee.memoripple.test/io.github.cragcoffee.memoripple.MemoRippleTestRunner
 *
 * Without the argument it is skipped, so the normal suite never loads a model.
 */
class LocalLlmRuntimeSmokeTest {
    @Test
    fun oneCommandThroughTheProductRuntime() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("llmModelPath")
        assumeTrue("no llmModelPath injected: the runtime smoke is skipped", !path.isNullOrBlank())
        val modelId = args.getString("llmModelId") ?: ModelProfiles.qwen3_4bInstruct2507.descriptor.id
        val profiled = ModelProfiles.all.first { it.descriptor.id == modelId }
        val descriptor = profiled.descriptor.copy(location = ModelLocation.DeveloperPath(path!!))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = LocalModelRuntimeImpl(LlamaCppEngine(), NoBackupModelFileLocator(context.noBackupFilesDir), ModelProfiles::profileFor)
        val generator = StructuredIntentGenerator(runtime, AssetPromptAssets(context), androidThermalGate(context))
        runBlocking {
            val loaded = runtime.load(descriptor)
            assertTrue("$loaded", loaded is LoadResult.Loaded)
            android.util.Log.i("LlmSmoke", "loaded ${descriptor.displayName} in ${(loaded as LoadResult.Loaded).loadMillis} ms")
            val out = generator.generate("昨日の日記を探して", AiResultContext.EMPTY)
            android.util.Log.i("LlmSmoke", "generation: $out")
            val proposed = out as IntentGeneration.Proposed
            assertEquals(AiIntent.SEARCH, proposed.proposal.intent)
            assertEquals(DocumentKind.JOURNAL, proposed.proposal.documentKind)
            assertEquals(DateToken.YESTERDAY, proposed.proposal.dateToken)
            assertEquals("v1", proposed.promptVersion)
            runtime.unload()
            assertEquals(RuntimeState.UNLOADED, runtime.state())
        }
    }
}

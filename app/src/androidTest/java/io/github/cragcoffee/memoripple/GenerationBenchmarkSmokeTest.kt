package io.github.cragcoffee.memoripple

import android.content.Context
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.ai.AssetPromptAssets
import io.github.cragcoffee.memoripple.data.ai.ModelProfiles
import io.github.cragcoffee.memoripple.data.ai.productLocalModelRuntime
import io.github.cragcoffee.memoripple.domain.ai.AiResultContext
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationLine
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationWindow
import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationCacheKey
import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationRoute
import io.github.cragcoffee.memoripple.domain.ai.runtime.IntentGeneration
import io.github.cragcoffee.memoripple.domain.ai.runtime.LoadResult
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelLocation
import io.github.cragcoffee.memoripple.domain.ai.runtime.StatusThermalGate
import io.github.cragcoffee.memoripple.domain.ai.runtime.StructuredIntentGenerator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Phase 4B S20 benchmark — the real model over the real runtime, skipped unless a model path is
 * injected (the standing developer path, no new GGUF):
 *
 *     -e llmModelPath /data/local/tmp/llmbench/models/<file>.gguf -e llmModelId ministral-3-3b-instruct-2512
 *
 * Three conditions × cold / warm × baseline (no cache key: a full evaluation every time) and
 * optimized (one conversation key: the common prefix reused), three repeats each. Logs (tag
 * `GenBench`) carry sizes and times only — never a prompt, a line or an answer; the intent JSON
 * of baseline and optimized is compared for equality (greedy sampling) and only the verdict is
 * logged. Touches no app data.
 */
class GenerationBenchmarkSmokeTest {
    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication

    private fun log(line: String) = Log.i("GenBench", line)
    private fun thermal(): Int = (application.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus

    private fun window(lines: Int, chars: Int): ConversationWindow = ConversationWindow(
        (1 until lines + 1).map { i -> ConversationLine(if (i % 2 == 1) ChatRole.USER else ChatRole.ASSISTANT, ("散歩や買い物の話を少しだけ。" + "今日の記録について" + "。").let { s -> s.repeat(1 + chars / lines / s.length).take(chars / lines) }) },
        truncated = false,
    )

    @Test
    fun coldAndWarmBaselineAgainstPrefixReuse() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("llmModelPath")
        assumeTrue("no llmModelPath injected: the generation benchmark is skipped", !path.isNullOrBlank())
        assumeTrue("the model file must exist: $path", File(path!!).isFile)
        val modelId = args.getString("llmModelId") ?: ModelProfiles.ministral3_3bInstruct2512.descriptor.id
        val profile = ModelProfiles.all.firstOrNull { it.descriptor.id == modelId } ?: ModelProfiles.ministral3_3bInstruct2512
        val descriptor = profile.descriptor.copy(location = ModelLocation.DeveloperPath(path))
        val runtime = productLocalModelRuntime(application.noBackupFilesDir)
        val assets = AssetPromptAssets(application)
        val generator = StructuredIntentGenerator(runtime, assets, StatusThermalGate { 0 })
        val key = GenerationCacheKey(modelId, assets.promptVersion, assets.intentGrammar.hashCode(), conversationId = 1L, route = GenerationRoute.STRUCTURED_INTENT).value

        data class Condition(val name: String, val text: String, val window: ConversationWindow)
        val conditions = listOf(
            Condition("short", "散歩について何かあったっけ", window(2, 60)),
            Condition("nearMax", "他に散歩の話はあったかな", window(6, 1_150)),
            Condition("intent", "MemoRipple開発に『Folder対応完了』を追記して", ConversationWindow.EMPTY),
        )

        log("model=$modelId bytes=${File(path).length()} pssKb=${Debug.getPss()} thermal=${thermal()}")
        val t0 = System.currentTimeMillis()
        val loaded = runBlocking { runtime.load(descriptor) }
        val loadMs = System.currentTimeMillis() - t0
        assumeTrue("the model loaded: $loaded", loaded is LoadResult.Loaded)
        log("load ms=$loadMs pssKb=${Debug.getPss()}")

        fun run(c: Condition, cacheKey: String?, tag: String) {
            val g = runBlocking { generator.generate(c.text, AiResultContext.EMPTY, c.window, cacheKey = cacheKey) }
            when (g) {
                is IntentGeneration.Proposed -> log("$tag cond=${c.name} promptChars=${g.promptChars} promptTokens=${g.promptTokens} reused=${g.reusedPrefixTokens} evaluated=${g.evaluatedPromptTokens} buildMs=${g.promptBuildMillis} tokenizeMs=${g.tokenizeMillis} promptEvalMs=${g.promptEvalMillis} ttftMs=${g.ttftMillis} totalMs=${g.totalMillis} genTokens=${g.generatedTokens} intentHash=${g.raw.hashCode()} thermal=${thermal()}")
                is IntentGeneration.Refused -> log("$tag cond=${c.name} refused=${g.reason}")
            }
        }

        // baseline: every request a full evaluation (no key)
        conditions.forEach { c -> repeat(3) { i -> run(c, null, "baseline#$i") } }
        // optimized: one conversation identity; the first request of a condition is its cold eval, the repeats reuse the prefix
        conditions.forEach { c -> repeat(3) { i -> run(c, key, "optimized#$i") } }
        // a consecutive turn inside the same conversation: the earlier turns become the reused prefix
        val grown = Condition("consecutive", "それに『完了』を追記して", window(4, 400))
        run(grown, key, "consecutive#0")
        run(Condition("consecutive", "他に散歩の話はあったかな", window(6, 700)), key, "consecutive#1")
        log("pssKb=${Debug.getPss()} thermal=${thermal()}")
        runBlocking { runtime.unload() }
        log("unloaded pssKb=${Debug.getPss()}")
    }
}

package io.github.cragcoffee.memoripple.data.ai.llamacpp

import io.github.cragcoffee.memoripple.data.ai.EngineGeneration
import io.github.cragcoffee.memoripple.data.ai.EngineHandle
import io.github.cragcoffee.memoripple.data.ai.NativeInferenceEngine
import io.github.cragcoffee.memoripple.domain.ai.runtime.StopReason
import java.io.File
import org.json.JSONObject

/**
 * llama.cpp (submodule pin b11039, CPU, arm64-v8a, armv8.2-a + dotprod + fp16) behind
 * [NativeInferenceEngine]. The native library is `libmemoripple_llm.so` (app/src/main/cpp); it is
 * the product's own thin bridge, not the Phase 0 bench. Because the build assumes the dotprod
 * instructions, [isSupportedDevice] reads the CPU features first and the engine is never called
 * on a CPU without them.
 */
class LlamaCppEngine : NativeInferenceEngine {
    init {
        System.loadLibrary("memoripple_llm")
        nativeBackendInit()
    }

    override fun isSupportedDevice(): Boolean = CpuFeatures.hasDotProduct()

    override fun load(modelFile: File, contextSize: Int, threads: Int): EngineHandle? {
        val raw = nativeLoad(modelFile.absolutePath, contextSize, 512, threads)
        return if (raw == 0L) null else EngineHandle(raw)
    }

    override fun render(handle: EngineHandle, systemPrompt: String, userMessage: String, enableThinking: Boolean): String =
        nativeRender(handle.raw, systemPrompt, userMessage, enableThinking)

    override fun generate(handle: EngineHandle, prompt: String, grammar: String, maxTokens: Int, allowPrefixReuse: Boolean): EngineGeneration {
        val j = JSONObject(nativeGenerate(handle.raw, prompt, grammar, maxTokens, allowPrefixReuse))
        val stop = when (j.optString("stop")) {
            "eog" -> StopReason.END
            "max", "ctx" -> StopReason.MAX_TOKENS
            "cancelled" -> StopReason.CANCELLED
            else -> StopReason.ERROR
        }
        val promptTokens = j.optInt("promptTokens")
        return EngineGeneration(
            text = j.optString("text"),
            promptTokens = promptTokens,
            generatedTokens = j.optInt("genTokens"),
            ttftMillis = j.optDouble("ttftMs", 0.0).toLong(),
            totalMillis = j.optDouble("totalMs", 0.0).toLong(),
            stop = if (j.optBoolean("ok")) stop else StopReason.ERROR,
            tokenizeMillis = j.optDouble("tokenizeMs", 0.0).toLong(),
            promptEvalMillis = j.optDouble("promptEvalMs", 0.0).toLong(),
            reusedPrefixTokens = j.optInt("reusedTokens", 0),
            evaluatedPromptTokens = j.optInt("evaluatedTokens", promptTokens),
        )
    }

    override fun resetCache(handle: EngineHandle) = nativeResetCache(handle.raw)

    override fun requestStop(handle: EngineHandle) = nativeRequestStop(handle.raw)

    override fun unload(handle: EngineHandle) = nativeUnload(handle.raw)

    private external fun nativeBackendInit()
    private external fun nativeLoad(path: String, nCtx: Int, nBatch: Int, nThreads: Int): Long
    private external fun nativeRender(handle: Long, system: String, user: String, enableThinking: Boolean): String
    private external fun nativeGenerate(handle: Long, prompt: String, grammar: String, maxTokens: Int, allowPrefixReuse: Boolean): String
    private external fun nativeResetCache(handle: Long)
    private external fun nativeRequestStop(handle: Long)
    private external fun nativeUnload(handle: Long)
}

/** `asimddp` in /proc/cpuinfo = the ARMv8.2 dot-product extension the native build is compiled for. */
object CpuFeatures {
    fun hasDotProduct(cpuinfo: String = readCpuInfo()): Boolean =
        cpuinfo.lineSequence().any { it.startsWith("Features") && it.split(Regex("\\s+")).contains("asimddp") }

    private fun readCpuInfo(): String = try { File("/proc/cpuinfo").readText() } catch (_: Throwable) { "" }
}

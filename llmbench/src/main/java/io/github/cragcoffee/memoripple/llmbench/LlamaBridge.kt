package io.github.cragcoffee.memoripple.llmbench

import org.json.JSONObject

/**
 * The JNI surface over llama.cpp (llmbench.cpp). One instance per process; a loaded model is
 * a [Handle]. All calls are blocking and must run off the main thread.
 */
class LlamaBridge {
    class Handle internal constructor(internal val raw: Long) {
        @Volatile internal var closed = false
    }

    data class ModelInfo(
        val loadMs: Long,
        val sizeBytes: Long,
        val nParams: Long,
        val hasTemplate: Boolean,
        val jinja: Boolean,
        val supportsThinking: Boolean,
        val bosText: String,
        val nCtxTrain: Int,
        val nCtx: Int,
        val desc: String,
    )

    data class Generation(
        val ok: Boolean,
        val text: String,
        val promptTokens: Int,
        val genTokens: Int,
        val promptMs: Double,
        val ttftMs: Double,
        val genMs: Double,
        val stop: String,
        val grammarUsed: Boolean,
        val error: String?,
    ) {
        val tokensPerSecond: Double get() = if (genMs > 0 && genTokens > 1) (genTokens - 1) * 1000.0 / genMs else 0.0
    }

    init {
        System.loadLibrary("llmbench")
        nativeBackendInit()
    }

    fun systemInfo(): String = nativeSystemInfo()

    fun load(path: String, nCtx: Int, nBatch: Int, nThreads: Int, useMmap: Boolean): Handle? {
        val raw = nativeLoad(path, nCtx, nBatch, nThreads, useMmap)
        return if (raw == 0L) null else Handle(raw)
    }

    fun unload(handle: Handle) {
        if (handle.closed) return
        handle.closed = true
        nativeUnload(handle.raw)
    }

    fun modelInfo(handle: Handle): ModelInfo {
        val j = JSONObject(nativeModelInfo(handle.raw))
        return ModelInfo(
            loadMs = j.optLong("loadMs"),
            sizeBytes = j.optLong("sizeBytes"),
            nParams = j.optLong("nParams"),
            hasTemplate = j.optBoolean("hasTemplate"),
            jinja = j.optBoolean("jinja"),
            supportsThinking = j.optBoolean("supportsThinking"),
            bosText = j.optString("bosText"),
            nCtxTrain = j.optInt("nCtxTrain"),
            nCtx = j.optInt("nCtx"),
            desc = j.optString("desc"),
        )
    }

    fun hasChatTemplate(handle: Handle): Boolean = nativeHasChatTemplate(handle.raw)

    /** The GGUF's own chat template text ("" when none) — recorded so thinking-mode defaults are visible. */
    fun chatTemplate(handle: Handle): String = nativeChatTemplate(handle.raw)

    /** The model's own chat template applied to (system, user) with the assistant turn opened. */
    fun applyChatTemplate(handle: Handle, system: String, user: String): String =
        nativeApplyChatTemplate(handle.raw, system, user)

    fun generate(handle: Handle, prompt: String, grammar: String?, maxTokens: Int): Generation {
        val j = JSONObject(nativeGenerate(handle.raw, prompt, grammar ?: "", maxTokens))
        return Generation(
            ok = j.optBoolean("ok"),
            text = j.optString("text"),
            promptTokens = j.optInt("promptTokens"),
            genTokens = j.optInt("genTokens"),
            promptMs = j.optDouble("promptMs", 0.0),
            ttftMs = j.optDouble("ttftMs", 0.0),
            genMs = j.optDouble("genMs", 0.0),
            stop = j.optString("stop"),
            grammarUsed = j.optBoolean("grammarUsed"),
            error = if (j.has("error")) j.optString("error") else null,
        )
    }

    /** Asks a running generation to stop at the next token (the thermal guard). */
    fun requestStop(handle: Handle) = nativeRequestStop(handle.raw)

    fun shutdown() = nativeBackendFree()

    private external fun nativeSystemInfo(): String
    private external fun nativeBackendInit()
    private external fun nativeBackendFree()
    private external fun nativeLoad(path: String, nCtx: Int, nBatch: Int, nThreads: Int, useMmap: Boolean): Long
    private external fun nativeUnload(handle: Long)
    private external fun nativeModelInfo(handle: Long): String
    private external fun nativeHasChatTemplate(handle: Long): Boolean
    private external fun nativeChatTemplate(handle: Long): String
    private external fun nativeApplyChatTemplate(handle: Long, system: String, user: String): String
    private external fun nativeGenerate(handle: Long, prompt: String, grammar: String, maxTokens: Int): String
    private external fun nativeRequestStop(handle: Long)
}

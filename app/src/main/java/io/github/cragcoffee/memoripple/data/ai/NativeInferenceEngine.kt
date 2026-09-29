package io.github.cragcoffee.memoripple.data.ai

import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelFileResolver
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelLocation
import io.github.cragcoffee.memoripple.domain.ai.runtime.StopReason
import java.io.File

/** An opaque handle to a loaded model inside an engine. */
@JvmInline
value class EngineHandle(val raw: Long)

data class EngineGeneration(
    val text: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val ttftMillis: Long,
    val totalMillis: Long,
    val stop: StopReason,
    /** Phase 4B decomposition (docs/GENERATION_EFFICIENCY.md): tokenization, prompt evaluation, the reused prefix and what was actually evaluated. */
    val tokenizeMillis: Long = 0,
    val promptEvalMillis: Long = 0,
    val reusedPrefixTokens: Int = 0,
    val evaluatedPromptTokens: Int = promptTokens,
)

/**
 * The narrowest engine surface the runtime needs. [LlamaCppEngine] implements it over JNI; a
 * test implements it in memory. Nothing above this interface knows which engine is behind it.
 */
interface NativeInferenceEngine {
    /** False on a CPU without the instructions the native build assumes; the engine is then never called. */
    fun isSupportedDevice(): Boolean
    fun load(modelFile: File, contextSize: Int, threads: Int): EngineHandle?
    /** The model's own chat template around (system, user), thinking on or off as the profile says. */
    fun render(handle: EngineHandle, systemPrompt: String, userMessage: String, enableThinking: Boolean): String
    /**
     * One generation. With [allowPrefixReuse] the engine may keep the token-for-token common prefix
     * with its last generation and evaluate only the rest (Phase 4B); the runtime allows it only
     * inside one cache identity and calls [resetCache] between identities.
     */
    fun generate(handle: EngineHandle, prompt: String, grammar: String, maxTokens: Int, allowPrefixReuse: Boolean = false): EngineGeneration
    /** Forgets whatever the engine cached from earlier generations: the next one is a full evaluation. */
    fun resetCache(handle: EngineHandle) = Unit
    /** Asks a running generation to stop at its next token. */
    fun requestStop(handle: EngineHandle)
    fun unload(handle: EngineHandle)
}

/** Where a [ModelLocation] is on this device. */
interface ModelFileLocator {
    fun locate(location: ModelLocation): File
}

/** Production: under `noBackupFilesDir/models`, or the developer's absolute path. */
class NoBackupModelFileLocator(noBackupDir: File) : ModelFileLocator {
    private val resolver = ModelFileResolver(noBackupDir, io.github.cragcoffee.memoripple.data.ai.models.FileModelStore.MODEL_FILE)
    override fun locate(location: ModelLocation): File = resolver.resolve(location)
}

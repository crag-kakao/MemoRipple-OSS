package io.github.cragcoffee.memoripple.domain.ai.runtime

import java.io.File

/**
 * The runtime the AI domain talks to (docs/LOCAL_LLM_RUNTIME.md). Three verbs — load, generate,
 * unload — and a state. Nothing here names an engine, a vendor, a file format or a platform: the
 * adapter in the data layer does, behind [LocalModelRuntime].
 *
 * Lifecycle rule from Phase 0 (the S20 reaches thermal SEVERE within minutes of continuous
 * inference): load → one or a few generations → idle → unload. Nothing stays resident by design.
 */
interface LocalModelRuntime {
    fun state(): RuntimeState
    suspend fun load(model: ModelDescriptor): LoadResult
    suspend fun generate(request: GenerationRequest): GenerationResult
    /** Idempotent. During a generation it asks the engine to stop, waits for it, then unloads. */
    suspend fun unload()
}

enum class RuntimeState { UNLOADED, LOADING, READY, GENERATING, UNLOADING, FAILED }

/** What a model is, for the domain: a name, a file, a shape. No vendor, no id of anything else. */
data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val location: ModelLocation,
    /** The architecture family the engine must know (informational for the domain). */
    val architecture: String,
    /** The context the runtime is asked to open — Phase 0's 4096, not the model's maximum. */
    val contextSize: Int,
    val quantization: String,
    val capabilities: Set<ModelCapability>,
)

sealed interface ModelLocation {
    /** A file under the app's model directory (below noBackupFilesDir: never backed up). */
    data class AppFile(val fileName: String) : ModelLocation
    /** A developer-injected absolute path (the Phase 2 smoke); never used in a shipped flow. */
    data class DeveloperPath(val absolutePath: String) : ModelLocation
    /** A model the user installed through the catalog: its own directory under the model directory (Phase 5); the data layer names the file. */
    data class Installed(val modelId: String) : ModelLocation
}

enum class ModelCapability { STRUCTURED_OUTPUT, JAPANESE, THINKING_SWITCH }

/** Where model files live: under the no-backup directory, or at an explicit developer path. */
class ModelFileResolver(private val noBackupDir: File, private val installedFileName: String) {
    fun resolve(location: ModelLocation): File = when (location) {
        is ModelLocation.AppFile -> File(File(noBackupDir, MODELS_DIR), File(location.fileName).name)
        is ModelLocation.DeveloperPath -> File(location.absolutePath)
        is ModelLocation.Installed -> File(File(File(noBackupDir, MODELS_DIR), safeId(location.modelId)), installedFileName)
    }

    companion object {
        const val MODELS_DIR = "models"
        private val SAFE_ID = Regex("[a-z0-9-]{1,64}")

        /** A catalog id is the only thing that may name a model directory: lowercase, digits, hyphens; never a path. */
        fun safeId(modelId: String): String {
            require(SAFE_ID.matches(modelId)) { "not a model id" }
            return modelId
        }
    }
}

/**
 * One structured generation: the shared system prompt, the user turn, the grammar that fixes the
 * shape, and a small token budget. The adapter renders the model's own chat template around it.
 */
data class GenerationRequest(
    val systemPrompt: String,
    val userMessage: String,
    val grammar: String,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    /**
     * Phase 4B (docs/GENERATION_EFFICIENCY.md): the cache identity this request belongs to — a
     * [GenerationCacheKey] value — or null for "no reuse, evaluate everything". The runtime lets the
     * engine keep a token-for-token common prefix only between requests of the same key.
     */
    val cacheKey: String? = null,
) {
    init { require(maxTokens in 1..MAX_TOKENS_CEILING) { "structured intents are short generations" } }

    companion object {
        const val DEFAULT_MAX_TOKENS = 256
        const val MAX_TOKENS_CEILING = 512
    }
}

/** The one generation route today; a future free-text route would be another identity, never the same cache. */
enum class GenerationRoute { STRUCTURED_INTENT }

/**
 * What must all be equal for one generation to reuse another's evaluated prefix: the model, the
 * prompt version, the grammar, the conversation and the route. Any difference is a full
 * evaluation; a model switch, a delete, an unload, a process death or a memory / thermal release
 * discard the native context itself, so nothing survives them regardless of the key.
 */
data class GenerationCacheKey(
    val modelId: String,
    val promptVersion: String,
    val grammarHash: Int,
    val conversationId: Long,
    val route: GenerationRoute,
) {
    val value: String get() = "$modelId|$promptVersion|$grammarHash|$conversationId|${route.name}"
}

enum class RuntimeFailure { MODEL_FILE_MISSING, UNSUPPORTED_DEVICE, INVALID_STATE, BUSY, ENGINE_ERROR }

sealed interface LoadResult {
    data class Loaded(val model: ModelDescriptor, val loadMillis: Long) : LoadResult
    data class Failed(val reason: RuntimeFailure) : LoadResult
}

enum class StopReason { END, MAX_TOKENS, CANCELLED, ERROR }

sealed interface GenerationResult {
    data class Generated(
        val text: String,
        val promptTokens: Int,
        val generatedTokens: Int,
        val ttftMillis: Long,
        val totalMillis: Long,
        val stop: StopReason,
        /** Phase 4B decomposition: tokenization, prompt evaluation, the reused prefix and the tokens actually evaluated. */
        val tokenizeMillis: Long = 0,
        val promptEvalMillis: Long = 0,
        val reusedPrefixTokens: Int = 0,
        val evaluatedPromptTokens: Int = promptTokens,
    ) : GenerationResult
    data class Failed(val reason: RuntimeFailure) : GenerationResult
}

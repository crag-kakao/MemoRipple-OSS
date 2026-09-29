package io.github.cragcoffee.memoripple.data.ai

import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationRequest
import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationResult
import io.github.cragcoffee.memoripple.domain.ai.runtime.LoadResult
import io.github.cragcoffee.memoripple.domain.ai.runtime.LocalModelRuntime
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelDescriptor
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeFailure
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
import io.github.cragcoffee.memoripple.domain.ai.runtime.StopReason
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The state machine over a [NativeInferenceEngine]:
 *
 * - `load` only from UNLOADED or FAILED (a second load while READY is INVALID_STATE, while
 *   LOADING it is BUSY); an unsupported device or a missing file fails before the engine is touched;
 * - `generate` only from READY, one at a time (a second concurrent call is BUSY);
 * - `unload` is idempotent; during a generation it asks the engine to stop, waits for the
 *   generation to return (as CANCELLED), then unloads.
 *
 * The engine's blocking calls run on [Dispatchers.Default]; the state is what callers read.
 */
class LocalModelRuntimeImpl(
    private val engine: NativeInferenceEngine,
    private val files: ModelFileLocator,
    private val profileFor: (ModelDescriptor) -> ModelProfile,
    private val fileExists: (File) -> Boolean = { it.isFile },
    private val threads: Int = 4,
) : LocalModelRuntime {
    private val state = AtomicReference(RuntimeState.UNLOADED)
    private val engineLock = Mutex()          // held for the whole of load / generate / unload
    private var handle: EngineHandle? = null
    private var loaded: ModelDescriptor? = null
    private var profile: ModelProfile? = null
    /** Phase 4B: the cache identity of the last successful generation; null = the engine holds nothing to reuse. */
    @Volatile private var cacheKey: String? = null

    override fun state(): RuntimeState = state.get()

    /** The identity whose prefix the engine currently holds (tests, diagnostics); null after a load, an unload or an error. */
    fun cacheKey(): String? = cacheKey

    override suspend fun load(model: ModelDescriptor): LoadResult {
        if (!state.compareAndSet(RuntimeState.UNLOADED, RuntimeState.LOADING) && !state.compareAndSet(RuntimeState.FAILED, RuntimeState.LOADING)) {
            return LoadResult.Failed(if (state.get() == RuntimeState.LOADING) RuntimeFailure.BUSY else RuntimeFailure.INVALID_STATE)
        }
        return engineLock.withLock {
            if (!engine.isSupportedDevice()) { state.set(RuntimeState.UNLOADED); return@withLock LoadResult.Failed(RuntimeFailure.UNSUPPORTED_DEVICE) }
            val file = files.locate(model.location)
            if (!fileExists(file)) { state.set(RuntimeState.UNLOADED); return@withLock LoadResult.Failed(RuntimeFailure.MODEL_FILE_MISSING) }
            val t0 = System.currentTimeMillis()
            val h = withContext(Dispatchers.Default) { engine.load(file, model.contextSize, threads) }
            if (h == null) { state.set(RuntimeState.FAILED); return@withLock LoadResult.Failed(RuntimeFailure.ENGINE_ERROR) }
            handle = h; loaded = model; profile = profileFor(model)
            state.set(RuntimeState.READY)
            LoadResult.Loaded(model, System.currentTimeMillis() - t0)
        }
    }

    override suspend fun generate(request: GenerationRequest): GenerationResult {
        if (!state.compareAndSet(RuntimeState.READY, RuntimeState.GENERATING)) {
            return GenerationResult.Failed(if (state.get() == RuntimeState.GENERATING) RuntimeFailure.BUSY else RuntimeFailure.INVALID_STATE)
        }
        return engineLock.withLock {
            val h = handle ?: run { state.set(RuntimeState.FAILED); return@withLock GenerationResult.Failed(RuntimeFailure.INVALID_STATE) }
            val p = profile ?: ModelProfile(loaded?.id.orEmpty(), enableThinking = false)
            // Phase 4B: a prefix is reused only inside one identity — a missing or changed key resets the engine first
            val key = request.cacheKey
            val reuse = key != null && key == cacheKey
            if (!reuse) { engine.resetCache(h); cacheKey = null }
            val out = try {
                withContext(Dispatchers.Default) {
                    val prompt = engine.render(h, request.systemPrompt, request.userMessage, p.enableThinking)
                    engine.generate(h, prompt, request.grammar, request.maxTokens, allowPrefixReuse = key != null)
                }
            } catch (t: Throwable) {
                cacheKey = null
                if (state.get() == RuntimeState.GENERATING) state.set(RuntimeState.READY)
                return@withLock GenerationResult.Failed(RuntimeFailure.ENGINE_ERROR)
            }
            state.compareAndSet(RuntimeState.GENERATING, RuntimeState.READY)
            // after an error nothing the engine holds is trusted; after a good generation the key names what it holds
            cacheKey = if (out.stop == StopReason.ERROR) null else key
            GenerationResult.Generated(
                out.text, out.promptTokens, out.generatedTokens, out.ttftMillis, out.totalMillis, out.stop,
                tokenizeMillis = out.tokenizeMillis, promptEvalMillis = out.promptEvalMillis,
                reusedPrefixTokens = out.reusedPrefixTokens, evaluatedPromptTokens = out.evaluatedPromptTokens,
            )
        }
    }

    override suspend fun unload() {
        // A generation in flight is asked to stop; the lock below waits for it to return.
        if (state.get() == RuntimeState.GENERATING) handle?.let { engine.requestStop(it) }
        engineLock.withLock {
            val h = handle ?: run { if (state.get() == RuntimeState.FAILED) state.set(RuntimeState.UNLOADED); return }
            state.set(RuntimeState.UNLOADING)
            withContext(Dispatchers.Default) { engine.unload(h) }
            handle = null; loaded = null; profile = null
            cacheKey = null   // the native context is gone with the model: nothing survives an unload
            state.set(RuntimeState.UNLOADED)
        }
    }
}

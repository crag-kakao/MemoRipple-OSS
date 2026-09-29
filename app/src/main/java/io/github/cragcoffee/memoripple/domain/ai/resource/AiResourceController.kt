package io.github.cragcoffee.memoripple.domain.ai.resource

import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationWindow
import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationRequest
import io.github.cragcoffee.memoripple.domain.ai.runtime.LoadResult
import io.github.cragcoffee.memoripple.domain.ai.runtime.LocalModelRuntime
import io.github.cragcoffee.memoripple.domain.ai.runtime.MemoryPressure
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelDescriptor
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeFailure
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
import io.github.cragcoffee.memoripple.domain.ai.runtime.ThermalDecision
import io.github.cragcoffee.memoripple.domain.ai.runtime.ThermalGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AI Resource Controller — Phase 2 (docs/AI_RESOURCE_CONTROLLER.md, human brief 2026-09-23).
 *
 * The application-scoped owner of the generation model's lifecycle: **lazy load** (nothing loads
 * until the first genuinely generative request), **keep warm** (a finished generation leaves the
 * model resident), **idle unload** (the configured window after the last generation, and only
 * with no active lease), the memory / power / thermal **policy** and the generation **budget**.
 *
 * The controller knows nothing of intents, resolvers, previews or documents — its whole world is
 * the runtime, a clock and three environment readings. The Fast Path, the template runner, the
 * chat screen and the history never call it: only a generation acquires, so only a generation
 * moves the idle deadline.
 */
interface AiResourceController {
    /**
     * Brackets one generation: creates the runtime on first use, loads [model] if it is not the
     * one READY, and hands back a [ResourceLease] carrying the runtime, the profile and the
     * budget of this request. [onLoading] fires only when a real load is about to run (the
     * screen's 「AIモデルを読み込んでいます…」). The caller must [ResourceLease.release] in a finally.
     */
    suspend fun acquireForGeneration(model: ModelDescriptor, onLoading: () -> Unit = {}): AcquireOutcome

    /** The profile as the environment stands now — memory over thermal over power over normal. */
    fun currentProfile(): PerformanceProfile

    /**
     * The Phase 3 memory amendment, moved here whole: LOW unloads an idle model at once and
     * defers to the release while a lease is active; CRITICAL unloads immediately (the runtime
     * stops a running generation itself, which surfaces as a cancelled, write-free outcome);
     * MODERATE does nothing. Never creates a runtime.
     */
    suspend fun onMemoryPressure(pressure: MemoryPressure)

    /** An explicit unload — the model switch / deselection / deletion path. Idempotent. */
    suspend fun unload(reason: UnloadReason)

    /** Internal observability (tests, debugging) — never a UI surface. */
    fun state(): AiResourceState
    fun runtimeState(): RuntimeState
    fun metrics(): AiResourceMetrics
}

/** How the environment shapes a generation. Priority: LOW_MEMORY > THERMAL_LIMITED > ECO > NORMAL. */
enum class PerformanceProfile { NORMAL, ECO, THERMAL_LIMITED, LOW_MEMORY }

/**
 * What one generation may spend (Phase 2): the conversation window and the output tokens. NORMAL
 * is exactly the standing baseline — introducing the controller changes nothing on a NORMAL ask —
 * and the reduced budget shrinks the window and the output without touching prompt v1 or the
 * grammar (their byte identity has its own guards).
 */
data class GenerationBudget(
    val maxRecentMessages: Int,
    val maxContextChars: Int,
    val maxMessageChars: Int,
    val maxOutputTokens: Int,
) {
    /** The newest lines that fit this budget; a drop marks the window truncated. */
    fun clip(window: ConversationWindow): ConversationWindow {
        if (window.messages.size <= maxRecentMessages && window.messages.sumOf { it.text.length } <= maxContextChars) return window
        val kept = ArrayList<io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationLine>()
        var chars = 0
        for (line in window.messages.asReversed()) {
            if (kept.size >= maxRecentMessages) break
            if (chars + line.text.length > maxContextChars && kept.isNotEmpty()) break
            kept.add(0, line)
            chars += line.text.length
        }
        return ConversationWindow(kept, truncated = true)
    }

    companion object {
        /** Today's numbers (ActiveContextBudget, GenerationRequest): the NORMAL ask is unchanged. */
        val NORMAL = GenerationBudget(maxRecentMessages = 6, maxContextChars = 1_200, maxMessageChars = 300, maxOutputTokens = 256)
        /** ECO / THERMAL_LIMITED / LOW_MEMORY: a smaller window and a shorter answer, the same meaning. */
        val REDUCED = GenerationBudget(maxRecentMessages = 4, maxContextChars = 800, maxMessageChars = 300, maxOutputTokens = 192)

        fun forProfile(profile: PerformanceProfile): GenerationBudget =
            if (profile == PerformanceProfile.NORMAL) NORMAL else REDUCED
    }

    init {
        require(maxOutputTokens in 1..GenerationRequest.MAX_TOKENS_CEILING)
    }
}

/** The keep-warm window: how long a finished generation keeps the model resident. Configurable, not decided for good. */
data class IdleUnloadPolicy(
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    /** Under ECO / THERMAL_LIMITED the warmth is shorter. */
    val reducedTimeoutMillis: Long = DEFAULT_REDUCED_MILLIS,
) {
    companion object {
        /** The standing conservative default (Phase 3's two minutes), kept until the S20 data decides otherwise. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 2 * 60 * 1000
        const val DEFAULT_REDUCED_MILLIS: Long = 30 * 1000
    }
}

/** Why the model left memory — for the metrics and the log line, never for the screen. */
enum class UnloadReason { IDLE, MEMORY_LOW, MEMORY_CRITICAL, EXPLICIT }

/** The controller's view of the resource; derived from the runtime, never a second state machine that could lie. */
sealed interface AiResourceState {
    data object Unloaded : AiResourceState
    data object Loading : AiResourceState
    data class Ready(val modelId: String) : AiResourceState
    data class Generating(val modelId: String) : AiResourceState
    data object Unloading : AiResourceState
    data class Failed(val reason: RuntimeFailure) : AiResourceState
}

/** Counts and reasons only — debug / test observability, no product surface. */
data class AiResourceMetrics(
    val loads: Int = 0,
    val unloads: Int = 0,
    val generations: Int = 0,
    val lastLoadMillis: Long = 0,
    val lastUnloadReason: UnloadReason? = null,
)

/** One generation's grip on the model. Idempotent [release]; the idle clock starts at the last release. */
class ResourceLease internal constructor(
    val runtime: LocalModelRuntime,
    val profile: PerformanceProfile,
    val budget: GenerationBudget,
    private val controller: DefaultAiResourceController,
) {
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun release() {
        if (released.compareAndSet(false, true)) controller.released()
    }
}

sealed interface AcquireOutcome {
    data class Acquired(val lease: ResourceLease) : AcquireOutcome
    data class Refused(val reason: RuntimeFailure) : AcquireOutcome
}

/**
 * The one implementation: a single mutex serializes every lifecycle transition (load, unload,
 * lease counting), so two requests cannot double-load, an unload cannot race a load, and a memory
 * callback never frees anything the runtime still holds — the runtime's own lock then serializes
 * the native side. [scope] runs the idle job (null = no idle scheduling, for bare tests);
 * [delayFn] is the clock, injectable so tests advance it instead of sleeping.
 */
class DefaultAiResourceController(
    private val runtime: () -> LocalModelRuntime,
    private val scope: CoroutineScope?,
    private val idle: IdleUnloadPolicy = IdleUnloadPolicy(),
    private val thermal: ThermalGate? = null,
    private val powerSaver: () -> Boolean = { false },
    private val log: (String) -> Unit = {},
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) : AiResourceController {
    private val transitions = Mutex()
    private var created: LocalModelRuntime? = null
    private var loadedModelId: String? = null
    private var lastFailure: RuntimeFailure? = null
    private var activeLeases = 0
    private var pendingUnload: UnloadReason? = null
    private var idleJob: Job? = null
    private var loads = 0
    private var unloads = 0
    private var generations = 0
    private var lastLoadMillis = 0L
    private var lastUnloadReason: UnloadReason? = null

    override suspend fun acquireForGeneration(model: ModelDescriptor, onLoading: () -> Unit): AcquireOutcome = transitions.withLock {
        idleJob?.cancel(); idleJob = null
        val rt = created ?: runtime().also { created = it }
        if (rt.state() != RuntimeState.READY) {
            if (rt.state() == RuntimeState.FAILED) rt.unload()
            onLoading()
            log("AI_RESOURCE load_start")
            when (val loaded = rt.load(model)) {
                is LoadResult.Loaded -> {
                    loads++; lastLoadMillis = loaded.loadMillis; loadedModelId = model.id; lastFailure = null
                    log("AI_RESOURCE load_done millis=${loaded.loadMillis}")
                }
                is LoadResult.Failed -> {
                    lastFailure = loaded.reason
                    log("AI_RESOURCE load_failed reason=${loaded.reason}")
                    return AcquireOutcome.Refused(loaded.reason)
                }
            }
        }
        activeLeases++
        val profile = profileLocked()
        if (profile != PerformanceProfile.NORMAL) log("AI_RESOURCE profile=$profile")
        AcquireOutcome.Acquired(ResourceLease(rt, profile, GenerationBudget.forProfile(profile), this))
    }

    internal suspend fun released() {
        transitions.withLock {
            activeLeases = (activeLeases - 1).coerceAtLeast(0)
            generations++
            if (activeLeases > 0) return
            val deferred = pendingUnload
            pendingUnload = null
            if (deferred != null) {
                unloadLocked(deferred)
                return
            }
            scheduleIdleLocked()
        }
    }

    override fun currentProfile(): PerformanceProfile = profileLocked()

    /** Reads only volatile-ish snapshots; the order is the brief's: memory > thermal > power > normal. */
    private fun profileLocked(): PerformanceProfile = when {
        pendingUnload != null -> PerformanceProfile.LOW_MEMORY
        thermal?.check() == ThermalDecision.THROTTLED -> PerformanceProfile.THERMAL_LIMITED
        powerSaver() -> PerformanceProfile.ECO
        else -> PerformanceProfile.NORMAL
    }

    override suspend fun onMemoryPressure(pressure: MemoryPressure) {
        when (pressure) {
            MemoryPressure.MODERATE -> Unit
            MemoryPressure.LOW -> transitions.withLock {
                if (created == null) return
                if (activeLeases > 0) {
                    // the S20 rule (Phase 3 amendment): loading a model raises the trim level by
                    // itself — a request in flight finishes, then the model goes
                    pendingUnload = UnloadReason.MEMORY_LOW
                    log("AI_RESOURCE memory=LOW deferred=true")
                } else {
                    log("AI_RESOURCE memory=LOW deferred=false")
                    unloadLocked(UnloadReason.MEMORY_LOW)
                }
            }
            MemoryPressure.CRITICAL -> transitions.withLock {
                if (created == null) return
                log("AI_RESOURCE memory=CRITICAL")
                // the runtime stops a running generation itself and the request surfaces as a
                // cancelled, write-free outcome; the release after this frees nothing again
                unloadLocked(UnloadReason.MEMORY_CRITICAL)
            }
        }
    }

    override suspend fun unload(reason: UnloadReason) {
        transitions.withLock {
            pendingUnload = null
            unloadLocked(reason)
        }
    }

    /** The only place the runtime's unload is called: under the mutex, counted only when something was loaded. */
    private suspend fun unloadLocked(reason: UnloadReason) {
        idleJob?.cancel(); idleJob = null
        val rt = created ?: return
        val hadModel = rt.state() != RuntimeState.UNLOADED
        rt.unload()
        loadedModelId = null
        if (hadModel) {
            unloads++
            lastUnloadReason = reason
            log("AI_RESOURCE unload reason=${reason.name.lowercase()}")
        }
    }

    private fun scheduleIdleLocked() {
        val runner = scope ?: return
        if (created?.state() != RuntimeState.READY) return
        val window = if (profileLocked() == PerformanceProfile.NORMAL) idle.timeoutMillis else idle.reducedTimeoutMillis
        idleJob?.cancel()
        idleJob = runner.launch {
            delayFn(window)
            transitions.withLock {
                idleJob = null   // before unloadLocked, which would otherwise cancel this very coroutine mid-unload
                if (activeLeases > 0) return@withLock
                unloadLocked(UnloadReason.IDLE)
            }
        }
    }

    override fun state(): AiResourceState {
        val rt = created ?: return AiResourceState.Unloaded
        return when (rt.state()) {
            RuntimeState.UNLOADED -> AiResourceState.Unloaded
            RuntimeState.LOADING -> AiResourceState.Loading
            RuntimeState.READY -> AiResourceState.Ready(loadedModelId.orEmpty())
            RuntimeState.GENERATING -> AiResourceState.Generating(loadedModelId.orEmpty())
            RuntimeState.UNLOADING -> AiResourceState.Unloading
            RuntimeState.FAILED -> AiResourceState.Failed(lastFailure ?: RuntimeFailure.ENGINE_ERROR)
        }
    }

    override fun runtimeState(): RuntimeState = created?.state() ?: RuntimeState.UNLOADED

    override fun metrics(): AiResourceMetrics =
        AiResourceMetrics(loads = loads, unloads = unloads, generations = generations, lastLoadMillis = lastLoadMillis, lastUnloadReason = lastUnloadReason)
}

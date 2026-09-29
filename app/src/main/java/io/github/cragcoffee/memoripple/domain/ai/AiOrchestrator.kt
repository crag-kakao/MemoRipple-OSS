package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationReferent
import io.github.cragcoffee.memoripple.domain.ai.decision.DecisionCertainty
import io.github.cragcoffee.memoripple.domain.ai.decision.DecisionEngine
import io.github.cragcoffee.memoripple.domain.ai.decision.DecisionInput
import io.github.cragcoffee.memoripple.domain.ai.decision.DecisionOutcome
import io.github.cragcoffee.memoripple.domain.ai.decision.DecisionResult
import io.github.cragcoffee.memoripple.domain.ai.decision.DeterministicDecisionEngine
import io.github.cragcoffee.memoripple.domain.ai.fast.ChatRoute
import io.github.cragcoffee.memoripple.domain.ai.fast.DeterministicChatRouter
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationReferents
import io.github.cragcoffee.memoripple.domain.ai.resource.AcquireOutcome
import io.github.cragcoffee.memoripple.domain.ai.resource.AiResourceController
import io.github.cragcoffee.memoripple.domain.ai.resource.DefaultAiResourceController
import io.github.cragcoffee.memoripple.domain.ai.resource.UnloadReason
import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationCacheKey
import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationRefusal
import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationRoute
import io.github.cragcoffee.memoripple.domain.ai.runtime.IntentGeneration
import io.github.cragcoffee.memoripple.domain.ai.runtime.LocalModelRuntime
import io.github.cragcoffee.memoripple.domain.ai.runtime.MemoryPressure
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelDescriptor
import io.github.cragcoffee.memoripple.domain.ai.runtime.PromptAssets
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeFailure
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
import io.github.cragcoffee.memoripple.domain.ai.runtime.StructuredIntentGenerator
import io.github.cragcoffee.memoripple.domain.ai.runtime.ThermalGate
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import io.github.cragcoffee.memoripple.domain.documents.DocumentAccess
import io.github.cragcoffee.memoripple.domain.documents.CreateDestination
import io.github.cragcoffee.memoripple.domain.documents.DocumentCreate
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentQuery
import io.github.cragcoffee.memoripple.domain.documents.DocumentReadResult
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.TemplateAction
import io.github.cragcoffee.memoripple.domain.memos.TemplateFlow
import io.github.cragcoffee.memoripple.domain.memos.TemplateRenderer
import io.github.cragcoffee.memoripple.domain.memos.TemplateRendering
import io.github.cragcoffee.memoripple.domain.memos.TemplateTargetSpec
import io.github.cragcoffee.memoripple.domain.memos.TemplateValidation
import io.github.cragcoffee.memoripple.domain.memos.TemplateValues
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which model the product would load — no default: the data layer answers from what the user
 * selected and installed (or a developer file in a debug build), or nothing. Phase 7 adds *why*
 * nothing: [availability] answers before any runtime exists, so a screen can show the setup
 * card instead of an input that would fail.
 */
interface ModelSelection {
    suspend fun selected(): ModelDescriptor?

    /** The model, or the reason there is none. The default derives it from [selected]; the product selection is more precise. */
    suspend fun availability(): ModelAvailability =
        selected()?.let { ModelAvailability.Available(it) } ?: ModelAvailability.Unavailable(ModelUnavailableReason.NO_MODEL_CONFIGURED)
}

/** Whether the AI can be asked at all — a model question, answered without loading anything. Thermal is not part of it. */
sealed interface ModelAvailability {
    data class Available(val model: ModelDescriptor) : ModelAvailability
    data class Unavailable(val reason: ModelUnavailableReason) : ModelAvailability
}

/**
 * How long the model took for one ask — numbers only (the chat shows them under the answer, as
 * the reference app does): the prompt and answer sizes in tokens, the time to the first token
 * and the whole generation. Never persisted; no text travels with it.
 */
data class AiTiming(
    val promptTokens: Int,
    val generatedTokens: Int,
    val ttftMillis: Long,
    val totalMillis: Long,
    /** Phase 4B decomposition (docs/GENERATION_EFFICIENCY.md) — every field a number; the product UI shows none of these. */
    val loadMillis: Long = 0,
    val promptBuildMillis: Long = 0,
    val tokenizeMillis: Long = 0,
    val promptEvalMillis: Long = 0,
    val reusedPrefixTokens: Int = 0,
    val evaluatedPromptTokens: Int = promptTokens,
    val promptChars: Int = 0,
) {
    val millisPerToken: Double get() = if (generatedTokens > 0) totalMillis.toDouble() / generatedTokens else 0.0
    val tokensPerSecond: Double get() = if (totalMillis > 0) generatedTokens * 1000.0 / totalMillis else 0.0
    /** How fast the prompt was evaluated — over the tokens actually evaluated, not the reused prefix. */
    val promptTokensPerSecond: Double get() = if (promptEvalMillis > 0) evaluatedPromptTokens * 1000.0 / promptEvalMillis else 0.0
}

/** What a screen may show while an interaction runs. */
enum class AiProgress { LOADING_MODEL, GENERATING }

/** Why no model can be asked: nothing selected, the selected file gone, the selected file not the verified length, a CPU the native build cannot run on. */
enum class ModelUnavailableReason { NO_MODEL_CONFIGURED, MODEL_FILE_MISSING, MODEL_FILE_CORRUPT, UNSUPPORTED_DEVICE }

enum class AiFailureStage { LOAD, GENERATION, PARSE }

/**
 * The Human Confirmation boundary (docs/AI_CONFIRMED_WRITE.md): a previewed write, held in memory
 * as an opaque, single-use ticket. Nothing on it can write; only [AiOrchestrator.execute] turns
 * it into a [ConfirmedCommand], once. Never serialised, never saved — a process death loses the
 * preview and therefore the ticket, and nothing resumes.
 */
class PendingWrite internal constructor(private val decision: ExecutionDecision.RequiresConfirmation) {
    private val consumed = java.util.concurrent.atomic.AtomicBoolean(false)

    val preview: CommandPreview get() = decision.preview

    /** True once [AiOrchestrator.execute] has taken it; a taken ticket runs nothing again. */
    val isConsumed: Boolean get() = consumed.get()

    internal fun take(): ExecutionDecision.RequiresConfirmation? = if (consumed.compareAndSet(false, true)) decision else null
}

/** What a confirmed write came to, in the screen's terms. Nothing here can run anything. */
sealed interface WriteOutcome {
    data class Success(val ref: DocumentRef) : WriteOutcome
    /** The document moved after the preview; nothing was written and nothing is retried. */
    data object Conflict : WriteOutcome
    data object ReadOnly : WriteOutcome
    data object NotFound : WriteOutcome
    data class Rejected(val reason: String) : WriteOutcome
    data class Failed(val developerDetail: String) : WriteOutcome
    /** The ticket was already taken: a second tap, a second call. Nothing ran. */
    data object AlreadyExecuted : WriteOutcome
}

/**
 * The one result type a screen handles (docs/AI_CHAT_PREVIEW.md). Reads carry their outcome;
 * writes carry a [CommandPreview] and nothing that could run it; every refusal is its own case
 * so the screen can explain it without knowing why the pipeline said so.
 */
sealed interface AiInteractionResult {
    data class SearchResults(val query: DocumentQuery, val results: List<DocumentSummary>) : AiInteractionResult
    /** A unique OPEN: the document to hand to the navigator. */
    data class Open(val target: DocumentSummary) : AiInteractionResult
    /** CREATE / APPEND / USE_TEMPLATE: what would be written, and the single-use ticket the user's confirmation runs. */
    data class WritePreview(val preview: CommandPreview, val pending: PendingWrite) : AiInteractionResult
    /** A confirmed write came back; [preview] is what the user confirmed, [outcome] what happened. */
    data class Written(val outcome: WriteOutcome, val preview: CommandPreview) : AiInteractionResult
    /** Template v2: the template needs values before it can run — the screen shows its form; [missing] are the required keys still empty. */
    data class TemplateForm(val template: MemoTemplate, val missing: Set<String>, val given: Map<String, String> = emptyMap()) : AiInteractionResult
    /**
     * Think templates (docs/THINK_TEMPLATES.md): the conversation ended in a rendered result — made by the
     * chat's deterministic engine, never by this orchestrator or a model; it holds no ticket and writes
     * nothing. 「メモとして保存」 runs the same template down the CREATE path ([runTemplate]) to a [WritePreview].
     */
    data class ThinkResult(val template: MemoTemplate, val text: String, val answers: Map<String, String>) : AiInteractionResult
    data class Ambiguous(val candidates: List<DocumentSummary>) : AiInteractionResult
    /** The fields the user has to add, and the intent they were missing from (so a screen can name "追記先" or "開く対象"). */
    data class NeedsInformation(val fields: Set<ProposalField>, val intent: AiIntent) : AiInteractionResult
    data class NotFound(val field: ProposalField) : AiInteractionResult
    data class Invalid(val reasons: List<AiRejection>) : AiInteractionResult
    data object Unknown : AiInteractionResult
    data object ThermalBlocked : AiInteractionResult
    data class ModelUnavailable(val reason: ModelUnavailableReason) : AiInteractionResult
    /** Something below the pipeline failed; [developerDetail] is for the log, never for the screen. */
    data class RuntimeError(val stage: AiFailureStage, val developerDetail: String) : AiInteractionResult
}

/**
 * The one door from a screen to the AI path: text in, [AiInteractionResult] out. A screen never
 * calls a generator, a resolver or an executor itself.
 */
interface AiOrchestrator {
    /**
     * [onNote] receives developer notes (e.g. how a missing target was resolved) — for the log, never the screen.
     * [referents] (Phase 8) is what the conversation lends to this ask: the last document anchor and the recent
     * window; [context] is the latest shown results rebuilt for this request. Neither can execute anything.
     */
    suspend fun interact(
        userText: String,
        context: AiResultContext,
        onProgress: (AiProgress) -> Unit = {},
        onNote: (String) -> Unit = {},
        referents: ConversationReferents = ConversationReferents.NONE,
        onTiming: (AiTiming) -> Unit = {},
        /** The chat's chosen folder for a CREATE (2026-09-22); the model never sees it; a journal ignores it. */
        destination: CreateDestination? = null,
    ): AiInteractionResult
    fun runtimeState(): RuntimeState

    /**
     * Phase 7 (docs/CHAT_V1_RELEASE_READINESS.md): whether an ask could reach a model right now —
     * the CPU gate and the selection, read without constructing a runtime or loading anything. A
     * screen asks this before it offers an input, and again whenever it comes back.
     */
    suspend fun availability(): ModelAvailability

    /**
     * Template v2 (docs/CHAT_UI_TEMPLATE_V2.md): runs a template with the user's values — no model,
     * no runtime, no thermal gate: validation → rendering → the unchanged Resolver / policy /
     * executor. A SEARCH answers at once; a CREATE / APPEND stops at its preview with the same
     * single-use ticket [execute] runs; missing values come back as a [AiInteractionResult.TemplateForm].
     */
    suspend fun runTemplate(template: MemoTemplate, values: Map<String, String>, destination: CreateDestination? = null): AiInteractionResult

    /**
     * Runs a previewed write the user has confirmed — once per ticket. No model, no runtime, no
     * thermal gate is involved: this is the boundary's `create` / `append` behind the Human
     * Confirmation, and it works with the model unloaded.
     */
    /**
     * Conversation → memo (Review Batch 2): a body the chat made by rule becomes the same preview and
     * single-use ticket a model's CREATE gets — no model, no runtime, no thermal gate; the write is still
     * [execute] after the user's confirmation, and nothing else.
     */
    suspend fun previewMemo(body: String, destination: CreateDestination? = null): AiInteractionResult

    /**
     * The Fast Path (docs/CHAT_FAST_PATH.md): a deterministic reading of an unmistakable sentence —
     * the same pipeline as [interact] minus the model; null means the sentence is the AI's.
     */
    suspend fun interactFast(userText: String, context: AiResultContext, referents: ConversationReferents = ConversationReferents.NONE, destination: CreateDestination? = null): AiInteractionResult?

    /**
     * The DecisionEngine door (Phase 3, docs/DECISION_ENGINE.md), tried after [interactFast] and
     * before [interact]: a pure decision over the conversation's safe context. A CERTAIN
     * operation settles through the same pipeline (zero loads, zero acquires); a clarification
     * comes back as its fixed question; null is NotApplicable — only then may the model run.
     */
    suspend fun interactDecide(userText: String, context: AiResultContext, referents: ConversationReferents = ConversationReferents.NONE, destination: CreateDestination? = null): DecisionOutcome? = null

    /**
     * Completes a clarified decision: the draft proposal with its answered slot, into the same
     * settle pipeline. No model, no runtime, no acquire — a write still stops at its preview.
     */
    suspend fun completeDecision(proposal: IntentProposal, anchorTarget: Boolean, context: AiResultContext, referents: ConversationReferents = ConversationReferents.NONE, destination: CreateDestination? = null): AiInteractionResult = AiInteractionResult.Unknown

    suspend fun execute(pending: PendingWrite): WriteOutcome

    /**
     * An explicit unload: the model switch / deselection / deletion path (never a screen's
     * lifecycle since Phase 2 — keep-warm outlives the chat, and the resource controller's idle
     * clock does the routine unloading). Waits for an ask in flight, then unloads. Idempotent.
     */
    suspend fun release()

    /**
     * The system's memory pressure, handed to the resource controller (Phase 2): LOW unloads an
     * *idle* model and defers to the end of an ask in flight (loading a model raises the trim
     * level by itself — the S20 smoke found the hook unloading the model between load and
     * generate); CRITICAL unloads regardless.
     */
    suspend fun onMemoryPressure(pressure: MemoryPressure) = Unit
}

/**
 * User text → runtime availability → thermal → model load → [StructuredIntentGenerator] →
 * [SemanticValidator] → [Resolver] → [ExecutionPolicy] → result. SEARCH and a unique OPEN run
 * through the [CommandExecutor]'s direct path; a write stops at [ExecutionDecision.RequiresConfirmation]
 * and only its preview leaves this class — the confirmation step does not exist in Phase 3.
 *
 * The runtime is created on the first interaction, never when the screen merely opens (the
 * engine loads its native library when constructed). One interaction at a time; the second waits.
 */
class LocalAiOrchestrator(
    private val runtime: () -> LocalModelRuntime,
    private val selection: ModelSelection,
    private val thermal: ThermalGate,
    private val assets: PromptAssets,
    private val resolver: Resolver,
    private val executor: CommandExecutor,
    /**
     * Phase 2 (docs/AI_RESOURCE_CONTROLLER.md): the owner of the model's lifecycle — lazy load,
     * keep warm, idle unload, memory / power / thermal policy, budget. Null builds a bare
     * controller with no idle scheduling (tests): the lifecycle rules still hold, nothing times out.
     */
    resources: AiResourceController? = null,
    /** The CPU feature gate, read here so an unsupported device is known before any engine is constructed. */
    private val deviceSupported: () -> Boolean = { true },
    /** Phase 8: the boundary the last document anchor is re-validated through before it may stand in as a target. */
    private val documents: DocumentAccess? = null,
    /** Phase 3 of the routing work (docs/DECISION_ENGINE.md): the pure decision between the Fast Path and the model. */
    private val decision: DecisionEngine = DeterministicDecisionEngine,
) : AiOrchestrator {
    private val lock = Mutex()
    private val resources: AiResourceController = resources ?: DefaultAiResourceController(runtime, scope = null, thermal = thermal)
    private var generator: StructuredIntentGenerator? = null
    private var generatorRuntime: LocalModelRuntime? = null

    override fun runtimeState(): RuntimeState = resources.runtimeState()

    override suspend fun availability(): ModelAvailability =
        if (!deviceSupported()) ModelAvailability.Unavailable(ModelUnavailableReason.UNSUPPORTED_DEVICE) else selection.availability()

    override suspend fun interact(userText: String, context: AiResultContext, onProgress: (AiProgress) -> Unit, onNote: (String) -> Unit, referents: ConversationReferents, onTiming: (AiTiming) -> Unit, destination: CreateDestination?): AiInteractionResult = lock.withLock {
        try {
            run(userText, context, onProgress, onNote, referents, onTiming, destination)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            AiInteractionResult.RuntimeError(AiFailureStage.GENERATION, "${t::class.java.simpleName}: ${t.message}")
        }
    }

    private suspend fun run(userText: String, context: AiResultContext, onProgress: (AiProgress) -> Unit, onNote: (String) -> Unit, referents: ConversationReferents, onTiming: (AiTiming) -> Unit, destination: CreateDestination?): AiInteractionResult {
        if (!thermal.check().allowsGeneration) return AiInteractionResult.ThermalBlocked
        // Phase 7: a missing or corrupt file, an unsupported CPU and an empty selection stop here — before a runtime exists
        val model = when (val availability = availability()) {
            is ModelAvailability.Available -> availability.model
            is ModelAvailability.Unavailable -> return AiInteractionResult.ModelUnavailable(availability.reason)
        }
        // Phase 2 (docs/AI_RESOURCE_CONTROLLER.md): the controller brackets the one generative part —
        // lazy load on the first genuine ask, keep warm after, the idle clock from the release
        val loadsBefore = resources.metrics().loads
        val lease = when (val acquired = resources.acquireForGeneration(model, onLoading = { onProgress(AiProgress.LOADING_MODEL) })) {
            is AcquireOutcome.Acquired -> acquired.lease
            is AcquireOutcome.Refused -> return when (acquired.reason) {
                RuntimeFailure.MODEL_FILE_MISSING -> AiInteractionResult.ModelUnavailable(ModelUnavailableReason.MODEL_FILE_MISSING)
                RuntimeFailure.UNSUPPORTED_DEVICE -> AiInteractionResult.ModelUnavailable(ModelUnavailableReason.UNSUPPORTED_DEVICE)
                else -> AiInteractionResult.RuntimeError(AiFailureStage.LOAD, "load failed: ${acquired.reason} (model ${model.id})")
            }
        }
        val loadMillis = resources.metrics().let { m -> if (m.loads > loadsBefore) m.lastLoadMillis else 0L }
        val proposed = try {
            onProgress(AiProgress.GENERATING)
            val rt = lease.runtime
            val gen = generator?.takeIf { generatorRuntime === rt } ?: StructuredIntentGenerator(rt, assets, thermal).also { generator = it; generatorRuntime = rt }
            // Phase 4B (docs/GENERATION_EFFICIENCY.md): the generation cache identity — model, prompt version, grammar,
            // this conversation, this route; a prefix is reused only inside it, and only with a conversation to key on
            val cacheKey = referents.conversationId?.let { conversationId ->
                GenerationCacheKey(model.id, assets.promptVersion, assets.intentGrammar.hashCode(), conversationId, GenerationRoute.STRUCTURED_INTENT).value
            }
            // the lease's budget: the window may shrink under ECO / LIMITED, the output cap likewise; NORMAL is byte-for-byte today's ask
            when (val g = gen.generate(userText, context, lease.budget.clip(referents.window), maxTokens = lease.budget.maxOutputTokens, cacheKey = cacheKey)) {
                is IntentGeneration.Proposed -> g.also {
                    onTiming(
                        AiTiming(
                            it.promptTokens, it.generatedTokens, it.ttftMillis, it.totalMillis,
                            loadMillis = loadMillis, promptBuildMillis = it.promptBuildMillis, tokenizeMillis = it.tokenizeMillis,
                            promptEvalMillis = it.promptEvalMillis, reusedPrefixTokens = it.reusedPrefixTokens,
                            evaluatedPromptTokens = it.evaluatedPromptTokens, promptChars = it.promptChars,
                        ),
                    )
                }
                is IntentGeneration.Refused -> return when (val r = g.reason) {
                    GenerationRefusal.ThermalBlocked -> AiInteractionResult.ThermalBlocked
                    GenerationRefusal.RuntimeNotReady -> AiInteractionResult.RuntimeError(AiFailureStage.GENERATION, "runtime not ready: ${rt.state()}")
                    is GenerationRefusal.GenerationFailed -> AiInteractionResult.RuntimeError(AiFailureStage.GENERATION, "generation failed: ${r.reason}")
                    is GenerationRefusal.Unparsable -> AiInteractionResult.RuntimeError(AiFailureStage.PARSE, "unparsable answer: ${r.failure} (${r.raw.length} chars)")
                }
            }
        } finally {
            lease.release()
        }
        // Phase 6 (docs/AI_TARGET_RESOLUTION.md): when the model gave neither a shown ref nor a name for OPEN / APPEND,
        // a few fixed shapes of the user's own words may yield a candidate *name* — still searched by the Resolver,
        // still previewed, still waiting for the user's own tap. A name the model did give is honoured as it is (no fallback after NotFound).
        var proposal = proposed.proposal
        var resolveContext = context
        // Template v2: the model may only *name* a template; its values are the user's (a form) or its defaults — the same runner as the plus button
        if (proposal.intent == AiIntent.USE_TEMPLATE) {
            val id = proposal.templateId?.trim().orEmpty()
            val template = if (id.isEmpty()) null else resolver.templates.find(id)
            onNote("use template: found=${template != null} fields=${template?.fields?.size ?: 0}")
            return if (template == null) AiInteractionResult.NotFound(ProposalField.TEMPLATE_ID) else runTemplate(template, emptyMap(), destination)
        }
        return settle(proposal, userText, resolveContext, referents, destination, onNote, proposed.proposal.intent)
    }

    /**
     * The Fast Path (docs/CHAT_FAST_PATH.md): an unmistakable sentence becomes a proposal by rule and
     * walks [settle] — the same validator → Resolver → policy → preview pipeline as a model's proposal —
     * with no selection, no runtime, no load, no generation, and no thermal gate (nothing generates; the
     * template runner's precedent). Null when the rules decline, or when a それに… lean has no anchor to
     * ride: the caller falls back to the AI route (or its no-model card) exactly as before.
     */
    override suspend fun interactFast(userText: String, context: AiResultContext, referents: ConversationReferents, destination: CreateDestination?): AiInteractionResult? {
        val fast = (DeterministicChatRouter.route(userText) as? ChatRoute.Fast)?.intent ?: return null
        if (fast.needsAnchor && referents.anchor == null) return null
        return settle(fast.proposal, userText, context, referents, destination, onNote = {}, intentForQuestions = fast.proposal.intent)
    }

    /**
     * The DecisionEngine door (Phase 3, docs/DECISION_ENGINE.md): a pure decision over the safe
     * context — the shown results' count and titles, the anchor's presence, kind and title, all
     * of *this* conversation only. A CERTAIN operation walks the same [settle] as everything
     * else (an anchor target is re-read through the boundary first — a vanished one is NotFound
     * and nothing retries); an AMBIGUOUS operation is refused; a clarification passes through
     * with its fixed question. No selection, no runtime, no thermal gate, no acquire.
     */
    override suspend fun interactDecide(userText: String, context: AiResultContext, referents: ConversationReferents, destination: CreateDestination?): DecisionOutcome? {
        val anchor = referents.anchor
        val anchorSummary = anchor?.let { ref ->
            when (val read = documents?.get(ref)) {
                is DocumentReadResult.Found -> read.content.summary
                else -> null   // vanished or unreadable: decided as "no anchor"; a CERTAIN path re-reads below anyway
            }
        }
        val input = DecisionInput(
            rawText = userText,
            shownCount = context.size,
            shownTitles = (1..context.size).mapNotNull { context.summary(AiResultRef(it))?.title },
            hasAnchor = anchor != null,
            anchorKind = anchor?.kind,
            anchorTitle = anchorSummary?.title,
        )
        return when (val decision = decision.decide(input)) {
            DecisionResult.NotApplicable -> null
            is DecisionResult.Operation -> {
                if (decision.certainty != DecisionCertainty.CERTAIN) return null   // never executed, tested
                DecisionOutcome.Settled(settleDecided(decision.proposal, decision.anchorTarget, context, referents, destination))
            }
            is DecisionResult.NeedsClarification ->
                DecisionOutcome.Clarify(decision.slot, decision.question, decision.choices, decision.draft, decision.anchorTarget)
        }
    }

    override suspend fun completeDecision(proposal: IntentProposal, anchorTarget: Boolean, context: AiResultContext, referents: ConversationReferents, destination: CreateDestination?): AiInteractionResult =
        settleDecided(proposal, anchorTarget, context, referents, destination)

    /** A decided proposal into [settle]; an anchor target becomes the one-line context the Phase 8 rule uses, re-read through the boundary. */
    private suspend fun settleDecided(proposal: IntentProposal, anchorTarget: Boolean, context: AiResultContext, referents: ConversationReferents, destination: CreateDestination?): AiInteractionResult {
        var resolveProposal = proposal
        var resolveContext = context
        if (anchorTarget) {
            val anchor = referents.anchor ?: return AiInteractionResult.NeedsInformation(setOf(ProposalField.TARGET_NAME), proposal.intent)
            val access = documents ?: return AiInteractionResult.NeedsInformation(setOf(ProposalField.TARGET_NAME), proposal.intent)
            val current = when (val read = access.get(anchor)) {
                is DocumentReadResult.Found -> read.content.summary
                DocumentReadResult.NotFound -> return AiInteractionResult.NotFound(ProposalField.TARGET_REF)
            }
            resolveContext = AiResultContext.of(listOf(current))
            resolveProposal = proposal.copy(targetRef = AiResultRef(1))
        }
        // the decided sentence is never re-parsed for referents: the decision already read it, so settle sees no user text
        return settle(resolveProposal, userText = "", resolveContext, referents, destination, onNote = {}, intentForQuestions = resolveProposal.intent)
    }

    /**
     * Everything after a proposal exists, whoever made it (the model or the Fast Path): the Phase 8
     * referent rules, the Phase 6 target assist, then SemanticValidator → Resolver → ExecutionPolicy —
     * a read runs, a write stops at its preview and the one confirmation. No model is reachable from here.
     */
    private suspend fun settle(
        initialProposal: IntentProposal,
        userText: String,
        context: AiResultContext,
        referents: ConversationReferents,
        destination: CreateDestination?,
        onNote: (String) -> Unit,
        intentForQuestions: AiIntent,
    ): AiInteractionResult {
        var proposal = initialProposal
        var resolveContext = context
        // Phase 8: a demonstrative the model wrote into targetName (「それ」, 「さっきのメモ」, 「2番目」) is a referent, not a title —
        // the Phase 6 rule that such words are never names, applied before any search; the user's own sentence decides below
        if (proposal.targetRef == null && proposal.targetName != null && ConversationReferent.isReferentPhrase(proposal.targetName)) {
            onNote("referent: model named a demonstrative; reading the sentence instead")
            proposal = proposal.copy(targetName = null)
        }
        // Phase 8 (seen on the S20): when the user's own sentence opens with a referent (「それに…」, 「2番目を…」) there is no name in
        // it to honour — a name the model still produced is set aside and the referent is read; a shown ref the model gave stands
        if (proposal.targetRef == null && proposal.targetName != null && (proposal.intent == AiIntent.OPEN || proposal.intent == AiIntent.APPEND) && ConversationReferent.parse(userText) != null) {
            onNote("referent: the sentence opens with a referent; a model-given name set aside")
            proposal = proposal.copy(targetName = null)
        }
        if ((proposal.intent == AiIntent.OPEN || proposal.intent == AiIntent.APPEND) && proposal.targetRef == null && proposal.targetName == null) {
            // Phase 8 (docs/AI_CONVERSATION_HISTORY.md): a referent in the user's own words — a position in the latest
            // shown results, or the last document anchor — read by rule. A position becomes the shown ref (the
            // Resolver re-validates it); the anchor is re-read through the boundary and stands in as a one-line
            // context. Missing, out of range or of another kind → a question, never a guess.
            when (val referent = ConversationReferent.parse(userText)) {
                is ConversationReferent.Ordinal, ConversationReferent.Last -> {
                    val index = if (referent is ConversationReferent.Ordinal) referent.index else context.size
                    val ref = if (index in 1..context.size) AiResultRef(index) else null
                    onNote("referent: kind=ORDINAL resolved= shown=")
                    if (ref == null) return AiInteractionResult.NeedsInformation(missingTarget(proposal), proposal.intent)
                    proposal = proposal.copy(targetRef = ref, missingFields = proposal.missingFields - setOf(ProposalField.TARGET_REF, ProposalField.TARGET_NAME))
                }
                is ConversationReferent.Anchor -> {
                    val anchor = referents.anchor
                    val access = documents
                    val agrees = anchor != null && (referent.kind == null || referent.kind == anchor.kind)
                    onNote("referent: kind=ANCHOR present= kindAgrees=")
                    if (anchor == null || !agrees || access == null) return AiInteractionResult.NeedsInformation(missingTarget(proposal), proposal.intent)
                    val current = when (val read = access.get(anchor)) {
                        is DocumentReadResult.Found -> read.content.summary
                        DocumentReadResult.NotFound -> return AiInteractionResult.NotFound(ProposalField.TARGET_REF)
                    }
                    resolveContext = AiResultContext.of(listOf(current))
                    proposal = proposal.copy(targetRef = AiResultRef(1), missingFields = proposal.missingFields - setOf(ProposalField.TARGET_REF, ProposalField.TARGET_NAME))
                }
                null -> Unit
            }
        }
        if ((proposal.intent == AiIntent.OPEN || proposal.intent == AiIntent.APPEND) && proposal.targetRef == null && proposal.targetName == null) {
            val candidate = TargetCandidateExtractor.extract(userText, proposal)
            if (candidate != null) {
                proposal = TargetCandidateExtractor.assist(userText, proposal)
                val candidateLength = candidate.text.length
                // redacted on purpose (docs/AI_TARGET_RESOLUTION.md §4): never the candidate text, the title or the input
                onNote("target assist: assistApplied=true source=${candidate.source} candidateLength=$candidateLength intent=${proposal.intent}")
            } else {
                onNote("target assist: assistApplied=false intent=${proposal.intent}")
            }
        }
        return when (val decision = ExecutionPolicy.decide(resolver.resolve(proposal, resolveContext, destination))) {
            is ExecutionDecision.Direct -> when (val executed = executor.execute(decision)) {
                is ExecutionResult.Searched -> AiInteractionResult.SearchResults((decision.command as ResolvedCommand.Search).query, executed.results)
                is ExecutionResult.Opened -> AiInteractionResult.Open((decision.command as ResolvedCommand.Open).target)
                else -> AiInteractionResult.RuntimeError(AiFailureStage.GENERATION, "unexpected direct outcome: $executed")
            }
            is ExecutionDecision.RequiresConfirmation -> AiInteractionResult.WritePreview(decision.preview, PendingWrite(decision))
            is ExecutionDecision.Blocked.Rejected ->
                if (decision.reasons == listOf(AiRejection.UNKNOWN_INTENT)) AiInteractionResult.Unknown else AiInteractionResult.Invalid(decision.reasons)
            is ExecutionDecision.Blocked.NeedsInformation -> AiInteractionResult.NeedsInformation(decision.fields, intentForQuestions)
            is ExecutionDecision.Blocked.Ambiguous -> AiInteractionResult.Ambiguous(decision.candidates)
            is ExecutionDecision.Blocked.NotFound -> {
                // redacted diagnostics (never the name): its shape, so a model habit can be told from a real miss
                if (decision.field == ProposalField.TARGET_NAME) proposal.targetName?.let { name ->
                    val nameLength = name.length
                    val hasBrackets = name.any { it == '「' || it == '『' || it == '」' || it == '』' }
                    val startsWithKindWord = name.startsWith("メモ") || name.startsWith("日記") || name.startsWith("アウトライン")
                    val endsWithParticle = name.endsWith("に") || name.endsWith("を") || name.endsWith("へ")
                    onNote("target name not found: nameLength=$nameLength hasBrackets=$hasBrackets startsWithKindWord=$startsWithKindWord endsWithParticle=$endsWithParticle anchorPresent=${referents.anchor != null}")
                }
                AiInteractionResult.NotFound(decision.field)
            }
        }
    }

    /** The fields a question about the target names: what the model admitted was missing, else the name. */
    private fun missingTarget(proposal: IntentProposal): Set<ProposalField> =
        proposal.missingFields.filter { it == ProposalField.TARGET_NAME || it == ProposalField.TARGET_REF }.toSet().ifEmpty { setOf(ProposalField.TARGET_NAME) }

    override suspend fun runTemplate(template: MemoTemplate, values: Map<String, String>, destination: CreateDestination?): AiInteractionResult =
        TemplateRunner.run(template, values, resolver.time.currentLocalDate(), resolver, executor, onNote = {}, destination = destination)

    override suspend fun previewMemo(body: String, destination: CreateDestination?): AiInteractionResult {
        // a memo is selected (「メモを選択」): the conversation goes to its end, not into a new memo
        destination?.selectedMemo?.let { selected -> return TemplateRunner.previewOf(resolver.appendTo(selected, body), AiIntent.APPEND) }
        val command = ResolvedCommand.Create(DocumentKind.MEMO, DocumentCreate.Memo(destination?.folderId), body, destination?.name)
        return when (val decision = ExecutionPolicy.decide(ResolutionResult.Resolved(command))) {
            is ExecutionDecision.RequiresConfirmation -> AiInteractionResult.WritePreview(decision.preview, PendingWrite(decision))
            else -> AiInteractionResult.RuntimeError(AiFailureStage.GENERATION, "unexpected decision for a memo export")
        }
    }

    override suspend fun execute(pending: PendingWrite): WriteOutcome {
        val decision = pending.take() ?: return WriteOutcome.AlreadyExecuted
        return try {
            when (val r = executor.execute(decision.confirm())) {
                is ExecutionResult.Written -> WriteOutcome.Success(r.ref)
                ExecutionResult.Conflict -> WriteOutcome.Conflict
                ExecutionResult.ReadOnly -> WriteOutcome.ReadOnly
                ExecutionResult.NotFound -> WriteOutcome.NotFound
                is ExecutionResult.Rejected -> WriteOutcome.Rejected(r.reason)
                is ExecutionResult.Searched, is ExecutionResult.Opened -> WriteOutcome.Failed("a read came back from a write: $r")
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            WriteOutcome.Failed("${t::class.java.simpleName}: ${t.message}")
        }
    }

    override suspend fun release() {
        // waits for an ask in flight (the interact lock), so 「AIモデルなし」 mid-answer still lets
        // the answer finish before the unload — the standing rule, now through the controller
        lock.withLock { resources.unload(UnloadReason.EXPLICIT) }
    }

    override suspend fun onMemoryPressure(pressure: MemoryPressure) = resources.onMemoryPressure(pressure)
}

/** The template runner (Template v2): the deterministic half of [LocalAiOrchestrator.runTemplate], kept beside it. */
internal object TemplateRunner {
    /** What the runner needs of the orchestrator: the boundary for the day and the target, the resolver and the executor. */
    suspend fun run(
        template: MemoTemplate,
        values: Map<String, String>,
        today: LocalDate,
        resolver: Resolver,
        executor: CommandExecutor,
        onNote: (String) -> Unit,
        destination: CreateDestination? = null,
    ): AiInteractionResult {
        val problems = TemplateValidation.problems(template)
        if (problems.isNotEmpty()) {
            onNote("template: invalid definition problems=${problems.size}")
            return AiInteractionResult.Invalid(listOf(AiRejection.UNKNOWN_INTENT))
        }
        // a Think template is a conversation first: every question is asked before its result can be saved (a skip is "")
        if (template.flow == TemplateFlow.THINK) {
            val unasked = template.fields.map { it.key }.filter { it !in values }.toSet()
            if (unasked.isNotEmpty()) return AiInteractionResult.TemplateForm(template, unasked, values)
        }
        // everything still wanted is asked for at once: the required fields and, for an APPEND that asks, the target
        val valueResult = TemplateValues.resolve(template.fields, values, today)
        val targetAsked = template.action == TemplateAction.APPEND && template.targetSpec == TemplateTargetSpec.AskAtRun && values[TemplateTargetSpec.TARGET_KEY]?.isBlank() != false
        val missing = (valueResult as? TemplateValues.Missing)?.keys.orEmpty() + (if (targetAsked) setOf(TemplateTargetSpec.TARGET_KEY) else emptySet())
        if (missing.isNotEmpty()) return AiInteractionResult.TemplateForm(template, missing, values)
        val resolved = when (valueResult) {
            is TemplateValues.Missing -> return AiInteractionResult.TemplateForm(template, valueResult.keys, values)
            is TemplateValues.Invalid -> return AiInteractionResult.TemplateForm(template, setOf(valueResult.key), values)
            is TemplateValues.Ready -> valueResult.values
        }
        fun render(text: String): String? = (TemplateRenderer.render(text, resolved) as? TemplateRendering.Rendered)?.text
        val proposal: IntentProposal
        val context: AiResultContext
        when (template.action) {
            TemplateAction.SEARCH -> {
                val spec = template.searchSpec ?: return AiInteractionResult.Invalid(listOf(AiRejection.UNBOUNDED_SEARCH))
                val query = DocumentQuery(
                    text = render(spec.query) ?: return AiInteractionResult.Invalid(listOf(AiRejection.UNKNOWN_INTENT)),
                    kinds = spec.kinds,
                    dateRange = spec.dateToken?.let { DateTokens.resolve(DateToken.valueOf(it.name), FixedDay(today)) },
                )
                if (!query.isBounded) return AiInteractionResult.Invalid(listOf(AiRejection.UNBOUNDED_SEARCH))
                onNote("template: SEARCH kinds=${spec.kinds.size} dated=${spec.dateToken != null}")
                return when (val executed = executor.execute(ExecutionDecision.Direct(ResolvedCommand.Search(query)))) {
                    is ExecutionResult.Searched -> AiInteractionResult.SearchResults(query, executed.results)
                    else -> AiInteractionResult.RuntimeError(AiFailureStage.GENERATION, "unexpected direct outcome: $executed")
                }
            }
            TemplateAction.CREATE -> {
                val body = render(template.body) ?: return AiInteractionResult.Invalid(listOf(AiRejection.UNKNOWN_INTENT))
                // a memo template while a memo is selected (「メモを選択」): its words go to the end of that memo
                destination?.selectedMemo?.let { selected ->
                    if (template.documentKind == DocumentKind.MEMO) {
                        val renderedChars = body.length
                        onNote("template: CREATE into the selected memo renderedChars=$renderedChars")
                        return previewOf(resolver.appendTo(selected, body), AiIntent.APPEND)
                    }
                }
                val request = when (template.documentKind) {
                    DocumentKind.MEMO -> DocumentCreate.Memo(destination?.folderId)
                    DocumentKind.OUTLINE -> DocumentCreate.Outline(destination?.folderId)
                    DocumentKind.JOURNAL -> DocumentCreate.Journal(today)
                }
                val renderedChars = body.length
                onNote("template: CREATE kind=${template.documentKind} renderedChars=$renderedChars")
                val command = ResolvedCommand.UseTemplate(template, body, request, destination?.name?.takeIf { template.documentKind != DocumentKind.JOURNAL })
                return when (val decision = ExecutionPolicy.decide(ResolutionResult.Resolved(command))) {
                    is ExecutionDecision.RequiresConfirmation -> AiInteractionResult.WritePreview(decision.preview, PendingWrite(decision))
                    else -> AiInteractionResult.RuntimeError(AiFailureStage.GENERATION, "unexpected decision for a create template")
                }
            }
            TemplateAction.APPEND -> {
                val text = render(template.body) ?: return AiInteractionResult.Invalid(listOf(AiRejection.UNKNOWN_INTENT))
                val name = when (val ts = template.targetSpec) {
                    is TemplateTargetSpec.Named -> ts.name
                    TemplateTargetSpec.AskAtRun -> values[TemplateTargetSpec.TARGET_KEY]?.trim().orEmpty().ifEmpty { return AiInteractionResult.TemplateForm(template, setOf(TemplateTargetSpec.TARGET_KEY), values) }
                    null -> return AiInteractionResult.Invalid(listOf(AiRejection.TARGET_NOT_FOUND))
                }
                val renderedChars = text.length
                onNote("template: APPEND askedTarget=${template.targetSpec == TemplateTargetSpec.AskAtRun} renderedChars=$renderedChars")
                proposal = IntentProposal(AiIntent.APPEND, targetName = name, text = text)
                context = AiResultContext.EMPTY
            }
        }
        // the append goes through the Resolver exactly as a model's would: exact name, then partial; one → preview, several → choice, none → not found
        return previewOf(resolver.resolve(proposal, context), proposal.intent)
    }

    /** A resolved write as the chat shows it: its preview and single-use ticket, or why it cannot be. */
    fun previewOf(resolution: ResolutionResult, intent: AiIntent): AiInteractionResult = when (val decision = ExecutionPolicy.decide(resolution)) {
        is ExecutionDecision.RequiresConfirmation -> AiInteractionResult.WritePreview(decision.preview, PendingWrite(decision))
        is ExecutionDecision.Blocked.Rejected -> AiInteractionResult.Invalid(decision.reasons)
        is ExecutionDecision.Blocked.NeedsInformation -> AiInteractionResult.NeedsInformation(decision.fields, intent)
        is ExecutionDecision.Blocked.Ambiguous -> AiInteractionResult.Ambiguous(decision.candidates)
        is ExecutionDecision.Blocked.NotFound -> AiInteractionResult.NotFound(decision.field)
        is ExecutionDecision.Direct -> AiInteractionResult.RuntimeError(AiFailureStage.GENERATION, "a write is never direct")
    }

    /** The clock the date tokens resolve against: the day the run started. */
    private class FixedDay(private val day: LocalDate) : TimeProvider {
        override fun nowMillis(): Long = day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        override fun currentLocalDate(): LocalDate = day
        override fun currentZoneId(): java.time.ZoneId = java.time.ZoneId.systemDefault()
    }
}

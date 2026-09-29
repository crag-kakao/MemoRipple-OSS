package io.github.cragcoffee.memoripple.domain.ai.runtime

import io.github.cragcoffee.memoripple.domain.ai.AiResultContext
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationPromptComposer
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationWindow
import io.github.cragcoffee.memoripple.domain.ai.IntentProposal
import io.github.cragcoffee.memoripple.domain.ai.ResolutionResult
import io.github.cragcoffee.memoripple.domain.ai.Resolver

sealed interface GenerationRefusal {
    data object ThermalBlocked : GenerationRefusal
    data object RuntimeNotReady : GenerationRefusal
    data class GenerationFailed(val reason: RuntimeFailure) : GenerationRefusal
    data class Unparsable(val failure: ParseFailure, val raw: String) : GenerationRefusal
}

sealed interface IntentGeneration {
    data class Proposed(
        val proposal: IntentProposal,
        val raw: String,
        val promptVersion: String,
        val promptTokens: Int,
        val generatedTokens: Int,
        val ttftMillis: Long,
        val totalMillis: Long,
        /** Phase 4B decomposition (docs/GENERATION_EFFICIENCY.md) — sizes and times only. */
        val promptChars: Int = 0,
        val promptBuildMillis: Long = 0,
        val tokenizeMillis: Long = 0,
        val promptEvalMillis: Long = 0,
        val reusedPrefixTokens: Int = 0,
        val evaluatedPromptTokens: Int = promptTokens,
    ) : IntentGeneration
    data class Refused(val reason: GenerationRefusal) : IntentGeneration
}

/**
 * User text (+ the shown results) → prompt → grammar-constrained generation → parsed
 * [IntentProposal]. It stops before the validator: a proposal is an *input* to the safety
 * pipeline, not a decision. The user message is exactly Phase 0's: the shown lines (label and
 * title, never an id) and the input.
 */
class StructuredIntentGenerator(
    private val runtime: LocalModelRuntime,
    private val assets: PromptAssets,
    private val thermal: ThermalGate,
) {
    suspend fun generate(
        userText: String,
        context: AiResultContext,
        window: ConversationWindow = ConversationWindow.EMPTY,
        maxTokens: Int = GenerationRequest.DEFAULT_MAX_TOKENS,
        /** Phase 4B: the generation cache identity (a [GenerationCacheKey] value) or null for a full evaluation. */
        cacheKey: String? = null,
    ): IntentGeneration {
        if (!thermal.check().allowsGeneration) return IntentGeneration.Refused(GenerationRefusal.ThermalBlocked)
        if (runtime.state() != RuntimeState.READY) return IntentGeneration.Refused(GenerationRefusal.RuntimeNotReady)
        val buildStarted = System.currentTimeMillis()
        val userMessage = ConversationPromptComposer.userMessage(userText, context, window)
        val request = GenerationRequest(
            systemPrompt = assets.intentSystemPrompt,
            userMessage = userMessage,
            grammar = assets.intentGrammar,
            // the resource controller's output budget (Phase 2): NORMAL is the standing default, ECO / LIMITED shorter
            maxTokens = maxTokens,
            cacheKey = cacheKey,
        )
        val promptBuildMillis = System.currentTimeMillis() - buildStarted
        val generated = when (val r = runtime.generate(request)) {
            is GenerationResult.Failed -> return IntentGeneration.Refused(GenerationRefusal.GenerationFailed(r.reason))
            is GenerationResult.Generated -> r
        }
        return when (val parsed = IntentProposalParser.parse(generated.text)) {
            is ProposalParseResult.Rejected -> IntentGeneration.Refused(GenerationRefusal.Unparsable(parsed.failure, generated.text))
            is ProposalParseResult.Parsed -> IntentGeneration.Proposed(
                parsed.proposal, generated.text, assets.promptVersion,
                generated.promptTokens, generated.generatedTokens, generated.ttftMillis, generated.totalMillis,
                promptChars = userMessage.length, promptBuildMillis = promptBuildMillis,
                tokenizeMillis = generated.tokenizeMillis, promptEvalMillis = generated.promptEvalMillis,
                reusedPrefixTokens = generated.reusedPrefixTokens, evaluatedPromptTokens = generated.evaluatedPromptTokens,
            )
        }
    }

    companion object {
        fun userMessage(userText: String, context: AiResultContext): String {
            val sb = StringBuilder("表示中の候補:\n")
            val lines = context.shownLines()
            if (lines.isEmpty()) sb.append("(なし)\n") else lines.forEach { sb.append(it).append('\n') }
            sb.append("入力: ").append(userText)
            return sb.toString()
        }
    }
}

sealed interface PipelineOutcome {
    data class Resolved(val proposal: IntentProposal, val raw: String, val resolution: ResolutionResult) : PipelineOutcome
    data class NotProposed(val refusal: GenerationRefusal) : PipelineOutcome
}

/**
 * Phase 2's reach: text → proposal → resolution. The resolution is handed back; whether it runs
 * is [io.github.cragcoffee.memoripple.domain.ai.ExecutionPolicy]'s and the user's business, not this class's.
 */
class LocalIntentPipeline(private val generator: StructuredIntentGenerator, private val resolver: Resolver) {
    suspend fun propose(userText: String, context: AiResultContext): PipelineOutcome =
        when (val g = generator.generate(userText, context)) {
            is IntentGeneration.Refused -> PipelineOutcome.NotProposed(g.reason)
            is IntentGeneration.Proposed -> PipelineOutcome.Resolved(g.proposal, g.raw, resolver.resolve(g.proposal, context))
        }
}

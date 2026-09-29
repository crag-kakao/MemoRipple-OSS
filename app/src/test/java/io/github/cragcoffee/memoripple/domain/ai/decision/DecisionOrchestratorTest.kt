package io.github.cragcoffee.memoripple.domain.ai.decision

import io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult
import io.github.cragcoffee.memoripple.domain.ai.AiIntent
import io.github.cragcoffee.memoripple.domain.ai.AiResultContext
import io.github.cragcoffee.memoripple.domain.ai.CommandExecutor
import io.github.cragcoffee.memoripple.domain.ai.FakeAiRuntime
import io.github.cragcoffee.memoripple.domain.ai.FakeDocumentAccess
import io.github.cragcoffee.memoripple.domain.ai.FakeTemplates
import io.github.cragcoffee.memoripple.domain.ai.FixedTime
import io.github.cragcoffee.memoripple.domain.ai.IntentProposal
import io.github.cragcoffee.memoripple.domain.ai.LocalAiOrchestrator
import io.github.cragcoffee.memoripple.domain.ai.ModelSelection
import io.github.cragcoffee.memoripple.domain.ai.OrchestratorPromptAssets
import io.github.cragcoffee.memoripple.domain.ai.ProposalField
import io.github.cragcoffee.memoripple.domain.ai.Resolver
import io.github.cragcoffee.memoripple.domain.ai.TEST_MODEL
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationReferents
import io.github.cragcoffee.memoripple.domain.ai.resource.DefaultAiResourceController
import io.github.cragcoffee.memoripple.domain.ai.resource.IdleUnloadPolicy
import io.github.cragcoffee.memoripple.domain.ai.runtime.StatusThermalGate
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decide door on the orchestrator (Phase 3, docs/DECISION_ENGINE.md): between the Fast
 * Path's null and interact(), before the resource controller — a CERTAIN decision or a
 * clarification acquires nothing and loads nothing; a decided proposal walks the same settle()
 * as everything else, so the Resolver stays the authority and a stale anchor is NotFound with
 * no silent retry; an AMBIGUOUS operation is refused, never executed.
 */
class DecisionOrchestratorTest {
    private val time = FixedTime()
    private val docs = FakeDocumentAccess(time)
    private val runtime = FakeAiRuntime()

    private fun TestScope.build(engine: DecisionEngine = DeterministicDecisionEngine): LocalAiOrchestrator {
        val controller = DefaultAiResourceController(
            runtime = { runtime }, scope = backgroundScope, idle = IdleUnloadPolicy(timeoutMillis = 120_000),
            thermal = StatusThermalGate { 0 },
        )
        return LocalAiOrchestrator(
            runtime = { runtime },
            selection = object : ModelSelection { override suspend fun selected() = TEST_MODEL },
            thermal = StatusThermalGate { 0 },
            assets = OrchestratorPromptAssets,
            resolver = Resolver(docs, FakeTemplates(emptyList()), time),
            executor = CommandExecutor(docs),
            resources = controller,
            documents = docs,
            decision = engine,
        )
    }

    private fun shownThree(): AiResultContext {
        val a = docs.memo(1, "散歩の記録"); val b = docs.memo(2, "買い物の記録"); val c = docs.memo(3, "会議の記録")
        return AiResultContext.of(listOf(docs.summary(a), docs.summary(b), docs.summary(c)))
    }

    @Test
    fun anOrdinalOpenSettlesAgainstTheShownResultsWithZeroLoads() = runTest {
        val o = build()
        val context = shownThree()
        val outcome = o.interactDecide("2番目を開いて", context, ConversationReferents.NONE, null)
        assertTrue("$outcome", outcome is DecisionOutcome.Settled)
        val result = (outcome as DecisionOutcome.Settled).result
        assertTrue("$result", result is AiInteractionResult.Open)
        assertEquals("買い物の記録", (result as AiInteractionResult.Open).target.title)
        assertEquals("no runtime, no load", 0, runtime.loads)
        assertEquals(0, runtime.requests.size)
    }

    @Test
    fun aDemonstrativeAppendWithABodySettlesToAPreviewOnTheAnchor() = runTest {
        val o = build()
        val anchor = docs.memo(9, "MemoRipple開発", body = "## 進捗")
        val outcome = o.interactDecide("それに『Folder対応完了』を追記して", AiResultContext.EMPTY, ConversationReferents(anchor = anchor), null)
        val result = (outcome as DecisionOutcome.Settled).result
        assertTrue("$result", result is AiInteractionResult.WritePreview)
        assertEquals("a preview writes nothing", 0, docs.writes.size)
        assertEquals(0, runtime.loads)
    }

    @Test
    fun aBodyClarificationCarriesTheFixedQuestionAndAcquiresNothing() = runTest {
        val o = build()
        val anchor = docs.memo(9, "MemoRipple開発")
        val outcome = o.interactDecide("それに追記して", AiResultContext.EMPTY, ConversationReferents(anchor = anchor), null)
        assertTrue("$outcome", outcome is DecisionOutcome.Clarify)
        val clarify = outcome as DecisionOutcome.Clarify
        assertEquals(ClarificationSlot.BODY, clarify.slot)
        assertEquals("何を追記しますか？", clarify.question)
        assertEquals(0, runtime.loads)
    }

    @Test
    fun completeDecisionFillsTheBodyAndStopsAtThePreview() = runTest {
        val o = build()
        val anchor = docs.memo(9, "MemoRipple開発")
        val referents = ConversationReferents(anchor = anchor)
        val clarify = o.interactDecide("それに追記して", AiResultContext.EMPTY, referents, null) as DecisionOutcome.Clarify
        val result = o.completeDecision(clarify.draft.copy(text = "Folder対応完了"), clarify.anchorTarget, AiResultContext.EMPTY, referents, null)
        assertTrue("$result", result is AiInteractionResult.WritePreview)
        assertEquals("still zero loads through the whole clarification", 0, runtime.loads)
        assertEquals(0, runtime.requests.size)
        assertEquals(0, docs.writes.size)
    }

    @Test
    fun aStaleAnchorIsNotFoundAndNothingRetries() = runTest {
        val o = build()
        val anchor = docs.memo(9, "消えるメモ")
        docs.docs.remove(anchor)
        val outcome = o.interactDecide("それを開いて", AiResultContext.EMPTY, ConversationReferents(anchor = anchor), null)
        val result = (outcome as DecisionOutcome.Settled).result
        assertTrue("$result", result is AiInteractionResult.NotFound)
        assertEquals(ProposalField.TARGET_REF, (result as AiInteractionResult.NotFound).field)
        assertEquals(0, runtime.loads)
        assertEquals("no silent second candidate", 0, docs.searches)
    }

    @Test
    fun notApplicableIsNullAndNothingWasTouched() = runTest {
        val o = build()
        assertNull(o.interactDecide("今日は疲れた", AiResultContext.EMPTY, ConversationReferents.NONE, null))
        assertEquals(0, runtime.loads)
        assertEquals(0, docs.searches)
    }

    @Test
    fun aDatedKindOpenGoesThroughTheResolversDayRuleNotAGuess() = runTest {
        val o = build()   // no memo exists yesterday
        val outcome = o.interactDecide("昨日のメモを開いて", AiResultContext.EMPTY, ConversationReferents.NONE, null)
        val result = (outcome as DecisionOutcome.Settled).result
        assertTrue("$result", result is AiInteractionResult.NotFound)
        assertEquals(0, runtime.loads)
    }

    @Test
    fun anAmbiguousOperationFromAnEngineIsRefusedNeverSettled() = runTest {
        val ambiguous = object : DecisionEngine {
            override fun decide(input: DecisionInput) =
                DecisionResult.Operation(IntentProposal(AiIntent.OPEN, targetName = "散歩の記録"), DecisionCertainty.AMBIGUOUS)
        }
        val o = build(engine = ambiguous)
        docs.memo(1, "散歩の記録")
        assertNull("an AMBIGUOUS operation never flows into execution", o.interactDecide("散歩の記録", AiResultContext.EMPTY, ConversationReferents.NONE, null))
        assertEquals(0, docs.searches)
    }
}

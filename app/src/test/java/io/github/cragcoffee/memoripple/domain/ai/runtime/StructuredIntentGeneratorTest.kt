package io.github.cragcoffee.memoripple.domain.ai.runtime

import io.github.cragcoffee.memoripple.domain.ai.AiIntent
import io.github.cragcoffee.memoripple.domain.ai.AiResultContext
import io.github.cragcoffee.memoripple.domain.ai.DateToken
import io.github.cragcoffee.memoripple.domain.ai.FakeDocumentAccess
import io.github.cragcoffee.memoripple.domain.ai.FakeTemplates
import io.github.cragcoffee.memoripple.domain.ai.FixedTime
import io.github.cragcoffee.memoripple.domain.ai.ResolutionResult
import io.github.cragcoffee.memoripple.domain.ai.ResolvedCommand
import io.github.cragcoffee.memoripple.domain.ai.Resolver
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scripted runtime: answers the next canned text, records every request, never touches a model. */
class ScriptedRuntime(private val answers: ArrayDeque<String>) : LocalModelRuntime {
    var state = RuntimeState.READY
    val requests = ArrayList<GenerationRequest>()
    override fun state(): RuntimeState = state
    override suspend fun load(model: ModelDescriptor): LoadResult { state = RuntimeState.READY; return LoadResult.Loaded(model, 1) }
    override suspend fun unload() { state = RuntimeState.UNLOADED }
    override suspend fun generate(request: GenerationRequest): GenerationResult {
        requests += request
        if (state != RuntimeState.READY) return GenerationResult.Failed(RuntimeFailure.INVALID_STATE)
        val text = answers.removeFirstOrNull() ?: return GenerationResult.Failed(RuntimeFailure.ENGINE_ERROR)
        return GenerationResult.Generated(text, promptTokens = 400, generatedTokens = 60, ttftMillis = 10, totalMillis = 20, stop = StopReason.END)
    }
}

object TestPromptAssets : PromptAssets {
    override val promptVersion = "v1"
    override val intentSystemPrompt = "あなたはメモアプリ「MemoRipple」のコマンド解釈器です。(test copy)"
    override val intentGrammar = "root ::= \"{\" \"}\""
}

/** RED 21–27: prompt composition, the parser behind the runtime, and the hand-off to the Resolver — and nothing past it. */
class StructuredIntentGeneratorTest {
    private val gate = StatusThermalGate { 0 }

    @Test
    fun theUserMessageCarriesLabelsAndTitlesOnlyNeverADocumentRefOrAnId() {
        val ctx = AiResultContext.of(listOf(DocumentSummary(DocumentRef(DocumentKind.MEMO, 4711), "会議のメモ", 1, 1), DocumentSummary(DocumentRef(DocumentKind.JOURNAL, 88), "9/17 の日記", 2, 2)))
        val runtime = ScriptedRuntime(ArrayDeque(listOf("""{"intent":"OPEN","targetRef":"result_1","missingFields":[]}""")))
        val gen = StructuredIntentGenerator(runtime, TestPromptAssets, gate)
        runBlocking { gen.generate("1番目を開いて", ctx) }
        val req = runtime.requests.single()
        assertEquals(TestPromptAssets.intentSystemPrompt, req.systemPrompt)
        assertEquals("表示中の候補:\nresult_1: 会議のメモ\nresult_2: 9/17 の日記\n入力: 1番目を開いて", req.userMessage)
        assertFalse(req.userMessage.contains("4711"))
        assertFalse(req.userMessage.contains("88:"))
        assertFalse(req.userMessage.contains("DocumentRef"))
        assertFalse(req.userMessage.contains("MEMO"))
        assertEquals(TestPromptAssets.intentGrammar, req.grammar)
        assertEquals(256, req.maxTokens)
        val empty = ScriptedRuntime(ArrayDeque(listOf("""{"intent":"UNKNOWN","missingFields":[]}""")))
        runBlocking { StructuredIntentGenerator(empty, TestPromptAssets, gate).generate("こんにちは", AiResultContext.EMPTY) }
        assertEquals("表示中の候補:\n(なし)\n入力: こんにちは", empty.requests.single().userMessage)
    }

    @Test
    fun theProposalComesFromTheParserAndOnlyResult1MapsToTheFirstShownDocument() {
        val ctx = AiResultContext.of(listOf(DocumentSummary(DocumentRef(DocumentKind.MEMO, 4711), "会議のメモ", 1, 1)))
        val runtime = ScriptedRuntime(ArrayDeque(listOf("""{"intent":"OPEN","query":null,"targetRef":"result_1","targetName":null,"documentKind":null,"text":null,"templateId":null,"dateToken":null,"missingFields":[]}""")))
        val out = runBlocking { StructuredIntentGenerator(runtime, TestPromptAssets, gate).generate("1番目を開いて", ctx) }
        val proposed = out as IntentGeneration.Proposed
        assertEquals(AiIntent.OPEN, proposed.proposal.intent)
        assertEquals(DocumentRef(DocumentKind.MEMO, 4711), ctx.ref(proposed.proposal.targetRef!!))
        assertEquals("v1", proposed.promptVersion)
    }

    @Test
    fun unparsableOutputIsRefusedNotCrashedAndAThermallyBlockedDeviceNeverGenerates() {
        val bad = ScriptedRuntime(ArrayDeque(listOf("""{"intent":"SEARCH","query":""")))
        val r1 = runBlocking { StructuredIntentGenerator(bad, TestPromptAssets, gate).generate("x", AiResultContext.EMPTY) }
        assertTrue("$r1", r1 is IntentGeneration.Refused && (r1 as IntentGeneration.Refused).reason is GenerationRefusal.Unparsable)
        val hot = ScriptedRuntime(ArrayDeque(listOf("""{"intent":"SEARCH","query":"x","missingFields":[]}""")))
        val r2 = runBlocking { StructuredIntentGenerator(hot, TestPromptAssets, StatusThermalGate { 3 }).generate("x", AiResultContext.EMPTY) }
        assertEquals(GenerationRefusal.ThermalBlocked, (r2 as IntentGeneration.Refused).reason)
        assertTrue("nothing was asked of the runtime", hot.requests.isEmpty())
        val cold = ScriptedRuntime(ArrayDeque(listOf("""{"intent":"SEARCH","query":"x","missingFields":[]}""")))
        cold.state = RuntimeState.UNLOADED
        val r3 = runBlocking { StructuredIntentGenerator(cold, TestPromptAssets, gate).generate("x", AiResultContext.EMPTY) }
        assertEquals(GenerationRefusal.RuntimeNotReady, (r3 as IntentGeneration.Refused).reason)
    }

    @Test
    fun theGeneratorHandsOffToTheResolverAndNothingPastIt() {
        val time = FixedTime()
        val docs = FakeDocumentAccess(time)
        docs.journal(1, time.today.minusDays(1), "昨日の出来事")
        docs.memo(2, "MemoRipple開発", "Phase 1")
        val resolver = Resolver(docs, FakeTemplates(emptyList()), time)
        val runtime = ScriptedRuntime(ArrayDeque(listOf(
            """{"intent":"SEARCH","query":null,"targetRef":null,"targetName":null,"documentKind":"JOURNAL","text":null,"templateId":null,"dateToken":"YESTERDAY","missingFields":[]}""",
            """{"intent":"UNKNOWN","query":"このメモを削除して","targetRef":null,"targetName":null,"documentKind":null,"text":null,"templateId":null,"dateToken":null,"missingFields":[]}""",
            """{"intent":"APPEND","query":null,"targetRef":null,"targetName":"MemoRipple開発","documentKind":null,"text":"Folder対応完了","templateId":null,"dateToken":null,"missingFields":[]}""",
        )))
        val pipeline = LocalIntentPipeline(StructuredIntentGenerator(runtime, TestPromptAssets, gate), resolver)

        // 26: a valid SEARCH reaches a resolved query with the token resolved by the clock
        val search = runBlocking { pipeline.propose("昨日の日記を探して", AiResultContext.EMPTY) } as PipelineOutcome.Resolved
        val q = ((search.resolution as ResolutionResult.Resolved).command as ResolvedCommand.Search).query
        assertEquals(setOf(DocumentKind.JOURNAL), q.kinds)
        assertEquals(DateTokensForTest.yesterday(time), q.dateRange)
        assertEquals(AiIntent.SEARCH, search.proposal.intent)
        assertEquals(DateToken.YESTERDAY, search.proposal.dateToken)

        // 25: an invalid proposal (UNKNOWN) is blocked and the resolver never searched
        val searchesBefore = docs.searches
        val unknown = runBlocking { pipeline.propose("このメモを削除して", AiResultContext.EMPTY) } as PipelineOutcome.Resolved
        assertTrue("${unknown.resolution}", unknown.resolution is ResolutionResult.Blocked)
        assertEquals(searchesBefore, docs.searches)

        // 27: a write proposal resolves to a command and nothing executes
        val append = runBlocking { pipeline.propose("MemoRipple開発に『Folder対応完了』を追記して", AiResultContext.EMPTY) } as PipelineOutcome.Resolved
        assertTrue("${append.resolution}", append.resolution is ResolutionResult.Resolved && (append.resolution as ResolutionResult.Resolved).command is ResolvedCommand.Append)
        assertTrue("no write happened", docs.writes.isEmpty())
        assertEquals(setOf("Resolved", "NotProposed"), PipelineOutcome::class.java.declaredClasses.map { it.simpleName }.toSet())
    }
}

object DateTokensForTest {
    fun yesterday(time: FixedTime) = io.github.cragcoffee.memoripple.domain.ai.DateTokens.resolve(DateToken.YESTERDAY, time)
}

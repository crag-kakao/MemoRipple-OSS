package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationRequest
import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationResult
import io.github.cragcoffee.memoripple.domain.ai.runtime.LoadResult
import io.github.cragcoffee.memoripple.domain.ai.runtime.LocalModelRuntime
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelCapability
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelDescriptor
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelLocation
import io.github.cragcoffee.memoripple.domain.ai.runtime.PromptAssets
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeFailure
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
import io.github.cragcoffee.memoripple.domain.ai.runtime.StatusThermalGate
import io.github.cragcoffee.memoripple.domain.ai.runtime.StopReason
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSearchScope
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** A runtime under test control: canned answers, a forced load outcome, counted loads and unloads. */
class FakeAiRuntime : LocalModelRuntime {
    var state = RuntimeState.UNLOADED
    val answers = ArrayDeque<String>()
    var loadFailure: RuntimeFailure? = null
    var throwOnGenerate: Throwable? = null
    var generateGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var loads = 0
    var unloads = 0
    val requests = ArrayList<GenerationRequest>()

    override fun state(): RuntimeState = state

    override suspend fun load(model: ModelDescriptor): LoadResult {
        loads++
        loadFailure?.let { state = RuntimeState.UNLOADED; return LoadResult.Failed(it) }
        state = RuntimeState.READY
        return LoadResult.Loaded(model, 1)
    }

    override suspend fun generate(request: GenerationRequest): GenerationResult {
        requests += request
        generateGate?.await()
        throwOnGenerate?.let { throw it }
        if (state != RuntimeState.READY) return GenerationResult.Failed(RuntimeFailure.INVALID_STATE)
        val text = answers.removeFirstOrNull() ?: return GenerationResult.Failed(RuntimeFailure.ENGINE_ERROR)
        return GenerationResult.Generated(text, 300, 40, 5, 10, StopReason.END)
    }

    override suspend fun unload() { if (state != RuntimeState.UNLOADED) unloads++; state = RuntimeState.UNLOADED }
}

object OrchestratorPromptAssets : PromptAssets {
    override val promptVersion = "v1"
    override val intentSystemPrompt = "system (test)"
    override val intentGrammar = "root ::= \"{\" \"}\""
}

val TEST_MODEL = ModelDescriptor(
    id = "test-model", displayName = "Test model", location = ModelLocation.AppFile("test.gguf"),
    architecture = "test", contextSize = 4096, quantization = "Q4_K_M", capabilities = setOf(ModelCapability.STRUCTURED_OUTPUT),
)

fun proposalJson(
    intent: String,
    query: String? = null,
    targetRef: String? = null,
    targetName: String? = null,
    documentKind: String? = null,
    text: String? = null,
    templateId: String? = null,
    dateToken: String? = null,
    extra: String = "",
): String {
    fun s(v: String?) = if (v == null) "null" else "\"" + v.replace("\"", "\\\"") + "\""
    return "{\"intent\":${s(intent)},\"query\":${s(query)},\"targetRef\":${s(targetRef)},\"targetName\":${s(targetName)}," +
        "\"documentKind\":${s(documentKind)},\"text\":${s(text)},\"templateId\":${s(templateId)},\"dateToken\":${s(dateToken)},\"missingFields\":[]$extra}"
}

/**
 * Phase 3 RED (docs/AI_CHAT_PREVIEW.md): the orchestrator is the one door from a screen to the
 * AI path. Text → runtime availability → thermal → load → generator → validator → resolver →
 * policy → a result a screen can show. SEARCH and a unique OPEN run; every write stops at its
 * preview; every refusal is a distinct, safe result; nothing is ever written.
 */
class AiOrchestratorTest {
    private val time = FixedTime()
    private val docs = FakeDocumentAccess(time)
    private val runtime = FakeAiRuntime()
    private var thermalStatus = 0
    private var selected: ModelDescriptor? = TEST_MODEL
    private var runtimeCreated = 0
    private val templates = FakeTemplates(listOf(MemoTemplate("t1", "週次レビュー", "## 今週\n- \n## 来週\n- ")))

    private fun orchestrator() = LocalAiOrchestrator(
        runtime = { runtimeCreated++; runtime },
        selection = object : ModelSelection { override suspend fun selected() = selected },
        thermal = StatusThermalGate { thermalStatus },
        assets = OrchestratorPromptAssets,
        resolver = Resolver(docs, templates, time),
        executor = CommandExecutor(docs),
    )

    private fun ask(text: String, context: AiResultContext = AiResultContext.EMPTY, progress: MutableList<AiProgress> = ArrayList()) =
        runBlocking { orchestrator().interact(text, context, onProgress = { progress += it }) }

    // --- availability and safety before any model runs (RED 4–9, 43) ---

    @Test
    fun theRuntimeIsNotEvenCreatedUntilTheFirstInteraction() {
        val o = orchestrator()
        assertEquals(0, runtimeCreated)
        assertEquals(RuntimeState.UNLOADED, o.runtimeState())
        runtime.answers += proposalJson("SEARCH", query = "散歩")
        runBlocking { o.interact("散歩を探して", AiResultContext.EMPTY) }
        assertEquals(1, runtimeCreated)
    }

    @Test
    fun noConfiguredModelIsAQuietUnavailableResultAndTouchesNoRuntime() {
        selected = null
        val r = ask("昨日の日記を探して")
        assertEquals(AiInteractionResult.ModelUnavailable(ModelUnavailableReason.NO_MODEL_CONFIGURED), r)
        assertEquals(0, runtime.loads)
        assertEquals(0, docs.searches)
    }

    @Test
    fun aMissingModelFileIsUnavailableNotAnError() {
        runtime.loadFailure = RuntimeFailure.MODEL_FILE_MISSING
        assertEquals(AiInteractionResult.ModelUnavailable(ModelUnavailableReason.MODEL_FILE_MISSING), ask("昨日の日記を探して"))
        assertEquals(RuntimeState.UNLOADED, runtime.state)
    }

    @Test
    fun anUnsupportedCpuIsUnavailableAndNothingIsGenerated() {
        runtime.loadFailure = RuntimeFailure.UNSUPPORTED_DEVICE
        assertEquals(AiInteractionResult.ModelUnavailable(ModelUnavailableReason.UNSUPPORTED_DEVICE), ask("昨日の日記を探して"))
        assertTrue(runtime.requests.isEmpty())
    }

    @Test
    fun aSevereThermalStatusBlocksBeforeTheModelIsEvenLoaded() {
        thermalStatus = 3
        assertEquals(AiInteractionResult.ThermalBlocked, ask("昨日の日記を探して"))
        assertEquals(0, runtime.loads)
        assertEquals(0, runtimeCreated)
    }

    @Test
    fun aLightThermalStatusStillAllowsOneGeneration() {
        thermalStatus = 2
        runtime.answers += proposalJson("SEARCH", query = "散歩")
        assertTrue(ask("散歩を探して") is AiInteractionResult.SearchResults)
    }

    @Test
    fun anEngineLoadFailureIsALoadErrorWithTheDetailKeptForTheLog() {
        runtime.loadFailure = RuntimeFailure.ENGINE_ERROR
        val r = ask("昨日の日記を探して") as AiInteractionResult.RuntimeError
        assertEquals(AiFailureStage.LOAD, r.stage)
        assertTrue(r.developerDetail, r.developerDetail.contains("ENGINE_ERROR"))
    }

    @Test
    fun aGenerationFailureIsAGenerationErrorNotACrash() {
        // no canned answer: the fake runtime fails the generation
        val r = ask("昨日の日記を探して") as AiInteractionResult.RuntimeError
        assertEquals(AiFailureStage.GENERATION, r.stage)
        assertEquals(RuntimeState.READY, runtime.state)
    }

    @Test
    fun aThrowingRuntimeBecomesAResultTheScreenCanShow() {
        runtime.throwOnGenerate = IllegalStateException("native boom")
        val r = ask("昨日の日記を探して") as AiInteractionResult.RuntimeError
        assertEquals(AiFailureStage.GENERATION, r.stage)
        assertTrue(r.developerDetail.contains("native boom"))
    }

    @Test
    fun malformedModelOutputIsAParseErrorAndReachesNoResolver() {
        runtime.answers += "{\"intent\": \"APPEND\", \"targetRef\": \"12345\"}"
        val r = ask("これに追記して") as AiInteractionResult.RuntimeError
        assertEquals(AiFailureStage.PARSE, r.stage)
        assertEquals(0, docs.searches)
        assertTrue(docs.writes.isEmpty())
    }

    @Test
    fun progressGoesLoadingThenGeneratingOnTheFirstAskAndGeneratingOnlyWhileLoaded() {
        val progress = ArrayList<AiProgress>()
        runtime.answers += proposalJson("SEARCH", query = "散歩")
        ask("散歩", progress = progress)
        assertEquals(listOf(AiProgress.LOADING_MODEL, AiProgress.GENERATING), progress)
        progress.clear()
        runtime.answers += proposalJson("SEARCH", query = "散歩")
        ask("散歩", progress = progress)
        assertEquals(listOf(AiProgress.GENERATING), progress)
        assertEquals(1, runtime.loads)
    }

    // --- SEARCH and OPEN run directly (RED 10, 12–14, 38) ---

    @Test
    fun aSearchProposalRunsThroughTheBoundaryAndReturnsTheResults() {
        val yesterday = time.today.minusDays(1)
        val j = docs.journal(1, yesterday, "昨日の散歩")
        docs.journal(2, time.today, "今日の散歩")
        runtime.answers += proposalJson("SEARCH", documentKind = "JOURNAL", dateToken = "YESTERDAY")
        val r = ask("昨日の日記を探して") as AiInteractionResult.SearchResults
        assertEquals(listOf(j), r.results.map { it.ref })
        assertEquals(setOf(DocumentKind.JOURNAL), r.query.kinds)
        assertEquals(1, docs.searches)
    }

    @Test
    fun aUniqueOpenResolvesToTheDocumentToOpen() {
        val m = docs.memo(1, "MemoRipple開発", "本文")
        runtime.answers += proposalJson("OPEN", targetName = "MemoRipple開発")
        val r = ask("MemoRipple開発を開いて") as AiInteractionResult.Open
        assertEquals(m, r.target.ref)
    }

    @Test
    fun anAmbiguousOpenListsTheCandidatesAndOpensNone() {
        // two documents with the same exact title: the resolver prefers an exact title match, so a partial one would not compete
        docs.memo(1, "MemoRipple開発")
        docs.outline(2, "MemoRipple開発")
        runtime.answers += proposalJson("OPEN", targetName = "MemoRipple開発")
        val r = ask("MemoRipple開発を開いて") as AiInteractionResult.Ambiguous
        assertEquals(setOf(1L, 2L), r.candidates.map { it.ref.id }.toSet())
    }

    @Test
    fun aShownRefOpensTheShownDocumentWithoutASearch() {
        val a = docs.memo(1, "A")
        val b = docs.memo(2, "B")
        val context = AiResultContext.of(listOf(docs.summary(a), docs.summary(b)))
        runtime.answers += proposalJson("OPEN", targetRef = "result_2")
        val r = ask("2番目を開いて", context) as AiInteractionResult.Open
        assertEquals(b, r.target.ref)
        assertEquals(0, docs.searches)
        assertTrue(runtime.requests.single().userMessage.contains("result_2: B"))
    }

    @Test
    fun aRefOutsideTheShownListIsInvalid() {
        runtime.answers += proposalJson("OPEN", targetRef = "result_3")
        val r = ask("3番目を開いて", AiResultContext.of(listOf(docs.summary(docs.memo(1, "A"))))) as AiInteractionResult.Invalid
        assertEquals(listOf(AiRejection.REF_NOT_IN_CONTEXT), r.reasons)
    }

    // --- refusals the screen explains (RED 15–17) ---

    @Test
    fun anAppendWithoutATargetAsksForTheTarget() {
        runtime.answers += proposalJson("APPEND", text = "買い物")
        assertEquals(AiInteractionResult.NeedsInformation(setOf(ProposalField.TARGET_NAME), AiIntent.APPEND), ask("これに追記して"))
    }

    @Test
    fun anUnknownIntentIsUnknownNotInvalid() {
        runtime.answers += proposalJson("UNKNOWN")
        assertEquals(AiInteractionResult.Unknown, ask("今日の天気は？"))
    }

    @Test
    fun aForbiddenVerbFromTheModelIsUnknownToo() {
        runtime.answers += proposalJson("DELETE", targetName = "MemoRipple開発")
        assertEquals(AiInteractionResult.Unknown, ask("MemoRipple開発を消して"))
        assertTrue(docs.writes.isEmpty())
    }

    @Test
    fun aNameNobodyHasIsNotFound() {
        runtime.answers += proposalJson("OPEN", targetName = "存在しないメモ")
        assertEquals(AiInteractionResult.NotFound(ProposalField.TARGET_NAME), ask("存在しないメモを開いて"))
    }

    // --- writes stop at the preview (RED 18–24, 36, 37, 39, 46, 47) ---

    @Test
    fun caseCAppendResolvesToAnAppendPreviewAndWritesNothing() {
        val target = docs.memo(7, "MemoRipple開発", "## 進捗\n- Chat v0 完了", updatedAt = 5_000)
        runtime.answers += proposalJson("APPEND", targetName = "MemoRipple開発", text = "Folder対応完了")
        val r = ask("MemoRipple開発に『Folder対応完了』を追記して") as AiInteractionResult.WritePreview
        val p = r.preview as CommandPreview.Append
        assertEquals(target, p.target.ref)
        assertEquals("Folder対応完了", p.text)
        assertEquals("## 進捗\n- Chat v0 完了", p.currentBody)
        assertEquals(DocumentVersion(5_000), p.expectedVersion)
        assertTrue("no write happened: ${docs.writes}", docs.writes.isEmpty())
        assertEquals("## 進捗\n- Chat v0 完了", docs.docs.getValue(target).body)
        assertEquals(5_000L, docs.docs.getValue(target).updatedAt)
    }

    @Test
    fun aCreateResolvesToACreatePreviewWithKindTextAndNoRow() {
        runtime.answers += proposalJson("CREATE", documentKind = "MEMO", text = "買い物リスト")
        val r = ask("新しいメモに買い物リストを作って") as AiInteractionResult.WritePreview
        val p = r.preview as CommandPreview.Create
        assertEquals(DocumentKind.MEMO, p.kind)
        assertEquals("買い物リスト", p.initialText)
        assertNull(p.journalDate)
        assertTrue(docs.writes.isEmpty())
        assertTrue(docs.docs.isEmpty())
    }

    @Test
    fun aJournalCreateNamesTheDayTheClockAllowsNeverAFutureOne() {
        runtime.answers += proposalJson("CREATE", documentKind = "JOURNAL", dateToken = "YESTERDAY")
        val p = (ask("昨日の日記を作って") as AiInteractionResult.WritePreview).preview as CommandPreview.Create
        assertEquals(time.today.minusDays(1), p.journalDate)
        assertTrue(p.journalDate!! <= time.today)
    }

    @Test
    fun aTemplateResolvesToATemplatePreviewWithTheBodyAsItIs() {
        runtime.answers += proposalJson("USE_TEMPLATE", templateId = "週次レビュー")
        val p = (ask("週次レビューのテンプレで作って") as AiInteractionResult.WritePreview).preview as CommandPreview.Template
        assertEquals("t1", p.template.id)
        assertEquals("## 今週\n- \n## 来週\n- ", p.renderedBody)
        assertTrue(docs.writes.isEmpty())
    }

    @Test
    fun aConfidenceInTheAnswerNeverTurnsAWriteIntoAnythingButAPreview() {
        docs.memo(1, "MemoRipple開発", "x")
        runtime.answers += proposalJson("APPEND", targetName = "MemoRipple開発", text = "Folder対応完了", extra = ",\"confidence\":1.0,\"autoConfirm\":true")
        assertTrue(ask("追記して") is AiInteractionResult.WritePreview)
        assertTrue(docs.writes.isEmpty())
    }

    @Test
    fun aLockedJournalIsRefusedBeforeAnyPreviewExists() {
        docs.journal(3, time.today.minusDays(10), "確定した日", state = DiaryState.LOCKED)
        runtime.answers += proposalJson("APPEND", targetName = "確定した日", text = "追記")
        val r = ask("確定した日に追記して") as AiInteractionResult.Invalid
        assertEquals(listOf(AiRejection.TARGET_READ_ONLY), r.reasons)
    }

    @Test
    fun thePreviewsVersionIsTheGuardAStaleOneCanNeverBeWritten() {
        val target = docs.memo(7, "MemoRipple開発", "old", updatedAt = 5_000)
        runtime.answers += proposalJson("APPEND", targetName = "MemoRipple開発", text = "追記")
        val p = (ask("追記して") as AiInteractionResult.WritePreview).preview as CommandPreview.Append
        // the document moves on after the preview
        docs.docs.getValue(target).body = "old + edited elsewhere"
        docs.docs.getValue(target).updatedAt = 6_000
        // even the domain's own confirmation path (which no screen calls in Phase 3) is stopped by the held version
        val decision = ExecutionPolicy.decide(ResolutionResult.Resolved(ResolvedCommand.Append(p.target, p.text, p.expectedVersion, p.currentBody))) as ExecutionDecision.RequiresConfirmation
        assertEquals(ExecutionResult.Conflict, runBlocking { CommandExecutor(docs).execute(decision.confirm()) })
        assertEquals("old + edited elsewhere", docs.docs.getValue(target).body)
    }

    @Test
    fun theOrchestratorKnowsNoConfirmationAndNoWriteVerb() {
        val src = java.io.File("src/main/java/io/github/cragcoffee/memoripple/domain/ai/AiOrchestrator.kt")
            .let { if (it.isFile) it else java.io.File("app/" + it.path) }.readText()
        listOf("documents.append", "documents.create", ".append(", ".create(").forEach {
            assertFalse("orchestrator mentions $it", src.contains(it))
        }
        // Phase 4: confirm() is called once, inside execute(PendingWrite) — AiConfirmedWritePolicyTest pins the place
        assertEquals(1, Regex("\\.confirm\\(").findAll(src).count())
        assertTrue(src.contains("ExecutionDecision.Direct"))
    }

    @Test
    fun theAiPathReachesTheV1ScopeOnlyFutureDiaryStaysOut() {
        assertEquals(DocumentSearchScope.V1, AiDocumentScope.kinds)
        runtime.answers += proposalJson("SEARCH", query = "未来")
        val r = ask("未来を探して") as AiInteractionResult.SearchResults
        assertTrue(r.query.kinds.all { it in DocumentSearchScope.V1 })
    }

    // --- lifecycle: no resident model (RED 51) ---

    @Test
    fun releaseUnloadsTheModelAndIsIdempotent() {
        val o = orchestrator()
        runtime.answers += proposalJson("SEARCH", query = "散歩")
        runBlocking { o.interact("散歩", AiResultContext.EMPTY) }
        assertEquals(RuntimeState.READY, o.runtimeState())
        runBlocking { o.release(); o.release() }
        assertEquals(RuntimeState.UNLOADED, o.runtimeState())
        assertEquals(1, runtime.unloads)
    }

    @Test
    fun releaseBeforeAnyInteractionCreatesNoRuntime() {
        val o = orchestrator()
        runBlocking { o.release() }
        assertEquals(0, runtimeCreated)
    }

    // The conservative idle timer moved into the resource controller (Phase 2): its unload, the
    // keep-warm window and the restart on a new ask are pinned in AiResourceControllerTest /
    // AiResourceOrchestratorTest on a virtual clock, no real sleeps.

    @Test
    fun theSecondAskIsNotStartedWhileTheFirstIsRunningItWaits() {
        val o = orchestrator()
        runtime.answers += proposalJson("SEARCH", query = "a")
        runtime.answers += proposalJson("SEARCH", query = "b")
        val results = runBlocking {
            val first = async { o.interact("a", AiResultContext.EMPTY) }
            val second = async { o.interact("b", AiResultContext.EMPTY) }
            listOf(first.await(), second.await())
        }
        assertTrue(results.all { it is AiInteractionResult.SearchResults })
        assertEquals(2, runtime.requests.size)
        assertEquals(1, runtime.loads)
    }

    @Test
    fun theResultContextIsWhatTheModelSeesAndCarriesNoIds() {
        val a = docs.memo(41, "散歩メモ")
        val context = AiResultContext.of(listOf(docs.summary(a)))
        runtime.answers += proposalJson("SEARCH", query = "散歩")
        ask("散歩", context)
        val message = runtime.requests.single().userMessage
        assertTrue(message.contains("result_1: 散歩メモ"))
        assertFalse(message.contains("41"))
        assertFalse(message.contains("MEMO"))
    }

    @Test
    fun aJournalTargetForTheCaseCShapeIsFoundByTitleLineToo() {
        val j = docs.journal(9, LocalDate.of(2026, 9, 18), "MemoRipple開発\n進捗", updatedAt = 900)
        runtime.answers += proposalJson("APPEND", targetName = "MemoRipple開発", text = "Folder対応完了")
        val p = (ask("MemoRipple開発に『Folder対応完了』を追記して") as AiInteractionResult.WritePreview).preview as CommandPreview.Append
        assertEquals(j, p.target.ref)
        assertEquals(DocumentRef(DocumentKind.JOURNAL, 9), p.target.ref)
    }
}

/**
 * Found by the S20 smoke: loading a model raises the trim level by itself, and the Phase 2 hook
 * unloaded the model between load and generate. LOW pressure must wait for an ask in flight;
 * CRITICAL still unloads.
 */
class AiOrchestratorMemoryPressureTest {
    private val time = FixedTime()
    private val docs = FakeDocumentAccess(time)
    private val runtime = FakeAiRuntime()

    private fun orchestrator() = LocalAiOrchestrator(
        runtime = { runtime },
        selection = object : ModelSelection { override suspend fun selected() = TEST_MODEL },
        thermal = StatusThermalGate { 0 },
        assets = OrchestratorPromptAssets,
        resolver = Resolver(docs, FakeTemplates(emptyList()), time),
        executor = CommandExecutor(docs),
    )

    @Test
    fun lowPressureDuringAnAskLeavesTheModelAloneAndAnIdleModelIsUnloaded() {
        val o = orchestrator()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        runtime.generateGate = gate
        runtime.answers += proposalJson("SEARCH", query = "散歩")
        val result = runBlocking {
            val ask = async { o.interact("散歩", AiResultContext.EMPTY) }
            while (runtime.requests.isEmpty()) delay(5)      // the ask is inside generate, loaded
            o.onMemoryPressure(io.github.cragcoffee.memoripple.domain.ai.runtime.MemoryPressure.LOW)
            assertEquals("no unload mid-ask", 0, runtime.unloads)
            assertEquals(RuntimeState.READY, runtime.state)
            gate.complete(Unit)
            ask.await()
        }
        assertTrue("$result", result is AiInteractionResult.SearchResults)
        runBlocking { o.onMemoryPressure(io.github.cragcoffee.memoripple.domain.ai.runtime.MemoryPressure.LOW) }
        assertEquals(1, runtime.unloads)
        assertEquals(RuntimeState.UNLOADED, runtime.state)
    }

    @Test
    fun moderatePressureNeverUnloadsAndCriticalAlwaysDoes() {
        val o = orchestrator()
        runtime.answers += proposalJson("SEARCH", query = "散歩")
        runBlocking { o.interact("散歩", AiResultContext.EMPTY) }
        runBlocking { o.onMemoryPressure(io.github.cragcoffee.memoripple.domain.ai.runtime.MemoryPressure.MODERATE) }
        assertEquals(0, runtime.unloads)
        runBlocking { o.onMemoryPressure(io.github.cragcoffee.memoripple.domain.ai.runtime.MemoryPressure.CRITICAL) }
        assertEquals(1, runtime.unloads)
    }

    @Test
    fun pressureBeforeAnyAskCreatesNoRuntime() {
        var created = 0
        val o = LocalAiOrchestrator(
            runtime = { created++; runtime }, selection = object : ModelSelection { override suspend fun selected() = TEST_MODEL },
            thermal = StatusThermalGate { 0 }, assets = OrchestratorPromptAssets,
            resolver = Resolver(docs, FakeTemplates(emptyList()), time), executor = CommandExecutor(docs),
        )
        runBlocking { o.onMemoryPressure(io.github.cragcoffee.memoripple.domain.ai.runtime.MemoryPressure.CRITICAL) }
        assertEquals(0, created)
    }
}

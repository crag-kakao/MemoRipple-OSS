package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
import io.github.cragcoffee.memoripple.domain.ai.runtime.StatusThermalGate
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.documents.DocumentAccess
import io.github.cragcoffee.memoripple.domain.documents.DocumentCreate
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentQuery
import io.github.cragcoffee.memoripple.domain.documents.DocumentReadResult
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSearchScope
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary
import io.github.cragcoffee.memoripple.domain.documents.DocumentWriteResult
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 RED (docs/AI_CONFIRMED_WRITE.md): a previewed write runs only through the ticket the
 * preview carries, once, after the user says so — Preview → Human Confirmation → ConfirmedCommand
 * → CommandExecutor → DocumentAccess. No runtime, no thermal, no model is involved in the write;
 * a stale version is a conflict and nothing is retried; a consumed ticket runs nothing again.
 */
class AiConfirmedWriteOrchestratorTest {
    private val time = FixedTime()
    private val docs = FakeDocumentAccess(time)
    private val runtime = FakeAiRuntime()
    private var thermalStatus = 0
    private val templates = FakeTemplates(listOf(MemoTemplate("t1", "週次レビュー", "## 今週\n- \n## 来週\n- ")))

    private fun orchestrator(access: DocumentAccess = docs) = LocalAiOrchestrator(
        runtime = { runtime },
        selection = object : ModelSelection { override suspend fun selected() = TEST_MODEL },
        thermal = StatusThermalGate { thermalStatus },
        assets = OrchestratorPromptAssets,
        resolver = Resolver(access, templates, time),
        executor = CommandExecutor(access),
    )

    private fun preview(o: AiOrchestrator, answer: String, text: String = "x", context: AiResultContext = AiResultContext.EMPTY): AiInteractionResult.WritePreview {
        runtime.answers += answer
        return runBlocking { o.interact(text, context) } as AiInteractionResult.WritePreview
    }

    // --- the preview alone writes nothing; the ticket is the only way (RED 4, 26–30, 45) ---

    @Test
    fun aPreviewCarriesATicketAndWritesNothingUntilItIsExecuted() {
        val o = orchestrator()
        val p = preview(o, proposalJson("CREATE", documentKind = "MEMO", text = "買い物リスト"))
        assertFalse(p.pending.isConsumed)
        assertTrue(docs.writes.isEmpty())
        assertTrue(docs.docs.isEmpty())
    }

    @Test
    fun readsAndRefusalsCarryNoTicket() {
        val o = orchestrator()
        docs.memo(1, "MemoRipple開発", "x")
        runtime.answers += proposalJson("SEARCH", query = "開発")
        assertTrue(runBlocking { o.interact("開発を探して", AiResultContext.EMPTY) } is AiInteractionResult.SearchResults)
        runtime.answers += proposalJson("OPEN", targetName = "MemoRipple開発")
        assertTrue(runBlocking { o.interact("開いて", AiResultContext.EMPTY) } is AiInteractionResult.Open)
        runtime.answers += proposalJson("UNKNOWN")
        assertEquals(AiInteractionResult.Unknown, runBlocking { o.interact("天気", AiResultContext.EMPTY) })
        runtime.answers += proposalJson("APPEND", targetName = "MemoRipple開発", text = "")
        assertTrue(runBlocking { o.interact("空を追記", AiResultContext.EMPTY) } is AiInteractionResult.Invalid)
        assertTrue(docs.writes.isEmpty())
    }

    @Test
    fun aConfidenceInTheAnswerStillYieldsATicketThatOnlyTheUserCanRun() {
        val o = orchestrator()
        val p = preview(o, proposalJson("CREATE", documentKind = "MEMO", text = "x", extra = ",\"confidence\":1.0,\"autoConfirm\":true"))
        assertTrue(docs.writes.isEmpty())
        assertFalse(p.pending.isConsumed)
    }

    // --- CREATE / APPEND / USE_TEMPLATE run once after confirmation (RED 8–10, 14–16, 49) ---

    @Test
    fun anExecutedCreateMakesOneDocumentWithItsTextAndReturnsItsRef() {
        val o = orchestrator()
        val p = preview(o, proposalJson("CREATE", documentKind = "MEMO", text = "買い物リスト"))
        val outcome = runBlocking { o.execute(p.pending) } as WriteOutcome.Success
        assertEquals(DocumentKind.MEMO, outcome.ref.kind)
        assertEquals("買い物リスト", docs.docs.getValue(outcome.ref).body)
        assertEquals(1, docs.docs.size)
        assertEquals(2, docs.writes.size) // create, then the first text as an append against the fresh version
        assertTrue(p.pending.isConsumed)
    }

    @Test
    fun anEmptyCreateFollowsTheExistingRuleOneEmptyDocumentNoAppend() {
        val o = orchestrator()
        val p = preview(o, proposalJson("CREATE", documentKind = "OUTLINE"))
        val outcome = runBlocking { o.execute(p.pending) } as WriteOutcome.Success
        assertEquals(DocumentKind.OUTLINE, outcome.ref.kind)
        assertEquals("", docs.docs.getValue(outcome.ref).body)
        assertEquals(1, docs.writes.size)
    }

    @Test
    fun anExecutedAppendWritesOnceWithThePreviewsVersionAndReturnsTheTarget() {
        val o = orchestrator()
        val target = docs.memo(7, "MemoRipple開発", "## 進捗", updatedAt = 5_000)
        val p = preview(o, proposalJson("APPEND", targetName = "MemoRipple開発", text = "Folder対応完了"))
        val outcome = runBlocking { o.execute(p.pending) } as WriteOutcome.Success
        assertEquals(target, outcome.ref)
        assertEquals(listOf("append:$target:Folder対応完了@5000"), docs.writes)
        assertEquals("## 進捗\nFolder対応完了", docs.docs.getValue(target).body)
    }

    @Test
    fun anExecutedTemplateMakesOneMemoFromTheBodyAsItIs() {
        val o = orchestrator()
        val p = preview(o, proposalJson("USE_TEMPLATE", templateId = "週次レビュー"))
        val outcome = runBlocking { o.execute(p.pending) } as WriteOutcome.Success
        assertEquals(DocumentKind.MEMO, outcome.ref.kind)
        assertEquals("## 今週\n- \n## 来週\n- ", docs.docs.getValue(outcome.ref).body)
        assertEquals(1, docs.docs.size)
    }

    @Test
    fun aTicketRunsOnceASecondExecutionIsRefusedAndWritesNothing() {
        val o = orchestrator()
        val target = docs.memo(7, "MemoRipple開発", "a", updatedAt = 5_000)
        val p = preview(o, proposalJson("APPEND", targetName = "MemoRipple開発", text = "b"))
        assertTrue(runBlocking { o.execute(p.pending) } is WriteOutcome.Success)
        assertEquals(WriteOutcome.AlreadyExecuted, runBlocking { o.execute(p.pending) })
        assertEquals(1, docs.writes.size)
        assertEquals("a\nb", docs.docs.getValue(target).body)
    }

    @Test
    fun twoConcurrentExecutionsOfOneTicketWriteOnce() {
        val o = orchestrator()
        docs.memo(7, "MemoRipple開発", "a", updatedAt = 5_000)
        val p = preview(o, proposalJson("APPEND", targetName = "MemoRipple開発", text = "b"))
        val outcomes = runBlocking {
            val a = async { o.execute(p.pending) }
            val b = async { o.execute(p.pending) }
            listOf(a.await(), b.await())
        }
        assertEquals(1, outcomes.count { it is WriteOutcome.Success })
        assertEquals(1, outcomes.count { it == WriteOutcome.AlreadyExecuted })
        assertEquals(1, docs.writes.size)
    }

    @Test
    fun aCreateTicketRunsOnceTooNoSecondDocument() {
        val o = orchestrator()
        val p = preview(o, proposalJson("CREATE", documentKind = "MEMO", text = "x"))
        runBlocking { o.execute(p.pending); o.execute(p.pending) }
        assertEquals(1, docs.docs.size)
    }

    // --- conflict, read-only, not found, rejected, failure (RED 18–25) ---

    @Test
    fun aVersionThatMovedAfterThePreviewIsAConflictNothingIsWrittenAndNothingIsRetried() {
        val o = orchestrator()
        val target = docs.memo(7, "MemoRipple開発", "old", updatedAt = 5_000)
        val p = preview(o, proposalJson("APPEND", targetName = "MemoRipple開発", text = "追記"))
        docs.docs.getValue(target).apply { body = "edited elsewhere"; updatedAt = 6_000 }
        assertEquals(WriteOutcome.Conflict, runBlocking { o.execute(p.pending) })
        assertEquals("edited elsewhere", docs.docs.getValue(target).body)
        assertEquals(6_000L, docs.docs.getValue(target).updatedAt)
        assertEquals("exactly one attempt, with the preview's version", listOf("append:$target:追記@5000"), docs.writes)
        // the ticket is spent: no silent retry with the newer version
        assertEquals(WriteOutcome.AlreadyExecuted, runBlocking { o.execute(p.pending) })
        assertEquals(1, docs.writes.size)
    }

    @Test
    fun aJournalLockedAfterThePreviewIsReadOnlyAtExecutionAndUntouched() {
        val o = orchestrator()
        val j = docs.journal(3, time.today.minusDays(2), "その日", updatedAt = 900)
        val p = preview(o, proposalJson("APPEND", targetName = "その日", text = "追記"))
        docs.docs.getValue(j).journalState = DiaryState.LOCKED
        assertEquals(WriteOutcome.ReadOnly, runBlocking { o.execute(p.pending) })
        assertEquals("その日", docs.docs.getValue(j).body)
    }

    @Test
    fun aLockedJournalNeverReachesAPreview() {
        val o = orchestrator()
        docs.journal(3, time.today.minusDays(10), "確定", state = DiaryState.LOCKED)
        runtime.answers += proposalJson("APPEND", targetName = "確定", text = "追記")
        val r = runBlocking { o.interact("確定に追記", AiResultContext.EMPTY) }
        assertTrue("$r", r is AiInteractionResult.Invalid)
        assertTrue(docs.writes.isEmpty())
    }

    @Test
    fun aTargetRemovedAfterThePreviewIsNotFoundAtExecution() {
        val o = orchestrator()
        val target = docs.memo(7, "MemoRipple開発", "a")
        val p = preview(o, proposalJson("APPEND", targetName = "MemoRipple開発", text = "b"))
        docs.docs.remove(target)
        assertEquals(WriteOutcome.NotFound, runBlocking { o.execute(p.pending) })
    }

    @Test
    fun aRejectedWriteIsReportedAsRejectedWithNothingMade() {
        // a journal whose day becomes "future" between preview and execution (the clock is the fake's)
        val movingClock = object : io.github.cragcoffee.memoripple.domain.diary.TimeProvider {
            var today = time.today
            override fun nowMillis() = time.nowMillis()
            override fun currentLocalDate() = today
            override fun currentZoneId() = time.currentZoneId()
        }
        val access = FakeDocumentAccess(movingClock)
        val o = LocalAiOrchestrator(
            runtime = { runtime }, selection = object : ModelSelection { override suspend fun selected() = TEST_MODEL },
            thermal = StatusThermalGate { 0 }, assets = OrchestratorPromptAssets,
            resolver = Resolver(access, templates, movingClock), executor = CommandExecutor(access),
        )
        val p = preview(o, proposalJson("CREATE", documentKind = "JOURNAL", dateToken = "TODAY"))
        movingClock.today = time.today.minusDays(1)   // the previewed day is now ahead of the clock
        val outcome = runBlocking { o.execute(p.pending) }
        assertTrue("$outcome", outcome is WriteOutcome.Rejected)
        assertTrue(access.docs.isEmpty())
    }

    @Test
    fun aThrowingBoundaryIsAFailedOutcomeNotACrash() {
        val throwing = object : DocumentAccess by docs {
            override suspend fun append(ref: DocumentRef, text: String, expectedUpdatedAt: Long): DocumentWriteResult = error("disk gone")
        }
        val o = orchestrator(throwing)
        docs.memo(7, "MemoRipple開発", "a")
        val p = preview(o, proposalJson("APPEND", targetName = "MemoRipple開発", text = "b"))
        val outcome = runBlocking { o.execute(p.pending) } as WriteOutcome.Failed
        assertTrue(outcome.developerDetail.contains("disk gone"))
        assertEquals("a", docs.docs.getValue(DocumentRef(DocumentKind.MEMO, 7)).body)
    }

    // --- the write needs no model: unload, thermal, no inference (RED 40, 41) ---

    @Test
    fun theModelMayBeUnloadedWhileWaitingAndTheWriteStillRunsWithoutReloadingIt() {
        val o = orchestrator()
        docs.memo(7, "MemoRipple開発", "a", updatedAt = 5_000)
        val p = preview(o, proposalJson("APPEND", targetName = "MemoRipple開発", text = "b"))
        runBlocking { o.release() }
        assertEquals(RuntimeState.UNLOADED, o.runtimeState())
        val loadsBefore = runtime.loads
        thermalStatus = 3   // SEVERE: no inference would be allowed — a write is not inference
        assertTrue(runBlocking { o.execute(p.pending) } is WriteOutcome.Success)
        assertEquals(loadsBefore, runtime.loads)
        assertEquals(RuntimeState.UNLOADED, o.runtimeState())
        assertEquals("no generation happened for the write", 1, runtime.requests.size)
    }

    // --- scope rules stand (RED 47, 48) ---

    @Test
    fun twoJournalsOnTheSameDayAreStillTwoCreates() {
        val o = orchestrator()
        val a = preview(o, proposalJson("CREATE", documentKind = "JOURNAL", dateToken = "TODAY"))
        runBlocking { o.execute(a.pending) }
        val b = preview(o, proposalJson("CREATE", documentKind = "JOURNAL", dateToken = "TODAY"))
        runBlocking { o.execute(b.pending) }
        assertEquals(2, docs.docs.values.count { it.journalDate == time.today })
    }

    @Test
    fun aFutureJournalCanNeverBePreviewedAndTheAiScopeIsV1() {
        val o = orchestrator()
        val p = preview(o, proposalJson("CREATE", documentKind = "JOURNAL", dateToken = "THIS_WEEK"))
        val c = p.preview as CommandPreview.Create
        assertTrue(c.journalDate!! <= time.today)
        assertEquals(DocumentSearchScope.V1, AiDocumentScope.kinds)
    }

    @Test
    fun theOutcomeCarriesWhatTheScreenNeedsAndNothingExecutable() {
        val o = orchestrator()
        val p = preview(o, proposalJson("CREATE", documentKind = "MEMO", text = "x"))
        val outcome = runBlocking { o.execute(p.pending) } as WriteOutcome.Success
        val content = runBlocking { docs.get(outcome.ref) } as DocumentReadResult.Found
        assertEquals("x", content.content.body)
        assertNull(WriteOutcome.Success::class.java.declaredFields.firstOrNull { it.type.simpleName.contains("Command") })
        assertTrue(runBlocking { docs.search(DocumentQuery(text = "x")) }.map(DocumentSummary::ref).contains(outcome.ref))
        assertFalse(java.io.Serializable::class.java.isAssignableFrom(PendingWrite::class.java))
    }

    @Test
    fun theTicketIsOpaqueNothingOnItCanWriteOrBeConfirmedFromOutside() {
        val methods = PendingWrite::class.java.methods.map { it.name }.toSet()
        listOf("confirm", "execute", "getDecision", "getCommand", "run").forEach { assertFalse("PendingWrite exposes $it", it in methods) }
        assertTrue(ExecutionDecision.RequiresConfirmation::class.java.getMethod("confirm").let { java.lang.reflect.Modifier.isPublic(it.modifiers) })
    }
}

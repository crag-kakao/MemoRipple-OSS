package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
import io.github.cragcoffee.memoripple.domain.ai.runtime.StatusThermalGate
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentSearchScope
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 6 RED (docs/AI_TARGET_RESOLUTION.md): with the model dropping `targetName`, the
 * orchestrator's deterministic assist yields a candidate that still goes through the Resolver's
 * search (exact title first, then partial), the preview and the Human Confirmation — never
 * straight to a document, never a guess, never a write. Case C end to end on the fake boundary.
 */
class AiTargetResolutionTest {
    private val time = FixedTime()
    private val docs = FakeDocumentAccess(time)
    private val runtime = FakeAiRuntime()
    private val notes = ArrayList<String>()

    private fun orchestrator() = LocalAiOrchestrator(
        runtime = { runtime },
        selection = object : ModelSelection { override suspend fun selected() = TEST_MODEL },
        thermal = StatusThermalGate { 0 },
        assets = OrchestratorPromptAssets,
        resolver = Resolver(docs, FakeTemplates(listOf(MemoTemplate("t1", "週次レビュー", "x"))), time),
        executor = CommandExecutor(docs),
    )

    private val caseC = "MemoRipple開発に『Folder対応完了』を追記して"
    private val caseCAnswer = proposalJson("APPEND", text = "Folder対応完了", extra = ",\"missingFields\":[\"targetName\"]").replace("\"missingFields\":[],", "")

    private fun ask(text: String, answer: String, context: AiResultContext = AiResultContext.EMPTY): AiInteractionResult {
        runtime.answers += answer
        return runBlocking { orchestrator().interact(text, context, onProgress = {}, onNote = { notes += it }) }
    }

    // --- case C (RED 1–9, 11) ---

    @Test
    fun caseCWithNoModelTargetReachesTheAppendPreviewOfTheUniqueDocumentAndWritesNothing() {
        val target = docs.memo(7, "MemoRipple開発", "## 進捗", updatedAt = 5_000)
        docs.memo(8, "別のメモ", "MemoRipple開発の話")   // a body mention: a partial hit, not the exact title
        val r = ask(caseC, caseCAnswer) as AiInteractionResult.WritePreview
        val p = r.preview as CommandPreview.Append
        assertEquals(target, p.target.ref)
        assertEquals("Folder対応完了", p.text)
        assertEquals(DocumentVersion(5_000), p.expectedVersion)
        assertTrue(docs.writes.isEmpty())
        assertEquals("## 進捗", docs.docs.getValue(target).body)
        assertTrue("the assist is logged for developers, redacted: $notes", notes.any { it.contains("assistApplied=true") && it.contains("source=BEFORE_PARTICLE_NI") && it.contains("candidateLength=12") })
        assertTrue("no user content in the note", notes.none { it.contains("MemoRipple開発") || it.contains("Folder対応完了") })
    }

    @Test
    fun caseCThenConfirmationWritesOnceAndASecondConfirmationDoesNot() {
        val target = docs.memo(7, "MemoRipple開発", "## 進捗", updatedAt = 5_000)
        val o = orchestrator()
        runtime.answers += caseCAnswer
        val r = runBlocking { o.interact(caseC, AiResultContext.EMPTY) } as AiInteractionResult.WritePreview
        assertTrue(docs.writes.isEmpty())
        assertTrue(runBlocking { o.execute(r.pending) } is WriteOutcome.Success)
        assertEquals(WriteOutcome.AlreadyExecuted, runBlocking { o.execute(r.pending) })
        assertEquals(listOf("append:$target:Folder対応完了@5000"), docs.writes)
        assertEquals("## 進捗\nFolder対応完了", docs.docs.getValue(target).body)
    }

    @Test
    fun caseCWithAStaleVersionIsAConflictNothingWrittenNothingRetried() {
        val target = docs.memo(7, "MemoRipple開発", "old", updatedAt = 5_000)
        val o = orchestrator()
        runtime.answers += caseCAnswer
        val r = runBlocking { o.interact(caseC, AiResultContext.EMPTY) } as AiInteractionResult.WritePreview
        docs.docs.getValue(target).apply { body = "edited elsewhere"; updatedAt = 6_000 }
        assertEquals(WriteOutcome.Conflict, runBlocking { o.execute(r.pending) })
        assertEquals("edited elsewhere", docs.docs.getValue(target).body)
        assertEquals(1, docs.writes.size)
    }

    @Test
    fun caseCWithNoSuchDocumentStopsSafelyAndCreatesNothing() {
        docs.memo(1, "別のメモ", "本文")
        val r = ask(caseC, caseCAnswer)
        assertEquals(AiInteractionResult.NotFound(ProposalField.TARGET_NAME), r)
        assertTrue(docs.writes.isEmpty())
        assertEquals(1, docs.docs.size)
    }

    @Test
    fun caseCWithTwoDocumentsOfThatTitleIsAmbiguousAndPicksNeither() {
        docs.memo(1, "MemoRipple開発", "a")
        docs.outline(2, "MemoRipple開発", "- b")
        val r = ask(caseC, caseCAnswer) as AiInteractionResult.Ambiguous
        assertEquals(setOf(1L, 2L), r.candidates.map { it.ref.id }.toSet())
        assertTrue(docs.writes.isEmpty())
    }

    @Test
    fun theExtractorRunsOnlyWhenTheModelGaveNoTarget() {
        docs.memo(7, "MemoRipple開発", "x")
        ask(caseC, caseCAnswer)
        assertTrue(notes.any { it.contains("assist") })
        notes.clear()
        ask(caseC, proposalJson("APPEND", targetName = "MemoRipple開発", text = "Folder対応完了"))
        assertTrue("no assist when the model named the target: $notes", notes.none { it.contains("assist") })
    }

    // --- the model's target wins; no fallback after NotFound (RED 10, 12) ---

    @Test
    fun aValidModelTargetIsUsedAsIsEvenWhenTheTextWouldSuggestAnother() {
        val named = docs.memo(1, "開発メモ", "x")
        docs.memo(2, "MemoRipple開発", "y")
        val r = ask(caseC, proposalJson("APPEND", targetName = "開発メモ", text = "Folder対応完了")) as AiInteractionResult.WritePreview
        assertEquals(named, (r.preview as CommandPreview.Append).target.ref)
    }

    @Test
    fun aModelTargetThatDoesNotExistStaysNotFoundTheExtractorDoesNotOverrideIt() {
        docs.memo(2, "MemoRipple開発", "y")
        val r = ask(caseC, proposalJson("APPEND", targetName = "存在しない名前", text = "Folder対応完了"))
        assertEquals("fixed behaviour: the model's name is honoured, no fallback to the extracted one", AiInteractionResult.NotFound(ProposalField.TARGET_NAME), r)
        assertTrue(notes.none { it.contains("assist") })
    }

    @Test
    fun aShownRefStillWinsOverEverything() {
        val a = docs.memo(1, "A", "x")
        docs.memo(2, "MemoRipple開発", "y")
        val context = AiResultContext.of(listOf(docs.summary(a)))
        val r = ask(caseC, proposalJson("APPEND", targetRef = "result_1", text = "Folder対応完了"), context) as AiInteractionResult.WritePreview
        assertEquals(a, (r.preview as CommandPreview.Append).target.ref)
    }

    // --- OPEN (RED 18–21) ---

    @Test
    fun openWithNoModelTargetResolvesUniquelyFromTheText() {
        val m = docs.memo(3, "MemoRipple開発", "x")
        val r = ask("MemoRipple開発を開いて", proposalJson("OPEN")) as AiInteractionResult.Open
        assertEquals(m, r.target.ref)
    }

    @Test
    fun openWithSeveralMatchesIsAmbiguousAndWithNoneIsNeedsInformationOrNotFound() {
        docs.memo(3, "MemoRipple開発", "x")
        docs.memo(4, "MemoRipple開発", "y")
        assertTrue(ask("MemoRipple開発を開いて", proposalJson("OPEN")) is AiInteractionResult.Ambiguous)
        docs.docs.clear()
        val none = ask("MemoRipple開発を開いて", proposalJson("OPEN"))
        assertTrue("$none", none is AiInteractionResult.NotFound || none is AiInteractionResult.NeedsInformation)
    }

    // --- false positives stay questions (RED 22–25) ---

    @Test
    fun inputsWithoutAUsableNameStayNeedsInformation() {
        docs.memo(1, "日記", "x")
        docs.journal(2, time.today, "今日の日記")
        listOf("これに追記して", "今日は疲れたので日記に追記して", "今日の日記に追記して").forEach { text ->
            val r = ask(text, proposalJson("APPEND", text = "追記"))
            assertTrue("$text → $r", r is AiInteractionResult.NeedsInformation)
        }
        listOf("前のやつを開いて", "メモを開いて").forEach { text ->
            val r = ask(text, proposalJson("OPEN"))
            assertTrue("$text → $r", r is AiInteractionResult.NeedsInformation)
        }
        assertTrue(docs.writes.isEmpty())
    }

    // --- exact before partial; partial with several is ambiguous (RED 28, 29) ---

    @Test
    fun anExactTitleIsPreferredOverPartialHitsAndSeveralPartialsAreAmbiguous() {
        val exact = docs.memo(1, "MemoRipple開発", "x")
        docs.memo(2, "MemoRipple開発 仕様", "y")
        docs.memo(3, "MemoRipple開発 メモ", "z")
        val r = ask(caseC, caseCAnswer) as AiInteractionResult.WritePreview
        assertEquals(exact, (r.preview as CommandPreview.Append).target.ref)
        docs.docs.remove(exact)
        val partial = ask(caseC, caseCAnswer)
        assertTrue("$partial", partial is AiInteractionResult.Ambiguous && (partial as AiInteractionResult.Ambiguous).candidates.size == 2)
    }

    @Test
    fun aKindFromTheModelNarrowsTheCandidateSearch() {
        docs.memo(1, "MemoRipple開発", "x")
        docs.outline(2, "MemoRipple開発", "- y")
        val r = ask(caseC, proposalJson("APPEND", documentKind = "OUTLINE", text = "Folder対応完了")) as AiInteractionResult.WritePreview
        assertEquals(DocumentKind.OUTLINE, (r.preview as CommandPreview.Append).target.ref.kind)
    }

    @Test
    fun aJournalIsFoundByItsFirstLineThroughTheExistingTitleSemantics() {
        val j = docs.journal(9, time.today.minusDays(1), "MemoRipple開発\n進捗")
        val r = ask(caseC, caseCAnswer) as AiInteractionResult.WritePreview
        assertEquals(j, (r.preview as CommandPreview.Append).target.ref)
    }

    // --- authority and scope (RED 35–41) ---

    @Test
    fun theExtractorHasNoExecutionAuthorityAndTheResolverStaysTheOnlyRouteToADocumentRef() {
        val src = File("src/main/java/io/github/cragcoffee/memoripple/domain/ai/TargetCandidateExtractor.kt").let { if (it.isFile) it else File("app/" + it.path) }.readText()
        listOf("DocumentRef", "DocumentAccess", "DocumentSearch", "CommandExecutor", "Resolver(", "resolver.", "ConfirmedCommand", ".confirm(", "suspend", "Levenshtein", "distance", "similar", "embedding", "vector", "confidence").forEach {
            assertFalse("extractor mentions $it", src.contains(it))
        }
        val orchestrator = File("src/main/java/io/github/cragcoffee/memoripple/domain/ai/AiOrchestrator.kt").let { if (it.isFile) it else File("app/" + it.path) }.readText()
        assertTrue("the assisted proposal still goes through resolver.resolve", orchestrator.contains("resolver.resolve("))
        assertEquals("one confirm() call, unchanged", 1, Regex("\\.confirm\\(").findAll(orchestrator).count())
    }

    @Test
    fun theAiScopeIsStillV1AndTheModelIsStillUnloadableAfterAnAssistedPreview() {
        docs.memo(7, "MemoRipple開発", "x")
        val o = orchestrator()
        runtime.answers += caseCAnswer
        val r = runBlocking { o.interact(caseC, AiResultContext.EMPTY) } as AiInteractionResult.WritePreview
        assertEquals(DocumentSearchScope.V1, AiDocumentScope.kinds)
        runBlocking { o.release() }
        assertEquals(RuntimeState.UNLOADED, o.runtimeState())
        assertTrue(runBlocking { o.execute(r.pending) } is WriteOutcome.Success)
        assertNull(docs.docs.values.firstOrNull { it.title == "Folder対応完了" })
    }
}

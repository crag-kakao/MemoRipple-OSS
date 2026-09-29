package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.documents.DocumentCreate
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSearchScope
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** RED 23–30 (+ case C): names resolve through DocumentSearch, refs through the context, never by guessing. */
class ResolverTest {
    private val time = FixedTime()
    private val docs = FakeDocumentAccess(time)
    private val templates = FakeTemplates(listOf(MemoTemplate("daily_journal", "日記", "## 出来事\n\n## 気持ち\n")))
    private val resolver = Resolver(docs, templates, time)

    private fun resolve(p: IntentProposal, ctx: AiResultContext = AiResultContext.EMPTY) = runBlocking { resolver.resolve(p, ctx) }

    @Test
    fun aUniqueTargetNameResolvesToItsDocument() {
        val dev = docs.memo(1, "MemoRipple開発", "Phase 0 done")
        docs.memo(2, "買い物リスト")
        val r = resolve(IntentProposal(AiIntent.OPEN, targetName = "MemoRipple開発"))
        assertTrue("$r", r is ResolutionResult.Resolved && r.command is ResolvedCommand.Open && (r.command as ResolvedCommand.Open).target.ref == dev)
    }

    @Test
    fun aTargetNameWithNoCandidateIsNotFound() {
        docs.memo(1, "MemoRipple開発")
        val r = resolve(IntentProposal(AiIntent.OPEN, targetName = "存在しないメモ"))
        assertTrue("$r", r is ResolutionResult.NotFound)
    }

    @Test
    fun aTargetNameWithSeveralCandidatesIsAmbiguousAndNoneIsPicked() {
        docs.memo(1, "MemoRipple開発 メモ")
        docs.memo(2, "MemoRipple開発 TODO")
        val r = resolve(IntentProposal(AiIntent.APPEND, targetName = "MemoRipple開発", text = "Folder対応完了"))
        assertTrue("$r", r is ResolutionResult.Ambiguous && (r as ResolutionResult.Ambiguous).candidates.size == 2)
        assertTrue(docs.writes.isEmpty())
    }

    // Case C — the Phase 0 weak spot, as the model should answer it
    @Test
    fun caseCResolvesToAnAppendWithTheCurrentVersion() {
        val dev = docs.memo(1, "MemoRipple開発", "Phase 0 done", updatedAt = 5_000)
        val r = resolve(IntentProposal(AiIntent.APPEND, targetName = "MemoRipple開発", text = "Folder対応完了"))
        val cmd = (r as ResolutionResult.Resolved).command as ResolvedCommand.Append
        assertEquals(dev, cmd.target.ref)
        assertEquals("Folder対応完了", cmd.text)
        assertEquals(DocumentVersion(5_000), cmd.expectedVersion)
        assertEquals("Phase 0 done", cmd.currentBody)
        assertTrue("resolving must not write", docs.writes.isEmpty())
    }

    // Case C as the models actually answered it
    @Test
    fun caseCWithoutTheTargetNameStopsAtNeedsInformationInsteadOfGuessing() {
        docs.memo(1, "MemoRipple開発")
        val r = resolve(IntentProposal(AiIntent.APPEND, text = "Folder対応完了", missingFields = setOf(ProposalField.TARGET_NAME)))
        assertTrue("$r", r is ResolutionResult.NeedsInformation && ProposalField.TARGET_NAME in (r as ResolutionResult.NeedsInformation).fields)
        val r2 = resolve(IntentProposal(AiIntent.APPEND, text = "Folder対応完了"))
        assertTrue("$r2", r2 is ResolutionResult.NeedsInformation)
    }

    @Test
    fun aShownRefResolvesThroughTheContextOnly() {
        val list = docs.memo(2, "買い物リスト", updatedAt = 42)
        docs.memo(1, "会議のメモ")
        val ctx = AiResultContext.of(listOf(docs.summary(DocumentRef(DocumentKind.MEMO, 1)), docs.summary(list)))
        val r = resolve(IntentProposal(AiIntent.APPEND, targetRef = AiResultRef(2), text = "あとで電話"), ctx)
        val cmd = (r as ResolutionResult.Resolved).command as ResolvedCommand.Append
        assertEquals(list, cmd.target.ref)
        assertEquals(DocumentVersion(42), cmd.expectedVersion)
        val bad = resolve(IntentProposal(AiIntent.OPEN, targetRef = AiResultRef(2)), AiResultContext.EMPTY)
        assertTrue("$bad", bad is ResolutionResult.Blocked && AiRejection.REF_NOT_IN_CONTEXT in (bad as ResolutionResult.Blocked).reasons)
    }

    @Test
    fun aKindMismatchBetweenTheProposalAndTheDocumentStops() {
        docs.memo(1, "旅行の計画")
        val r = resolve(IntentProposal(AiIntent.OPEN, targetName = "旅行の計画", documentKind = DocumentKind.OUTLINE))
        assertTrue("$r", r is ResolutionResult.Blocked && AiRejection.KIND_MISMATCH in (r as ResolutionResult.Blocked).reasons)
    }

    @Test
    fun appendingToALockedJournalIsBlockedBeforeAnyWrite() {
        val locked = docs.journal(9, LocalDate.of(2026, 9, 1), "locked day", state = DiaryState.LOCKED)
        val ctx = AiResultContext.of(listOf(docs.summary(locked)))
        val r = resolve(IntentProposal(AiIntent.APPEND, targetRef = AiResultRef(1), text = "追記"), ctx)
        assertTrue("$r", r is ResolutionResult.Blocked && AiRejection.TARGET_READ_ONLY in (r as ResolutionResult.Blocked).reasons)
        assertTrue(docs.writes.isEmpty())
    }

    @Test
    fun theAiScopeIsTheV1DocumentScopeAndAJournalIsCreatedForTodayOnly() {
        assertEquals(DocumentSearchScope.V1, AiDocumentScope.kinds)
        assertTrue(DocumentKind.entries.none { it.name.contains("FUTURE") })
        val r = resolve(IntentProposal(AiIntent.CREATE, documentKind = DocumentKind.JOURNAL, dateToken = DateToken.TODAY))
        val cmd = (r as ResolutionResult.Resolved).command as ResolvedCommand.Create
        assertEquals(DocumentCreate.Journal(time.today), cmd.request)
        // a search never widens past the scope
        val s = resolve(IntentProposal(AiIntent.SEARCH, query = "旅行"))
        assertTrue(((s as ResolutionResult.Resolved).command as ResolvedCommand.Search).query.kinds.all { it in DocumentSearchScope.V1 })
    }

    @Test
    fun searchResolvesTheDateTokenWithTheProvidersClockAndTheKind() {
        val r = resolve(IntentProposal(AiIntent.SEARCH, documentKind = DocumentKind.JOURNAL, dateToken = DateToken.YESTERDAY))
        val q = ((r as ResolutionResult.Resolved).command as ResolvedCommand.Search).query
        assertEquals(setOf(DocumentKind.JOURNAL), q.kinds)
        assertEquals(DateTokens.resolve(DateToken.YESTERDAY, time), q.dateRange)
        assertTrue(q.isBounded)
    }

    @Test
    fun aTemplateResolvesByIdOrNameAndAMissingOneIsNotFound() {
        val ok = resolve(IntentProposal(AiIntent.USE_TEMPLATE, templateId = "日記"))
        assertTrue("$ok", ok is ResolutionResult.Resolved && (ok.command as ResolvedCommand.UseTemplate).template.id == "daily_journal")
        val missing = resolve(IntentProposal(AiIntent.USE_TEMPLATE, templateId = "idea"))
        assertTrue("$missing", missing is ResolutionResult.NotFound)
    }
}

package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RED 36–38: writes reach DocumentAccess only through a ConfirmedCommand, and a stale version is a conflict. */
class CommandExecutorTest {
    private val time = FixedTime()
    private val docs = FakeDocumentAccess(time)
    private val resolver = Resolver(docs, FakeTemplates(listOf(MemoTemplate("daily_journal", "日記", "## 出来事\n"))), time)
    private val executor = CommandExecutor(docs)

    private fun decide(p: IntentProposal) = ExecutionPolicy.decide(runBlocking { resolver.resolve(p, AiResultContext.EMPTY) })

    @Test
    fun aConfirmedAppendWritesThroughDocumentAccessWithTheExpectedVersion() {
        val dev = docs.memo(1, "MemoRipple開発", "Phase 0 done", updatedAt = 5_000)
        val decision = decide(IntentProposal(AiIntent.APPEND, targetName = "MemoRipple開発", text = "Folder対応完了")) as ExecutionDecision.RequiresConfirmation
        assertTrue("no write before confirmation", docs.writes.isEmpty())
        val result = runBlocking { executor.execute(decision.confirm()) }
        assertTrue("$result", result is ExecutionResult.Written && result.ref == dev)
        assertEquals(listOf("append:$dev:Folder対応完了@5000"), docs.writes)
        assertEquals("Phase 0 done\nFolder対応完了", docs.docs.getValue(dev).body)
    }

    @Test
    fun aVersionThatChangedBetweenPreviewAndConfirmationIsAConflictAndNothingIsWritten() {
        val dev = docs.memo(1, "MemoRipple開発", "Phase 0 done", updatedAt = 5_000)
        val decision = decide(IntentProposal(AiIntent.APPEND, targetName = "MemoRipple開発", text = "Folder対応完了")) as ExecutionDecision.RequiresConfirmation
        docs.docs.getValue(dev).apply { body = "edited elsewhere"; updatedAt = 6_000 }
        val result = runBlocking { executor.execute(decision.confirm()) }
        assertTrue("$result", result is ExecutionResult.Conflict)
        assertEquals("edited elsewhere", docs.docs.getValue(dev).body)
    }

    @Test
    fun thePreviewKeepsTheVersionItWasBuiltFrom() {
        docs.memo(1, "MemoRipple開発", "v", updatedAt = 777)
        val decision = decide(IntentProposal(AiIntent.APPEND, targetName = "MemoRipple開発", text = "x")) as ExecutionDecision.RequiresConfirmation
        assertEquals(DocumentVersion(777), (decision.preview as CommandPreview.Append).expectedVersion)
        assertEquals(DocumentVersion(777), (decision.command as ResolvedCommand.Append).expectedVersion)
    }

    @Test
    fun documentAccessIsReachedOnlyAfterConfirmationAndReadsRunDirectly() {
        docs.memo(1, "会議のメモ", "議事録")
        val create = decide(IntentProposal(AiIntent.CREATE, documentKind = DocumentKind.MEMO, text = "買い物"))
        val template = decide(IntentProposal(AiIntent.USE_TEMPLATE, templateId = "daily_journal"))
        assertTrue(create is ExecutionDecision.RequiresConfirmation && template is ExecutionDecision.RequiresConfirmation)
        assertTrue("resolving and deciding never write", docs.writes.isEmpty())
        // the executor's API takes a Direct decision or a ConfirmedCommand — never a bare RequiresConfirmation
        assertTrue(CommandExecutor::class.java.methods.none { m -> m.parameterTypes.any { it == ExecutionDecision.RequiresConfirmation::class.java } })
        val opened = runBlocking { executor.execute(decide(IntentProposal(AiIntent.OPEN, targetName = "会議のメモ")) as ExecutionDecision.Direct) }
        assertEquals(ExecutionResult.Opened(DocumentRef(DocumentKind.MEMO, 1)), opened)
        val searched = runBlocking { executor.execute(decide(IntentProposal(AiIntent.SEARCH, query = "議事録")) as ExecutionDecision.Direct) }
        assertTrue("$searched", searched is ExecutionResult.Searched && searched.results.single().title == "会議のメモ")
        assertTrue(docs.writes.isEmpty())
        val written = runBlocking { executor.execute((create as ExecutionDecision.RequiresConfirmation).confirm()) }
        assertTrue("$written", written is ExecutionResult.Written && written.ref.kind == DocumentKind.MEMO)
        assertEquals(2, docs.writes.size) // create, then the initial text appended with the fresh version
        val fromTemplate = runBlocking { executor.execute((template as ExecutionDecision.RequiresConfirmation).confirm()) }
        assertTrue("$fromTemplate", fromTemplate is ExecutionResult.Written)
        assertEquals("## 出来事\n", docs.docs.getValue((fromTemplate as ExecutionResult.Written).ref).body)
    }
}

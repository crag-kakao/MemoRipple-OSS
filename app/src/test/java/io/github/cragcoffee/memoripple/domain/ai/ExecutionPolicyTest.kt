package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RED 31–35, 42: every write needs a preview; reads run directly; confidence changes nothing. */
class ExecutionPolicyTest {
    private val time = FixedTime()
    private val docs = FakeDocumentAccess(time)
    private val resolver = Resolver(docs, FakeTemplates(listOf(MemoTemplate("daily_journal", "日記", "## 出来事\n"))), time)

    private fun decide(p: IntentProposal, signal: ModelSignal? = null) =
        ExecutionPolicy.decide(runBlocking { resolver.resolve(p, AiResultContext.EMPTY) }, signal)

    @Test fun createRequiresConfirmationWithACreatePreview() {
        val d = decide(IntentProposal(AiIntent.CREATE, documentKind = DocumentKind.MEMO, text = "買い物 牛乳 卵"))
        assertTrue("$d", d is ExecutionDecision.RequiresConfirmation && d.preview is CommandPreview.Create)
        assertEquals("買い物 牛乳 卵", ((d as ExecutionDecision.RequiresConfirmation).preview as CommandPreview.Create).initialText)
    }

    @Test fun appendRequiresConfirmationWithAnAppendPreview() {
        docs.memo(1, "MemoRipple開発", "Phase 0 done", updatedAt = 5_000)
        val d = decide(IntentProposal(AiIntent.APPEND, targetName = "MemoRipple開発", text = "Folder対応完了"))
        val p = (d as ExecutionDecision.RequiresConfirmation).preview as CommandPreview.Append
        assertEquals("MemoRipple開発", p.target.title)
        assertEquals("Folder対応完了", p.text)
        assertEquals(DocumentVersion(5_000), p.expectedVersion)
    }

    @Test fun useTemplateRequiresConfirmationWithTheTemplateBodyAndNothingFilledIn() {
        val d = decide(IntentProposal(AiIntent.USE_TEMPLATE, templateId = "daily_journal"))
        val p = (d as ExecutionDecision.RequiresConfirmation).preview as CommandPreview.Template
        assertEquals("日記", p.template.name)
        assertEquals("## 出来事\n", p.renderedBody)
    }

    @Test fun searchRunsDirectly() {
        val d = decide(IntentProposal(AiIntent.SEARCH, query = "会議"))
        assertTrue("$d", d is ExecutionDecision.Direct && d.command is ResolvedCommand.Search)
    }

    @Test fun aUniquelyResolvedOpenRunsDirectly() {
        docs.memo(1, "会議のメモ")
        val d = decide(IntentProposal(AiIntent.OPEN, targetName = "会議のメモ"))
        assertTrue("$d", d is ExecutionDecision.Direct && d.command is ResolvedCommand.Open)
    }

    @Test fun unknownAndInvalidProposalsAreBlocked() {
        assertTrue(decide(IntentProposal.UNKNOWN) is ExecutionDecision.Blocked)
        assertTrue(decide(IntentProposal(AiIntent.APPEND, targetName = "x", text = " ")) is ExecutionDecision.Blocked)
        assertTrue(decide(IntentProposal(AiIntent.APPEND, text = "Folder対応完了")) is ExecutionDecision.Blocked)
    }

    @Test fun modelConfidenceCannotBypassThePreview() {
        docs.memo(1, "MemoRipple開発")
        val sure = decide(IntentProposal(AiIntent.APPEND, targetName = "MemoRipple開発", text = "x"), ModelSignal(confidence = 1.0))
        val unsure = decide(IntentProposal(AiIntent.APPEND, targetName = "MemoRipple開発", text = "x"), ModelSignal(confidence = 0.0))
        assertTrue(sure is ExecutionDecision.RequiresConfirmation)
        assertTrue(unsure is ExecutionDecision.RequiresConfirmation)
        val create = decide(IntentProposal(AiIntent.CREATE, documentKind = DocumentKind.OUTLINE), ModelSignal(confidence = 1.0))
        assertTrue(create is ExecutionDecision.RequiresConfirmation)
    }
}

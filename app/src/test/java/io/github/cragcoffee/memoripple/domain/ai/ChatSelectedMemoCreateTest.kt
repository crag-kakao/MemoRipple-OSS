package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.ai.runtime.StatusThermalGate
import io.github.cragcoffee.memoripple.domain.documents.CreateDestination
import io.github.cragcoffee.memoripple.domain.documents.DocumentCreate
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.TemplateField
import io.github.cragcoffee.memoripple.domain.memos.TemplateFieldType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「メモを選択」 and the chat's memo-making paths (human decision 2026-09-27, docs/CHAT_MEMO_CONTEXT.md §7):
 * while a memo is selected, a memo the chat would *create* — the AI's CREATE (Fast Path, the home's
 * メモ question, the model), a CREATE template (and a Think save), a conversation saved as a memo — is
 * appended to the end of the selected memo instead: the Resolver's own append (the memo re-read, its
 * version taken), the Append preview, the one confirmation, a moved memo a Conflict. The memo wins over
 * the folder. An outline or a journal is not a memo and is still made. Nothing to append → a question.
 */
class ChatSelectedMemoCreateTest {
    private val time = FixedTime()
    private val docs = FakeDocumentAccess(time)
    private val runtime = FakeAiRuntime()
    private val meeting = MemoTemplate("m", "会議メモ", "# {{meeting_name}}", fields = listOf(TemplateField("meeting_name", "会議名", TemplateFieldType.TEXT, required = true)))
    private val outline = MemoTemplate("o", "骨子", "- 骨子", documentKind = DocumentKind.OUTLINE)
    private val journal = MemoTemplate("j", "日記", "今日", documentKind = DocumentKind.JOURNAL)
    private val resolver = Resolver(docs, FakeTemplates(listOf(meeting, outline, journal)), time)
    private val selected = docs.memo(1, "買い物", "牛乳")
    private val chosen = CreateDestination(selectedMemo = selected)
    private val chosenInAFolder = CreateDestination(folderId = 7L, name = "仕事", selectedMemo = selected)

    private fun orchestrator() = LocalAiOrchestrator(
        runtime = { runtime },
        selection = object : ModelSelection { override suspend fun selected() = null },
        thermal = StatusThermalGate { 3 },
        assets = OrchestratorPromptAssets,
        resolver = resolver,
        executor = CommandExecutor(docs),
        documents = docs,
    )

    private fun resolve(kind: DocumentKind, text: String?, destination: CreateDestination?) =
        runBlocking { resolver.resolve(IntentProposal(AiIntent.CREATE, documentKind = kind, text = text), AiResultContext.EMPTY, destination = destination) }

    @Test
    fun aMemoCreateWithAMemoSelectedIsAnAppendToItsEnd() {
        listOf(chosen, chosenInAFolder).forEach { destination ->
            val resolved = resolve(DocumentKind.MEMO, "卵", destination) as ResolutionResult.Resolved
            val append = resolved.command as ResolvedCommand.Append
            assertEquals("the selected memo, not a new one (and never the folder)", selected, append.target.ref)
            assertEquals("卵", append.text)
            assertEquals("its version, taken now", DocumentVersion(docs.summary(selected).updatedAt), append.expectedVersion)
            assertTrue(ExecutionPolicy.decide(resolved).let { (it as ExecutionDecision.RequiresConfirmation).preview } is CommandPreview.Append)
        }
    }

    @Test
    fun anOutlineOrAJournalIsStillMadeAndNothingToAppendIsAQuestion() {
        assertEquals(DocumentCreate.Outline(null), ((resolve(DocumentKind.OUTLINE, "x", chosen) as ResolutionResult.Resolved).command as ResolvedCommand.Create).request)
        assertTrue(((resolve(DocumentKind.JOURNAL, "x", chosen) as ResolutionResult.Resolved).command as ResolvedCommand.Create).request is DocumentCreate.Journal)
        assertEquals(ResolutionResult.NeedsInformation(setOf(ProposalField.TEXT)), resolve(DocumentKind.MEMO, null, chosen))
        assertEquals(ResolutionResult.NeedsInformation(setOf(ProposalField.TEXT)), resolve(DocumentKind.MEMO, "  ", chosen))
        // no memo selected: exactly as before
        assertEquals(DocumentCreate.Memo(), ((resolve(DocumentKind.MEMO, "卵", null) as ResolutionResult.Resolved).command as ResolvedCommand.Create).request)
    }

    @Test
    fun aSelectedMemoThatIsGoneIsNotFoundNeverAnotherMemo() {
        val gone = CreateDestination(selectedMemo = io.github.cragcoffee.memoripple.domain.documents.DocumentRef(DocumentKind.MEMO, 99))
        assertEquals(ResolutionResult.NotFound(ProposalField.TARGET_REF), resolve(DocumentKind.MEMO, "卵", gone))
    }

    @Test
    fun aCreateTemplateAndAConversationSavedAsAMemoAreAppendedToTheSelectedMemo() = runBlocking {
        val o = orchestrator()
        val t = o.runTemplate(meeting, mapOf("meeting_name" to "定例会"), destination = chosenInAFolder) as AiInteractionResult.WritePreview
        val tp = t.preview as CommandPreview.Append
        assertEquals(selected, tp.target.ref)
        assertEquals("# 定例会", tp.text)
        val m = o.previewMemo("# 会話", destination = chosen) as AiInteractionResult.WritePreview
        assertEquals(selected, (m.preview as CommandPreview.Append).target.ref)
        // an outline or a journal template is still its own document
        assertTrue((o.runTemplate(outline, emptyMap(), destination = chosen) as AiInteractionResult.WritePreview).preview is CommandPreview.Template)
        assertTrue((o.runTemplate(journal, emptyMap(), destination = chosen) as AiInteractionResult.WritePreview).preview is CommandPreview.Template)
        // nothing is written before the confirmation; the confirmation appends and creates nothing
        assertEquals(0, docs.writes.size)
        assertTrue(o.execute(t.pending) is WriteOutcome.Success)
        assertEquals(listOf("append:$selected:# 定例会@${1_000L + 1}"), docs.writes)
    }

    @Test
    fun aSelectedMemoWrittenAfterThePreviewIsAConflictAndNothingIsAppended() = runBlocking {
        val o = orchestrator()
        val m = o.previewMemo("# 会話", destination = chosen) as AiInteractionResult.WritePreview
        docs.memo(1, "買い物", "牛乳\n別の手", updatedAt = 9_999L)
        assertEquals(WriteOutcome.Conflict, o.execute(m.pending))
        assertTrue("no create, and the append was refused by its version", docs.writes.none { it.startsWith("create:") })
    }
}

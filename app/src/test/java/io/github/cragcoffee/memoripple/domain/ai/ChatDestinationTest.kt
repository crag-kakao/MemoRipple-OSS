package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.ai.runtime.StatusThermalGate
import io.github.cragcoffee.memoripple.domain.documents.DocumentCreate
import io.github.cragcoffee.memoripple.domain.documents.CreateDestination
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.folders.FolderChoices
import io.github.cragcoffee.memoripple.domain.folders.FolderNode
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.TemplateAction
import io.github.cragcoffee.memoripple.domain.memos.TemplateField
import io.github.cragcoffee.memoripple.domain.memos.TemplateFieldType
import io.github.cragcoffee.memoripple.domain.memos.TemplateTargetSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat's folder (human decision 2026-09-22, "全て推奨"): a destination the user chooses above the
 * input — never the model — that every document the chat *creates* goes into: a CREATE of the AI,
 * a CREATE template, a Think save, a conversation saved as a memo. A journal has no folder and is
 * untouched; an APPEND / SEARCH / OPEN is untouched. The preview names the folder before the one
 * confirmation. With no choice everything is as before.
 */
class ChatDestinationTest {
    private val time = FixedTime()
    private val docs = FakeDocumentAccess(time)
    private val runtime = FakeAiRuntime()
    private val work = CreateDestination(folderId = 7L, name = "仕事")
    private val meeting = MemoTemplate("m", "会議メモ", "# {{meeting_name}}", fields = listOf(TemplateField("meeting_name", "会議名", TemplateFieldType.TEXT, required = true)))
    private val outline = MemoTemplate("o", "骨子", "- 骨子", documentKind = DocumentKind.OUTLINE)
    private val journal = MemoTemplate("j", "日記", "今日", documentKind = DocumentKind.JOURNAL)
    private val devLog = MemoTemplate("a", "開発ログへ追記", "- {{entry}}", action = TemplateAction.APPEND, targetSpec = TemplateTargetSpec.Named("MemoRipple開発"), fields = listOf(TemplateField("entry", "内容", TemplateFieldType.TEXT, required = true)))
    private val templates = FakeTemplates(listOf(meeting, outline, journal, devLog))
    private val resolver = Resolver(docs, templates, time)

    private fun orchestrator() = LocalAiOrchestrator(
        runtime = { runtime },
        selection = object : ModelSelection { override suspend fun selected() = null },
        thermal = StatusThermalGate { 3 },
        assets = OrchestratorPromptAssets,
        resolver = resolver,
        executor = CommandExecutor(docs),
        documents = docs,
    )

    @Test
    fun theResolverPutsTheChosenFolderIntoAMemoOrOutlineCreateAndNamesItInThePreview() {
        val memo = runBlocking { resolver.resolve(IntentProposal(AiIntent.CREATE, documentKind = DocumentKind.MEMO, text = "本文"), AiResultContext.EMPTY, destination = work) } as ResolutionResult.Resolved
        val create = memo.command as ResolvedCommand.Create
        assertEquals(DocumentCreate.Memo(7L), create.request)
        val preview = ExecutionPolicy.decide(memo).let { (it as ExecutionDecision.RequiresConfirmation).preview as CommandPreview.Create }
        assertEquals("仕事", preview.folderName)
        val outline = runBlocking { resolver.resolve(IntentProposal(AiIntent.CREATE, documentKind = DocumentKind.OUTLINE), AiResultContext.EMPTY, destination = work) } as ResolutionResult.Resolved
        assertEquals(DocumentCreate.Outline(7L), (outline.command as ResolvedCommand.Create).request)
        // a journal has no folder: the destination changes nothing about it
        val journal = runBlocking { resolver.resolve(IntentProposal(AiIntent.CREATE, documentKind = DocumentKind.JOURNAL, text = "x"), AiResultContext.EMPTY, destination = work) } as ResolutionResult.Resolved
        assertEquals(DocumentCreate.Journal(time.currentLocalDate()), (journal.command as ResolvedCommand.Create).request)
        assertNull((ExecutionPolicy.decide(journal) as ExecutionDecision.RequiresConfirmation).preview.let { (it as CommandPreview.Create).folderName })
        // no choice: as before
        val plain = runBlocking { resolver.resolve(IntentProposal(AiIntent.CREATE, documentKind = DocumentKind.MEMO), AiResultContext.EMPTY) } as ResolutionResult.Resolved
        assertEquals(DocumentCreate.Memo(), (plain.command as ResolvedCommand.Create).request)
        assertNull((ExecutionPolicy.decide(plain) as ExecutionDecision.RequiresConfirmation).preview.let { (it as CommandPreview.Create).folderName })
    }

    @Test
    fun aTemplateRunAndAConversationExportTakeTheDestinationAndAnAppendIgnoresIt() = runBlocking {
        val o = orchestrator()
        val t = o.runTemplate(meeting, mapOf("meeting_name" to "定例会"), destination = work) as AiInteractionResult.WritePreview
        assertEquals("仕事", (t.preview as CommandPreview.Template).folderName)
        val ol = o.runTemplate(outline, emptyMap(), destination = work) as AiInteractionResult.WritePreview
        assertEquals("仕事", (ol.preview as CommandPreview.Template).folderName)
        val j = o.runTemplate(journal, emptyMap(), destination = work) as AiInteractionResult.WritePreview
        assertNull("a journal names no folder", (j.preview as CommandPreview.Template).folderName)
        val m = o.previewMemo("# 会話", destination = work) as AiInteractionResult.WritePreview
        assertEquals("仕事", (m.preview as CommandPreview.Create).folderName)
        docs.memo(1, "MemoRipple開発", "## 進捗")
        val a = o.runTemplate(devLog, mapOf("entry" to "x"), destination = work) as AiInteractionResult.WritePreview
        assertTrue("an append has no folder to take", a.preview is CommandPreview.Append)
        // the writes carry it through: one confirm each, the document in the folder — the journal on its day, no folder
        assertEquals(0, docs.writes.size)
        o.execute(t.pending); o.execute(ol.pending); o.execute(j.pending); o.execute(m.pending)
        assertEquals(
            listOf("create:${DocumentCreate.Memo(7L)}", "create:${DocumentCreate.Outline(7L)}", "create:${DocumentCreate.Journal(time.currentLocalDate())}", "create:${DocumentCreate.Memo(7L)}"),
            docs.writes.filter { it.startsWith("create:") },
        )
    }

    @Test
    fun theChoicesAreTheWallsTreeFlattenedParentsFirstWithDepth() {
        val nodes = listOf(FolderNode(3, 1, "会議"), FolderNode(1, null, "仕事"), FolderNode(2, null, "アイデア"), FolderNode(4, 3, "定例"))
        val choices = FolderChoices.of(nodes)
        assertEquals(listOf("アイデア", "仕事", "会議", "定例"), choices.map { it.name })
        assertEquals(listOf(0, 0, 1, 2), choices.map { it.depth })
        assertEquals("a chosen id that is gone is no destination", null, FolderChoices.destination(choices, 99L))
        assertEquals(CreateDestination(3L, "会議"), FolderChoices.destination(choices, 3L))
        assertNull(FolderChoices.destination(choices, null))
    }
}

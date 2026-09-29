package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.ai.runtime.StatusThermalGate
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.TemplateAction
import io.github.cragcoffee.memoripple.domain.memos.TemplateDateToken
import io.github.cragcoffee.memoripple.domain.memos.TemplateField
import io.github.cragcoffee.memoripple.domain.memos.TemplateFieldType
import io.github.cragcoffee.memoripple.domain.memos.TemplateSearchSpec
import io.github.cragcoffee.memoripple.domain.memos.TemplateTargetSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Template v2 RED 10, 12–17, 37–39 (docs/CHAT_UI_TEMPLATE_V2.md): a template runs without any
 * model through the same door as an ask — `AiOrchestrator.runTemplate` → validation → rendering →
 * the unchanged Resolver / policy / executor → the same results: a SEARCH answers at once, a CREATE
 * or APPEND stops at its preview with the same single-use ticket the confirm button runs.
 */
class TemplateRunnerTest {
    private val time = FixedTime()
    private val docs = FakeDocumentAccess(time)
    private val runtime = FakeAiRuntime()
    private var selected: io.github.cragcoffee.memoripple.domain.ai.runtime.ModelDescriptor? = null   // no model at all

    private val meeting = MemoTemplate(
        id = "m", name = "会議メモ", body = "# {{meeting_name}}\n\n日付: {{date}}\n\n## 議題\n{{agenda}}",
        fields = listOf(
            TemplateField("meeting_name", "会議名", TemplateFieldType.TEXT, required = true),
            TemplateField("date", "日付", TemplateFieldType.DATE, default = "TODAY"),
            TemplateField("agenda", "議題", TemplateFieldType.MULTILINE, default = "（未定）"),
        ),
    )
    private val weekly = MemoTemplate(id = "s", name = "今週のMemoRipple", body = "", action = TemplateAction.SEARCH, searchSpec = TemplateSearchSpec("MemoRipple", TemplateDateToken.THIS_WEEK, setOf(DocumentKind.MEMO, DocumentKind.OUTLINE, DocumentKind.JOURNAL)))
    private val devLog = MemoTemplate(id = "a", name = "開発ログへ追記", body = "- {{entry}}", action = TemplateAction.APPEND, targetSpec = TemplateTargetSpec.Named("MemoRipple開発"), fields = listOf(TemplateField("entry", "内容", TemplateFieldType.TEXT, required = true)))
    private val templates = FakeTemplates(listOf(meeting, weekly, devLog))

    private fun orchestrator() = LocalAiOrchestrator(
        runtime = { runtime },
        selection = object : ModelSelection { override suspend fun selected() = selected },
        thermal = StatusThermalGate { 3 },   // even a hot device: a template needs no generation
        assets = OrchestratorPromptAssets,
        resolver = Resolver(docs, templates, time),
        executor = CommandExecutor(docs),
        documents = docs,
    )

    private fun run(t: MemoTemplate, values: Map<String, String>) = runBlocking { orchestrator().runTemplate(t, values) }

    @Test
    fun aCreateTemplateRendersToACreatePreviewAndOneConfirmationMakesOneDocument() {
        val r = run(meeting, mapOf("meeting_name" to "定例会"))
        val preview = (r as AiInteractionResult.WritePreview).preview as CommandPreview.Template
        assertEquals("会議メモ", preview.template.name)
        assertEquals("# 定例会\n\n日付: 2026年9月19日\n\n## 議題\n（未定）", preview.renderedBody)
        assertEquals(0, docs.writes.size)
        assertEquals(0, runtime.loads)
        val o = orchestrator()
        assertTrue(runBlocking { o.execute(r.pending) } is WriteOutcome.Success)
        assertEquals("one document (the boundary makes it empty, then fills it)", 1, docs.docs.size)
        assertEquals("the ticket runs once", WriteOutcome.AlreadyExecuted, runBlocking { o.execute(r.pending) })
        assertEquals("# 定例会\n\n日付: 2026年9月19日\n\n## 議題\n（未定）", docs.docs.values.single().body)
    }

    @Test
    fun aMissingRequiredFieldIsATemplateFormNotAGuess() {
        val r = run(meeting, emptyMap())
        val form = r as AiInteractionResult.TemplateForm
        assertEquals(meeting, form.template)
        assertEquals(setOf("meeting_name"), form.missing)
        assertEquals(0, docs.writes.size)
    }

    @Test
    fun aJournalCreateTemplateMakesTheDayTheClockAllows() {
        val journal = meeting.copy(id = "j", name = "今日の振り返り", documentKind = DocumentKind.JOURNAL, body = "## 振り返り\n{{agenda}}", fields = meeting.fields.drop(1))
        val r = run(journal, emptyMap()) as AiInteractionResult.WritePreview
        val p = r.preview as CommandPreview.Template
        assertEquals(DocumentKind.JOURNAL, p.kind)
        assertEquals(time.currentLocalDate(), p.journalDate)
        runBlocking { orchestrator().execute(r.pending) }
        assertEquals(DocumentKind.JOURNAL, docs.docs.keys.single().kind)
    }

    @Test
    fun aSearchTemplateRunsWithoutAModelAndAnswersLikeAnAiSearch() {
        val hit = docs.memo(1, "MemoRipple開発", updatedAt = time.nowMillis())
        docs.memo(2, "買い物", updatedAt = time.nowMillis())
        val r = run(weekly, emptyMap())
        assertEquals(listOf(hit), (r as AiInteractionResult.SearchResults).results.map { it.ref })
        assertEquals("MemoRipple", r.query.text)
        assertTrue(r.query.dateRange != null)
        assertEquals(0, runtime.loads)
    }

    @Test
    fun aSearchTemplateMayRenderFieldsIntoItsQuery() {
        val t = weekly.copy(id = "s2", searchSpec = TemplateSearchSpec("{{word}}", null, setOf(DocumentKind.MEMO)), fields = listOf(TemplateField("word", "言葉", TemplateFieldType.TEXT, required = true)))
        docs.memo(1, "散歩の記録"); docs.memo(2, "買い物")
        val r = run(t, mapOf("word" to "散歩")) as AiInteractionResult.SearchResults
        assertEquals(listOf("散歩の記録"), r.results.map { it.title })
    }

    @Test
    fun anAppendTemplateWithANamedTargetResolvesThroughTheResolverToAPreview() {
        val dev = docs.memo(1, "MemoRipple開発", body = "## 進捗")
        val r = run(devLog, mapOf("entry" to "会話テスト")) as AiInteractionResult.WritePreview
        val p = r.preview as CommandPreview.Append
        assertEquals(dev, p.target.ref)
        assertEquals("- 会話テスト", p.text)
        assertEquals(0, docs.writes.size)
        assertTrue(runBlocking { orchestrator().execute(r.pending) } is WriteOutcome.Success)
        assertEquals("## 進捗\n- 会話テスト", docs.docs.getValue(dev).body)
    }

    @Test
    fun anAppendTargetThatIsMissingAmbiguousOrLockedStopsSafely() {
        assertEquals(AiInteractionResult.NotFound(ProposalField.TARGET_NAME), run(devLog, mapOf("entry" to "x")))
        docs.memo(1, "MemoRipple開発"); docs.outline(2, "MemoRipple開発")
        assertTrue(run(devLog, mapOf("entry" to "x")) is AiInteractionResult.Ambiguous)
        val locked = docs.journal(3, time.currentLocalDate(), "x", state = io.github.cragcoffee.memoripple.domain.diary.DiaryState.LOCKED)
        val toLocked = devLog.copy(targetSpec = TemplateTargetSpec.Named(docs.summary(locked).title))
        assertTrue(run(toLocked, mapOf("entry" to "x")) is AiInteractionResult.Invalid)
        assertEquals(0, docs.writes.size)
    }

    @Test
    fun anAskAtRunTargetTakesTheNameFromTheValuesAndNothingElse() {
        val t = devLog.copy(targetSpec = TemplateTargetSpec.AskAtRun)
        val dev = docs.memo(1, "MemoRipple開発")
        assertTrue("no target given → the form asks", run(t, mapOf("entry" to "x")) is AiInteractionResult.TemplateForm)
        val r = run(t, mapOf("entry" to "x", TemplateTargetSpec.TARGET_KEY to "MemoRipple開発")) as AiInteractionResult.WritePreview
        assertEquals(dev, (r.preview as CommandPreview.Append).target.ref)
    }

    @Test
    fun anInvalidTemplateIsRefusedBeforeAnythingRuns() {
        val broken = meeting.copy(body = "{{nope}}")
        val r = run(broken, mapOf("meeting_name" to "x"))
        assertTrue(r is AiInteractionResult.Invalid)
        assertEquals(0, docs.searches + docs.writes.size)
    }

    // --- the model's USE_TEMPLATE goes into the same runner ---

    @Test
    fun theModelsUseTemplateOpensTheFormWhenFieldsAreNeededAndRunsALegacyTemplateAsBefore() {
        selected = TEST_MODEL
        val cool = LocalAiOrchestrator({ runtime }, object : ModelSelection { override suspend fun selected() = selected }, StatusThermalGate { 0 }, OrchestratorPromptAssets, Resolver(docs, templates, time), CommandExecutor(docs), null, documents = docs)
        runtime.answers += proposalJson("USE_TEMPLATE", templateId = "会議メモ")
        val r = runBlocking { cool.interact("会議メモを作りたい", AiResultContext.EMPTY) }
        assertEquals(meeting, (r as AiInteractionResult.TemplateForm).template)
        val legacy = MemoTemplate("t1", "週次レビュー", "## 今週\n- ")
        val o = LocalAiOrchestrator({ runtime }, object : ModelSelection { override suspend fun selected() = selected }, StatusThermalGate { 0 }, OrchestratorPromptAssets, Resolver(docs, FakeTemplates(listOf(legacy)), time), CommandExecutor(docs), null, documents = docs)
        runtime.answers += proposalJson("USE_TEMPLATE", templateId = "t1")
        val p = (runBlocking { o.interact("週次レビューで作って", AiResultContext.EMPTY) } as AiInteractionResult.WritePreview).preview as CommandPreview.Template
        assertEquals("## 今週\n- ", p.renderedBody)
        assertEquals(DocumentKind.MEMO, p.kind)
    }
}

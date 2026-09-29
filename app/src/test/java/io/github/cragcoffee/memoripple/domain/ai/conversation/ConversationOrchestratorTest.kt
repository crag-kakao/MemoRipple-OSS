package io.github.cragcoffee.memoripple.domain.ai.conversation

import io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult
import io.github.cragcoffee.memoripple.domain.ai.AiResultContext
import io.github.cragcoffee.memoripple.domain.ai.CommandExecutor
import io.github.cragcoffee.memoripple.domain.ai.CommandPreview
import io.github.cragcoffee.memoripple.domain.ai.FakeAiRuntime
import io.github.cragcoffee.memoripple.domain.ai.FakeDocumentAccess
import io.github.cragcoffee.memoripple.domain.ai.FakeTemplates
import io.github.cragcoffee.memoripple.domain.ai.FixedTime
import io.github.cragcoffee.memoripple.domain.ai.LocalAiOrchestrator
import io.github.cragcoffee.memoripple.domain.ai.ModelSelection
import io.github.cragcoffee.memoripple.domain.ai.OrchestratorPromptAssets
import io.github.cragcoffee.memoripple.domain.ai.ProposalField
import io.github.cragcoffee.memoripple.domain.ai.Resolver
import io.github.cragcoffee.memoripple.domain.ai.TEST_MODEL
import io.github.cragcoffee.memoripple.domain.ai.WriteOutcome
import io.github.cragcoffee.memoripple.domain.ai.proposalJson
import io.github.cragcoffee.memoripple.domain.ai.runtime.StatusThermalGate
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8 RED 18–34 (docs/AI_CONVERSATION_HISTORY.md): the conversation's safe context reaches the
 * orchestrator as a request-scoped result context (rebuilt from the stored refs) and an anchor;
 * 「2番目」 / 「それ」 / 「さっきのメモ」 are read by rule and still go through the Resolver, the
 * preview and the Human Confirmation. The model never sees an id; the window reaches the prompt.
 */
class ConversationOrchestratorTest {
    private val time = FixedTime()
    private val docs = FakeDocumentAccess(time)
    private val runtime = FakeAiRuntime()
    private val templates = FakeTemplates(listOf(MemoTemplate("t1", "週次レビュー", "## 今週\n- ")))

    private fun orchestrator() = LocalAiOrchestrator(
        runtime = { runtime },
        selection = object : ModelSelection { override suspend fun selected() = TEST_MODEL },
        thermal = StatusThermalGate { 0 },
        assets = OrchestratorPromptAssets,
        resolver = Resolver(docs, templates, time),
        executor = CommandExecutor(docs),
        documents = docs,
    )

    private fun ask(text: String, context: AiResultContext = AiResultContext.EMPTY, referents: ConversationReferents = ConversationReferents.NONE) =
        runBlocking { orchestrator().interact(text, context, referents = referents) }

    private val noTarget = proposalJson("OPEN", extra = "".let { "" }).replace("\"missingFields\":[]", "\"missingFields\":[\"targetName\"]")
    private val appendNoTarget = proposalJson("APPEND", text = "完了").replace("\"missingFields\":[]", "\"missingFields\":[\"targetName\"]")

    // --- A (RED 17, 18, 20): 「2番目を開いて」 = the second of the latest shown results, through the Resolver ---

    @Test
    fun theSecondOfTheLatestResultsOpensWhenTheModelGivesNoTarget() {
        val a = docs.journal(1, time.currentLocalDate().minusDays(1), "散歩")
        val b = docs.journal(2, time.currentLocalDate().minusDays(1), "買い物")
        val c = docs.journal(3, time.currentLocalDate().minusDays(1), "会議")
        val context = AiResultContext.of(listOf(docs.summary(a), docs.summary(b), docs.summary(c)))
        runtime.answers += noTarget
        val r = ask("2番目を開いて", context)
        assertEquals(b, (r as AiInteractionResult.Open).target.ref)
    }

    @Test
    fun theModelsOwnResultRefStillWinsAndTheShownLinesCarryNoId() {
        val a = docs.memo(11, "MemoRipple開発"); val b = docs.memo(12, "買い物")
        val context = AiResultContext.of(listOf(docs.summary(a), docs.summary(b)))
        runtime.answers += proposalJson("OPEN", targetRef = "result_2")
        assertEquals(b, (ask("2番目を開いて", context) as AiInteractionResult.Open).target.ref)
        val prompt = runtime.requests.single().userMessage
        assertTrue(prompt.contains("result_2: 買い物"))
        assertFalse(prompt.contains("12") && prompt.contains("MEMO"))
    }

    // --- RED 21, 22: an ordinal past the list, or a result that vanished, is a safe stop ---

    @Test
    fun anOrdinalPastTheShownListAsksInsteadOfGuessing() {
        val a = docs.memo(11, "MemoRipple開発")
        runtime.answers += noTarget
        val r = ask("5番目を開いて", AiResultContext.of(listOf(docs.summary(a))))
        assertTrue(r is AiInteractionResult.NeedsInformation)
        assertEquals(0, docs.writes.size)
    }

    @Test
    fun aShownResultThatNoLongerExistsIsNotFoundNeverOpened() {
        val a = docs.memo(11, "MemoRipple開発"); val b = docs.memo(12, "買い物")
        val context = AiResultContext.of(listOf(docs.summary(a), docs.summary(b)))
        docs.docs.remove(b)
        runtime.answers += noTarget
        val r = ask("2番目を開いて", context)
        assertEquals(AiInteractionResult.NotFound(ProposalField.TARGET_REF), r)
        runtime.answers += proposalJson("OPEN", targetRef = "result_2")
        assertEquals("the model's own ref is re-validated too", AiInteractionResult.NotFound(ProposalField.TARGET_REF), ask("2番目を開いて", context))
    }

    // --- B, C (RED 23–26, 29, 30): 「それ」 and 「さっきのメモ」 = the last document anchor, re-validated, previewed ---

    @Test
    fun itAppendsToTheAnchorThroughAPreviewAndOneConfirmation() {
        val dev = docs.memo(11, "MemoRipple開発", body = "## 進捗")
        runtime.answers += appendNoTarget
        val r = ask("それに『完了』を追記して", referents = ConversationReferents(anchor = dev))
        val preview = (r as AiInteractionResult.WritePreview).preview as CommandPreview.Append
        assertEquals(dev, preview.target.ref)
        assertEquals("完了", preview.text)
        assertEquals(0, docs.writes.size)
        assertTrue(runBlocking { orchestrator().execute(r.pending) } is WriteOutcome.Success)
        assertEquals("## 進捗\n完了", docs.docs.getValue(dev).body)
        assertEquals("the ticket runs once", WriteOutcome.AlreadyExecuted, runBlocking { orchestrator().execute(r.pending) })
    }

    @Test
    fun theRecentMemoIsTheAnchorOnlyWhenTheAnchorIsAMemo() {
        val memo = docs.memo(11, "MemoRipple開発")
        runtime.answers += appendNoTarget
        assertTrue(ask("さっきのメモに「追記」を追加して", referents = ConversationReferents(anchor = memo)) is AiInteractionResult.WritePreview)
        val journal = docs.journal(2, time.currentLocalDate(), "散歩")
        runtime.answers += appendNoTarget
        val r = ask("さっきのメモに「追記」を追加して", referents = ConversationReferents(anchor = journal))
        assertTrue("a journal is not さっきのメモ", r is AiInteractionResult.NeedsInformation)
        assertEquals(0, docs.writes.size)
    }

    @Test
    fun itWithNoAnchorIsAQuestionNotAGuess() {
        docs.memo(11, "MemoRipple開発")
        runtime.answers += appendNoTarget
        val r = ask("それに『完了』を追記して")
        assertTrue(r is AiInteractionResult.NeedsInformation)
        assertEquals(0, docs.searches)
    }

    @Test
    fun anAnchorWhoseDocumentIsGoneIsNotFoundAndALockedJournalIsRefused() {
        val gone = DocumentRef(DocumentKind.MEMO, 999)
        runtime.answers += noTarget
        assertEquals(AiInteractionResult.NotFound(ProposalField.TARGET_REF), ask("それを開いて", referents = ConversationReferents(anchor = gone)))
        val locked = docs.journal(3, time.currentLocalDate(), "x", state = io.github.cragcoffee.memoripple.domain.diary.DiaryState.LOCKED)
        runtime.answers += appendNoTarget
        val r = ask("それに『完了』を追記して", referents = ConversationReferents(anchor = locked))
        assertTrue(r is AiInteractionResult.Invalid)
    }

    // --- D (RED 28): another conversation's context is simply absent ---

    @Test
    fun aFreshConversationHasNoResultsAndNoAnchorSoTheOrdinalIsAQuestion() {
        docs.memo(11, "MemoRipple開発")
        runtime.answers += noTarget
        assertTrue(ask("2番目を開いて", AiResultContext.EMPTY, ConversationReferents.NONE) is AiInteractionResult.NeedsInformation)
    }

    // --- RED 19, 36: the window reaches the prompt, bounded; nothing hidden in it ---

    @Test
    fun theRecentWindowIsInTheUserTurnAndTheSystemPromptIsUntouched() {
        val window = ConversationWindow(listOf(ConversationLine(ChatRole.USER, "昨日の日記を探して"), ConversationLine(ChatRole.ASSISTANT, "3件見つかりました。")), truncated = true)
        runtime.answers += proposalJson("SEARCH", query = "散歩")
        ask("散歩を探して", referents = ConversationReferents(window = window))
        val request = runtime.requests.single()
        assertEquals(OrchestratorPromptAssets.intentSystemPrompt, request.systemPrompt)
        assertTrue(request.userMessage.contains("USER: 昨日の日記を探して") && request.userMessage.contains("ASSISTANT: 3件見つかりました。"))
        assertTrue(request.userMessage.contains("それより前の会話は省略"))
    }

    // --- Phase 8 (S20): the model wrote the demonstrative into targetName — a referent, never a title to search ---

    @Test
    fun aDemonstrativeTheModelWroteAsTheNameIsReadAsTheAnchorNotSearched() {
        val dev = docs.memo(11, "MemoRipple開発", body = "## 進捗")
        runtime.answers += proposalJson("APPEND", targetName = "それ", text = "会話テスト")
        val r = ask("それに『会話テスト』を追記して", referents = ConversationReferents(anchor = dev))
        assertEquals(dev, ((r as AiInteractionResult.WritePreview).preview as CommandPreview.Append).target.ref)
        assertEquals("no search for a demonstrative", 0, docs.searches)
        runtime.answers += proposalJson("OPEN", targetName = "さっきのメモ")
        assertTrue("without an anchor it is a question, not NotFound", ask("さっきのメモを開いて") is AiInteractionResult.NeedsInformation)
        docs.memo(12, "それから")
        runtime.answers += proposalJson("OPEN", targetName = "それから")
        assertEquals("a real title that starts like a demonstrative is searched", DocumentRef(DocumentKind.MEMO, 12), (ask("それからを開いて") as AiInteractionResult.Open).target.ref)
    }

    // --- RED 31, 32: the Resolver stays authoritative; the user's referent beats a name the model invented, a name in the sentence stands ---

    @Test
    fun theSentencesReferentWinsOverAModelInventedNameAndANamedSentenceIsHonoured() {
        val dev = docs.memo(11, "MemoRipple開発"); docs.memo(12, "買い物")
        // the user said 「それ」: the anchor is the reading, whatever name the model produced (seen on the S20: a 5-character invention)
        runtime.answers += proposalJson("OPEN", targetName = "買い物")
        assertEquals(dev, (ask("それを開いて", referents = ConversationReferents(anchor = dev)) as AiInteractionResult.Open).target.ref)
        // the user named a document: the model's name for it is honoured, the anchor is not consulted
        runtime.answers += proposalJson("OPEN", targetName = "買い物")
        assertEquals(DocumentRef(DocumentKind.MEMO, 12), (ask("買い物を開いて", referents = ConversationReferents(anchor = dev)) as AiInteractionResult.Open).target.ref)
        // a shown ref the model gave still stands over the sentence
        val context = AiResultContext.of(listOf(docs.summary(DocumentRef(DocumentKind.MEMO, 12)), docs.summary(dev)))
        runtime.answers += proposalJson("OPEN", targetRef = "result_1")
        assertEquals(DocumentRef(DocumentKind.MEMO, 12), (ask("それを開いて", context, ConversationReferents(anchor = dev)) as AiInteractionResult.Open).target.ref)
    }
}

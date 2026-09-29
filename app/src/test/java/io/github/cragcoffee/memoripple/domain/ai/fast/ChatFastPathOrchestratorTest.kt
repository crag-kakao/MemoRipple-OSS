package io.github.cragcoffee.memoripple.domain.ai.fast

import io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult
import io.github.cragcoffee.memoripple.domain.ai.AiResultContext
import io.github.cragcoffee.memoripple.domain.ai.CommandExecutor
import io.github.cragcoffee.memoripple.domain.ai.CommandPreview
import io.github.cragcoffee.memoripple.domain.ai.FakeAiRuntime
import io.github.cragcoffee.memoripple.domain.ai.FakeDocumentAccess
import io.github.cragcoffee.memoripple.domain.ai.FakeTemplates
import io.github.cragcoffee.memoripple.domain.ai.FixedTime
import io.github.cragcoffee.memoripple.domain.ai.LocalAiOrchestrator
import io.github.cragcoffee.memoripple.domain.ai.OrchestratorPromptAssets
import io.github.cragcoffee.memoripple.domain.ai.Resolver
import io.github.cragcoffee.memoripple.domain.ai.WriteOutcome
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationReferents
import io.github.cragcoffee.memoripple.domain.ai.ModelSelection
import io.github.cragcoffee.memoripple.domain.ai.runtime.StatusThermalGate
import io.github.cragcoffee.memoripple.domain.documents.CreateDestination
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Fast Path through the orchestrator (docs/CHAT_FAST_PATH.md): a recognized sentence enters
 * the same pipeline the model's proposal would — validator → Resolver → policy → direct read or
 * preview + the one confirmation — with **no model**: no selection, no runtime, no load, no
 * generation, thermal irrelevant (nothing generates). NO_MATCH — and a referent with no anchor —
 * returns null: the caller falls back to the AI route.
 */
class ChatFastPathOrchestratorTest {
    private val time = FixedTime()
    private val docs = FakeDocumentAccess(time)
    private val runtime = FakeAiRuntime()

    private fun orchestrator() = LocalAiOrchestrator(
        runtime = { runtime },
        selection = object : ModelSelection { override suspend fun selected() = null },   // no model at all
        thermal = StatusThermalGate { 3 },                                                 // even a hot device
        assets = OrchestratorPromptAssets,
        resolver = Resolver(docs, FakeTemplates(emptyList()), time),
        executor = CommandExecutor(docs),
        documents = docs,
    )

    private fun fast(text: String, referents: ConversationReferents = ConversationReferents.NONE, destination: CreateDestination? = null): AiInteractionResult? =
        runBlocking { orchestrator().interactFast(text, AiResultContext.EMPTY, referents, destination) }

    @Test
    fun aUniqueYesterdayJournalOpensWithNoModelAndTodayAmbiguousAsks() {
        val yesterday = docs.journal(1, time.today.minusDays(1), "昨日のこと")
        docs.journal(2, time.today, "今日その一")
        docs.journal(3, time.today, "今日その二")
        val opened = fast("昨日の日記を開いて") as AiInteractionResult.Open
        assertEquals(yesterday, opened.target.ref)
        val ambiguous = fast("今日の日記を開いて") as AiInteractionResult.Ambiguous
        assertEquals(2, ambiguous.candidates.size)
        assertTrue(fast("『MemoRipple開発』を開いて") is AiInteractionResult.NotFound)
        assertEquals("no load, ever", 0, runtime.loads)
        assertEquals("no generation, ever", 0, runtime.requests.size)
    }

    @Test
    fun searchRunsDirectlyAndKeepsTheQuery() {
        docs.memo(1, "旅行の計画", "沖縄")
        val r = fast("旅行を探して") as AiInteractionResult.SearchResults
        assertEquals("旅行", r.query.text)
        assertEquals(1, r.results.size)
        val dated = fast("昨日の日記を探して") as AiInteractionResult.SearchResults
        assertTrue(dated.query.text.isEmpty())
        assertEquals(0, runtime.loads)
    }

    @Test
    fun createStopsAtThePreviewTakesTheDestinationAndOneConfirmationMakesOne() = runBlocking {
        val o = orchestrator()
        val preview = o.interactFast("メモを作って", AiResultContext.EMPTY, ConversationReferents.NONE, CreateDestination(7L, "仕事")) as AiInteractionResult.WritePreview
        assertEquals("仕事", (preview.preview as CommandPreview.Create).folderName)
        assertEquals("the preview wrote nothing", 0, docs.writes.size)
        assertTrue(o.execute(preview.pending) is WriteOutcome.Success)
        assertEquals(1, docs.writes.count { it.startsWith("create:") })
        assertTrue("a second confirm is one write", o.execute(preview.pending) is WriteOutcome.AlreadyExecuted)
        assertEquals(1, docs.writes.count { it.startsWith("create:") })
        assertEquals(0, runtime.loads)
    }

    @Test
    fun appendStopsAtThePreviewAndAReferentRidesTheAnchorOrFallsBack() = runBlocking {
        val dev = docs.memo(1, "MemoRipple開発", "## 進捗")
        val o = orchestrator()
        val preview = o.interactFast("『MemoRipple開発』に『Folder対応完了』を追記して", AiResultContext.EMPTY, ConversationReferents.NONE, null) as AiInteractionResult.WritePreview
        assertEquals("Folder対応完了", (preview.preview as CommandPreview.Append).text)
        assertEquals(0, docs.writes.size)
        // the anchor settles それ — by the existing rule, never by the parser
        val anchored = o.interactFast("それに『会話テスト』を追記して", AiResultContext.EMPTY, ConversationReferents(anchor = dev), null) as AiInteractionResult.WritePreview
        assertEquals(dev, (anchored.preview as CommandPreview.Append).target.ref)
        // no anchor: the fast route declines — the AI (or the setup card) takes it
        assertNull(o.interactFast("それに『会話テスト』を追記して", AiResultContext.EMPTY, ConversationReferents.NONE, null))
        assertEquals(0, runtime.loads)
        assertEquals(0, runtime.requests.size)
    }

    @Test
    fun anAnchorOfTheWrongLifeIsNotFoundNeverAWrite() = runBlocking {
        val o = orchestrator()
        val gone = DocumentRef(DocumentKind.MEMO, 99L)
        val r = o.interactFast("それに『会話テスト』を追記して", AiResultContext.EMPTY, ConversationReferents(anchor = gone), null)
        assertTrue("a vanished anchor is NotFound by the existing rule", r is AiInteractionResult.NotFound)
        assertEquals(0, docs.writes.size)
    }

    @Test
    fun everythingElseReturnsNullForTheAiRoute() {
        listOf("昨日のことをなんかいい感じにして", "メモを作らないで", "昨日の日記を開ける？", "昨日の日記を開いて、そこに追記して").forEach {
            assertNull("「$it」 is the AI's", fast(it))
        }
        assertEquals(0, runtime.loads)
    }
}

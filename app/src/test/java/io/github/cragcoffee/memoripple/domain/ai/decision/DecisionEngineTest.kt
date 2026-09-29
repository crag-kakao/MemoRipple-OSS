package io.github.cragcoffee.memoripple.domain.ai.decision

import io.github.cragcoffee.memoripple.domain.ai.AiIntent
import io.github.cragcoffee.memoripple.domain.ai.AiResultRef
import io.github.cragcoffee.memoripple.domain.ai.DateToken
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DecisionEngine Phase 3 (docs/DECISION_ENGINE.md, human brief 2026-09-23): a pure, deterministic
 * layer between the Fast Path's NO_MATCH and the generation model. It answers only three things —
 * a CERTAIN operation the safe pipeline can take, a fixed clarification question, or
 * NotApplicable for the model. **This file is the golden corpus and the contract**: every CERTAIN
 * must be exactly the expected proposal, every negative must never become an unsafe CERTAIN —
 * a false negative costs one model call, a false positive would cost trust.
 */
class DecisionEngineTest {

    private fun input(
        text: String,
        shown: List<String> = emptyList(),
        anchorKind: DocumentKind? = null,
        anchorTitle: String? = null,
    ) = DecisionInput(
        rawText = text,
        shownCount = shown.size,
        shownTitles = shown,
        hasAnchor = anchorKind != null,
        anchorKind = anchorKind,
        anchorTitle = anchorTitle ?: if (anchorKind != null) "アンカー" else null,
    )

    private fun decide(
        text: String,
        shown: List<String> = emptyList(),
        anchorKind: DocumentKind? = null,
        anchorTitle: String? = null,
    ): DecisionResult = DeterministicDecisionEngine.decide(input(text, shown, anchorKind, anchorTitle))

    private fun certain(text: String, shown: List<String> = emptyList(), anchorKind: DocumentKind? = null): DecisionResult.Operation {
        val r = decide(text, shown, anchorKind)
        assertTrue("expected CERTAIN: 「$text」 → $r", r is DecisionResult.Operation && r.certainty == DecisionCertainty.CERTAIN)
        return r as DecisionResult.Operation
    }

    private fun clarify(text: String, shown: List<String> = emptyList(), anchorKind: DocumentKind? = null): DecisionResult.NeedsClarification {
        val r = decide(text, shown, anchorKind)
        assertTrue("expected CLARIFICATION: 「$text」 → $r", r is DecisionResult.NeedsClarification)
        return r as DecisionResult.NeedsClarification
    }

    private fun notApplicable(text: String, shown: List<String> = emptyList(), anchorKind: DocumentKind? = null) {
        val r = decide(text, shown, anchorKind)
        assertEquals("expected NOT_APPLICABLE: 「$text」 → $r", DecisionResult.NotApplicable, r)
    }

    private val three = listOf("散歩の記録", "買い物の記録", "会議の記録")

    // --- CERTAIN: ordinal OPEN (previous result reference) ---

    @Test
    fun ordinalOpensResolveAgainstTheShownResults() {
        listOf("2番目を開いて", "2番目を開いてください", "2件目を開いて", "2つ目を見せて", "2番目を表示して").forEach { text ->
            val op = certain(text, shown = three)
            assertEquals(AiIntent.OPEN, op.proposal.intent)
            assertEquals(AiResultRef(2), op.proposal.targetRef)
            assertNull(op.proposal.targetName)
        }
        assertEquals(AiResultRef(1), certain("最初のを開いて", shown = three).proposal.targetRef)
        assertEquals(AiResultRef(3), certain("最後のを開いて", shown = three).proposal.targetRef)
        assertEquals(AiResultRef(1), certain("1番目を開いて", shown = three).proposal.targetRef)
    }

    @Test
    fun ordinalAppendWithAnExplicitBodyIsCertain() {
        listOf("2番目に『完了』を追記して", "2番目に「完了」を追加して", "2番目に完了を書き足して").forEach { text ->
            val op = certain(text, shown = three)
            assertEquals(AiIntent.APPEND, op.proposal.intent)
            assertEquals(AiResultRef(2), op.proposal.targetRef)
            assertEquals("完了", op.proposal.text)
        }
    }

    // --- CLARIFICATION BODY: ordinal append without a body asks, never generates ---

    @Test
    fun ordinalAppendWithoutABodyAsksWhatToAppend() {
        listOf("2番目に追記して", "2番目に追加して", "3番目に書き足して").forEach { text ->
            val c = clarify(text, shown = three)
            assertEquals(ClarificationSlot.BODY, c.slot)
            assertEquals("何を追記しますか？", c.question)
            assertTrue(c.choices.isEmpty())
            assertEquals(AiIntent.APPEND, c.draft.intent)
            assertTrue(c.draft.targetRef != null)
        }
    }

    // --- ORDINAL negatives: out of range, no results, wrong verbs ---

    @Test
    fun ordinalsOutsideTheShownResultsNeverMatch() {
        notApplicable("2番目を開いて")                          // nothing shown (also: another conversation's refs simply are not here)
        notApplicable("4番目を開いて", shown = three)           // out of range
        notApplicable("12番目を開いて", shown = three)
        notApplicable("2番目を探して", shown = three)           // an ordinal is not a query
        notApplicable("2番目", shown = three)                   // no operation
    }

    // --- CERTAIN: demonstrative context (the anchor, never free inference) ---

    @Test
    fun demonstrativeOpensRideTheAnchorWhenItAgreesInKind() {
        listOf("それを開いて", "これを開いて", "さっきのメモを開いて", "それ開いて").forEach { text ->
            val op = certain(text, anchorKind = DocumentKind.MEMO)
            assertEquals(AiIntent.OPEN, op.proposal.intent)
            assertTrue("the anchor carries the target, not a name", op.anchorTarget)
            assertNull(op.proposal.targetName)
        }
        assertTrue(certain("さっきの日記を開いて", anchorKind = DocumentKind.JOURNAL).anchorTarget)
    }

    @Test
    fun demonstrativeAppendWithABodyIsCertainOnTheAnchor() {
        val op = certain("それに『Folder対応完了』を追記して", anchorKind = DocumentKind.MEMO)
        assertEquals(AiIntent.APPEND, op.proposal.intent)
        assertEquals("Folder対応完了", op.proposal.text)
        assertTrue(op.anchorTarget)
        assertEquals("Folder対応完了", certain("これにFolder対応完了を追加して", anchorKind = DocumentKind.MEMO).proposal.text)
    }

    @Test
    fun demonstrativeAppendWithoutABodyAsksWhatToAppend() {
        listOf("それに追記して", "これに追加して", "さっきのメモに追記して").forEach { text ->
            val c = clarify(text, anchorKind = DocumentKind.MEMO)
            assertEquals(ClarificationSlot.BODY, c.slot)
            assertEquals("何を追記しますか？", c.question)
            assertTrue(c.anchorTarget)
        }
    }

    // --- DEMONSTRATIVE negatives: no anchor, kind mismatch — never a guess ---

    @Test
    fun demonstrativesWithoutAUsableAnchorNeverMatch() {
        notApplicable("それを開いて")                                        // no anchor at all
        notApplicable("それに『完了』を追記して")
        notApplicable("さっきの日記を開いて", anchorKind = DocumentKind.MEMO)  // the kind disagrees
        notApplicable("さっきのメモを開いて", anchorKind = DocumentKind.JOURNAL)
    }

    // --- CERTAIN: bare verbs on the anchor ---

    @Test
    fun aBareOpenRidesTheAnchorAndABareAppendAsksForTheBody() {
        assertTrue(certain("開いて", anchorKind = DocumentKind.MEMO).anchorTarget)
        val c = clarify("追記して", anchorKind = DocumentKind.MEMO)
        assertEquals(ClarificationSlot.BODY, c.slot)
        assertTrue(c.anchorTarget)
        notApplicable("開いて")            // no anchor
        notApplicable("追記して")
        notApplicable("探して", anchorKind = DocumentKind.MEMO)   // unbounded
    }

    // --- CERTAIN: date + document kind (wider than the Fast Path's 日記-only shapes) ---

    @Test
    fun datedKindsOpenAndSearchDeterministically() {
        certain("昨日のメモを開いて").proposal.let {
            assertEquals(AiIntent.OPEN, it.intent); assertEquals(DocumentKind.MEMO, it.documentKind); assertEquals(DateToken.YESTERDAY, it.dateToken)
        }
        certain("今日のアウトラインを開いて").proposal.let {
            assertEquals(DocumentKind.OUTLINE, it.documentKind); assertEquals(DateToken.TODAY, it.dateToken)
        }
        certain("昨日の記録を探して").proposal.let {
            assertEquals(AiIntent.SEARCH, it.intent); assertNull(it.documentKind); assertEquals(DateToken.YESTERDAY, it.dateToken)
        }
        certain("昨日のメモを探して").proposal.let {
            assertEquals(AiIntent.SEARCH, it.intent); assertEquals(DocumentKind.MEMO, it.documentKind)
        }
        certain("今日の記録を検索して").proposal.let {
            assertEquals(AiIntent.SEARCH, it.intent); assertEquals(DateToken.TODAY, it.dateToken)
        }
    }

    @Test
    fun datedShapesThatAreNotSafeStayOut() {
        notApplicable("昨日の記録を開いて")            // 記録 is no kind for a dated OPEN target
        notApplicable("昨日の日記に追記して")           // a dated APPEND target stays the model's in Phase 3
        notApplicable("先週のメモを開いて")             // only today / yesterday in this phase
        notApplicable("昨日のメモをまとめて")           // not an operation verb
    }

    // --- CLARIFICATION TARGET: an append body with no target and enumerable candidates ---

    @Test
    fun aTargetlessAppendWithShownResultsAsksWhichRecord() {
        val c = clarify("『完了』を追記して", shown = three)
        assertEquals(ClarificationSlot.TARGET, c.slot)
        assertEquals("どの記録に追記しますか？", c.question)
        assertEquals(three, c.choices.map { it.label })
        assertEquals(listOf(1, 2, 3), c.choices.map { it.ordinal })
        assertEquals("完了", c.draft.text)
        assertEquals(AiIntent.APPEND, c.draft.intent)
    }

    @Test
    fun aTargetlessAppendWithOnlyTheAnchorIsCertainOnIt() {
        val op = certain("『完了』を追記して", anchorKind = DocumentKind.MEMO)
        assertTrue(op.anchorTarget)
        assertEquals("完了", op.proposal.text)
    }

    @Test
    fun aTargetlessAppendWithBothShownResultsAndAnAnchorListsBoth() {
        val c = decide("『完了』を追記して", shown = listOf("散歩の記録", "買い物の記録"), anchorKind = DocumentKind.MEMO, anchorTitle = "MemoRipple開発")
        assertTrue("$c", c is DecisionResult.NeedsClarification)
        val choices = (c as DecisionResult.NeedsClarification).choices
        assertEquals(listOf("散歩の記録", "買い物の記録", "MemoRipple開発"), choices.map { it.label })
        assertEquals(listOf(1, 2, null), choices.map { it.ordinal })
    }

    @Test
    fun tooManyCandidatesAreNobodysGuess() {
        val six = (1..6).map { "記録$it" }
        notApplicable("『完了』を追記して", shown = six)
        notApplicable("『完了』を追記して")   // nothing shown, no anchor: never a silent target
    }

    // --- AMBIGUOUS date phrase (例4): competing deterministic references ask, never pick ---

    @Test
    fun anAmbiguousDatedTargetAsksInsteadOfPicking() {
        val c = clarify("昨日のやつに『完了』を追加して", shown = listOf("昨日の日記", "昨日のメモ"))
        assertEquals(ClarificationSlot.TARGET, c.slot)
        assertTrue(c.choices.isNotEmpty())
        notApplicable("昨日のやつに『完了』を追加して")   // nothing to offer → the model's
    }

    // --- CONVERSATION: plain talk goes to the model ---

    @Test
    fun conversationalSentencesAreNotApplicable() {
        listOf(
            "今日は疲れた", "ちょっと相談したい", "このアイデアどう思う", "話を聞いて",
            "散歩について何かあったっけ", "昨日のことを思い出したいんだけど", "おはよう", "ありがとう",
            "明日の予定を考えたい", "いいアイデアが浮かんだ",
        ).forEach { notApplicable(it, shown = three, anchorKind = DocumentKind.MEMO) }
    }

    // --- CAPABILITY QUESTION: a question about ability is never an operation ---

    @Test
    fun capabilityQuestionsNeverExecute() {
        listOf(
            "昨日の日記を開ける？", "2番目を開けますか？", "それに追記できる？", "メモを作れますか",
            "2番目を開けるかな", "昨日のメモって開けるっけ",
        ).forEach { notApplicable(it, shown = three, anchorKind = DocumentKind.MEMO) }
    }

    // --- NEGATION ---

    @Test
    fun negationsNeverBecomeOperations() {
        listOf(
            "それは開かないで", "2番目は開かないで", "追記しなくていい", "それに追記しないで",
            "開かなくていいよ", "昨日のメモは探さないでください",
        ).forEach { notApplicable(it, shown = three, anchorKind = DocumentKind.MEMO) }
    }

    // --- COMPOUND: two operations are the model's ---

    @Test
    fun compoundRequestsAreNotDecomposed() {
        listOf(
            "昨日の日記を開いて要約して", "2番目を開いて、それに追記して", "開いて見せて教えて",
            "それを開いてから昨日のメモを探して",
        ).forEach { notApplicable(it, shown = three, anchorKind = DocumentKind.MEMO) }
    }

    // --- NO CONTEXT: quoted verbs, names, particles — the boundary stays the AI's ---

    @Test
    fun everythingElseStaysTheModels() {
        notApplicable("『2番目を開いて』というメモを探して", shown = three)   // a quoted verb is words
        notApplicable("ラーメンについて書いたメモに『完了』を追記して", shown = three)   // a clause target
        notApplicable("メモを作って考えを整理したい")
        notApplicable("それとこれを比べて", anchorKind = DocumentKind.MEMO)
        notApplicable("")
        notApplicable("   ")
    }

    // --- STALE / cross-conversation guards live in the inputs: no refs, no anchor → nothing decided ---

    @Test
    fun theEngineSeesOnlyWhatTheConversationLendsIt() {
        // another conversation's results are simply not in the input: the ordinal cannot resolve
        notApplicable("2番目を開いて", shown = emptyList())
        // a vanished anchor is passed as no anchor: the demonstrative cannot resolve
        notApplicable("それを開いて", anchorKind = null)
    }

    // --- The engine never invents ids, names or numeric confidence ---

    @Test
    fun certainOperationsCarryOnlySafeReferences() {
        val ops = listOf(
            certain("2番目を開いて", shown = three),
            certain("それを開いて", anchorKind = DocumentKind.MEMO),
            certain("昨日のメモを開いて"),
        )
        ops.forEach { op ->
            assertNull("no invented name", op.proposal.targetName)
            assertTrue("no missing fields smuggled", op.proposal.missingFields.isEmpty())
        }
    }

    // --- 「この〜」 (docs/CHAT_MEMO_CONTEXT.md, 2026-09-26): the selected memo stands where the anchor stands ---

    @Test
    fun thisMemoOpensAndAppendsOnTheAnchor() {
        val open = certain("このメモを開いて", anchorKind = DocumentKind.MEMO)
        assertEquals(AiIntent.OPEN, open.proposal.intent)
        assertTrue(open.anchorTarget)
        assertNull(open.proposal.targetName)
        val append = certain("このメモに『牛乳』を追記して", anchorKind = DocumentKind.MEMO)
        assertEquals(AiIntent.APPEND, append.proposal.intent)
        assertEquals("牛乳", append.proposal.text)
        assertTrue(append.anchorTarget)
        val ask = clarify("このメモに追記して", anchorKind = DocumentKind.MEMO)
        assertEquals(ClarificationSlot.BODY, ask.slot)
        assertTrue(ask.anchorTarget)
    }

    @Test
    fun thisWithTheWrongKindOrNoAnchorIsNoDecision() {
        notApplicable("この日記を開いて", anchorKind = DocumentKind.MEMO)
        notApplicable("このメモを開いて")
        notApplicable("このまちの記録を開いて", anchorKind = DocumentKind.MEMO)
    }
}

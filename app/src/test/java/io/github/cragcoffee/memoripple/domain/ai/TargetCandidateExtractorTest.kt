package io.github.cragcoffee.memoripple.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 RED (docs/AI_TARGET_RESOLUTION.md): the deterministic target-candidate layer. Rule-based,
 * a handful of Japanese shapes, one candidate at most with its evidence; no fuzzy, phonetic or
 * semantic guess; nothing that is likely not a document title becomes a candidate.
 */
class TargetCandidateExtractorTest {
    private fun append(text: String? = "Folder対応完了", targetName: String? = null, kind: io.github.cragcoffee.memoripple.domain.documents.DocumentKind? = null) =
        IntentProposal(AiIntent.APPEND, text = text, targetName = targetName, documentKind = kind)

    private fun open(targetName: String? = null) = IntentProposal(AiIntent.OPEN, targetName = targetName)

    private fun extract(userText: String, proposal: IntentProposal): TargetCandidate? = TargetCandidateExtractor.extract(userText, proposal)

    // --- case C (RED 1, 13–17) ---

    @Test
    fun caseCYieldsTheSegmentBeforeNiAndLeavesTheProposalTextAlone() {
        val c = extract("MemoRipple開発に『Folder対応完了』を追記して", append())
        assertEquals(TargetCandidate("MemoRipple開発", CandidateSource.BEFORE_PARTICLE_NI), c)
        val untouched = TargetCandidateExtractor.assist("MemoRipple開発に『Folder対応完了』を追記して", append(text = "Folder対応完了"))
        assertEquals("Folder対応完了", untouched.text)
        assertEquals("MemoRipple開発", untouched.targetName)
    }

    @Test
    fun theQuotedAppendContentIsNeverTheTarget() {
        listOf(
            "MemoRipple開発に『Folder対応完了』を追記して",
            "MemoRipple開発に「Folder対応完了」を追記して",
            "MemoRipple開発に\"Folder対応完了\"を追記して",
            "MemoRipple開発に 『Folder対応完了』 を追記して",
        ).forEach { text ->
            val c = extract(text, append())
            assertEquals(text, "MemoRipple開発", c?.text)
        }
        // a quote that contains に must not split the target
        val c = extract("MemoRipple開発に『次にやること』を追記して", append(text = "次にやること"))
        assertEquals("MemoRipple開発", c?.text)
    }

    @Test
    fun anUnquotedAppendStillFindsTheTargetBeforeNi() {
        assertEquals("MemoRipple開発", extract("MemoRipple開発にFolder対応完了を追記して", append())?.text)
        assertEquals("買い物リスト", extract("買い物リストに牛乳を追記して", append(text = "牛乳"))?.text)
    }

    @Test
    fun aProposalThatAlreadyNamesATargetIsNotSecondGuessed() {
        val p = append(targetName = "MemoRipple開発")
        assertEquals(p, TargetCandidateExtractor.assist("別の名前に『x』を追記して", p))
        assertNull(extract("別の名前に『x』を追記して", p))
    }

    // --- OPEN shapes (RED 18) ---

    @Test
    fun openShapesYieldTheSegmentBeforeTheVerb() {
        listOf("MemoRipple開発を開いて", "MemoRipple開発を見せて", "MemoRipple開発を表示して", "MemoRipple開発を開く", "MemoRipple開発 を開いて").forEach { text ->
            assertEquals(text, TargetCandidate("MemoRipple開発", CandidateSource.BEFORE_OPEN_VERB), extract(text, open()))
        }
        assertEquals(CandidateSource.BEFORE_OPEN_VERB, extract("週次レビューを開いて", open())?.source)
    }

    // --- false-positive protection (RED 22–25) ---

    @Test
    fun demonstrativesAndReferencesToEarlierTurnsAreNotTargets() {
        listOf("これに追記して", "それに追記して", "あれに『x』を追記して", "さっきのに追記して", "前のやつを開いて", "さっきのを開いて", "それを開いて", "2番目を開いて").forEach { text ->
            assertNull(text, extract(text, if (text.contains("開")) open() else append(text = null)))
        }
    }

    @Test
    fun genericDocumentAndFolderWordsAreNotTitles() {
        listOf("日記に追記して", "メモに追記して", "アウトラインに追記して", "ノートに追記して", "フォルダにメモを追加して", "フォルダに追記して", "メモを開いて", "日記を開いて").forEach { text ->
            assertNull(text, extract(text, if (text.contains("開")) open() else append(text = null)))
        }
    }

    @Test
    fun datePhrasesAndSentencesAreNotTitles() {
        listOf("今日は疲れたので日記に追記して", "今日の日記に追記して", "昨日の日記に『散歩』を追記して", "今週の予定に追記して", "明日のメモを開いて", "9月19日の日記を開いて").forEach { text ->
            assertNull(text, extract(text, if (text.contains("開")) open() else append(text = null)))
        }
        // a clause with a sentence particle before に is not a name
        assertNull(extract("疲れたのでMemoRipple開発に追記して", append(text = null)))
        assertNull(extract("MemoRipple開発が終わったので日記に追記して", append(text = null)))
    }

    @Test
    fun aTitleThatHappensToContainNoIsStillATitle() {
        assertEquals("今後の予定", extract("今後の予定に『会議』を追記して", append(text = "会議"))?.text)
        assertEquals("旅の記録", extract("旅の記録を開いて", open())?.text)
    }

    @Test
    fun onlyOpenAndAppendAreAssistedAndOnlyWhenTheTargetIsMissing() {
        assertNull(extract("MemoRipple開発に『x』を追記して", IntentProposal(AiIntent.SEARCH, query = "x")))
        assertNull(extract("MemoRipple開発に『x』を追記して", IntentProposal(AiIntent.CREATE, documentKind = io.github.cragcoffee.memoripple.domain.documents.DocumentKind.MEMO)))
        assertNull(extract("MemoRipple開発を開いて", IntentProposal(AiIntent.UNKNOWN)))
        assertNull(extract("MemoRipple開発を開いて", IntentProposal(AiIntent.OPEN, targetRef = AiResultRef(1))))
    }

    // --- normalization (RED 26, 27, 30) ---

    @Test
    fun whitespaceAndUnicodeAreNormalizedButNothingIsGuessed() {
        assertEquals("MemoRipple開発", extract("  ＭｅｍｏＲｉｐｐｌｅ開発　に『x』を追記して", append(text = "x"))?.text)
        assertEquals("MemoRipple開発", extract("MemoRipple開発　を開いて", open())?.text)
        assertEquals("会議 メモ", extract("会議  メモを開いて", open())?.text)
        // a typo is a different name: no edit-distance guess is ever made here
        assertEquals("MemoRiple開発", extract("MemoRiple開発を開いて", open())?.text)
    }

    @Test
    fun aCandidateIsBoundedInLengthAndNeverEmpty() {
        assertNull(extract("に『x』を追記して", append(text = "x")))
        assertNull(extract("を開いて", open()))
        assertNull(extract("a".repeat(120) + "を開いて", open()))
        assertTrue(extract("a".repeat(40) + "を開いて", open()) != null)
    }

    @Test
    fun theAssistedProposalDropsTheModelsAdmissionOfTheMissingTargetAndNothingElse() {
        val p = IntentProposal(AiIntent.APPEND, text = "Folder対応完了", missingFields = setOf(ProposalField.TARGET_NAME, ProposalField.TARGET_REF))
        val a = TargetCandidateExtractor.assist("MemoRipple開発に『Folder対応完了』を追記して", p)
        assertEquals("MemoRipple開発", a.targetName)
        assertEquals(emptySet<ProposalField>(), a.missingFields)
        assertEquals(p.copy(targetName = "MemoRipple開発", missingFields = emptySet()), a)
        val untouched = IntentProposal(AiIntent.APPEND, text = null, missingFields = setOf(ProposalField.TEXT))
        assertEquals("the text gap stays a question", setOf(ProposalField.TEXT), TargetCandidateExtractor.assist("MemoRipple開発に追記して", untouched).missingFields)
    }
}

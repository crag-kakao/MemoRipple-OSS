package io.github.cragcoffee.memoripple.domain.ai.fast

import io.github.cragcoffee.memoripple.domain.ai.AiIntent
import io.github.cragcoffee.memoripple.domain.ai.DateToken
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Fast Path's recognizer (docs/CHAT_FAST_PATH.md, human brief 2026-09-22): a small set of
 * unmistakable Japanese shapes becomes an IntentProposal by rule — everything else is NO_MATCH
 * and goes to the AI. The golden corpus below is the contract: every MATCH must route exactly as
 * written, and every NO_MATCH must never match — a false negative is allowed, a false positive
 * is not. Dates stay tokens (the Resolver's clock turns them into days); quotes 「」『』 mark a
 * title or a body; a verb inside quotes is words, not an operation.
 */
class FastIntentRecognizerTest {

    private fun match(text: String): FastIntent {
        val fast = FastIntentRecognizer.recognize(text)
        assertTrue("expected MATCH: 「$text」", fast != null)
        return fast!!
    }

    private fun noMatch(text: String) {
        val fast = FastIntentRecognizer.recognize(text)
        assertNull("expected NO_MATCH: 「$text」 → ${fast?.proposal}", fast)
    }

    // --- OPEN ---

    @Test
    fun openMatchesTheUnmistakableShapes() {
        match("今日の日記を開いて").proposal.let {
            assertEquals(AiIntent.OPEN, it.intent); assertEquals(DocumentKind.JOURNAL, it.documentKind); assertEquals(DateToken.TODAY, it.dateToken); assertNull(it.targetName)
        }
        listOf("昨日の日記を開いて", "昨日の日記を見せて", "昨日の日記を表示して", "きのうの日記を開いて").forEach { text ->
            match(text).proposal.let { assertEquals(AiIntent.OPEN, it.intent); assertEquals(DateToken.YESTERDAY, it.dateToken); assertEquals(DocumentKind.JOURNAL, it.documentKind) }
        }
        match("『MemoRipple開発』を開いて").proposal.let { assertEquals(AiIntent.OPEN, it.intent); assertEquals("MemoRipple開発", it.targetName); assertNull(it.documentKind) }
        match("「MemoRipple開発」を開いて").proposal.let { assertEquals("MemoRipple開発", it.targetName) }
        match("MemoRipple開発を開いて").proposal.let { assertEquals(AiIntent.OPEN, it.intent); assertEquals("MemoRipple開発", it.targetName) }
        match("　MemoRipple開発を開いて。").proposal.let { assertEquals("MemoRipple開発", it.targetName) }
    }

    // --- SEARCH ---

    @Test
    fun searchMatchesAPlainWordAQuotedTitleAndTheJournalDay() {
        match("旅行を探して").proposal.let { assertEquals(AiIntent.SEARCH, it.intent); assertEquals("旅行", it.query); assertNull(it.documentKind) }
        match("『旅行』というメモを検索して").proposal.let { assertEquals(AiIntent.SEARCH, it.intent); assertEquals("旅行", it.query); assertEquals(DocumentKind.MEMO, it.documentKind) }
        match("『旅行』を検索して").proposal.let { assertEquals("旅行", it.query) }
        // a quoted verb is words to search for, never an operation
        match("『メモを作って』という文章を探して").proposal.let { assertEquals(AiIntent.SEARCH, it.intent); assertEquals("メモを作って", it.query) }
        match("昨日の日記を探して").proposal.let { assertEquals(AiIntent.SEARCH, it.intent); assertEquals(DateToken.YESTERDAY, it.dateToken); assertEquals(DocumentKind.JOURNAL, it.documentKind); assertTrue(it.query.isNullOrEmpty()) }
    }

    // --- CREATE (memo only, nothing but the ask) ---

    @Test
    fun createMatchesOnlyThePlainMemoAsk() {
        listOf("メモを作って", "新しいメモを作って", "メモを作成して", "新しいメモを作成して").forEach { text ->
            match(text).proposal.let { assertEquals(AiIntent.CREATE, it.intent); assertEquals(DocumentKind.MEMO, it.documentKind); assertNull(it.text) }
        }
        noMatch("旅行についてメモを作って")   // a body or title in the sentence: the boundary is not obvious — the AI's
        noMatch("日記を作って")               // a journal is the calendar's; not a fast shape
        noMatch("アウトラインを作って")
    }

    // --- APPEND (explicit target and body only; or a referent head the anchor must settle) ---

    @Test
    fun appendMatchesOnlyAnExplicitTargetAndBody() {
        match("『MemoRipple開発』に『Folder対応完了』を追記して").proposal.let {
            assertEquals(AiIntent.APPEND, it.intent); assertEquals("MemoRipple開発", it.targetName); assertEquals("Folder対応完了", it.text)
        }
        match("MemoRipple開発に『Folder対応完了』を追記して").proposal.let { assertEquals("MemoRipple開発", it.targetName); assertEquals("Folder対応完了", it.text) }
        match("『買い物リスト』に牛乳を追加して").proposal.let { assertEquals(AiIntent.APPEND, it.intent); assertEquals("買い物リスト", it.targetName); assertEquals("牛乳", it.text) }
        val referent = match("それに『会話テスト』を追記して")
        assertEquals(AiIntent.APPEND, referent.proposal.intent)
        assertNull("the parser never decides what それ means", referent.proposal.targetName)
        assertEquals("会話テスト", referent.proposal.text)
        assertTrue("the anchor must settle it, or the route falls back", referent.needsAnchor)
        noMatch("それに追記して")             // no body
        noMatch("前のやつに追記して")
        noMatch("MemoRipple開発に昨日話した内容と今後の予定について追記して")   // a free-text body with particles: the AI's
    }

    // --- the guards: negation, questions, compound commands, ambiguity ---

    @Test
    fun negationQuestionsAndCompoundsNeverMatch() {
        noMatch("メモを作らないで")
        noMatch("昨日の日記は開かなくていい")
        noMatch("日記を開かないでください")
        noMatch("昨日の日記を開ける？")
        noMatch("メモを作れますか？")
        noMatch("昨日の日記を開いて、そこに追記して")
        noMatch("メモを探して開いて")
        noMatch("昨日のことをなんかいい感じにして")
        noMatch("ラーメンについて書いたメモを探して")   // particles in the query: not obviously a title — the AI's
        noMatch("それを開いて")                          // a referent OPEN stays the AI's in Phase 1
        noMatch("これ")
        noMatch("メモ")
        noMatch("メモを探して")                          // a generic word alone is no query
        noMatch("開いて")
        noMatch("")
        noMatch("   ")
        // gate-found false positives (2026-09-22): a date phrase heading a token is a date the
        // Resolver must read, never a title (Phase 6's standing rule) — the prefix refuses, not
        // only the bare word; an ordinal is Phase 8's referent (the stored result), never a name
        noMatch("昨日の記録を探して")
        noMatch("今日のメモを開いて")
        noMatch("昨日の買い物リストを開いて")
        noMatch("2番目を開いて")
        noMatch("２番目を開いて")
        noMatch("3つ目を見せて")
        noMatch("MemoRipple開発に2番目を追記して")   // an ordinal is no body either
    }
}

package io.github.cragcoffee.memoripple.domain.ai.conversation

import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8 RED 20–27: the deterministic reading of 「N番目」, 「それ」, 「さっきのメモ」 — a rule, never a
 * guess. An ordinal names a position in the latest shown results; a demonstrative names the last
 * document anchor, with a kind word that must agree. Anything else is no referent.
 */
class ConversationReferentTest {
    @Test
    fun ordinalsNameAPositionInTheShownResults() {
        assertEquals(ConversationReferent.Ordinal(2), ConversationReferent.parse("2番目を開いて"))
        assertEquals(ConversationReferent.Ordinal(2), ConversationReferent.parse("２番目を開いて"))
        assertEquals(ConversationReferent.Ordinal(3), ConversationReferent.parse("3件目に『x』を追記して"))
        assertEquals(ConversationReferent.Ordinal(1), ConversationReferent.parse("最初のを開いて"))
        assertEquals(ConversationReferent.Ordinal(1), ConversationReferent.parse("一番目を開いて"))
        assertEquals(ConversationReferent.Last, ConversationReferent.parse("最後のを開いて"))
        assertEquals(ConversationReferent.Ordinal(10), ConversationReferent.parse("10番目"))
    }

    @Test
    fun demonstrativesNameTheAnchorWithAnOptionalKind() {
        assertEquals(ConversationReferent.Anchor(null), ConversationReferent.parse("それに『完了』を追記して"))
        assertEquals(ConversationReferent.Anchor(null), ConversationReferent.parse("これを開いて"))
        assertEquals(ConversationReferent.Anchor(null), ConversationReferent.parse("さっきのを開いて"))
        assertEquals(ConversationReferent.Anchor(DocumentKind.MEMO), ConversationReferent.parse("さっきのメモに「追記」を追加して"))
        assertEquals(ConversationReferent.Anchor(DocumentKind.MEMO), ConversationReferent.parse("そのメモを開いて"))
        assertEquals(ConversationReferent.Anchor(DocumentKind.JOURNAL), ConversationReferent.parse("さっきの日記に追記して"))
        assertEquals(ConversationReferent.Anchor(DocumentKind.OUTLINE), ConversationReferent.parse("さっきのアウトラインを開いて"))
        assertEquals(ConversationReferent.Anchor(null), ConversationReferent.parse("前のやつを開いて"))
    }

    @Test
    fun aNameADateOrAnythingElseIsNoReferent() {
        listOf("MemoRipple開発を開いて", "昨日の日記を探して", "今日の日記に追記して", "メモを開いて", "新しいメモを作って", "", "2025年を開いて", "2つ作って", "それからを開いて", "その他に『x』を追記して", "これからの計画を開いて").forEach {
            assertNull(it, ConversationReferent.parse(it))
        }
    }

    @Test
    fun aNumberedNameStaysANameWhenAQuotedTextFollows() {
        // the quoted append text may contain digits and demonstratives; only the head of the sentence is read
        assertEquals(ConversationReferent.Anchor(null), ConversationReferent.parse("それに『2番目の案』を追記して"))
        assertNull(ConversationReferent.parse("会議メモに『それを2番目に』を追記して"))
    }
}

/** Phase 8 (seen on the S20): the model may put the demonstrative itself into targetName; that whole phrase is a referent, a real title is not. */
class ConversationReferentPhraseTest {
    @org.junit.Test
    fun aDemonstrativeOrOrdinalAloneIsAReferentPhraseARealTitleIsNot() {
        listOf("それ", "これ", "その", "さっきの", "さっきのメモ", "さっきの日記", "前のやつ", "2番目", "２番目", "3件目", "最初の", "最後", "一番目").forEach {
            org.junit.Assert.assertTrue(it, ConversationReferent.isReferentPhrase(it))
        }
        listOf("それから", "これからの計画", "MemoRipple開発", "2番目の案", "最初の一歩", "その他", "さっきのメモ帳", "").forEach {
            org.junit.Assert.assertFalse(it, ConversationReferent.isReferentPhrase(it))
        }
    }

    // --- 「この〜」 (docs/CHAT_MEMO_CONTEXT.md, 2026-09-26): the selected memo's natural name ---

    @Test
    fun thisWithAKindWordNamesTheAnchor() {
        assertEquals(ConversationReferent.Anchor(io.github.cragcoffee.memoripple.domain.documents.DocumentKind.MEMO), ConversationReferent.parse("このメモを開いて"))
        assertEquals(ConversationReferent.Anchor(io.github.cragcoffee.memoripple.domain.documents.DocumentKind.MEMO), ConversationReferent.parse("このメモに『牛乳』を追記して"))
        assertEquals(ConversationReferent.Anchor(io.github.cragcoffee.memoripple.domain.documents.DocumentKind.JOURNAL), ConversationReferent.parse("この日記を開いて"))
        assertTrue(ConversationReferent.isReferentPhrase("このメモ"))
        assertTrue(ConversationReferent.isReferentPhrase("このメモに"))
    }

    @Test
    fun thisWithoutAKindWordIsNoReferentAndARealTitleStaysATitle() {
        listOf("このまちの記録を開いて", "このアイデアを開いて", "このメモ帳を開いて", "この本に『x』を追記して").forEach {
            assertNull(it, ConversationReferent.parse(it))
        }
        assertFalse(ConversationReferent.isReferentPhrase("このまちの記録"))
        assertFalse(ConversationReferent.isReferentPhrase("この"))
    }
}

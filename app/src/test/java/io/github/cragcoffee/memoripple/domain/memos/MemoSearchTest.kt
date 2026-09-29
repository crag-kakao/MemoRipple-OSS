package io.github.cragcoffee.memoripple.domain.memos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoSearchTest {

    private fun matches(query: String, title: String = "", body: String = "", tags: List<String> = emptyList()) =
        MemoSearch.matches(MemoSearch.parse(query), title, body, tags)

    @Test
    fun everyWordHasToMatchSoAddingAWordNarrows() {
        val body = "会議の資料をまとめる"

        assertTrue(matches("会議 資料", body = body))
        assertTrue(matches("資料 会議", body = body))
        assertFalse(matches("会議 議事録", body = body))
    }

    @Test
    fun wordsMayComeFromTheTitleAndTheBodyTogether() {
        assertTrue(matches("週報 提出", title = "週報", body = "金曜に提出する"))
    }

    @Test
    fun aQuotedPhraseStaysOneWord() {
        assertTrue(matches("\"会議 資料\"", body = "会議 資料を持参"))
        assertFalse(matches("\"会議 資料\"", body = "資料と会議"))
    }

    @Test
    fun aLeadingHyphenExcludes() {
        assertTrue(matches("会議 -中止", body = "会議を開く"))
        assertFalse(matches("会議 -中止", body = "会議は中止"))
    }

    @Test
    fun aHashLooksOnlyAtTags() {
        assertTrue(matches("#仕事", tags = listOf("仕事")))
        assertFalse(matches("#仕事", body = "仕事の話"))
    }

    @Test
    fun aPlainWordAlsoFindsATagBecauseATagIsPartOfTheMemo() {
        assertTrue(matches("仕事", tags = listOf("仕事")))
    }

    @Test
    fun halfWidthAndFullWidthFindEachOther() {
        assertTrue(matches("ﾒﾓ", body = "メモを書く"))
        assertTrue(matches("ABC", body = "ＡＢＣ"))
    }

    @Test
    fun decorationDoesNotBreakAPhraseThatSpansIt() {
        assertTrue(matches("これは太字です", body = "これは**太字**です"))
    }

    @Test
    fun anEmptyQueryMatchesEverything() {
        assertTrue(MemoSearch.parse("").isEmpty)
        assertTrue(matches("   ", body = "なんでも"))
    }

    @Test
    fun highlightsCoverTheWordsAResultActuallyContains() {
        val query = MemoSearch.parse("会議 -中止 #仕事")

        assertEquals(listOf("会議"), query.highlights)
    }

    @Test
    fun theSnippetIsTheLineThatWasHitNotTheTopOfTheMemo() {
        val body = "一行目\n二行目\n会議の資料をまとめる\n四行目"

        assertEquals("会議の資料をまとめる", MemoSearch.snippet(body, listOf("資料")))
    }

    @Test
    fun theSnippetIsCleanedOfItsMarkers() {
        val body = "# 見出し\n- [ ] **会議**の準備"

        assertEquals("会議の準備", MemoSearch.snippet(body, listOf("会議")))
    }

    @Test
    fun aLongLineIsWindowedAroundTheHit() {
        val body = "あ".repeat(200) + "会議" + "い".repeat(200)

        val snippet = MemoSearch.snippet(body, listOf("会議"))!!

        assertTrue(snippet.contains("会議"))
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.length < 100)
    }

    @Test
    fun thereIsNoSnippetWhenNothingWasHitInTheBody() {
        assertEquals(null, MemoSearch.snippet("本文", listOf("見つからない")))
        assertEquals(null, MemoSearch.snippet("本文", emptyList()))
    }
}

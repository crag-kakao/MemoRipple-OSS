package io.github.cragcoffee.memoripple.domain.outline

import io.github.cragcoffee.memoripple.domain.BodyReading
import io.github.cragcoffee.memoripple.domain.OutlineSymbolRole
import io.github.cragcoffee.memoripple.domain.WorkCommentParser
import io.github.cragcoffee.memoripple.domain.WorkCommentSyntax
import io.github.cragcoffee.memoripple.domain.comments.CommentLinkMarkers
import io.github.cragcoffee.memoripple.domain.comments.CommentLineModifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outline reading of a body must be a way of looking at the same text, not a copy of it:
 * whatever is written — prose, every symbol set, deep nesting, markers, modifiers, blank
 * lines, odd spacing — comes back character for character, and the lines it reads as
 * outline are exactly the lines the comment parser and the reading page read as outline.
 */
class OutlineTextTest {

    /** Every shape the existing syntax allows, plus the shapes writers actually produce. */
    private val fixtures: Map<String, String> = linkedMapOf(
        "empty" to "",
        "one prose line" to "今日は買い物に行く。",
        "prose with trailing newline" to "今日は買い物に行く。\n",
        "prose, outline and blanks mixed" to """
            今日は買い物に行く。

            - 食品
              - 牛乳
              - 卵

            帰りに郵便局へ行く。
        """.trimIndent(),
        "standard set, every role" to """
            ■ 見出し
            - 項目
            - [ ] やること
            - [x] 済んだこと
            > 補足
            ! 重要
            ? 疑問
        """.trimIndent(),
        "japanese set, every role" to """
            ◆ 見出し
            ・ 項目
            ☐ やること
            ☑ 済んだこと
            ※ 補足
            ★ 重要
            ？ 疑問
        """.trimIndent(),
        "emoji set, every role" to """
            📌 見出し
            • 項目
            ☐ やること
            ✅ 済んだこと
            💬 補足
            ⚠️ 重要
            ❓ 疑問
        """.trimIndent(),
        "sets mixed in one memo" to "■ 標準\n・ 日本語\n• 絵文字\n⚠ 選択子なし",
        "markdown headings, every level" to """
            # 一
            ## 二
            ### 三
            #### 四
            ##### 五
            ###### 六
            ####### 七つは見出しではない
        """.trimIndent(),
        "deep nesting" to """
            - 一
              - 二
                - 三
                  - 四
                    - 五
                      - 六
                        - 七
            - 戻る
        """.trimIndent(),
        "nesting under headings" to """
            ■ 章
              - 節
                ・ 項
              ■ 小見出し
            本文
        """.trimIndent(),
        "comment link markers" to "- 発端 [R1]\n  - 続き [R2] [R1]\n本文の中の [R3] も残る",
        "line-end modifiers" to """
            - 右へ →
            - 速く ←←
            - 固定 ↑
            - 下に固定 ↓
            - 全部 ←←← ++ ×3 {赤} ~ * ...
            - 完全固定 ↑↑
            - ループ ↺
            - ASCII <<- ^ vv oo
            - マーカーも [R1] ←← [R2]
        """.trimIndent(),
        "consecutive outline lines" to "- 一\n- 二\n- 三\n- 四",
        "blank lines everywhere" to "\n\n- 項目\n\n\n本文\n\n",
        "whitespace-only lines" to "- 一\n   \n\t\n- 二",
        "odd indentation" to " - 一つ空き\n   - 三つ空き\n\t- タブ\n  \t  - 混在",
        "ascii" to "# Title\n- item one\n  - [ ] task\n> note\nplain ascii line",
        "unicode" to "■ 🌊 波紋\n- Ünïcödé — “quotes” … 𝄞\n  - 한국어 · Ελληνικά",
        "inline markup and links" to "- **太字** と ==ハイライト== と ==red:赤==\n- [[別のメモ]] へのリンク\n[[先頭のリンク]] は本文",
        "ruby" to "- ｜戦《いくさ》の朝\n本文の｜漢字《かんじ》",
        "marker without its space is prose" to "#仕事のこと\n-1℃だった\n・箇条ではない",
        "crlf stays inside the line" to "- 一\r\n- 二\r\n",
        "trailing spaces are kept" to "- 一   \n本文  ",
    )

    @Test
    fun serializeOfParseReturnsTheTextCharacterForCharacter() {
        fixtures.forEach { (name, text) ->
            assertEquals(name, text, OutlineText.serialize(OutlineText.parse(text)))
        }
    }

    @Test
    fun parseOfSerializeReturnsTheSameDocument() {
        fixtures.forEach { (name, text) ->
            val document = OutlineText.parse(text)
            assertEquals(name, document, OutlineText.parse(OutlineText.serialize(document)))
        }
    }

    @Test
    fun everyLineBecomesOneEntryInOrderWithStableIds() {
        val document = OutlineText.parse("- 一\n\n本文\n  - 二")

        assertEquals(4, document.entries.size)
        assertEquals(listOf(1, 2, 3, 4), document.entries.map(OutlineEntry::id))
        assertEquals(5, document.nextId)
        assertTrue(document.entries[1] is OutlineBlank)
        assertEquals("本文", (document.entries[2] as OutlineNode).text)
    }

    @Test
    fun roleAndDepthAgreeWithTheSharedSyntaxRuleOnEveryLine() {
        fixtures.forEach { (name, text) ->
            val document = OutlineText.parse(text)
            text.split('\n').forEachIndexed { index, rawLine ->
                val entry = document.entries[index]
                val expected = WorkCommentSyntax.recognize(rawLine)
                if (rawLine.isBlank()) {
                    assertTrue("$name#$index should be blank", entry is OutlineBlank)
                    return@forEachIndexed
                }
                val node = entry as OutlineNode
                assertEquals("$name#$index role", expected?.type?.name?.let(::roleNamed), node.role)
                assertEquals("$name#$index depth", expected?.depth ?: 0, node.depth)
                assertEquals(
                    "$name#$index text after the marker",
                    expected?.text ?: rawLine.trimStart(),
                    node.text,
                )
            }
        }
    }

    @Test
    fun theMarkedLinesAreExactlyTheLinesTheCommentParserFlies() {
        fixtures.forEach { (name, text) ->
            val document = OutlineText.parse(text)
            val flown = WorkCommentParser().parseOutline(OutlineText.serialize(document))
            val marked = document.entries.filterIsInstance<OutlineNode>().filter { it.role != null }
            assertEquals("$name: one marked node per flown comment", flown.size, marked.size)
            flown.zip(marked).forEach { (line, node) ->
                assertEquals("$name: source line", document.entries.indexOf(node), line.sourceLine)
                assertEquals("$name: depth", line.depth, node.depth)
                assertEquals("$name: modifiers", line.modifiers, node.modifiers)
            }
        }
    }

    @Test
    fun theReadingPageSeesTheSameLinesAndDepths() {
        fixtures.forEach { (name, text) ->
            val nodes = OutlineText.parse(text).entries.filterIsInstance<OutlineNode>()
            val reading = BodyReading.lines(text)
            assertEquals("$name: line count", reading.size, nodes.size)
            reading.zip(nodes).forEach { (line, node) ->
                assertEquals("$name: depth", line.depth, node.depth)
                // The reading page splits on every line break kind, so a CR is not part of
                // its text; the outline keeps the CR so the body comes back unchanged.
                assertEquals("$name: readable text", line.text, node.readableText.trimEnd('\r'))
            }
        }
    }

    @Test
    fun markersModifiersAndLinksStayInsideTheTextUntouched() {
        val node = OutlineText.parse("  - 発端 [R1] と [[相手]] ←← [R2]").entries.single() as OutlineNode

        assertEquals("  ", node.indent)
        assertEquals("- ", node.marker)
        assertEquals("-", node.glyph)
        assertEquals(OutlineSymbolRole.ITEM, node.role)
        assertEquals("発端 [R1] と [[相手]] ←← [R2]", node.text)
        assertEquals(listOf(1, 2), CommentLinkMarkers.markersIn(node.text).map { it.number })
        assertEquals(CommentLineModifiers.strip(node.text).modifiers, node.modifiers)
        assertEquals("発端 [R1] と [[相手]] [R2]", node.readableText)
    }

    @Test
    fun aMarkdownHeadingKeepsItsHashesAndCountsThemAsDepth() {
        val node = OutlineText.parse("  ### 三").entries.single() as OutlineNode

        assertEquals("### ", node.marker)
        assertEquals(OutlineSymbolRole.HEADING, node.role)
        assertEquals(1 + 2, node.depth)
        assertEquals("  ### 三", node.toLine())
    }

    @Test
    fun proseHasNoMarkerAndNoRole() {
        val node = OutlineText.parse("ただの文").entries.single() as OutlineNode

        assertEquals("", node.marker)
        assertNull(node.role)
        assertEquals(0, node.depth)
    }

    private fun roleNamed(typeName: String): OutlineSymbolRole = OutlineSymbolRole.valueOf(typeName)
}

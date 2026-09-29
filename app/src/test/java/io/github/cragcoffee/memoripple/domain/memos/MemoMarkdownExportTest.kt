package io.github.cragcoffee.memoripple.domain.memos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoMarkdownExportTest {

    @Test
    fun theBodyLeavesExactlyAsItWasWritten() {
        val body = "# 見出し\n- [x] 済んだこと\n- [ ] これから\n**太字**と==red:色=="

        val rendered = MemoMarkdownExport.render(ExportableMemo("買い物", body))

        assertTrue(rendered.startsWith("# 買い物\n\n"))
        assertTrue(rendered.contains(body))
    }

    @Test
    fun aHeadingLeavesAsTheHashesItIsEquivalentTo() {
        val rendered = MemoMarkdownExport.render(
            ExportableMemo("題", "■ 見出し\n本文\n  ■ 子見出し\n- 項目"),
        )

        // Depth becomes the number of hashes, so the indent is carried by them instead.
        assertTrue(rendered.contains("# 見出し\n本文\n## 子見出し\n- 項目"))
    }

    @Test
    fun aHeadingAlreadyWrittenWithHashesIsLeftAsItIs() {
        val rendered = MemoMarkdownExport.render(ExportableMemo("題", "## 今週\n本文"))

        assertTrue(rendered.contains("## 今週\n本文"))
    }

    @Test
    fun nothingOtherThanTheHeadingIsRewritten() {
        val body = "- [x] 済んだこと\n> 補足\n! 重要\n? 疑問\n**太字**と[[リンク]]"

        assertTrue(MemoMarkdownExport.render(ExportableMemo("題", body)).contains(body))
    }

    @Test
    fun aHeadingDeeperThanMarkdownGoesStopsAtTheDeepestItHas() {
        val rendered = MemoMarkdownExport.render(
            ExportableMemo("題", " ".repeat(20) + "■ とても深い"),
        )

        assertTrue(rendered.contains("###### とても深い"))
    }

    @Test
    fun tagsLeaveAsHashtagsBecauseThatIsWhatOtherEditorsRead() {
        val rendered = MemoMarkdownExport.render(
            ExportableMemo("題", "本文", listOf("仕事", "来週 まとめ")),
        )

        assertTrue(rendered.contains("#仕事 #来週_まとめ"))
    }

    @Test
    fun anUntitledMemoStillGetsAHeading() {
        assertTrue(MemoMarkdownExport.render(ExportableMemo("", "本文")).startsWith("# 無題のメモ"))
    }

    @Test
    fun anEmptyBodyDoesNotLeaveADanglingBlankSection() {
        assertEquals("# 題\n", MemoMarkdownExport.render(ExportableMemo("題", "")))
    }

    @Test
    fun severalMemosAreSeparatedByARule() {
        val rendered = MemoMarkdownExport.renderAll(
            listOf(ExportableMemo("一", "本文一"), ExportableMemo("二", "本文二")),
        )

        assertEquals("# 一\n\n本文一\n\n---\n\n# 二\n\n本文二\n", rendered)
    }

    @Test
    fun theFileNameLosesWhatAFileSystemRefuses() {
        assertEquals("会議_資料.md", MemoMarkdownExport.fileName("会議/資料"))
        assertEquals("a_b.md", MemoMarkdownExport.fileName("a:b"))
    }

    @Test
    fun aBlankTitleStillProducesAUsableFileName() {
        assertEquals("無題のメモ.md", MemoMarkdownExport.fileName("   "))
        assertEquals("無題のメモ.md", MemoMarkdownExport.fileName("..."))
    }

    @Test
    fun aVeryLongTitleIsShortenedRatherThanRejected() {
        val name = MemoMarkdownExport.fileName("あ".repeat(200))

        assertEquals(60 + MemoMarkdownExport.EXTENSION.length, name.length)
        assertTrue(name.endsWith(".md"))
    }

    @Test
    fun aCollectionIsStampedSoRepeatedExportsDoNotCollide() {
        assertEquals("メモ_2026-08-27.md", MemoMarkdownExport.collectionFileName("2026-08-27"))
    }

    // --- An outline's front matter (docs/OUTLINE_EXPORT_IMPORT.md) ---

    @Test
    fun anOutlineOpensWithTheOneFrontMatterLine() {
        assertEquals(
            "---\nmemoripple: outline\n---\n\n# 計画\n\n- 旅行\n  - 京都\n",
            MemoMarkdownExport.render(ExportableMemo("計画", "- 旅行\n  - 京都", outline = true)),
        )
    }

    @Test
    fun aMemoIsByteForByteWhatItAlwaysWas() {
        assertEquals(
            "# 買い物\n\n牛乳\n卵\n\n#生活\n",
            MemoMarkdownExport.render(ExportableMemo("買い物", "牛乳\n卵", listOf("生活"))),
        )
        assertEquals("# 無題のメモ\n", MemoMarkdownExport.render(ExportableMemo("", "")))
    }

    @Test
    fun aCollectionCarriesNoFrontMatter() {
        val all = MemoMarkdownExport.renderAll(listOf(ExportableMemo("a", "x", outline = true), ExportableMemo("b", "y")))
        assertEquals("# a\n\nx\n\n---\n\n# b\n\ny\n", all)
    }
}

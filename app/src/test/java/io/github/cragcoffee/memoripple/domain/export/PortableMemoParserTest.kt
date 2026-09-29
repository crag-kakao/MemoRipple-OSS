package io.github.cragcoffee.memoripple.domain.export

import io.github.cragcoffee.memoripple.domain.documents.DocumentTitles
import io.github.cragcoffee.memoripple.domain.export.PortableMarkdownRenderer.PhotoOutcome
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableMemoParserTest {

    private val zone = ZoneId.of("Asia/Tokyo")

    private fun rendered(memo: PortableMemo): String {
        val planned = PortableExportPlanner.plan(
            PortableSnapshot(listOf(memo), emptyList(), emptyList()),
            includeTrash = true,
            zone = zone,
        ).allMemos.single()
        return PortableMarkdownRenderer.memoMarkdown(
            planned,
            planned.photos.map { PhotoOutcome(it, copied = true) },
            zone,
        )
    }

    @Test
    fun whatTheRendererWritesTheParserReadsBack() {
        val memo = PortableMemo(
            id = 7,
            title = "旅行の準備",
            body = "本文の一行目。\n\n- [ ] 充電器\n[[構成案]]も見る。",
            createdAt = 1_755_651_000_000,
            updatedAt = 1_756_600_800_000,
            favorite = true,
            pinned = false,
            shelf = PortableShelf.ACTIVE,
            tags = listOf("旅行", "予定"),
            comments = listOf(PortableComment("読み返す", emptyList())),
            photos = listOf(
                PortablePhoto("a".repeat(64), "image/jpeg", 5),
                PortablePhoto("b".repeat(64), "image/png", 5),
            ),
        )
        val parsed = requireNotNull(PortableMemoParser.parse(rendered(memo)))
        assertEquals("旅行の準備", parsed.title)
        assertEquals(memo.body, parsed.body)
        assertEquals(listOf("旅行", "予定"), parsed.tags)
        assertEquals(listOf("photo-01.jpg", "photo-02.png"), parsed.photoFileNames)
    }

    @Test
    fun anUntitledMemoRoundTripsToABlankTitle() {
        val memo = PortableMemo(
            id = 1, title = "", body = "本文だけ。", createdAt = 0, updatedAt = 0,
            favorite = false, pinned = false, shelf = PortableShelf.ACTIVE,
            tags = emptyList(), comments = emptyList(), photos = emptyList(),
        )
        val parsed = requireNotNull(PortableMemoParser.parse(rendered(memo)))
        assertEquals("", parsed.title)
        assertEquals("本文だけ。", parsed.body)
    }

    @Test
    fun theExportSectionsNeverEnterTheBody() {
        val memo = PortableMemo(
            id = 2, title = "x", body = "本文。", createdAt = 0, updatedAt = 0,
            favorite = false, pinned = false, shelf = PortableShelf.ACTIVE,
            tags = emptyList(),
            comments = listOf(PortableComment("コメントは本文ではない", listOf("色: 赤"))),
            photos = listOf(PortablePhoto("a".repeat(64), "image/jpeg", 1)),
        )
        val parsed = requireNotNull(PortableMemoParser.parse(rendered(memo)))
        assertEquals("本文。", parsed.body)
    }

    @Test
    fun somethingThatIsNotAMemoFileIsRefused() {
        assertNull(PortableMemoParser.parse("ただのテキスト"))
        assertNull(PortableMemoParser.parse(""))
    }

    // --- The kind travels (docs/OUTLINE_EXPORT_IMPORT.md) ---

    private fun plain(title: String, body: String, outline: Boolean) = PortableMemo(
        id = 3, title = title, body = body, createdAt = 0, updatedAt = 0,
        favorite = false, pinned = false, shelf = PortableShelf.ACTIVE,
        tags = emptyList(), comments = emptyList(), photos = emptyList(), outline = outline,
    )

    @Test
    fun anOutlineSaysSoInItsListAndIsReadBackAsAnOutline() {
        val body = "- 旅行\n  - [ ] 宿を取る\n- 帰る"
        val markdown = rendered(plain("計画", body, outline = true))
        assertTrue(markdown.lines().contains("- 種類: outline"))
        val parsed = requireNotNull(PortableMemoParser.parse(markdown))
        assertTrue(parsed.outline)
        assertEquals("計画", parsed.title)
        assertEquals(body, parsed.body)
    }

    @Test
    fun aMemoFileIsByteForByteWhatItAlwaysWas() {
        val markdown = rendered(plain("買い物", "牛乳\n卵", outline = false))
        assertEquals(
            "# 買い物\n\n- 作成: 1970-01-01 09:00\n- 更新: 1970-01-01 09:00\n- お気に入り: いいえ\n- ピン留め: いいえ\n\n---\n\n牛乳\n卵\n",
            markdown,
        )
        assertFalse(markdown.contains("種類"))
        assertFalse(requireNotNull(PortableMemoParser.parse(markdown)).outline)
    }

    @Test
    fun aKindThisDoesNotKnowIsAMemoAndNothingIsRefused() {
        val file = "# 何か\n\n- 作成: 1970-01-01 09:00\n- 種類: journal\n\n---\n\n本文\n"
        val parsed = requireNotNull(PortableMemoParser.parse(file))
        assertFalse(parsed.outline)
        assertEquals("本文", parsed.body)
        // An empty or odd value is the same: a memo, never a crash.
        assertFalse(requireNotNull(PortableMemoParser.parse("# 何か\n\n- 種類:\n- 種類: OUTLINE!\n\n---\n\n本文\n")).outline)
    }

    @Test
    fun aKindWrittenInTheBodyIsTheBodysNotTheFiles() {
        val file = "# 何か\n\n- 作成: 1970-01-01 09:00\n\n---\n\n- 種類: outline\n"
        val parsed = requireNotNull(PortableMemoParser.parse(file))
        assertFalse(parsed.outline)
        assertEquals("- 種類: outline", parsed.body)
    }

    @Test
    fun anUntitledOutlineRoundTripsToABlankTitle() {
        val markdown = rendered(plain("", "- 行", outline = true))
        assertTrue(markdown.startsWith("# ${DocumentTitles.UNTITLED_OUTLINE}\n"))
        assertEquals("", requireNotNull(PortableMemoParser.parse(markdown)).title)
        // A file written before the name changed (2026-09-25) reads back untitled too.
        assertEquals("", requireNotNull(PortableMemoParser.parse(markdown.replaceFirst(DocumentTitles.UNTITLED_OUTLINE, "無題のアウトライン"))).title)
        // A memo really called that keeps its name.
        assertEquals(DocumentTitles.UNTITLED_OUTLINE, requireNotNull(PortableMemoParser.parse(rendered(plain(DocumentTitles.UNTITLED_OUTLINE, "x", outline = false)))).title)
    }
}

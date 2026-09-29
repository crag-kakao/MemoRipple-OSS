package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.domain.WorkCommentSyntax
import io.github.cragcoffee.memoripple.domain.WorkLineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoMarkdownImportTest {

    @Test
    fun whatWasExportedComesBackAsItLeft() {
        val exported = MemoMarkdownExport.renderAll(
            listOf(
                ExportableMemo("買い物", "- [x] 牛乳\n- [ ] パン", listOf("生活")),
                ExportableMemo("会議", "# 議題\n**重要**な話", emptyList()),
            ),
        )

        val imported = MemoMarkdownImport.parse(exported)

        assertEquals(2, imported.size)
        assertEquals("買い物", imported[0].title)
        assertEquals("- [x] 牛乳\n- [ ] パン", imported[0].body)
        assertEquals(listOf("生活"), imported[0].tagNames)
        assertEquals("会議", imported[1].title)
        assertEquals("# 議題\n**重要**な話", imported[1].body)
        assertTrue(imported[1].tagNames.isEmpty())
    }

    @Test
    fun aHeadingSurvivesTheRoundTripAsTheSameHeading() {
        val exported = MemoMarkdownExport.render(
            ExportableMemo("題", "■ 見出し\n本文\n  ■ 子見出し"),
        )

        val body = MemoMarkdownImport.parse(exported).single().body

        // The mark changed on the way out, but the document did not: same headings, same depths.
        assertEquals("# 見出し\n本文\n## 子見出し", body)
        assertEquals(
            listOf(WorkLineType.HEADING, WorkLineType.PLAIN, WorkLineType.HEADING),
            body.lines().map { WorkCommentSyntax.recognize(it)?.type ?: WorkLineType.PLAIN },
        )
        assertEquals(listOf(0, 1), body.lines().mapNotNull { WorkCommentSyntax.recognize(it) }
            .filter { it.type == WorkLineType.HEADING }.map { it.depth })
    }

    @Test
    fun theTitleLineIsTheOnlyHeadingTakenOutOfTheBody() {
        val imported = MemoMarkdownImport.parse("# タイトル\n\n# 本文の見出し\n中身").single()

        assertEquals("タイトル", imported.title)
        assertEquals("# 本文の見出し\n中身", imported.body)
    }

    @Test
    fun aMarkdownFileFromElsewhereIsOneMemo() {
        val imported = MemoMarkdownImport.parse("# タイトル\n\n本文\n\n## 小見出し\nつづき")

        assertEquals(1, imported.size)
        assertEquals("タイトル", imported.single().title)
        assertEquals("本文\n\n## 小見出し\nつづき", imported.single().body)
    }

    @Test
    fun aFileWithoutAHeadingStillBecomesAMemo() {
        val imported = MemoMarkdownImport.parse("見出しの無い覚え書き")

        assertEquals("", imported.single().title)
        assertEquals("見出しの無い覚え書き", imported.single().body)
    }

    @Test
    fun frontMatterDescribesTheFileAndDoesNotBecomeABody() {
        val imported = MemoMarkdownImport.parse("---\ntitle: foo\ntags: [a]\n---\n# 本題\n中身")

        assertEquals(1, imported.size)
        assertEquals("本題", imported.single().title)
        assertEquals("中身", imported.single().body)
    }

    @Test
    fun aRuleWrittenAnyOfTheUsualWaysSeparatesMemos() {
        val imported = MemoMarkdownImport.parse("# 一\n本文\n\n***\n\n# 二\n本文")

        assertEquals(listOf("一", "二"), imported.map(ImportedMemo::title))
    }

    @Test
    fun onlyAClosingLineOfPureHashtagsBecomesTags() {
        val withTags = MemoMarkdownImport.parse("# 題\n本文\n\n#仕事 #来週").single()
        assertEquals(listOf("仕事", "来週"), withTags.tagNames)
        assertEquals("本文", withTags.body)

        // A heading, or a line that merely mentions a hashtag, stays part of the body.
        val withoutTags = MemoMarkdownImport.parse("# 題\n本文\n\n# 見出し").single()
        assertTrue(withoutTags.tagNames.isEmpty())
        assertEquals("本文\n\n# 見出し", withoutTags.body)

        val mentioned = MemoMarkdownImport.parse("# 題\n#仕事 のこと").single()
        assertTrue(mentioned.tagNames.isEmpty())
    }

    @Test
    fun emptyChunksAreSkippedRatherThanBecomingBlankMemos() {
        val imported = MemoMarkdownImport.parse("# 一\n本文\n\n---\n\n---\n\n# 二\n本文")

        assertEquals(listOf("一", "二"), imported.map(ImportedMemo::title))
    }

    @Test
    fun nothingReadableGivesNothing() {
        assertTrue(MemoMarkdownImport.parse("").isEmpty())
        assertTrue(MemoMarkdownImport.parse("   \n\n  ").isEmpty())
        assertTrue(MemoMarkdownImport.parse("---\n---\n").isEmpty())
    }

    @Test
    fun windowsLineEndingsDoNotLeaveStrayCharacters() {
        val imported = MemoMarkdownImport.parse("# 題\r\n\r\n一行目\r\n二行目").single()

        assertEquals("題", imported.title)
        assertEquals("一行目\n二行目", imported.body)
    }

    @Test
    fun aFileIsNotAllowedToBecomeMoreMemosThanThePolicyAllows() {
        val many = (1..MemoMarkdownImport.MAX_MEMOS + 20)
            .joinToString("\n\n---\n\n") { "# 題$it\n本文" }

        assertEquals(MemoMarkdownImport.MAX_MEMOS, MemoMarkdownImport.parse(many).size)
    }

    // --- An outline's front matter (docs/OUTLINE_EXPORT_IMPORT.md) ---

    @Test
    fun anOutlineExportedAloneComesBackAsOneOutline() {
        val body = "- 旅行\n  - [ ] 宿を取る\n\n---\n\n- 帰る"
        val exported = MemoMarkdownExport.render(ExportableMemo("計画", body, listOf("旅"), outline = true))
        val imported = MemoMarkdownImport.parse(exported).single()
        assertTrue(imported.outline)
        assertEquals("計画", imported.title)
        // A rule inside an outline is one of its lines, not the start of another document.
        assertEquals(body, imported.body)
        assertEquals(listOf("旅"), imported.tagNames)
    }

    @Test
    fun aMemoExportedAloneStaysAMemo() {
        val imported = MemoMarkdownImport.parse(MemoMarkdownExport.render(ExportableMemo("買い物", "牛乳"))).single()
        assertFalse(imported.outline)
        assertEquals("買い物", imported.title)
    }

    @Test
    fun aFileWithNoFrontMatterIsMemosAsBefore() {
        val imported = MemoMarkdownImport.parse("# 計画\n- 旅行\n  - 京都")
        assertFalse(imported.single().outline)
    }

    @Test
    fun theOneLineIsReadWhereverItSitsInTheBlock() {
        val imported = MemoMarkdownImport.parse("---\ntitle: x\nmemoripple: \"outline\"\n---\n# 計画\n- 行").single()
        assertTrue(imported.outline)
        assertEquals("- 行", imported.body)
    }

    @Test
    fun unrelatedFrontMatterIsNotAnOutline() {
        listOf(
            "---\ntitle: 計画\ntags: [a]\n---\n# 計画\n本文",
            "---\ntype: outline\n---\n# 計画\n本文",
            "---\nnote: memoripple: outline\n---\n# 計画\n本文",
            "---\nmemoripple_kind: outline\n---\n# 計画\n本文",
        ).forEach { file ->
            val imported = MemoMarkdownImport.parse(file)
            assertEquals(file, 1, imported.size)
            assertFalse(file, imported.single().outline)
            assertEquals(file, "本文", imported.single().body)
        }
    }

    @Test
    fun oddMemoRippleValuesAreMemosAndNeverACrash() {
        listOf(
            "---\nmemoripple: journal\n---\n# 題\n本文",
            "---\nmemoripple:\n---\n# 題\n本文",
            "---\nmemoripple: outline outline\n---\n# 題\n本文",
            "---\nmemoripple\n---\n# 題\n本文",
        ).forEach { file ->
            assertFalse(file, MemoMarkdownImport.parse(file).single().outline)
        }
        // An unclosed block is not front matter at all; the text is read as it always was.
        MemoMarkdownImport.parse("---\nmemoripple: outline\n# 題\n本文").forEach { assertFalse(it.outline) }
        // Front matter and nothing else is no document.
        assertTrue(MemoMarkdownImport.parse("---\nmemoripple: outline\n---\n").isEmpty())
    }

    @Test
    fun anUntitledOutlineComesBackUntitled() {
        val imported = MemoMarkdownImport.parse(MemoMarkdownExport.render(ExportableMemo("", "- 行", outline = true))).single()
        assertTrue(imported.outline)
        assertEquals("", imported.title)
        assertEquals("- 行", imported.body)
        // A file written before the name changed (2026-09-25) reads back untitled too.
        assertEquals("", MemoMarkdownImport.parse("---\nmemoripple: outline\n---\n\n# 無題のアウトライン\n\n- 行\n").single().title)
    }
}

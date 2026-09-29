package io.github.cragcoffee.memoripple.domain.export

import io.github.cragcoffee.memoripple.domain.export.PortableMarkdownRenderer.PhotoOutcome
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableMarkdownRendererTest {

    private val zone = ZoneId.of("Asia/Tokyo")

    private fun plannedMemo(
        memo: PortableMemo,
    ): PortableExportPlanner.PlannedMemo = PortableExportPlanner.plan(
        PortableSnapshot(listOf(memo), emptyList(), emptyList()),
        includeTrash = true,
        zone = zone,
    ).allMemos.single()

    @Test
    fun memoMarkdownCarriesMetadataBodyPhotosAndComments() {
        val memo = PortableMemo(
            id = 7,
            title = "旅行の準備",
            body = "■ 持ち物\n- [ ] 充電器",
            createdAt = 1_755_651_000_000, // 2025-08-20 (JST)
            updatedAt = 1_756_600_800_000,
            favorite = true,
            pinned = false,
            shelf = PortableShelf.ACTIVE,
            tags = listOf("旅行", "予定"),
            comments = listOf(
                PortableComment("そのまま", emptyList()),
                PortableComment("目立たせる", listOf("色: ピンク", "大きさ: 大きめ")),
            ),
            photos = listOf(PortablePhoto("a".repeat(64), "image/jpeg", 5)),
        )
        val planned = plannedMemo(memo)
        val outcomes = planned.photos.map { PhotoOutcome(it, copied = true) }
        val markdown = PortableMarkdownRenderer.memoMarkdown(planned, outcomes, zone)

        assertTrue(markdown.startsWith("# 旅行の準備\n"))
        assertTrue(markdown.contains("- お気に入り: はい"))
        assertTrue(markdown.contains("- ピン留め: いいえ"))
        assertTrue(markdown.contains("- タグ: 旅行、予定"))
        // The app's own heading mark leaves as ordinary Markdown (the same conversion the
        // single-memo export has always used); the checkbox stays itself.
        assertTrue(markdown.contains("\n# 持ち物\n"))
        assertTrue(markdown.contains("- [ ] 充電器"))
        assertTrue(markdown.contains("![写真 1](photos/photo-01.jpg)"))
        // Comments read in order; only the second carries expression notes.
        val commentBlock = markdown.substringAfter("## コメント")
        assertTrue(commentBlock.contains("1. そのまま"))
        assertTrue(commentBlock.contains("2. 目立たせる"))
        assertTrue(commentBlock.contains("   - 色: ピンク"))
        assertFalse(commentBlock.lines().any { it.contains("1. そのまま") && it.contains("色") })
        // Nothing internal leaks.
        assertFalse(markdown.contains("playbackOrder"))
        assertFalse(markdown.contains("rtl"))
    }

    @Test
    fun aMissingPhotoBecomesASentenceNotABrokenImage() {
        val memo = PortableMemo(
            id = 1, title = "x", body = "", createdAt = 0, updatedAt = 0,
            favorite = false, pinned = false, shelf = PortableShelf.ACTIVE,
            tags = emptyList(), comments = emptyList(),
            photos = listOf(
                PortablePhoto("a".repeat(64), "image/jpeg", 1),
                PortablePhoto("b".repeat(64), "image/png", 1),
            ),
        )
        val planned = plannedMemo(memo)
        val outcomes = listOf(
            PhotoOutcome(planned.photos[0], copied = false),
            PhotoOutcome(planned.photos[1], copied = true),
        )
        val markdown = PortableMarkdownRenderer.memoMarkdown(planned, outcomes, zone)
        assertTrue(markdown.contains("> 写真1は読み込めなかったため、書き出されませんでした。"))
        assertTrue(markdown.contains("![写真 2](photos/photo-02.png)"))
        assertFalse(markdown.contains("photo-01"))
    }

    @Test
    fun diaryMarkdownShowsDateStateAndOnlyOpenedFutureComments() {
        val diary = PortableDiary(
            id = 3,
            epochDay = java.time.LocalDate.of(2026, 8, 31).toEpochDay(),
            body = "今日の記録。",
            stateLabel = "確定済み",
            photos = emptyList(),
            futureComments = listOf(PortableFutureComment("未来より", listOf("表示: 上に固定"))),
        )
        val planned = PortableExportPlanner.plan(
            PortableSnapshot(emptyList(), listOf(diary), emptyList()),
            includeTrash = false,
            zone = zone,
        ).diaries.single()
        val markdown = PortableMarkdownRenderer.diaryMarkdown(planned, emptyList(), zone)
        assertTrue(markdown.startsWith("# 2026-08-31 の日記"))
        assertTrue(markdown.contains("- 状態: 確定済み"))
        assertTrue(markdown.contains("今日の記録。"))
        assertTrue(markdown.contains("## 開封済みの未来コメント"))
        assertTrue(markdown.contains("1. 未来より"))
        assertTrue(markdown.contains("   - 表示: 上に固定"))
    }

    @Test
    fun noteReadmeLinksEveryEpisodeUnderItsChapter() {
        val note = PortableNote(
            id = 5, title = "Android学習", subtitle = "手を動かす", createdAt = 0, updatedAt = 0,
            coverPhoto = null,
            sections = listOf(
                PortableNoteSection(null, listOf(PortableEpisode(1, 1, "はじまり", "x", emptyList()))),
                PortableNoteSection("基礎", listOf(PortableEpisode(2, 2, "変数", "y", emptyList()))),
            ),
        )
        val planned = PortableExportPlanner.plan(
            PortableSnapshot(emptyList(), emptyList(), listOf(note)),
            includeTrash = false,
            zone = zone,
        ).notes.single()
        val readme = PortableMarkdownRenderer.noteReadme(planned, null, zone)
        assertTrue(readme.startsWith("# Android学習"))
        assertTrue(readme.contains("手を動かす"))
        assertTrue(readme.contains("### 基礎"))
        assertTrue(readme.contains("- [第1話 はじまり](episodes/01-はじまり.md)"))
        assertTrue(readme.contains("- [第2話 変数](episodes/02-変数.md)"))

        val episode = planned.episodeFiles.last()
        val body = PortableMarkdownRenderer.episodeMarkdown(episode, emptyList())
        assertTrue(body.startsWith("# 第2話 変数"))
        assertTrue(body.contains("- 章: 基礎"))
    }

    @Test
    fun episodePhotosLinkOutOfTheEpisodesFolder() {
        val note = PortableNote(
            id = 5, title = "n", subtitle = "", createdAt = 0, updatedAt = 0, coverPhoto = null,
            sections = listOf(
                PortableNoteSection(
                    null,
                    listOf(
                        PortableEpisode(
                            1, 3, "話", "x",
                            listOf(PortablePhoto("a".repeat(64), "image/jpeg", 1)),
                        ),
                    ),
                ),
            ),
        )
        val planned = PortableExportPlanner.plan(
            PortableSnapshot(emptyList(), emptyList(), listOf(note)),
            includeTrash = false,
            zone = zone,
        ).notes.single()
        val file = planned.episodeFiles.single()
        assertEquals("../photos/03-photo-01.jpg", file.photos.single().markdownRef)
        val markdown = PortableMarkdownRenderer.episodeMarkdown(
            file,
            file.photos.map { PhotoOutcome(it, copied = true) },
        )
        assertTrue(markdown.contains("![写真 1](../photos/03-photo-01.jpg)"))
    }

    @Test
    fun indexWalksEveryShelfInOrder() {
        val plan = PortableExportPlanner.plan(
            PortableSnapshot(
                memos = listOf(
                    PortableMemo(1, "使う方", "", 0, 10, false, false, PortableShelf.ACTIVE, emptyList(), emptyList(), emptyList()),
                    PortableMemo(2, "寝かせた方", "", 0, 5, false, false, PortableShelf.ARCHIVED, emptyList(), emptyList(), emptyList()),
                    PortableMemo(3, "捨てた方", "", 0, 1, false, false, PortableShelf.TRASHED, emptyList(), emptyList(), emptyList()),
                ),
                diaries = listOf(
                    PortableDiary(1, java.time.LocalDate.of(2026, 8, 31).toEpochDay(), "", "下書き", emptyList(), emptyList()),
                ),
                notes = listOf(PortableNote(9, "帳面", "", 0, 0, null, emptyList())),
            ),
            includeTrash = true,
            zone = zone,
        )
        val index = PortableMarkdownRenderer.index(plan)
        assertTrue(index.startsWith("# MemoRipple 書き出し"))
        assertTrue(index.indexOf("### 使用中") < index.indexOf("### アーカイブ"))
        assertTrue(index.contains("### 2026年"))
        // createdAt 0 is 1970-01-01 09:00 in Asia/Tokyo: the entry's folder is its minute and id.
        assertTrue(index.contains("- [2026-08-31 09:00](diaries/2026/2026-08-31/0900-1/diary.md)"))
        assertTrue(index.contains("## ノート"))
        assertTrue(index.contains("## ゴミ箱"))
        // Links are root-relative: no leading slash, no ROOT prefix, no traversal.
        Regex("""\]\(([^)]+)\)""").findAll(index).forEach { match ->
            val link = match.groupValues[1]
            assertFalse(link.startsWith("/"))
            assertFalse(link.contains(".."))
            assertFalse(link.startsWith("MemoRipple-Export"))
        }
    }

    @Test
    fun readmeStatesTheContractInPlainLanguage() {
        val readme = PortableMarkdownRenderer.readme("2026-08-31 12:00")
        assertTrue(readme.contains("MemoRipple Portable Export"))
        assertTrue(readme.contains("Format 1"))
        assertTrue(readme.contains("完全復元用バックアップではありません"))
        assertTrue(readme.contains("暗号化されていません"))
        assertTrue(readme.contains("未開封の未来コメントはプライバシー保護のため書き出していません"))
        assertTrue(readme.contains("位置情報などのメタデータ"))
    }

    @Test
    fun expressionNotesShowOnlyWhatDiffersFromTheDefaults() {
        assertTrue(
            PortableExpressionNotes.forComment(
                color = "default", size = "standard", emphasis = "normal", motionMode = "flow",
                speed = "standard", placement = "auto", flowDirection = "rtl", flowEffect = "straight",
            ).isEmpty(),
        )
        assertEquals(
            listOf("色: ピンク", "大きさ: 大きめ", "動き: 波"),
            PortableExpressionNotes.forComment(
                color = "pink", size = "large", emphasis = "normal", motionMode = "flow",
                speed = "standard", placement = "auto", flowDirection = "rtl", flowEffect = "wave",
            ),
        )
        // A malformed storage id falls back to the default and prints nothing.
        assertTrue(
            PortableExpressionNotes.forComment(
                color = "no-such", size = "no-such", emphasis = "no-such", motionMode = "no-such",
            ).isEmpty(),
        )
    }
}

package io.github.cragcoffee.memoripple.domain.export

import io.github.cragcoffee.memoripple.domain.export.PortableExportPlanner.PlannedDiary
import io.github.cragcoffee.memoripple.domain.export.PortableExportPlanner.PlannedEpisodeFile
import io.github.cragcoffee.memoripple.domain.export.PortableExportPlanner.PlannedMemo
import io.github.cragcoffee.memoripple.domain.export.PortableExportPlanner.PlannedNote
import io.github.cragcoffee.memoripple.domain.export.PortableExportPlanner.PlannedPhoto
import io.github.cragcoffee.memoripple.domain.export.PortableExportPlanner.PortableExportPlan
import io.github.cragcoffee.memoripple.domain.documents.DocumentTitles
import io.github.cragcoffee.memoripple.domain.memos.MemoMarkdownExport
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Every Markdown file in the portable ZIP comes from here. Bodies pass through the same
 * conversion the single-memo export has always used — headings become hashes, everything else
 * stays exactly as written; nothing is reworded, summarized, or run through anything clever.
 * Photos appear as ordinary relative links, and a photo that could not be copied appears as a
 * plain sentence instead of a broken image.
 */
object PortableMarkdownRenderer {

    /** What became of one planned photo, decided by the copier before the Markdown is written. */
    data class PhotoOutcome(val planned: PlannedPhoto, val copied: Boolean)

    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun stamp(epochMillis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(dateTime)

    fun memoMarkdown(
        planned: PlannedMemo,
        photoOutcomes: List<PhotoOutcome>,
        zone: ZoneId,
    ): String = buildString {
        val memo = planned.memo
        append("# ").append(memo.title.ifBlank { if (memo.outline) DocumentTitles.UNTITLED_OUTLINE else DocumentTitles.UNTITLED_MEMO }).append("\n\n")
        append("- 作成: ").append(stamp(memo.createdAt, zone)).append('\n')
        append("- 更新: ").append(stamp(memo.updatedAt, zone)).append('\n')
        append("- お気に入り: ").append(if (memo.favorite) "はい" else "いいえ").append('\n')
        append("- ピン留め: ").append(if (memo.pinned) "はい" else "いいえ").append('\n')
        if (memo.tags.isNotEmpty()) {
            append("- タグ: ").append(memo.tags.joinToString("、")).append('\n')
        }
        // An outline says what it is, as one more line of the list — a fixed token, read back by
        // the import (docs/OUTLINE_EXPORT_IMPORT.md). A memo's file has no such line: unchanged.
        if (memo.outline) {
            append("- ").append(PortableMemoParser.KIND_LABEL).append(": ").append(PortableMemoParser.KIND_OUTLINE).append('\n')
        }
        append("\n---\n\n")
        if (memo.body.isNotBlank()) {
            append(MemoMarkdownExport.markdownBody(memo.body).trimEnd()).append('\n')
        }
        appendPhotoSection(photoOutcomes)
        if (memo.comments.isNotEmpty()) {
            append("\n## コメント\n\n")
            memo.comments.forEachIndexed { index, comment ->
                append(index + 1).append(". ").append(singleLine(comment.text)).append('\n')
                comment.expressionNotes.forEach { note ->
                    append("   - ").append(note).append('\n')
                }
            }
        }
    }

    fun diaryMarkdown(
        planned: PlannedDiary,
        photoOutcomes: List<PhotoOutcome>,
        zone: ZoneId,
    ): String = buildString {
        val diary = planned.diary
        append("# ").append(planned.dateLabel).append(" の日記\n\n")
        append("- 状態: ").append(diary.stateLabel).append('\n')
        append("\n---\n\n")
        if (diary.body.isNotBlank()) {
            append(diary.body.trimEnd()).append('\n')
        }
        appendPhotoSection(photoOutcomes)
        if (diary.futureComments.isNotEmpty()) {
            append("\n## 開封済みの未来コメント\n\n")
            diary.futureComments.forEachIndexed { index, comment ->
                append(index + 1).append(". ").append(singleLine(comment.text)).append('\n')
                comment.expressionNotes.forEach { note ->
                    append("   - ").append(note).append('\n')
                }
            }
        }
    }

    fun episodeMarkdown(
        planned: PlannedEpisodeFile,
        photoOutcomes: List<PhotoOutcome>,
    ): String = buildString {
        val episode = planned.episode
        append("# 第").append(episode.number).append("話 ")
            .append(episode.title.ifBlank { "無題" }).append("\n\n")
        planned.sectionTitle?.let { append("- 章: ").append(it).append('\n') }
        append("\n---\n\n")
        if (episode.body.isNotBlank()) {
            append(MemoMarkdownExport.markdownBody(episode.body).trimEnd()).append('\n')
        }
        appendPhotoSection(photoOutcomes)
    }

    fun noteReadme(
        planned: PlannedNote,
        coverOutcome: PhotoOutcome?,
        zone: ZoneId,
    ): String = buildString {
        val note = planned.note
        append("# ").append(note.title.ifBlank { "無題のノート" }).append("\n\n")
        if (note.subtitle.isNotBlank()) append(note.subtitle).append("\n\n")
        append("- 作成: ").append(stamp(note.createdAt, zone)).append('\n')
        append("- 更新: ").append(stamp(note.updatedAt, zone)).append('\n')
        if (coverOutcome != null) {
            append('\n')
            if (coverOutcome.copied) {
                append("![表紙](").append(coverOutcome.planned.markdownRef).append(")\n")
            } else {
                append("> 表紙の写真は読み込めなかったため、書き出されませんでした。\n")
            }
        }
        append("\n## 目次\n\n")
        var lastSection: String? = null
        planned.episodeFiles.forEach { file ->
            if (file.sectionTitle != null && file.sectionTitle != lastSection) {
                append('\n').append("### ").append(file.sectionTitle).append("\n\n")
            }
            lastSection = file.sectionTitle
            // README sits at the note root; episode files under episodes/.
            val relative = "episodes/" + file.markdownPath.substringAfterLast("episodes/")
            append("- [第").append(file.episode.number).append("話 ")
                .append(file.episode.title.ifBlank { "無題" })
                .append("](").append(relative).append(")\n")
        }
    }

    fun index(plan: PortableExportPlan): String = buildString {
        append("# MemoRipple 書き出し\n")
        fun memoLine(planned: PlannedMemo) {
            append("- [").append(planned.memo.title.ifBlank { "無題のメモ" })
                .append("](").append(rootRelative(planned.markdownPath)).append(")\n")
        }
        if (plan.activeMemos.isNotEmpty() || plan.archivedMemos.isNotEmpty()) {
            append("\n## メモ\n")
            if (plan.activeMemos.isNotEmpty()) {
                append("\n### 使用中\n\n")
                plan.activeMemos.forEach(::memoLine)
            }
            if (plan.archivedMemos.isNotEmpty()) {
                append("\n### アーカイブ\n\n")
                plan.archivedMemos.forEach(::memoLine)
            }
        }
        if (plan.diaries.isNotEmpty()) {
            append("\n## 日記\n")
            var year = -1
            plan.diaries.forEach { planned ->
                if (planned.year != year) {
                    year = planned.year
                    append("\n### ").append(year).append("年\n\n")
                }
                append("- [").append(planned.dateLabel).append(' ').append(planned.timeLabel)
                    .append("](").append(rootRelative(planned.markdownPath)).append(")\n")
            }
        }
        if (plan.notes.isNotEmpty()) {
            append("\n## ノート\n\n")
            plan.notes.forEach { planned ->
                append("- [").append(planned.note.title.ifBlank { "無題のノート" })
                    .append("](").append(rootRelative(planned.readmePath)).append(")\n")
            }
        }
        if (plan.trashedMemos.isNotEmpty()) {
            append("\n## ゴミ箱\n\n")
            plan.trashedMemos.forEach(::memoLine)
        }
    }

    fun readme(exportedAtLabel: String): String = """
        |# MemoRipple 書き出し
        |
        |MemoRipple Portable Export
        |Format 1
        |
        |- 作成日時: $exportedAtLabel
        |- 内容: メモ / 日記 / ノート / 写真
        |
        |## 読み方
        |
        |- 文章はMarkdownファイルです。一般的なテキストエディタで読めます。
        |- 写真は各記録の `photos` フォルダにあります。Markdown内の相対リンクが対応を示します。
        |- 全体の目次は `INDEX.md` にあります。
        |
        |## この書き出しについて
        |
        |- これはMemoRippleへの完全復元用バックアップではありません。復元にはアプリ内の「バックアップを作成」を使ってください。
        |- 文章と写真は暗号化されていません。保存先を利用できる人は内容を読めます。
        |- 未開封の未来コメントはプライバシー保護のため書き出していません。
        |- 写真には撮影日時・機種・位置情報などのメタデータが含まれる可能性があります。
        |""".trimMargin()

    /** One line per problem, general reasons only — no paths, URIs, or internals. */
    fun warnings(lines: List<String>): String = buildString {
        append("# 書き出し時の注意\n\n")
        lines.forEach { append("- ").append(it).append('\n') }
    }

    fun photoWarningLine(displayIndex: Int): String =
        "> 写真${displayIndex}は読み込めなかったため、書き出されませんでした。"

    private fun StringBuilder.appendPhotoSection(photoOutcomes: List<PhotoOutcome>) {
        if (photoOutcomes.isEmpty()) return
        append("\n## 写真\n\n")
        photoOutcomes.forEach { outcome ->
            if (outcome.copied) {
                append("![写真 ").append(outcome.planned.displayIndex).append("](")
                    .append(outcome.planned.markdownRef).append(")\n\n")
            } else {
                append(photoWarningLine(outcome.planned.displayIndex)).append("\n\n")
            }
        }
    }

    private fun rootRelative(entryPath: String): String =
        entryPath.removePrefix(PortableExportPlanner.ROOT + "/")

    private fun singleLine(text: String): String = text.replace('\n', ' ').trim()
}

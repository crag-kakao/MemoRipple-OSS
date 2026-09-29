package io.github.cragcoffee.memoripple.domain.export

import io.github.cragcoffee.memoripple.domain.WorkCommentSyntax
import io.github.cragcoffee.memoripple.domain.WorkLineType
import io.github.cragcoffee.memoripple.domain.export.PortableExportPlanner.PlannedDiary
import io.github.cragcoffee.memoripple.domain.export.PortableExportPlanner.PlannedEpisodeFile
import io.github.cragcoffee.memoripple.domain.export.PortableExportPlanner.PlannedMemo
import io.github.cragcoffee.memoripple.domain.export.PortableExportPlanner.PlannedNote
import io.github.cragcoffee.memoripple.domain.export.PortableMarkdownRenderer.PhotoOutcome
import io.github.cragcoffee.memoripple.domain.export.PortableExportPlanner.PortableExportPlan
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The browser's way into the same archive: every record gets a small self-contained HTML page
 * beside its Markdown, and `index.html` at the root mirrors `INDEX.md`, so unzipping and
 * double-clicking is enough to read everything with the photos inline. No scripts, no external
 * assets, one shared handful of inline styles; the body text is escaped and lightly shaped
 * (headings, lists, checkboxes, quotes) — never rewritten.
 */
object PortableHtmlRenderer {

    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun stamp(epochMillis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(dateTime)

    fun escape(text: String): String = buildString(text.length) {
        text.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }

    /** `path/to/memo.md` → `path/to/memo.html`. */
    fun htmlPathFor(markdownPath: String): String =
        markdownPath.removeSuffix(".md") + ".html"

    private const val STYLE = """
    body{font-family:sans-serif;line-height:1.8;max-width:42em;margin:2em auto;padding:0 1em;color:#222;background:#fdfdfd}
    h1{font-size:1.5em;border-bottom:1px solid #ccc;padding-bottom:.3em}
    h2{font-size:1.2em;margin-top:1.6em}
    img{max-width:100%;height:auto;border-radius:6px;margin:.4em 0}
    ul{padding-left:1.4em}
    blockquote{color:#666;border-left:3px solid #ccc;margin:0;padding-left:1em}
    .meta{color:#666;font-size:.9em}
    .warn{color:#946200}
    a{color:#1e6fd9}
    """

    private fun page(title: String, bodyHtml: String): String = buildString {
        append("<!DOCTYPE html>\n<html lang=\"ja\">\n<head>\n<meta charset=\"utf-8\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        append("<title>").append(escape(title)).append("</title>\n")
        append("<style>").append(STYLE).append("</style>\n</head>\n<body>\n")
        append(bodyHtml)
        append("\n</body>\n</html>\n")
    }

    /**
     * The body as readable HTML: the app's headings become heading tags, checkboxes become
     * their glyphs, items and quotes keep their shape, everything else is an escaped line.
     */
    fun bodyHtml(body: String): String = buildString {
        var listOpen = false
        fun closeList() {
            if (listOpen) {
                append("</ul>\n")
                listOpen = false
            }
        }
        body.lineSequence().forEach { line ->
            val syntax = WorkCommentSyntax.recognize(line)
            val trimmed = line.trim()
            when {
                syntax?.type == WorkLineType.HEADING -> {
                    closeList()
                    val level = (syntax.depth + 2).coerceIn(2, 5)
                    append("<h").append(level).append('>')
                        .append(escape(syntax.text)).append("</h").append(level).append(">\n")
                }

                // A capital X is Markdown-world spelling recognition never learned; the
                // renderer keeps reading it so imported files render as they always did.
                trimmed.startsWith("- [X] ") -> {
                    if (!listOpen) {
                        append("<ul>\n")
                        listOpen = true
                    }
                    append("<li>☑ ").append(escape(trimmed.drop(6))).append("</li>\n")
                }

                // Symbol lines go through the same recognition every set shares, so a
                // task written ☐ or ✅ renders exactly like one written - [ ].
                syntax?.type == WorkLineType.TASK_DONE -> {
                    if (!listOpen) {
                        append("<ul>\n")
                        listOpen = true
                    }
                    append("<li>☑ ").append(escape(syntax.text)).append("</li>\n")
                }

                syntax?.type == WorkLineType.TASK -> {
                    if (!listOpen) {
                        append("<ul>\n")
                        listOpen = true
                    }
                    append("<li>☐ ").append(escape(syntax.text)).append("</li>\n")
                }

                syntax?.type == WorkLineType.ITEM -> {
                    if (!listOpen) {
                        append("<ul>\n")
                        listOpen = true
                    }
                    append("<li>").append(escape(syntax.text)).append("</li>\n")
                }

                syntax?.type == WorkLineType.NOTE -> {
                    closeList()
                    append("<blockquote>").append(escape(syntax.text))
                        .append("</blockquote>\n")
                }

                trimmed.isEmpty() -> closeList()

                else -> {
                    closeList()
                    append("<p>").append(escape(line)).append("</p>\n")
                }
            }
        }
        closeList()
    }

    private fun StringBuilder.appendPhotos(outcomes: List<PhotoOutcome>) {
        if (outcomes.isEmpty()) return
        append("<h2>写真</h2>\n")
        outcomes.forEach { outcome ->
            if (outcome.copied) {
                append("<img src=\"").append(escape(outcome.planned.markdownRef))
                    .append("\" alt=\"写真 ").append(outcome.planned.displayIndex)
                    .append("\">\n")
            } else {
                append("<p class=\"warn\">写真").append(outcome.planned.displayIndex)
                    .append("は読み込めなかったため、書き出されませんでした。</p>\n")
            }
        }
    }

    private fun StringBuilder.appendComments(heading: String, comments: List<PortableComment>) {
        if (comments.isEmpty()) return
        append("<h2>").append(escape(heading)).append("</h2>\n<ol>\n")
        comments.forEach { comment ->
            append("<li>").append(escape(comment.text.replace('\n', ' ')))
            if (comment.expressionNotes.isNotEmpty()) {
                append(" <span class=\"meta\">（")
                    .append(escape(comment.expressionNotes.joinToString("、")))
                    .append("）</span>")
            }
            append("</li>\n")
        }
        append("</ol>\n")
    }

    fun memoHtml(planned: PlannedMemo, outcomes: List<PhotoOutcome>, zone: ZoneId): String {
        val memo = planned.memo
        val body = buildString {
            append("<h1>").append(escape(memo.title.ifBlank { "無題のメモ" })).append("</h1>\n")
            append("<p class=\"meta\">作成 ").append(stamp(memo.createdAt, zone))
                .append(" ／ 更新 ").append(stamp(memo.updatedAt, zone))
            if (memo.tags.isNotEmpty()) {
                append(" ／ タグ: ").append(escape(memo.tags.joinToString("、")))
            }
            append("</p>\n")
            append(bodyHtml(memo.body))
            appendPhotos(outcomes)
            appendComments("コメント", memo.comments)
        }
        return page(memo.title.ifBlank { "無題のメモ" }, body)
    }

    fun diaryHtml(planned: PlannedDiary, outcomes: List<PhotoOutcome>): String {
        val diary = planned.diary
        val body = buildString {
            append("<h1>").append(planned.dateLabel).append(" の日記</h1>\n")
            append("<p class=\"meta\">状態: ").append(escape(diary.stateLabel)).append("</p>\n")
            append(bodyHtml(diary.body))
            appendPhotos(outcomes)
            appendComments(
                "開封済みの未来コメント",
                diary.futureComments.map { PortableComment(it.text, it.expressionNotes) },
            )
        }
        return page("${planned.dateLabel} の日記", body)
    }

    fun episodeHtml(planned: PlannedEpisodeFile, outcomes: List<PhotoOutcome>): String {
        val episode = planned.episode
        val title = "第${episode.number}話 " + episode.title.ifBlank { "無題" }
        val body = buildString {
            append("<h1>").append(escape(title)).append("</h1>\n")
            planned.sectionTitle?.let {
                append("<p class=\"meta\">章: ").append(escape(it)).append("</p>\n")
            }
            append(bodyHtml(episode.body))
            appendPhotos(outcomes)
        }
        return page(title, body)
    }

    fun noteHtml(planned: PlannedNote, coverOutcome: PhotoOutcome?, zone: ZoneId): String {
        val note = planned.note
        val body = buildString {
            append("<h1>").append(escape(note.title.ifBlank { "無題のノート" })).append("</h1>\n")
            if (note.subtitle.isNotBlank()) {
                append("<p>").append(escape(note.subtitle)).append("</p>\n")
            }
            append("<p class=\"meta\">作成 ").append(stamp(note.createdAt, zone))
                .append(" ／ 更新 ").append(stamp(note.updatedAt, zone)).append("</p>\n")
            if (coverOutcome != null && coverOutcome.copied) {
                append("<img src=\"").append(escape(coverOutcome.planned.markdownRef))
                    .append("\" alt=\"表紙\">\n")
            }
            append("<h2>目次</h2>\n")
            var lastSection: String? = null
            planned.episodeFiles.forEach { file ->
                if (file.sectionTitle != null && file.sectionTitle != lastSection) {
                    append("<h3>").append(escape(file.sectionTitle)).append("</h3>\n")
                }
                lastSection = file.sectionTitle
                val relative = "episodes/" +
                    htmlPathFor(file.markdownPath).substringAfterLast("episodes/")
                append("<p><a href=\"").append(escape(relative)).append("\">第")
                    .append(file.episode.number).append("話 ")
                    .append(escape(file.episode.title.ifBlank { "無題" }))
                    .append("</a></p>\n")
            }
        }
        return page(note.title.ifBlank { "無題のノート" }, body)
    }

    fun indexHtml(plan: PortableExportPlan): String {
        val body = buildString {
            append("<h1>MemoRipple 書き出し</h1>\n")
            append("<p class=\"meta\">各ページは通常のブラウザで開けます。同じ内容のMarkdownも隣にあります。</p>\n")
            fun appendLink(label: String, markdownPath: String) {
                val href = htmlPathFor(markdownPath).removePrefix(PortableExportPlanner.ROOT + "/")
                append("<p><a href=\"").append(escape(href)).append("\">")
                    .append(escape(label)).append("</a></p>\n")
            }
            if (plan.activeMemos.isNotEmpty() || plan.archivedMemos.isNotEmpty()) {
                append("<h2>メモ</h2>\n")
                if (plan.activeMemos.isNotEmpty()) {
                    append("<h3>使用中</h3>\n")
                    plan.activeMemos.forEach {
                        appendLink(it.memo.title.ifBlank { "無題のメモ" }, it.markdownPath)
                    }
                }
                if (plan.archivedMemos.isNotEmpty()) {
                    append("<h3>アーカイブ</h3>\n")
                    plan.archivedMemos.forEach {
                        appendLink(it.memo.title.ifBlank { "無題のメモ" }, it.markdownPath)
                    }
                }
            }
            if (plan.diaries.isNotEmpty()) {
                append("<h2>日記</h2>\n")
                var year = -1
                plan.diaries.forEach { planned ->
                    if (planned.year != year) {
                        year = planned.year
                        append("<h3>").append(year).append("年</h3>\n")
                    }
                    appendLink(planned.dateLabel, planned.markdownPath)
                }
            }
            if (plan.notes.isNotEmpty()) {
                append("<h2>ノート</h2>\n")
                plan.notes.forEach {
                    appendLink(it.note.title.ifBlank { "無題のノート" }, it.readmePath)
                }
            }
            if (plan.trashedMemos.isNotEmpty()) {
                append("<h2>ゴミ箱</h2>\n")
                plan.trashedMemos.forEach {
                    appendLink(it.memo.title.ifBlank { "無題のメモ" }, it.markdownPath)
                }
            }
        }
        return page("MemoRipple 書き出し", body)
    }
}

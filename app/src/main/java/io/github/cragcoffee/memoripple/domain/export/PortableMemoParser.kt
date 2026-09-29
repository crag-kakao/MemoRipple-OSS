package io.github.cragcoffee.memoripple.domain.export

import io.github.cragcoffee.memoripple.domain.documents.DocumentTitles

/**
 * Reads a Format 1 `memo.md` back into a title, a body, and tag names — the inverse of what
 * [PortableMarkdownRenderer.memoMarkdown] writes, for「新規メモとして取り込む」only. The
 * metadata list, the photo section, and the comment section belong to the export, not the
 * memo, so they never enter the body; hash headings stay as hashes, which the editor reads
 * as headings natively — the same stance the Markdown import takes. What this does not
 * do: restore ids, dates, favorites, comments, or lifecycle — those are the backup's job.
 */
object PortableMemoParser {

    data class ParsedMemo(
        val title: String,
        val body: String,
        val tags: List<String>,
        /** photo-NN file names referenced by the memo, in display order. */
        val photoFileNames: List<String>,
        /**
         * The file said `- 種類: outline` (docs/OUTLINE_EXPORT_IMPORT.md). No kind line — every
         * file written before outlines travelled — or any other value is a memo, as the import
         * has always made: nothing is refused for a word it does not know.
         */
        val outline: Boolean = false,
    )

    /** The header line naming the kind, and the one value it carries today. */
    const val KIND_LABEL = "種類"
    const val KIND_OUTLINE = "outline"

    private val photoLink = Regex("""!\[写真 \d+]\((?:\.\./)?photos/([^)/\\]+)\)""")
    private val metadataTag = Regex("""^- タグ: (.+)$""")
    private val metadataKind = Regex("""^- $KIND_LABEL: (.+)$""")
    /** The placeholders an untitled document is written with; read back as no title. */
    private val untitledOutline = setOf(DocumentTitles.UNTITLED_OUTLINE, "無題のアウトライン") // today's name, and the one before 2026-09-25

    fun parse(markdown: String): ParsedMemo? {
        val lines = markdown.lines()
        val titleLine = lines.firstOrNull { it.isNotBlank() } ?: return null
        if (!titleLine.startsWith("# ")) return null
        val title = titleLine.removePrefix("# ").trim()

        val separator = lines.indexOfFirst { it.trim() == "---" }
        val header = lines.take(if (separator >= 0) separator else lines.size)
        val outline = header.firstNotNullOfOrNull { metadataKind.find(it.trim())?.groupValues?.get(1)?.trim() } == KIND_OUTLINE
        val tags = header
            .firstNotNullOfOrNull { metadataTag.find(it.trim())?.groupValues?.get(1) }
            ?.split('、', ',')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()

        // The body runs from after the --- to the first export-owned section.
        val afterSeparator = if (separator >= 0) lines.drop(separator + 1) else emptyList()
        val sectionStart = afterSeparator.indexOfFirst {
            it.trim() == "## 写真" || it.trim() == "## コメント"
        }
        val bodyLines = if (sectionStart >= 0) {
            afterSeparator.take(sectionStart)
        } else {
            afterSeparator
        }
        val body = bodyLines.joinToString("\n").trim('\n')

        val photos = photoLink.findAll(markdown).map { it.groupValues[1] }.toList()
        return ParsedMemo(
            title = if (title == DocumentTitles.UNTITLED_MEMO || (outline && title in untitledOutline)) "" else title,
            body = body,
            tags = tags,
            photoFileNames = photos,
            outline = outline,
        )
    }
}

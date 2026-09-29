package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.domain.WorkCommentSyntax
import io.github.cragcoffee.memoripple.domain.WorkLineType
import io.github.cragcoffee.memoripple.domain.documents.DocumentTitles

/** One memo as it leaves the app. */
data class ExportableMemo(
    val title: String,
    val body: String,
    val tagNames: List<String> = emptyList(),
    /** An outline, not a memo: its single-document file says so in front matter (docs/OUTLINE_EXPORT_IMPORT.md). */
    val outline: Boolean = false,
)

/**
 * Writes memos out as Markdown.
 *
 * The body is already Markdown shaped, so it leaves as it was written: items, checkboxes and the
 * inline decoration all mean the same thing in another editor.
 *
 * The one thing written differently is the heading. This app marks a heading with `■ `, which no
 * other editor knows, and a heading nothing else can see is not much of an export. So a heading
 * leaves as the hashes it is equivalent to, its depth becoming their number. Nothing is lost by
 * this: the import side reads hash headings, so the file comes back as the same document.
 *
 * Photos are not part of this. They stay with the backup, which is what carries a whole memo.
 */
object MemoMarkdownExport {

    const val MIME_TYPE = "text/markdown"

    /** The front matter key MemoRipple writes and reads, and the one value it has today. */
    const val FRONT_MATTER_KEY = "memoripple"
    const val FRONT_MATTER_OUTLINE = "outline"
    const val OUTLINE_FRONT_MATTER = "---\n$FRONT_MATTER_KEY: $FRONT_MATTER_OUTLINE\n---\n"

    const val EXTENSION = ".md"

    private const val SEPARATOR = "\n\n---\n\n"
    private const val MAX_FILE_NAME_CHARS = 60
    private const val MAX_HEADING_LEVEL = 6
    private val unsafeForFileName = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

    fun render(memo: ExportableMemo): String = buildString {
        // An outline's file opens with the one piece of front matter MemoRipple reads back; a memo's
        // file is exactly what it always was (docs/OUTLINE_EXPORT_IMPORT.md).
        if (memo.outline) append(OUTLINE_FRONT_MATTER).append('\n')
        append("# ").append(memo.title.ifBlank { if (memo.outline) DocumentTitles.UNTITLED_OUTLINE else DocumentTitles.UNTITLED_MEMO })
        if (memo.body.isNotBlank()) {
            append("\n\n").append(markdownBody(memo.body).trimEnd())
        }
        if (memo.tagNames.isNotEmpty()) {
            // Written as hashtags rather than a label, because that is what other editors read.
            append("\n\n").append(memo.tagNames.joinToString(" ") { "#" + it.trim().replace(' ', '_') })
        }
        append("\n")
    }

    /**
     * Several documents in one file, one after another. No front matter here: it belongs to the
     * start of a file, and a file of many is read back as memos.
     */
    fun renderAll(memos: List<ExportableMemo>): String =
        memos.joinToString(SEPARATOR) { render(it.copy(outline = false)).trimEnd() } + "\n"

    /** A file name a file manager will accept, derived from what the memo is called. */
    fun fileName(title: String): String {
        val base = unsafeForFileName.replace(title, "_")
            .trim()
            .trim('.')
            .trim()
            .take(MAX_FILE_NAME_CHARS)
        return (base.ifBlank { "無題のメモ" }) + EXTENSION
    }

    /**
     * [body] with every heading written the way Markdown writes one, and nothing else touched.
     * Public because the portable export renders bodies through exactly this conversion — one
     * contract, wherever a memo leaves the app.
     */
    fun markdownBody(body: String): String = body.lineSequence().joinToString("\n") { line ->
        val syntax = WorkCommentSyntax.recognize(line)
        when (syntax?.type) {
            WorkLineType.HEADING ->
                "#".repeat((syntax.depth + 1).coerceIn(1, MAX_HEADING_LEVEL)) + " " + syntax.text
            // A checkbox leaves as the CommonMark task Markdown readers know, whichever
            // set's glyph wrote it here; every other mark leaves as written.
            WorkLineType.TASK ->
                "  ".repeat(syntax.depth) + WorkCommentSyntax.TASK_MARKER + syntax.text
            WorkLineType.TASK_DONE ->
                "  ".repeat(syntax.depth) + WorkCommentSyntax.TASK_DONE_MARKER + syntax.text
            else -> line
        }
    }

    /** Name for a whole set of memos, stamped so repeated exports do not collide silently. */
    fun collectionFileName(stamp: String): String = "メモ_$stamp$EXTENSION"
}

package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.domain.documents.DocumentTitles

/** One memo read out of a Markdown file, before anything is written. */
data class ImportedMemo(
    val title: String,
    val body: String,
    val tagNames: List<String> = emptyList(),
    /** The file's front matter said `memoripple: outline` — it comes back as an outline. */
    val outline: Boolean = false,
)

/**
 * Reads memos back out of Markdown.
 *
 * This is the other half of [MemoMarkdownExport] and follows the same shape: a rule separates one
 * memo from the next, the leading heading is the title, and a closing line of hashtags is the tags.
 * A file that has none of that is one memo, which is what a Markdown file from elsewhere should be.
 *
 * Nothing is written here. The caller decides whether to keep what this returns.
 */
object MemoMarkdownImport {

    const val MAX_MEMOS = 500
    const val MAX_CHARACTERS = 2_000_000

    private val separator = Regex("""(?m)^[ \t]*(-{3,}|\*{3,}|_{3,})[ \t]*$""")
    private val frontMatter = Regex("""^---\r?\n.*?\r?\n---\r?\n""", RegexOption.DOT_MATCHES_ALL)
    /** The placeholders an untitled outline is written with (the name changed once). */
    private val untitledOutline = setOf(DocumentTitles.UNTITLED_OUTLINE, "無題のアウトライン") // today's name, and the one before 2026-09-25
    private val hashtagLine = Regex("""^#\S+(?:[ \t]+#\S+)*$""")

    fun parse(markdown: String): List<ImportedMemo> {
        if (markdown.isBlank()) return emptyList()
        val normalized = markdown.replace("\r\n", "\n")
        // Front matter describes the file rather than the memo, so it does not become a body —
        // but MemoRipple's own key is read first: `memoripple: outline` is an outline, the whole
        // file one document (an outline's lines are not cut at rules). Any other front matter, or
        // a value this does not know, is read as before: memos (docs/OUTLINE_EXPORT_IMPORT.md).
        val matter = frontMatter.find(normalized)
        val text = if (matter != null) normalized.substring(matter.range.last + 1) else normalized
        if (matter != null && isOutline(matter.value)) {
            // An untitled outline was written with the app's placeholder; it comes back untitled.
            return listOfNotNull(
                readOne(text)?.let { it.copy(title = if (it.title in untitledOutline) "" else it.title, outline = true) },
            )
        }
        return separator.split(text)
            .asSequence()
            .mapNotNull(::readOne)
            .take(MAX_MEMOS)
            .toList()
    }

    /** MemoRipple's own line in a front matter block — `memoripple: outline`, nothing else is read. */
    private fun isOutline(block: String): Boolean = block.lines().any { line ->
        val colon = line.indexOf(':')
        colon > 0 &&
            line.substring(0, colon).trim() == MemoMarkdownExport.FRONT_MATTER_KEY &&
            line.substring(colon + 1).trim().trim('"', '\'') == MemoMarkdownExport.FRONT_MATTER_OUTLINE
    }

    private fun readOne(chunk: String): ImportedMemo? {
        val lines = chunk.trim('\n').lines().dropWhile(String::isBlank).dropLastWhile(String::isBlank)
        if (lines.all(String::isBlank)) return null

        var body = lines
        var title = ""
        if (body.first().startsWith("# ")) {
            title = body.first().removePrefix("# ").trim()
            body = body.drop(1).dropWhile(String::isBlank)
        }

        var tagNames = emptyList<String>()
        val last = body.lastOrNull()?.trim()
        if (last != null && hashtagLine.matches(last)) {
            tagNames = last.split(Regex("""[ \t]+""")).map { it.removePrefix("#") }
            body = body.dropLast(1).dropLastWhile(String::isBlank)
        }

        val text = body.joinToString("\n")
        if (title.isBlank() && text.isBlank()) return null
        return ImportedMemo(title, text, tagNames)
    }
}

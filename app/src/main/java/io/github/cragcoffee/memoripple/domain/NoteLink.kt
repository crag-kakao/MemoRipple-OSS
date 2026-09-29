package io.github.cragcoffee.memoripple.domain

import io.github.cragcoffee.memoripple.domain.tags.TagNameNormalizer

/** A `[[title]]` reference located by its offsets in the raw body. */
data class NoteLinkSpan(val start: Int, val end: Int, val title: String) {
    val openMarker: IntRange get() = start until (start + NoteLink.OPEN.length)
    val closeMarker: IntRange get() = (end - NoteLink.CLOSE.length) until end
    val contentRange: IntRange get() = (start + NoteLink.OPEN.length) until (end - NoteLink.CLOSE.length)
}

/**
 * References between memos, written as `[[title]]` inside the body.
 *
 * A link is the title of another memo rather than an id, so it survives an export, keeps working
 * when a memo is restored from a backup, and can be written before the memo it points at exists.
 * Nothing new is stored: the reference is part of the text.
 */
object NoteLink {

    const val OPEN = "[["
    const val CLOSE = "]]"

    private val pattern = Regex("""\[\[(?!\s)([^\[\]\n]+?)(?<!\s)]]""")

    fun spans(raw: String): List<NoteLinkSpan> = pattern.findAll(raw).map { match ->
        NoteLinkSpan(
            start = match.range.first,
            end = match.range.last + 1,
            title = match.groupValues[1],
        )
    }.toList()

    /** Titles this body points at, each once, in the order they were written. */
    fun titles(raw: String): List<String> = spans(raw).map(NoteLinkSpan::title).distinctBy(::key)

    /** [raw] with the brackets removed, leaving the title as ordinary words. */
    fun strip(raw: String): String = pattern.replace(raw) { it.groupValues[1] }

    /** Writes a reference to [title] where the cursor is, replacing whatever was selected. */
    fun insert(edit: OutlineEdit, title: String): OutlineEdit =
        WorkOutlineEditing.insert(edit, OPEN + title.trim() + CLOSE)

    /** Titles are matched the way tags are, so width and case do not decide whether a link lands. */
    fun key(title: String): String = TagNameNormalizer.normalizeKey(title)
}

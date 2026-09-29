package io.github.cragcoffee.memoripple.domain

import io.github.cragcoffee.memoripple.domain.comments.CommentColorRole

enum class InlineStyle { BOLD, HIGHLIGHT }

/** A styled run of [text], located by its offsets in the raw body. */
data class InlineSpan(
    val start: Int,
    val end: Int,
    val style: InlineStyle,
    val color: CommentColorRole = CommentColorRole.DEFAULT,
) {
    /** Offsets of the opening and closing markers, which stay visible while editing. */
    val openMarker: IntRange get() = start until (start + openLength)
    val closeMarker: IntRange get() = (end - MARKER_LENGTH) until end
    val contentRange: IntRange get() = (start + openLength) until (end - MARKER_LENGTH)

    private val openLength: Int
        get() = MARKER_LENGTH + if (style == InlineStyle.HIGHLIGHT && color != CommentColorRole.DEFAULT) {
            color.storageId.length + 1
        } else {
            0
        }

    private companion object {
        const val MARKER_LENGTH = 2
    }
}

/**
 * Markdown-shaped inline decoration carried inside the memo body itself.
 *
 * `**text**` is bold, `==text==` is a highlight, and `==red:text==` is a coloured highlight using
 * the same colour ids that comments already persist. Keeping the decoration in the text means Room
 * and the backup format are untouched, and a body stays readable wherever it is exported.
 */
object InlineTextMarkup {

    const val BOLD_MARKER = "**"
    const val HIGHLIGHT_MARKER = "=="

    private val pattern = Regex(
        """\*\*(?!\s)(.+?)(?<!\s)\*\*|==(?:([a-z]+):)?(?!\s)(.+?)(?<!\s)==""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** Spans found in [raw], in document order. Overlapping or nested markers are not supported. */
    fun spans(raw: String): List<InlineSpan> = pattern.findAll(raw).map { match ->
        val bold = match.groups[1] != null
        val colorId = match.groups[2]?.value
        InlineSpan(
            start = match.range.first,
            end = match.range.last + 1,
            style = if (bold) InlineStyle.BOLD else InlineStyle.HIGHLIGHT,
            color = colorId?.let(CommentColorRole::fromStorageId) ?: CommentColorRole.DEFAULT,
        )
    }.toList()

    /** [raw] with every marker removed, for speech and for anything that wants the words only. */
    fun strip(raw: String): String = pattern.replace(raw) { match ->
        match.groups[1]?.value ?: match.groups[3]?.value.orEmpty()
    }

    /**
     * Wraps the selection in [edit] with the requested decoration, or unwraps it when the selection
     * already carries exactly that decoration. An empty selection is left alone: there is nothing to
     * decorate, and inserting bare markers would leave the body holding a marker with no content.
     */
    fun toggle(
        edit: OutlineEdit,
        style: InlineStyle,
        color: CommentColorRole = CommentColorRole.DEFAULT,
    ): OutlineEdit {
        val start = edit.selectionStart.coerceIn(0, edit.text.length)
        val end = edit.selectionEnd.coerceIn(start, edit.text.length)
        if (start == end) return edit

        val selected = edit.text.substring(start, end)
        val open = openMarkerFor(style, color)
        val close = if (style == InlineStyle.BOLD) BOLD_MARKER else HIGHLIGHT_MARKER

        val existing = spans(edit.text).firstOrNull { it.start == start && it.end == end }
        if (existing != null && existing.style == style && existing.color == color) {
            val bare = strip(selected)
            return OutlineEdit(
                edit.text.replaceRange(start, end, bare),
                start,
                start + bare.length,
            )
        }

        val bare = strip(selected)
        val wrapped = open + bare + close
        return OutlineEdit(
            edit.text.replaceRange(start, end, wrapped),
            start,
            start + wrapped.length,
        )
    }

    /** True when the selection is exactly one span carrying [style] and [color]. */
    fun isActive(
        edit: OutlineEdit,
        style: InlineStyle,
        color: CommentColorRole = CommentColorRole.DEFAULT,
    ): Boolean = spans(edit.text).any {
        it.start == edit.selectionStart && it.end == edit.selectionEnd &&
            it.style == style && it.color == color
    }

    private fun openMarkerFor(style: InlineStyle, color: CommentColorRole): String = when {
        style == InlineStyle.BOLD -> BOLD_MARKER
        color == CommentColorRole.DEFAULT -> HIGHLIGHT_MARKER
        else -> HIGHLIGHT_MARKER + color.storageId + ":"
    }
}

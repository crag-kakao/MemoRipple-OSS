package io.github.cragcoffee.memoripple.domain

import io.github.cragcoffee.memoripple.domain.comments.CommentColorRole

enum class RenderedKind { BOLD, HIGHLIGHT, LINK }

/** A styled run of the text a reader sees, located by offsets in that text. */
data class RenderedSpan(
    val start: Int,
    val end: Int,
    val kind: RenderedKind,
    val color: CommentColorRole = CommentColorRole.DEFAULT,
    val linkTitle: String? = null,
)

/** A comment link's place in the finished reading text: the number, and where its quiet
 * mark should sit — on the character just before where the marker stood, or standing alone
 * when the marker opened the line. */
data class RenderedLinkMarker(
    val number: Int,
    /** Offset in the rendered text where the marker stood. */
    val offset: Int,
) {
    /** The anchor character's range, empty when the marker had nothing before it. */
    val anchorStart: Int get() = (offset - 1).coerceAtLeast(0)
    val standsAlone: Boolean get() = offset == 0
}

/** Text with the markers taken off, plus where the decoration landed once they were gone. */
data class ReadableText(
    val text: String,
    val spans: List<RenderedSpan>,
    val linkMarkers: List<RenderedLinkMarker> = emptyList(),
) {
    fun linkAt(offset: Int): String? = spans
        .firstOrNull { it.kind == RenderedKind.LINK && offset >= it.start && offset < it.end }
        ?.linkTitle
}

/**
 * Turns a written line into the line a reader sees.
 *
 * The editor keeps its markers visible so offsets stay one to one with what is stored. Reading has
 * no caret to keep honest, so here the markers come off and only their effect remains.
 */
object ReadableMarkup {

    fun of(raw: String): ReadableText {
        val markup = InlineTextMarkup.spans(raw)
        val links = NoteLink.spans(raw)
        val commentLinks = io.github.cragcoffee.memoripple.domain.comments
            .CommentLinkMarkers.markersIn(raw)
        if (markup.isEmpty() && links.isEmpty() && commentLinks.isEmpty()) {
            return ReadableText(raw, emptyList())
        }

        val dropped = BooleanArray(raw.length)
        fun drop(range: IntRange) = range.forEach { if (it in raw.indices) dropped[it] = true }
        markup.forEach { drop(it.openMarker); drop(it.closeMarker) }
        links.forEach { drop(it.openMarker); drop(it.closeMarker) }
        commentLinks.forEach { drop(it.start until it.endExclusive) }

        // Where each original offset ends up once the markers are gone.
        val moved = IntArray(raw.length + 1)
        val builder = StringBuilder()
        raw.indices.forEach { index ->
            moved[index] = builder.length
            if (!dropped[index]) builder.append(raw[index])
        }
        moved[raw.length] = builder.length

        val spans = buildList {
            markup.forEach { span ->
                add(
                    RenderedSpan(
                        start = moved[span.contentRange.first],
                        end = moved[span.contentRange.last + 1],
                        kind = if (span.style == InlineStyle.BOLD) {
                            RenderedKind.BOLD
                        } else {
                            RenderedKind.HIGHLIGHT
                        },
                        color = span.color,
                    ),
                )
            }
            links.forEach { span ->
                add(
                    RenderedSpan(
                        start = moved[span.contentRange.first],
                        end = moved[span.contentRange.last + 1],
                        kind = RenderedKind.LINK,
                        linkTitle = span.title,
                    ),
                )
            }
        }.sortedBy(RenderedSpan::start)

        val renderedMarkers = commentLinks.map { marker ->
            RenderedLinkMarker(number = marker.number, offset = moved[marker.start])
        }

        return ReadableText(builder.toString(), spans, renderedMarkers)
    }
}

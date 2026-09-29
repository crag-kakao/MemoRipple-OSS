package io.github.cragcoffee.memoripple.domain.outline

import io.github.cragcoffee.memoripple.domain.OutlineSymbolRole
import io.github.cragcoffee.memoripple.domain.OutlineSymbols
import io.github.cragcoffee.memoripple.domain.WorkCommentSyntax
import io.github.cragcoffee.memoripple.domain.comments.CommentLineModifiers

/**
 * One line of a memo body, seen as an outline. The body text stays the record; this is a
 * reading of it that can be written back character for character.
 */
sealed interface OutlineEntry {
    /** Stable within one editing session; never derived from the line's position. */
    val id: Int

    /** The line exactly as it is written back. */
    fun toLine(): String
}

/**
 * A line with words on it: prose (no marker) or a marked outline line.
 *
 * Everything is kept raw so that [toLine] is `indent + marker + text` with nothing
 * normalised: the indent as written (odd spaces and tabs included), the marker in the glyph
 * the writer chose (space included, `## ` for a Markdown heading, empty for prose), and the
 * text with its inline markup, `[R n]` markers and 行末修飾子 still in place.
 */
data class OutlineNode(
    override val id: Int,
    val indent: String,
    val marker: String,
    val role: OutlineSymbolRole?,
    val text: String,
    /**
     * A photo row (Room 29, docs/OUTLINE_PHOTO_ROWS.md): a line of the outline of its own that is
     * a picture — the `memo_photo_attachments` row it shows — with a depth (its [indent]) and no
     * words. It moves, indents and folds like any line; it is never written into the body.
     */
    val photoAttachmentId: Long? = null,
) : OutlineEntry {
    /** A picture, not words: no marker, no text, never part of the body. */
    val isPhoto: Boolean get() = photoAttachmentId != null

    /**
     * The same number [WorkCommentSyntax] reads: two leading spaces per level — only the
     * spaces before any other whitespace count, as there — plus, for a Markdown heading,
     * one level per hash beyond the first.
     */
    val depth: Int
        get() = indent.takeWhile { it == ' ' }.length / INDENT_WIDTH + hashDepth

    private val hashDepth: Int
        get() = if (role == OutlineSymbolRole.HEADING && marker.startsWith("#")) {
            marker.trimEnd().length - 1
        } else {
            0
        }

    /** The marker without its space — what a bullet shows. */
    val glyph: String get() = marker.trimEnd()

    /** The text with the 行末修飾子 cut off — what a reading surface shows. */
    val readableText: String get() = CommentLineModifiers.strip(text).text

    /** How the line asked to fly, read from its end the way the comment parser reads it. */
    val modifiers: CommentLineModifiers.LineModifiers
        get() = CommentLineModifiers.strip(text).modifiers

    override fun toLine(): String = indent + marker + text

    private companion object {
        const val INDENT_WIDTH = 2
    }
}

/** A blank or whitespace-only line, kept verbatim: spacing the writer left. */
data class OutlineBlank(
    override val id: Int,
    val raw: String,
) : OutlineEntry {
    override fun toLine(): String = raw
}

/**
 * A whole body as outline entries, one per line. [nextId] is the id the next new entry takes.
 */
data class OutlineDocument(
    val entries: List<OutlineEntry>,
    val nextId: Int,
)

/**
 * The memo body ⇄ outline reading. `serialize(parse(text)) == text` for every text: the
 * outline is a way of editing the body, never a second copy of it.
 *
 * Lines are cut on `\n` alone, so a CR — or anything else — stays inside its line and comes
 * back; the shared syntax rule then reads each line exactly as the comment parser and the
 * reading page do, and what it does not consume is kept as written.
 */
object OutlineText {

    fun parse(text: String): OutlineDocument {
        var nextId = 1
        val entries = text.split('\n').map { rawLine -> parseLine(nextId++, rawLine) }
        return OutlineDocument(entries, nextId)
    }

    /** The body: the lines with words, in order — a photo row is never written into it. */
    fun serialize(document: OutlineDocument): String =
        document.entries.filterNot { it is OutlineNode && it.isPhoto }.joinToString("\n", transform = OutlineEntry::toLine)

    /** Reads one line the way [WorkCommentSyntax] does, keeping every character for [OutlineNode.toLine]. */
    fun parseLine(id: Int, rawLine: String): OutlineEntry {
        if (rawLine.isBlank()) return OutlineBlank(id, rawLine)
        // trimStart() removes every kind of leading whitespace; the indent is exactly that run.
        val content = rawLine.trimStart()
        val indent = rawLine.substring(0, rawLine.length - content.length)
        WorkCommentSyntax.markdownHeading.find(content)?.let { match ->
            return OutlineNode(
                id = id,
                indent = indent,
                marker = match.value,
                role = OutlineSymbolRole.HEADING,
                text = content.drop(match.value.length),
            )
        }
        val match = OutlineSymbols.match(content)
            ?: return OutlineNode(id, indent, marker = "", role = null, text = content)
        return OutlineNode(
            id = id,
            indent = indent,
            marker = content.substring(0, match.consumed),
            role = match.role,
            text = content.drop(match.consumed),
        )
    }
}

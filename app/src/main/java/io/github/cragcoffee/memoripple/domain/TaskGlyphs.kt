package io.github.cragcoffee.memoripple.domain

/**
 * Draws task notation as the box it means.
 *
 * `- [ ] ` and `- [x] ` stay in the text — the file format is theirs — but the eye should get a
 * checkbox, the way bold gets bold. Unlike the bold markers, hiding is not enough here: nothing
 * would remain to look like a box, so the six-character prefix is visually replaced by a glyph
 * and a space, which needs a real offset mapping between the raw and drawn text.
 *
 * The mapping is monotonic and total: every raw offset inside a prefix maps into the glyph pair,
 * and both glyph offsets map back inside the prefix, so the caret can stand on either side of
 * the box and backspace still eats the notation one character at a time.
 */
object TaskGlyphs {
    const val OPEN_PREFIX = "- [ ] "
    const val DONE_PREFIX = "- [x] "
    const val OPEN_GLYPH = "☐ "
    const val DONE_GLYPH = "☑ "

    data class Replacement(val rawStart: Int, val done: Boolean) {
        val rawEnd: Int get() = rawStart + OPEN_PREFIX.length
    }

    /** The prefixes to draw as boxes: one per line that starts, after indent, with the notation. */
    fun replacements(raw: String): List<Replacement> {
        val found = mutableListOf<Replacement>()
        var lineStart = 0
        while (lineStart <= raw.length) {
            val lineEnd = raw.indexOf('\n', lineStart).let { if (it < 0) raw.length else it }
            var content = lineStart
            while (content < lineEnd && (raw[content] == ' ' || raw[content] == '\t')) content++
            if (raw.startsWith(OPEN_PREFIX, content)) {
                found.add(Replacement(content, done = false))
            } else if (raw.startsWith(DONE_PREFIX, content)) {
                found.add(Replacement(content, done = true))
            }
            if (lineEnd == raw.length) break
            lineStart = lineEnd + 1
        }
        return found
    }

    /** The drawn text, with each prefix replaced by its glyph. */
    fun drawn(raw: String, replacements: List<Replacement> = replacements(raw)): String {
        if (replacements.isEmpty()) return raw
        val builder = StringBuilder(raw.length)
        var cursor = 0
        replacements.forEach { r ->
            builder.append(raw, cursor, r.rawStart)
            builder.append(if (r.done) DONE_GLYPH else OPEN_GLYPH)
            cursor = r.rawEnd
        }
        builder.append(raw, cursor, raw.length)
        return builder.toString()
    }

    private const val SHRINK = OPEN_PREFIX.length - OPEN_GLYPH.length // 4 per replacement

    /** Raw offset → drawn offset. Inside a prefix, offsets fold onto the glyph pair. */
    fun rawToDrawn(offset: Int, replacements: List<Replacement>): Int {
        var shift = 0
        for (r in replacements) {
            if (offset <= r.rawStart) return offset - shift
            if (offset < r.rawEnd) {
                // Within the notation: its first character is the box, the rest lean on the
                // trailing space, so a caret inside never lands beyond the glyph pair.
                val into = offset - r.rawStart
                return r.rawStart - shift + if (into == 0) 0 else 1
            }
            shift += SHRINK
        }
        return offset - shift
    }

    /** Drawn offset → raw offset. The box maps to the prefix's start, the space to its middle. */
    fun drawnToRaw(offset: Int, replacements: List<Replacement>): Int {
        var shift = 0
        for (r in replacements) {
            val drawnStart = r.rawStart - shift
            if (offset <= drawnStart) return offset + shift
            if (offset < drawnStart + OPEN_GLYPH.length) return r.rawStart + (offset - drawnStart)
            shift += SHRINK
        }
        return offset + shift
    }
}

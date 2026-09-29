package io.github.cragcoffee.memoripple.domain

import java.text.BreakIterator

/**
 * Keeps inline markers whole while the body is edited with the keyboard.
 *
 * [InlineTextMarkup]'s markers stay in the text but are drawn invisibly, so the caret can sit
 * inside one without the writer knowing. Left alone, a backspace at the visible end of a bold word
 * deletes the closing `*` the writer cannot see, the span stops matching, and the notation floods
 * back into view. The editor therefore runs every keyboard edit through [guard], which reads the
 * edit the way the writer saw it: a deletion that lands inside a marker meant the visible
 * character beside it, a range deletion keeps each partly-selected span's markers whole around
 * whatever content survives, and a span whose content is gone takes its markers with it.
 *
 * The guard corrects only what would break a marker. Markers typed by hand are new text, not
 * deletions, and pass through untouched, as does everything during IME composition — the caller
 * must not invoke this mid-composition, because rewriting composing text breaks conversion.
 */
object InlineMarkupEditing {

    /**
     * The corrected edit, or null when [after] is fine as it is.
     *
     * [before] is the field as it stood, [after] the value the keyboard proposes. When the two
     * texts are equal only the selection moved, and endpoints that landed strictly inside an
     * invisible marker are snapped out of it — through it when the caret was walking (so arrow
     * keys never stick), and to the content edge when it jumped there.
     */
    fun guard(before: OutlineEdit, after: OutlineEdit): OutlineEdit? {
        val spans = InlineTextMarkup.spans(before.text)
        if (spans.isEmpty()) return null
        if (after.text == before.text) return snapSelection(before, after, spans)

        val edit = diff(before.text, after.text) ?: return null
        var (start, end) = edit.first to edit.second
        val inserted = edit.third

        // Editing one of several identical characters is ambiguous to a text diff, and marker
        // characters come in identical pairs. The caret the keyboard reports sits at the true edit
        // point — backspace and forward delete both leave it on the deletion's start — so the edit
        // is realigned to it whenever that reads the same.
        val target = after.selectionStart - inserted.length
        if (target != start && target >= 0 && target + (end - start) <= before.text.length) {
            val realigned = StringBuilder(before.text)
                .replace(target, target + (end - start), inserted)
                .toString()
            if (realigned == after.text) {
                end = target + (end - start)
                start = target
            }
        }

        // A deletion falling wholly inside one marker is the writer deleting beside an invisible
        // thing: read it as the visible grapheme it meant.
        if (inserted.isEmpty() && start < end) {
            val marker = spans.firstOrNull { span ->
                within(start, end, span.openMarker) || within(start, end, span.closeMarker)
            }
            if (marker != null) {
                val meant = reinterpret(before, spans, start, end, marker)
                    ?: return snapOut(before, marker, start)
                start = meant.first
                end = meant.second
            }
        }

        if (spans.none { touches(start, end, it) }) return null

        val rebuilt = rebuild(before.text, spans, start, end, inserted)
        return if (rebuilt.text == after.text && rebuilt.selectionStart == after.selectionStart) {
            null
        } else {
            rebuilt
        }
    }

    // ---------------------------------------------------------------- selection

    private fun snapSelection(before: OutlineEdit, after: OutlineEdit, spans: List<InlineSpan>): OutlineEdit? {
        val walkingFrom = if (before.selectionStart == before.selectionEnd) before.selectionStart else null
        val start = snapOffset(after.selectionStart, spans, walkingFrom)
        val end = snapOffset(after.selectionEnd, spans, walkingFrom)
        return if (start == after.selectionStart && end == after.selectionEnd) {
            null
        } else {
            OutlineEdit(after.text, start, end)
        }
    }

    private fun snapOffset(offset: Int, spans: List<InlineSpan>, walkingFrom: Int?): Int {
        val span = spans.firstOrNull {
            strictlyInside(offset, it.openMarker) || strictlyInside(offset, it.closeMarker)
        } ?: return offset
        val inOpen = strictlyInside(offset, span.openMarker)
        return when {
            // One key to the right steps through the marker, not back onto it.
            walkingFrom != null && offset == walkingFrom + 1 ->
                if (inOpen) span.contentRange.first else span.end
            walkingFrom != null && offset == walkingFrom - 1 ->
                if (inOpen) span.start else span.contentRange.last + 1
            // A jump — a tap, a drag — lands on the content edge it looks like.
            else -> if (inOpen) span.contentRange.first else span.contentRange.last + 1
        }
    }

    // ---------------------------------------------------------------- deletion intent

    /** The visible grapheme a marker-interior deletion meant, or null when there is none. */
    private fun reinterpret(
        before: OutlineEdit,
        spans: List<InlineSpan>,
        start: Int,
        end: Int,
        span: InlineSpan,
    ): Pair<Int, Int>? {
        val collapsed = before.selectionStart == before.selectionEnd
        val backspace = collapsed && before.selectionStart == end
        val forward = collapsed && before.selectionStart == start
        val contentStart = span.contentRange.first
        val contentEnd = span.contentRange.last + 1
        val inOpen = within(start, end, span.openMarker)
        return when {
            backspace && !inOpen -> previousGrapheme(before.text, contentEnd)?.let { it to contentEnd }
            // Before the opening marker there may sit another span's invisible closing marker;
            // the visible character before this span is then that span's last one.
            backspace && inOpen -> when (val neighbour = spans.firstOrNull { it.end == span.start }) {
                null -> previousGrapheme(before.text, span.start)?.let { it to span.start }
                else -> previousGrapheme(before.text, neighbour.contentRange.last + 1)
                    ?.let { it to neighbour.contentRange.last + 1 }
            }
            forward && inOpen -> nextGrapheme(before.text, contentStart)?.let { contentStart to it }
            forward && !inOpen -> when (val neighbour = spans.firstOrNull { it.start == span.end }) {
                null -> nextGrapheme(before.text, span.end)?.let { span.end to it }
                else -> nextGrapheme(before.text, neighbour.contentRange.first)
                    ?.let { neighbour.contentRange.first to it }
            }
            // A selection wholly inside a marker selects nothing visible; deleting it does nothing.
            else -> null
        }
    }

    /** The no-op edit for a deletion that had nothing visible to remove, caret on the content edge. */
    private fun snapOut(before: OutlineEdit, span: InlineSpan, start: Int): OutlineEdit {
        val caret = if (start <= span.contentRange.first) span.contentRange.first else span.contentRange.last + 1
        return OutlineEdit(before.text, caret, caret)
    }

    // ---------------------------------------------------------------- rebuild

    /**
     * Applies replace([start], [end], [inserted]) to [text] treating each span's markers as
     * atomic: a partly-deleted span keeps whole markers around its surviving content, a span whose
     * content is gone (or blank) loses its markers, and content-edge whitespace moves outside the
     * markers so the span keeps matching.
     */
    private fun rebuild(
        text: String,
        spans: List<InlineSpan>,
        start: Int,
        end: Int,
        inserted: String,
    ): OutlineEdit {
        val out = StringBuilder()
        var caret = -1
        var insertionPending = inserted.isNotEmpty()

        // A plain stretch of [text]: everything the deletion covers goes, the insertion lands at
        // the deletion point, and the caret is remembered the first time that point is passed.
        fun emitSegment(from: Int, to: Int) {
            val cutStart = start.coerceIn(from, to)
            val cutEnd = end.coerceIn(from, to)
            out.append(text, from, cutStart)
            if (start in from..to) {
                if (caret < 0) caret = out.length
                if (insertionPending) {
                    out.append(inserted)
                    caret = out.length
                    insertionPending = false
                }
            }
            out.append(text, cutEnd, to)
        }

        var pos = 0
        spans.forEach { span ->
            emitSegment(pos, span.start)
            pos = span.end
            val overlaps = start < span.end && end > span.start
            val insertsInside = insertionPending && start > span.start && start < span.end
            if (!overlaps && !insertsInside) {
                out.append(text, span.start, span.end)
                return@forEach
            }
            val contentStart = span.contentRange.first
            val contentEnd = span.contentRange.last + 1
            val prefix = text.substring(contentStart, start.coerceIn(contentStart, contentEnd))
            val suffix = text.substring(end.coerceIn(contentStart, contentEnd), contentEnd)
            val innerInsert = if (insertsInside) inserted else ""
            if (insertsInside) insertionPending = false
            val inner = prefix + innerInsert + suffix
            val caretInInner = if (innerInsert.isNotEmpty()) {
                prefix.length + innerInsert.length
            } else if (caret < 0 && start in span.start..span.end) {
                prefix.length
            } else {
                -1
            }
            val at = out.length
            if (inner.isBlank()) {
                // Nothing worth styling survives: the markers go, blank content stays as plain.
                out.append(inner)
                if (caretInInner >= 0) caret = at + caretInInner.coerceAtMost(inner.length)
            } else {
                // Whole markers go back around the surviving content; whitespace that would sit
                // against a marker moves outside it, so the span keeps matching.
                val leading = inner.takeWhile(Char::isWhitespace)
                val trailing = inner.takeLastWhile(Char::isWhitespace)
                val core = inner.substring(leading.length, inner.length - trailing.length)
                val open = text.substring(span.start, contentStart)
                val close = text.substring(contentEnd, span.end)
                out.append(leading).append(open).append(core).append(close).append(trailing)
                if (caretInInner >= 0) {
                    caret = at + caretInInner + when {
                        caretInInner < leading.length -> 0
                        caretInInner <= leading.length + core.length -> open.length
                        else -> open.length + close.length
                    }
                }
            }
        }
        emitSegment(pos, text.length)
        if (insertionPending) {
            out.append(inserted)
            caret = out.length
        }
        if (caret < 0) caret = out.length
        return OutlineEdit(out.toString(), caret, caret)
    }

    // ---------------------------------------------------------------- pieces

    private fun within(start: Int, end: Int, marker: IntRange): Boolean =
        start >= marker.first && end <= marker.last + 1

    private fun strictlyInside(offset: Int, marker: IntRange): Boolean =
        offset > marker.first && offset <= marker.last

    private fun touches(start: Int, end: Int, span: InlineSpan): Boolean =
        start <= span.end && end >= span.start

    /** (deleteStart, deleteEnd, inserted) between the two texts, or null when they are equal. */
    private fun diff(before: String, after: String): Triple<Int, Int, String>? {
        if (before == after) return null
        var prefix = 0
        val shortest = minOf(before.length, after.length)
        while (prefix < shortest && before[prefix] == after[prefix]) prefix++
        var suffix = 0
        while (
            suffix < shortest - prefix &&
            before[before.length - 1 - suffix] == after[after.length - 1 - suffix]
        ) {
            suffix++
        }
        return Triple(prefix, before.length - suffix, after.substring(prefix, after.length - suffix))
    }

    private fun previousGrapheme(text: String, offset: Int): Int? {
        if (offset <= 0) return null
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val previous = it.preceding(offset)
        return if (previous == BreakIterator.DONE) null else previous
    }

    private fun nextGrapheme(text: String, offset: Int): Int? {
        if (offset >= text.length) return null
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val next = it.following(offset)
        return if (next == BreakIterator.DONE) null else next
    }
}

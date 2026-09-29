package io.github.cragcoffee.memoripple.domain.comments

/**
 * コメントリンクの本文側: `[R1]` markers written into a memo body, tying a place in the text
 * to the comment holding that number. Everything here is pure offset arithmetic — where the
 * markers stand, what the text looks like with them gone, where each one lands in the
 * stripped text, and how speech is cut so a marker fires exactly when reading passes it.
 * Numbers are memo-scoped, gaps are legal (deletion never renumbers), and the same number
 * may stand in several places.
 */
object CommentLinkMarkers {

    /** The wire shape of a marker in the body. The number is capped so `\d+` cannot overflow. */
    val pattern = Regex("""\[R(\d{1,4})]""")

    /** One marker as written: its number and its span in the original text. */
    data class Marker(val number: Int, val start: Int, val endExclusive: Int)

    /** A marker's place once the marker text itself is gone. */
    data class StrippedMarker(val number: Int, val offset: Int)

    /** The text a reader (or the TTS engine) receives, and where the markers stood in it. */
    data class Stripped(val text: String, val markers: List<StrippedMarker>)

    fun markersIn(text: String): List<Marker> = pattern.findAll(text).mapNotNull { match ->
        match.groupValues[1].toIntOrNull()?.let { number ->
            Marker(number, match.range.first, match.range.last + 1)
        }
    }.toList()

    fun strip(text: String): Stripped {
        val markers = markersIn(text)
        if (markers.isEmpty()) return Stripped(text, emptyList())
        val builder = StringBuilder(text.length)
        val stripped = mutableListOf<StrippedMarker>()
        var consumed = 0
        markers.forEach { marker ->
            builder.append(text, consumed, marker.start)
            stripped.add(StrippedMarker(marker.number, builder.length))
            consumed = marker.endExclusive
        }
        builder.append(text, consumed, text.length)
        return Stripped(builder.toString(), stripped)
    }

    /** How many places in [body] carry [number] — what the delete warning counts. */
    fun occurrenceCount(body: String, number: Int): Int =
        markersIn(body).count { it.number == number }

    /**
     * One piece of a speech feed cut at the markers: speaking [text], with [leadingNumbers]
     * firing the moment this piece starts, and [start] saying where the piece's words sit in
     * the stripped text. A piece may be empty (markers at the very end of the text, or several
     * in a row) — it still fires, it just has nothing to say.
     */
    data class SpeechPiece(val text: String, val leadingNumbers: List<Int>, val start: Int = 0)

    /** [stripped] cut at its marker offsets, for engines that report nothing mid-utterance. */
    fun speechPieces(stripped: Stripped): List<SpeechPiece> {
        if (stripped.markers.isEmpty()) {
            return listOf(SpeechPiece(stripped.text, emptyList(), 0))
        }
        val pieces = mutableListOf<SpeechPiece>()
        var index = 0
        val markers = stripped.markers
        // The run before the first marker fires nothing.
        val firstOffset = markers.first().offset
        if (firstOffset > 0) {
            pieces.add(SpeechPiece(stripped.text.substring(0, firstOffset), emptyList(), 0))
        }
        while (index < markers.size) {
            // All markers standing at this offset lead the next piece together.
            val here = mutableListOf<Int>()
            val offset = markers[index].offset
            while (index < markers.size && markers[index].offset == offset) {
                here.add(markers[index].number)
                index += 1
            }
            val nextOffset = if (index < markers.size) markers[index].offset else stripped.text.length
            pieces.add(SpeechPiece(stripped.text.substring(offset, nextOffset), here, offset))
        }
        return pieces
    }

    /**
     * Walks speech progress over [markers] (offsets in the stripped text) and fires each
     * exactly once per pass. Feed it every position report; it hands back the numbers whose
     * offsets have just been passed. Restarting a reading means a fresh tracker — a new pass.
     */
    class PassTracker(private val markers: List<StrippedMarker>) {
        private var next = 0

        /** Numbers passed by reaching [offset] (inclusive) that have not fired yet. */
        fun advanceTo(offset: Int): List<Int> {
            val fired = mutableListOf<Int>()
            while (next < markers.size && markers[next].offset <= offset) {
                fired.add(markers[next].number)
                next += 1
            }
            return fired
        }

        /** Everything not yet fired — spoken to the end, every marker has been passed. */
        fun finish(): List<Int> = advanceTo(Int.MAX_VALUE)
    }
}

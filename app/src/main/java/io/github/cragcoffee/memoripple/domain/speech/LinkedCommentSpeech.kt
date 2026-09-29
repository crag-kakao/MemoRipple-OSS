package io.github.cragcoffee.memoripple.domain.speech

import io.github.cragcoffee.memoripple.domain.comments.CommentLinkMarkers

/**
 * The plan for one reading that must fire コメントリンク as it passes their markers. Two ways
 * of listening, one plan shape:
 *
 * - **RANGE** — the body goes to the engine in its ordinary large chunks and the engine's
 *   `onRangeStart` positions, mapped through each chunk's base offset, walk a
 *   [CommentLinkMarkers.PassTracker]. Precise, but not every device reports ranges.
 * - **PIECES** — the body is cut at the markers themselves, and a marker fires the moment its
 *   piece starts. Works everywhere the engine can speak at all.
 *
 * Whichever mode ran, segment starts also advance the tracker (RANGE catch-up on quiet
 * devices) and the session's natural end fires whatever was never reported. Every marker
 * fires exactly once per reading; a new reading is a new plan.
 */
class LinkedCommentSpeechPlan private constructor(
    /** What to hand to speakSegments, in order: the preamble first, then the body. */
    val segments: List<String>,
    /** Base offset of each body segment in the stripped body; absent for preamble segments. */
    private val bodyBases: Map<Int, Int>,
    private val tracker: CommentLinkMarkers.PassTracker,
    /** In PIECES mode, the segment whose start fires these numbers directly. */
    private val leadingBySegment: Map<Int, List<Int>>,
) {

    /** Whether the engine ever reported an in-utterance range — the auto-detect signal. */
    var sawRange: Boolean = false
        private set

    enum class Mode { RANGE, PIECES }

    /** Numbers to fire because [index] started. */
    fun onSegmentStart(index: Int): List<Int> {
        val direct = leadingBySegment[index].orEmpty()
        // Reaching a body segment means everything before its base has been passed.
        val base = bodyBases[index] ?: return direct
        return direct + tracker.advanceTo(base)
    }

    /** Numbers to fire because the engine reached [startInclusive] within [index]. */
    fun onSegmentRange(index: Int, startInclusive: Int): List<Int> {
        sawRange = true
        val base = bodyBases[index] ?: return emptyList()
        return tracker.advanceTo(base + startInclusive)
    }

    /** Numbers the engine never got around to reporting before the reading ended. */
    fun onFinished(): List<Int> = tracker.finish()

    /**
     * Where [startInclusive] within segment [index] sits in the stripped body — the number the
     * follow-along cursor reads. Null for segments that are not the body (title, comments).
     */
    fun bodyOffsetOf(index: Int, startInclusive: Int = 0): Int? =
        bodyBases[index]?.plus(startInclusive)

    companion object {
        fun plan(
            mode: Mode,
            preambleTexts: List<String>,
            body: CommentLinkMarkers.Stripped,
            maxInputLength: Int,
            chunker: SpeechChunker = SpeechChunker(),
        ): LinkedCommentSpeechPlan {
            val preamble = preambleTexts.filter(String::isNotBlank)
            return when (mode) {
                Mode.RANGE -> {
                    // The chunker cuts by substring, so the chunks concatenate back to the
                    // body exactly — cumulative lengths are exact base offsets.
                    val chunks = chunker.chunk(body.text, maxInputLength)
                    val bases = mutableMapOf<Int, Int>()
                    var offset = 0
                    chunks.forEachIndexed { index, chunk ->
                        bases[preamble.size + index] = offset
                        offset += chunk.text.length
                    }
                    LinkedCommentSpeechPlan(
                        segments = preamble + chunks.map(SpeechSegment::text),
                        bodyBases = bases,
                        tracker = CommentLinkMarkers.PassTracker(body.markers),
                        leadingBySegment = emptyMap(),
                    )
                }

                Mode.PIECES -> {
                    val segments = mutableListOf<String>()
                    segments.addAll(preamble)
                    val leading = mutableMapOf<Int, List<Int>>()
                    val bases = mutableMapOf<Int, Int>()
                    val pieces = CommentLinkMarkers.speechPieces(body)
                    val trailing = mutableListOf<Int>()
                    pieces.forEach { piece ->
                        if (piece.text.isBlank()) {
                            // Nothing to say — these fire when the reading finishes.
                            trailing.addAll(piece.leadingNumbers)
                            return@forEach
                        }
                        // A piece longer than the engine accepts still speaks whole, in
                        // several utterances; only the first carries the firing. Each chunk
                        // remembers where it sits, so the follow-along cursor works here too.
                        val chunks = chunker.chunk(piece.text, maxInputLength)
                        var chunkOffset = 0
                        chunks.forEachIndexed { chunkIndex, chunk ->
                            if (chunkIndex == 0 && piece.leadingNumbers.isNotEmpty()) {
                                leading[segments.size] = piece.leadingNumbers
                            }
                            bases[segments.size] = piece.start + chunkOffset
                            segments.add(chunk.text)
                            chunkOffset += chunk.text.length
                        }
                    }
                    // Trailing numbers ride an empty tracker so onFinished hands them out.
                    // The bases serve only the follow-along cursor: firing in this mode stays
                    // with the piece starts, and the tracker holds nothing they would double.
                    LinkedCommentSpeechPlan(
                        segments = segments,
                        bodyBases = bases,
                        tracker = CommentLinkMarkers.PassTracker(
                            trailing.map { CommentLinkMarkers.StrippedMarker(it, Int.MAX_VALUE) },
                        ),
                        leadingBySegment = leading,
                    )
                }
            }
        }
    }
}

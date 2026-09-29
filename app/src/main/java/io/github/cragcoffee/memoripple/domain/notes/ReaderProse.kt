package io.github.cragcoffee.memoripple.domain.notes

import io.github.cragcoffee.memoripple.domain.BodyText
import io.github.cragcoffee.memoripple.domain.ProseTyping
import io.github.cragcoffee.memoripple.domain.WorkCommentSyntax
import io.github.cragcoffee.memoripple.domain.comments.CommentLineModifiers
import io.github.cragcoffee.memoripple.domain.comments.CommentLinkMarkers

/**
 * The episode as the reader sees and hears it: paragraphs first, then the sentences inside
 * them. One definition serves both — the screen that draws the page and the voice that reads
 * it must cut the prose in the same places, or the follow-along cursor points at the wrong
 * words.
 */
object ReaderProse {

    /**
     * One spoken stretch: where it stands on the page, and the words as written (ruby and
     * コメントリンク markers kept — the page draws around both).
     */
    data class Sentence(val paragraphIndex: Int, val raw: String) {
        /** What the voice says: markers gone, the ruby folded to the words underneath. */
        val spoken: String get() = spoken(readRubyReadings = false)

        /** The sentence as the voice says it — ruby as its reading when the writer asked. */
        fun spoken(readRubyReadings: Boolean): String {
            val bare = CommentLinkMarkers.strip(raw).text
            return if (readRubyReadings) {
                ProseTyping.stripToReadings(bare)
            } else {
                ProseTyping.strip(bare)
            }
        }

        /** The コメントリンク numbers whose markers stand in this sentence. */
        val linkNumbers: List<Int>
            get() = CommentLinkMarkers.markersIn(raw).map(CommentLinkMarkers.Marker::number)
    }

    /**
     * Consecutive lines of prose are one paragraph, the way a wrapped manuscript reads. A
     * marked line stands alone: marking it is the writer setting it apart. The marks do not
     * appear — but the ruby stays, because the reader draws it. A blank line stays too, as an
     * empty entry: the writer put air there, and the page keeps the air the manuscript has,
     * one empty line per blank line written.
     */
    fun paragraphs(body: String): List<String> {
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) paragraphs += current.toString()
            current.clear()
        }
        body.lineSequence().forEach { line ->
            val syntax = WorkCommentSyntax.recognize(line)
            // 行末修飾子 leave first; ruby and [R n] markers stay for the page to draw.
            val words = CommentLineModifiers.strip(
                BodyText.readableKeepingRubyAndMarkers(syntax?.text ?: line.trim()),
            ).text
            when {
                words.isBlank() -> { flush(); paragraphs += "" }
                syntax != null -> { flush(); paragraphs += words }
                else -> current.append(words)
            }
        }
        flush()
        // Air at the very end of the manuscript says nothing on a page that simply ends there.
        while (paragraphs.isNotEmpty() && paragraphs.last().isEmpty()) {
            paragraphs.removeAt(paragraphs.lastIndex)
        }
        return paragraphs
    }

    /**
     * Every paragraph cut into whole sentences, each keeping the mark it ends with. No
     * shortening: these are for reading aloud and pointing at, not for flying across a screen.
     */
    fun sentences(paragraphs: List<String>): List<Sentence> =
        paragraphs.flatMapIndexed { paragraphIndex, paragraph ->
            splitSentences(paragraph).map { Sentence(paragraphIndex, it) }
        }

    private fun splitSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        text.forEach { character ->
            current.append(character)
            if (character in SENTENCE_END) {
                result += current.toString()
                current.clear()
            }
        }
        if (current.isNotBlank()) result += current.toString()
        return result.map(String::trim).filter(String::isNotEmpty)
    }

    private val SENTENCE_END = setOf('。', '！', '？', '!', '?', '．')
}

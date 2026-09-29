package io.github.cragcoffee.memoripple.domain

/**
 * The one place that turns a written body into the words it stands for.
 *
 * Markers are how a writer says something; they are not what was said. Everything that reads a body
 * on the reader's behalf, speech, playback, previews and search, goes through here so they all agree
 * on where the writing ends and the words begin.
 */
object BodyText {
    fun readable(raw: String): String =
        io.github.cragcoffee.memoripple.domain.comments.CommentLinkMarkers
            .strip(ProseTyping.strip(NoteLink.strip(InlineTextMarkup.strip(raw)))).text

    /**
     * The words a voice should say. Identical to [readable] until the writer asks for the
     * ruby readings (設定 > ルビは読みを読み上げる): then 「｜戦《いくさ》」 is spoken as
     * いくさ — the reading the writer put over the word — instead of trusting the engine
     * with the kanji.
     */
    fun spoken(raw: String, readRubyReadings: Boolean): String =
        if (readRubyReadings) {
            io.github.cragcoffee.memoripple.domain.comments.CommentLinkMarkers
                .strip(ProseTyping.stripToReadings(NoteLink.strip(InlineTextMarkup.strip(raw))))
                .text
        } else {
            readable(raw)
        }

    /** Like [readable], but the コメントリンク markers stay — for the one caller that must
     * take them off itself while keeping their offsets. */
    fun readableKeepingMarkers(raw: String): String =
        ProseTyping.strip(NoteLink.strip(InlineTextMarkup.strip(raw)))

    /** [spoken] with the コメントリンク markers kept, for the linked-speech offset table. */
    fun spokenKeepingMarkers(raw: String, readRubyReadings: Boolean): String =
        if (readRubyReadings) {
            ProseTyping.stripToReadings(NoteLink.strip(InlineTextMarkup.strip(raw)))
        } else {
            readableKeepingMarkers(raw)
        }

    /**
     * Like [readable], but the ruby stays in its written form. For the one surface that draws
     * furigana — the note reader — instead of reducing it to the words underneath.
     */
    fun readableKeepingRuby(raw: String): String =
        io.github.cragcoffee.memoripple.domain.comments.CommentLinkMarkers
            .strip(NoteLink.strip(InlineTextMarkup.strip(raw))).text

    /** Ruby and markers both kept: the note reader draws the first and anchors dots on the
     * second, so it takes the prose with both still in place. */
    fun readableKeepingRubyAndMarkers(raw: String): String =
        NoteLink.strip(InlineTextMarkup.strip(raw))
}

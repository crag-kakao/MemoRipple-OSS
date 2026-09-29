package io.github.cragcoffee.memoripple.domain

/**
 * The conventions Japanese prose is written with, and the typing help they need.
 *
 * Ruby is written the way the submission sites read it: `｜親文字《ふりがな》`. Nothing new is stored
 * for it — like the rest of this app's decoration it lives in the body text, so a memo pasted into
 * カクヨム or 小説家になろう arrives with its ruby intact, and [strip] takes it back to the words
 * for anything that reads rather than edits.
 */
object ProseTyping {

    const val RUBY_BASE_MARKER = "｜"
    const val RUBY_OPEN = "《"
    const val RUBY_CLOSE = "》"

    /** The pairs a writer opens far more often than they close. */
    private val closers = mapOf(
        '「' to '」',
        '『' to '』',
        '（' to '）',
        '〈' to '〉',
        RUBY_OPEN.first() to RUBY_CLOSE.first(),
    )

    private val ruby = Regex("""｜([^｜《》\n]+)《([^《》\n]*)》""")

    /** [raw] with the ruby reduced to the words it was put over. */
    fun strip(raw: String): String = ruby.replace(raw) { it.groupValues[1] }

    /**
     * [raw] with the ruby reduced to its READING instead — 「｜戦《いくさ》」 becomes いくさ.
     * For a voice that should say what the writer wrote over the words, not gamble on the
     * kanji. A form with no reading falls back to the words, like [strip].
     */
    fun stripToReadings(raw: String): String = ruby.replace(raw) { match ->
        match.groupValues[2].ifBlank { match.groupValues[1] }
    }

    /** The readings in [raw], in document order. */
    fun readings(raw: String): List<String> =
        ruby.findAll(raw).map { it.groupValues[2] }.filter(String::isNotBlank).toList()

    /** One stretch of prose: the words, and the reading written over them when there is one. */
    data class RubyRun(val text: String, val reading: String? = null)

    /**
     * [raw] cut into runs for a reader that draws ruby instead of stripping it. Plain stretches
     * come back whole; each `｜親文字《ふりがな》` becomes its own run carrying the reading. An
     * empty reading collapses to plain words — a form the writer opened and never filled.
     */
    fun rubyRuns(raw: String): List<RubyRun> {
        val runs = mutableListOf<RubyRun>()
        var consumed = 0
        ruby.findAll(raw).forEach { match ->
            if (match.range.first > consumed) {
                runs += RubyRun(raw.substring(consumed, match.range.first))
            }
            val reading = match.groupValues[2]
            runs += RubyRun(match.groupValues[1], reading.takeIf(String::isNotBlank))
            consumed = match.range.last + 1
        }
        if (consumed < raw.length) runs += RubyRun(raw.substring(consumed))
        return runs
    }

    /**
     * Puts [open] and [close] around the selection, or writes the empty pair when there is none.
     *
     * With something selected the selection stays selected, so a second bracket can be added
     * without picking the words again. With nothing selected the caret lands between the two, which
     * is where the next character goes.
     */
    fun wrap(edit: OutlineEdit, open: String, close: String): OutlineEdit {
        val start = edit.selectionStart.coerceIn(0, edit.text.length)
        val end = edit.selectionEnd.coerceIn(start, edit.text.length)
        val selected = edit.text.substring(start, end)
        val updated = edit.text.replaceRange(start, end, open + selected + close)
        return if (selected.isEmpty()) {
            OutlineEdit(updated, start + open.length, start + open.length)
        } else {
            OutlineEdit(updated, start + open.length, start + open.length + selected.length)
        }
    }

    /**
     * Marks the selection as the base text of a ruby and leaves the caret where the reading goes.
     *
     * With nothing selected the whole form is written empty and the caret sits on the base text
     * instead, because there is no word yet to put a reading over.
     */
    fun insertRuby(edit: OutlineEdit): OutlineEdit {
        val start = edit.selectionStart.coerceIn(0, edit.text.length)
        val end = edit.selectionEnd.coerceIn(start, edit.text.length)
        val base = edit.text.substring(start, end)
        val written = RUBY_BASE_MARKER + base + RUBY_OPEN + RUBY_CLOSE
        val updated = edit.text.replaceRange(start, end, written)
        val caret = if (base.isEmpty()) {
            start + RUBY_BASE_MARKER.length
        } else {
            start + RUBY_BASE_MARKER.length + base.length + RUBY_OPEN.length
        }
        return OutlineEdit(updated, caret, caret)
    }

    /**
     * The closing bracket for one that was just typed, or null when nothing should be added.
     *
     * Only a single character typed at the caret counts. Pasting a paragraph that happens to open a
     * quote is not someone opening a quote, and a closing bracket already sitting there is not
     * worth a second one.
     */
    fun closeBracket(before: String, after: OutlineEdit): OutlineEdit? {
        if (after.selectionStart != after.selectionEnd) return null
        if (after.text.length != before.length + 1) return null
        val caret = after.selectionStart
        if (caret <= 0 || caret > after.text.length) return null
        val opened = after.text[caret - 1]
        val closing = closers[opened] ?: return null
        if (after.text.removeRange(caret - 1, caret) != before) return null
        if (caret < after.text.length && after.text[caret] == closing) return null
        return OutlineEdit(
            after.text.replaceRange(caret, caret, closing.toString()),
            caret,
            caret,
        )
    }
}

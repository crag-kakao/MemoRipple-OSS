package io.github.cragcoffee.memoripple.domain

import java.text.BreakIterator

/** What a line-start symbol means, independent of which set's glyph wrote it. */
enum class OutlineSymbolRole { HEADING, ITEM, TASK, TASK_DONE, NOTE, IMPORTANT, QUESTION }

/**
 * The three ways the outline symbols can be written. The chosen set decides only what the
 * shortcut bar's chips and checkbox button INSERT — recognition always accepts every set, so
 * switching never breaks an old memo and one memo may mix sets freely.
 */
enum class OutlineSymbolSet(val storageId: String, val label: String) {
    STANDARD("standard", "標準"),
    JAPANESE("japanese", "日本語記号"),
    EMOJI("emoji", "絵文字"),
    ;

    /** The bare symbol this set writes for [role], without the mandatory trailing space. */
    fun symbol(role: OutlineSymbolRole): String = when (this) {
        STANDARD -> when (role) {
            OutlineSymbolRole.HEADING -> "■"
            OutlineSymbolRole.ITEM -> "-"
            OutlineSymbolRole.TASK -> "- [ ]"
            OutlineSymbolRole.TASK_DONE -> "- [x]"
            OutlineSymbolRole.NOTE -> ">"
            OutlineSymbolRole.IMPORTANT -> "!"
            OutlineSymbolRole.QUESTION -> "?"
        }

        JAPANESE -> when (role) {
            OutlineSymbolRole.HEADING -> "◆"
            OutlineSymbolRole.ITEM -> "・"
            OutlineSymbolRole.TASK -> "☐"
            OutlineSymbolRole.TASK_DONE -> "☑"
            OutlineSymbolRole.NOTE -> "※"
            OutlineSymbolRole.IMPORTANT -> "★"
            OutlineSymbolRole.QUESTION -> "？"
        }

        EMOJI -> when (role) {
            OutlineSymbolRole.HEADING -> "📌"
            OutlineSymbolRole.ITEM -> "•"
            OutlineSymbolRole.TASK -> "☐"
            OutlineSymbolRole.TASK_DONE -> "✅"
            OutlineSymbolRole.NOTE -> "💬"
            OutlineSymbolRole.IMPORTANT -> "⚠️"
            OutlineSymbolRole.QUESTION -> "❓"
        }
    }

    /** What the toolbar writes: the symbol plus the mandatory half-width space. */
    fun marker(role: OutlineSymbolRole): String = symbol(role) + " "

    /** The one-line preview the settings row shows for this set. */
    fun preview(): String = listOf(
        OutlineSymbolRole.HEADING,
        OutlineSymbolRole.ITEM,
        OutlineSymbolRole.TASK,
        OutlineSymbolRole.TASK_DONE,
        OutlineSymbolRole.NOTE,
        OutlineSymbolRole.IMPORTANT,
        OutlineSymbolRole.QUESTION,
    ).joinToString(" ", transform = ::symbol)

    companion object {
        fun fromStorageId(value: String?): OutlineSymbolSet =
            entries.firstOrNull { it.storageId == value } ?: STANDARD
    }
}

/**
 * Recognition over every set at once — the one table the parser, the toolbar and the exports
 * read, so a symbol means the same thing everywhere it appears.
 *
 * Matching is grapheme-aware: a line's first cluster is cut with [BreakIterator], and the
 * variation selector (U+FE0F) is ignored on both sides, so ⚠ and ⚠️ are the same mark
 * whichever way a keyboard wrote them. Multi-character symbols (`- [ ]`, `- [x]`, `-`) match
 * longest-first so a checkbox is never mistaken for an item.
 */
/**
 * The writer's own alphabet: one glyph chosen per role, freely across the sets — 見出し may
 * be ◆ while 項目 stays `-`. Encoded for the device preference as `role:setId` pairs joined
 * with `|`; a legacy value holding just one set id (how the choice was first stored) reads
 * as that set for every role. Recognition is untouched by any of this: every glyph of every
 * set is always read.
 */
data class OutlineSymbolSelection(
    private val choice: Map<OutlineSymbolRole, OutlineSymbolSet>,
) {
    fun setFor(role: OutlineSymbolRole): OutlineSymbolSet =
        choice[role] ?: OutlineSymbolSet.STANDARD

    fun symbol(role: OutlineSymbolRole): String = setFor(role).symbol(role)

    fun marker(role: OutlineSymbolRole): String = setFor(role).marker(role)

    fun with(role: OutlineSymbolRole, set: OutlineSymbolSet): OutlineSymbolSelection =
        OutlineSymbolSelection(choice + (role to set))

    fun encode(): String = OutlineSymbolRole.entries.joinToString("|") { role ->
        "${role.name.lowercase()}:${setFor(role).storageId}"
    }

    companion object {
        val Default = OutlineSymbolSelection(emptyMap())

        fun decode(stored: String?): OutlineSymbolSelection {
            if (stored.isNullOrBlank()) return Default
            // The first shape this preference had: one set id for every role.
            OutlineSymbolSet.entries.firstOrNull { it.storageId == stored }?.let { set ->
                return OutlineSymbolSelection(
                    OutlineSymbolRole.entries.associateWith { set },
                )
            }
            val map = mutableMapOf<OutlineSymbolRole, OutlineSymbolSet>()
            stored.split('|').forEach { pair ->
                val role = OutlineSymbolRole.entries.firstOrNull {
                    it.name.lowercase() == pair.substringBefore(':')
                } ?: return@forEach
                val set = OutlineSymbolSet.entries.firstOrNull {
                    it.storageId == pair.substringAfter(':', "")
                } ?: return@forEach
                map[role] = set
            }
            return OutlineSymbolSelection(map)
        }
    }
}

object OutlineSymbols {

    /** A recognised line start: its meaning, and how many chars it consumed (space included). */
    data class Match(val role: OutlineSymbolRole, val consumed: Int)

    /** Every distinct symbol across the sets, longest first, each with its one meaning. */
    val allSymbols: List<Pair<String, OutlineSymbolRole>> = run {
        val seen = LinkedHashMap<String, OutlineSymbolRole>()
        OutlineSymbolSet.entries.forEach { set ->
            OutlineSymbolRole.entries.forEach { role ->
                val symbol = set.symbol(role)
                val existing = seen[symbol]
                check(existing == null || existing == role) {
                    "symbol $symbol claimed by two roles"
                }
                seen[symbol] = role
            }
        }
        seen.entries.map { it.key to it.value }
            .sortedByDescending { (symbol, _) -> symbol.length }
    }

    private const val VARIATION_SELECTOR = '\uFE0F'

    private fun bare(symbol: String): String =
        symbol.filterNot { it == VARIATION_SELECTOR }

    /**
     * The symbol match at the very start of [content] (already stripped of its indent), or
     * null when the line starts with no symbol or the mandatory space is missing.
     */
    fun match(content: String): Match? {
        if (content.isEmpty()) return null
        // The literal path: exact symbol + space. Longest first, so `- [ ]` beats `-`.
        allSymbols.forEach { (symbol, role) ->
            if (content.startsWith("$symbol ")) return Match(role, symbol.length + 1)
        }
        // The grapheme path: the line's first cluster with the variation selector ignored,
        // so ⚠ and ⚠️ are the same mark whichever way a keyboard wrote them.
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(content)
        iterator.first()
        val clusterEnd = iterator.next()
        if (clusterEnd == BreakIterator.DONE) return null
        if (clusterEnd >= content.length || content[clusterEnd] != ' ') return null
        val cluster = bare(content.substring(0, clusterEnd))
        if (cluster.isEmpty()) return null
        allSymbols.forEach { (symbol, role) ->
            if (bare(symbol) == cluster) return Match(role, clusterEnd + 1)
        }
        return null
    }
}

/**
 * How the shortcut bar labels its outline chips. The glyph teaches what the chip will
 * write; the word says what it means. Whoever knows their own symbols wants only the
 * glyph, and whoever reads by meaning wants only the word — so the writer chooses.
 */
enum class OutlineChipLabel(val storageId: String) {
    /** ■ 見出し — the glyph and the word, which is where the bar started. */
    SYMBOL_AND_WORD("symbol_and_word"),

    /** 見出し — the word alone. */
    WORD("word"),

    /** ■ — the glyph alone, which fits the most chips on a narrow bar. */
    SYMBOL("symbol"),
    ;

    /** The chip's face for [role]'s [word], written in [selection]'s glyphs. */
    fun textFor(word: String, role: OutlineSymbolRole, selection: OutlineSymbolSelection): String =
        when (this) {
            SYMBOL_AND_WORD -> "${selection.symbol(role)} $word"
            WORD -> word
            SYMBOL -> selection.symbol(role)
        }

    companion object {
        fun fromStorageId(value: String?): OutlineChipLabel =
            entries.firstOrNull { it.storageId == value } ?: SYMBOL_AND_WORD
    }
}

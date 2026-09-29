package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.domain.OutlineSymbolRole
import io.github.cragcoffee.memoripple.domain.WorkOutlineEditing

/** The two units of the bar that are rows of chips, each arranged on its own stage. */
enum class ToolbarChipGroup(val storageId: String, val title: String) {
    LABELS("labels", "見出し・項目などの記号"),
    FLOW("flow", "流れ方"),
}

/** One chip: how it is stored, what it does, and the word beside its glyph. */
data class ToolbarChip(val id: String, val word: String, val description: String)

/**
 * A chip row in the writer's order, some chips put away. A hidden chip keeps its place, so
 * bringing it back returns it where it was.
 */
data class ToolbarChipArrangement(val order: List<String>, val hidden: Set<String> = emptySet()) {
    val visible: List<String> get() = order.filterNot(hidden::contains)
    fun isHidden(id: String): Boolean = id in hidden
    fun withHidden(id: String, hide: Boolean): ToolbarChipArrangement =
        copy(hidden = if (hide) hidden + id else hidden - id)

    /** One step up (-1) or down (+1) the row; unchanged at either end. */
    fun moved(id: String, direction: Int): ToolbarChipArrangement {
        val from = order.indexOf(id)
        if (from < 0) return this
        val to = (from + direction).coerceIn(order.indices)
        if (to == from) return this
        return copy(order = order.toMutableList().apply { add(to, removeAt(from)) })
    }
}

/**
 * The chips of 見出し・項目などの記号 and 流れ方, and their arrangements as device preferences:
 * a `|`-joined list of ids, a hidden chip written with a leading `-`. Decoding forgives —
 * unknown ids are dropped and chips this build added join at the end, shown.
 */
object ToolbarChips {
    private const val HIDDEN_MARK = '-'

    /** The label chips, in [WorkOutlineEditing.chipRoles] order; ids are the roles' names. */
    val labels: List<ToolbarChip> = listOf(
        ToolbarChip("heading", "見出し", "行を見出しにする"),
        ToolbarChip("item", "項目", "行を項目にする"),
        ToolbarChip("note", "補足", "行を補足にする"),
        ToolbarChip("important", "重要", "行を重要にする"),
        ToolbarChip("question", "疑問", "行を疑問にする"),
    )

    /** The 流れ方 chips: the token each writes at the line's end. */
    val flow: List<ToolbarChip> = listOf(
        ToolbarChip("left", "←", "左へ流す"),
        ToolbarChip("right", "→", "右へ流す"),
        ToolbarChip("top", "↑", "上に固定"),
        ToolbarChip("bottom", "↓", "下に固定"),
        ToolbarChip("pin_top", "↑↑", "上に完全固定"),
        ToolbarChip("loop", "↺", "ずっと繰り返す"),
        ToolbarChip("large", "大", "大きく流す"),
        ToolbarChip("small", "小", "小さく流す"),
    )

    fun chipsFor(group: ToolbarChipGroup): List<ToolbarChip> = when (group) {
        ToolbarChipGroup.LABELS -> labels
        ToolbarChipGroup.FLOW -> flow
    }

    fun chip(group: ToolbarChipGroup, id: String): ToolbarChip? = chipsFor(group).firstOrNull { it.id == id }

    /** The role a label chip writes — the same list the memo bar has always offered. */
    fun roleOf(id: String): OutlineSymbolRole? =
        labels.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let(WorkOutlineEditing.chipRoles::getOrNull)

    /** The token a 流れ方 chip writes (the memo bar's own tokens; 大 and 小 are `+` and `-`). */
    fun flowToken(id: String): String? = when (id) {
        "left" -> "←"; "right" -> "→"; "top" -> "↑"; "bottom" -> "↓"
        "pin_top" -> "↑↑"; "loop" -> "↺"; "large" -> "+"; "small" -> "-"
        else -> null
    }

    fun defaultArrangement(group: ToolbarChipGroup): ToolbarChipArrangement =
        ToolbarChipArrangement(chipsFor(group).map(ToolbarChip::id))

    fun encode(arrangement: ToolbarChipArrangement): String =
        arrangement.order.joinToString("|") { id -> (if (arrangement.isHidden(id)) "$HIDDEN_MARK" else "") + id }

    fun decode(stored: String?, group: ToolbarChipGroup): ToolbarChipArrangement {
        val ids = chipsFor(group).map(ToolbarChip::id)
        if (stored.isNullOrBlank()) return defaultArrangement(group)
        val hidden = mutableSetOf<String>()
        val known = stored.split('|').mapNotNull { token ->
            val put = token.startsWith(HIDDEN_MARK)
            val id = if (put) token.substring(1) else token
            if (id !in ids) return@mapNotNull null
            if (put) hidden += id
            id
        }.distinct()
        if (known.isEmpty()) return defaultArrangement(group)
        return ToolbarChipArrangement(order = known + ids.filterNot(known::contains), hidden = hidden)
    }
}

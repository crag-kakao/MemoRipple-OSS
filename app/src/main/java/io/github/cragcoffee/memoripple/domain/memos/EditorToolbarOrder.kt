package io.github.cragcoffee.memoripple.domain.memos

/**
 * One movable unit of the editor's shortcut bar. Buttons that only make sense together —
 * 元に戻す and やり直し, the five outline chips — move as one, so the bar can be rearranged
 * without being taken apart. Declaration order is the bar's original order.
 */
enum class EditorToolbarItem(val storageId: String, val label: String, val detail: String) {
    PHOTO("photo", "写真を追加", "写真をメモに添える"),
    HISTORY("history", "元に戻す・やり直し", "直前の編集をたどる"),
    BOLD("bold", "太字", "選択した文字を強くする"),
    HIGHLIGHT("highlight", "ハイライト", "選択した文字に色を敷く"),
    PROSE_MARKS("prose_marks", "小説の記号", "「」『』……――ルビ"),
    TEMPLATE("template", "テンプレート", "登録した文面を差し込む"),
    LINK("link", "メモへのリンク", "他のメモへの参照を書く"),
    COMMENT_LINK("comment_link", "コメントリンク", "コメントの目印を本文に置く"),
    TASK("task", "チェックボックス", "行をタスクにする"),
    OUTLINE_LABELS("outline_labels", "見出し・項目などの記号", "行に構造の印を付ける"),
    INDENT("indent", "字下げ", "階層を上げ下げする"),
    FLOW_MODIFIERS("flow_marks", "流れ方", "行末に ← → ↑ ↓ 大 小 を書く"),

    // The outliner's own tools: a line moved among lines, folded, zoomed into.
    MOVE_LINES("move_lines", "上下へ移動", "行を上や下へ動かす"),
    FOLD("fold", "折りたたみ", "行の下を畳む・開く"),
    ZOOM("zoom", "ズーム", "この行だけを表示する"),
}

/**
 * Which editor the bar belongs to. A loose memo and a note's episode are different kinds of
 * writing with different tools, so each keeps its own arrangement — rearranging one never
 * disturbs the other.
 */
enum class EditorToolbarSurface { MEMO, NOTE, OUTLINER }

/**
 * A surface's whole bar: every unit in the writer's order, which of them are put away, and
 * which stand in the lower row when the bar is set to two. A hidden unit keeps its place in
 * the order, so bringing it back returns it where it was rather than at the end.
 *
 * [lower] only matters to the two-row bar; the one-row bar is [visible] as it stands.
 */
data class EditorToolbarArrangement(
    val order: List<EditorToolbarItem>,
    val hidden: Set<EditorToolbarItem> = emptySet(),
    val lower: Set<EditorToolbarItem> = EditorToolbarOrder.DefaultLowerRow,
) {
    /** What the bar actually shows. */
    val visible: List<EditorToolbarItem> get() = order.filterNot(hidden::contains)

    /** The upper row of the two-row bar, in the writer's order. */
    val upperRow: List<EditorToolbarItem> get() = visible.filterNot(lower::contains)

    /** The lower row of the two-row bar, in the writer's order. */
    val lowerRow: List<EditorToolbarItem> get() = visible.filter(lower::contains)

    fun isHidden(item: EditorToolbarItem): Boolean = item in hidden

    fun isLower(item: EditorToolbarItem): Boolean = item in lower

    fun withHidden(item: EditorToolbarItem, hide: Boolean): EditorToolbarArrangement =
        copy(hidden = if (hide) hidden + item else hidden - item)

    /**
     * Moves [item] to the other row. It lands at the boundary between the rows — the end of
     * the upper row, or the head of the lower one — which is where it stood when it crossed,
     * and keeps the one-row order reading as the two rows do.
     */
    fun withRow(item: EditorToolbarItem, toLower: Boolean): EditorToolbarArrangement {
        if (item !in order || isLower(item) == toLower) return this
        val nextLower = if (toLower) lower + item else lower - item
        val rest = order.filterNot { it == item }
        // The boundary is the first lower unit; the crosser lands there either way — as the
        // head of the lower row, or as the last of the upper one.
        val boundary = rest.indexOfFirst(nextLower::contains).let {
            if (it < 0) rest.size else it
        }
        return copy(
            order = rest.toMutableList().apply { add(boundary, item) },
            lower = nextLower,
        )
    }
}

/**
 * The bar's arrangement as a device preference: a `|`-joined list of storage ids, a hidden
 * unit written with a leading `-` and a row written with `^` (upper) or `_` (lower) —
 * `-_task` is a hidden unit belonging to the lower row. Decoding is forgiving both ways —
 * ids this build does not know are dropped, and items the stored string does not know are
 * appended after it in their own order, so an update that adds a button still shows it
 * without discarding anyone's arrangement. A string written before hiding existed hides
 * nothing; one written before the rows were the writer's to choose keeps the original
 * shelf, which is what its owner has been looking at all along.
 */
object EditorToolbarOrder {

    private const val HIDDEN_MARK = '-'
    private const val UPPER_MARK = '^'
    private const val LOWER_MARK = '_'

    val Default: List<EditorToolbarItem> = EditorToolbarItem.entries.toList()

    /**
     * The lower row as the bar has always drawn it: the structure shelf — a memo's outline
     * machinery, an episode's prose marks. Everything else stands above.
     */
    val DefaultLowerRow: Set<EditorToolbarItem> = setOf(
        EditorToolbarItem.TASK,
        EditorToolbarItem.OUTLINE_LABELS,
        EditorToolbarItem.INDENT,
        EditorToolbarItem.PROSE_MARKS,
    )

    /**
     * The outliner bar's lower row: what a line is and how it flies. The line tools — 字下げ,
     * 上下へ移動, 折りたたみ, ズーム — stand above (字下げ is structure on the memo bar, but here it
     * is one of the line tools).
     */
    val OutlinerLowerRow: Set<EditorToolbarItem> = setOf(
        EditorToolbarItem.TASK,
        EditorToolbarItem.OUTLINE_LABELS,
        EditorToolbarItem.FLOW_MODIFIERS,
    )

    /** The lower row a surface's bar is born with. */
    fun defaultLowerRow(surface: EditorToolbarSurface): Set<EditorToolbarItem> = when (surface) {
        EditorToolbarSurface.OUTLINER -> OutlinerLowerRow
        else -> DefaultLowerRow.intersect(itemsFor(surface).toSet())
    }

    /** The tools only the outliner has; the memo and note bars never show them. */
    val OutlinerOnly: Set<EditorToolbarItem> = setOf(
        EditorToolbarItem.MOVE_LINES,
        EditorToolbarItem.FOLD,
        EditorToolbarItem.ZOOM,
    )

    fun itemsFor(surface: EditorToolbarSurface): List<EditorToolbarItem> = when (surface) {
        EditorToolbarSurface.MEMO -> Default.filterNot { it == EditorToolbarItem.PROSE_MARKS || it in OutlinerOnly }
        // The outliner bar: the line's structure first — its own tools, nearest the hand — then the
        // memo's writing aids in the memo's order, then what a line is and how it flies.
        EditorToolbarSurface.OUTLINER -> listOf(
            EditorToolbarItem.INDENT,
            EditorToolbarItem.MOVE_LINES,
            EditorToolbarItem.FOLD,
            EditorToolbarItem.ZOOM,
            EditorToolbarItem.PHOTO,
            EditorToolbarItem.HISTORY,
            EditorToolbarItem.BOLD,
            EditorToolbarItem.HIGHLIGHT,
            EditorToolbarItem.TEMPLATE,
            EditorToolbarItem.LINK,
            EditorToolbarItem.COMMENT_LINK,
            EditorToolbarItem.TASK,
            EditorToolbarItem.OUTLINE_LABELS,
            EditorToolbarItem.FLOW_MODIFIERS,
        )
        EditorToolbarSurface.NOTE -> listOf(
            EditorToolbarItem.PHOTO,
            EditorToolbarItem.HISTORY,
            EditorToolbarItem.BOLD,
            EditorToolbarItem.HIGHLIGHT,
            EditorToolbarItem.TEMPLATE,
            EditorToolbarItem.LINK,
            EditorToolbarItem.COMMENT_LINK,
            EditorToolbarItem.FLOW_MODIFIERS,
            EditorToolbarItem.PROSE_MARKS,
        )
    }

    /** A surface's bar as it comes out of the box: original order, nothing hidden, the
     * structure shelf below — and only units this surface actually has. */
    fun defaultArrangement(surface: EditorToolbarSurface): EditorToolbarArrangement {
        val items = itemsFor(surface)
        return EditorToolbarArrangement(
            order = items,
            lower = defaultLowerRow(surface),
        )
    }

    fun encode(order: List<EditorToolbarItem>): String =
        order.joinToString("|", transform = EditorToolbarItem::storageId)

    fun encode(arrangement: EditorToolbarArrangement): String =
        arrangement.order.joinToString("|") { item ->
            buildString {
                if (arrangement.isHidden(item)) append(HIDDEN_MARK)
                // Both rows are written, always: a string that marks none of them is one
                // from before the rows were chosen, and must keep the original shelf.
                append(if (arrangement.isLower(item)) LOWER_MARK else UPPER_MARK)
                append(item.storageId)
            }
        }

    /** The units this surface's bar shows, in the writer's order — hidden ones left out. */
    fun decode(stored: String?, surface: EditorToolbarSurface): List<EditorToolbarItem> =
        decodeArrangement(stored, surface).visible

    /** The whole arrangement, hidden units included, for the screen that arranges it. */
    fun decodeArrangement(
        stored: String?,
        surface: EditorToolbarSurface,
    ): EditorToolbarArrangement {
        val items = itemsFor(surface)
        if (stored.isNullOrBlank()) return defaultArrangement(surface)
        val byId = items.associateBy(EditorToolbarItem::storageId)
        val hidden = mutableSetOf<EditorToolbarItem>()
        val lower = mutableSetOf<EditorToolbarItem>()
        var rowsWereChosen = false
        val known = stored.split('|').mapNotNull { token ->
            var rest = token
            val put = rest.startsWith(HIDDEN_MARK)
            if (put) rest = rest.substring(1)
            val row = rest.firstOrNull()?.takeIf { it == UPPER_MARK || it == LOWER_MARK }
            if (row != null) {
                rowsWereChosen = true
                rest = rest.substring(1)
            }
            val item = byId[rest] ?: return@mapNotNull null
            if (put) hidden += item
            if (row == LOWER_MARK) lower += item
            item
        }.distinct()
        if (known.isEmpty()) return defaultArrangement(surface)
        // A unit this build added is new to the stored string; it joins at the end, shown,
        // in whichever row it was born into.
        val newcomers = items.filterNot(known::contains)
        return EditorToolbarArrangement(
            order = known + newcomers,
            hidden = hidden,
            lower = if (rowsWereChosen) {
                lower + newcomers.filter(defaultLowerRow(surface)::contains)
            } else {
                defaultLowerRow(surface)
            },
        )
    }
}

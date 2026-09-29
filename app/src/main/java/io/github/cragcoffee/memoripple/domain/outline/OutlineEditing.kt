package io.github.cragcoffee.memoripple.domain.outline

import io.github.cragcoffee.memoripple.domain.OutlineSymbolRole
import io.github.cragcoffee.memoripple.domain.OutlineSymbolSet

/**
 * Editing an [OutlineDocument] as a tree of lines — pure functions over the entries, no UI,
 * no view state. Folding and zooming are not stored here: what is folded is a set of ids
 * the screen keeps ([visible] applies it) and a zoom is one id; neither is ever written
 * into the body.
 *
 * Depth is what [OutlineNode.depth] reads, so the tree is the one the comment parser and the
 * reading page see: a line is a child of the nearest shallower line above it, whatever the
 * gap. Indent and outdent write exactly the characters
 * [io.github.cragcoffee.memoripple.domain.WorkOutlineEditing] writes for a line, and take a
 * node's descendants along — that is the one deliberate difference from the text toolbar.
 */
object OutlineEditing {

    /** A change that added a line: the document after it and the id of the new entry. */
    data class Insertion(val document: OutlineDocument, val newId: Int)

    /** A deletion: the document after it, the line to focus, and where the caret lands. */
    data class Deletion(val document: OutlineDocument, val focusId: Int?, val caret: Int)

    /** Replaces the words on one line. [text] must be one line; a newline belongs to [split]. */
    fun updateText(document: OutlineDocument, id: Int, text: String): OutlineDocument {
        require('\n' !in text) { "a newline is a split, not a text change" }
        val position = document.position(id)
        val node = (document.entries[position] as? OutlineNode)?.takeUnless { it.isPhoto } ?: return document
        if (node.text == text) return document
        return document.replacing(position, node.copy(text = text))
    }

    /** What adding photos came to: the document, and the line to go on writing in (with its caret). */
    data class PhotoInsertion(val document: OutlineDocument, val focusId: Int?, val caret: Int)

    /**
     * Photos added while [afterId] is being written (docs/OUTLINE_PHOTO_ROWS.md): photo rows of
     * their own, in the order given, in the flow of the outline — never gathered at the top or
     * the end.
     * - [caret] inside the words: the line is split there (as Enter does) and the photos go
     *   between the two halves; writing goes on at the start of the second half.
     * - [caret] at the start of words: the photos go just before the line, at its depth.
     * - otherwise (the end, or no caret): the photos go right after the line — as its first
     *   children when it has children, else as its next siblings — and a new empty line of the
     *   same kind follows them, where writing goes on.
     * With no line to follow, the photos and a new line go at the end.
     */
    fun insertPhotos(document: OutlineDocument, afterId: Int?, attachmentIds: List<Long>, caret: Int?): PhotoInsertion {
        // One row per photo: a photo already shown is not shown twice.
        val shown = document.entries.mapNotNullTo(HashSet()) { (it as? OutlineNode)?.photoAttachmentId }
        val attachmentIds = attachmentIds.distinct().filterNot { it in shown }
        if (attachmentIds.isEmpty()) return PhotoInsertion(document, afterId, caret ?: 0)
        val target = afterId?.let { id -> document.entries.firstOrNull { it.id == id } as? OutlineNode }?.takeUnless { it.isPhoto }
            ?: document.entries.lastOrNull { it is OutlineNode && !it.isPhoto } as? OutlineNode
        var next = document.nextId
        fun photos(indent: String) = attachmentIds.map { OutlineNode(next++, indent, "", null, "", photoAttachmentId = it) }
        if (target == null) {
            val added = photos("")
            val line = nodeFor(next++, "", "- ", "")
            return PhotoInsertion(OutlineDocument(document.entries + added + line, next), line.id, 0)
        }
        val position = document.position(target.id)
        val at = caret?.coerceIn(0, target.text.length)
        if (at != null && at == 0 && target.text.isNotEmpty()) {
            val added = photos(target.indent)
            val entries = document.entries.toMutableList().apply { addAll(position, added) }
            return PhotoInsertion(OutlineDocument(entries, next), target.id, 0)
        }
        if (at != null && at > 0 && at < target.text.length) {
            val cut = split(document, target.id, at)
            next = cut.document.nextId
            val tail = cut.document.entries.first { it.id == cut.newId } as OutlineNode
            val tailPosition = cut.document.entries.indexOfFirst { it.id == cut.newId }
            val added = photos(tail.indent)
            val entries = cut.document.entries.toMutableList().apply { addAll(tailPosition, added) }
            return PhotoInsertion(OutlineDocument(entries, next), tail.id, 0)
        }
        val indent = if (hasChildren(document, target.id)) target.indent + INDENT else target.indent
        val added = photos(indent)
        val line = nodeFor(next++, indent, target.marker, "")
        val entries = document.entries.toMutableList().apply { addAll(position + 1, added + line) }
        return PhotoInsertion(OutlineDocument(entries, next), line.id, 0)
    }

    /** A photo row taken out of the outline; the lines around it stay as they are. */
    fun removePhoto(document: OutlineDocument, id: Int): OutlineDocument {
        val node = document.entries.firstOrNull { it.id == id } as? OutlineNode ?: return document
        if (!node.isPhoto) return document
        return document.copy(entries = document.entries.filterNot { it.id == id })
    }

    /**
     * Enter at the end of a line. A node with children gets the new line as its first child;
     * a leaf gets it as its next sibling — either way every existing parent keeps its
     * children. The new line wears [marker] (the node's own by default, so prose stays prose).
     */
    fun insertAfter(
        document: OutlineDocument,
        id: Int,
        marker: String? = null,
        text: String = "",
    ): Insertion {
        val position = document.position(id)
        val node = document.entries[position] as? OutlineNode
        val indent = when {
            node == null -> ""
            hasChildren(document, id) -> node.indent + INDENT
            else -> node.indent
        }
        val newMarker = marker ?: node?.marker.orEmpty()
        val entry = nodeFor(document.nextId, indent, newMarker, text)
        val entries = document.entries.toMutableList().apply { add(position + 1, entry) }
        return Insertion(OutlineDocument(entries, document.nextId + 1), entry.id)
    }


    /**
     * The + under the last line: a new item at the end of the document — or, inside a zoom,
     * as the zoomed line's last child, so it stays inside what the screen is showing.
     */
    fun appendLine(document: OutlineDocument, zoomId: Int?): Insertion {
        val root = zoomId?.let { id -> document.entries.firstOrNull { it.id == id } as? OutlineNode }
        val at = if (root != null) zoomRange(document, root.id).last + 1 else document.entries.size
        val indent = root?.let { it.indent + INDENT }.orEmpty()
        val entry = nodeFor(document.nextId, indent, ITEM_MARKER, "")
        val entries = document.entries.toMutableList().apply { add(at, entry) }
        return Insertion(OutlineDocument(entries, document.nextId + 1), entry.id)
    }


    /** The ids of [id] and everything it owns — what moves with it. */
    fun blockIds(document: OutlineDocument, id: Int): Set<Int> =
        blockOf(document, document.position(id)).map { document.entries[it].id }.toSet()

    /**
     * Carries [id]'s block anywhere: right after [afterId] (null = the very top), at [depth] —
     * pulled back to one step under the line above at most; deeper than that line means its
     * first child, otherwise it lands after that line's whole block as a sibling or an aunt.
     * Everything the block owns keeps its shape and steps with it. Never into itself.
     */
    fun relocate(document: OutlineDocument, id: Int, afterId: Int?, depth: Int): OutlineDocument {
        val position = document.position(id)
        val node = document.entries[position] as? OutlineNode ?: return document
        val block = blockOf(document, position)
        if (afterId != null && document.position(afterId) in block) return document
        val carried = document.entries.slice(block)
        val rest = document.entries.filterIndexed { index, _ -> index !in block }
        val restDocument = OutlineDocument(rest, document.nextId)
        val (at, targetDepth) = if (afterId == null) {
            0 to depth.coerceAtLeast(0)
        } else {
            val afterPosition = restDocument.position(afterId)
            val after = rest[afterPosition] as? OutlineNode
            val wanted = depth.coerceIn(0, (after?.depth ?: -1) + 1)
            val insertAt = if (after != null && wanted > after.depth) afterPosition + 1 else blockOf(restDocument, afterPosition).last + 1
            insertAt to wanted
        }
        val delta = targetDepth - node.depth
        val moved = carried.map { entry ->
            if (entry !is OutlineNode) entry
            else {
                val spaces = entry.indent.takeWhile { it == ' ' }.length / INDENT.length
                entry.copy(indent = INDENT.repeat((spaces + delta).coerceAtLeast(0)))
            }
        }
        val entries = rest.toMutableList().apply { addAll(at, moved) }
        return document.copy(entries = entries)
    }

    /**
     * Where a dragged line may land under [afterId] (null = the top of what is shown): under a
     * line whose children show it becomes their first; otherwise anything from the margin (or
     * one step under the zoomed line) to one step under that line, as close to [wanted] as that
     * allows. [collapsed] and [zoomId] say which children show.
     */
    fun landingDepth(
        document: OutlineDocument,
        id: Int,
        afterId: Int?,
        wanted: Int,
        collapsed: Set<Int>,
        zoomId: Int?,
    ): Int {
        val root = zoomId?.let { z -> document.entries.firstOrNull { it.id == z } as? OutlineNode }
        val base = root?.let { it.depth + 1 } ?: 0
        val after = afterId?.let { a -> document.entries.firstOrNull { it.id == a } as? OutlineNode } ?: return base
        val block = blockIds(document, id)
        val shown = visible(document, collapsed, zoomId)
        val afterHasShownChildren = shown.asSequence()
            .dropWhile { it.id != after.id }.drop(1)
            .firstOrNull { it.id !in block }
            .let { it is OutlineNode && it.depth > after.depth }
        return if (afterHasShownChildren) after.depth + 1 else wanted.coerceIn(base, after.depth + 1)
    }


    /**
     * One line rewritten by a line tool — a marker toggled, a 流れ方 token, a task box — as
     * the memo editor's bar would write it. The whole raw line goes in and comes back; its id
     * stays. A rewrite that leaves no words on the line leaves the line as it was.
     */
    fun rewriteLine(document: OutlineDocument, id: Int, transform: (String) -> String): OutlineDocument {
        val position = document.position(id)
        val node = (document.entries[position] as? OutlineNode)?.takeUnless { it.isPhoto } ?: return document
        val rewritten = OutlineText.parseLine(id, transform(node.toLine())) as? OutlineNode ?: return document
        if (rewritten == node) return document
        val entries = document.entries.toMutableList().apply { set(position, rewritten) }
        return document.copy(entries = entries)
    }

    /**
     * The entry the body's [line] (0-based) is — the body's lines are the outline's entries with
     * the photo rows left out, as the reading page draws them. Null past the end.
     */
    fun entryIdAtBodyLine(document: OutlineDocument, line: Int): Int? =
        document.entries.filterNot { it is OutlineNode && it.isPhoto }.getOrNull(line)?.id

    /** A text change that may carry newlines: the document after it, the line to focus, and the caret there. */
    data class TextChange(val document: OutlineDocument, val focusId: Int, val caret: Int)

    /**
     * Replaces the words on one line with [text], which may span lines — what a pasted template
     * or an inserted link produces. One line is a plain [updateText]; every further line is
     * inserted after the one before it (a node with children gets the first as its first child,
     * as Enter does), and the caret lands on the line [caret] falls in.
     */
    fun replaceText(document: OutlineDocument, id: Int, text: String, caret: Int): TextChange {
        val lines = text.split('\n')
        if ((document.entries.firstOrNull { it.id == id } as? OutlineNode)?.isPhoto == true) return TextChange(document, id, 0)
        if (lines.size == 1) return TextChange(updateText(document, id, text), id, caret.coerceIn(0, text.length))
        var current = updateText(document, id, lines.first())
        var previousId = id
        var focusId = id
        var focusCaret = caret.coerceIn(0, lines.first().length)
        var consumed = lines.first().length + 1
        lines.drop(1).forEach { line ->
            val inserted = insertAfter(current, previousId, marker = null, text = line)
            current = inserted.document
            previousId = inserted.newId
            if (caret >= consumed) {
                focusId = inserted.newId
                focusCaret = (caret - consumed).coerceIn(0, line.length)
            }
            consumed += line.length + 1
        }
        return TextChange(current, focusId, focusCaret)
    }

    /** Enter in the middle of a line: the words after [caret] move to the new line. */
    fun split(document: OutlineDocument, id: Int, caret: Int): Insertion {
        val position = document.position(id)
        val node = document.entries[position] as? OutlineNode
            ?: return insertAfter(document, id)
        if (node.isPhoto) return Insertion(document, id)
        val at = caret.coerceIn(0, node.text.length)
        val kept = document.replacing(position, node.copy(text = node.text.substring(0, at)))
        return insertAfter(kept, id, marker = null, text = node.text.substring(at))
    }

    /**
     * Backspace at the very start of a line. An empty line simply goes; a line with words
     * joins them onto the nearest line above, marker and all of the upper line kept, and the
     * caret lands at the seam. Either way the line's descendants are not orphaned: they follow
     * the words under their new parent (an empty parent's children are promoted one level).
     * The first line, having nothing above it, is left alone.
     */
    fun deleteBackward(document: OutlineDocument, id: Int): Deletion {
        val position = document.position(id)
        val node = (document.entries[position] as? OutlineNode)?.takeUnless { it.isPhoto }
            ?: return Deletion(document, id, 0)
        val abovePosition = (position - 1 downTo 0).firstOrNull { document.entries[it] is OutlineNode }
            ?: return Deletion(document, id, 0)
        // Right under a photo row nothing happens (as in a memo): the keyboard never removes a
        // photo, and words never jump over one to join the line above it.
        if ((document.entries[abovePosition] as OutlineNode).isPhoto) return Deletion(document, id, 0)
        val above = document.entries[abovePosition] as OutlineNode
        val subtree = subtreeRange(document, position)
        val levelShift = above.level - node.level
        val joined = above.copy(text = above.text + node.text)
        val entries = document.entries.mapIndexed { index, entry ->
            when {
                index == abovePosition -> joined
                index in subtree && index != position && entry is OutlineNode ->
                    entry.copy(indent = shiftLevel(entry.indent, levelShift))
                else -> entry
            }
        }.filterIndexed { index, _ -> index != position }
        return Deletion(document.copy(entries = entries), above.id, above.text.length)
    }

    /**
     * One level deeper, taking the node's descendants along. Only possible under a previous
     * sibling — a first child cannot be indented, and the document is returned unchanged.
     */
    fun indent(document: OutlineDocument, id: Int): OutlineDocument {
        if (!canIndent(document, id)) return document
        val position = document.position(id)
        return document.mapNodes(subtreeRange(document, position)) { it.copy(indent = INDENT + it.indent) }
    }

    fun canIndent(document: OutlineDocument, id: Int): Boolean =
        previousSibling(document, document.position(id)) != null

    /**
     * One level shallower, taking the node's descendants along, the same way
     * [io.github.cragcoffee.memoripple.domain.WorkOutlineEditing.outdent] treats a line.
     * A line already at the margin is returned unchanged. Inside a zoom the zoom root's own
     * children stay inside it: they cannot be outdented out of view.
     */
    fun outdent(document: OutlineDocument, id: Int, zoomId: Int? = null): OutlineDocument {
        if (!canOutdent(document, id, zoomId)) return document
        val position = document.position(id)
        return document.mapNodes(subtreeRange(document, position)) { it.copy(indent = outdentOnce(it.indent)) }
    }

    fun canOutdent(document: OutlineDocument, id: Int, zoomId: Int? = null): Boolean {
        val position = document.position(id)
        val node = document.entries[position] as? OutlineNode ?: return false
        if (!node.indent.startsWith(" ")) return false
        if (zoomId == null) return true
        val root = document.entries[document.position(zoomId)] as? OutlineNode ?: return true
        return node.depth > root.depth + 1
    }

    /** Swaps the node's block with the previous sibling's block; nothing else moves. */
    fun moveUp(document: OutlineDocument, id: Int): OutlineDocument {
        val position = document.position(id)
        val sibling = previousSibling(document, position) ?: return document
        return swapBlocks(document, blockOf(document, sibling), blockOf(document, position))
    }

    /** Swaps the node's block with the next sibling's block; nothing else moves. */
    fun moveDown(document: OutlineDocument, id: Int): OutlineDocument {
        val position = document.position(id)
        val sibling = nextSibling(document, position) ?: return document
        return swapBlocks(document, blockOf(document, position), blockOf(document, sibling))
    }

    fun canMoveUp(document: OutlineDocument, id: Int): Boolean =
        previousSibling(document, document.position(id)) != null

    fun canMoveDown(document: OutlineDocument, id: Int): Boolean =
        nextSibling(document, document.position(id)) != null

    /** True when a deeper line follows this node before any line at its depth or shallower. */
    fun hasChildren(document: OutlineDocument, id: Int): Boolean {
        val position = document.position(id)
        return subtreeRange(document, position).last > position
    }

    /** How many lines a fold on [id] hides: every node it owns, at any depth. */
    fun hiddenCount(document: OutlineDocument, id: Int): Int {
        val range = ownedRange(document, document.position(id)) ?: return 0
        return range.count { document.entries[it] is OutlineNode }
    }

    /**
     * Ticks a task line or unticks a done one, in the symbol set the line was written in;
     * any other line is returned as it is. Only the marker changes — the words stay.
     */
    fun toggleTask(document: OutlineDocument, id: Int): OutlineDocument {
        val position = document.position(id)
        val node = document.entries[position] as? OutlineNode ?: return document
        val target = when (node.role) {
            OutlineSymbolRole.TASK -> OutlineSymbolRole.TASK_DONE
            OutlineSymbolRole.TASK_DONE -> OutlineSymbolRole.TASK
            else -> return document
        }
        val set = OutlineSymbolSet.entries.firstOrNull { it.symbol(node.role) == node.glyph } ?: OutlineSymbolSet.STANDARD
        val entries = document.entries.toMutableList()
        entries[position] = node.copy(marker = set.marker(target), role = target)
        return document.copy(entries = entries)
    }

    /**
     * Every node that [hasChildren], found in one pass: a screen drawing hundreds of rows
     * asks this once per document instead of once per row.
     */
    fun nodesWithChildren(document: OutlineDocument): Set<Int> {
        val parents = HashSet<Int>()
        var previous: OutlineNode? = null
        document.entries.forEach { entry ->
            val node = entry as? OutlineNode ?: return@forEach
            val above = previous
            if (above != null && !above.isPhoto && node.depth > above.depth) parents += above.id
            previous = node
        }
        return parents
    }

    /**
     * Positions the node at [position] hides when folded, or null when there is nothing to
     * hide. A heading owns everything up to the next heading at its depth or shallower —
     * the reading page's rule — and any other line owns the deeper lines under it.
     */
    fun ownedRange(document: OutlineDocument, position: Int): IntRange? {
        val node = document.entries[position] as? OutlineNode ?: return null
        val last = if (node.role == OutlineSymbolRole.HEADING) {
            lastOwnedBy(document, position) { it.role == OutlineSymbolRole.HEADING && it.depth <= node.depth }
        } else {
            lastOwnedBy(document, position) { it.depth <= node.depth }
        }
        return if (last > position) (position + 1)..last else null
    }

    /** The zoomed node and what it owns — what a zoom shows. */
    fun zoomRange(document: OutlineDocument, id: Int): IntRange {
        val position = document.position(id)
        return position..(ownedRange(document, position)?.last ?: position)
    }

    /**
     * The lines above [id] it is nested under, from the outermost down to its parent. A photo row
     * is never one of them (docs/OUTLINE_PHOTO_ROWS.md): a zoom, a breadcrumb and a fold stand
     * only on lines of words.
     */
    fun ancestors(document: OutlineDocument, id: Int): List<OutlineNode> {
        val position = document.position(id)
        val node = document.entries[position] as? OutlineNode ?: return emptyList()
        val path = mutableListOf<OutlineNode>()
        var depth = node.depth
        for (index in position - 1 downTo 0) {
            val above = (document.entries[index] as? OutlineNode)?.takeUnless { it.isPhoto } ?: continue
            if (above.depth < depth) {
                path += above
                depth = above.depth
                if (depth == 0) break
            }
        }
        return path.reversed()
    }

    /**
     * The entries still on screen once the nodes whose ids are in [collapsed] are folded —
     * inside the zoomed node's block when [zoomId] is set, where the root itself always
     * shows its children.
     */
    fun visible(
        document: OutlineDocument,
        collapsed: Set<Int>,
        zoomId: Int? = null,
    ): List<OutlineEntry> {
        val range = zoomId?.let { zoomRange(document, it) } ?: document.entries.indices
        val result = mutableListOf<OutlineEntry>()
        var position = range.first
        while (position <= range.last) {
            val entry = document.entries[position]
            result += entry
            val folded = entry.id in collapsed && entry.id != zoomId
            val owned = if (folded) ownedRange(document, position) else null
            position = owned?.let { it.last + 1 } ?: (position + 1)
        }
        return result
    }

    /** [collapsed] with [id] folded or unfolded — unchanged when the node hides nothing, or is a photo row. */
    fun toggleFold(document: OutlineDocument, collapsed: Set<Int>, id: Int): Set<Int> {
        if (id in collapsed) return collapsed - id
        if ((document.entries.firstOrNull { it.id == id } as? OutlineNode)?.isPhoto == true) return collapsed
        return if (ownedRange(document, document.position(id)) != null) collapsed + id else collapsed
    }

    /**
     * A node for a new line, read by the same rule as any line — except that an empty one is
     * still a node (a line the writer is about to type on), not the blank it would parse as.
     */
    private fun nodeFor(id: Int, indent: String, marker: String, text: String): OutlineNode {
        val probe = OutlineText.parseLine(id, indent + marker + text.ifEmpty { "x" })
        val node = probe as? OutlineNode ?: return OutlineNode(id, indent, marker, role = null, text = text)
        return if (text.isEmpty()) node.copy(text = "") else node
    }

    /** The nearest node above at the same depth, unless a shallower one comes first. */
    private fun previousSibling(document: OutlineDocument, position: Int): Int? {
        val node = document.entries[position] as? OutlineNode ?: return null
        for (index in position - 1 downTo 0) {
            val above = document.entries[index] as? OutlineNode ?: continue
            if (above.depth == node.depth) return index
            if (above.depth < node.depth) return null
        }
        return null
    }

    /** The nearest node below at the same depth after this node's block, unless a shallower one comes first. */
    private fun nextSibling(document: OutlineDocument, position: Int): Int? {
        val node = document.entries[position] as? OutlineNode ?: return null
        for (index in blockOf(document, position).last + 1 until document.entries.size) {
            val below = document.entries[index] as? OutlineNode ?: continue
            if (below.depth == node.depth) return index
            if (below.depth < node.depth) return null
        }
        return null
    }

    /** What moves with a line: itself and what it owns (a heading's whole section). */
    private fun blockOf(document: OutlineDocument, position: Int): IntRange =
        position..(ownedRange(document, position)?.last ?: position)

    /** Exchanges two blocks, [first] before [second]; the entries between them stay between. */
    private fun swapBlocks(document: OutlineDocument, first: IntRange, second: IntRange): OutlineDocument {
        val entries = document.entries
        val rebuilt = entries.subList(0, first.first) +
            entries.slice(second) +
            entries.subList(first.last + 1, second.first) +
            entries.slice(first) +
            entries.subList(second.last + 1, entries.size)
        return document.copy(entries = rebuilt)
    }

    /** The node at [position] and the deeper lines under it; blanks after the last of them are not part of it. */
    private fun subtreeRange(document: OutlineDocument, position: Int): IntRange {
        val node = document.entries[position] as? OutlineNode ?: return position..position
        return position..lastOwnedBy(document, position) { it.depth <= node.depth }
    }

    /**
     * The position of the last node below [position] before one that [stops] the ownership;
     * blanks in between are owned, blanks after the last owned node are not.
     */
    private inline fun lastOwnedBy(
        document: OutlineDocument,
        position: Int,
        stops: (OutlineNode) -> Boolean,
    ): Int {
        var last = position
        for (index in position + 1 until document.entries.size) {
            val entry = document.entries[index] as? OutlineNode ?: continue
            if (stops(entry)) break
            last = index
        }
        return last
    }

    private fun outdentOnce(indent: String): String =
        if (indent.startsWith(INDENT)) indent.drop(INDENT.length) else indent.trimStart(' ')

    /** [indent] moved by [levels] (negative = shallower), one level at a time as the toolbar would. */
    private fun shiftLevel(indent: String, levels: Int): String = when {
        levels > 0 -> INDENT.repeat(levels) + indent
        levels < 0 -> (1..-levels).fold(indent) { acc, _ -> outdentOnce(acc) }
        else -> indent
    }

    /** Indent levels a line has, spaces only — what indent/outdent add and remove. */
    private val OutlineNode.level: Int
        get() = indent.takeWhile { it == ' ' }.length / INDENT.length

    private fun OutlineDocument.position(id: Int): Int {
        val position = entries.indexOfFirst { it.id == id }
        require(position >= 0) { "no entry with id $id" }
        return position
    }

    private fun OutlineDocument.replacing(position: Int, entry: OutlineEntry): OutlineDocument =
        copy(entries = entries.toMutableList().apply { set(position, entry) })

    private fun OutlineDocument.mapNodes(
        range: IntRange,
        transform: (OutlineNode) -> OutlineNode,
    ): OutlineDocument = copy(
        entries = entries.mapIndexed { index, entry ->
            if (index in range && entry is OutlineNode) transform(entry) else entry
        },
    )

    private const val INDENT = "  "
    private const val ITEM_MARKER = "- "
}

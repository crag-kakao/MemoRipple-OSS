package io.github.cragcoffee.memoripple.domain

/** A body edit expressed as plain text plus a selection, so this stays free of UI types. */
data class OutlineEdit(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

/**
 * Line-start editing for the Work Comment syntax recognised by [WorkCommentSyntax].
 *
 * The editor toolbar acts on the line the cursor is on, not on the end of the body, and a marker
 * that is already there is removed rather than stacked. Every operation keeps the caret on the same
 * character of the line it started on.
 *
 * Marks are handled by ROLE, not by glyph: a line marked `※ ` carries the same 補足 role as one
 * marked `> `, whichever set the toolbar is currently set to insert. Toggling a role a line
 * already carries removes it — again whichever set wrote it — and writing one uses the chosen
 * set's glyph. Checkbox flips stay inside the line's own set: `- [ ]` becomes `- [x]`, ☐ becomes
 * the chosen set's done mark (☑ or ✅), never a cross-set mixture.
 */
object WorkOutlineEditing {

    /** The roles the toolbar's chips offer, in their bar order. */
    val chipRoles = listOf(
        OutlineSymbolRole.HEADING,
        OutlineSymbolRole.ITEM,
        OutlineSymbolRole.NOTE,
        OutlineSymbolRole.IMPORTANT,
        OutlineSymbolRole.QUESTION,
    )

    /** State of the checkbox on a line, used by the toolbar to decide what the next tap does. */
    enum class TaskState { NONE, OPEN, DONE }

    private const val INDENT = "  "
    private const val INDENT_WIDTH = 2

    /** The marker standing at a line's start: its role and its literal length, space included. */
    private data class LineMarker(val role: OutlineSymbolRole, val consumed: Int)

    /**
     * Applies [role]'s marker (in [set]'s glyphs) to every line the selection touches. If all
     * of those lines already carry that role — in any set's glyphs — it is removed instead; a
     * different marker is replaced.
     */
    fun toggleMarker(
        edit: OutlineEdit,
        role: OutlineSymbolRole,
        selection: OutlineSymbolSelection = OutlineSymbolSelection.Default,
    ): OutlineEdit {
        require(role in chipRoles) { "not a chip role: $role" }
        val lines = edit.lineRange()
        val alreadyMarked = lines.all { carriesRole(edit.text.lineAt(it), role) }
        return edit.mapLines(lines) { line ->
            val indent = line.takeWhile { it == ' ' }
            val body = line.drop(indent.length)
            val bare = markerOf(body)?.let { body.drop(it.consumed) } ?: body
            if (alreadyMarked) indent + bare else indent + selection.marker(role) + bare
        }
    }

    /**
     * 流れ方 chips: writes [token] at the end of every line the selection touches, replacing
     * a token of the same kind. The caret keeps its character; the line end changes alone.
     */
    fun applyFlowModifier(edit: OutlineEdit, token: String): OutlineEdit =
        edit.mapLines(edit.lineRange()) { line ->
            io.github.cragcoffee.memoripple.domain.comments.CommentLineModifiers
                .applyChipToken(line, token)
        }

    /** Writes [text] where the cursor is, replacing whatever was selected. */
    fun insert(edit: OutlineEdit, text: String): OutlineEdit {
        val start = edit.selectionStart.coerceIn(0, edit.text.length)
        val end = edit.selectionEnd.coerceIn(start, edit.text.length)
        val updated = edit.text.replaceRange(start, end, text)
        return OutlineEdit(updated, start + text.length, start + text.length)
    }

    /** Adds one indent level to every line the selection touches. */
    fun indent(edit: OutlineEdit): OutlineEdit =
        edit.mapLines(edit.lineRange()) { INDENT + it }

    /** Removes one indent level from every line the selection touches, never past column zero. */
    fun outdent(edit: OutlineEdit): OutlineEdit =
        edit.mapLines(edit.lineRange()) { line ->
            if (line.startsWith(INDENT)) line.drop(INDENT_WIDTH) else line.trimStart(' ')
        }

    /** True when every line the selection touches already carries [role], in any set. */
    fun isMarkerActive(edit: OutlineEdit, role: OutlineSymbolRole): Boolean {
        val lines = edit.lineRange()
        return lines.all { carriesRole(edit.text.lineAt(it), role) }
    }

    /**
     * Moves the checkbox on every line the selection touches one step round: no checkbox, unchecked,
     * checked, gone. One control covers the whole life of a task, which is how a checkbox is used.
     * A new box is written in [set]'s glyphs; an existing one flips within its own.
     */
    fun cycleTask(
        edit: OutlineEdit,
        selection: OutlineSymbolSelection = OutlineSymbolSelection.Default,
    ): OutlineEdit {
        val state = taskState(edit)
        return edit.mapLines(edit.lineRange()) { line ->
            val indent = line.takeWhile { it == ' ' }
            val body = line.drop(indent.length)
            val marker = markerOf(body)
            val bare = marker?.let { body.drop(it.consumed) } ?: body
            when (state) {
                TaskState.NONE -> indent + selection.marker(OutlineSymbolRole.TASK) + bare
                TaskState.OPEN -> indent + doneMarkerFor(body, selection) + bare
                TaskState.DONE -> indent + bare
            }
        }
    }

    /** The state shared by every line the selection touches, or [TaskState.NONE] when they differ. */
    fun taskState(edit: OutlineEdit): TaskState {
        val states = edit.lineRange().map { index ->
            when (markerOf(edit.text.lineAt(index).trimStart(' '))?.role) {
                OutlineSymbolRole.TASK -> TaskState.OPEN
                OutlineSymbolRole.TASK_DONE -> TaskState.DONE
                else -> TaskState.NONE
            }
        }
        return states.distinct().singleOrNull() ?: TaskState.NONE
    }

    /**
     * Flips the checkbox on the source line [lineIndex] of [body]. Only a line that already carries
     * one is touched: this is the reading view tapping a box, not the toolbar making one. The flip
     * stays inside the line's own symbol set; the shared ☐ checks in [preferredSet]'s glyph when
     * that set uses ☐, and as ☑ otherwise.
     */
    fun toggleTaskAt(
        body: String,
        lineIndex: Int,
        selection: OutlineSymbolSelection = OutlineSymbolSelection.Default,
    ): String {
        val split = body.split("\n").toMutableList()
        val line = split.getOrNull(lineIndex) ?: return body
        val indent = line.takeWhile { it == ' ' }
        val rest = line.drop(indent.length)
        val marker = markerOf(rest) ?: return body
        val flipped = when (marker.role) {
            OutlineSymbolRole.TASK ->
                doneMarkerFor(rest, selection) + rest.drop(marker.consumed)
            OutlineSymbolRole.TASK_DONE ->
                openMarkerFor(rest, selection) + rest.drop(marker.consumed)
            else -> return body
        }
        split[lineIndex] = indent + flipped
        return split.joinToString("\n")
    }

    /** A task line's box: which line, where its marker starts in the body, and whether it is ticked. */
    data class TaskBox(val line: Int, val rawOffset: Int, val done: Boolean)

    /**
     * Every task line's box, whichever set wrote it — for a surface that draws the body and
     * wants a tap on the box to tick the line rather than land the caret beside it.
     */
    fun taskBoxes(body: String): List<TaskBox> {
        val boxes = mutableListOf<TaskBox>()
        var offset = 0
        body.split("\n").forEachIndexed { index, line ->
            val indent = line.takeWhile { it == ' ' }.length
            when (markerOf(line.drop(indent))?.role) {
                OutlineSymbolRole.TASK -> boxes += TaskBox(index, offset + indent, done = false)
                OutlineSymbolRole.TASK_DONE -> boxes += TaskBox(index, offset + indent, done = true)
                else -> Unit
            }
            offset += line.length + 1
        }
        return boxes
    }

    /** Character ranges of the lines carrying a checked task, for styling them as done. */
    fun doneTaskRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var offset = 0
        text.split("\n").forEach { line ->
            if (markerOf(line.trimStart(' '))?.role == OutlineSymbolRole.TASK_DONE) {
                ranges += offset until (offset + line.length)
            }
            offset += line.length + 1
        }
        return ranges
    }

    /**
     * The done mark matching the open task at the start of [content]. A standard `- [ ]`
     * always checks as `- [x]`; a ☐ checks in the writer's own chosen 完了 glyph — and never
     * as a cross-set `- [x]`, so a symbol line stays a symbol line.
     */
    private fun doneMarkerFor(content: String, selection: OutlineSymbolSelection): String =
        if (content.startsWith(WorkCommentSyntax.TASK_MARKER)) {
            WorkCommentSyntax.TASK_DONE_MARKER
        } else {
            val chosen = selection.setFor(OutlineSymbolRole.TASK_DONE)
            if (chosen == OutlineSymbolSet.STANDARD) {
                OutlineSymbolSet.JAPANESE.marker(OutlineSymbolRole.TASK_DONE)
            } else {
                chosen.marker(OutlineSymbolRole.TASK_DONE)
            }
        }

    /** The open mark matching the done task at the start of [content], inside its own kind. */
    private fun openMarkerFor(content: String, selection: OutlineSymbolSelection): String =
        if (content.startsWith(WorkCommentSyntax.TASK_DONE_MARKER)) {
            WorkCommentSyntax.TASK_MARKER
        } else {
            val chosen = selection.setFor(OutlineSymbolRole.TASK)
            if (chosen == OutlineSymbolSet.STANDARD) {
                OutlineSymbolSet.JAPANESE.marker(OutlineSymbolRole.TASK)
            } else {
                chosen.marker(OutlineSymbolRole.TASK)
            }
        }

    /**
     * The marker a line starts with, whichever set — or way of writing a heading — put it
     * there. A heading read out of a Markdown file is still a heading, so the toolbar treats
     * it as one.
     */
    private fun markerOf(content: String): LineMarker? {
        OutlineSymbols.match(content)?.let { return LineMarker(it.role, it.consumed) }
        WorkCommentSyntax.markdownHeading.find(content)?.let {
            return LineMarker(OutlineSymbolRole.HEADING, it.value.length)
        }
        return null
    }

    /** True when [content]'s line already carries [role], in any of its spellings. */
    private fun carriesRole(line: String, role: OutlineSymbolRole): Boolean =
        markerOf(line.trimStart(' '))?.role == role

    /** Indices of the lines the selection touches, inclusive. */
    private fun OutlineEdit.lineRange(): IntRange {
        val starts = text.lineStartOffsets()
        val first = starts.indexOfLast { it <= selectionStart.coerceIn(0, text.length) }
        val last = starts.indexOfLast { it <= selectionEnd.coerceIn(0, text.length) }
        return first.coerceAtLeast(0)..last.coerceAtLeast(0)
    }

    private fun OutlineEdit.mapLines(lines: IntRange, transform: (String) -> String): OutlineEdit {
        val split = text.split("\n").toMutableList()
        if (lines.first !in split.indices) return this
        var deltaBeforeStart = 0
        var deltaTotal = 0
        for (index in lines) {
            if (index !in split.indices) break
            val before = split[index]
            val after = transform(before)
            split[index] = after
            val delta = after.length - before.length
            deltaTotal += delta
            if (index == lines.first) deltaBeforeStart = delta
        }
        val updated = split.joinToString("\n")
        val start = (selectionStart + deltaBeforeStart).coerceIn(0, updated.length)
        val end = (selectionEnd + deltaTotal).coerceIn(start, updated.length)
        return OutlineEdit(updated, start, end)
    }

    private fun String.lineStartOffsets(): List<Int> {
        val offsets = mutableListOf(0)
        forEachIndexed { index, c -> if (c == '\n') offsets += index + 1 }
        return offsets
    }

    // A slice, not a split: the toolbar asks for the caret's line several times per keystroke
    // and per caret move, and splitting the whole body into a list of strings each time turned
    // that into the editor's costliest habit. Out-of-range indices still answer "".
    private fun String.lineAt(index: Int): String {
        if (index < 0) return ""
        var start = 0
        repeat(index) {
            val next = indexOf('\n', start)
            if (next < 0) return ""
            start = next + 1
        }
        val end = indexOf('\n', start).let { if (it < 0) length else it }
        return substring(start, end)
    }
}

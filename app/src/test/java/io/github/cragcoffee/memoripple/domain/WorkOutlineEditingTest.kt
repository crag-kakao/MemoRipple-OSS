package io.github.cragcoffee.memoripple.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkOutlineEditingTest {

    private val HEADING = OutlineSymbolRole.HEADING
    private val ITEM = OutlineSymbolRole.ITEM

    /** A whole-set selection, the way the preference's legacy value decodes. */
    private fun all(set: OutlineSymbolSet) = OutlineSymbolSelection.decode(set.storageId)

    private fun edit(text: String, start: Int, end: Int = start) = OutlineEdit(text, start, end)

    @Test
    fun markerAppliesToTheLineHoldingTheCursorNotTheEndOfTheBody() {
        val result = WorkOutlineEditing.toggleMarker(edit("一行目\n二行目\n三行目", start = 5), HEADING)

        assertEquals("一行目\n■ 二行目\n三行目", result.text)
    }

    @Test
    fun tappingTheSameMarkerAgainRemovesIt() {
        val once = WorkOutlineEditing.toggleMarker(edit("見出し", start = 0), HEADING)
        val twice = WorkOutlineEditing.toggleMarker(once, HEADING)

        assertEquals("■ 見出し", once.text)
        assertEquals("見出し", twice.text)
    }

    @Test
    fun aDifferentMarkerReplacesTheExistingOneInsteadOfStacking() {
        val result = WorkOutlineEditing.toggleMarker(edit("■ 見出し", start = 3), ITEM)

        assertEquals("- 見出し", result.text)
    }

    @Test
    fun markerIsAppliedAfterTheIndentSoDepthSurvives() {
        val result = WorkOutlineEditing.toggleMarker(edit("    子項目", start = 6), ITEM)

        assertEquals("    - 子項目", result.text)
        assertEquals(2, WorkCommentSyntax.recognize(result.text)?.depth)
    }

    @Test
    fun aSelectionSpanningLinesMarksEveryLineItTouches() {
        val result = WorkOutlineEditing.toggleMarker(edit("一\n二\n三", start = 0, end = 3), ITEM)

        assertEquals("- 一\n- 二\n三", result.text)
    }

    @Test
    fun aSelectionAlreadyFullyMarkedIsCleared() {
        val marked = WorkOutlineEditing.toggleMarker(edit("一\n二", start = 0, end = 3), ITEM)
        val cleared = WorkOutlineEditing.toggleMarker(marked, ITEM)

        assertEquals("一\n二", cleared.text)
    }

    @Test
    fun indentAndOutdentMoveByTheWidthTheSyntaxRecognises() {
        val indented = WorkOutlineEditing.indent(edit("- 項目", start = 2))
        assertEquals("  - 項目", indented.text)
        assertEquals(1, WorkCommentSyntax.recognize(indented.text)?.depth)

        val back = WorkOutlineEditing.outdent(indented)
        assertEquals("- 項目", back.text)
        assertEquals(0, WorkCommentSyntax.recognize(back.text)?.depth)
    }

    @Test
    fun outdentAtColumnZeroLeavesTheLineAlone() {
        val result = WorkOutlineEditing.outdent(edit("- 項目", start = 0))

        assertEquals("- 項目", result.text)
    }

    @Test
    fun theCaretStaysOnTheSameCharacterAfterMarking() {
        val result = WorkOutlineEditing.toggleMarker(edit("見出し", start = 3), HEADING)

        assertEquals("■ 見出し", result.text)
        assertEquals(5, result.selectionStart)
    }

    @Test
    fun activeStateReportsWhatTheCursorLineCarries() {
        assertTrue(WorkOutlineEditing.isMarkerActive(edit("■ 見出し", start = 2), HEADING))
        assertFalse(WorkOutlineEditing.isMarkerActive(edit("■ 見出し", start = 2), ITEM))
    }

    @Test
    fun aHeadingWrittenWithHashesIsStillAHeadingToTheToolbar() {
        // A file written elsewhere marks its headings with hashes. The toolbar sees a heading.
        assertTrue(WorkOutlineEditing.isMarkerActive(edit("## 今週", start = 4), HEADING))
        assertTrue(WorkOutlineEditing.isMarkerActive(edit("# 週報", start = 3), HEADING))

        // And tapping the heading action on one takes the heading off, rather than stacking.
        assertEquals("今週", WorkOutlineEditing.toggleMarker(edit("## 今週", start = 4), HEADING).text)
    }

    @Test
    fun aHashHeadingCarriesItsDepthInTheNumberOfHashes() {
        assertEquals(0, WorkCommentSyntax.recognize("# 週報")?.depth)
        assertEquals(1, WorkCommentSyntax.recognize("## 今週")?.depth)
        assertEquals(2, WorkCommentSyntax.recognize("### 詳細")?.depth)
        assertEquals("今週", WorkCommentSyntax.recognize("## 今週")?.text)
        assertEquals(WorkLineType.HEADING, WorkCommentSyntax.recognize("###### 底")?.type)
    }

    @Test
    fun indentAndHashesBothAddDepth() {
        assertEquals(1, WorkCommentSyntax.recognize("  ■ 子見出し")?.depth)
        assertEquals(2, WorkCommentSyntax.recognize("  ## 子見出し")?.depth)
    }

    @Test
    fun aHashWithoutASpaceIsNotAHeading() {
        assertEquals(null, WorkCommentSyntax.recognize("#仕事"))
        assertEquals(null, WorkCommentSyntax.recognize("####### 七つ"))
    }

    @Test
    fun theCheckboxGoesRoundFromNothingToOpenToDoneAndBackToNothing() {
        val open = WorkOutlineEditing.cycleTask(edit("買い物", start = 0))
        assertEquals("- [ ] 買い物", open.text)

        val done = WorkOutlineEditing.cycleTask(open)
        assertEquals("- [x] 買い物", done.text)

        val cleared = WorkOutlineEditing.cycleTask(done)
        assertEquals("買い物", cleared.text)
    }

    @Test
    fun aTaskIsRecognisedAsATaskRatherThanAnItemThatStartsWithABracket() {
        assertEquals(WorkLineType.TASK, WorkCommentSyntax.recognize("- [ ] 買い物")?.type)
        assertEquals(WorkLineType.TASK_DONE, WorkCommentSyntax.recognize("- [x] 買い物")?.type)
        assertEquals(WorkLineType.ITEM, WorkCommentSyntax.recognize("- 買い物")?.type)
        assertEquals("買い物", WorkCommentSyntax.recognize("- [x] 買い物")?.text)
    }

    @Test
    fun aCheckboxKeepsItsIndentSoNestedTasksStayNested() {
        val result = WorkOutlineEditing.cycleTask(edit("    子タスク", start = 6))

        assertEquals("    - [ ] 子タスク", result.text)
        assertEquals(2, WorkCommentSyntax.recognize(result.text)?.depth)
    }

    @Test
    fun theCheckboxReplacesAnOutlineMarkerInsteadOfStackingOnIt() {
        val result = WorkOutlineEditing.cycleTask(edit("■ 見出し", start = 0))

        assertEquals("- [ ] 見出し", result.text)
    }

    @Test
    fun anOutlineMarkerReplacesTheCheckboxInsteadOfStackingOnIt() {
        val task = WorkOutlineEditing.cycleTask(edit("項目", start = 0))
        val heading = WorkOutlineEditing.toggleMarker(task, HEADING)

        assertEquals("■ 項目", heading.text)
    }

    @Test
    fun linesInMixedStatesAreBroughtToOneStateFirst() {
        val mixed = edit("- [ ] 一\n- [x] 二", start = 0, end = 12)

        assertEquals(WorkOutlineEditing.TaskState.NONE, WorkOutlineEditing.taskState(mixed))
        assertEquals("- [ ] 一\n- [ ] 二", WorkOutlineEditing.cycleTask(mixed).text)
    }

    @Test
    fun doneRangesCoverOnlyTheCheckedLines() {
        val body = "- [ ] 一\n- [x] 二\n三"

        // "- [ ] 一" is seven characters, so the checked line starts just past its newline.
        assertEquals(listOf(8 until 15), WorkOutlineEditing.doneTaskRanges(body))
    }

    @Test
    fun insertingWritesWhereTheCursorIsAndLeavesItAfterTheText() {
        val result = WorkOutlineEditing.insert(edit("ここに", start = 3), "差し込み")

        assertEquals("ここに差し込み", result.text)
        assertEquals(7, result.selectionStart)
        assertEquals(7, result.selectionEnd)
    }

    @Test
    fun insertingReplacesWhateverWasSelected() {
        assertEquals("差し込み", WorkOutlineEditing.insert(edit("下書き", 0, 3), "差し込み").text)
    }

    @Test
    fun everyToolbarMarkerRoundTripsThroughTheSharedSyntaxInEverySet() {
        OutlineSymbolSet.entries.forEach { set ->
            WorkOutlineEditing.chipRoles.forEach { role ->
                val applied = WorkOutlineEditing.toggleMarker(edit("本文", start = 0), role, all(set))

                assertEquals(set.marker(role) + "本文", applied.text)
                assertEquals("本文", WorkCommentSyntax.recognize(applied.text)?.text)
            }
        }
    }

    @Test
    fun aRoleWrittenInOneSetIsRecognisedAndClearedByAnother() {
        // The chip acts by meaning: a ※ line is the 補足 the > chip toggles off, and a line
        // marked in one set replaced through another wears the newer set's glyph.
        val japanese = WorkOutlineEditing.toggleMarker(
            edit("補足です", start = 0),
            OutlineSymbolRole.NOTE,
            all(OutlineSymbolSet.JAPANESE),
        )
        assertEquals("※ 補足です", japanese.text)
        assertTrue(WorkOutlineEditing.isMarkerActive(japanese, OutlineSymbolRole.NOTE))
        assertEquals(
            "補足です",
            WorkOutlineEditing.toggleMarker(japanese, OutlineSymbolRole.NOTE).text,
        )
        assertEquals(
            "⚠️ 補足です",
            WorkOutlineEditing.toggleMarker(
                japanese,
                OutlineSymbolRole.IMPORTANT,
                all(OutlineSymbolSet.EMOJI),
            ).text,
        )
    }

    @Test
    fun aCheckboxFlipsInsideItsOwnSet() {
        // ☐ checks in the writer's current set when that set uses ☐, and never as - [x].
        assertEquals(
            "☑ 買い物",
            WorkOutlineEditing.toggleTaskAt("☐ 買い物", 0, all(OutlineSymbolSet.JAPANESE)),
        )
        assertEquals(
            "✅ 買い物",
            WorkOutlineEditing.toggleTaskAt("☐ 買い物", 0, all(OutlineSymbolSet.EMOJI)),
        )
        assertEquals(
            "☑ 買い物",
            WorkOutlineEditing.toggleTaskAt("☐ 買い物", 0, all(OutlineSymbolSet.STANDARD)),
        )
        // Checked marks of any set reopen as their own set's box.
        assertEquals("☐ 買い物", WorkOutlineEditing.toggleTaskAt("✅ 買い物", 0))
        assertEquals("☐ 買い物", WorkOutlineEditing.toggleTaskAt("☑ 買い物", 0))
        assertEquals(
            "- [x] 買い物",
            WorkOutlineEditing.toggleTaskAt("- [ ] 買い物", 0, all(OutlineSymbolSet.EMOJI)),
        )
        assertEquals("- [ ] 買い物", WorkOutlineEditing.toggleTaskAt("- [x] 買い物", 0))
    }

    @Test
    fun theTaskCycleWritesTheChosenSetAndChecksTheLineOwnSet() {
        val fresh = WorkOutlineEditing.cycleTask(edit("用事", start = 0), all(OutlineSymbolSet.EMOJI))
        assertEquals("☐ 用事", fresh.text)
        val checked = WorkOutlineEditing.cycleTask(fresh, all(OutlineSymbolSet.EMOJI))
        assertEquals("✅ 用事", checked.text)
        assertEquals("用事", WorkOutlineEditing.cycleTask(checked).text)
        // A standard box cycled while another set is chosen stays standard.
        val standard = WorkOutlineEditing.cycleTask(
            edit("- [ ] 用事", start = 0),
            all(OutlineSymbolSet.JAPANESE),
        )
        assertEquals("- [x] 用事", standard.text)
    }

    @Test
    fun doneRangesSeeEverySetsCheckedMark() {
        val body = "- [x] 一\n☑ 二\n✅ 三\n- [ ] 未"
        assertEquals(3, WorkOutlineEditing.doneTaskRanges(body).size)
    }

    @Test
    fun aMixedSelectionWritesEachRoleInItsOwnGlyph() {
        // 見出しは◆、項目は標準の - : each chip writes its own chosen glyph.
        val mixed = OutlineSymbolSelection.Default
            .with(OutlineSymbolRole.HEADING, OutlineSymbolSet.JAPANESE)
        assertEquals(
            "◆ 章の名",
            WorkOutlineEditing.toggleMarker(edit("章の名", start = 0), HEADING, mixed).text,
        )
        assertEquals(
            "- 買う物",
            WorkOutlineEditing.toggleMarker(edit("買う物", start = 0), ITEM, mixed).text,
        )
        // A ☐ checks in the chosen 完了 glyph even when the open glyph came from elsewhere.
        val emojiDone = mixed.with(OutlineSymbolRole.TASK_DONE, OutlineSymbolSet.EMOJI)
        assertEquals("✅ 用事", WorkOutlineEditing.toggleTaskAt("☐ 用事", 0, emojiDone))
    }
}

/** Where the boxes stand in a body, for the editor to take a tap on one. */
class TaskBoxesTest {
    @Test
    fun everyTaskLineReportsItsBoxAfterTheIndentWhicheverSetWroteIt() {
        val body = "見出し\n- [ ] 牛乳\n  - [x] パン\n☐ 卵\n・ 塩"
        val boxes = WorkOutlineEditing.taskBoxes(body)
        assertEquals(
            listOf(
                WorkOutlineEditing.TaskBox(line = 1, rawOffset = 4, done = false),
                WorkOutlineEditing.TaskBox(line = 2, rawOffset = 15, done = true),
                WorkOutlineEditing.TaskBox(line = 3, rawOffset = 24, done = false),
            ),
            boxes,
        )
        assertEquals(emptyList<WorkOutlineEditing.TaskBox>(), WorkOutlineEditing.taskBoxes(""))
    }
}

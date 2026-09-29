package io.github.cragcoffee.memoripple.domain.outline

import io.github.cragcoffee.memoripple.domain.BodyReading
import io.github.cragcoffee.memoripple.domain.OutlineEdit
import io.github.cragcoffee.memoripple.domain.WorkOutlineEditing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outline operations must mean exactly what the existing text operations mean — the
 * same characters, line for line — and must never leave a parent without its children or
 * a child under the wrong parent. Folding changes what is shown, never what is written.
 */
class OutlineEditingTest {

    private fun doc(text: String) = OutlineText.parse(text)
    private fun text(document: OutlineDocument) = OutlineText.serialize(document)
    private fun idOfLine(document: OutlineDocument, line: Int) = document.entries[line].id

    // --- updateText ---

    @Test
    fun updateTextChangesOnlyTheWordsOfThatLine() {
        val document = doc("- 一\n  - 二")

        val updated = OutlineEditing.updateText(document, idOfLine(document, 1), "二を書き直す ←")

        assertEquals("- 一\n  - 二を書き直す ←", text(updated))
        assertEquals(document.entries[0], updated.entries[0])
        assertEquals(idOfLine(document, 1), updated.entries[1].id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun updateTextRefusesANewlineBecauseThatIsASplit() {
        val document = doc("- 一")
        OutlineEditing.updateText(document, idOfLine(document, 0), "一\n二")
    }

    // --- insertAfter / split ---

    @Test
    fun enterOnALeafAddsANextSiblingWearingTheSameMarker() {
        val document = doc("- 一\n- 二")

        val (updated, newId) = OutlineEditing.insertAfter(document, idOfLine(document, 0))

        assertEquals("- 一\n- \n- 二", text(updated))
        assertEquals(document.nextId, newId)
        assertEquals(newId, updated.entries[1].id)
        assertEquals(document.nextId + 1, updated.nextId)
        assertEquals(listOf(idOfLine(document, 0), newId, idOfLine(document, 1)), updated.entries.map { it.id })
    }

    @Test
    fun enterOnAParentAddsAFirstChildSoTheOldChildrenKeepTheirParent() {
        val document = doc("- 親\n  - 子一\n  - 子二\n- 次")

        val (updated, newId) = OutlineEditing.insertAfter(document, idOfLine(document, 0))

        assertEquals("- 親\n  - \n  - 子一\n  - 子二\n- 次", text(updated))
        assertEquals(newId, updated.entries[1].id)
        // Everyone who had a parent still has the same one; the new line is the parent's first child.
        assertTrue(OutlineEditing.hasChildren(updated, idOfLine(document, 0)))
        assertEquals(2, (updated.entries[1] as OutlineNode).indent.length)
    }

    @Test
    fun enterOnADeepLeafAddsTheSiblingAtTheSameDepthAndTheRestIsUntouched() {
        val document = doc("- 一\n  - 二\n    - 三\n- 四")

        val (updated, _) = OutlineEditing.insertAfter(document, idOfLine(document, 2))

        assertEquals("- 一\n  - 二\n    - 三\n    - \n- 四", text(updated))
    }

    @Test
    fun enterOnProseStaysProse() {
        val document = doc("本文の一行")

        val (updated, _) = OutlineEditing.insertAfter(document, idOfLine(document, 0))

        assertEquals("本文の一行\n", text(updated))
        assertNull((updated.entries[1] as OutlineNode).role)
    }

    @Test
    fun enterOnALeafCanBeAskedForADifferentMarker() {
        val document = doc("本文")

        val (updated, _) = OutlineEditing.insertAfter(document, idOfLine(document, 0), marker = "- ")

        assertEquals("本文\n- ", text(updated))
    }

    @Test
    fun splitMovesTheWordsAfterTheCaretToTheNewLine() {
        val document = doc("- 前と後 ←←")

        val (updated, newId) = OutlineEditing.split(document, idOfLine(document, 0), caret = 2)

        assertEquals("- 前と\n- 後 ←←", text(updated))
        assertEquals(newId, updated.entries[1].id)
    }

    @Test
    fun splitAtTheEndIsEnter() {
        val document = doc("- 一\n  - 子")

        val (updated, _) = OutlineEditing.split(document, idOfLine(document, 0), caret = 1)

        assertEquals("- 一\n  - \n  - 子", text(updated))
    }

    // --- indent ---

    @Test
    fun indentUnderAPreviousSiblingAddsTwoSpaces() {
        val document = doc("- 一\n- 二")

        val updated = OutlineEditing.indent(document, idOfLine(document, 1))

        assertEquals("- 一\n  - 二", text(updated))
        assertTrue(OutlineEditing.hasChildren(updated, idOfLine(document, 0)))
    }

    @Test
    fun indentTakesTheDescendantsAlong() {
        val document = doc("- 一\n- 二\n  - 二の子\n    - 孫\n- 三")

        val updated = OutlineEditing.indent(document, idOfLine(document, 1))

        assertEquals("- 一\n  - 二\n    - 二の子\n      - 孫\n- 三", text(updated))
    }

    @Test
    fun aFirstChildCannotBeIndentedAndNothingChanges() {
        val document = doc("- 親\n  - 最初の子\n  - 二番目")

        assertFalse(OutlineEditing.canIndent(document, idOfLine(document, 1)))
        assertSame(document, OutlineEditing.indent(document, idOfLine(document, 1)))
        assertTrue(OutlineEditing.canIndent(document, idOfLine(document, 2)))
    }

    @Test
    fun theFirstLineCannotBeIndented() {
        val document = doc("- 一\n- 二")

        assertFalse(OutlineEditing.canIndent(document, idOfLine(document, 0)))
        assertSame(document, OutlineEditing.indent(document, idOfLine(document, 0)))
    }

    @Test
    fun aBlankLineBetweenSiblingsDoesNotBreakTheSiblingRelation() {
        val document = doc("- 一\n\n- 二")

        val updated = OutlineEditing.indent(document, idOfLine(document, 2))

        assertEquals("- 一\n\n  - 二", text(updated))
    }

    @Test
    fun aLineWrittenTwoLevelsDeeperThanItsParentIsReadButNotIndentedFurther() {
        // Writers can produce this with the text toolbar; the outline must read it and not break it.
        val document = doc("- 一\n    - 飛んだ")

        assertTrue(OutlineEditing.hasChildren(document, idOfLine(document, 0)))
        assertFalse(OutlineEditing.canIndent(document, idOfLine(document, 1)))
        assertSame(document, OutlineEditing.indent(document, idOfLine(document, 1)))
    }

    @Test
    fun proseIndentsLikeAnyOtherLine() {
        val document = doc("一行目\n二行目")

        assertEquals("一行目\n  二行目", text(OutlineEditing.indent(document, idOfLine(document, 1))))
    }

    // --- outdent ---

    @Test
    fun outdentRemovesTwoSpacesAndTakesTheDescendantsAlong() {
        val document = doc("- 一\n  - 二\n    - 二の子\n  - 三")

        val updated = OutlineEditing.outdent(document, idOfLine(document, 1))

        // 三 stays where it was written; being deeper than 二 now, it reads as 二's child.
        assertEquals("- 一\n- 二\n  - 二の子\n  - 三", text(updated))
    }

    @Test
    fun outdentAtTheMarginChangesNothing() {
        val document = doc("- 一\n  - 二")

        assertFalse(OutlineEditing.canOutdent(document, idOfLine(document, 0)))
        assertSame(document, OutlineEditing.outdent(document, idOfLine(document, 0)))
    }

    @Test
    fun outdentOfAnOddIndentTrimsToTheMarginLikeTheTextToolbar() {
        val document = doc(" - 一つ空き\n   - 三つ空き")

        // The one-space line trims to the margin; its deeper child comes along, losing two.
        assertEquals("- 一つ空き\n - 三つ空き", text(OutlineEditing.outdent(document, idOfLine(document, 0))))
        assertEquals(" - 一つ空き\n - 三つ空き", text(OutlineEditing.outdent(document, idOfLine(document, 1))))
    }

    @Test
    fun aMarkdownHeadingKeepsItsHashesWhenIndentedAndOutdented() {
        val document = doc("# 一\n# 二")

        val deeper = OutlineEditing.indent(document, idOfLine(document, 1))
        assertEquals("# 一\n  # 二", text(deeper))
        assertEquals("# 一\n# 二", text(OutlineEditing.outdent(deeper, idOfLine(document, 1))))
        // A second-level hash heading is already a child of the first: nothing to indent under.
        assertFalse(OutlineEditing.canIndent(doc("# 一\n## 二"), 2))
    }

    // --- parity with the text toolbar ---

    @Test
    fun indentAndOutdentOfOneLeafWriteExactlyWhatTheTextToolbarWrites() {
        // Lines that have a previous sibling, so both sides agree that indenting is allowed.
        val indentable = listOf(
            "- 一\n- 二" to 1,
            "- 一\n  - 二\n  - 三" to 2,
            "一\n二" to 1,
            "- 一\n \t- 空きとタブ" to 1,
            "# 一\n# 二" to 1,
        )
        indentable.forEach { (body, line) ->
            val document = doc(body)
            val caret = caretAtLine(body, line)
            assertEquals(
                "indent of $body",
                WorkOutlineEditing.indent(OutlineEdit(body, caret, caret)).text,
                text(OutlineEditing.indent(document, idOfLine(document, line))),
            )
        }
        // Outdent has no precondition beyond the margin, so every shape must agree.
        val outdentable = indentable + listOf(
            "- 一\n   - 三つ空き" to 1,
            "# 一\n  ## 二" to 1,
            "- 一\n    - 飛んだ" to 1,
        )
        outdentable.forEach { (body, line) ->
            val document = doc(body)
            val caret = caretAtLine(body, line)
            assertEquals(
                "outdent of $body",
                WorkOutlineEditing.outdent(OutlineEdit(body, caret, caret)).text,
                text(OutlineEditing.outdent(document, idOfLine(document, line))),
            )
        }
    }

    /**
     * The text toolbar indents only the lines the caret touches, while the outline takes a
     * node's descendants along; that is the one deliberate difference, and it is recorded here.
     */
    @Test
    fun theTextToolbarMovesOneLineWhereTheOutlineMovesTheSubtree() {
        val body = "- 一\n- 二\n  - 二の子"
        val caret = caretAtLine(body, 1)

        assertEquals("- 一\n  - 二\n  - 二の子", WorkOutlineEditing.indent(OutlineEdit(body, caret, caret)).text)
        assertEquals("- 一\n  - 二\n    - 二の子", text(OutlineEditing.indent(doc(body), 2)))
    }

    private fun caretAtLine(body: String, line: Int): Int =
        body.split('\n').take(line).sumOf { it.length + 1 }

    // --- fold ---

    @Test
    fun anItemOwnsTheDeeperLinesUnderItAndNotTheBlankAfterThem() {
        val document = doc("- 一\n  - 子\n\n  - 子二\n\n- 二")

        assertEquals(1..3, OutlineEditing.ownedRange(document, 0))
        assertNull(OutlineEditing.ownedRange(document, 1))
        assertNull(OutlineEditing.ownedRange(document, 5))
    }

    @Test
    fun aHeadingOwnsEverythingUpToTheNextHeadingAtItsDepthTheWayTheReadingPageDoes() {
        val body = "■ 一\n- 項目\n本文\n  ■ 小見出し\n- 子項目\n■ 二\nあと"
        val document = doc(body)

        assertEquals(1..4, OutlineEditing.ownedRange(document, 0))
        assertEquals(4..4, OutlineEditing.ownedRange(document, 3))
        assertEquals(6..6, OutlineEditing.ownedRange(document, 5))

        val reading = BodyReading.lines(body)
        listOf(setOf(0), setOf(3), setOf(0, 5), setOf(3, 5)).forEach { sourceLines ->
            val ids = sourceLines.map { document.entries[it].id }.toSet()
            assertEquals(
                "folded $sourceLines",
                BodyReading.visible(reading, sourceLines).map { it.sourceLine },
                OutlineEditing.visible(document, ids).map { document.entries.indexOf(it) },
            )
        }
    }

    @Test
    fun foldingHidesTheOwnedLinesAndWritesNothing() {
        val document = doc("- 一\n  - 子\n    - 孫\n- 二")
        val id = idOfLine(document, 0)

        val collapsed = OutlineEditing.toggleFold(document, emptySet(), id)

        assertEquals(setOf(id), collapsed)
        assertEquals(listOf(0, 3), OutlineEditing.visible(document, collapsed).map { document.entries.indexOf(it) })
        assertEquals("- 一\n  - 子\n    - 孫\n- 二", text(document))
        assertEquals(emptySet<Int>(), OutlineEditing.toggleFold(document, collapsed, id))
    }

    @Test
    fun aLeafCannotBeFolded() {
        val document = doc("- 一\n- 二")

        assertEquals(emptySet<Int>(), OutlineEditing.toggleFold(document, emptySet(), idOfLine(document, 0)))
    }

    @Test
    fun aFoldedNodeTakesItsFoldedDescendantsWithIt() {
        val document = doc("- 一\n  - 子\n    - 孫\n- 二")
        val collapsed = setOf(idOfLine(document, 0), idOfLine(document, 1))

        assertEquals(listOf(0, 3), OutlineEditing.visible(document, collapsed).map { document.entries.indexOf(it) })
    }
}

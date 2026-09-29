package io.github.cragcoffee.memoripple.domain.outline

import io.github.cragcoffee.memoripple.domain.outline.OutlineRows.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/OUTLINE_STABLE_ROWS.md — an outline's lines as rows with lasting ids; the body is their
 * projection, byte for byte; a whole new body is laid onto them line by line.
 */
class OutlineRowsTest {
    private val body = "# 旅行計画\n- 京都へ行く\n  - [ ] 寺院を回る\n  - [x] 宿を取る\n\n- 帰る\n"

    @Test
    fun aBodyBecomesRowsWithUniqueIdsAndTheProjectionIsTheBodyByteForByte() {
        val rows = OutlineRows.fresh(body)
        assertEquals((1..rows.size).toList(), rows.map { it.id })
        assertEquals(body, OutlineRows.projection(rows))
        assertEquals("the same reading the outliner makes", OutlineText.serialize(OutlineText.parse(body)), OutlineRows.projection(rows))
        assertEquals(OutlineText.parse(body), OutlineRows.documentOf(rows))
        listOf("", "\n", "a", "a\n\nb", "  \t- x\r\n- y", "- 絵文字 📌\n").forEach { text ->
            assertEquals("$text", text, OutlineRows.projection(OutlineRows.fresh(text)))
        }
    }

    @Test
    fun aDocumentsRowsKeepTheirIdsAndTheNextIdIsPastTheHighest() {
        val rows = listOf(Row(7, "- a"), Row(3, "  - b"), Row(12, "- c"))
        val document = OutlineRows.documentOf(rows)
        assertEquals(listOf(7, 3, 12), document.entries.map { it.id })
        assertEquals(13, document.nextId)
        assertEquals("never below a floor it was given", 40, OutlineRows.documentOf(rows, floor = 40).nextId)
        assertEquals(rows, OutlineRows.rowsOf(document))
    }

    @Test
    fun aLineAddedAtTheEndIsNewAndEveryOtherLineKeepsItsId() {
        val rows = OutlineRows.fresh("- a\n- b")
        val next = OutlineRows.reconcile(rows, "- a\n- b\n- AIの追記", nextId = 3)
        assertEquals(listOf(Row(1, "- a"), Row(2, "- b"), Row(3, "- AIの追記")), next)
    }

    @Test
    fun aTickedTaskIsTheSameLine() {
        val rows = OutlineRows.fresh("- 一\n- [ ] 二\n- 三")
        val next = OutlineRows.reconcile(rows, "- 一\n- [x] 二\n- 三", nextId = 4)
        assertEquals(listOf(1, 2, 3), next.map { it.id })
        assertEquals("- [x] 二", next[1].line)
    }

    @Test
    fun linesRemovedAndInsertedInTheMiddleKeepTheUnchangedOnes() {
        val rows = listOf(Row(10, "- a"), Row(11, "- b"), Row(12, "- c"), Row(13, "- d"))
        val next = OutlineRows.reconcile(rows, "- a\n- x\n- y\n- d", nextId = 14)
        // b and c were rewritten in place (they keep 11 and 12); nothing new.
        assertEquals(listOf(10, 11, 12, 13), next.map { it.id })
        val shorter = OutlineRows.reconcile(rows, "- a\n- d", nextId = 14)
        assertEquals(listOf(10, 13), shorter.map { it.id })
        val longer = OutlineRows.reconcile(rows, "- a\n- b\n- 新1\n- 新2\n- c\n- d", nextId = 14)
        assertEquals(listOf(10, 11, 14, 15, 12, 13), longer.map { it.id })
    }

    @Test
    fun reconcilingIsDeterministicAndNeverRepeatsAnId() {
        val rows = OutlineRows.fresh((1..50).joinToString("\n") { "- 行$it" })
        val body = (1..50).filter { it % 3 != 0 }.joinToString("\n") { "- 行$it" } + "\n- 追加1\n- 追加2"
        val first = OutlineRows.reconcile(rows, body, nextId = 51)
        assertEquals(first, OutlineRows.reconcile(rows, body, nextId = 51))
        assertEquals(body, OutlineRows.projection(first))
        assertEquals(first.size, first.map { it.id }.toSet().size)
        assertTrue(first.takeLast(2).all { it.id >= 51 })
    }

    @Test
    fun anUnchangedBodyChangesNothing() {
        val rows = listOf(Row(5, "- a"), Row(9, "- b"))
        assertTrue(OutlineRows.reconcile(rows, "- a\n- b", nextId = 10) === rows)
    }
}

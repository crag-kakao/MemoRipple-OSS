package io.github.cragcoffee.memoripple.domain.outline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A fold is remembered by what the folded line is, not by where it is: a key derived from
 * the line's depth, its words and which occurrence of that line it is, so lines added or
 * moved above it do not steal its fold, while a line rewritten simply unfolds.
 */
class OutlineFoldKeysTest {

    private fun doc(text: String) = OutlineText.parse(text)
    private fun id(document: OutlineDocument, line: Int) = document.entries[line].id

    @Test
    fun aKeyIsStableAcrossParsesOfTheSameText() {
        val first = doc("- A\n  - A1\n- B")
        val second = doc("- A\n  - A1\n- B")

        assertEquals(OutlineFoldKeys.keyFor(first, id(first, 0)), OutlineFoldKeys.keyFor(second, id(second, 0)))
        assertNull(OutlineFoldKeys.keyFor(doc("- A\n\n- B"), 2))
    }

    @Test
    fun aFoldSurvivesLinesInsertedAboveAndAMove() {
        val before = doc("- A\n  - A1\n- B\n  - B1")
        val keys = OutlineFoldKeys.keysFor(before, setOf(id(before, 2)))

        val inserted = doc("- 新しい行\n- A\n  - A1\n- B\n  - B1")
        assertEquals(setOf(id(inserted, 3)), OutlineFoldKeys.restore(inserted, keys))

        val moved = doc("- B\n  - B1\n- A\n  - A1")
        assertEquals(setOf(id(moved, 0)), OutlineFoldKeys.restore(moved, keys))
    }

    @Test
    fun rewritingAFoldedLineUnfoldsItRatherThanFoldingSomethingElse() {
        val before = doc("- A\n  - A1\n- B\n  - B1")
        val keys = OutlineFoldKeys.keysFor(before, setOf(id(before, 2)))

        val rewritten = doc("- A\n  - A1\n- C\n  - B1")

        assertEquals(emptySet<Int>(), OutlineFoldKeys.restore(rewritten, keys))
    }

    @Test
    fun identicalLinesAreToldApartByTheirOrder() {
        val document = doc("- 同じ\n  - 一\n- 同じ\n  - 二")

        val first = OutlineFoldKeys.keyFor(document, id(document, 0))
        val second = OutlineFoldKeys.keyFor(document, id(document, 2))

        assertNotEquals(first, second)
        assertEquals(setOf(id(document, 2)), OutlineFoldKeys.restore(document, setOf(second!!)))
    }

    @Test
    fun depthIsPartOfTheKey() {
        val document = doc("- 同じ\n  - 同じ")

        assertNotEquals(OutlineFoldKeys.keyFor(document, id(document, 0)), OutlineFoldKeys.keyFor(document, id(document, 1)))
    }

    @Test
    fun keysAreShortAndSafeToStoreInOnePreferenceValue() {
        val document = doc("- 記号,や;や=や|を含む行\n  - 子")

        val key = OutlineFoldKeys.keyFor(document, id(document, 0))!!

        assertEquals(true, key.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(16, key.length)
    }
}

package io.github.cragcoffee.memoripple.domain.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tree rules at a size no one will reach by hand: a thousand folders, a hundred deep.
 * Every walk is linear in the tree (a queue and a visited set — nothing quadratic), and the
 * whole check stays far below what a frame can afford. Not a benchmark, a tripwire.
 */
class FolderTreeScaleTest {
    // 10 root chains of 100 folders each: id = chain*100 + depth + 1, parent = the one above.
    private val deep = (0 until 10).flatMap { chain ->
        (0 until 100).map { depth ->
            val id = chain * 100L + depth + 1
            FolderNode(id, if (depth == 0) null else id - 1, "f$id")
        }
    }
    // 1000 siblings under one root.
    private val wide = listOf(FolderNode(0, null, "root")) + (1..1000L).map { FolderNode(it, 0, "f$it") }

    @Test
    fun aThousandFoldersAHundredDeepAreCheckedAndWalkedWellWithinASanityBound() {
        val started = System.nanoTime()
        repeat(20) {
            assertTrue(FolderTree.isWellFormed(deep))
            assertEquals(99, FolderTree.ancestors(deep, 100).size)
            assertEquals(99, FolderTree.descendants(deep, 1).size)
            assertTrue(FolderTree.canMoveTo(deep, 1, 900))
            assertTrue(!FolderTree.canMoveTo(deep, 1, 100))
            assertEquals(100, FolderTree.path(deep, 100).size)
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("deep tree: ${elapsedMillis}ms for 20 rounds", elapsedMillis < 2_000)
    }

    @Test
    fun aThousandSiblingsListAndCheckWellWithinASanityBound() {
        val started = System.nanoTime()
        repeat(20) {
            assertTrue(FolderTree.isWellFormed(wide))
            assertEquals(1000, FolderTree.children(wide, 0).size)
            assertEquals(1000, FolderTree.descendants(wide, 0).size)
            assertTrue(FolderTree.canMoveTo(wide, 500, 501))
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("wide tree: ${elapsedMillis}ms for 20 rounds", elapsedMillis < 2_000)
    }
}

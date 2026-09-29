package io.github.cragcoffee.memoripple.domain.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tree drawn as a list: parents before children, siblings in the order the caller asks
 * for, each row carrying its depth. One pass over the folders, whatever their number — the
 * navigator will redraw this on every expand, so it cannot be quadratic.
 */
class FolderTreeFlattenTest {
    private val byName = Comparator<FolderNode> { a, b -> a.name.compareTo(b.name) }

    // 開発(1) ├─ Android(2) │  └─ MemoRipple(3) └─ Docs(4);  雑記(5) — 雑 sorts after 開 by code point
    private val tree = listOf(
        FolderNode(5, null, "雑記"),
        FolderNode(3, 2, "MemoRipple"),
        FolderNode(1, null, "開発"),
        FolderNode(4, 1, "Docs"),
        FolderNode(2, 1, "Android"),
    )

    @Test
    fun parentsComeBeforeChildrenSiblingsFollowTheComparatorAndDepthIsCounted() {
        val rows = FolderTree.flatten(tree, byName)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), rows.map { it.first.id })
        assertEquals(listOf(0, 1, 2, 1, 0), rows.map { it.second })
    }

    @Test
    fun anOrphanAndACycleAreLeftOutRatherThanLoopedOver() {
        val broken = tree + FolderNode(6, 99, "orphan") +
            FolderNode(7, 8, "a") + FolderNode(8, 7, "b")
        val rows = FolderTree.flatten(broken, byName)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), rows.map { it.first.id })
    }

    @Test
    fun aThousandSiblingsAndAHundredDeepChainFlattenInOnePass() {
        val wide = listOf(FolderNode(0, null, "root")) + (1..1000L).map { FolderNode(it, 0, "f%04d".format(it)) }
        val deep = (0 until 100).map { depth -> FolderNode(2000L + depth, if (depth == 0) null else 2000L + depth - 1, "d$depth") }
        val started = System.nanoTime()
        repeat(50) {
            assertEquals(1001, FolderTree.flatten(wide, byName).size)
            val chain = FolderTree.flatten(deep, byName)
            assertEquals(100, chain.size)
            assertEquals(99, chain.last().second)
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("flatten: ${elapsedMillis}ms for 50 rounds", elapsedMillis < 2_000)
    }
}

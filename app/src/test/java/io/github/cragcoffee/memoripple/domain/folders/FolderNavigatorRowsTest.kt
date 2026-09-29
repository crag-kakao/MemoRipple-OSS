package io.github.cragcoffee.memoripple.domain.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigator's rows are a filter over FolderTree.flatten: a folder is shown when every
 * ancestor is expanded. Roots are always shown. A row knows its depth, whether it can be
 * expanded, whether it is, and whether it is the folder the wall is showing.
 */
class FolderNavigatorRowsTest {
    private val byName = Comparator<FolderNode> { a, b -> a.name.compareTo(b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id) }

    // 開発 / MemoRipple / {AI設計, Calendar設計}; 開発 / CharacterLauncher; 創作; 個人
    private val dev = FolderNode(1, null, "開発")
    private val memoRipple = FolderNode(2, 1, "MemoRipple")
    private val ai = FolderNode(3, 2, "AI設計")
    private val calendar = FolderNode(4, 2, "Calendar設計")
    private val launcher = FolderNode(5, 1, "CharacterLauncher")
    private val art = FolderNode(6, null, "創作")
    private val personal = FolderNode(7, null, "個人")
    private val tree = listOf(dev, memoRipple, ai, calendar, launcher, art, personal)

    private fun ids(rows: List<FolderRow>) = rows.map { it.folderId }

    @Test
    fun onlyRootsShowWhenNothingIsExpanded() {
        val rows = FolderNavigator.rows(tree, byName, expanded = emptySet(), selected = null)
        assertEquals(listOf(7L, 6L, 1L).sorted(), ids(rows).sorted())
        assertEquals(listOf(0, 0, 0), rows.map { it.depth })
        assertTrue(rows.first { it.folderId == 1L }.hasChildren)
        assertFalse(rows.first { it.folderId == 6L }.hasChildren)
        assertTrue(rows.none { it.expanded })
        assertTrue(rows.none { it.selected })
    }

    @Test
    fun anExpandedParentShowsItsChildrenAndAnExpandedChildItsGrandchildren() {
        val oneLevel = FolderNavigator.rows(tree, byName, expanded = setOf(1L), selected = null)
        // 開発 (wherever the name order puts it among the roots), then its children by name.
        val fromDev = oneLevel.dropWhile { it.folderId != 1L }
        assertEquals(listOf(1L, 5L, 2L), ids(fromDev).take(3))
        assertEquals(listOf(0, 1, 1), fromDev.take(3).map { it.depth })
        assertTrue(oneLevel.first { it.folderId == 1L }.expanded)
        assertTrue(ids(oneLevel).none { it == 3L || it == 4L })

        val twoLevels = FolderNavigator.rows(tree, byName, expanded = setOf(1L, 2L), selected = null)
        val fromDevTwo = twoLevels.dropWhile { it.folderId != 1L }
        assertEquals(listOf(1L, 5L, 2L, 3L, 4L), ids(fromDevTwo).take(5))
        assertEquals(listOf(0, 1, 1, 2, 2), fromDevTwo.take(5).map { it.depth })
    }

    @Test
    fun collapsingAParentHidesEveryDescendantEvenExpandedOnes() {
        // MemoRipple stays "expanded" in the set, but its parent is closed, so nothing under 開発 shows.
        val rows = FolderNavigator.rows(tree, byName, expanded = setOf(2L), selected = null)
        assertEquals(listOf(7L, 6L, 1L).sorted(), ids(rows).sorted())
    }

    @Test
    fun theSelectedFolderIsMarkedAndItsAncestorsAreWhatRevealsIt() {
        val rows = FolderNavigator.rows(tree, byName, expanded = setOf(1L, 2L), selected = 3L)
        assertEquals(listOf(3L), rows.filter { it.selected }.map { it.folderId })
        assertEquals(setOf(1L, 2L), FolderNavigator.revealing(tree, selected = 3L))
        assertEquals(emptySet<Long>(), FolderNavigator.revealing(tree, selected = 6L))
        assertEquals(emptySet<Long>(), FolderNavigator.revealing(tree, selected = null))
    }

    @Test
    fun orphansAndCyclesStayOutLikeFlatten() {
        val broken = tree + FolderNode(8, 99, "orphan") + FolderNode(9, 10, "cycleA") + FolderNode(10, 9, "cycleB")
        val rows = FolderNavigator.rows(broken, byName, expanded = setOf(1L, 2L, 8L, 9L, 10L), selected = null)
        assertEquals(tree.map { it.id }.toSet(), ids(rows).toSet())
    }

    @Test
    fun fiveThousandFoldersAreProjectedWellWithinASanityBound() {
        // 50 roots × 10 children × 10 grandchildren, everything expanded: 5,550 rows.
        val nodes = ArrayList<FolderNode>()
        var next = 1L
        repeat(50) { r ->
            val root = next++; nodes += FolderNode(root, null, "r$r")
            repeat(10) { c ->
                val child = next++; nodes += FolderNode(child, root, "c$c")
                repeat(10) { g -> nodes += FolderNode(next++, child, "g$g") }
            }
        }
        val expanded = nodes.map { it.id }.toSet()
        val started = System.nanoTime()
        val rows = FolderNavigator.rows(nodes, byName, expanded, selected = nodes.last().id)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(nodes.size, rows.size)
        assertEquals(1, rows.count { it.selected })
        assertTrue("took $elapsedMs ms", elapsedMs < 500)
    }
}

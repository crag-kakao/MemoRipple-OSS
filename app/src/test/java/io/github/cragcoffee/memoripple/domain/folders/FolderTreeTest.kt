package io.github.cragcoffee.memoripple.domain.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folder hierarchy is a tree over folders only. It must never turn into a cycle, a
 * folder may not move under itself or under anything below it, deleting a folder hands its
 * contents to its parent, and the root is `null`, not a record.
 */
class FolderTreeTest {
    // 開発(1) ├─ Android(2) │  └─ MemoRipple(3) └─ Docs(4);  日常(5)
    private val tree = listOf(
        FolderNode(1, null, "開発"),
        FolderNode(2, 1, "Android"),
        FolderNode(3, 2, "MemoRipple"),
        FolderNode(4, 1, "Docs"),
        FolderNode(5, null, "日常"),
    )

    @Test
    fun theRootIsNullAndChildrenAreListedByParent() {
        assertEquals(listOf(1L, 5L), FolderTree.children(tree, null).map { it.id })
        assertEquals(listOf(2L, 4L), FolderTree.children(tree, 1).map { it.id })
        assertEquals(emptyList<FolderNode>(), FolderTree.children(tree, 3))
        assertEquals(emptyList<FolderNode>(), FolderTree.children(tree, 99))
    }

    @Test
    fun ancestorsRunFromTheRootDownAndThePathEndsWithTheFolderItself() {
        assertEquals(listOf("開発", "Android"), FolderTree.ancestors(tree, 3).map { it.name })
        assertEquals(listOf("開発", "Android", "MemoRipple"), FolderTree.path(tree, 3).map { it.name })
        assertEquals(emptyList<FolderNode>(), FolderTree.ancestors(tree, 1))
        assertEquals(listOf(1L), FolderTree.path(tree, 1).map { it.id })
        assertEquals(emptyList<FolderNode>(), FolderTree.path(tree, 99))
    }

    @Test
    fun descendantsReachEveryDepth() {
        assertEquals(setOf(2L, 3L, 4L), FolderTree.descendants(tree, 1))
        assertEquals(setOf(3L), FolderTree.descendants(tree, 2))
        assertEquals(emptySet<Long>(), FolderTree.descendants(tree, 5))
    }

    @Test
    fun aFolderMayMoveToTheRootOrUnderAnotherBranchButNeverUnderItselfOrItsDescendants() {
        assertTrue(FolderTree.canMoveTo(tree, 2, null))
        assertTrue(FolderTree.canMoveTo(tree, 2, 5))
        assertTrue(FolderTree.canMoveTo(tree, 3, 4))
        // A → A
        assertFalse(FolderTree.canMoveTo(tree, 1, 1))
        // A → its own child / grandchild
        assertFalse(FolderTree.canMoveTo(tree, 1, 2))
        assertFalse(FolderTree.canMoveTo(tree, 1, 3))
        // Nowhere: the target must exist.
        assertFalse(FolderTree.canMoveTo(tree, 2, 99))
        assertFalse(FolderTree.canMoveTo(tree, 99, null))
    }

    @Test
    fun aDocumentMayBeHeldByAnExistingFolderOrTheRoot() {
        assertTrue(FolderTree.canHold(tree, null))
        assertTrue(FolderTree.canHold(tree, 3))
        assertFalse(FolderTree.canHold(tree, 99))
    }

    @Test
    fun aSoundTreeHasUniqueIdsPresentParentsAndNoCycle() {
        assertTrue(FolderTree.isWellFormed(tree))
        assertTrue(FolderTree.isWellFormed(emptyList()))
        // A parent=B, B parent=A
        assertFalse(FolderTree.isWellFormed(listOf(FolderNode(1, 2, "A"), FolderNode(2, 1, "B"))))
        // A parent=A
        assertFalse(FolderTree.isWellFormed(listOf(FolderNode(1, 1, "A"))))
        // A three-step ring under a sound root
        assertFalse(
            FolderTree.isWellFormed(
                listOf(FolderNode(1, null, "root"), FolderNode(2, 4, "B"), FolderNode(3, 2, "C"), FolderNode(4, 3, "D")),
            ),
        )
        // A parent that does not exist
        assertFalse(FolderTree.isWellFormed(listOf(FolderNode(1, 9, "A"))))
        // The same id twice
        assertFalse(FolderTree.isWellFormed(listOf(FolderNode(1, null, "A"), FolderNode(1, null, "B"))))
    }

    @Test
    fun deletingAFolderHandsItsContentsToItsParentOrToTheRoot() {
        assertEquals(1L, FolderTree.promotionTarget(tree, 2))
        assertEquals(2L, FolderTree.promotionTarget(tree, 3))
        assertNull(FolderTree.promotionTarget(tree, 1))
        assertNull(FolderTree.promotionTarget(tree, 5))
        assertNull(FolderTree.promotionTarget(tree, 99))
    }

    @Test
    fun aBrokenTreeNeverLoopsForever() {
        val ring = listOf(FolderNode(1, 2, "A"), FolderNode(2, 1, "B"))
        // Ancestry and descent stop when they see a folder twice.
        assertTrue(FolderTree.ancestors(ring, 1).size <= 2)
        assertTrue(FolderTree.descendants(ring, 1).size <= 2)
        assertFalse(FolderTree.canMoveTo(ring, 1, 2))
    }
}

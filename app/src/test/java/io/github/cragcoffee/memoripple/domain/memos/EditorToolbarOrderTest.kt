package io.github.cragcoffee.memoripple.domain.memos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EditorToolbarOrderTest {

    @Test
    fun eachSurfaceShowsItsOwnToolsInTheOriginalOrder() {
        val memo = EditorToolbarOrder.itemsFor(EditorToolbarSurface.MEMO)
        assertFalse(memo.contains(EditorToolbarItem.PROSE_MARKS))
        val note = EditorToolbarOrder.itemsFor(EditorToolbarSurface.NOTE)
        assertFalse(note.contains(EditorToolbarItem.TASK))
        assertFalse(note.contains(EditorToolbarItem.OUTLINE_LABELS))
        assertFalse(note.contains(EditorToolbarItem.INDENT))
        // Each surface's own original order: photo first on both; the memo bar ends with
        // its outline machinery, the note bar closes with the prose marks after the inserts.
        assertEquals(EditorToolbarItem.PHOTO, memo.first())
        assertEquals(EditorToolbarItem.PHOTO, note.first())
        assertEquals(EditorToolbarItem.FLOW_MODIFIERS, memo.last())
        assertEquals(EditorToolbarItem.PROSE_MARKS, note.last())
        assertEquals(
            listOf(
                EditorToolbarItem.LINK,
                EditorToolbarItem.COMMENT_LINK,
                EditorToolbarItem.FLOW_MODIFIERS,
                EditorToolbarItem.PROSE_MARKS,
            ),
            note.takeLast(4),
        )
    }

    @Test
    fun nothingStoredMeansTheSurfaceOriginalOrder() {
        assertEquals(
            EditorToolbarOrder.itemsFor(EditorToolbarSurface.MEMO),
            EditorToolbarOrder.decode(null, EditorToolbarSurface.MEMO),
        )
        assertEquals(
            EditorToolbarOrder.itemsFor(EditorToolbarSurface.NOTE),
            EditorToolbarOrder.decode("", EditorToolbarSurface.NOTE),
        )
    }

    @Test
    fun anArrangementRoundTripsOnItsSurface() {
        val order = EditorToolbarOrder.itemsFor(EditorToolbarSurface.NOTE).reversed()
        assertEquals(
            order,
            EditorToolbarOrder.decode(
                EditorToolbarOrder.encode(order),
                EditorToolbarSurface.NOTE,
            ),
        )
    }

    @Test
    fun aPartialArrangementLeadsAndTheRestFollowInTheirOwnOrder() {
        val decoded = EditorToolbarOrder.decode("comment_link|task", EditorToolbarSurface.MEMO)
        assertEquals(EditorToolbarItem.COMMENT_LINK, decoded[0])
        assertEquals(EditorToolbarItem.TASK, decoded[1])
        assertEquals(
            EditorToolbarOrder.itemsFor(EditorToolbarSurface.MEMO).filterNot {
                it == EditorToolbarItem.COMMENT_LINK || it == EditorToolbarItem.TASK
            },
            decoded.drop(2),
        )
    }

    @Test
    fun anotherSurfacesToolsAreForgivenNotShown() {
        // A memo arrangement mentioning prose marks (or garbage) never puts them on the
        // memo bar — and one surface's stored string never disturbs the other's default.
        val decoded = EditorToolbarOrder.decode(
            "prose_marks|retired_button|bold|bold",
            EditorToolbarSurface.MEMO,
        )
        assertEquals(EditorToolbarItem.BOLD, decoded.first())
        assertFalse(decoded.contains(EditorToolbarItem.PROSE_MARKS))
        assertEquals(EditorToolbarOrder.itemsFor(EditorToolbarSurface.MEMO).size, decoded.size)

        val noteDecoded = EditorToolbarOrder.decode("task|outline_labels", EditorToolbarSurface.NOTE)
        assertEquals(EditorToolbarOrder.itemsFor(EditorToolbarSurface.NOTE), noteDecoded)
    }

    @Test
    fun aHiddenToolKeepsItsPlaceButLeavesTheBar() {
        val arrangement = EditorToolbarArrangement(
            EditorToolbarOrder.itemsFor(EditorToolbarSurface.MEMO),
        ).withHidden(EditorToolbarItem.HIGHLIGHT, true)
        val stored = EditorToolbarOrder.encode(arrangement)
        val decoded = EditorToolbarOrder.decodeArrangement(stored, EditorToolbarSurface.MEMO)

        assertEquals(arrangement.order, decoded.order)
        assertEquals(setOf(EditorToolbarItem.HIGHLIGHT), decoded.hidden)
        assertFalse(decoded.visible.contains(EditorToolbarItem.HIGHLIGHT))
        // The bar itself is the visible list, so the editor needs no new call.
        assertFalse(
            EditorToolbarOrder.decode(stored, EditorToolbarSurface.MEMO)
                .contains(EditorToolbarItem.HIGHLIGHT),
        )
        // Its place is remembered: shown again, it returns where it stood.
        val shownAgain = decoded.withHidden(EditorToolbarItem.HIGHLIGHT, false)
        assertEquals(
            EditorToolbarOrder.itemsFor(EditorToolbarSurface.MEMO),
            shownAgain.visible,
        )
    }

    @Test
    fun anArrangementWrittenBeforeHidingExistedHidesNothing() {
        val legacy = EditorToolbarOrder.encode(
            EditorToolbarOrder.itemsFor(EditorToolbarSurface.NOTE).reversed(),
        )
        val decoded = EditorToolbarOrder.decodeArrangement(legacy, EditorToolbarSurface.NOTE)

        assertEquals(emptySet<EditorToolbarItem>(), decoded.hidden)
        assertEquals(decoded.order, decoded.visible)
    }

    @Test
    fun aToolThisBuildAddedJoinsTheBarShown() {
        // An arrangement stored before 流れ方 existed, with one of its own units put away.
        val stored = "photo|-history|bold"
        val decoded = EditorToolbarOrder.decodeArrangement(stored, EditorToolbarSurface.MEMO)

        assertEquals(setOf(EditorToolbarItem.HISTORY), decoded.hidden)
        assertEquals(EditorToolbarItem.PHOTO, decoded.visible.first())
        assertEquals(EditorToolbarItem.FLOW_MODIFIERS, decoded.visible.last())
    }

    @Test
    fun theTwoRowsStartWhereTheBarHasAlwaysDrawnThem() {
        val memo = EditorToolbarOrder.decodeArrangement(null, EditorToolbarSurface.MEMO)
        assertEquals(
            listOf(
                EditorToolbarItem.TASK,
                EditorToolbarItem.OUTLINE_LABELS,
                EditorToolbarItem.INDENT,
            ),
            memo.lowerRow,
        )
        assertEquals(EditorToolbarItem.PHOTO, memo.upperRow.first())
        assertFalse(memo.upperRow.contains(EditorToolbarItem.TASK))

        val note = EditorToolbarOrder.decodeArrangement(null, EditorToolbarSurface.NOTE)
        assertEquals(listOf(EditorToolbarItem.PROSE_MARKS), note.lowerRow)
    }

    @Test
    fun crossingTheDividerLandsAtTheBoundaryAndRoundTrips() {
        val start = EditorToolbarOrder.decodeArrangement(null, EditorToolbarSurface.MEMO)
        // 流れ方 walks down out of the upper row: it becomes the head of the lower one.
        val moved = start.withRow(EditorToolbarItem.FLOW_MODIFIERS, toLower = true)
        assertEquals(EditorToolbarItem.FLOW_MODIFIERS, moved.lowerRow.first())
        assertFalse(moved.upperRow.contains(EditorToolbarItem.FLOW_MODIFIERS))
        // The single-row order reads as the two rows do, so one bar never contradicts the other.
        assertEquals(moved.upperRow + moved.lowerRow, moved.visible)

        val stored = EditorToolbarOrder.encode(moved)
        val decoded = EditorToolbarOrder.decodeArrangement(stored, EditorToolbarSurface.MEMO)
        assertEquals(moved.order, decoded.order)
        assertEquals(moved.lower, decoded.lower)

        // And back up: it returns as the last of the upper row.
        val returned = decoded.withRow(EditorToolbarItem.FLOW_MODIFIERS, toLower = false)
        assertEquals(EditorToolbarItem.FLOW_MODIFIERS, returned.upperRow.last())
        assertEquals(start.lowerRow, returned.lowerRow)
    }

    @Test
    fun aRowMayBeEmptiedAndTheChoiceSurvivesStorage() {
        val start = EditorToolbarOrder.decodeArrangement(null, EditorToolbarSurface.NOTE)
        val everythingUp = start.lowerRow.fold(start) { arrangement, item ->
            arrangement.withRow(item, toLower = false)
        }
        assertEquals(emptyList<EditorToolbarItem>(), everythingUp.lowerRow)

        // An emptied lower row marks no unit `_`; it must not read back as the old shelf.
        val decoded = EditorToolbarOrder.decodeArrangement(
            EditorToolbarOrder.encode(everythingUp),
            EditorToolbarSurface.NOTE,
        )
        assertEquals(emptyList<EditorToolbarItem>(), decoded.lowerRow)
    }

    @Test
    fun anArrangementWrittenBeforeTheRowsWereChosenKeepsTheOriginalShelf() {
        val legacy = "comment_link|task|-highlight"
        val decoded = EditorToolbarOrder.decodeArrangement(legacy, EditorToolbarSurface.MEMO)

        assertEquals(setOf(EditorToolbarItem.HIGHLIGHT), decoded.hidden)
        assertEquals(EditorToolbarOrder.defaultArrangement(EditorToolbarSurface.MEMO).lower, decoded.lower)
        assertEquals(EditorToolbarItem.TASK, decoded.lowerRow.first())
        assertEquals(EditorToolbarItem.COMMENT_LINK, decoded.upperRow.first())
    }

    @Test
    fun everyToolMayBePutAway() {
        val all = EditorToolbarOrder.itemsFor(EditorToolbarSurface.NOTE)
        val emptied = all.fold(EditorToolbarArrangement(all)) { arrangement, item ->
            arrangement.withHidden(item, true)
        }
        assertEquals(
            emptyList<EditorToolbarItem>(),
            EditorToolbarOrder.decode(
                EditorToolbarOrder.encode(emptied),
                EditorToolbarSurface.NOTE,
            ),
        )
    }
}

/** The outliner's bar: its own seven tools, arranged and hidden like the others'; the memo bar never shows its three. */
class OutlinerToolbarOrderTest {
    @org.junit.Test
    fun theOutlinerBarHasItsOwnToolsAndTheMemoBarNoneOfTheOutlinerOnes() {
        val outliner = EditorToolbarOrder.itemsFor(EditorToolbarSurface.OUTLINER)
        org.junit.Assert.assertEquals(
            listOf(
                EditorToolbarItem.INDENT, EditorToolbarItem.MOVE_LINES, EditorToolbarItem.FOLD, EditorToolbarItem.ZOOM,
                EditorToolbarItem.PHOTO, EditorToolbarItem.HISTORY, EditorToolbarItem.BOLD, EditorToolbarItem.HIGHLIGHT,
                EditorToolbarItem.TEMPLATE, EditorToolbarItem.LINK, EditorToolbarItem.COMMENT_LINK,
                EditorToolbarItem.TASK, EditorToolbarItem.OUTLINE_LABELS, EditorToolbarItem.FLOW_MODIFIERS,
            ),
            outliner,
        )
        org.junit.Assert.assertTrue(EditorToolbarOrder.itemsFor(EditorToolbarSurface.MEMO).none { it in EditorToolbarOrder.OutlinerOnly })
        org.junit.Assert.assertTrue(EditorToolbarOrder.itemsFor(EditorToolbarSurface.NOTE).none { it in EditorToolbarOrder.OutlinerOnly })
    }

    @org.junit.Test
    fun anOutlinerArrangementHidesAndReordersAndRoundTrips() {
        val arranged = EditorToolbarOrder.defaultArrangement(EditorToolbarSurface.OUTLINER)
            .withHidden(EditorToolbarItem.ZOOM, true)
            .let { it.copy(order = listOf(EditorToolbarItem.OUTLINE_LABELS) + it.order.filterNot { i -> i == EditorToolbarItem.OUTLINE_LABELS }) }
        val stored = EditorToolbarOrder.encode(arranged)
        val decoded = EditorToolbarOrder.decodeArrangement(stored, EditorToolbarSurface.OUTLINER)
        org.junit.Assert.assertEquals(arranged.order, decoded.order)
        org.junit.Assert.assertEquals(setOf(EditorToolbarItem.ZOOM), decoded.hidden)
        org.junit.Assert.assertEquals(EditorToolbarItem.OUTLINE_LABELS, EditorToolbarOrder.decode(stored, EditorToolbarSurface.OUTLINER).first())
        org.junit.Assert.assertFalse(EditorToolbarItem.ZOOM in EditorToolbarOrder.decode(stored, EditorToolbarSurface.OUTLINER))
    }
}

/** The outliner's two-row bar: the line tools above, what a line is and how it flies below. */
class OutlinerToolbarRowsTest {
    @org.junit.Test
    fun theOutlinerBarSplitsIntoLineToolsAboveAndWhatALineIsBelow() {
        val arranged = EditorToolbarOrder.defaultArrangement(EditorToolbarSurface.OUTLINER)
        org.junit.Assert.assertEquals(
            listOf(
                EditorToolbarItem.INDENT, EditorToolbarItem.MOVE_LINES, EditorToolbarItem.FOLD, EditorToolbarItem.ZOOM,
                EditorToolbarItem.PHOTO, EditorToolbarItem.HISTORY, EditorToolbarItem.BOLD, EditorToolbarItem.HIGHLIGHT,
                EditorToolbarItem.TEMPLATE, EditorToolbarItem.LINK, EditorToolbarItem.COMMENT_LINK,
            ),
            arranged.upperRow,
        )
        org.junit.Assert.assertEquals(
            listOf(EditorToolbarItem.TASK, EditorToolbarItem.OUTLINE_LABELS, EditorToolbarItem.FLOW_MODIFIERS),
            arranged.lowerRow,
        )
        // A stored order without row marks (written before the outliner bar had rows) splits the same way.
        val stored = EditorToolbarOrder.encode(arranged.order)
        org.junit.Assert.assertEquals(arranged.lowerRow, EditorToolbarOrder.decodeArrangement(stored, EditorToolbarSurface.OUTLINER).lowerRow)
        // The memo bar keeps its own split: 字下げ is structure there and stands below.
        org.junit.Assert.assertTrue(EditorToolbarOrder.defaultArrangement(EditorToolbarSurface.MEMO).isLower(EditorToolbarItem.INDENT))
    }
}

package io.github.cragcoffee.memoripple.ui.outline

import io.github.cragcoffee.memoripple.data.AttachmentLimits
import io.github.cragcoffee.memoripple.domain.outline.OutlineDocument
import io.github.cragcoffee.memoripple.domain.outline.OutlineEditing
import io.github.cragcoffee.memoripple.domain.outline.OutlinePhotos
import io.github.cragcoffee.memoripple.domain.outline.OutlineRows
import io.github.cragcoffee.memoripple.domain.outline.OutlineText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outline's photo cap counts what it shows, never what it only holds (docs/OUTLINE_PHOTO_ROWS.md
 * §7.3): a photo row taken out and kept for 元に戻す is not counted, so one taken out makes room for
 * one new; 元に戻す / やり直し never takes the outline above the cap and says so when it will not;
 * a picture taken out in this session is shown again when added again, one still shown is not.
 */
class OutlinerPhotoLimitTest {
    private val max = AttachmentLimits.MAX_PHOTOS_PER_RECORD

    /** A line, then [count] photo rows (photos 1..count), then a last line. */
    private fun outline(count: Int): OutlineDocument = OutlineRows.documentOf(
        listOf(OutlineRows.Row(1, "- 写真の記録")) +
            (1..count).map { OutlineRows.Row(100 + it, "", photo = it.toLong()) } +
            OutlineRows.Row(2, "- 終わり"),
    )
    private var written: OutlineDocument? = null
    private val write: (OutlineDocument) -> Unit = { written = it }
    private fun session(doc: OutlineDocument) = OutlinerSession().apply { acceptBody(OutlineText.serialize(doc), doc) }
    private fun OutlinerSession.active() = OutlinePhotos.shown(document).size

    @Test
    fun twentyShownLeaveNoRoomAndOneTakenOutLeavesNineteen() {
        val full = session(outline(max))
        assertEquals(20, full.active())
        assertEquals("20 shown: nothing more can be added", 0, AttachmentLimits.availablePhotoSlots(OutlinePhotos.activeCount(full.document, emptyList())))
        full.edit(write) { OutlineEditing.removePhoto(it, 101) }
        assertEquals("the photo taken out is only held, not shown", 19, full.active())
        assertEquals(1, AttachmentLimits.availablePhotoSlots(OutlinePhotos.activeCount(full.document, emptyList())))
        assertEquals("a photo about to be placed counts", 20, OutlinePhotos.activeCount(full.document, listOf(99L)))
    }

    @Test
    fun aNewPhotoAfterOneTakenOutMakesTwentyAndUndoTakesBackTheNewOneFirst() {
        val s = session(outline(max))
        s.edit(write) { OutlineEditing.removePhoto(it, 101) }
        s.edit(write) { OutlineEditing.insertPhotos(it, 2, listOf(99L), caret = null).document }
        assertEquals(20, s.active())
        // 元に戻す goes back one step at a time: the new photo goes first — never 21 shown.
        assertEquals(HistoryStep.APPLIED, s.undo(write))
        assertEquals(19, s.active())
        assertFalse(99L in OutlinePhotos.shown(s.document))
        assertEquals(HistoryStep.APPLIED, s.undo(write))
        assertEquals("the photo taken out comes back into the room the new one left", 20, s.active())
        assertTrue(1L in OutlinePhotos.shown(s.document))
        assertTrue(s.active() <= max)
    }

    @Test
    fun undoThatWouldGoAboveTheCapIsRefusedVisiblyAndChangesNothing() {
        // 21 shown: a photo back from a process death on top of 20.
        val s = session(outline(max + 1))
        s.edit(write) { OutlineEditing.removePhoto(it, 101) }
        assertEquals(20, s.active())
        val before = s.document
        written = null
        assertEquals("never silently", HistoryStep.PHOTO_LIMIT, s.undo(write))
        assertEquals("nothing changed", before, s.document)
        assertEquals("nothing written", null, written)
        assertTrue("the step is still there", s.canUndo)
        assertEquals("写真は最大20枚までです。追加した写真を減らしてから元に戻してください。", OUTLINE_HISTORY_PHOTO_LIMIT_MESSAGE)

        // Taking another photo out makes 元に戻す possible again: the newest step first.
        s.edit(write) { OutlineEditing.removePhoto(it, 102) }
        assertEquals(19, s.active())
        assertEquals(HistoryStep.APPLIED, s.undo(write))
        assertEquals(20, s.active())
        // The step that would make 21 is still refused, still visibly.
        assertEquals(HistoryStep.PHOTO_LIMIT, s.undo(write))
        assertEquals(20, s.active())
    }

    @Test
    fun redoIsHeldToTheSameRule() {
        val s = session(outline(max))
        // A state above the cap (not reachable by adding photos, which the import caps).
        s.edit(write) { OutlineEditing.insertPhotos(it, 2, listOf(99L), caret = null).document }
        assertEquals(21, s.active())
        assertEquals("going down is always allowed", HistoryStep.APPLIED, s.undo(write))
        assertEquals(20, s.active())
        val before = s.document
        assertEquals(HistoryStep.PHOTO_LIMIT, s.redo(write))
        assertEquals(before, s.document)
        assertTrue(s.canRedo)
        assertEquals(HistoryStep.NONE, OutlinerSession().redo(write))
    }

    @Test
    fun aPictureTakenOutIsShownAgainAndOneStillShownIsNot() {
        val doc = outline(3)
        val takenOut = OutlineEditing.removePhoto(doc, 102)
        // The import found both pictures already there: photo 2 (held) and photo 3 (shown).
        assertEquals(listOf(50L, 2L), OutlinePhotos.toPlace(listOf(50L), listOf(2L, 3L), takenOut, emptyList()))
        assertEquals("one already about to be placed is not placed twice", listOf(50L), OutlinePhotos.toPlace(listOf(50L), listOf(2L), takenOut, listOf(2L)))
        assertEquals(emptyList<Long>(), OutlinePhotos.toPlace(emptyList(), listOf(3L, 3L), takenOut, emptyList()))
    }

    @Test
    fun aPhotoRowsMenuFollowsTheLineRules() {
        val doc = OutlineRows.documentOf(
            listOf(OutlineRows.Row(1, "", photo = 1L), OutlineRows.Row(2, "- 一"), OutlineRows.Row(3, "  ", photo = 2L)),
        )
        // The first row: nothing above to go under or swap with; at the margin already.
        assertFalse(OutlineEditing.canMoveUp(doc, 1))
        assertFalse(OutlineEditing.canIndent(doc, 1))
        assertFalse(OutlineEditing.canOutdent(doc, 1))
        assertTrue(OutlineEditing.canMoveDown(doc, 1))
        // Under 一: can come out, cannot go deeper (no sibling above it), no sibling below.
        assertTrue(OutlineEditing.canOutdent(doc, 3))
        assertFalse(OutlineEditing.canIndent(doc, 3))
        assertFalse(OutlineEditing.canMoveDown(doc, 3))
    }
}

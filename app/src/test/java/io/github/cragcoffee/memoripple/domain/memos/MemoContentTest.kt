package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.domain.memos.MemoBlock.Photo
import io.github.cragcoffee.memoripple.domain.memos.MemoBlock.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** docs/MEMO_CONTENT_BLOCKS.md — the pure rules a memo's blocks follow. */
class MemoContentTest {
    private fun kinds(blocks: List<MemoBlock>) = blocks.joinToString(" ") {
        when (it) {
            is Text -> "T(${it.text})"
            is Photo -> "P${it.attachmentId}"
        }
    }

    @Test
    fun theProjectionIsTheSayingTextsJoinedByOneBreak() {
        val blocks = listOf(Text(1, "今日は公園へ行った"), Photo(2, 10), Text(3, "桜がきれいだった"), Photo(4, 11), Text(5, ""))
        assertEquals("今日は公園へ行った\n桜がきれいだった", MemoContent.projection(blocks))
        assertEquals("", MemoContent.projection(listOf(Text(1, ""))))
    }

    @Test
    fun aLegacyMemoKeepsItsPhotosAboveAndItsBodyUnchanged() {
        val blocks = MemoContent.legacy("ABC", listOf(7, 8))
        assertEquals("P7 P8 T(ABC)", kinds(blocks))
        assertEquals("ABC", MemoContent.projection(blocks))
        assertEquals("T(ABC)", kinds(MemoContent.legacy("ABC", emptyList())))
    }

    @Test
    fun readingToleratesWhatStorageHolds() {
        assertEquals("no blocks → legacy", "P7 T(本文)", kinds(MemoContent.resolve(emptyList(), "本文", listOf(7))))
        val stored = listOf(Text(1, "a"), Photo(2, 7), Text(3, "b"))
        assertEquals("a photo no block names joins the leading photos", "P8 T(a) P7 T(b)", kinds(MemoContent.resolve(stored, "a\nb", listOf(7, 8))))
        assertEquals("a block whose photo is gone is dropped", "T(a) T(b)", kinds(MemoContent.resolve(stored, "a\nb", emptyList())))
        assertEquals("a photo last gets a place to write", "T(a) P7 T()", kinds(MemoContent.resolve(listOf(Text(1, "a"), Photo(2, 7)), "a", listOf(7))))
    }

    @Test
    fun aMemoWithoutPhotosIsOneTextAndTheLastBlockIsText() {
        assertEquals("T(a\nb)", kinds(MemoContent.normalize(listOf(Text(1, "a"), Text(2, ""), Text(3, "b")))))
        assertEquals(1L, MemoContent.normalize(listOf(Text(1, "a"), Text(3, "b"))).single().id)
        assertEquals("T(a) P7 T()", kinds(MemoContent.normalize(listOf(Text(1, "a"), Photo(2, 7)))))
    }

    @Test
    fun aPhotoGoesRightAfterTheTextBeingWrittenAndWritingContinuesBelowIt() {
        val start = listOf(Text(1, "今日は公園へ行った"))
        val first = MemoContent.insertPhotos(start, activeTextId = 1, attachmentIds = listOf(10))
        assertEquals("T(今日は公園へ行った) P10 T()", kinds(first.blocks))
        assertEquals(2, first.writingIndex)
        // The writer goes on below the photo, then adds another.
        val written = first.blocks.toMutableList().also { it[2] = Text(3, "桜がきれいだった") }
        val second = MemoContent.insertPhotos(written, activeTextId = 3, attachmentIds = listOf(11, 12))
        assertEquals("T(今日は公園へ行った) P10 T(桜がきれいだった) P11 P12 T()", kinds(second.blocks))
        assertEquals(5, second.writingIndex)
    }

    @Test
    fun anEmptyTextStaysTheWritingPlaceAndPhotosGoInFrontOfIt() {
        val blocks = listOf(Text(1, "a"), Photo(2, 10), Text(3, ""))
        val result = MemoContent.insertPhotos(blocks, activeTextId = 3, attachmentIds = listOf(11))
        assertEquals("T(a) P10 P11 T()", kinds(result.blocks))
        assertEquals(3, result.writingIndex)
        assertEquals(3L, result.blocks[result.writingIndex].id)
    }

    @Test
    fun anExistingEmptyTextAfterThePhotoIsReusedNotDoubled() {
        val blocks = listOf(Text(1, "a"), Text(2, ""), Photo(3, 10), Text(4, "b"))
        val result = MemoContent.insertPhotos(blocks, activeTextId = 1, attachmentIds = listOf(11))
        assertEquals("T(a) P11 T() P10 T(b)", kinds(result.blocks))
        assertEquals(2L, result.blocks[result.writingIndex].id)
    }

    @Test
    fun aPhotoGoesWhereTheCaretIsAndCutsTheTextThere() {
        // S26, 2026-09-24: a caret between the words put the photo after all of them.
        val blocks = listOf(Text(1, "abcdef"), Photo(2, 10), Text(3, "g"))
        val middle = MemoContent.insertPhotos(blocks, activeTextId = 1, attachmentIds = listOf(11), caret = 3)
        assertEquals("T(abc) P11 T(def) P10 T(g)", kinds(middle.blocks))
        assertEquals("the text keeps its id above the photo", 1L, middle.blocks[0].id)
        assertEquals("writing goes on at the start of the words after the caret", 2, middle.writingIndex)
        assertEquals(MemoContent.NEW, middle.blocks[2].id)
        // Then the next photo of the same pick goes in front of that text: after this one.
        val second = MemoContent.insertPhotos(middle.blocks.toMutableList().also { it[2] = Text(5, "def") }, 5, listOf(12), caret = 0)
        assertEquals("T(abc) P11 P12 T(def) P10 T(g)", kinds(second.blocks))
        assertEquals(5L, second.blocks[second.writingIndex].id)
    }

    @Test
    fun aCutAtALineEdgeKeepsTheProjectionAndTheEndsBehaveAsBefore() {
        val text = listOf(Text(1, "一行目\n二行目"))
        val atLineStart = MemoContent.insertPhotos(text, 1, listOf(10), caret = 4)
        assertEquals("T(一行目) P10 T(二行目)", kinds(atLineStart.blocks))
        assertEquals("一行目\n二行目", MemoContent.projection(atLineStart.blocks))
        val atLineEnd = MemoContent.insertPhotos(text, 1, listOf(10), caret = 3)
        assertEquals("T(一行目) P10 T(二行目)", kinds(atLineEnd.blocks))
        assertEquals("一行目\n二行目", MemoContent.projection(atLineEnd.blocks))
        assertEquals("inside a line the line is cut", "abc\ndef", MemoContent.projection(MemoContent.insertPhotos(listOf(Text(1, "abcdef")), 1, listOf(10), caret = 3).blocks))
        assertEquals("at the end: after it, a place to write below", "T(一行目\n二行目) P10 T()", kinds(MemoContent.insertPhotos(text, 1, listOf(10), caret = 7).blocks))
        assertEquals("no caret is the end", "T(一行目\n二行目) P10 T()", kinds(MemoContent.insertPhotos(text, 1, listOf(10)).blocks))
        val atStart = MemoContent.insertPhotos(text, 1, listOf(10), caret = 0)
        assertEquals("at the start: in front of it, and it stays the writing place", "P10 T(一行目\n二行目)", kinds(atStart.blocks))
        assertEquals(1L, atStart.blocks[atStart.writingIndex].id)
    }

    @Test
    fun aJournalWrittenBeforeBlocksKeepsItsWordsAboveItsPhotos() {
        // Room 27 / Backup 21 (2026-09-25): a journal's photos sat under its words.
        val blocks = MemoContent.legacy("今日のこと", listOf(7, 8), photosFirst = false)
        assertEquals("T(今日のこと) P7 P8 T()", kinds(blocks))
        assertEquals("今日のこと", MemoContent.projection(blocks))
        assertEquals("no photo, one text", "T(本文)", kinds(MemoContent.legacy("本文", emptyList(), photosFirst = false)))
        assertEquals("no blocks → the words, then the photos", "T(本文) P7 T()", kinds(MemoContent.resolve(emptyList(), "本文", listOf(7), photosFirst = false)))
        val stored = listOf(Text(1, "a"), Photo(2, 7), Text(3, ""))
        assertEquals("a photo no block names joins the closing photos", "T(a) P7 P8 T()", kinds(MemoContent.resolve(stored, "a", listOf(7, 8), photosFirst = false)))
    }

    @Test
    fun backspaceAtTheStartOfATextJoinsItToTheTextBeforeButNeverEatsAPhoto() {
        val blocks = listOf(Text(1, "abc"), Text(2, "def"), Photo(3, 10), Text(4, "ghi"))
        val merge = MemoContent.mergeWithPrevious(blocks, 2)!!
        assertEquals("T(abcdef) P10 T(ghi)", kinds(merge.blocks))
        assertEquals(1L, merge.textBlockId)
        assertEquals(3, merge.caret)
        assertNull("the block before is a photo", MemoContent.mergeWithPrevious(blocks, 4))
        assertNull("nothing before the first", MemoContent.mergeWithPrevious(blocks, 1))
    }

    @Test
    fun aReorderKeepsTheSlotsAndChangesThePictures() {
        val blocks = listOf(Photo(1, 10), Text(2, "a"), Photo(3, 11), Text(4, ""))
        assertEquals("P11 T(a) P10 T()", kinds(MemoContent.reassignPhotos(blocks, listOf(11, 10))))
    }

    @Test
    fun theReadingViewPlacesPhotosAtProjectionLineBreaks() {
        val blocks = listOf(Photo(1, 9), Text(2, "一\n二"), Photo(3, 10), Text(4, ""), Photo(5, 11), Text(6, "三"), Photo(7, 12), Text(8, ""))
        assertEquals(mapOf(0 to listOf(9L), 2 to listOf(10L, 11L), 3 to listOf(12L)), MemoContent.photoBreaks(blocks))
        assertEquals(2L to 1, MemoContent.lineToBlock(blocks, 1))
        assertEquals(6L to 0, MemoContent.lineToBlock(blocks, 2))
        assertEquals("past the end → the last line", 6L to 0, MemoContent.lineToBlock(blocks, 9))
    }

    @Test
    fun anAppendFromAiGoesToTheEndOfTheNote() {
        val closing = listOf(Text(1, "abc"), Photo(2, 10), Text(3, ""))
        assertEquals("T(abc) P10 T(追記)", kinds(MemoContent.reconcile(closing, "abc\n追記")))
        val open = listOf(Photo(1, 10), Text(2, "abc"))
        assertEquals("P10 T(abc\n追記)", kinds(MemoContent.reconcile(open, "abc\n追記")))
        val written = listOf(Text(1, "abc"), Photo(2, 10), Text(3, "def"))
        assertEquals("T(abc) P10 T(def\n追記)", kinds(MemoContent.reconcile(written, "abc\ndef\n追記")))
    }

    @Test
    fun anEditInsideOneTextChangesOnlyThatText() {
        val blocks = listOf(Text(1, "今日は公園"), Photo(2, 10), Text(3, "桜"))
        val result = MemoContent.reconcile(blocks, "今日は大きな公園\n桜")
        assertEquals("T(今日は大きな公園) P10 T(桜)", kinds(result))
        assertEquals(listOf(1L, 2L, 3L), result.map { it.id })
        assertEquals("T(今日は公園) P10 T(満開の桜)", kinds(MemoContent.reconcile(blocks, "今日は公園\n満開の桜")))
    }

    @Test
    fun anEditAcrossTextsJoinsThemAndKeepsEveryPhoto() {
        val blocks = listOf(Text(1, "abc"), Photo(2, 10), Text(3, "def"), Photo(4, 11), Text(5, "ghi"))
        // The line break between abc and def deleted in a plain-text editor.
        val joined = MemoContent.reconcile(blocks, "abcdef\nghi")
        assertEquals("T(abcdef) P10 P11 T(ghi)", kinds(joined))
        assertEquals("abcdef\nghi", MemoContent.projection(joined))
        val emptied = MemoContent.reconcile(blocks, "")
        assertEquals("T() P10 P11 T()", kinds(emptied))
    }

    @Test
    fun aNoteWithNothingWrittenTakesTheNewTextInItsLastText() {
        assertEquals("P10 T(new)", kinds(MemoContent.reconcile(listOf(Photo(1, 10), Text(2, "")), "new")))
        assertEquals("T(same)", kinds(MemoContent.reconcile(listOf(Text(1, "same")), "same")))
    }
}

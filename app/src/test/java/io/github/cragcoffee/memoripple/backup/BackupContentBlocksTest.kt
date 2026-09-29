package io.github.cragcoffee.memoripple.backup

import io.github.cragcoffee.memoripple.data.AttachmentBlobEntity
import io.github.cragcoffee.memoripple.data.MemoContentBlockEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoPhotoAttachmentEntity
import io.github.cragcoffee.memoripple.data.RoomBackupSnapshot
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backup 20 (docs/MEMO_CONTENT_BLOCKS.md, human decision 2026-09-24): a memo's text and photos in
 * the order the writer put them travel whole; 1–19 files restore with the photos above the text
 * (as they looked); a 20 file whose blocks do not describe its memos is refused.
 */
class BackupContentBlocksTest {
    private val sha1 = "a".repeat(64)
    private val sha2 = "b".repeat(64)
    private val blobs = listOf(
        AttachmentBlobEntity(sha1, "image", "image/png", 10, 8, 8, 1),
        AttachmentBlobEntity(sha2, "image", "image/png", 10, 8, 8, 1),
    )
    private val memo = MemoEntity(id = 1, title = "公園", body = "今日は公園へ行った\n桜がきれいだった", createdAt = 1, updatedAt = 2)
    private val outline = MemoEntity(id = 2, title = "週次", body = "- a", createdAt = 1, updatedAt = 2, kind = "outline")
    private val photos = listOf(
        MemoPhotoAttachmentEntity(id = 11, memoId = 1, blobSha256 = sha1, sortOrder = 0, createdAt = 1),
        MemoPhotoAttachmentEntity(id = 12, memoId = 1, blobSha256 = sha2, sortOrder = 1, createdAt = 1),
    )
    private val blocks = listOf(
        MemoContentBlockEntity(101, 1, 0, "text", "今日は公園へ行った", null),
        MemoContentBlockEntity(102, 1, 1, "photo", null, 11),
        MemoContentBlockEntity(103, 1, 2, "text", "桜がきれいだった", null),
        MemoContentBlockEntity(104, 1, 3, "photo", null, 12),
        MemoContentBlockEntity(105, 1, 4, "text", "", null),
    )
    private fun snapshot(contentBlocks: List<MemoContentBlockEntity> = blocks) = RoomBackupSnapshot(
        memos = listOf(memo, outline),
        memoComments = emptyList(),
        diaryEntries = emptyList(),
        futureDiaryComments = emptyList(),
        attachmentBlobs = blobs,
        memoPhotoAttachments = photos,
        memoContentBlocks = contentBlocks,
    )
    private fun document(contentBlocks: List<MemoContentBlockEntity> = blocks) =
        BackupMapper.toDocument(snapshot(contentBlocks), AppSettings.Default, 1, "test", 1)

    @Test
    fun textAndPhotosInTheirOrderRoundTripWhole() {
        val doc = document()
        assertEquals(23, doc.formatVersion)
        assertEquals(BackupValidationResult.Valid, BackupValidator().validate(doc))
        val restored = BackupMapper.toRoomSnapshot(doc)
        assertEquals(blocks, restored.memoContentBlocks)
        assertEquals("the body is the blocks' projection", memo.body, restored.memos.first { it.id == 1L }.body)
        assertTrue("an outline has no blocks", restored.memoContentBlocks.none { it.memoId == 2L })
    }

    @Test
    fun aMemoWhoseBlocksWereNeverStoredIsWrittenWithItsPhotosAbove() {
        val doc = document(contentBlocks = emptyList())
        assertEquals(BackupValidationResult.Valid, BackupValidator().validate(doc))
        val written = doc.payload.memoContentBlocks
        assertEquals(listOf("photo", "photo", "text"), written.map { it.type })
        assertEquals(listOf(11L, 12L, null), written.map { it.photoAttachmentId })
        assertEquals(memo.body, written.last().text)
    }

    @Test
    fun anOlderFileRestoresWithThePhotosAboveTheText() {
        val nineteen = document().let { it.copy(formatVersion = 19, payload = it.payload.copy(memoContentBlocks = emptyList())) }
        assertEquals(BackupValidationResult.Valid, BackupValidator().validate(nineteen))
        val restored = BackupMapper.toRoomSnapshot(nineteen)
        val own = restored.memoContentBlocks.filter { it.memoId == 1L }
        assertEquals(listOf("photo", "photo", "text"), own.map { it.type })
        assertEquals(listOf(11L, 12L, null), own.map { it.photoAttachmentId })
        assertEquals(memo.body, own.last().text)
        assertEquals("the body is not touched", memo.body, restored.memos.first { it.id == 1L }.body)
        // Even a format-20 payload is ignored in an older file: the version decides.
        val stray = document().copy(formatVersion = 19)
        assertEquals(listOf("photo", "photo", "text"), BackupMapper.toRoomSnapshot(stray).memoContentBlocks.filter { it.memoId == 1L }.map { it.type })
    }

    @Test
    fun blocksThatDoNotDescribeTheirMemoAreRefused() {
        fun refused(changed: List<MemoContentBlockEntity>) {
            val doc = document().let { it.copy(payload = it.payload.copy(memoContentBlocks = changed.map { b ->
                MemoContentBlockBackupDto(b.id, b.memoId, b.position, b.type, b.text, b.photoAttachmentId)
            })) }
            val result = BackupValidator().validate(doc)
            assertTrue("$changed", result is BackupValidationResult.Invalid &&
                BackupValidationIssue.INVALID_CONTENT_BLOCKS in result.issues)
        }
        refused(blocks + MemoContentBlockEntity(106, 2, 0, "text", "x", null))               // on an outline
        refused(blocks.filterNot { it.id == 104L }.map { if (it.id == 105L) it.copy(position = 3) else it }) // a photo left out
        refused(blocks.map { if (it.id == 103L) it.copy(position = 7) else it })              // a gap in the order
        refused(blocks.map { if (it.id == 102L) it.copy(photoAttachmentId = 12) else it })    // a photo named twice
        refused(blocks.map { if (it.id == 101L) it.copy(photoAttachmentId = 11) else it })    // a text with a photo
        refused(listOf(blocks[1].copy(position = 0), blocks[3].copy(position = 1)))            // no text at all
    }
}

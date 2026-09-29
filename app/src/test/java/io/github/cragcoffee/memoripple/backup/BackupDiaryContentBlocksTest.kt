package io.github.cragcoffee.memoripple.backup

import io.github.cragcoffee.memoripple.data.AttachmentBlobEntity
import io.github.cragcoffee.memoripple.data.DiaryContentBlockEntity
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.DiaryPhotoAttachmentEntity
import io.github.cragcoffee.memoripple.data.RoomBackupSnapshot
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backup 21 (docs/MEMO_CONTENT_BLOCKS.md §11, human decision 2026-09-25): a journal entry's text
 * and photos in the order the writer put them travel whole; 1–20 files restore with the words
 * above the photos (as they looked); a 21 file whose diary blocks do not describe its entries is
 * refused. The lifecycle state and the timestamps travel untouched.
 */
class BackupDiaryContentBlocksTest {
    private val sha1 = "a".repeat(64)
    private val sha2 = "b".repeat(64)
    private val blobs = listOf(
        AttachmentBlobEntity(sha1, "image", "image/png", 10, 8, 8, 1),
        AttachmentBlobEntity(sha2, "image", "image/png", 10, 8, 8, 1),
    )
    private val entry = DiaryEntryEntity(id = 1, diaryDateEpochDay = 20_000, body = "朝は晴れ\n夜は雨", state = DiaryState.DRAFT, createdAt = 1, updatedAt = 2)
    private val locked = DiaryEntryEntity(id = 2, diaryDateEpochDay = 19_000, body = "昔の日", state = DiaryState.LOCKED, createdAt = 1, updatedAt = 2, lockedAt = 3)
    private val photos = listOf(
        DiaryPhotoAttachmentEntity(id = 11, diaryEntryId = 1, blobSha256 = sha1, sortOrder = 0, createdAt = 1),
        DiaryPhotoAttachmentEntity(id = 12, diaryEntryId = 1, blobSha256 = sha2, sortOrder = 1, createdAt = 1),
    )
    private val blocks = listOf(
        DiaryContentBlockEntity(101, 1, 0, "text", "朝は晴れ", null),
        DiaryContentBlockEntity(102, 1, 1, "photo", null, 11),
        DiaryContentBlockEntity(103, 1, 2, "text", "夜は雨", null),
        DiaryContentBlockEntity(104, 1, 3, "photo", null, 12),
        DiaryContentBlockEntity(105, 1, 4, "text", "", null),
        DiaryContentBlockEntity(106, 2, 0, "text", "昔の日", null),
    )
    private fun snapshot(diaryBlocks: List<DiaryContentBlockEntity> = blocks) = RoomBackupSnapshot(
        memos = emptyList(),
        memoComments = emptyList(),
        diaryEntries = listOf(entry, locked),
        futureDiaryComments = emptyList(),
        attachmentBlobs = blobs,
        diaryPhotoAttachments = photos,
        diaryContentBlocks = diaryBlocks,
    )
    private fun document(diaryBlocks: List<DiaryContentBlockEntity> = blocks) =
        BackupMapper.toDocument(snapshot(diaryBlocks), AppSettings.Default, 1, "test", 1)

    @Test
    fun wordsAndPhotosInTheirOrderRoundTripWhole() {
        val doc = document()
        assertEquals(23, doc.formatVersion)
        assertEquals(BackupValidationResult.Valid, BackupValidator().validate(doc))
        val restored = BackupMapper.toRoomSnapshot(doc)
        assertEquals(blocks, restored.diaryContentBlocks)
        assertEquals("the body is the blocks' projection", entry.body, restored.diaryEntries.first { it.id == 1L }.body)
        val lockedBack = restored.diaryEntries.first { it.id == 2L }
        assertEquals("the lifecycle travels untouched", DiaryState.LOCKED, lockedBack.state)
        assertEquals(3L, lockedBack.lockedAt)
    }

    @Test
    fun anEntryWhoseBlocksWereNeverStoredIsWrittenWithItsWordsAbove() {
        val doc = document(diaryBlocks = emptyList())
        assertEquals(BackupValidationResult.Valid, BackupValidator().validate(doc))
        val own = doc.payload.diaryContentBlocks.filter { it.diaryEntryId == 1L }
        assertEquals(listOf("text", "photo", "photo", "text"), own.map { it.type })
        assertEquals(listOf(null, 11L, 12L, null), own.map { it.photoAttachmentId })
        assertEquals(entry.body, own.first().text)
    }

    @Test
    fun anOlderFileRestoresWithTheWordsAboveThePhotos() {
        val twenty = document().let { it.copy(formatVersion = 20, payload = it.payload.copy(diaryContentBlocks = emptyList())) }
        assertEquals(BackupValidationResult.Valid, BackupValidator().validate(twenty))
        val restored = BackupMapper.toRoomSnapshot(twenty)
        val own = restored.diaryContentBlocks.filter { it.diaryEntryId == 1L }
        assertEquals(listOf("text", "photo", "photo", "text"), own.map { it.type })
        assertEquals(listOf(null, 11L, 12L, null), own.map { it.photoAttachmentId })
        assertEquals(entry.body, own.first().text)
        assertEquals("the body is not touched", entry.body, restored.diaryEntries.first { it.id == 1L }.body)
        assertTrue("every block has its own id", restored.diaryContentBlocks.map { it.id }.toSet().size == restored.diaryContentBlocks.size)
        // Even a format-21 payload is ignored in an older file: the version decides.
        val stray = document().copy(formatVersion = 20)
        assertEquals(listOf("text", "photo", "photo", "text"), BackupMapper.toRoomSnapshot(stray).diaryContentBlocks.filter { it.diaryEntryId == 1L }.map { it.type })
    }

    @Test
    fun diaryBlocksThatDoNotDescribeTheirEntryAreRefused() {
        fun refused(changed: List<DiaryContentBlockEntity>) {
            val doc = document().let { it.copy(payload = it.payload.copy(diaryContentBlocks = changed.map { b ->
                DiaryContentBlockBackupDto(b.id, b.diaryEntryId, b.position, b.type, b.text, b.photoAttachmentId)
            })) }
            val result = BackupValidator().validate(doc)
            assertTrue("$changed", result is BackupValidationResult.Invalid &&
                BackupValidationIssue.INVALID_CONTENT_BLOCKS in result.issues)
        }
        refused(blocks + DiaryContentBlockEntity(107, 99, 0, "text", "x", null))                  // no such entry
        refused(blocks.filterNot { it.id == 104L }.map { if (it.id == 105L) it.copy(position = 3) else it }) // a photo left out
        refused(blocks.map { if (it.id == 103L) it.copy(position = 7) else it })                    // a gap in the order
        refused(blocks.map { if (it.id == 102L) it.copy(photoAttachmentId = 12) else it })          // a photo named twice
        refused(blocks.map { if (it.id == 101L) it.copy(photoAttachmentId = 11) else it })          // a text with a photo
    }
}

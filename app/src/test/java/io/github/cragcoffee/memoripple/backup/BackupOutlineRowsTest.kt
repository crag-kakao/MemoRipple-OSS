package io.github.cragcoffee.memoripple.backup

import io.github.cragcoffee.memoripple.data.AttachmentBlobEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoPhotoAttachmentEntity
import io.github.cragcoffee.memoripple.data.OutlineRowEntity
import io.github.cragcoffee.memoripple.data.RoomBackupSnapshot
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backup 22 (docs/OUTLINE_STABLE_ROWS.md, human decision 2026-09-25): an outline's lines travel with
 * their lasting ids and come back with the same ids; 1–21 files restore their outlines line by line
 * (ids 1..n, the body unchanged); a file whose rows do not describe an outline is refused.
 *
 * Backup 23 (docs/OUTLINE_PHOTO_ROWS.md, human decision 2026-09-25): photo rows travel as rows —
 * their id, place, depth and photo — and come back whole; 1–22 files restore the outline's photos
 * as photo rows at the top, the lines and their ids unchanged.
 */
class BackupOutlineRowsTest {
    private val outline = MemoEntity(id = 5, title = "旅行", body = "- 旅行計画\n  - 京都へ行く\n\n- 帰る", createdAt = 1, updatedAt = 2, kind = "outline")
    private val memo = MemoEntity(id = 6, title = "メモ", body = "本文", createdAt = 1, updatedAt = 2)
    private val rows = listOf(
        OutlineRowEntity(5, 21, 0, text = "- 旅行計画"),
        OutlineRowEntity(5, 4, 1, text = "  - 京都へ行く"),
        OutlineRowEntity(5, 30, 2, text = ""),
        OutlineRowEntity(5, 9, 3, text = "- 帰る"),
    )
    private val sha1 = "a".repeat(64)
    private val sha2 = "b".repeat(64)
    private val blobs = listOf(
        AttachmentBlobEntity(sha1, "image", "image/png", 10, 8, 8, 1),
        AttachmentBlobEntity(sha2, "image", "image/png", 10, 8, 8, 1),
    )
    private val photos = listOf(
        MemoPhotoAttachmentEntity(id = 11, memoId = 5, blobSha256 = sha1, sortOrder = 0, createdAt = 1),
        MemoPhotoAttachmentEntity(id = 12, memoId = 5, blobSha256 = sha2, sortOrder = 1, createdAt = 1),
    )
    /** Text → photo → text → photo (nested) → text, with ids that are not 1..n. */
    private val photoRows = listOf(
        OutlineRowEntity(5, 21, 0, text = "- 旅行計画"),
        OutlineRowEntity(5, 44, 1, OutlineRowEntity.KIND_PHOTO, "", 12),
        OutlineRowEntity(5, 4, 2, text = "  - 京都へ行く"),
        OutlineRowEntity(5, 45, 3, OutlineRowEntity.KIND_PHOTO, "    ", 11),
        OutlineRowEntity(5, 30, 4, text = ""),
        OutlineRowEntity(5, 9, 5, text = "- 帰る"),
    )
    private fun document(outlineRows: List<OutlineRowEntity> = rows, withPhotos: Boolean = false) = BackupMapper.toDocument(
        RoomBackupSnapshot(
            memos = listOf(outline, memo),
            memoComments = emptyList(),
            diaryEntries = emptyList(),
            futureDiaryComments = emptyList(),
            attachmentBlobs = if (withPhotos) blobs else emptyList(),
            memoPhotoAttachments = if (withPhotos) photos else emptyList(),
            outlineRows = outlineRows,
        ),
        AppSettings.Default, 1, "test", 1,
    )

    @Test
    fun theLinesAndTheirIdsRoundTripWhole() {
        val doc = document()
        assertEquals(23, doc.formatVersion)
        assertEquals(BackupValidationResult.Valid, BackupValidator().validate(doc))
        val restored = BackupMapper.toRoomSnapshot(doc)
        assertEquals(rows, restored.outlineRows)
        assertEquals("the body is the rows' projection", outline.body, restored.memos.first { it.id == 5L }.body)
        assertTrue("a memo has no rows", restored.outlineRows.none { it.memoId == 6L })
    }

    @Test
    fun anOutlineWithoutRowsIsWrittenLineByLine() {
        val doc = document(outlineRows = emptyList())
        assertEquals(BackupValidationResult.Valid, BackupValidator().validate(doc))
        assertEquals(listOf(1, 2, 3, 4), doc.payload.outlineRows.map { it.rowId })
        assertEquals(outline.body, doc.payload.outlineRows.joinToString("\n") { it.text })
    }

    @Test
    fun anOlderFileRestoresEachOutlineLineByLineWithItsBodyUnchanged() {
        for (version in listOf(15, 20, 21)) {
            val older = document().let { it.copy(formatVersion = version, payload = it.payload.copy(outlineRows = emptyList())) }
            val restored = BackupMapper.toRoomSnapshot(older)
            assertEquals("$version", listOf(1, 2, 3, 4), restored.outlineRows.map { it.rowId })
            assertEquals(outline.body, restored.outlineRows.joinToString("\n") { it.text })
            assertEquals(outline.body, restored.memos.first { it.id == 5L }.body)
        }
        // A format-22 payload inside an older file is ignored: the version decides.
        val stray = document().copy(formatVersion = 21)
        assertEquals(listOf(1, 2, 3, 4), BackupMapper.toRoomSnapshot(stray).outlineRows.map { it.rowId })
    }

    @Test
    fun rowsThatDoNotDescribeAnOutlineAreRefused() {
        fun refused(changed: List<OutlineRowEntity>) {
            val doc = document().let { it.copy(payload = it.payload.copy(outlineRows = changed.map { r -> OutlineRowBackupDto(r.memoId, r.rowId, r.position, r.text) })) }
            val result = BackupValidator().validate(doc)
            assertTrue("$changed", result is BackupValidationResult.Invalid && BackupValidationIssue.INVALID_OUTLINE_ROWS in result.issues)
        }
        refused(rows + OutlineRowEntity(6, 1, 0, text = "本文"))                        // on a memo
        refused(rows.map { if (it.rowId == 4) it.copy(rowId = 21) else it })     // an id twice
        refused(rows.map { if (it.rowId == 30) it.copy(position = 9) else it }) // a gap
        refused(rows.map { if (it.rowId == 9) it.copy(text = "- 帰る\n- 次") else it }) // two lines in one
        refused(rows.map { if (it.rowId == 9) it.copy(rowId = 0) else it })     // not a lasting id
    }

    @Test
    fun photoRowsRoundTripWithTheirIdsPlacesDepthsAndPhotos() {
        val doc = document(photoRows, withPhotos = true)
        assertEquals(23, doc.formatVersion)
        assertEquals(BackupValidationResult.Valid, BackupValidator().validate(doc))
        assertEquals(listOf("text", "photo", "text", "photo", "text", "text"), doc.payload.outlineRows.map { it.kind })
        val restored = BackupMapper.toRoomSnapshot(doc)
        assertEquals(photoRows, restored.outlineRows)
        assertEquals("the photos never enter the body", outline.body, restored.memos.first { it.id == 5L }.body)
        assertEquals(photos, restored.memoPhotoAttachments)
    }

    @Test
    fun anOutlineWithPhotosNeverStoredAsRowsIsWrittenWithThePhotosOnTop() {
        val doc = document(outlineRows = emptyList(), withPhotos = true)
        assertEquals(BackupValidationResult.Valid, BackupValidator().validate(doc))
        val written = doc.payload.outlineRows
        assertEquals(listOf("photo", "photo", "text", "text", "text", "text"), written.map { it.kind })
        assertEquals(listOf(11L, 12L), written.take(2).map { it.photoAttachmentId })
        assertEquals(listOf(5, 6, 1, 2, 3, 4), written.map { it.rowId })
    }

    @Test
    fun aTwentyTwoFileRestoresItsLinesAndTheirIdsWithThePhotosOnTop() {
        val twentyTwo = document(withPhotos = true).let { doc ->
            doc.copy(
                formatVersion = 22,
                payload = doc.payload.copy(outlineRows = rows.map { OutlineRowBackupDto(it.memoId, it.rowId, it.position, it.text) }),
            )
        }
        val restored = BackupMapper.toRoomSnapshot(twentyTwo)
        val own = restored.outlineRows.filter { it.memoId == 5L }
        assertEquals(listOf(31, 32, 21, 4, 30, 9), own.map { it.rowId })
        assertEquals(listOf(11L, 12L, null, null, null, null), own.map { it.photoAttachmentId })
        assertEquals(listOf("photo", "photo", "text", "text", "text", "text"), own.map { it.kind })
        assertEquals(rows.map { it.text }, own.drop(2).map { it.text })
        assertEquals(outline.body, restored.memos.first { it.id == 5L }.body)
        // 1–21: the lines read from the body, the photos on top.
        for (version in listOf(15, 21)) {
            val older = document(withPhotos = true).let { it.copy(formatVersion = version, payload = it.payload.copy(outlineRows = emptyList())) }
            val back = BackupMapper.toRoomSnapshot(older).outlineRows
            assertEquals("$version", listOf(11L, 12L, null, null, null, null), back.map { it.photoAttachmentId })
            assertEquals(outline.body, back.filter { it.kind == "text" }.joinToString("\n") { it.text })
        }
    }

    @Test
    fun photoRowsThatDoNotDescribeTheOutlinesPhotosAreRefused() {
        fun refused(changed: List<OutlineRowEntity>) {
            val doc = document(photoRows, withPhotos = true).let {
                it.copy(payload = it.payload.copy(outlineRows = changed.map { r -> OutlineRowBackupDto(r.memoId, r.rowId, r.position, r.text, r.kind, r.photoAttachmentId) }))
            }
            val result = BackupValidator().validate(doc)
            assertTrue("$changed", result is BackupValidationResult.Invalid && BackupValidationIssue.INVALID_OUTLINE_ROWS in result.issues)
        }
        refused(photoRows.map { if (it.rowId == 45) it.copy(photoAttachmentId = 12) else it })   // one photo, two rows
        refused(photoRows.map { if (it.rowId == 45) it.copy(photoAttachmentId = 99) else it })   // not this outline's photo
        refused(photoRows.map { if (it.rowId == 45) it.copy(photoAttachmentId = null) else it }) // a photo row with no photo
        refused(photoRows.map { if (it.rowId == 45) it.copy(text = "    写真") else it })        // a photo row with words
        refused(photoRows.map { if (it.rowId == 21) it.copy(photoAttachmentId = 11) else it })   // a line of words with a photo
        refused(photoRows.map { if (it.rowId == 45) it.copy(kind = "video") else it })          // an unknown kind
    }

    /** [outlinePhotos] photo records on the outline, the first [shown] of them shown by photo rows. */
    private fun capped(outlinePhotos: Int, shown: Int, memoPhotos: Int = 0, version: Int = 23): BackupValidationResult {
        val shas = (1..outlinePhotos + memoPhotos).map { "%064x".format(it) }
        val relations = (1..outlinePhotos).map { MemoPhotoAttachmentEntity(id = it.toLong(), memoId = 5, blobSha256 = shas[it - 1], sortOrder = it - 1, createdAt = 1) } +
            (1..memoPhotos).map { MemoPhotoAttachmentEntity(id = 100L + it, memoId = 6, blobSha256 = shas[outlinePhotos + it - 1], sortOrder = it - 1, createdAt = 1) }
        val lines = rows.map { it.copy(position = it.position + shown) }
        val photoRows = (1..shown).map { OutlineRowEntity(5, 100 + it, it - 1, OutlineRowEntity.KIND_PHOTO, "", it.toLong()) }
        val doc = BackupMapper.toDocument(
            RoomBackupSnapshot(
                memos = listOf(outline, memo), memoComments = emptyList(), diaryEntries = emptyList(), futureDiaryComments = emptyList(),
                attachmentBlobs = shas.map { AttachmentBlobEntity(it, "image", "image/png", 10, 8, 8, 1) },
                memoPhotoAttachments = relations,
                outlineRows = photoRows + lines,
            ),
            AppSettings.Default, 1, "test", 1,
        )
        return BackupValidator().validate(if (version == 23) doc else doc.copy(formatVersion = version))
    }

    @Test
    fun anOutlinesPhotoCapCountsTheRowsItShowsNotThePhotosItHolds() {
        // 20 shown and one held for 元に戻す (or back from a process death): a valid outline.
        assertEquals(BackupValidationResult.Valid, capped(outlinePhotos = 21, shown = 20))
        assertEquals(BackupValidationResult.Valid, capped(outlinePhotos = 25, shown = 19))
        fun orderIssue(result: BackupValidationResult) =
            result is BackupValidationResult.Invalid && BackupValidationIssue.INVALID_PHOTO_ORDER in result.issues
        assertTrue("21 shown is above the cap", orderIssue(capped(outlinePhotos = 21, shown = 21)))
        // A memo keeps its cap on every photo it has; a file before 23 keeps it on every photo.
        assertTrue(orderIssue(capped(outlinePhotos = 0, shown = 0, memoPhotos = 21)))
        assertTrue(orderIssue(capped(outlinePhotos = 21, shown = 20, version = 22)))
    }
}

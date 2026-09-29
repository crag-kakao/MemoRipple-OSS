package io.github.cragcoffee.memoripple.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Query("SELECT memoId, COUNT(*) AS count FROM memo_photo_attachments GROUP BY memoId")
    fun observeMemoPhotoCounts(): Flow<List<MemoPhotoCount>>

    @Query(
        """
        SELECT relation.id, relation.memoId AS ownerId, relation.blobSha256,
            relation.sortOrder, relation.createdAt, blob.mimeType, blob.sizeBytes,
            blob.widthPx, blob.heightPx
        FROM memo_photo_attachments AS relation
        INNER JOIN attachment_blobs AS blob ON blob.sha256 = relation.blobSha256
        WHERE relation.memoId = :memoId
        ORDER BY relation.sortOrder ASC, relation.id ASC
        """,
    )
    fun observeMemoPhotos(memoId: Long): Flow<List<PhotoAttachment>>

    /** A note's cover picture, shaped like any other photo so the same loader can draw it. */
    @Query(
        """
        SELECT note.id, note.id AS ownerId, note.coverBlobSha256 AS blobSha256,
            0 AS sortOrder, note.updatedAt AS createdAt, blob.mimeType, blob.sizeBytes,
            blob.widthPx, blob.heightPx
        FROM notes AS note
        INNER JOIN attachment_blobs AS blob ON blob.sha256 = note.coverBlobSha256
        WHERE note.id = :noteId
        """,
    )
    fun observeNoteCover(noteId: Long): Flow<PhotoAttachment?>

    @Query(
        """
        SELECT note.id, note.id AS ownerId, note.coverBlobSha256 AS blobSha256,
            0 AS sortOrder, note.updatedAt AS createdAt, blob.mimeType, blob.sizeBytes,
            blob.widthPx, blob.heightPx
        FROM notes AS note
        INNER JOIN attachment_blobs AS blob ON blob.sha256 = note.coverBlobSha256
        """,
    )
    fun observeAllNoteCovers(): Flow<List<PhotoAttachment>>

    @Query(
        """
        SELECT relation.id, relation.diaryEntryId AS ownerId, relation.blobSha256,
            relation.sortOrder, relation.createdAt, blob.mimeType, blob.sizeBytes,
            blob.widthPx, blob.heightPx
        FROM diary_photo_attachments AS relation
        INNER JOIN attachment_blobs AS blob ON blob.sha256 = relation.blobSha256
        WHERE relation.diaryEntryId = :diaryEntryId
        ORDER BY relation.sortOrder ASC, relation.id ASC
        """,
    )
    fun observeDiaryPhotos(diaryEntryId: Long): Flow<List<PhotoAttachment>>

    @Query("SELECT COUNT(*) FROM memo_photo_attachments WHERE memoId = :memoId")
    suspend fun memoPhotoCount(memoId: Long): Int

    @Query("SELECT COUNT(*) FROM diary_photo_attachments WHERE diaryEntryId = :entryId")
    suspend fun diaryPhotoCount(entryId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM memos WHERE id = :memoId)")
    suspend fun memoExists(memoId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM diary_entries WHERE id = :entryId)")
    suspend fun diaryEntryExists(entryId: Long): Boolean

    @Query(
        "SELECT * FROM memo_photo_attachments " +
            "WHERE memoId = :memoId ORDER BY sortOrder ASC, id ASC",
    )
    suspend fun memoRelations(memoId: Long): List<MemoPhotoAttachmentEntity>

    @Query(
        "SELECT * FROM diary_photo_attachments " +
            "WHERE diaryEntryId = :entryId ORDER BY sortOrder ASC, id ASC",
    )
    suspend fun diaryRelations(entryId: Long): List<DiaryPhotoAttachmentEntity>

    @Query(
        "UPDATE memo_photo_attachments SET sortOrder = :sortOrder " +
            "WHERE id = :attachmentId AND memoId = :memoId",
    )
    suspend fun updateMemoSortOrder(memoId: Long, attachmentId: Long, sortOrder: Int): Int

    @Query(
        "UPDATE diary_photo_attachments SET sortOrder = :sortOrder " +
            "WHERE id = :attachmentId AND diaryEntryId = :entryId",
    )
    suspend fun updateDiarySortOrder(entryId: Long, attachmentId: Long, sortOrder: Int): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM memo_photo_attachments WHERE memoId = :memoId")
    suspend fun nextMemoSortOrder(memoId: Long): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM diary_photo_attachments WHERE diaryEntryId = :entryId")
    suspend fun nextDiarySortOrder(entryId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBlob(blob: AttachmentBlobEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemoRelation(relation: MemoPhotoAttachmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDiaryRelation(relation: DiaryPhotoAttachmentEntity): Long

    @Query("DELETE FROM memo_photo_attachments WHERE id = :id AND memoId = :memoId")
    suspend fun deleteMemoRelation(memoId: Long, id: Long): Int

    @Query("DELETE FROM diary_photo_attachments WHERE id = :id AND diaryEntryId = :entryId")
    suspend fun deleteDiaryRelation(entryId: Long, id: Long): Int

    // The backup validator requires every owner's photo sortOrder to be a contiguous
    // 0..n-1, the same invariant the reorder sheet writes. A bare delete leaves a gap
    // that never heals (nextMemoSortOrder is MAX+1) and permanently fails every backup,
    // so a delete closes its own gap in the same transaction — the way a comment's
    // deleteAndNormalize already does for playbackOrder.

    @Transaction
    suspend fun deleteMemoRelationAndNormalize(memoId: Long, id: Long): Int {
        val deleted = deleteMemoRelation(memoId, id)
        if (deleted > 0) {
            memoRelations(memoId).forEachIndexed { index, relation ->
                if (relation.sortOrder != index) {
                    updateMemoSortOrder(memoId, relation.id, index)
                }
            }
        }
        return deleted
    }

    @Transaction
    suspend fun deleteDiaryRelationAndNormalize(entryId: Long, id: Long): Int {
        val deleted = deleteDiaryRelation(entryId, id)
        if (deleted > 0) {
            diaryRelations(entryId).forEachIndexed { index, relation ->
                if (relation.sortOrder != index) {
                    updateDiarySortOrder(entryId, relation.id, index)
                }
            }
        }
        return deleted
    }

    @Query("SELECT sha256 FROM attachment_blobs")
    suspend fun allBlobShas(): List<String>

    @Query(
        """
        SELECT sha256 FROM attachment_blobs AS blob
        WHERE NOT EXISTS (
            SELECT 1 FROM memo_photo_attachments WHERE blobSha256 = blob.sha256
        ) AND NOT EXISTS (
            SELECT 1 FROM diary_photo_attachments WHERE blobSha256 = blob.sha256
        ) AND NOT EXISTS (
            SELECT 1 FROM notes WHERE coverBlobSha256 = blob.sha256
        )
        """,
    )
    suspend fun unreferencedBlobShas(): List<String>

    @Query(
        """
        DELETE FROM attachment_blobs
        WHERE sha256 = :sha256
          AND NOT EXISTS (SELECT 1 FROM memo_photo_attachments WHERE blobSha256 = :sha256)
          AND NOT EXISTS (SELECT 1 FROM diary_photo_attachments WHERE blobSha256 = :sha256)
          AND NOT EXISTS (SELECT 1 FROM notes WHERE coverBlobSha256 = :sha256)
        """,
    )
    suspend fun deleteBlobIfUnreferenced(sha256: String): Int
}

package io.github.cragcoffee.memoripple.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One block of a journal entry's content (Room 27, docs/MEMO_CONTENT_BLOCKS.md §11): a text or a
 * photo, at a place in the entry. These rows are the entry's source of truth; `diary_entries.body`
 * is their projection. The same shape as [MemoContentBlockEntity], keyed to the diary's own rows.
 */
@Entity(
    tableName = "diary_content_blocks",
    foreignKeys = [
        ForeignKey(
            entity = DiaryEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["diaryEntryId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DiaryPhotoAttachmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["photoAttachmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("diaryEntryId"),
        Index(value = ["photoAttachmentId"], unique = true),
    ],
)
data class DiaryContentBlockEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val diaryEntryId: Long,
    /** 0..n-1 within the entry. */
    val position: Int,
    /** [MemoContentBlockEntity.TYPE_TEXT] or [MemoContentBlockEntity.TYPE_PHOTO]. */
    val type: String,
    /** The words of a text block (may be empty); null for a photo. */
    val text: String?,
    /** The `diary_photo_attachments` row of a photo block; null for a text. */
    val photoAttachmentId: Long?,
)

@Dao
interface DiaryContentBlockDao {
    @Query("SELECT * FROM diary_content_blocks WHERE diaryEntryId = :entryId ORDER BY position ASC, id ASC")
    suspend fun blocks(entryId: Long): List<DiaryContentBlockEntity>

    @Query("SELECT * FROM diary_content_blocks WHERE diaryEntryId = :entryId ORDER BY position ASC, id ASC")
    fun observeBlocks(entryId: Long): Flow<List<DiaryContentBlockEntity>>

    @Insert
    suspend fun insert(block: DiaryContentBlockEntity): Long

    @Query("DELETE FROM diary_content_blocks WHERE diaryEntryId = :entryId")
    suspend fun deleteForEntry(entryId: Long): Int

    @Query("SELECT body FROM diary_entries WHERE id = :entryId")
    suspend fun entryBody(entryId: Long): String?

    @Query("SELECT body FROM diary_entries WHERE id = :entryId")
    fun observeEntryBody(entryId: Long): Flow<String?>

    @Query("SELECT state FROM diary_entries WHERE id = :entryId")
    suspend fun entryState(entryId: Long): String?

    /** The projection, and the time the words changed — the AI's version check reads `updatedAt`. */
    @Query("UPDATE diary_entries SET body = :body, updatedAt = :updatedAt WHERE id = :entryId")
    suspend fun setBody(entryId: Long, body: String, updatedAt: Long): Int
}

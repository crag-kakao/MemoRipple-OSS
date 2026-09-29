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
 * One block of a memo's content (Room 26, docs/MEMO_CONTENT_BLOCKS.md): a text or a photo, at a
 * place in the memo. For a memo (kind `memo`) these rows are the source of truth; `memos.body` is
 * their projection. An outline has none.
 */
@Entity(
    tableName = "memo_content_blocks",
    foreignKeys = [
        ForeignKey(
            entity = MemoEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MemoPhotoAttachmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["photoAttachmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("memoId"),
        Index(value = ["photoAttachmentId"], unique = true),
    ],
)
data class MemoContentBlockEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val memoId: Long,
    /** 0..n-1 within the memo. */
    val position: Int,
    /** [TYPE_TEXT] or [TYPE_PHOTO]. */
    val type: String,
    /** The words of a text block (may be empty); null for a photo. */
    val text: String?,
    /** The `memo_photo_attachments` row of a photo block; null for a text. */
    val photoAttachmentId: Long?,
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_PHOTO = "photo"
    }
}

@Dao
interface MemoContentBlockDao {
    @Query("SELECT * FROM memo_content_blocks WHERE memoId = :memoId ORDER BY position ASC, id ASC")
    suspend fun blocks(memoId: Long): List<MemoContentBlockEntity>

    @Query("SELECT * FROM memo_content_blocks WHERE memoId = :memoId ORDER BY position ASC, id ASC")
    fun observeBlocks(memoId: Long): Flow<List<MemoContentBlockEntity>>

    @Insert
    suspend fun insert(block: MemoContentBlockEntity): Long

    @Query("DELETE FROM memo_content_blocks WHERE memoId = :memoId")
    suspend fun deleteForMemo(memoId: Long): Int

    @Query("UPDATE memo_content_blocks SET text = :text WHERE id = :id AND memoId = :memoId AND type = 'text'")
    suspend fun updateText(memoId: Long, id: Long, text: String): Int

    @Query("SELECT kind FROM memos WHERE id = :memoId")
    suspend fun memoKind(memoId: Long): String?

    @Query("SELECT body FROM memos WHERE id = :memoId")
    suspend fun memoBody(memoId: Long): String?

    @Query("SELECT body FROM memos WHERE id = :memoId")
    fun observeMemoBody(memoId: Long): Flow<String?>

    @Query("UPDATE memos SET body = :body WHERE id = :memoId")
    suspend fun setBody(memoId: Long, body: String): Int
}

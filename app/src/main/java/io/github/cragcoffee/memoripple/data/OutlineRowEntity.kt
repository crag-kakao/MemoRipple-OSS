package io.github.cragcoffee.memoripple.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One line of an outline (Room 28, docs/OUTLINE_STABLE_ROWS.md): its lasting id within the outline,
 * its place, and its text exactly as written (indent, marker and words — what
 * [io.github.cragcoffee.memoripple.domain.outline.OutlineText.parseLine] reads). For an outline
 * (kind `outline`) these rows are the source of truth; `memos.body` is their projection.
 */
@Entity(
    tableName = "outline_rows",
    primaryKeys = ["memoId", "rowId"],
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
data class OutlineRowEntity(
    val memoId: Long,
    /** Lasting within the outline: never renumbered, never reused while the outline is open. */
    val rowId: Int,
    /** 0..n-1 within the outline. */
    val position: Int,
    /** [KIND_TEXT] or [KIND_PHOTO] (Room 29, docs/OUTLINE_PHOTO_ROWS.md). */
    val kind: String = KIND_TEXT,
    /** The line as written, without its line break; for a photo row, its indent alone. */
    val text: String,
    /** The `memo_photo_attachments` row a photo row shows; null for a line of words. */
    val photoAttachmentId: Long? = null,
) {
    companion object {
        const val KIND_TEXT = "text"
        const val KIND_PHOTO = "photo"
    }
}

@Dao
interface OutlineRowDao {
    @Query("SELECT * FROM outline_rows WHERE memoId = :memoId ORDER BY position ASC, rowId ASC")
    suspend fun rows(memoId: Long): List<OutlineRowEntity>

    @Query("SELECT * FROM outline_rows WHERE memoId = :memoId ORDER BY position ASC, rowId ASC")
    fun observeRows(memoId: Long): Flow<List<OutlineRowEntity>>

    @Insert
    suspend fun insertAll(rows: List<OutlineRowEntity>)

    @Query("DELETE FROM outline_rows WHERE memoId = :memoId")
    suspend fun deleteForMemo(memoId: Long): Int

    @Query("SELECT kind FROM memos WHERE id = :memoId")
    suspend fun memoKind(memoId: Long): String?

    @Query("SELECT body FROM memos WHERE id = :memoId")
    suspend fun memoBody(memoId: Long): String?

    @Query("SELECT COUNT(*) FROM memo_comments WHERE memoId = :memoId")
    suspend fun commentCount(memoId: Long): Int

    @Query("SELECT COUNT(*) FROM memo_tag_cross_refs WHERE memoId = :memoId")
    suspend fun tagCount(memoId: Long): Int

    @Query("SELECT COUNT(*) FROM memo_photo_attachments WHERE memoId = :memoId")
    suspend fun photoCount(memoId: Long): Int

    /** An outline taken back as if it had never been made (docs/OUTLINE_PHOTO_ROWS.md §8); its rows go with it. */
    @Query("DELETE FROM memos WHERE id = :memoId AND kind = 'outline'")
    suspend fun deleteOutline(memoId: Long): Int

    /** The outline's photos that no photo row shows (a photo row taken out, not yet let go). */
    @Query(
        "SELECT id FROM memo_photo_attachments WHERE memoId = :memoId " +
            "AND id NOT IN (SELECT photoAttachmentId FROM outline_rows WHERE memoId = :memoId AND photoAttachmentId IS NOT NULL)",
    )
    suspend fun unshownPhotos(memoId: Long): List<Long>
}

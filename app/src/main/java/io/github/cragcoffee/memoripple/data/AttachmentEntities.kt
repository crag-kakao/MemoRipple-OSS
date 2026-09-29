package io.github.cragcoffee.memoripple.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "attachment_blobs", primaryKeys = ["sha256"])
data class AttachmentBlobEntity(
    val sha256: String,
    val kind: String,
    val mimeType: String,
    val sizeBytes: Long,
    val widthPx: Int,
    val heightPx: Int,
    val createdAt: Long,
)

@Entity(
    tableName = "memo_photo_attachments",
    foreignKeys = [
        ForeignKey(
            entity = MemoEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AttachmentBlobEntity::class,
            parentColumns = ["sha256"],
            childColumns = ["blobSha256"],
        ),
    ],
    indices = [
        Index("memoId"),
        Index("blobSha256"),
        Index(value = ["memoId", "blobSha256"], unique = true),
    ],
)
data class MemoPhotoAttachmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val memoId: Long,
    val blobSha256: String,
    val sortOrder: Int,
    val createdAt: Long,
)

@Entity(
    tableName = "diary_photo_attachments",
    foreignKeys = [
        ForeignKey(
            entity = DiaryEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["diaryEntryId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AttachmentBlobEntity::class,
            parentColumns = ["sha256"],
            childColumns = ["blobSha256"],
        ),
    ],
    indices = [
        Index("diaryEntryId"),
        Index("blobSha256"),
        Index(value = ["diaryEntryId", "blobSha256"], unique = true),
    ],
)
data class DiaryPhotoAttachmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val diaryEntryId: Long,
    val blobSha256: String,
    val sortOrder: Int,
    val createdAt: Long,
)

data class PhotoAttachment(
    val id: Long,
    val ownerId: Long,
    val blobSha256: String,
    val sortOrder: Int,
    val createdAt: Long,
    val mimeType: String,
    val sizeBytes: Long,
    val widthPx: Int,
    val heightPx: Int,
)

data class MemoPhotoCount(val memoId: Long, val count: Int)

object AttachmentKind {
    const val IMAGE = "image"
}

package io.github.cragcoffee.memoripple.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "future_diary_comments",
    foreignKeys = [
        ForeignKey(
            entity = DiaryEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["diaryEntryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("diaryEntryId"), Index("revealAt")],
)
data class FutureDiaryCommentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val diaryEntryId: Long,
    val text: String,
    val sealedAt: Long,
    val revealAt: Long,
    val deliveredAt: Long? = null,
    val revealedAt: Long? = null,
    val firstPresentedAt: Long? = null,
    val appearanceColor: String = "default",
    val appearanceSize: String = "standard",
    val appearanceEmphasis: String = "normal",
    val motionMode: String = "flow",
)

data class FutureDiaryCommentOverviewRecord(
    val id: Long,
    val diaryEntryId: Long,
    val revealAt: Long,
    val deliveredAt: Long?,
    val revealedAt: Long?,
    val firstPresentedAt: Long?,
    val revealedText: String?,
    val revealedAppearanceColor: String?,
    val revealedAppearanceSize: String?,
    val revealedAppearanceEmphasis: String?,
    val revealedMotionMode: String?,
)

package io.github.cragcoffee.memoripple.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "tags",
    indices = [Index(value = ["normalizedName"], unique = true)],
)
data class TagEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String,
    val createdAt: Long,
)

@Entity(
    tableName = "memo_tag_cross_refs",
    primaryKeys = ["memoId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = MemoEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tagId")],
)
data class MemoTagCrossRef(val memoId: Long, val tagId: Long)

data class TagSummary(
    val id: Long,
    val name: String,
    val normalizedName: String,
    val createdAt: Long,
    val memoCount: Int,
)

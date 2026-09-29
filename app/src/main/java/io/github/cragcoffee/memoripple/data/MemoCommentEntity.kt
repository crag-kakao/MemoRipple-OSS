package io.github.cragcoffee.memoripple.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(
    tableName = "memo_comments",
    foreignKeys = [
        ForeignKey(
            entity = MemoEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("memoId")],
)
data class MemoCommentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val memoId: Long,
    val text: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val playbackOrder: Int = 0,
    @ColumnInfo(defaultValue = "'default'") val appearanceColor: String = "default",
    @ColumnInfo(defaultValue = "'standard'") val appearanceSize: String = "standard",
    @ColumnInfo(defaultValue = "'normal'") val appearanceEmphasis: String = "normal",
    @ColumnInfo(defaultValue = "'standard'") val motionSpeed: String = "standard",
    @ColumnInfo(defaultValue = "'auto'") val motionPlacement: String = "auto",
    @ColumnInfo(defaultValue = "'flow'") val motionMode: String = "flow",
    @ColumnInfo(defaultValue = "'rtl'") val flowDirection: String = "rtl",
    @ColumnInfo(defaultValue = "'straight'") val flowEffect: String = "straight",
    /**
     * コメントリンクの番号 (R1, R2…). Memo-scoped, assigned max+1, never reassigned after a
     * deletion — a gap is safer than a body marker quietly pointing at somebody else. Null
     * means an ordinary comment; a linked one leaves the normal playback timeline and flows
     * only when reading passes (or taps) its marker.
     */
    val linkNo: Int? = null,
)

package io.github.cragcoffee.memoripple.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.cragcoffee.memoripple.domain.diary.DiaryState

@Entity(
    tableName = "diary_entries",
    // Several entries may share a day since Room 23; the day plus creation time is how they are
    // grouped and ordered. The old unique index was dropped by MIGRATION_22_23.
    indices = [Index(value = ["diaryDateEpochDay", "createdAt"])],
)
data class DiaryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val diaryDateEpochDay: Long,
    val body: String,
    val state: DiaryState,
    val createdAt: Long,
    val updatedAt: Long,
    val finalizedAt: Long? = null,
    val correctionStartedAt: Long? = null,
    val lockedAt: Long? = null,
)

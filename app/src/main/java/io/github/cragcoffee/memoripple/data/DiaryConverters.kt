package io.github.cragcoffee.memoripple.data

import androidx.room.TypeConverter
import io.github.cragcoffee.memoripple.domain.diary.DiaryState

class DiaryConverters {
    @TypeConverter
    fun fromDiaryState(state: DiaryState): String = state.name

    @TypeConverter
    fun toDiaryState(value: String): DiaryState = DiaryState.valueOf(value)
}

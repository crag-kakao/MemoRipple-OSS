package io.github.cragcoffee.memoripple.domain.diary

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import java.time.YearMonth

data class DiaryCalendarDay(
    val date: LocalDate,
    val belongsToDisplayedMonth: Boolean,
    val hasDiary: Boolean,
    val isToday: Boolean,
    val isSelected: Boolean,
    val isFuture: Boolean,
)

data class DiaryCalendarMonth(
    val displayedMonth: YearMonth,
    val firstDayOfWeek: DayOfWeek,
    val weekDays: List<DayOfWeek>,
    val days: List<DiaryCalendarDay>,
) {
    init {
        require(weekDays.size == DAYS_PER_WEEK)
        require(days.size == CALENDAR_CELL_COUNT)
    }

    companion object {
        const val DAYS_PER_WEEK = 7
        const val CALENDAR_CELL_COUNT = 42
    }
}

object DiaryCalendarGridFactory {
    fun create(
        displayedMonth: YearMonth,
        today: LocalDate,
        firstDayOfWeek: DayOfWeek,
        diaryDates: Set<LocalDate> = emptySet(),
        selectedDate: LocalDate? = null,
    ): DiaryCalendarMonth {
        val firstOfMonth = displayedMonth.atDay(1)
        val leadingDays = (
            firstOfMonth.dayOfWeek.value - firstDayOfWeek.value +
                DiaryCalendarMonth.DAYS_PER_WEEK
            ) % DiaryCalendarMonth.DAYS_PER_WEEK
        val firstCellDate = firstOfMonth.minusDays(leadingDays.toLong())
        val weekDays = List(DiaryCalendarMonth.DAYS_PER_WEEK) { offset ->
            firstDayOfWeek.plus(offset.toLong())
        }
        val days = List(DiaryCalendarMonth.CALENDAR_CELL_COUNT) { offset ->
            val date = firstCellDate.plusDays(offset.toLong())
            DiaryCalendarDay(
                date = date,
                belongsToDisplayedMonth = YearMonth.from(date) == displayedMonth,
                hasDiary = date in diaryDates,
                isToday = date == today,
                isSelected = date == selectedDate,
                isFuture = date.isAfter(today),
            )
        }
        return DiaryCalendarMonth(
            displayedMonth = displayedMonth,
            firstDayOfWeek = firstDayOfWeek,
            weekDays = weekDays,
            days = days,
        )
    }
}

object DiaryCalendarNavigation {
    fun canGoToNextMonth(displayedMonth: YearMonth, currentMonth: YearMonth): Boolean =
        displayedMonth < currentMonth

    fun previousMonth(displayedMonth: YearMonth): YearMonth = displayedMonth.minusMonths(1)

    fun nextMonth(displayedMonth: YearMonth, currentMonth: YearMonth): YearMonth =
        if (canGoToNextMonth(displayedMonth, currentMonth)) {
            displayedMonth.plusMonths(1).coerceAtMost(currentMonth)
        } else {
            displayedMonth
        }

    fun selectionAfterMonthChange(displayedMonth: YearMonth, today: LocalDate): LocalDate? =
        today.takeIf { YearMonth.from(it) == displayedMonth }
}

data class PastTodayItem<T>(
    val value: T,
    val date: LocalDate,
    val yearsAgo: Int,
)

object PastTodayFactory {
    fun <T> create(
        values: List<T>,
        today: LocalDate,
        dateOf: (T) -> LocalDate,
    ): List<PastTodayItem<T>> {
        val todayMonthDay = MonthDay.from(today)
        return values.mapNotNull { value ->
            val date = dateOf(value)
            if (date.year >= today.year || MonthDay.from(date) != todayMonthDay) {
                null
            } else {
                PastTodayItem(
                    value = value,
                    date = date,
                    yearsAgo = today.year - date.year,
                )
            }
        }.sortedByDescending(PastTodayItem<T>::date)
    }
}

package io.github.cragcoffee.memoripple.domain.diary

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaryCalendarTest {
    @Test
    fun everyMonthUsesSixWeeksAndStartsOnTheRequestedWeekday() {
        listOf(
            YearMonth.of(2026, 2),
            YearMonth.of(2024, 2),
            YearMonth.of(2026, 4),
            YearMonth.of(2026, 7),
        ).forEach { month ->
            DayOfWeek.entries.forEach { firstDayOfWeek ->
                val calendar = DiaryCalendarGridFactory.create(
                    displayedMonth = month,
                    today = LocalDate.of(2026, 8, 22),
                    firstDayOfWeek = firstDayOfWeek,
                )

                assertEquals(42, calendar.days.size)
                assertEquals(firstDayOfWeek, calendar.days.first().date.dayOfWeek)
                assertEquals(month.lengthOfMonth(), calendar.days.count { it.belongsToDisplayedMonth })
            }
        }
    }

    @Test
    fun sundayAndMondayFirstProduceTheExpectedHeadersAndLeadingCells() {
        val month = YearMonth.of(2026, 8)
        val today = LocalDate.of(2026, 8, 22)

        val sundayFirst = DiaryCalendarGridFactory.create(month, today, DayOfWeek.SUNDAY)
        val mondayFirst = DiaryCalendarGridFactory.create(month, today, DayOfWeek.MONDAY)

        assertEquals(DayOfWeek.SUNDAY, sundayFirst.weekDays.first())
        assertEquals(LocalDate.of(2026, 7, 26), sundayFirst.days.first().date)
        assertEquals(DayOfWeek.MONDAY, mondayFirst.weekDays.first())
        assertEquals(LocalDate.of(2026, 7, 27), mondayFirst.days.first().date)
    }

    @Test
    fun leapYearAndYearBoundaryAreDelegatedToJavaTime() {
        val leapFebruary = DiaryCalendarGridFactory.create(
            YearMonth.of(2024, 2),
            LocalDate.of(2024, 2, 29),
            DayOfWeek.SUNDAY,
        )
        val january = DiaryCalendarGridFactory.create(
            YearMonth.of(2026, 1),
            LocalDate.of(2026, 1, 10),
            DayOfWeek.MONDAY,
        )

        assertTrue(leapFebruary.days.any { it.date == LocalDate.of(2024, 2, 29) })
        assertTrue(january.days.first().date.year == 2025)
        assertEquals(YearMonth.of(2025, 12), DiaryCalendarNavigation.previousMonth(YearMonth.of(2026, 1)))
    }

    @Test
    fun dayStateMapsDiaryTodaySelectionPastAndFuture() {
        val today = LocalDate.of(2026, 8, 22)
        val diaryDate = today.minusDays(2)
        val calendar = DiaryCalendarGridFactory.create(
            displayedMonth = YearMonth.from(today),
            today = today,
            firstDayOfWeek = DayOfWeek.SUNDAY,
            diaryDates = setOf(diaryDate),
            selectedDate = today,
        )

        val diaryDay = calendar.days.single { it.date == diaryDate }
        val todayDay = calendar.days.single { it.date == today }
        val futureDay = calendar.days.single { it.date == today.plusDays(1) }

        assertTrue(diaryDay.hasDiary)
        assertFalse(diaryDay.isFuture)
        assertTrue(todayDay.isToday)
        assertTrue(todayDay.isSelected)
        assertFalse(todayDay.isFuture)
        assertTrue(futureDay.isFuture)
        assertFalse(futureDay.isSelected)
    }

    @Test
    fun navigationStopsAtCurrentMonthAndSelectionPolicyIsExplicit() {
        val today = LocalDate.of(2026, 8, 22)
        val current = YearMonth.from(today)
        val previous = current.minusMonths(1)

        assertTrue(DiaryCalendarNavigation.canGoToNextMonth(previous, current))
        assertFalse(DiaryCalendarNavigation.canGoToNextMonth(current, current))
        assertEquals(current, DiaryCalendarNavigation.nextMonth(previous, current))
        assertEquals(current, DiaryCalendarNavigation.nextMonth(current, current))
        assertEquals(today, DiaryCalendarNavigation.selectionAfterMonthChange(current, today))
        assertNull(DiaryCalendarNavigation.selectionAfterMonthChange(previous, today))
    }

    @Test
    fun largeDiaryHistoryStillMapsOnlyTheFixedCalendarGrid() {
        val today = LocalDate.of(2026, 8, 22)
        val diaryDates = (0 until 500).mapTo(mutableSetOf()) { offset ->
            today.minusDays(offset.toLong())
        }

        val calendar = DiaryCalendarGridFactory.create(
            displayedMonth = YearMonth.from(today),
            today = today,
            firstDayOfWeek = DayOfWeek.MONDAY,
            diaryDates = diaryDates,
        )

        assertEquals(500, diaryDates.size)
        assertEquals(42, calendar.days.size)
        assertEquals(22, calendar.days.count { it.belongsToDisplayedMonth && it.hasDiary })
    }
}

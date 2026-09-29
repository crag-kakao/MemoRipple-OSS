package io.github.cragcoffee.memoripple.domain.diary

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PastTodayFactoryTest {
    @Test
    fun emptyInputProducesNoRecall() {
        assertTrue(
            PastTodayFactory.create(emptyList<LocalDate>(), LocalDate.of(2026, 8, 22)) { it }
                .isEmpty(),
        )
    }

    @Test
    fun exactMonthDayExcludesTodayAndSortsNewestYearFirst() {
        val today = LocalDate.of(2026, 8, 22)
        val result = PastTodayFactory.create(
            values = listOf(
                today,
                LocalDate.of(2022, 8, 22),
                LocalDate.of(2025, 8, 22),
                LocalDate.of(2024, 8, 21),
                LocalDate.of(2024, 8, 22),
                LocalDate.of(2027, 8, 22),
            ),
            today = today,
            dateOf = { it },
        )

        assertEquals(
            listOf(
                LocalDate.of(2025, 8, 22),
                LocalDate.of(2024, 8, 22),
                LocalDate.of(2022, 8, 22),
            ),
            result.map { it.date },
        )
        assertEquals(listOf(1, 2, 4), result.map { it.yearsAgo })
    }

    @Test
    fun leapDayOnlyMatchesPastLeapDaysWithoutSubstitution() {
        val result = PastTodayFactory.create(
            values = listOf(
                LocalDate.of(2020, 2, 29),
                LocalDate.of(2024, 2, 29),
                LocalDate.of(2025, 2, 28),
                LocalDate.of(2024, 3, 1),
            ),
            today = LocalDate.of(2028, 2, 29),
            dateOf = { it },
        )

        assertEquals(
            listOf(LocalDate.of(2024, 2, 29), LocalDate.of(2020, 2, 29)),
            result.map { it.date },
        )
    }
}

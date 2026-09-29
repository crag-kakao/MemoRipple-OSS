package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.documents.DocumentDateRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** RED 5–9: the token is resolved by the app's TimeProvider, never by the model; the week is Monday..Sunday. */
class DateTokenTest {
    private val time = FixedTime(LocalDate.of(2026, 9, 19)) // Saturday

    @Test fun todayResolvesToTheProvidersDay() =
        assertEquals(DocumentDateRange.day(LocalDate.of(2026, 9, 19)), DateTokens.resolve(DateToken.TODAY, time))

    @Test fun yesterdayResolvesToTheDayBefore() =
        assertEquals(DocumentDateRange.day(LocalDate.of(2026, 9, 18)), DateTokens.resolve(DateToken.YESTERDAY, time))

    @Test fun thisWeekIsMondayToSundayOfTheProvidersWeek() =
        assertEquals(DocumentDateRange.of(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20)), DateTokens.resolve(DateToken.THIS_WEEK, time))

    @Test fun lastWeekIsThePreviousMondayToSunday() =
        assertEquals(DocumentDateRange.of(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 13)), DateTokens.resolve(DateToken.LAST_WEEK, time))

    @Test fun onlyTheFourTokensExistAndAModelDateIsNotOne() {
        assertEquals(listOf("TODAY", "YESTERDAY", "THIS_WEEK", "LAST_WEEK"), DateToken.entries.map { it.name })
        assertNull(DateToken.fromModel("2026-09-18"))
        assertNull(DateToken.fromModel("9月10日"))
        assertNull(DateToken.fromModel("TOMORROW"))
        assertEquals(DateToken.YESTERDAY, DateToken.fromModel("yesterday"))
        // a different provider day moves every range: the model never picked a date
        val monday = FixedTime(LocalDate.of(2026, 9, 21))
        assertEquals(DocumentDateRange.of(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27)), DateTokens.resolve(DateToken.THIS_WEEK, monday))
    }
}

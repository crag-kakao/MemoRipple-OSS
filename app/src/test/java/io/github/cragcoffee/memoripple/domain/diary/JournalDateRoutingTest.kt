package io.github.cragcoffee.memoripple.domain.diary

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The old `diary-editor/{epochDay}` route is a compatibility shim. A date is not an identity:
 * exactly one entry on that day opens that entry by id; none or several open the day's list —
 * and the shim never creates an entry by itself.
 */
class JournalDateRoutingTest {
    @Test
    fun exactlyOneEntryOpensItByIdAnythingElseOpensTheDayList() {
        assertEquals(JournalDateTarget.DayList, JournalDateRouting.resolve(emptyList()))
        assertEquals(JournalDateTarget.Entry(42), JournalDateRouting.resolve(listOf(42L)))
        assertEquals(JournalDateTarget.DayList, JournalDateRouting.resolve(listOf(1L, 2L)))
    }
}

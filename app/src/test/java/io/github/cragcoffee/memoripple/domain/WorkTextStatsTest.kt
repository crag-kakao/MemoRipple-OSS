package io.github.cragcoffee.memoripple.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkTextStatsTest {

    @Test
    fun countingIgnoresTheMarkersBecauseTheyAreNotTheWords() {
        val stats = WorkTextStats.of("# 見出し\n- **項目**")

        assertEquals("見出し項目".length, stats.characters)
        assertEquals(2, stats.lines)
    }

    @Test
    fun whitespaceIsCountedSeparatelyForWritersWhoWantEither() {
        val stats = WorkTextStats.of("あ い う")

        assertEquals(5, stats.characters)
        assertEquals(3, stats.charactersWithoutWhitespace)
    }

    @Test
    fun blankLinesDoNotCountAsLines() {
        val stats = WorkTextStats.of("一行目\n\n\n二行目")

        assertEquals(2, stats.lines)
    }

    @Test
    fun taskProgressCountsCheckedAgainstTotal() {
        val stats = WorkTextStats.of("- [x] 一\n- [ ] 二\n- [ ] 三\n- ただの項目")

        assertTrue(stats.hasTasks)
        assertEquals(1, stats.doneTasks)
        assertEquals(3, stats.totalTasks)
    }

    @Test
    fun aBodyWithoutTasksReportsNoTasks() {
        val stats = WorkTextStats.of("ただの本文")

        assertFalse(stats.hasTasks)
        assertEquals(0, stats.totalTasks)
    }

    @Test
    fun anEmptyBodyIsZeroRatherThanAFailure() {
        val stats = WorkTextStats.of("")

        assertEquals(0, stats.characters)
        assertEquals(0, stats.lines)
        assertEquals(0, stats.totalTasks)
    }
}

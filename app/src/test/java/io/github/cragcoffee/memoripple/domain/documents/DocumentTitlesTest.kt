package io.github.cragcoffee.memoripple.domain.documents

import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.calendar.TimelineBuckets
import io.github.cragcoffee.memoripple.domain.calendar.TimelineFilter
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** One title rule, and the calendar keeps saying exactly what it said before it shared it. */
class DocumentTitlesTest {
    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")
    private val day: LocalDate = LocalDate.of(2026, 9, 18)
    private val at: Long = day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun titleThenFirstNonBlankLineTrimmedThenThePlaceholder() {
        assertEquals("題", DocumentTitles.resolve("題", "本文", "x"))
        assertEquals("- 一行目", DocumentTitles.resolve("", "\n\n  - 一行目  \n二行目", "x"))
        assertEquals("x", DocumentTitles.resolve("  ", " \n\t\n", "x"))
        assertEquals(null, DocumentTitles.firstLine("\n \n"))
        assertEquals(DocumentTitles.UNTITLED_MEMO, DocumentTitles.placeholder(DocumentKind.MEMO))
        assertEquals(DocumentTitles.UNTITLED_OUTLINE, DocumentTitles.placeholder(DocumentKind.OUTLINE))
        assertEquals(DocumentTitles.EMPTY_JOURNAL, DocumentTitles.placeholder(DocumentKind.JOURNAL))
    }

    @Test
    fun theCalendarRowsCarryTheSameTitlesAsBefore() {
        val memos = listOf(
            MemoEntity(id = 1, title = "題あり", body = "本文", createdAt = at, updatedAt = at),
            MemoEntity(id = 2, title = "", body = "  最初の行  \n次", createdAt = at, updatedAt = at),
            MemoEntity(id = 3, title = "", body = "\n \n", createdAt = at, updatedAt = at),
            MemoEntity(id = 4, title = "", body = "- 見出し\n  - 子", createdAt = at, updatedAt = at, kind = "outline"),
        )
        val journals = listOf(
            DiaryEntryEntity(id = 10, diaryDateEpochDay = day.toEpochDay(), body = "\n朝の記録\n", state = DiaryState.DRAFT, createdAt = at, updatedAt = at),
            DiaryEntryEntity(id = 11, diaryDateEpochDay = day.toEpochDay(), body = "", state = DiaryState.DRAFT, createdAt = at, updatedAt = at),
        )
        val titles = TimelineBuckets.itemsFor(day, memos, journals, zone, TimelineFilter.ALL).associate { it.id to it.title }
        // Exactly what the calendar showed before the rule moved into DocumentTitles.
        assertEquals("題あり", titles[1])
        assertEquals("最初の行", titles[2])
        assertEquals("無題のメモ", titles[3])
        assertEquals("- 見出し", titles[4])
        assertEquals("朝の記録", titles[10])
        assertEquals("（本文なし）", titles[11])
        // And the same inputs through the shared rule give the same words.
        assertEquals(titles[2], DocumentTitles.resolve("", "  最初の行  \n次", DocumentTitles.UNTITLED_MEMO))
        assertEquals(titles[3], DocumentTitles.resolve("", "\n \n", DocumentTitles.UNTITLED_MEMO))
        assertEquals(titles[11], DocumentTitles.resolve("", "", DocumentTitles.EMPTY_JOURNAL))
    }
}

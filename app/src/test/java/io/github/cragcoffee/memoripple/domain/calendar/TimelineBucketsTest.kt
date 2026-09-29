package io.github.cragcoffee.memoripple.domain.calendar

import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import java.time.LocalDate
import java.time.YearMonth
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calendar is a view over what already exists: memos and outlines by their timestamps,
 * journal entries by their day. A day is 00:00–23:59:59 in the device zone, nothing cleverer.
 * 「作成」 is createdAt on the day, 「更新」 is updatedAt on the day (and not the creation
 * moment), 「すべて」 is either — and a document created and updated on one day is one row.
 */
class TimelineBucketsTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val utc = ZoneId.of("UTC")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: ZoneId = tokyo): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun memo(id: Long, created: Long, updated: Long = created, kind: MemoKind = MemoKind.MEMO, title: String = "メモ$id") =
        MemoEntity(id = id, title = title, body = "", createdAt = created, updatedAt = updated, kind = kind.storageId)

    private fun journal(id: Long, day: LocalDate, created: Long, updated: Long = created, body: String = "日記$id") =
        DiaryEntryEntity(id = id, diaryDateEpochDay = day.toEpochDay(), body = body, state = DiaryState.DRAFT, createdAt = created, updatedAt = updated)

    private val sep17 = LocalDate.of(2026, 9, 17)

    @Test
    fun aMemoCreatedOnTheDaySitsUnderCreatedAndUnderAllAtItsCreationTime() {
        val m = memo(1, created = at(2026, 9, 17, 9, 10))
        val all = TimelineBuckets.itemsFor(sep17, listOf(m), emptyList(), tokyo, TimelineFilter.ALL)
        val created = TimelineBuckets.itemsFor(sep17, listOf(m), emptyList(), tokyo, TimelineFilter.CREATED)
        val updated = TimelineBuckets.itemsFor(sep17, listOf(m), emptyList(), tokyo, TimelineFilter.UPDATED)
        assertEquals(listOf(DocumentRef(DocumentKind.MEMO, 1)), all.map { it.sourceRef })
        assertEquals(TimelineActivity.CREATED, all.single().activity)
        assertEquals(DocumentKind.MEMO, all.single().kind)
        assertEquals("09:10", all.single().timeLabel)
        assertEquals(1, created.size)
        assertTrue(updated.isEmpty())
    }

    @Test
    fun aMemoWrittenToOnTheDayButBornEarlierIsAnUpdateNotACreation() {
        val m = memo(2, created = at(2026, 9, 10, 8, 0), updated = at(2026, 9, 17, 11, 40))
        val all = TimelineBuckets.itemsFor(sep17, listOf(m), emptyList(), tokyo, TimelineFilter.ALL)
        assertEquals(TimelineActivity.UPDATED, all.single().activity)
        assertEquals("11:40", all.single().timeLabel)
        assertTrue(TimelineBuckets.itemsFor(sep17, listOf(m), emptyList(), tokyo, TimelineFilter.CREATED).isEmpty())
        assertEquals(1, TimelineBuckets.itemsFor(sep17, listOf(m), emptyList(), tokyo, TimelineFilter.UPDATED).size)
        // And on its birthday it is a creation, not an update.
        val birthday = TimelineBuckets.itemsFor(LocalDate.of(2026, 9, 10), listOf(m), emptyList(), tokyo, TimelineFilter.ALL)
        assertEquals(TimelineActivity.CREATED, birthday.single().activity)
    }

    @Test
    fun createdAndUpdatedOnTheSameDayIsOneRowThatSaysBoth() {
        val m = memo(3, created = at(2026, 9, 17, 9, 0), updated = at(2026, 9, 17, 18, 5))
        val all = TimelineBuckets.itemsFor(sep17, listOf(m), emptyList(), tokyo, TimelineFilter.ALL)
        assertEquals(1, all.size)
        assertEquals(TimelineActivity.CREATED_AND_UPDATED, all.single().activity)
        assertEquals("09:00", all.single().timeLabel)
        assertEquals(1, TimelineBuckets.itemsFor(sep17, listOf(m), emptyList(), tokyo, TimelineFilter.CREATED).size)
        assertEquals(1, TimelineBuckets.itemsFor(sep17, listOf(m), emptyList(), tokyo, TimelineFilter.UPDATED).size)
    }

    @Test
    fun anOutlineIsListedLikeAMemoUnderItsOwnKind() {
        val o = memo(4, created = at(2026, 9, 17, 11, 40), kind = MemoKind.OUTLINE, title = "Calendar再設計")
        val all = TimelineBuckets.itemsFor(sep17, listOf(o), emptyList(), tokyo, TimelineFilter.ALL)
        assertEquals(DocumentKind.OUTLINE, all.single().kind)
        assertEquals(DocumentRef(DocumentKind.OUTLINE, 4), all.single().sourceRef)
        assertEquals("Calendar再設計", all.single().title)
    }

    @Test
    fun journalEntriesSitOnTheirDiaryDayEachByItsIdSeveralADay() {
        val morning = journal(10, sep17, created = at(2026, 9, 17, 9, 10), body = "朝の記録")
        val night = journal(11, sep17, created = at(2026, 9, 17, 23, 30), body = "今日の振り返り")
        // A journal dated the 17th but begun after midnight still belongs to the 17th.
        val late = journal(12, sep17, created = at(2026, 9, 18, 0, 20), body = "日付を跨いで書いた")
        val all = TimelineBuckets.itemsFor(sep17, emptyList(), listOf(night, late, morning), tokyo, TimelineFilter.ALL)
        assertEquals(listOf(DocumentRef(DocumentKind.JOURNAL, 10), DocumentRef(DocumentKind.JOURNAL, 11), DocumentRef(DocumentKind.JOURNAL, 12)), all.map { it.sourceRef })
        assertTrue(all.all { it.kind == DocumentKind.JOURNAL && it.activity == TimelineActivity.CREATED })
        assertEquals(listOf("朝の記録", "今日の振り返り", "日付を跨いで書いた"), all.map { it.title })
        assertTrue(TimelineBuckets.itemsFor(LocalDate.of(2026, 9, 18), emptyList(), listOf(late), tokyo, TimelineFilter.ALL).isEmpty())
    }

    @Test
    fun aJournalEditedOnALaterDayIsAnUpdateThereAndACreationOnItsOwnDay() {
        val j = journal(13, sep17, created = at(2026, 9, 17, 9, 0), updated = at(2026, 9, 20, 7, 30))
        val onItsDay = TimelineBuckets.itemsFor(sep17, emptyList(), listOf(j), tokyo, TimelineFilter.ALL).single()
        val later = TimelineBuckets.itemsFor(LocalDate.of(2026, 9, 20), emptyList(), listOf(j), tokyo, TimelineFilter.ALL).single()
        assertEquals(TimelineActivity.CREATED, onItsDay.activity)
        assertEquals(TimelineActivity.UPDATED, later.activity)
        assertEquals("07:30", later.timeLabel)
    }

    @Test
    fun theDayIsMidnightToMidnightInTheGivenZoneAcrossMonthYearAndZoneBoundaries() {
        // 2026-08-31 23:59 Tokyo is still August; 2026-09-01 00:00 Tokyo is September.
        val lastOfAugust = memo(20, created = at(2026, 8, 31, 23, 59))
        val firstOfSeptember = memo(21, created = at(2026, 9, 1, 0, 0))
        assertEquals(listOf(20L), TimelineBuckets.itemsFor(LocalDate.of(2026, 8, 31), listOf(lastOfAugust, firstOfSeptember), emptyList(), tokyo, TimelineFilter.ALL).map { it.id })
        assertEquals(listOf(21L), TimelineBuckets.itemsFor(LocalDate.of(2026, 9, 1), listOf(lastOfAugust, firstOfSeptember), emptyList(), tokyo, TimelineFilter.ALL).map { it.id })
        // Year boundary the same way.
        val newYearsEve = memo(22, created = at(2026, 12, 31, 23, 59))
        val newYear = memo(23, created = at(2027, 1, 1, 0, 0))
        assertEquals(listOf(22L), TimelineBuckets.itemsFor(LocalDate.of(2026, 12, 31), listOf(newYearsEve, newYear), emptyList(), tokyo, TimelineFilter.ALL).map { it.id })
        assertEquals(listOf(23L), TimelineBuckets.itemsFor(LocalDate.of(2027, 1, 1), listOf(newYearsEve, newYear), emptyList(), tokyo, TimelineFilter.ALL).map { it.id })
        // One instant, two zones: 2026-09-17 01:00 Tokyo is 2026-09-16 16:00 UTC.
        val earlyTokyo = memo(24, created = at(2026, 9, 17, 1, 0))
        assertEquals(listOf(24L), TimelineBuckets.itemsFor(sep17, listOf(earlyTokyo), emptyList(), tokyo, TimelineFilter.ALL).map { it.id })
        assertTrue(TimelineBuckets.itemsFor(sep17, listOf(earlyTokyo), emptyList(), utc, TimelineFilter.ALL).isEmpty())
        assertEquals(listOf(24L), TimelineBuckets.itemsFor(LocalDate.of(2026, 9, 16), listOf(earlyTokyo), emptyList(), utc, TimelineFilter.ALL).map { it.id })
    }

    @Test
    fun aDayIsOrderedByTheMomentThatPutEachRowThereAndKindsAreCountedForTheGrid() {
        val memo = memo(30, created = at(2026, 9, 17, 9, 10))
        val outline = memo(31, created = at(2026, 9, 1, 8, 0), updated = at(2026, 9, 17, 11, 40), kind = MemoKind.OUTLINE)
        val entry = journal(32, sep17, created = at(2026, 9, 17, 18, 0))
        val all = TimelineBuckets.itemsFor(sep17, listOf(outline, memo), listOf(entry), tokyo, TimelineFilter.ALL)
        assertEquals(listOf("09:10", "11:40", "18:00"), all.map { it.timeLabel })
        assertEquals(listOf(DocumentKind.MEMO, DocumentKind.OUTLINE, DocumentKind.JOURNAL), all.map { it.kind })

        val days = TimelineBuckets.daySummaries(listOf(outline, memo), listOf(entry), tokyo)
        assertEquals(3, days.getValue(sep17).count)
        assertEquals(setOf(DocumentKind.MEMO, DocumentKind.OUTLINE, DocumentKind.JOURNAL), days.getValue(sep17).kinds)
        assertEquals(1, days.getValue(LocalDate.of(2026, 9, 1)).count)
        assertEquals(setOf(DocumentKind.OUTLINE), days.getValue(LocalDate.of(2026, 9, 1)).kinds)
    }

    @Test
    fun theMonthWindowInMillisIsTheZonesFirstMidnightToTheNextMonthsFirstMidnight() {
        val window = TimelineBuckets.monthWindow(java.time.YearMonth.of(2026, 9), tokyo)
        assertEquals(at(2026, 9, 1, 0, 0), window.startInclusiveMillis)
        assertEquals(at(2026, 10, 1, 0, 0), window.endExclusiveMillis)
        assertEquals(LocalDate.of(2026, 9, 1).toEpochDay(), window.startEpochDay)
        assertEquals(LocalDate.of(2026, 9, 30).toEpochDay(), window.endEpochDayInclusive)
    }
}

/**
 * 「すべて」 is the month (human brief 2026-09-23): the same rules, applied to every day the rows
 * touch, grouped by day and newest first — so the calendar answers 「今月なにを書いたか」 and not
 * only 「この日になにをしたか」. Nothing about 作成 / 更新 or the day view changes: a month is
 * exactly the sum of its days.
 */
class TimelineMonthTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(tokyo).toInstant().toEpochMilli()

    private fun memo(id: Long, created: Long, updated: Long = created, kind: MemoKind = MemoKind.MEMO, title: String = "メモ$id") =
        MemoEntity(id = id, title = title, body = "", createdAt = created, updatedAt = updated, kind = kind.storageId)

    private fun journal(id: Long, day: LocalDate, created: Long, updated: Long = created, body: String = "日記$id") =
        DiaryEntryEntity(id = id, diaryDateEpochDay = day.toEpochDay(), body = body, state = DiaryState.DRAFT, createdAt = created, updatedAt = updated)

    private val september = YearMonth.of(2026, 9)

    @Test
    fun theMonthCarriesEveryKindGroupedByDayNewestFirst() {
        val memoRow = memo(1, created = at(2026, 9, 3, 9, 0))
        val outline = memo(2, created = at(2026, 9, 21, 10, 0), kind = MemoKind.OUTLINE, title = "MemoRipple改善案")
        val diary = journal(3, LocalDate.of(2026, 9, 23), created = at(2026, 9, 23, 22, 0))
        val days = TimelineBuckets.monthItems(september, listOf(memoRow, outline), listOf(diary), tokyo, TimelineFilter.ALL)

        assertEquals("newest day first", listOf(LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 3)), days.map { it.date })
        assertEquals(listOf(DocumentKind.JOURNAL), days[0].items.map { it.kind })
        assertEquals(listOf(DocumentKind.OUTLINE), days[1].items.map { it.kind })
        assertEquals(listOf(DocumentKind.MEMO), days[2].items.map { it.kind })
        assertEquals("every row opens through its own ref", listOf(3L, 2L, 1L), days.flatMap { d -> d.items.map { it.id } })
    }

    @Test
    fun aDayWithSeveralKindsKeepsTheDayViewsOwnOrder() {
        val morning = memo(1, created = at(2026, 9, 23, 8, 0))
        val noon = memo(2, created = at(2026, 9, 23, 12, 0), kind = MemoKind.OUTLINE)
        val diary = journal(3, LocalDate.of(2026, 9, 23), created = at(2026, 9, 23, 22, 0))
        val day = LocalDate.of(2026, 9, 23)
        val fromMonth = TimelineBuckets.monthItems(september, listOf(morning, noon), listOf(diary), tokyo, TimelineFilter.ALL).single()
        val fromDay = TimelineBuckets.itemsFor(day, listOf(morning, noon), listOf(diary), tokyo, TimelineFilter.ALL)
        assertEquals("a month day is exactly the day view", fromDay, fromMonth.items)
        assertEquals(day, fromMonth.date)
    }

    @Test
    fun aRowOutsideTheMonthIsNotInIt() {
        val august = memo(1, created = at(2026, 8, 31, 23, 0))
        val september1 = memo(2, created = at(2026, 9, 1, 0, 30))
        val october = memo(3, created = at(2026, 10, 1, 0, 30))
        val days = TimelineBuckets.monthItems(september, listOf(august, september1, october), emptyList(), tokyo, TimelineFilter.ALL)
        assertEquals(listOf(LocalDate.of(2026, 9, 1)), days.map { it.date })
        assertEquals(listOf(2L), days.single().items.map { it.id })
    }

    @Test
    fun aMemoCreatedBeforeTheMonthAndUpdatedInsideItSitsOnTheUpdateDayOnly() {
        val carried = memo(1, created = at(2026, 8, 20, 9, 0), updated = at(2026, 9, 5, 18, 0))
        val days = TimelineBuckets.monthItems(september, listOf(carried), emptyList(), tokyo, TimelineFilter.ALL)
        assertEquals(listOf(LocalDate.of(2026, 9, 5)), days.map { it.date })
        assertEquals(TimelineActivity.UPDATED, days.single().items.single().activity)
    }

    @Test
    fun aMonthWithNothingIsAnEmptyList() {
        assertTrue(TimelineBuckets.monthItems(september, emptyList(), emptyList(), tokyo, TimelineFilter.ALL).isEmpty())
        val elsewhere = memo(1, created = at(2026, 7, 4, 9, 0))
        assertTrue(TimelineBuckets.monthItems(september, listOf(elsewhere), emptyList(), tokyo, TimelineFilter.ALL).isEmpty())
    }

    @Test
    fun aDayThatTheFilterEmptiesIsNotADayOfTheMonth() {
        // 作成 and 更新 keep their meanings; a month asked with one of them simply has fewer days
        val created = memo(1, created = at(2026, 9, 3, 9, 0))
        val updated = memo(2, created = at(2026, 8, 1, 9, 0), updated = at(2026, 9, 10, 9, 0))
        val onlyCreated = TimelineBuckets.monthItems(september, listOf(created, updated), emptyList(), tokyo, TimelineFilter.CREATED)
        val onlyUpdated = TimelineBuckets.monthItems(september, listOf(created, updated), emptyList(), tokyo, TimelineFilter.UPDATED)
        assertEquals(listOf(LocalDate.of(2026, 9, 3)), onlyCreated.map { it.date })
        assertEquals(listOf(LocalDate.of(2026, 9, 10)), onlyUpdated.map { it.date })
    }

    @Test
    fun aJournalSitsOnItsDiaryDayEvenWhenItWasWrittenLater() {
        val backfilled = journal(1, LocalDate.of(2026, 9, 2), created = at(2026, 9, 20, 21, 0), updated = at(2026, 9, 20, 21, 0))
        val days = TimelineBuckets.monthItems(september, emptyList(), listOf(backfilled), tokyo, TimelineFilter.ALL)
        assertEquals("the diary day is the day", listOf(LocalDate.of(2026, 9, 2)), days.map { it.date })
    }

    @Test
    fun theMonthAgreesWithTheGridsMarks() {
        val rows = listOf(memo(1, created = at(2026, 9, 3, 9, 0)), memo(2, created = at(2026, 9, 21, 10, 0)))
        val journals = listOf(journal(3, LocalDate.of(2026, 9, 23), created = at(2026, 9, 23, 22, 0)))
        val marked = TimelineBuckets.daySummaries(rows, journals, tokyo).keys.filter { YearMonth.from(it) == september }.toSet()
        val listed = TimelineBuckets.monthItems(september, rows, journals, tokyo, TimelineFilter.ALL).map { it.date }.toSet()
        assertEquals("a dot on the grid is a day in the list", marked, listed)
    }
}

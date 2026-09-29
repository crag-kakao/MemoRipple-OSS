package io.github.cragcoffee.memoripple.data

import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A journal entry is a dated document with an id. A day may hold several; the date is how they
 * are grouped, never what they are. Nothing locks by itself any more — LOCKED is a state old
 * rows carry, and the only one that refuses edits.
 */
class DiaryRepositoryTest {
    @Test
    fun monthRangeObservesOnlyEntriesInsideInclusiveBounds() = runBlocking {
        val fixture = Fixture()
        val julyLast = LocalDate.of(2026, 7, 31)
        val augustFirst = LocalDate.of(2026, 8, 1)
        val augustLast = LocalDate.of(2026, 8, 31)
        val septemberFirst = LocalDate.of(2026, 9, 1)
        listOf(julyLast, augustFirst, augustLast, septemberFirst).forEach { fixture.dao.seed(DiaryState.LOCKED, it.toEpochDay()) }

        val result = fixture.repository.observeEntriesBetween(augustFirst, augustLast).first()

        assertEquals(
            listOf(augustLast.toEpochDay(), augustFirst.toEpochDay()),
            result.map(DiaryEntryEntity::diaryDateEpochDay),
        )
    }

    @Test
    fun twoEntriesCanBeCreatedOnTheSameDayAndAreListedInCreationOrder() = runBlocking {
        val fixture = Fixture()
        val day = fixture.time.currentLocalDate().toEpochDay()

        val morning = fixture.repository.createEntry(day)
        fixture.time.now = 2_000L
        val evening = fixture.repository.createEntry(day)
        fixture.repository.saveBody(morning.id, "朝の記録")
        fixture.repository.saveBody(evening.id, "夜の振り返り")

        val ofTheDay = fixture.repository.observeForDate(day).first()
        assertEquals(listOf(morning.id, evening.id), ofTheDay.map(DiaryEntryEntity::id))
        assertEquals(listOf("朝の記録", "夜の振り返り"), ofTheDay.map(DiaryEntryEntity::body))
        assertEquals(2, fixture.repository.entriesForDate(day).size)
    }

    @Test
    fun savingAnUnchangedBodyLeavesUpdatedAtAloneAndAChangedOneMovesIt() = runBlocking {
        val fixture = Fixture()
        val day = fixture.time.currentLocalDate().toEpochDay()
        val entry = fixture.repository.createEntry(day)
        fixture.time.now = 1_500L
        fixture.repository.saveBody(entry.id, "本文")
        assertEquals(1_500L, fixture.dao.values.value.single().updatedAt)

        fixture.time.now = 3_000L
        val same = fixture.repository.saveBody(entry.id, "本文")
        assertEquals(1_500L, (same as DiaryResult.Success).entry?.updatedAt)
        assertEquals(1_500L, fixture.dao.values.value.single().updatedAt)

        fixture.time.now = 4_000L
        fixture.repository.saveBody(entry.id, "本文を書き足した")
        assertEquals(4_000L, fixture.dao.values.value.single().updatedAt)
    }

    @Test
    fun aDateChangeLocksNothingAndYesterdaysJournalStaysEditable() = runBlocking {
        DiaryState.entries.filterNot { it == DiaryState.LOCKED }.forEach { startingState ->
            val fixture = Fixture()
            val yesterday = fixture.time.currentLocalDate().minusDays(1).toEpochDay()
            val seeded = fixture.dao.seed(startingState, yesterday)

            fixture.time.date = fixture.time.date.plusDays(1)
            fixture.time.now = 2_000L
            val result = fixture.repository.saveBody(seeded.id, "翌日に書き足す")

            val stored = fixture.dao.values.value.single()
            assertEquals(startingState.name, startingState, stored.state)
            assertNull(startingState.name, stored.lockedAt)
            assertEquals(startingState.name, "翌日に書き足す", (result as DiaryResult.Success).entry?.body)
        }
    }

    @Test
    fun onlyALockedEntryRefusesEdits() = runBlocking {
        val fixture = Fixture()
        val day = fixture.time.currentLocalDate().toEpochDay()
        val locked = fixture.dao.seed(DiaryState.LOCKED, day)

        val result = fixture.repository.saveBody(locked.id, "書き換え")

        assertEquals(DiaryResult.Rejected(DiaryRejection.INVALID_STATE, locked), result)
        assertEquals(locked.body, fixture.dao.values.value.single().body)
    }

    @Test
    fun aBlankDraftWithoutPhotosIsRemovedWhenSavedAndAMissingEntryIsReported() = runBlocking {
        val fixture = Fixture()
        val day = fixture.time.currentLocalDate().toEpochDay()
        val entry = fixture.repository.createEntry(day)

        assertEquals(DiaryResult.Success(null), fixture.repository.saveBody(entry.id, "  "))
        assertTrue(fixture.dao.values.value.isEmpty())
        assertEquals(DiaryResult.Rejected(DiaryRejection.NOT_FOUND), fixture.repository.saveBody(999, "x"))
    }
}

private class Fixture {
    val time = FakeTimeProvider()
    val dao = FakeDiaryDao()
    val repository = DiaryRepository(dao, time)
}

private class FakeTimeProvider(
    var now: Long = 1_000L,
    var date: LocalDate = LocalDate.of(2026, 8, 21),
) : TimeProvider {
    override fun nowMillis(): Long = now
    override fun currentLocalDate(): LocalDate = date
    override fun currentZoneId(): ZoneId = ZoneId.of("Asia/Tokyo")
}

/** The table without the unique date index: any number of rows per day, kept in insertion order. */
private class FakeDiaryDao : DiaryDao {
    val values = MutableStateFlow<List<DiaryEntryEntity>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<DiaryEntryEntity>> = values

    override fun observeBetween(startEpochDay: Long, endEpochDay: Long): Flow<List<DiaryEntryEntity>> =
        values.map { entries ->
            entries.filter { it.diaryDateEpochDay in startEpochDay..endEpochDay }
                .sortedByDescending(DiaryEntryEntity::diaryDateEpochDay)
        }

    override fun observeForDate(epochDay: Long): Flow<List<DiaryEntryEntity>> =
        values.map { entries -> entriesOf(entries, epochDay) }

    override suspend fun entriesForDate(epochDay: Long): List<DiaryEntryEntity> = entriesOf(values.value, epochDay)

    private fun entriesOf(entries: List<DiaryEntryEntity>, epochDay: Long) =
        entries.filter { it.diaryDateEpochDay == epochDay }.sortedWith(compareBy({ it.createdAt }, { it.id }))

    override suspend fun findByDate(epochDay: Long): DiaryEntryEntity? =
        entriesOf(values.value, epochDay).firstOrNull()

    override suspend fun findById(id: Long): DiaryEntryEntity? = values.value.firstOrNull { it.id == id }

    override suspend fun insert(entry: DiaryEntryEntity): Long {
        val id = nextId++
        values.value = values.value + entry.copy(id = id)
        return id
    }

    override suspend fun insertIgnoringConflict(entry: DiaryEntryEntity): Long = insert(entry)

    override suspend fun update(entry: DiaryEntryEntity): Int {
        if (values.value.none { it.id == entry.id }) return 0
        values.value = values.value.map { if (it.id == entry.id) entry else it }
        return 1
    }

    override suspend fun deleteDraft(id: Long): Int {
        val before = values.value.size
        values.value = values.value.filterNot { it.id == id && it.state == DiaryState.DRAFT }
        return before - values.value.size
    }

    fun seed(state: DiaryState, epochDay: Long): DiaryEntryEntity {
        val entry = DiaryEntryEntity(
            id = nextId++,
            diaryDateEpochDay = epochDay,
            body = "本文 ${state.name}",
            state = state,
            createdAt = 100,
            updatedAt = 100,
            lockedAt = if (state == DiaryState.LOCKED) 100 else null,
        )
        values.value = values.value + entry
        return entry
    }
}

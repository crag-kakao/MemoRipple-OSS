package io.github.cragcoffee.memoripple

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.cragcoffee.memoripple.data.AppDatabase
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiaryCalendarDaoInstrumentationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun monthRangeQueryIsInclusiveAndUsesEpochDayOrdering() = runBlocking {
        val dates = listOf(
            LocalDate.of(2026, 7, 31),
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 20),
            LocalDate.of(2026, 8, 31),
            LocalDate.of(2026, 9, 1),
        )
        dates.forEach { date ->
            database.diaryDao().insertIgnoringConflict(
                DiaryEntryEntity(
                    diaryDateEpochDay = date.toEpochDay(),
                    body = date.toString(),
                    state = DiaryState.LOCKED,
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
        }

        val result = database.diaryDao().observeBetween(
            startEpochDay = LocalDate.of(2026, 8, 1).toEpochDay(),
            endEpochDay = LocalDate.of(2026, 8, 31).toEpochDay(),
        ).first()

        assertEquals(
            listOf(
                LocalDate.of(2026, 8, 31),
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 1),
            ),
            result.map { LocalDate.ofEpochDay(it.diaryDateEpochDay) },
        )
    }
}

class DiaryDayDaoInstrumentationTest {
    private val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
    private val database = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    @org.junit.After
    fun tearDown() {
        database.close()
    }

    /** Two entries on one day are ordinary rows, listed in the order they were begun. */
    @org.junit.Test
    fun aDayHoldsSeveralEntriesInCreationOrder() = runBlocking {
        val day = LocalDate.of(2026, 9, 17).toEpochDay()
        val evening = database.diaryDao().insert(DiaryEntryEntity(diaryDateEpochDay = day, body = "夜", state = DiaryState.DRAFT, createdAt = 2_000, updatedAt = 2_000))
        val morning = database.diaryDao().insert(DiaryEntryEntity(diaryDateEpochDay = day, body = "朝", state = DiaryState.DRAFT, createdAt = 1_000, updatedAt = 1_000))
        assertEquals(listOf(morning, evening), database.diaryDao().observeForDate(day).first().map { it.id })
        assertEquals(listOf("朝", "夜"), database.diaryDao().entriesForDate(day).map { it.body })
        assertEquals(morning, database.diaryDao().findByDate(day)?.id)
    }
}

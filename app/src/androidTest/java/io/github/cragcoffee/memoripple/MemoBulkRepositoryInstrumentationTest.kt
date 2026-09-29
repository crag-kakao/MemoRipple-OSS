package io.github.cragcoffee.memoripple

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.cragcoffee.memoripple.data.AppDatabase
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoRepository
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoBulkRepositoryInstrumentationTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: MemoRepository
    private val clock = BulkClock()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = MemoRepository(database.memoDao(), clock)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun bulkOrganizationAndLifecycleAreActiveOnlyAtomicStatements() = runBlocking {
        val first = database.memoDao().insert(memo("first", 10))
        val second = database.memoDao().insert(memo("second", 20, favorite = true))
        val archived = database.memoDao().insert(memo("archived", 30, archivedAt = 50))

        assertEquals(2, repository.setFavoriteForMemos(setOf(first, second, archived), true).count)
        assertEquals(2, repository.setPinnedForMemos(setOf(first, second, archived), true).count)
        assertFalse(requireNotNull(database.memoDao().findById(archived)).isFavorite)
        assertEquals(10L, database.memoDao().findById(first)?.updatedAt)

        val archivedChange = repository.archiveMemos(setOf(first, second, archived))
        assertEquals(setOf(first, second), archivedChange.memoIds)
        assertEquals(2, repository.undoArchiveMemos(archivedChange))
        assertNull(database.memoDao().findById(first)?.archivedAt)

        val trashChange = repository.moveMemosToTrash(setOf(first, second, archived))
        assertEquals(setOf(first, second), trashChange.memoIds)
        assertTrue(database.memoDao().findById(first)?.trashedAt != null)
        assertEquals(2, repository.undoMoveMemosToTrash(trashChange))
        assertNull(database.memoDao().findById(second)?.trashedAt)
        assertEquals(20L, database.memoDao().findById(second)?.updatedAt)
    }

    @Test
    fun staleUndoIsGuardedByTheOperationTimestampAndState() = runBlocking {
        val id = database.memoDao().insert(memo("memo", 10))
        val change = repository.archiveMemos(setOf(id))
        database.memoDao().moveToTrash(id, 2_000)

        assertEquals(0, repository.undoArchiveMemos(change))
        assertEquals(2_000L, database.memoDao().findById(id)?.trashedAt)
    }

    private fun memo(
        title: String,
        updatedAt: Long,
        favorite: Boolean = false,
        archivedAt: Long? = null,
    ) = MemoEntity(
        title = title,
        body = "",
        createdAt = updatedAt,
        updatedAt = updatedAt,
        isFavorite = favorite,
        archivedAt = archivedAt,
    )
}

private class BulkClock : TimeProvider {
    override fun nowMillis(): Long = 1_000
    override fun currentLocalDate(): LocalDate = LocalDate.of(2026, 1, 1)
    override fun currentZoneId(): ZoneId = ZoneId.of("UTC")
}

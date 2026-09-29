package io.github.cragcoffee.memoripple.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DiaryDao {
    @Query("SELECT * FROM diary_entries ORDER BY diaryDateEpochDay DESC, createdAt DESC, id DESC")
    fun observeAll(): Flow<List<DiaryEntryEntity>>

    @Query(
        """
        SELECT * FROM diary_entries
        WHERE diaryDateEpochDay BETWEEN :startEpochDay AND :endEpochDay
        ORDER BY diaryDateEpochDay DESC, createdAt DESC, id DESC
        """,
    )
    fun observeBetween(
        startEpochDay: Long,
        endEpochDay: Long,
    ): Flow<List<DiaryEntryEntity>>

    /** The entries of one day in the order they were begun. */
    @Query(
        "SELECT * FROM diary_entries WHERE diaryDateEpochDay = :epochDay ORDER BY createdAt ASC, id ASC",
    )
    fun observeForDate(epochDay: Long): Flow<List<DiaryEntryEntity>>

    @Query(
        "SELECT * FROM diary_entries WHERE diaryDateEpochDay = :epochDay ORDER BY createdAt ASC, id ASC",
    )
    suspend fun entriesForDate(epochDay: Long): List<DiaryEntryEntity>

    /** The first entry of a day — a convenience for tests and the compatibility route; not an identity. */
    @Query(
        "SELECT * FROM diary_entries WHERE diaryDateEpochDay = :epochDay ORDER BY createdAt ASC, id ASC LIMIT 1",
    )
    suspend fun findByDate(epochDay: Long): DiaryEntryEntity?

    @Query("SELECT * FROM diary_entries WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): DiaryEntryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: DiaryEntryEntity): Long

    /** Kept for callers written when a day could hold one entry; since Room 23 it simply inserts. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringConflict(entry: DiaryEntryEntity): Long

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(entry: DiaryEntryEntity): Int

    @Query("DELETE FROM diary_entries WHERE id = :id AND state = 'DRAFT'")
    suspend fun deleteDraft(id: Long): Int
}

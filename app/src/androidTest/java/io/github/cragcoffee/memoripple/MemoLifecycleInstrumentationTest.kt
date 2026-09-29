package io.github.cragcoffee.memoripple

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.cragcoffee.memoripple.data.AppDatabase
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoCommentEntity
import io.github.cragcoffee.memoripple.data.MemoTagCrossRef
import io.github.cragcoffee.memoripple.data.NoteEntity
import io.github.cragcoffee.memoripple.data.TagEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoLifecycleInstrumentationTest {
    private lateinit var database: AppDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).build()
    }

    @After fun tearDown() = database.close()

    @Test
    fun activeArchiveAndTrashQueriesAreDisjointAndRestoreKeepsOrigin() = runBlocking {
        val activeId = database.memoDao().insert(MemoEntity(title = "active", body = "", createdAt = 1, updatedAt = 1))
        val archivedId = database.memoDao().insert(MemoEntity(title = "archived", body = "", createdAt = 2, updatedAt = 2))
        val trashedId = database.memoDao().insert(MemoEntity(title = "trashed", body = "", createdAt = 3, updatedAt = 3))
        database.memoDao().archive(archivedId, 20)
        database.memoDao().archive(trashedId, 30)
        database.memoDao().moveToTrash(trashedId, 40)

        assertEquals(listOf(activeId), database.memoDao().observeMemos("").first().map { it.id })
        assertEquals(listOf(archivedId), database.memoDao().observeArchivedMemos("").first().map { it.id })
        assertEquals(listOf(trashedId), database.memoDao().observeTrashedMemos("").first().map { it.id })
        database.memoDao().restoreFromTrash(trashedId)
        assertEquals(30L, database.memoDao().findById(trashedId)?.archivedAt)
        assertNull(database.memoDao().findById(trashedId)?.trashedAt)
    }

    @Test
    fun anEpisodeIsReadInItsNoteAndNotAlsoOnTheWall() = runBlocking {
        val loose = database.memoDao()
            .insert(MemoEntity(title = "loose", body = "", createdAt = 1, updatedAt = 1))
        val episode = database.memoDao()
            .insert(MemoEntity(title = "episode", body = "", createdAt = 2, updatedAt = 2))
        val noteId = database.noteDao().insert(
            NoteEntity(title = "note", coverColor = "plum", createdAt = 1, updatedAt = 1),
        )
        database.noteDao().placeEpisode(episode, noteId, null, 0)

        assertEquals(
            listOf(loose),
            database.memoDao().observeStandaloneMemos("").first().map { it.id },
        )
        assertEquals(
            listOf(episode),
            database.noteDao().observeEpisodes(noteId).first().map { it.id },
        )

        // Released from the note, it is an ordinary memo again — nothing about it was consumed.
        database.noteDao().placeEpisode(episode, null, null, 0)
        assertEquals(
            setOf(loose, episode),
            database.memoDao().observeStandaloneMemos("").first().mapTo(hashSetOf()) { it.id },
        )
    }

    @Test
    fun permanentDeleteCascadesRelationsButKeepsTag() = runBlocking {
        val memoId = database.memoDao().insert(MemoEntity(title = "memo", body = "", createdAt = 1, updatedAt = 1))
        val tagId = database.tagDao().insert(TagEntity(name = "tag", normalizedName = "tag", createdAt = 1))
        database.tagDao().attach(MemoTagCrossRef(memoId, tagId))
        database.memoCommentDao().insertAtEnd(
            MemoCommentEntity(memoId = memoId, text = "comment", createdAt = 1),
        )
        assertEquals(0, database.memoDao().deletePermanently(memoId))
        database.memoDao().moveToTrash(memoId, 2)
        assertEquals(1, database.memoDao().deletePermanently(memoId))
        assertNull(database.memoDao().findById(memoId))
        assertNotNull(database.tagDao().findById(tagId))
        assertEquals(emptyList<MemoTagCrossRef>(), database.tagDao().observeAllRelations().first())
        assertEquals(emptyList<MemoCommentEntity>(), database.memoCommentDao().observeForMemo(memoId).first())
    }
}

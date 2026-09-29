package io.github.cragcoffee.memoripple

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.cragcoffee.memoripple.data.AppDatabase
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.TagEntity
import io.github.cragcoffee.memoripple.data.TagMutationResult
import io.github.cragcoffee.memoripple.data.TagRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TagRepositoryInstrumentationTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: TagRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = TagRepository(database.tagDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun createRenameAndDuplicateUseNormalizedIdentity() = runBlocking {
        val created = repository.create("  Ｃｏｆｆｅｅ  ", now = 100) as TagMutationResult.Success
        val tag = requireNotNull(created.tag)
        assertEquals("Ｃｏｆｆｅｅ", tag.name)
        assertEquals("coffee", tag.normalizedName)
        assertEquals(TagMutationResult.Duplicate, repository.create("coffee", now = 200))
        val sameIdentityRename = repository.rename(tag.id, " Coffee ") as TagMutationResult.Success
        assertEquals("Coffee", sameIdentityRename.tag?.name)
        assertEquals(100L, sameIdentityRename.tag?.createdAt)

        val second = (repository.create("仕事", now = 300) as TagMutationResult.Success).tag!!
        assertEquals(TagMutationResult.Duplicate, repository.rename(second.id, "COFFEE"))
        val renamed = repository.rename(second.id, " 業務 ") as TagMutationResult.Success
        assertEquals("業務", renamed.tag?.name)
        assertEquals(300L, renamed.tag?.createdAt)
    }

    @Test
    fun attachDetachAndTagChangesDoNotMutateMemoTimestamps() = runBlocking {
        val memoId = database.memoDao().insert(MemoEntity(title = "memo", body = "body", createdAt = 10, updatedAt = 20))
        val tag = (repository.create("仕事", 30) as TagMutationResult.Success).tag!!

        assertTrue(repository.attach(memoId, tag.id))
        assertFalse(repository.attach(memoId, tag.id))
        assertEquals(listOf(tag.id), repository.observeTagsForMemo(memoId).first().map(TagEntity::id))
        assertEquals(20L, database.memoDao().findById(memoId)?.updatedAt)

        assertTrue(repository.rename(tag.id, "業務") is TagMutationResult.Success)
        assertEquals(20L, database.memoDao().findById(memoId)?.updatedAt)

        assertTrue(repository.detach(memoId, tag.id))
        assertFalse(repository.detach(memoId, tag.id))
        assertTrue(repository.observeTagsForMemo(memoId).first().isEmpty())
        assertEquals(20L, database.memoDao().findById(memoId)?.updatedAt)

        repository.attach(memoId, tag.id)
        repository.delete(requireNotNull(database.tagDao().findById(tag.id)))
        assertEquals(20L, database.memoDao().findById(memoId)?.updatedAt)
        assertTrue(database.tagDao().observeAllRelations().first().isEmpty())
    }

    @Test
    fun cascadesDeleteOnlyRelationsAndKeepsTheOtherSide() = runBlocking {
        val firstMemoId = database.memoDao().insert(MemoEntity(title = "first", body = "", createdAt = 1, updatedAt = 1))
        val secondMemoId = database.memoDao().insert(MemoEntity(title = "second", body = "", createdAt = 2, updatedAt = 2))
        val firstTag = (repository.create("A", 3) as TagMutationResult.Success).tag!!
        val secondTag = (repository.create("B", 4) as TagMutationResult.Success).tag!!
        repository.attach(firstMemoId, firstTag.id)
        repository.attach(firstMemoId, secondTag.id)
        repository.attach(secondMemoId, firstTag.id)

        database.memoDao().moveToTrash(firstMemoId, 10)
        database.memoDao().deletePermanently(firstMemoId)

        assertEquals(listOf(secondMemoId to firstTag.id), database.tagDao().observeAllRelations().first().map { it.memoId to it.tagId })
        assertEquals(2, database.tagDao().observeAllTags().first().size)

        repository.delete(firstTag)

        assertTrue(database.tagDao().observeAllRelations().first().isEmpty())
        assertNotNull(database.memoDao().findById(secondMemoId))
        assertEquals(listOf(secondTag.id), database.tagDao().observeAllTags().first().map(TagEntity::id))
    }

    @Test
    fun memoContentAutosaveDoesNotOverwriteRelationsOrOrganizationMetadata() = runBlocking {
        val memoId = database.memoDao().insert(
            MemoEntity(
                title = "before",
                body = "old",
                createdAt = 10,
                updatedAt = 20,
                isFavorite = true,
                isPinned = true,
            ),
        )
        val tag = (repository.create("小説", 30) as TagMutationResult.Success).tag!!
        repository.attach(memoId, tag.id)

        database.memoDao().updateContent(memoId, "after", "new", 40)

        val memo = requireNotNull(database.memoDao().findById(memoId))
        assertEquals(10L, memo.createdAt)
        assertEquals(40L, memo.updatedAt)
        assertTrue(memo.isFavorite)
        assertTrue(memo.isPinned)
        assertEquals(listOf(tag.id), repository.observeTagsForMemo(memoId).first().map(TagEntity::id))
    }

    @Test
    fun bulkTagAddAndRemoveAreSetBasedActiveOnlyAndTimestampSafe() = runBlocking {
        val first = database.memoDao().insert(
            MemoEntity(title = "first", body = "", createdAt = 10, updatedAt = 20),
        )
        val second = database.memoDao().insert(
            MemoEntity(title = "second", body = "", createdAt = 30, updatedAt = 40),
        )
        val archived = database.memoDao().insert(
            MemoEntity(title = "archived", body = "", createdAt = 50, updatedAt = 60, archivedAt = 70),
        )
        val a = (repository.create("A", 100) as TagMutationResult.Success).tag!!
        val b = (repository.create("B", 101) as TagMutationResult.Success).tag!!

        assertEquals(
            setOf(first, second),
            repository.addTagsToMemos(setOf(first, second, archived), setOf(a.id, b.id)).memoIds,
        )
        repository.addTagsToMemos(setOf(first, second), setOf(a.id, b.id))
        assertEquals(4, repository.observeAllRelations().first().size)
        assertTrue(repository.observeTagsForMemo(archived).first().isEmpty())

        repository.removeTagsFromMemos(setOf(first, second, archived), setOf(a.id))
        assertEquals(
            setOf(first to b.id, second to b.id),
            repository.observeAllRelations().first().mapTo(hashSetOf()) { it.memoId to it.tagId },
        )
        assertEquals(20L, database.memoDao().findById(first)?.updatedAt)
        assertEquals(40L, database.memoDao().findById(second)?.updatedAt)
        assertEquals(2, repository.observeAllTags().first().size)
    }
}

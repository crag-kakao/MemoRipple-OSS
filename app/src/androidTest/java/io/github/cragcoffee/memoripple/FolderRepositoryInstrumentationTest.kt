package io.github.cragcoffee.memoripple

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.cragcoffee.memoripple.data.AppDatabase
import io.github.cragcoffee.memoripple.data.FolderRepository
import io.github.cragcoffee.memoripple.data.FolderResult
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The folder tree as the repository keeps it: every change is one transaction checked
 * against the tree rules, so the table never holds a cycle, a missing parent, or a document
 * pointing at a folder that is gone — and deleting a folder never deletes a document.
 */
@RunWith(AndroidJUnit4::class)
class FolderRepositoryInstrumentationTest {
    private lateinit var database: AppDatabase
    private lateinit var folders: FolderRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        folders = FolderRepository(database, database.folderDao(), database.memoDao(), FixedClock(1_000))
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun foldersAreMadeAtTheRootOrUnderAParentWithATrimmedNonBlankName() = runBlocking {
        val dev = folders.create("  開発 ", null) as FolderResult.Done
        val android = folders.create("Android", dev.id) as FolderResult.Done

        assertEquals("開発", folders.findById(dev.id)?.name)
        assertNull(folders.findById(dev.id)?.parentFolderId)
        assertEquals(dev.id, folders.findById(android.id)?.parentFolderId)
        assertEquals(1_000L, folders.findById(dev.id)?.createdAt)
        assertEquals(FolderResult.InvalidName, folders.create("   ", null))
        assertEquals(FolderResult.MissingTarget, folders.create("孤児", 999))
        // Two folders may share a name: they are told apart by id.
        assertTrue(folders.create("開発", null) is FolderResult.Done)
        assertEquals(3, folders.observeFolders().first().size)
    }

    @Test
    fun renameChangesTheNameAndNothingElse() = runBlocking {
        val dev = folders.create("開発", null) as FolderResult.Done
        val child = folders.create("Android", dev.id) as FolderResult.Done
        val memo = database.memoDao().insert(memo("仕様", "- 一"))
        folders.assignMemo(memo, dev.id)

        assertEquals(FolderResult.Done(dev.id), folders.rename(dev.id, " Projects "))

        assertEquals("Projects", folders.findById(dev.id)?.name)
        assertEquals(dev.id, folders.findById(child.id)?.parentFolderId)
        assertEquals(dev.id, database.memoDao().findById(memo)?.folderId)
        assertEquals("- 一", database.memoDao().findById(memo)?.body)
        assertEquals(FolderResult.InvalidName, folders.rename(dev.id, ""))
        assertEquals(FolderResult.NotFound, folders.rename(999, "x"))
    }

    @Test
    fun aFolderMovesToTheRootOrUnderAnotherBranchButNeverUnderItselfOrBelow() = runBlocking {
        val dev = folders.create("開発", null) as FolderResult.Done
        val android = folders.create("Android", dev.id) as FolderResult.Done
        val memoRipple = folders.create("MemoRipple", android.id) as FolderResult.Done
        val projects = folders.create("Projects", null) as FolderResult.Done

        assertEquals(FolderResult.Done(android.id), folders.move(android.id, projects.id))
        assertEquals(projects.id, folders.findById(android.id)?.parentFolderId)
        assertEquals(android.id, folders.findById(memoRipple.id)?.parentFolderId)

        assertEquals(FolderResult.Done(android.id), folders.move(android.id, null))
        assertNull(folders.findById(android.id)?.parentFolderId)

        assertEquals(FolderResult.WouldCycle, folders.move(android.id, android.id))
        assertEquals(FolderResult.WouldCycle, folders.move(android.id, memoRipple.id))
        assertEquals(FolderResult.MissingTarget, folders.move(android.id, 999))
        assertEquals(FolderResult.NotFound, folders.move(999, null))
        assertNull(folders.findById(android.id)?.parentFolderId)
    }

    @Test
    fun deletingAFolderHandsDocumentsAndChildrenToItsParentInEveryLifecycleState() = runBlocking {
        val a = folders.create("A", null) as FolderResult.Done
        val b = folders.create("B", a.id) as FolderResult.Done
        val c = folders.create("C", b.id) as FolderResult.Done
        val active = database.memoDao().insert(memo("memo1", "本文"))
        val outline = database.memoDao().insert(memo("plan", "- 一", MemoKind.OUTLINE))
        val archived = database.memoDao().insert(memo("archived", "本文", archivedAt = 5))
        val trashed = database.memoDao().insert(memo("trashed", "本文", trashedAt = 6))
        listOf(active, outline, archived, trashed).forEach { folders.assignMemo(it, b.id) }

        assertEquals(FolderResult.Done(b.id), folders.delete(b.id))

        assertNull(folders.findById(b.id))
        assertEquals(a.id, folders.findById(c.id)?.parentFolderId)
        listOf(active, outline, archived, trashed).forEach { id ->
            assertEquals("memo $id", a.id, database.memoDao().findById(id)?.folderId)
        }
        // Nothing was deleted or otherwise changed.
        assertEquals(4, database.backupDao().readMemos().size)
        assertEquals(5L, database.memoDao().findById(archived)?.archivedAt)
        assertEquals(6L, database.memoDao().findById(trashed)?.trashedAt)
        assertEquals(MemoKind.OUTLINE.storageId, database.memoDao().findById(outline)?.kind)
        assertEquals(FolderResult.NotFound, folders.delete(b.id))
    }

    @Test
    fun deletingARootFolderHandsEverythingToTheRoot() = runBlocking {
        val a = folders.create("A", null) as FolderResult.Done
        val child = folders.create("child", a.id) as FolderResult.Done
        val memo = database.memoDao().insert(memo("memo", "本文"))
        folders.assignMemo(memo, a.id)

        assertEquals(FolderResult.Done(a.id), folders.delete(a.id))

        assertNull(folders.findById(child.id)?.parentFolderId)
        assertNull(database.memoDao().findById(memo)?.folderId)
        assertEquals(1, folders.observeFolders().first().size)
    }

    @Test
    fun aMemoOrAnOutlineIsPutInAFolderOrBackAtTheRootOnlyIfTheFolderExists() = runBlocking {
        val dev = folders.create("開発", null) as FolderResult.Done
        val memo = database.memoDao().insert(memo("メモ", "本文"))
        val outline = database.memoDao().insert(memo("計画", "- 一", MemoKind.OUTLINE))

        assertEquals(FolderResult.Done(memo), folders.assignMemo(memo, dev.id))
        assertEquals(FolderResult.Done(outline), folders.assignMemo(outline, dev.id))
        assertEquals(dev.id, database.memoDao().findById(memo)?.folderId)
        assertEquals(dev.id, database.memoDao().findById(outline)?.folderId)

        assertEquals(FolderResult.Done(memo), folders.assignMemo(memo, null))
        assertNull(database.memoDao().findById(memo)?.folderId)

        assertEquals(FolderResult.MissingTarget, folders.assignMemo(outline, 999))
        assertEquals(dev.id, database.memoDao().findById(outline)?.folderId)
        assertEquals(FolderResult.NotFound, folders.assignMemo(999, dev.id))
        // The body is never touched by a move.
        assertEquals("- 一", database.memoDao().findById(outline)?.body)
    }

    private fun memo(
        title: String,
        body: String,
        kind: MemoKind = MemoKind.MEMO,
        archivedAt: Long? = null,
        trashedAt: Long? = null,
    ) = MemoEntity(
        title = title, body = body, createdAt = 1, updatedAt = 1,
        archivedAt = archivedAt, trashedAt = trashedAt, kind = kind.storageId,
    )

    private class FixedClock(private val now: Long) : TimeProvider {
        override fun nowMillis(): Long = now
        override fun currentLocalDate(): LocalDate = LocalDate.of(2026, 9, 14)
        override fun currentZoneId(): ZoneId = ZoneId.of("Asia/Tokyo")
    }
}

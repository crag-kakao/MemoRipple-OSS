package io.github.cragcoffee.memoripple

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.cragcoffee.memoripple.data.AppDatabase
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoRepository
import io.github.cragcoffee.memoripple.data.OutlineStore
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.domain.outline.OutlineEditing
import io.github.cragcoffee.memoripple.domain.outline.OutlineRows
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An editor holding an older outline never overwrites a newer one (docs/OUTLINE_STABLE_ROWS.md §9):
 * the outline's `updatedAt` is its version; every write moves it; the outliner's save names the
 * version it last read or wrote, and a version that moved is a conflict with nothing written — no
 * row, no body. Never the last writer winning, never a merge.
 */
@RunWith(AndroidJUnit4::class)
class OutlineConflictInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var database: AppDatabase
    private lateinit var store: OutlineStore
    private lateinit var memos: MemoRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        store = OutlineStore(database)
        memos = MemoRepository(database.memoDao(), outlines = store)
    }

    @After fun tearDown() { database.close() }

    private val body = "- 旅行計画\n  - 京都へ行く\n- 帰る"
    private fun outline(updatedAt: Long = 100) = runBlocking {
        database.memoDao().insert(MemoEntity(title = "旅行", body = body, createdAt = 1, updatedAt = updatedAt, kind = MemoKind.OUTLINE.storageId))
    }
    private fun row(id: Long) = runBlocking { database.memoDao().findById(id)!! }
    private fun rows(id: Long) = runBlocking { database.outlineRowDao().rows(id).map { OutlineRows.Row(it.rowId, it.text) } }

    @Test
    fun aStaleEditorsSaveIsRefusedAndTheOutsideWriteStandsWhole() = runBlocking {
        val id = outline()
        // The editor opens at version N.
        val opened = store.materialize(id)!!
        val versionN = row(id).updatedAt
        // Someone else appends (what DocumentAccess.append and the split pane do).
        memos.save(row(id), "旅行", "$body\n- AIの追記", now = 50)
        val afterOutside = row(id)
        assertTrue("the version moved (N+1 or later), even with a clock behind it", afterOutside.updatedAt > versionN)
        val rowsAfterOutside = rows(id)
        // The stale editor saves what it holds.
        val stale = OutlineEditing.updateText(opened, 3, "帰る（古い画面から）")
        val result = store.saveDocument(id, "旅行", stale, now = 200, expectedUpdatedAt = versionN)
        assertEquals(OutlineStore.Save.Conflict, result)
        assertEquals("the outside words remain", afterOutside.body, row(id).body)
        assertTrue(row(id).body.endsWith("- AIの追記"))
        assertEquals("the version is the outside write's", afterOutside.updatedAt, row(id).updatedAt)
        assertEquals("no row was touched — ids, order and text", rowsAfterOutside, rows(id))
        assertEquals("the projection is the latest body", row(id).body, OutlineRows.projection(rows(id)))
    }

    @Test
    fun aSaveWithNoOutsideWriteGoesThroughAndMovesTheVersion() = runBlocking {
        val id = outline()
        val opened = store.materialize(id)!!
        val versionN = row(id).updatedAt
        val edited = OutlineEditing.updateText(opened, 2, "奈良へ行く")
        val saved = store.saveDocument(id, "旅行", edited, now = 300, expectedUpdatedAt = versionN)
        assertTrue(saved is OutlineStore.Save.Saved)
        assertEquals((saved as OutlineStore.Save.Saved).updatedAt, row(id).updatedAt)
        assertEquals("- 旅行計画\n  - 奈良へ行く\n- 帰る", row(id).body)
        // The next save names the new version; the old one is refused.
        val again = OutlineEditing.updateText(edited, 3, "帰宅")
        assertEquals(OutlineStore.Save.Conflict, store.saveDocument(id, "旅行", again, now = 400, expectedUpdatedAt = versionN))
        assertTrue(store.saveDocument(id, "旅行", again, now = 400, expectedUpdatedAt = saved.updatedAt) is OutlineStore.Save.Saved)
    }

    @Test
    fun twoWritesInTheSameMillisecondAreTwoVersions() = runBlocking {
        val id = outline(updatedAt = 500)
        memos.save(row(id), "旅行", "$body\n- 一つ目", now = 500)
        val first = row(id).updatedAt
        memos.save(row(id), "旅行", "$body\n- 一つ目\n- 二つ目", now = 500)
        assertTrue(row(id).updatedAt > first)
        assertTrue(first > 500)
    }
}

package io.github.cragcoffee.memoripple

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.AppDatabase
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoRepository
import io.github.cragcoffee.memoripple.data.OutlineStore
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentQuery
import io.github.cragcoffee.memoripple.domain.documents.DocumentReadResult
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentWriteResult
import io.github.cragcoffee.memoripple.domain.memos.ExportableMemo
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.domain.memos.MemoMarkdownExport
import io.github.cragcoffee.memoripple.domain.outline.OutlineEditing
import io.github.cragcoffee.memoripple.domain.outline.OutlineRows
import io.github.cragcoffee.memoripple.domain.speech.SpeechContentComposer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An outline's rows through the real repositories and Room (docs/OUTLINE_STABLE_ROWS.md): the ids
 * outlive the app (a database closed and opened again), a save keeps them, a plain-body writer
 * (the AI's append) keeps every line it leaves alone, a deleted outline takes its rows, and every
 * reader of the body — search, the AI's read, speech, export — reads exactly what it read before.
 */
@RunWith(AndroidJUnit4::class)
class OutlineStoreInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val name = "outline-store-test.db"
    private lateinit var database: AppDatabase

    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()

    @Before fun setUp() { context.deleteDatabase(name); database = open() }
    @After fun tearDown() { database.close(); context.deleteDatabase(name) }

    private val body = "# 旅行計画\n- 京都へ行く\n  - [ ] 寺院を回る\n\n- 帰る"
    private fun outline(db: AppDatabase = database) = runBlocking {
        db.memoDao().insert(MemoEntity(title = "旅行", body = body, createdAt = 1, updatedAt = 1, kind = MemoKind.OUTLINE.storageId))
    }

    @Test
    fun idsOutliveTheAppAndASaveKeepsThem() = runBlocking {
        val id = outline()
        val first = OutlineStore(database).materialize(id)!!
        assertEquals(listOf(1, 2, 3, 4, 5), first.entries.map { it.id })
        // Reorder, indent and a new line, saved as the outliner saves.
        val moved = OutlineEditing.moveUp(OutlineEditing.indent(first, 5), 5)
        val split = OutlineEditing.split(moved, 2, caret = 2).document
        val version = database.memoDao().findById(id)!!.updatedAt
        assertTrue(OutlineStore(database).saveDocument(id, "旅行", split, 2, expectedUpdatedAt = version) is OutlineStore.Save.Saved)
        val expected = OutlineRows.rowsOf(split)

        // The app goes away and comes back.
        database.close()
        database = open()
        val again = OutlineStore(database).materialize(id)!!
        assertEquals(expected, OutlineRows.rowsOf(again))
        assertEquals(OutlineRows.projection(expected), database.memoDao().findById(id)!!.body)
        assertEquals("no duplicate id", again.entries.size, again.entries.map { it.id }.toSet().size)
    }

    @Test
    fun thePlainBodyWritersKeepTheLinesTheyLeaveAlone() = runBlocking {
        val store = OutlineStore(database)
        val memos = MemoRepository(database.memoDao(), outlines = store)
        val id = outline()
        val before = store.materialize(id)!!
        // What DocumentAccess.append does for an outline: one new root line at the end.
        val existing = database.memoDao().findById(id)!!
        memos.save(existing, existing.title, existing.body + "\n- AIの追記", 3)
        val after = store.materialize(id)!!
        assertEquals(before.entries.map { it.id } + 6, after.entries.map { it.id })
        // A task ticked on the reading page: the same line.
        val ticked = database.memoDao().findById(id)!!
        memos.save(ticked, ticked.title, ticked.body.replace("- [ ] 寺院", "- [x] 寺院"), 4)
        assertEquals(after.entries.map { it.id }, store.materialize(id)!!.entries.map { it.id })
    }

    @Test
    fun aNewOutlineIsOneEmptyLineAndADeletedOutlineTakesItsRows() = runBlocking {
        val store = OutlineStore(database)
        val memos = MemoRepository(database.memoDao(), outlines = store)
        val created = memos.createOutline(1)
        assertEquals(listOf(OutlineRows.Row(1, "")), OutlineRows.rowsOf(store.materialize(created.id)!!))
        val copy = memos.save(null, "写し", body, 2, kind = MemoKind.OUTLINE)!!
        assertEquals(body, OutlineRows.projection(OutlineRows.rowsOf(store.materialize(copy.id)!!)))
        database.memoDao().moveToTrash(copy.id, 5)
        assertEquals(1, database.memoDao().deletePermanently(copy.id))
        assertTrue("no orphan row", database.outlineRowDao().rows(copy.id).isEmpty())
        val memo = memos.save(null, "メモ", "- 行", 2)!!
        assertTrue("a memo has no rows", database.outlineRowDao().rows(memo.id).isEmpty())
    }

    @Test
    fun searchAiSpeechAndExportReadExactlyWhatTheyReadBefore() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MemoRippleApplication
        app.database.clearAllTables()
        // An outline as the upgrade finds it: a body and no rows yet.
        val id = outline(app.database)
        val ref = DocumentRef(DocumentKind.OUTLINE, id)
        fun readers(): List<Any?> = runBlocking {
            val row = app.database.memoDao().findById(id)!!
            listOf(
                app.database.memoDao().observeOutlineDocuments("寺院").first().map { it.id },
                (app.documentAccess.get(ref) as DocumentReadResult.Found).content.body,
                app.documentAccess.search(DocumentQuery(text = "寺院")).map { it.ref },
                SpeechContentComposer().memo(row.title, row.body, emptyList(), includeComments = false),
                MemoMarkdownExport.render(ExportableMemo(row.title, row.body)),
            )
        }
        val before = readers()
        app.outlineStore.materialize(id)
        assertEquals(before, readers())
        assertEquals(body, app.database.memoDao().findById(id)!!.body)
        // The AI's append still lands as one root line, through the rows.
        val updatedAt = app.database.memoDao().findById(id)!!.updatedAt
        assertTrue(app.documentAccess.append(ref, "追記", updatedAt) is DocumentWriteResult.Done)
        val appended = app.database.memoDao().findById(id)!!.body
        assertTrue(appended.startsWith("$body\n") && appended.endsWith("追記") && appended.count { it == '\n' } == body.count { it == '\n' } + 1)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), app.outlineStore.materialize(id)!!.entries.map { it.id })
        app.database.clearAllTables()
    }
}

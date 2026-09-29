package io.github.cragcoffee.memoripple

import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.cragcoffee.memoripple.data.AppDatabase
import io.github.cragcoffee.memoripple.data.AttachmentBlobEntity
import io.github.cragcoffee.memoripple.data.AttachmentBlobStore
import io.github.cragcoffee.memoripple.data.AttachmentKind
import io.github.cragcoffee.memoripple.data.AttachmentRepository
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoPhotoAttachmentEntity
import io.github.cragcoffee.memoripple.data.MemoRepository
import io.github.cragcoffee.memoripple.data.OutlineRowEntity
import io.github.cragcoffee.memoripple.data.OutlineStore
import io.github.cragcoffee.memoripple.domain.memos.ExportableMemo
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.domain.memos.MemoMarkdownExport
import io.github.cragcoffee.memoripple.domain.outline.OutlineDocument
import io.github.cragcoffee.memoripple.domain.outline.OutlineEditing
import io.github.cragcoffee.memoripple.domain.outline.OutlineNode
import io.github.cragcoffee.memoripple.domain.outline.OutlineRows
import io.github.cragcoffee.memoripple.domain.speech.SpeechContentComposer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Photo rows through the real store and Room (Room 29, docs/OUTLINE_PHOTO_ROWS.md): they outlive the
 * app with their ids, places, depths and photos; the body and everything that reads it (search,
 * speech, export) never see them; a photo row shows only one of the outline's own photos, once, and
 * goes with it; a plain-body writer keeps them; a photo no row shows after the process ended comes
 * back on top instead of being lost; only photos no row shows are let go, and a stale editor's save
 * writes no row at all.
 */
@RunWith(AndroidJUnit4::class)
class OutlinePhotoRowsStoreInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val name = "outline-photo-rows-test.db"
    private lateinit var database: AppDatabase
    private lateinit var root: File

    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
    private val store get() = OutlineStore(database)
    private val memos get() = MemoRepository(database.memoDao(), outlines = store)
    private val attachments get() = AttachmentRepository(
        database = database,
        dao = database.attachmentDao(),
        noteDao = database.noteDao(),
        contentResolver = context.contentResolver,
        blobStore = AttachmentBlobStore(root),
        nowMillis = { 1L },
    )

    @Before fun setUp() {
        context.deleteDatabase(name)
        database = open()
        root = File(context.cacheDir, "outline-photos-${System.nanoTime()}").apply { mkdirs() }
    }
    @After fun tearDown() {
        database.close()
        context.deleteDatabase(name)
        root.deleteRecursively()
    }

    private val body = "- 旅行計画\n  - 京都へ行く\n- 帰る"
    private suspend fun outline(): Long =
        database.memoDao().insert(MemoEntity(title = "旅行", body = body, createdAt = 1, updatedAt = 1, kind = MemoKind.OUTLINE.storageId))

    /** A photo of [memoId] with its file, as an import leaves it. */
    private suspend fun photo(memoId: Long, index: Int): Long {
        val temp = File(root, "p$index-${System.nanoTime()}.png")
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)[index % 4])
            temp.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        val sha = AttachmentBlobStore.hash(temp)
        return attachments.installForRestore(listOf(Triple(temp, sha, temp.length()))) {
            database.withTransaction {
                database.attachmentDao().insertBlob(AttachmentBlobEntity(sha, AttachmentKind.IMAGE, "image/png", temp.length(), 4, 4, 1))
                database.attachmentDao().insertMemoRelation(MemoPhotoAttachmentEntity(memoId = memoId, blobSha256 = sha, sortOrder = index, createdAt = 1))
            }
        }
    }

    private suspend fun rows(memoId: Long) = database.outlineRowDao().rows(memoId)
    private suspend fun version(memoId: Long) = database.memoDao().findById(memoId)!!.updatedAt
    private suspend fun fileOf(memoId: Long, attachmentId: Long) =
        database.attachmentDao().memoRelations(memoId).first { it.id == attachmentId }.let { AttachmentBlobStore(root).blobFile(it.blobSha256) }

    /**
     * Text → photo → text → photo (beside 京都へ行く) → a new line → text, placed on [doc] — the
     * outliner's document from before the import, as the session holds it — and saved.
     */
    private suspend fun withPhotos(memoId: Long, doc: OutlineDocument, a: Long, b: Long): Boolean {
        var placed = OutlineEditing.insertPhotos(doc, afterId = 1, attachmentIds = listOf(a), caret = 0).document
        placed = OutlineEditing.insertPhotos(placed, afterId = 2, attachmentIds = listOf(b), caret = null).document
        return store.saveDocument(memoId, "旅行", placed, 10, version(memoId)) is OutlineStore.Save.Saved
    }

    @Test
    fun photoRowsOutliveTheAppAndNeverEnterTheBody() = runBlocking {
        val id = outline()
        val doc = store.materialize(id)!!
        val (a, b) = photo(id, 0) to photo(id, 1)
        assertTrue(withPhotos(id, doc, a, b))
        val saved = rows(id)
        assertEquals(listOf("photo", "text", "text", "photo", "text", "text"), saved.map { it.kind })
        assertEquals(listOf(a, null, null, b, null, null), saved.map { it.photoAttachmentId })
        assertEquals("  ", saved[3].text)
        val written = "- 旅行計画\n  - 京都へ行く\n  - \n- 帰る"
        assertEquals(written, database.memoDao().findById(id)!!.body)

        database.close()
        database = open()
        assertEquals(saved, rows(id))
        val again = store.materialize(id)!!
        assertEquals(saved.map { it.rowId }, again.entries.map { it.id })
        assertEquals("no duplicate id", again.entries.size, again.entries.map { it.id }.toSet().size)
        val row = database.memoDao().findById(id)!!
        assertFalse(SpeechContentComposer().memo(row.title, row.body, emptyList(), includeComments = false).contains("photo"))
        assertEquals(MemoMarkdownExport.render(ExportableMemo(row.title, written)), MemoMarkdownExport.render(ExportableMemo(row.title, row.body)))
        assertEquals(listOf(id), database.memoDao().observeOutlineDocuments("京都").first().map { it.id })
    }

    @Test
    fun aPhotoRowShowsOnlyThisOutlinesPhotoOnceAndGoesWithIt() = runBlocking {
        val id = outline()
        val other = database.memoDao().insert(MemoEntity(title = "メモ", body = "本文", createdAt = 1, updatedAt = 1))
        store.materialize(id)
        val own = photo(id, 0)
        val theirs = photo(other, 1)
        val doc = store.materialize(id)!!
        val foreign = OutlineEditing.insertPhotos(doc, afterId = 3, attachmentIds = listOf(theirs), caret = null).document
        assertTrue(store.saveDocument(id, "旅行", foreign, 10, version(id)) is OutlineStore.Save.Saved)
        assertTrue("another memo's photo is never shown here", rows(id).none { it.photoAttachmentId == theirs })
        // A photo that goes (the relation deleted) takes its row: no orphan row.
        assertTrue(rows(id).any { it.photoAttachmentId == own })
        database.attachmentDao().deleteMemoRelationAndNormalize(id, own)
        assertTrue(rows(id).none { it.kind == OutlineRowEntity.KIND_PHOTO })
        assertEquals(body + "\n- ", database.memoDao().findById(id)!!.body)
    }

    @Test
    fun aPlainBodyWriterKeepsEveryPhotoRow() = runBlocking {
        val id = outline()
        val doc = store.materialize(id)!!
        val (a, b) = photo(id, 0) to photo(id, 1)
        assertTrue(withPhotos(id, doc, a, b))
        val before = rows(id)
        val existing = database.memoDao().findById(id)!!
        memos.save(existing, existing.title, existing.body + "\n- AIの追記", 20)
        val after = rows(id)
        assertEquals(before.map { it.rowId } + (before.maxOf { it.rowId } + 1), after.map { it.rowId })
        assertEquals(before.map { it.photoAttachmentId } + null, after.map { it.photoAttachmentId })
    }

    @Test
    fun aPhotoNoRowShowsAfterTheProcessEndedComesBackOnTop() = runBlocking {
        val id = outline()
        store.materialize(id)
        // Imported, then the process ended before the outliner saved its row.
        val lost = photo(id, 0)
        assertEquals(listOf(lost), store.unshownPhotos(id))
        val reopened = store.materialize(id)!!
        assertEquals(lost, reopened.entries.first().let { (it as OutlineNode).photoAttachmentId })
        assertEquals("written, so leaving never lets it go unseen", emptyList<Long>(), store.unshownPhotos(id))
        assertEquals(listOf(1, 2, 3), rows(id).filter { it.kind == "text" }.map { it.rowId })
        assertEquals(body, database.memoDao().findById(id)!!.body)
    }

    @Test
    fun onlyAPhotoNoRowShowsIsLetGoAndItsFileWithIt() = runBlocking {
        val id = outline()
        val doc = store.materialize(id)!!
        val (a, b) = photo(id, 0) to photo(id, 1)
        assertTrue(withPhotos(id, doc, a, b))
        val fileA = fileOf(id, a)
        val fileB = fileOf(id, b)
        // A photo row taken out and saved: the photo stays until the session ends.
        val stored = store.materialize(id)!!
        val removed = OutlineEditing.removePhoto(stored, stored.entries.first { (it as? OutlineNode)?.photoAttachmentId == b }.id)
        assertTrue(store.saveDocument(id, "旅行", removed, 30, version(id)) is OutlineStore.Save.Saved)
        assertEquals(listOf(b), store.unshownPhotos(id))
        assertTrue(fileB.exists())
        // The session ends with everything saved.
        attachments.releaseUnshownOutlinePhotos(id) { store.unshownPhotos(id) }
        assertEquals(listOf(a), database.attachmentDao().memoRelations(id).map { it.id })
        assertFalse(fileB.exists())
        assertTrue("a photo a row shows is never let go", fileA.exists())
        // Nothing left to let go: a second release changes nothing.
        attachments.releaseUnshownOutlinePhotos(id) { store.unshownPhotos(id) }
        assertTrue(fileA.exists())
        assertEquals(listOf(a), database.attachmentDao().memoRelations(id).map { it.id })
    }

    @Test
    fun aStaleEditorsSaveWithPhotoRowsWritesNothing() = runBlocking {
        val id = outline()
        val doc = store.materialize(id)!!
        val (a, b) = photo(id, 0) to photo(id, 1)
        assertTrue(withPhotos(id, doc, a, b))
        val opened = store.materialize(id)!!
        val versionN = version(id)
        val existing = database.memoDao().findById(id)!!
        memos.save(existing, existing.title, existing.body + "\n- 外から", 40)
        val latest = rows(id)
        val latestBody = database.memoDao().findById(id)!!.body
        val photoRow = opened.entries.first { (it as? OutlineNode)?.photoAttachmentId == b }.id
        val stale = OutlineEditing.moveUp(OutlineEditing.removePhoto(opened, photoRow), 3)
        assertEquals(OutlineStore.Save.Conflict, store.saveDocument(id, "旅行", stale, 50, versionN))
        assertEquals("no row, photo or line, was touched", latest, rows(id))
        assertEquals(latestBody, database.memoDao().findById(id)!!.body)
        assertEquals(setOf(a, b), database.attachmentDao().memoRelations(id).map { it.id }.toSet())
    }
}

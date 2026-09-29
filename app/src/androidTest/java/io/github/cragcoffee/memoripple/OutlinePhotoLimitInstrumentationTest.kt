package io.github.cragcoffee.memoripple

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.cragcoffee.memoripple.data.AppDatabase
import io.github.cragcoffee.memoripple.data.AttachmentBlobStore
import io.github.cragcoffee.memoripple.data.AttachmentLimits
import io.github.cragcoffee.memoripple.data.AttachmentRepository
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.OutlineStore
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.domain.outline.OutlineDocument
import io.github.cragcoffee.memoripple.domain.outline.OutlineEditing
import io.github.cragcoffee.memoripple.domain.outline.OutlineNode
import io.github.cragcoffee.memoripple.domain.outline.OutlinePhotos
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
 * The outline's 20-photo cap through the real import and Room (docs/OUTLINE_PHOTO_ROWS.md §7.3):
 * it counts the photo rows the outline shows — never a photo kept for 元に戻す — so 20 shown refuse
 * one more, one taken out makes room for one new while its record and file stay; the same picture
 * added again after it was taken out is that same photo (no second record), one still shown is
 * refused as already there; and leaving lets go only of what no row shows.
 */
@RunWith(AndroidJUnit4::class)
class OutlinePhotoLimitInstrumentationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AppDatabase
    private lateinit var root: File
    private lateinit var fixtures: PhotoFixtureSource
    private lateinit var store: OutlineStore
    private lateinit var photos: AttachmentRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        root = File(context.cacheDir, "outline-limit-${System.nanoTime()}").apply { mkdirs() }
        fixtures = PhotoFixtureSource()
        store = OutlineStore(database)
        photos = AttachmentRepository(
            database = database,
            dao = database.attachmentDao(),
            noteDao = database.noteDao(),
            contentResolver = context.contentResolver,
            blobStore = AttachmentBlobStore(root),
            nowMillis = { 1L },
            contentSource = fixtures,
        )
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    /** A picture of its own colour — a picture of its own sha. */
    private fun image(index: Int): Uri {
        val file = File(root, "p$index.png")
        Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.rgb(index * 9 % 256, index * 37 % 256, index * 71 % 256))
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        return fixtures.register("p$index", file, "image/png")
    }

    private suspend fun relations(memoId: Long) = database.attachmentDao().memoRelations(memoId).map { it.id }
    private suspend fun fileOf(memoId: Long, id: Long) =
        database.attachmentDao().memoRelations(memoId).first { it.id == id }.let { AttachmentBlobStore(root).blobFile(it.blobSha256) }
    private fun shown(doc: OutlineDocument) = OutlinePhotos.shown(doc)

    /** What the outliner does with an import: count what it shows, place what the import gives. */
    private suspend fun add(memoId: Long, doc: OutlineDocument, vararg uris: Uri): Pair<OutlineDocument, io.github.cragcoffee.memoripple.data.PhotoImportResult> {
        val result = photos.importMemoPhotos(memoId, uris.toList(), activeCount = OutlinePhotos.activeCount(doc, emptyList()))
        val placed = OutlinePhotos.toPlace(result.addedIds, result.duplicateIds, doc, emptyList())
        val last = doc.entries.last { it is OutlineNode && !it.isPhoto }.id
        return OutlineEditing.insertPhotos(doc, last, placed, caret = null).document to result
    }

    private suspend fun save(memoId: Long, doc: OutlineDocument) {
        val version = database.memoDao().findById(memoId)!!.updatedAt
        assertTrue(store.saveDocument(memoId, "写真", doc, version + 1, version) is OutlineStore.Save.Saved)
    }

    @Test
    fun theCapCountsWhatIsShownAndNeverWhatIsHeldForUndo() = runBlocking {
        val id = database.memoDao().insert(MemoEntity(title = "写真", body = "- 写真の記録", createdAt = 1, updatedAt = 1, kind = MemoKind.OUTLINE.storageId))
        var doc = store.materialize(id)!!
        val (full, first) = add(id, doc, *(1..20).map(::image).toTypedArray())
        doc = full
        save(id, doc)
        assertEquals(20, first.added)
        assertEquals(20, shown(doc).size)

        // 1. 20 shown: one more is refused, nothing is stored.
        val (same, refused) = add(id, doc, image(21))
        assertTrue(refused.limitReached)
        assertEquals(0, refused.added)
        assertEquals(doc, same)
        assertEquals(20, relations(id).size)

        // 2–3. One taken out: 19 shown, its record and file kept for 元に戻す.
        val takenOutRow = doc.entries.first { (it as? OutlineNode)?.isPhoto == true } as OutlineNode
        val takenOut = takenOutRow.photoAttachmentId!!
        doc = OutlineEditing.removePhoto(doc, takenOutRow.id)
        save(id, doc)
        assertEquals(19, shown(doc).size)
        assertTrue(takenOut in relations(id))
        assertTrue(fileOf(id, takenOut).exists())

        // 9. The same picture again: that same photo comes back, no second record.
        val (back, again) = add(id, doc, image(1))
        assertEquals(1, again.duplicates)
        assertEquals(listOf(takenOut), again.duplicateIds)
        assertTrue(takenOut in shown(back))
        assertEquals(20, relations(id).size)
        // 10. A picture still shown is refused as already there, as before.
        // (The first import placed its pictures in order: picture n is first.addedIds[n - 1].)
        assertEquals(first.addedIds[0], takenOut)
        val (unchanged, dup) = add(id, doc, image(2))
        assertEquals(1, dup.duplicates)
        assertEquals(listOf(first.addedIds[1]), dup.duplicateIds)
        assertEquals(doc, unchanged)
        assertEquals(20, relations(id).size)

        // 4–5. With 19 shown and one held, a new picture is added and makes 20 shown.
        val (twenty, added) = add(id, doc, image(22))
        assertEquals(1, added.added)
        doc = twenty
        save(id, doc)
        assertEquals(20, shown(doc).size)
        assertEquals("20 shown + the one held", 21, relations(id).size)
        assertTrue(fileOf(id, takenOut).exists())
        // …and the cap is reached again.
        assertTrue(add(id, doc, image(23)).second.limitReached)

        // 11. Leaving (saved, no conflict): only the held photo is let go, its file with it.
        val heldFile = fileOf(id, takenOut)
        photos.releaseUnshownOutlinePhotos(id) { store.unshownPhotos(id) }
        assertFalse(takenOut in relations(id))
        assertFalse(heldFile.exists())
        assertEquals(shown(doc), relations(id).toSet())
        assertTrue(shown(doc).all { fileOf(id, it).exists() })
        assertEquals(AttachmentLimits.MAX_PHOTOS_PER_RECORD, relations(id).size)
    }
}

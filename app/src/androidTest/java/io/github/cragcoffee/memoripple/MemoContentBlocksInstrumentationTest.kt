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
import io.github.cragcoffee.memoripple.data.AttachmentRepository
import io.github.cragcoffee.memoripple.data.MemoContentStore
import io.github.cragcoffee.memoripple.data.MemoRepository
import io.github.cragcoffee.memoripple.domain.memos.MemoBlock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A memo's blocks through the real repositories and Room (docs/MEMO_CONTENT_BLOCKS.md): the body is
 * always the projection; a photo goes after the text being written and writing continues below;
 * deleting a photo keeps the text around it; a reorder swaps pictures, not places; a plain-body
 * writer (AI append) lands at the end; an import keeps photos above; an outline has no blocks.
 */
@RunWith(AndroidJUnit4::class)
class MemoContentBlocksInstrumentationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AppDatabase
    private lateinit var root: File
    private lateinit var fixtures: PhotoFixtureSource
    private lateinit var content: MemoContentStore
    private lateinit var memos: MemoRepository
    private lateinit var photos: AttachmentRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        root = File(context.cacheDir, "blocks-${System.nanoTime()}").apply { mkdirs() }
        fixtures = PhotoFixtureSource()
        content = MemoContentStore(database)
        memos = MemoRepository(database.memoDao(), content = content)
        photos = AttachmentRepository(
            database = database,
            dao = database.attachmentDao(),
            noteDao = database.noteDao(),
            contentResolver = context.contentResolver,
            blobStore = AttachmentBlobStore(root),
            nowMillis = { 1L },
            contentSource = fixtures,
            content = content,
        )
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    private fun image(name: String, color: Int): Uri {
        val file = File(root, "$name.png")
        Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(color)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        return fixtures.register(name, file, "image/png")
    }

    private suspend fun blocks(memoId: Long) = content.materialize(memoId)
    private fun shape(blocks: List<MemoBlock>) = blocks.joinToString(" ") { if (it is MemoBlock.Text) "T(${it.text})" else "P" }
    private suspend fun body(memoId: Long) = database.memoDao().findById(memoId)!!.body

    @Test
    fun textPhotoTextPhotoIsOneNoteAndTheBodyIsItsText() = runBlocking {
        val memo = memos.save(null, "公園", "今日は公園へ行った", 10)!!
        assertEquals("T(今日は公園へ行った)", shape(blocks(memo.id)))

        val first = photos.importMemoPhotos(memo.id, listOf(image("a", Color.RED)), writingTextId = blocks(memo.id).single().id)
        assertEquals("T(今日は公園へ行った) P T()", shape(blocks(memo.id)))
        assertEquals("writing continues below the photo", blocks(memo.id)[2].id, first.writingTextId)

        memos.saveBlocks(memo, "公園", mapOf(first.writingTextId!! to "桜がきれいだった"), 20)
        val second = photos.importMemoPhotos(memo.id, listOf(image("b", Color.BLUE)), writingTextId = first.writingTextId)
        memos.saveBlocks(memo, "公園", mapOf(second.writingTextId!! to "また行きたい"), 30)

        assertEquals("T(今日は公園へ行った) P T(桜がきれいだった) P T(また行きたい)", shape(blocks(memo.id)))
        assertEquals("今日は公園へ行った\n桜がきれいだった\nまた行きたい", body(memo.id))
        val photoOrder = blocks(memo.id).filterIsInstance<MemoBlock.Photo>().map { it.attachmentId }
        assertEquals("the photos' sortOrder follows their blocks", photoOrder, database.attachmentDao().memoRelations(memo.id).map { it.id })
    }

    @Test
    fun deletingAPhotoKeepsTheTextAroundItAndTheLastOneLeavesOneText() = runBlocking {
        val memo = memos.save(null, "t", "上", 10)!!
        val added = photos.importMemoPhotos(memo.id, listOf(image("c", Color.GREEN)), writingTextId = blocks(memo.id).single().id)
        memos.saveBlocks(memo, "t", mapOf(added.writingTextId!! to "下"), 20)
        val photo = blocks(memo.id).filterIsInstance<MemoBlock.Photo>().single()
        photos.deleteMemoPhoto(memo.id, photo.attachmentId)
        assertEquals("the texts stay; with no photo they are one text", "T(上\n下)", shape(blocks(memo.id)))
        assertEquals("上\n下", body(memo.id))
    }

    @Test
    fun aReorderSwapsThePicturesAndKeepsTheirPlaces() = runBlocking {
        val memo = memos.save(null, "t", "一", 10)!!
        val first = photos.importMemoPhotos(memo.id, listOf(image("d", Color.RED)), writingTextId = blocks(memo.id).single().id)
        memos.saveBlocks(memo, "t", mapOf(first.writingTextId!! to "二"), 20)
        photos.importMemoPhotos(memo.id, listOf(image("e", Color.BLUE)), writingTextId = first.writingTextId)
        val before = blocks(memo.id)
        val (p1, p2) = before.filterIsInstance<MemoBlock.Photo>().map { it.attachmentId }
        photos.reorderMemoPhotos(memo.id, listOf(p2, p1))
        val after = blocks(memo.id)
        assertEquals(shape(before), shape(after))
        assertEquals(listOf(p2, p1), after.filterIsInstance<MemoBlock.Photo>().map { it.attachmentId })
        assertEquals(listOf(p2, p1), database.attachmentDao().memoRelations(memo.id).map { it.id })
    }

    @Test
    fun aPlainBodyWriterLandsAtTheEndAndKeepsEveryPhoto() = runBlocking {
        val memo = memos.save(null, "t", "abc", 10)!!
        photos.importMemoPhotos(memo.id, listOf(image("f", Color.RED)), writingTextId = blocks(memo.id).single().id)
        // What DocumentAccess.append does: the whole body with a line added.
        memos.save(database.memoDao().findById(memo.id), "t", "abc\n追記", 30)
        assertEquals("T(abc) P T(追記)", shape(blocks(memo.id)))
        assertEquals("abc\n追記", body(memo.id))
    }

    @Test
    fun searchFindsTheWordsOfEveryTextBlock() = runBlocking {
        val memo = memos.save(null, "公園", "今日は公園へ行った", 10)!!
        val added = photos.importMemoPhotos(memo.id, listOf(image("s", Color.RED)), writingTextId = blocks(memo.id).single().id)
        memos.saveBlocks(memo, "公園", mapOf(added.writingTextId!! to "桜がきれいだった"), 20)
        val found = database.memoDao().observeMemos("桜").first()
        assertEquals(listOf(memo.id), found.map { it.id })
    }

    @Test
    fun anImportPutsPhotosAboveAsTheyAlwaysSat() = runBlocking {
        val memo = memos.save(null, "t", "本文", 10)!!
        photos.importMemoPhotos(memo.id, listOf(image("g", Color.RED), image("h", Color.BLUE)))
        assertEquals("P P T(本文)", shape(blocks(memo.id)))
        assertEquals("本文", body(memo.id))
    }

    @Test
    fun photosPickedWithTheCaretBetweenTheWordsGoBetweenThem() = runBlocking {
        // S26, 2026-09-24: the caret stood between the words and the photo went after all of them.
        val memo = memos.save(null, "t", "一行目\n二行目", 10)!!
        val text = blocks(memo.id).single().id
        val added = photos.importMemoPhotos(memo.id, listOf(image("i", Color.RED), image("k", Color.BLUE)), writingTextId = text, caret = 4)
        assertEquals("T(一行目) P P T(二行目)", shape(blocks(memo.id)))
        assertEquals("the first text keeps its id", text, blocks(memo.id).first().id)
        assertEquals("writing goes on at the words after the caret", blocks(memo.id).last().id, added.writingTextId)
        assertEquals("the body did not move", "一行目\n二行目", body(memo.id))
        val photoOrder = blocks(memo.id).filterIsInstance<MemoBlock.Photo>().map { it.attachmentId }
        assertEquals("picked order, and sortOrder follows", photoOrder, database.attachmentDao().memoRelations(memo.id).map { it.id })
    }

    @Test
    fun anOutlineHasNoBlocksAndADeletedMemoTakesItsBlocks() = runBlocking {
        val outline = memos.createOutline(10)
        photos.importMemoPhotos(outline.id, listOf(image("j", Color.RED)))
        assertTrue(database.memoContentBlockDao().blocks(outline.id).isEmpty())
        memos.save(outline, "o", "- a", 20)
        assertEquals("- a", body(outline.id))

        val memo = memos.save(null, "t", "消える", 10)!!
        assertTrue(database.memoContentBlockDao().blocks(memo.id).isNotEmpty())
        database.memoDao().moveToTrash(memo.id, 50)
        assertEquals(1, database.memoDao().deletePermanently(memo.id))
        assertTrue(database.memoContentBlockDao().blocks(memo.id).isEmpty())
    }
}

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
import io.github.cragcoffee.memoripple.data.CreateFutureCommentResult
import io.github.cragcoffee.memoripple.data.DiaryContentStore
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.DiaryRepository
import io.github.cragcoffee.memoripple.data.DiaryResult
import io.github.cragcoffee.memoripple.data.FutureDiaryCommentRepository
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import io.github.cragcoffee.memoripple.domain.memos.MemoBlock
import java.io.File
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
 * A journal entry's blocks through the real repositories and Room (docs/MEMO_CONTENT_BLOCKS.md
 * §11): the body is always the projection; a photo goes where the caret is and writing goes on
 * below it; deleting a photo keeps the words around it; a LOCKED entry takes no write; a blank
 * DRAFT with no photo is still released on leaving; the AI's append lands at the end; the future
 * comments of an entry are untouched by its blocks.
 */
@RunWith(AndroidJUnit4::class)
class DiaryContentBlocksInstrumentationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AppDatabase
    private lateinit var root: File
    private lateinit var fixtures: PhotoFixtureSource
    private lateinit var content: DiaryContentStore
    private lateinit var diary: DiaryRepository
    private lateinit var photos: AttachmentRepository
    private var now = 1_000L
    private val zone = ZoneId.of("Asia/Tokyo")
    private val today = LocalDate.of(2026, 9, 25)
    private val clock = object : TimeProvider {
        override fun nowMillis(): Long = now
        override fun currentLocalDate(): LocalDate = today
        override fun currentZoneId(): ZoneId = zone
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        root = File(context.cacheDir, "diary-blocks-${System.nanoTime()}").apply { mkdirs() }
        fixtures = PhotoFixtureSource()
        content = DiaryContentStore(database, clock::nowMillis)
        diary = DiaryRepository(database.diaryDao(), clock, database.attachmentDao(), content)
        photos = AttachmentRepository(
            database = database,
            dao = database.attachmentDao(),
            noteDao = database.noteDao(),
            contentResolver = context.contentResolver,
            blobStore = AttachmentBlobStore(root),
            nowMillis = { now },
            contentSource = fixtures,
            diaryContent = content,
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

    private suspend fun blocks(id: Long) = content.materialize(id)
    private fun shape(blocks: List<MemoBlock>) = blocks.joinToString(" ") { if (it is MemoBlock.Text) "T(${it.text})" else "P" }
    private suspend fun entry(id: Long) = database.diaryDao().findById(id)
    private suspend fun write(id: Long, text: String): Long {
        val first = blocks(id).first { it is MemoBlock.Text }.id
        diary.saveBlocks(id, mapOf(first to text), releaseIfBlank = false)
        return first
    }

    @Test
    fun textPhotoTextPhotoIsOneEntryAndTheBodyIsItsWords() = runBlocking {
        val created = diary.createEntry(today.toEpochDay())
        assertEquals("a new entry is one text", "T()", shape(blocks(created.id)))
        val text = write(created.id, "朝は晴れ")
        val first = photos.importDiaryPhotos(created.id, listOf(image("a", Color.RED)), writingTextId = text)
        assertEquals("T(朝は晴れ) P T()", shape(blocks(created.id)))
        assertEquals("writing continues below the photo", blocks(created.id)[2].id, first.writingTextId)
        diary.saveBlocks(created.id, mapOf(first.writingTextId!! to "昼は散歩"), releaseIfBlank = false)
        val second = photos.importDiaryPhotos(created.id, listOf(image("b", Color.BLUE)), writingTextId = first.writingTextId)
        diary.saveBlocks(created.id, mapOf(second.writingTextId!! to "夜は雨"), releaseIfBlank = false)

        assertEquals("T(朝は晴れ) P T(昼は散歩) P T(夜は雨)", shape(blocks(created.id)))
        assertEquals("朝は晴れ\n昼は散歩\n夜は雨", entry(created.id)!!.body)
        val photoOrder = blocks(created.id).filterIsInstance<MemoBlock.Photo>().map { it.attachmentId }
        assertEquals("the photos' sortOrder follows their blocks", photoOrder, database.attachmentDao().diaryRelations(created.id).map { it.id })
    }

    @Test
    fun aPhotoPickedWithTheCaretBetweenTheWordsGoesBetweenThem() = runBlocking {
        val created = diary.createEntry(today.toEpochDay())
        val text = write(created.id, "一行目\n二行目")
        photos.importDiaryPhotos(created.id, listOf(image("c", Color.GREEN)), writingTextId = text, caret = 4)
        assertEquals("T(一行目) P T(二行目)", shape(blocks(created.id)))
        assertEquals("the body did not move", "一行目\n二行目", entry(created.id)!!.body)
    }

    @Test
    fun deletingAPhotoKeepsTheWordsAroundIt() = runBlocking {
        val created = diary.createEntry(today.toEpochDay())
        val text = write(created.id, "上")
        val added = photos.importDiaryPhotos(created.id, listOf(image("d", Color.RED)), writingTextId = text)
        diary.saveBlocks(created.id, mapOf(added.writingTextId!! to "下"), releaseIfBlank = false)
        val photo = blocks(created.id).filterIsInstance<MemoBlock.Photo>().single()
        photos.deleteDiaryPhoto(created.id, photo.attachmentId)
        assertEquals("the words stay; with no photo they are one text", "T(上\n下)", shape(blocks(created.id)))
        assertEquals("上\n下", entry(created.id)!!.body)
    }

    @Test
    fun aLockedEntryTakesNoWriteAndIsReadAsItWas() = runBlocking {
        val id = database.diaryDao().insert(
            DiaryEntryEntity(diaryDateEpochDay = 19_000, body = "昔の日", state = DiaryState.LOCKED, createdAt = 1, updatedAt = 2, lockedAt = 2),
        )
        assertEquals("T(昔の日)", shape(blocks(id)))
        val text = blocks(id).single().id
        assertTrue(diary.saveBlocks(id, mapOf(text to "書き換え")) is DiaryResult.Rejected)
        assertTrue(diary.saveBody(id, "書き換え") is DiaryResult.Rejected)
        val imported = photos.importDiaryPhotos(id, listOf(image("e", Color.RED)), writingTextId = text)
        assertEquals(0, imported.added)
        assertEquals(0, database.attachmentDao().diaryRelations(id).size)
        assertEquals("昔の日", entry(id)!!.body)
        assertEquals(DiaryState.LOCKED, entry(id)!!.state)
        assertEquals(2L, entry(id)!!.updatedAt)
    }

    @Test
    fun aBlankDraftIsReleasedOnLeavingUnlessItHoldsAPhoto() = runBlocking {
        val empty = diary.createEntry(today.toEpochDay())
        val text = blocks(empty.id).single().id
        assertEquals(DiaryResult.Success(null), diary.saveBlocks(empty.id, mapOf(text to ""), releaseIfBlank = true))
        assertNull("an empty draft goes", entry(empty.id))

        val withPhoto = diary.createEntry(today.toEpochDay())
        photos.importDiaryPhotos(withPhoto.id, listOf(image("f", Color.RED)), writingTextId = blocks(withPhoto.id).single().id)
        val after = blocks(withPhoto.id).filterIsInstance<MemoBlock.Text>().associate { it.id to "" }
        diary.saveBlocks(withPhoto.id, after, releaseIfBlank = true)
        assertTrue("a draft holding a photo stays", entry(withPhoto.id) != null)
    }

    @Test
    fun theAisAppendLandsAtTheEndAndMovesTheVersion() = runBlocking {
        val created = diary.createEntry(today.toEpochDay())
        val text = write(created.id, "本文")
        photos.importDiaryPhotos(created.id, listOf(image("g", Color.RED)), writingTextId = text)
        now = 5_000L
        // What DocumentAccess.appendToJournal does: the whole body with a line added.
        diary.saveBody(created.id, entry(created.id)!!.body + "\n追記", releaseIfBlank = false)
        assertEquals("T(本文) P T(追記)", shape(blocks(created.id)))
        assertEquals("本文\n追記", entry(created.id)!!.body)
        assertEquals("the version the AI checks moved", 5_000L, entry(created.id)!!.updatedAt)
    }

    @Test
    fun openingAndLeavingWithoutAChangeWritesNothing() = runBlocking {
        val created = diary.createEntry(today.toEpochDay())
        write(created.id, "変えない")
        val before = entry(created.id)!!.updatedAt
        now = 9_000L
        blocks(created.id)
        val text = blocks(created.id).single().id
        diary.saveBlocks(created.id, mapOf(text to "変えない"), releaseIfBlank = true)
        assertEquals(before, entry(created.id)!!.updatedAt)
    }

    @Test
    fun theFutureCommentsOfAnEntryAreUntouchedByItsBlocks() = runBlocking {
        val future = FutureDiaryCommentRepository(database.futureDiaryCommentDao(), database.diaryDao(), clock)
        val created = diary.createEntry(today.toEpochDay())
        val text = write(created.id, "今日")
        val sent = future.create(created.id, "未来の私へ", revealAt = now + 86_400_000L)
        assertTrue(sent is CreateFutureCommentResult.Success)
        val added = photos.importDiaryPhotos(created.id, listOf(image("h", Color.RED)), writingTextId = text)
        diary.saveBlocks(created.id, mapOf(added.writingTextId!! to "続き"), releaseIfBlank = false)
        photos.deleteDiaryPhoto(created.id, blocks(created.id).filterIsInstance<MemoBlock.Photo>().single().attachmentId)
        assertEquals(1, future.observeForDiary(created.id).first().size)
    }
}

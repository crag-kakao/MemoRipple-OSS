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
import io.github.cragcoffee.memoripple.data.AttachmentContentSource
import io.github.cragcoffee.memoripple.data.AttachmentRepository
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.DiaryRepository
import io.github.cragcoffee.memoripple.data.DiaryResult
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoRepository
import io.github.cragcoffee.memoripple.data.PhotoReorderResult
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import io.github.cragcoffee.memoripple.ui.attachments.AttachmentImageLoader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoAttachmentRepositoryInstrumentationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AppDatabase
    private lateinit var root: File
    private lateinit var store: AttachmentBlobStore
    private lateinit var fixtures: PhotoFixtureSource
    private lateinit var repository: AttachmentRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        root = File(context.cacheDir, "photo-qa-${System.nanoTime()}").apply { mkdirs() }
        store = AttachmentBlobStore(root)
        fixtures = PhotoFixtureSource()
        repository = AttachmentRepository(
            database = database,
            dao = database.attachmentDao(),
            noteDao = database.noteDao(),
            contentResolver = context.contentResolver,
            blobStore = store,
            nowMillis = { 1234L },
            contentSource = fixtures,
        )
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun nineteenPlusThreeAddsOnlyOneAndTwentyPlusOneIsRejected() = runBlocking {
        val memoId = insertMemo("limit")
        val uris = (0 until 23).map { index -> imageUri("limit-$index", Color.rgb(index, 20, 40)) }

        val first = repository.importMemoPhotos(memoId, uris.take(19))
        assertEquals(19, first.added)
        assertEquals(19, repository.memoPhotoCount(memoId))

        val boundary = repository.importMemoPhotos(memoId, uris.drop(19).take(3))
        assertEquals(1, boundary.added)
        assertEquals(2, boundary.rejected)
        assertTrue(boundary.limitReached)
        assertEquals(20, repository.memoPhotoCount(memoId))

        val over = repository.importMemoPhotos(memoId, listOf(uris.last()))
        assertEquals(0, over.added)
        assertEquals(1, over.rejected)
        assertTrue(over.limitReached)
        assertEquals(20, repository.memoPhotoCount(memoId))
    }

    @Test
    fun corruptMimeAndPartialBatchFailuresLeaveNoRowsBlobsOrTempFiles() = runBlocking {
        val memoId = insertMemo("corrupt")
        val validA = imageUri("valid-a", Color.RED)
        val validB = imageUri("valid-b", Color.BLUE)
        val zero = register("zero", ByteArray(0), "image/png")
        val random = register("random", ByteArray(128) { it.toByte() }, "image/png")
        val validFile = fixtures.fileFor(validA)!!
        val truncated = register(
            "truncated",
            validFile.readBytes().copyOf(validFile.length().toInt() / 2),
            "image/png",
        )
        val wrongMime = register("wrong-mime", validFile.readBytes(), "application/octet-stream")
        val unreadable = fixtures.unreadable("unreadable", "image/png")

        val partial = repository.importMemoPhotos(memoId, listOf(validA, random, validB))
        assertEquals(2, partial.added)
        assertEquals(1, partial.rejected)
        assertEquals(2, repository.memoPhotoCount(memoId))

        listOf(zero, truncated, wrongMime, unreadable).forEach { uri ->
            val result = repository.importMemoPhotos(memoId, listOf(uri))
            assertEquals(0, result.added)
            assertEquals(1, result.rejected)
        }
        assertEquals(2, database.backupDao().readAttachmentBlobs().size)
        assertEquals(2, database.backupDao().readMemoPhotoAttachments().size)
        assertTrue(File(root, "attachments/tmp").listFiles().orEmpty().isEmpty())
        assertEquals(2, File(root, "attachments/blobs").listFiles().orEmpty().size)
    }

    @Test
    fun duplicateSharedReferencesAndGarbageCollectionAreSafeAndIdempotent() = runBlocking {
        val memoA = insertMemo("A")
        val memoB = insertMemo("B")
        val diaryId = database.diaryDao().insertIgnoringConflict(
            DiaryEntryEntity(
                diaryDateEpochDay = 20_000,
                body = "",
                state = DiaryState.DRAFT,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        val uri = imageUri("shared", Color.GREEN)

        assertEquals(1, repository.importMemoPhotos(memoA, listOf(uri)).added)
        assertEquals(1, repository.importMemoPhotos(memoA, listOf(uri)).duplicates)
        assertEquals(1, repository.importMemoPhotos(memoB, listOf(uri)).added)
        assertEquals(1, repository.importDiaryPhotos(diaryId, listOf(uri)).added)
        assertEquals(1, database.backupDao().readAttachmentBlobs().size)
        assertEquals(2, database.backupDao().readMemoPhotoAttachments().size)
        assertEquals(1, database.backupDao().readDiaryPhotoAttachments().size)

        val sha = database.backupDao().readAttachmentBlobs().single().sha256
        val physical = store.blobFile(sha)
        val firstMemoRelation = repository.observeMemoPhotos(memoA).first().single()
        repository.deleteMemoPhoto(memoA, firstMemoRelation.id)
        assertTrue(physical.isFile)
        assertEquals(2, database.backupDao().readMemoPhotoAttachments().size +
            database.backupDao().readDiaryPhotoAttachments().size)

        repository.deleteMemoPhoto(memoB, repository.observeMemoPhotos(memoB).first().single().id)
        assertTrue(physical.isFile)
        repository.deleteDiaryPhoto(
            diaryId,
            repository.observeDiaryPhotos(diaryId).first().single().id,
        )
        assertFalse(physical.exists())
        assertTrue(database.backupDao().readAttachmentBlobs().isEmpty())

        val orphan = File(physical.parentFile, "a".repeat(64)).apply { writeText("orphan") }
        val suspicious = File(physical.parentFile, "do-not-delete").apply { writeText("unknown") }
        val suspiciousDirectory = File(physical.parentFile, "directory").apply { mkdirs() }
        repository.garbageCollect()
        repository.garbageCollect()
        assertFalse(orphan.exists())
        assertTrue(suspicious.isFile)
        assertTrue(suspiciousDirectory.isDirectory)
    }

    @Test
    fun corruptOrMissingStoredBlobUsesSafePlaceholderSignalWithoutDeletingRelation() = runBlocking {
        val memoId = insertMemo("integrity")
        val uri = imageUri("integrity", Color.MAGENTA)
        repository.importMemoPhotos(memoId, listOf(uri))
        val photo = repository.observeMemoPhotos(memoId).first().single()
        val file = store.blobFile(photo.blobSha256)
        val loader = AttachmentImageLoader(store)
        assertNotNull(loader.load(photo, 320))

        file.writeBytes(ByteArray(photo.sizeBytes.toInt()) { 7 })
        assertNull(loader.load(photo, 320))
        assertEquals(1, repository.memoPhotoCount(memoId))

        file.delete()
        assertNull(AttachmentImageLoader(store).load(photo, 320))
        assertEquals(1, repository.memoPhotoCount(memoId))
    }

    @Test
    fun archiveTrashRestoreAndPermanentDeletePreserveThenCollectPhoto() = runBlocking {
        val memoId = insertMemo("lifecycle")
        repository.importMemoPhotos(memoId, listOf(imageUri("lifecycle", Color.CYAN)))
        val sha = database.backupDao().readAttachmentBlobs().single().sha256
        val memoRepository = MemoRepository(database.memoDao())

        assertTrue(memoRepository.archive(memoId))
        assertEquals(1, repository.memoPhotoCount(memoId))
        assertTrue(store.blobFile(sha).isFile)
        assertTrue(memoRepository.moveToTrash(memoId))
        assertEquals(1, repository.memoPhotoCount(memoId))
        assertEquals(
            io.github.cragcoffee.memoripple.data.TrashRestoreDestination.ARCHIVED,
            memoRepository.restoreFromTrash(memoId),
        )
        assertEquals(1, repository.memoPhotoCount(memoId))
        assertTrue(memoRepository.moveToTrash(memoId))
        assertTrue(memoRepository.deletePermanently(memoId))
        repository.garbageCollect()
        assertTrue(database.backupDao().readMemoPhotoAttachments().isEmpty())
        assertTrue(database.backupDao().readAttachmentBlobs().isEmpty())
        assertFalse(store.blobFile(sha).exists())
    }

    @Test
    fun emptyTrashCollectsUniqueBlobButKeepsBlobSharedByActiveMemo() = runBlocking {
        val activeId = insertMemo("active")
        val trashId = insertMemo("trash")
        val shared = imageUri("empty-trash-shared", Color.YELLOW)
        val unique = imageUri("empty-trash-unique", Color.DKGRAY)
        repository.importMemoPhotos(activeId, listOf(shared))
        repository.importMemoPhotos(trashId, listOf(shared, unique))
        val hashes = database.backupDao().readAttachmentBlobs().associateBy { it.sha256 }
        val sharedSha = repository.observeMemoPhotos(activeId).first().single().blobSha256
        val uniqueSha = hashes.keys.single { it != sharedSha }
        val memoRepository = MemoRepository(database.memoDao())

        assertTrue(memoRepository.moveToTrash(trashId))
        assertEquals(1, memoRepository.emptyTrash())
        repository.garbageCollect()

        assertTrue(store.blobFile(sharedSha).isFile)
        assertFalse(store.blobFile(uniqueSha).exists())
        assertEquals(listOf(sharedSha), database.backupDao().readAttachmentBlobs().map { it.sha256 })
        assertEquals(1, repository.memoPhotoCount(activeId))
    }

    @Test
    fun photoOnlyMemoAndDiaryRemainValidUntilTheirFinalPhotoIsRemoved() = runBlocking {
        val memoRepository = MemoRepository(database.memoDao())
        val memo = memoRepository.createEmpty(10)
        repository.importMemoPhotos(memo.id, listOf(imageUri("photo-only-memo", Color.LTGRAY)))
        val savedMemo = memoRepository.save(memo, "", "", 11)
        assertNotNull(savedMemo)
        assertEquals(1, repository.memoPhotoCount(memo.id))

        val today = LocalDate.of(2026, 8, 25)
        val timeProvider = object : TimeProvider {
            override fun nowMillis(): Long = 20
            override fun currentLocalDate(): LocalDate = today
            override fun currentZoneId(): ZoneId = ZoneId.of("Asia/Tokyo")
        }
        val diaryRepository = DiaryRepository(
            database.diaryDao(),
            timeProvider,
            database.attachmentDao(),
        )
        val draft = diaryRepository.createEntry(today.toEpochDay())
        repository.importDiaryPhotos(draft.id, listOf(imageUri("photo-only-diary", Color.WHITE)))
        // Leaving with an empty body keeps the entry: its photo is content.
        val left = diaryRepository.saveBody(draft.id, "", releaseIfBlank = true)
        assertEquals(draft.id, (left as DiaryResult.Success).entry?.id)
        assertEquals(DiaryState.DRAFT, left.entry?.state)
        assertEquals(1, repository.diaryPhotoCount(draft.id))
    }

    @Test
    fun memoAndDiaryReorderNormalizeOrderAndPreserveRelationMetadata() = runBlocking {
        val memoId = insertMemo("reorder memo")
        val diaryId = database.diaryDao().insertIgnoringConflict(
            DiaryEntryEntity(
                diaryDateEpochDay = 20_001,
                body = "diary",
                state = DiaryState.DRAFT,
                createdAt = 8,
                updatedAt = 9,
            ),
        )
        val memoUpdatedAt = requireNotNull(database.memoDao().findById(memoId)).updatedAt
        val diaryUpdatedAt = requireNotNull(database.diaryDao().findById(diaryId)).updatedAt
        repository.importMemoPhotos(
            memoId,
            listOf(
                imageUri("memo-reorder-a", Color.RED),
                imageUri("memo-reorder-b", Color.GREEN),
                imageUri("memo-reorder-c", Color.BLUE),
            ),
        )
        repository.importDiaryPhotos(
            diaryId,
            listOf(
                imageUri("diary-reorder-a", Color.CYAN),
                imageUri("diary-reorder-b", Color.MAGENTA),
                imageUri("diary-reorder-c", Color.YELLOW),
            ),
        )
        val memoBefore = repository.observeMemoPhotos(memoId).first()
        val diaryBefore = repository.observeDiaryPhotos(diaryId).first()

        assertEquals(
            PhotoReorderResult.CHANGED,
            repository.reorderMemoPhotos(memoId, memoBefore.map { it.id }.reversed()),
        )
        assertEquals(
            PhotoReorderResult.CHANGED,
            repository.reorderDiaryPhotos(
                diaryId,
                listOf(diaryBefore[1].id, diaryBefore[2].id, diaryBefore[0].id),
            ),
        )

        val memoAfter = repository.observeMemoPhotos(memoId).first()
        val diaryAfter = repository.observeDiaryPhotos(diaryId).first()
        assertEquals(memoBefore.map { it.id }.reversed(), memoAfter.map { it.id })
        assertEquals(listOf(0, 1, 2), memoAfter.map { it.sortOrder })
        assertEquals(
            listOf(diaryBefore[1].id, diaryBefore[2].id, diaryBefore[0].id),
            diaryAfter.map { it.id },
        )
        assertEquals(listOf(0, 1, 2), diaryAfter.map { it.sortOrder })
        assertEquals(
            memoBefore.associate { it.id to (it.createdAt to it.blobSha256) },
            memoAfter.associate { it.id to (it.createdAt to it.blobSha256) },
        )
        assertEquals(
            diaryBefore.associate { it.id to (it.createdAt to it.blobSha256) },
            diaryAfter.associate { it.id to (it.createdAt to it.blobSha256) },
        )
        assertEquals(memoUpdatedAt, requireNotNull(database.memoDao().findById(memoId)).updatedAt)
        assertEquals(diaryUpdatedAt, requireNotNull(database.diaryDao().findById(diaryId)).updatedAt)
    }

    @Test
    fun invalidAndNoOpReordersLeaveEveryOwnerUnchanged() = runBlocking {
        val memoA = insertMemo("reorder A")
        val memoB = insertMemo("reorder B")
        repository.importMemoPhotos(
            memoA,
            listOf(
                imageUri("invalid-a", Color.RED),
                imageUri("invalid-b", Color.GREEN),
                imageUri("invalid-c", Color.BLUE),
            ),
        )
        repository.importMemoPhotos(memoB, listOf(imageUri("foreign", Color.WHITE)))
        val original = repository.observeMemoPhotos(memoA).first()
        val ids = original.map { it.id }
        val foreignId = repository.observeMemoPhotos(memoB).first().single().id

        assertEquals(PhotoReorderResult.UNCHANGED, repository.reorderMemoPhotos(memoA, ids))
        assertEquals(
            PhotoReorderResult.REJECTED,
            repository.reorderMemoPhotos(memoA, listOf(ids[0], ids[0], ids[2])),
        )
        assertEquals(
            PhotoReorderResult.REJECTED,
            repository.reorderMemoPhotos(memoA, ids.dropLast(1)),
        )
        assertEquals(
            PhotoReorderResult.REJECTED,
            repository.reorderMemoPhotos(memoA, ids + foreignId),
        )
        assertEquals(
            PhotoReorderResult.REJECTED,
            repository.reorderMemoPhotos(memoA, listOf(ids[0], ids[1], foreignId)),
        )
        assertEquals(
            PhotoReorderResult.REJECTED,
            repository.reorderMemoPhotos(Long.MAX_VALUE, emptyList()),
        )
        assertEquals(original, repository.observeMemoPhotos(memoA).first())
        assertEquals(listOf(foreignId), repository.observeMemoPhotos(memoB).first().map { it.id })
    }

    @Test
    fun invalidDiaryReordersAreRejectedWithoutPartialWrites() = runBlocking {
        val diaryA = database.diaryDao().insertIgnoringConflict(
            DiaryEntryEntity(
                diaryDateEpochDay = 20_010,
                body = "A",
                state = DiaryState.DRAFT,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        val diaryB = database.diaryDao().insertIgnoringConflict(
            DiaryEntryEntity(
                diaryDateEpochDay = 20_011,
                body = "B",
                state = DiaryState.DRAFT,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        repository.importDiaryPhotos(
            diaryA,
            listOf(
                imageUri("diary-invalid-a", Color.RED),
                imageUri("diary-invalid-b", Color.GREEN),
                imageUri("diary-invalid-c", Color.BLUE),
            ),
        )
        repository.importDiaryPhotos(diaryB, listOf(imageUri("diary-foreign", Color.WHITE)))
        val original = repository.observeDiaryPhotos(diaryA).first()
        val ids = original.map { it.id }
        val foreignId = repository.observeDiaryPhotos(diaryB).first().single().id

        listOf(
            listOf(ids[0], ids[0], ids[2]),
            ids.dropLast(1),
            ids + foreignId,
            listOf(ids[0], ids[1], foreignId),
        ).forEach { invalid ->
            assertEquals(
                PhotoReorderResult.REJECTED,
                repository.reorderDiaryPhotos(diaryA, invalid),
            )
        }
        assertEquals(original, repository.observeDiaryPhotos(diaryA).first())
    }

    @Test
    fun reorderForSharedBlobsDoesNotChangeOtherOwners() = runBlocking {
        val memoA = insertMemo("shared reorder A")
        val memoB = insertMemo("shared reorder B")
        val diary = database.diaryDao().insertIgnoringConflict(
            DiaryEntryEntity(
                diaryDateEpochDay = 20_012,
                body = "shared diary",
                state = DiaryState.DRAFT,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        val sharedUris = listOf(
            imageUri("shared-reorder-a", Color.RED),
            imageUri("shared-reorder-b", Color.GREEN),
            imageUri("shared-reorder-c", Color.BLUE),
        )
        repository.importMemoPhotos(memoA, sharedUris)
        repository.importMemoPhotos(memoB, sharedUris)
        repository.importDiaryPhotos(diary, sharedUris)
        val a = repository.observeMemoPhotos(memoA).first()
        val b = repository.observeMemoPhotos(memoB).first()
        val c = repository.observeDiaryPhotos(diary).first()

        assertEquals(
            PhotoReorderResult.CHANGED,
            repository.reorderMemoPhotos(memoA, a.map { it.id }.reversed()),
        )

        assertEquals(b, repository.observeMemoPhotos(memoB).first())
        assertEquals(c, repository.observeDiaryPhotos(diary).first())
        assertEquals(3, database.backupDao().readAttachmentBlobs().size)
    }

    private suspend fun insertMemo(title: String): Long = database.memoDao().insert(
        MemoEntity(title = title, body = "", createdAt = 1, updatedAt = 1),
    )


    // ---- Release audit B03: a delete must close its own sortOrder gap, or the backup
    // validator's contiguous 0..n-1 demand fails every future export.

    @Test
    fun deletingAnyMemoPhotoLeavesAContiguousOrder() = runBlocking {
        suspend fun freshMemoWithThree(tag: String): Long {
            val memoId = insertMemo("gap $tag")
            repository.importMemoPhotos(
                memoId,
                listOf(
                    imageUri("gap-$tag-a", Color.RED),
                    imageUri("gap-$tag-b", Color.GREEN),
                    imageUri("gap-$tag-c", Color.BLUE),
                ),
            )
            return memoId
        }
        suspend fun orders(memoId: Long): List<Int> =
            database.attachmentDao().memoRelations(memoId).map { it.sortOrder }

        // Middle, first, last: whatever leaves, the rest close ranks to 0..n-1.
        val middle = freshMemoWithThree("middle")
        repository.deleteMemoPhoto(
            middle,
            repository.observeMemoPhotos(middle).first()[1].id,
        )
        assertEquals(listOf(0, 1), orders(middle))

        val first = freshMemoWithThree("first")
        repository.deleteMemoPhoto(
            first,
            repository.observeMemoPhotos(first).first()[0].id,
        )
        assertEquals(listOf(0, 1), orders(first))

        val last = freshMemoWithThree("last")
        repository.deleteMemoPhoto(
            last,
            repository.observeMemoPhotos(last).first()[2].id,
        )
        assertEquals(listOf(0, 1), orders(last))

        // A single photo deletes to empty without complaint.
        val single = insertMemo("gap single")
        repository.importMemoPhotos(single, listOf(imageUri("gap-single", Color.CYAN)))
        repository.deleteMemoPhoto(
            single,
            repository.observeMemoPhotos(single).first().single().id,
        )
        assertEquals(emptyList<Int>(), orders(single))
        // And the next import starts back at 0, not past the healed gap.
        repository.importMemoPhotos(last, listOf(imageUri("gap-after", Color.MAGENTA)))
        assertEquals(listOf(0, 1, 2), orders(last))
    }

    @Test
    fun deletingAnyDiaryPhotoLeavesAContiguousOrder() = runBlocking {
        suspend fun freshDiaryWithThree(day: Long, tag: String): Long {
            val diaryId = database.diaryDao().insertIgnoringConflict(
                DiaryEntryEntity(
                    diaryDateEpochDay = day,
                    body = "gap diary $tag",
                    state = DiaryState.DRAFT,
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
            repository.importDiaryPhotos(
                diaryId,
                listOf(
                    imageUri("dgap-$tag-a", Color.RED),
                    imageUri("dgap-$tag-b", Color.GREEN),
                    imageUri("dgap-$tag-c", Color.BLUE),
                ),
            )
            return diaryId
        }
        suspend fun orders(diaryId: Long): List<Int> =
            database.attachmentDao().diaryRelations(diaryId).map { it.sortOrder }

        val middle = freshDiaryWithThree(20_101, "middle")
        repository.deleteDiaryPhoto(
            middle,
            repository.observeDiaryPhotos(middle).first()[1].id,
        )
        assertEquals(listOf(0, 1), orders(middle))

        val first = freshDiaryWithThree(20_102, "first")
        repository.deleteDiaryPhoto(
            first,
            repository.observeDiaryPhotos(first).first()[0].id,
        )
        assertEquals(listOf(0, 1), orders(first))

        val last = freshDiaryWithThree(20_103, "last")
        repository.deleteDiaryPhoto(
            last,
            repository.observeDiaryPhotos(last).first()[2].id,
        )
        assertEquals(listOf(0, 1), orders(last))
    }

    @Test
    fun normalizingOneOwnersOrderLeavesSharedBlobOwnersAlone() = runBlocking {
        val memoA = insertMemo("gap shared A")
        val memoB = insertMemo("gap shared B")
        val shared = listOf(
            imageUri("gap-shared-a", Color.RED),
            imageUri("gap-shared-b", Color.GREEN),
            imageUri("gap-shared-c", Color.BLUE),
        )
        repository.importMemoPhotos(memoA, shared)
        repository.importMemoPhotos(memoB, shared)
        val before = repository.observeMemoPhotos(memoB).first()
        val sharedSha = repository.observeMemoPhotos(memoA).first()[1].blobSha256

        repository.deleteMemoPhoto(
            memoA,
            repository.observeMemoPhotos(memoA).first()[1].id,
        )

        // The other owner's rows, order and bytes are untouched, and the blob survives
        // because it is still referenced.
        assertEquals(before, repository.observeMemoPhotos(memoB).first())
        assertEquals(
            listOf(0, 1),
            database.attachmentDao().memoRelations(memoA).map { it.sortOrder },
        )
        assertTrue(store.blobFile(sharedSha).isFile)
        repository.garbageCollect()
        assertTrue(store.blobFile(sharedSha).isFile)
    }

    private fun imageUri(name: String, color: Int): Uri {
        val file = File(root, "$name.png")
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { output -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        bitmap.recycle()
        return fixtures.register(name, file, "image/png")
    }

    private fun register(name: String, bytes: ByteArray, mimeType: String): Uri {
        val file = File(root, "$name.bin").apply { writeBytes(bytes) }
        return fixtures.register(name, file, mimeType)
    }
}

internal class PhotoFixtureSource : AttachmentContentSource {
    private data class Fixture(
        val file: File?,
        val mimeType: String,
        val unreadable: Boolean = false,
    )
    private val fixtures = mutableMapOf<String, Fixture>()

    fun register(name: String, file: File, mimeType: String): Uri {
        fixtures[name] = Fixture(file, mimeType)
        return uri(name)
    }

    fun unreadable(name: String, mimeType: String): Uri {
        fixtures[name] = Fixture(null, mimeType, unreadable = true)
        return uri(name)
    }

    fun fileFor(uri: Uri): File? = fixtures[uri.lastPathSegment]?.file

    override fun getType(uri: Uri): String? = fixtures[uri.lastPathSegment]?.mimeType

    override fun openInputStream(uri: Uri): InputStream? {
        val fixture = fixtures[uri.lastPathSegment] ?: return null
        if (fixture.unreadable) throw IOException("fixture stream failure")
        return fixture.file?.let(::FileInputStream)
    }

    private fun uri(name: String): Uri = Uri.parse("fixture://photo/$name")
}

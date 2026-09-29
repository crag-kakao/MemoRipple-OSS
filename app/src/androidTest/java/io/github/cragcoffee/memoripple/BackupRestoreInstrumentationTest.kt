package io.github.cragcoffee.memoripple

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.cragcoffee.memoripple.backup.BACKUP_FORMAT_IDENTIFIER
import io.github.cragcoffee.memoripple.backup.BACKUP_FORMAT_VERSION
import io.github.cragcoffee.memoripple.backup.BackupCodec
import io.github.cragcoffee.memoripple.backup.BackupEngine
import io.github.cragcoffee.memoripple.backup.BackupInspectionResult
import io.github.cragcoffee.memoripple.backup.BackupPayloadDto
import io.github.cragcoffee.memoripple.backup.BackupRestoreResult
import io.github.cragcoffee.memoripple.backup.DiaryEntryBackupDto
import io.github.cragcoffee.memoripple.backup.FolderBackupDto
import io.github.cragcoffee.memoripple.backup.FutureDiaryCommentBackupDto
import io.github.cragcoffee.memoripple.backup.MemoBackupDto
import io.github.cragcoffee.memoripple.backup.MemoCommentBackupDto
import io.github.cragcoffee.memoripple.backup.MemoRippleBackupDto
import io.github.cragcoffee.memoripple.backup.MemoTagRelationBackupDto
import io.github.cragcoffee.memoripple.backup.SettingsBackupDto
import io.github.cragcoffee.memoripple.backup.TagBackupDto
import io.github.cragcoffee.memoripple.backup.BackupFileReadResult
import io.github.cragcoffee.memoripple.backup.AttachmentBlobBackupDto
import io.github.cragcoffee.memoripple.backup.MemoPhotoAttachmentBackupDto
import io.github.cragcoffee.memoripple.backup.BackupContainer
import io.github.cragcoffee.memoripple.backup.SafBackupFileStore
import io.github.cragcoffee.memoripple.backup.RestoreCandidate
import io.github.cragcoffee.memoripple.data.AppDatabase
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.DiaryRepository
import io.github.cragcoffee.memoripple.data.FutureDiaryCommentEntity
import io.github.cragcoffee.memoripple.data.FutureDiaryCommentRepository
import io.github.cragcoffee.memoripple.data.MemoCommentEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.RoomBackupSnapshot
import io.github.cragcoffee.memoripple.data.SettingsRepository
import io.github.cragcoffee.memoripple.data.AttachmentBlobStore
import io.github.cragcoffee.memoripple.data.AttachmentRepository
import io.github.cragcoffee.memoripple.data.AttachmentBlobEntity
import io.github.cragcoffee.memoripple.data.MemoPhotoAttachmentEntity
import io.github.cragcoffee.memoripple.data.DiaryPhotoAttachmentEntity
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.domain.settings.CommentSize
import io.github.cragcoffee.memoripple.domain.settings.PlaybackSpeed
import io.github.cragcoffee.memoripple.domain.settings.StageBackground
import io.github.cragcoffee.memoripple.domain.settings.ThemeMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRestoreInstrumentationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_800_000_000_000L
    private val timeProvider = FixedTimeProvider(now)
    private lateinit var database: AppDatabase
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var engine: BackupEngine
    private lateinit var attachmentStore: AttachmentBlobStore
    private lateinit var attachmentRepository: AttachmentRepository
    private lateinit var attachmentRoot: File

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settingsFile = File(context.cacheDir, "restore-${System.nanoTime()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) { settingsFile }
        settingsRepository = SettingsRepository(dataStore)
        attachmentRoot = File(context.filesDir, "attachment-test-${System.nanoTime()}")
        attachmentStore = AttachmentBlobStore(attachmentRoot)
        attachmentRepository = AttachmentRepository(
            database,
            database.attachmentDao(),
            database.noteDao(),
            context.contentResolver,
            attachmentStore,
            timeProvider::nowMillis,
        )
        engine = BackupEngine(
            database = database,
            backupDao = database.backupDao(),
            settingsRepository = settingsRepository,
            diaryRepository = DiaryRepository(database.diaryDao(), timeProvider),
            futureDiaryCommentRepository = FutureDiaryCommentRepository(
                database.futureDiaryCommentDao(),
                database.diaryDao(),
                timeProvider,
            ),
            timeProvider = timeProvider,
            appVersionName = "instrumentation",
            appVersionCode = 1,
            blobStore = attachmentStore,
            attachmentRepository = attachmentRepository,
            cacheDirectory = context.cacheDir,
        )
    }

    @After
    fun tearDown() {
        database.close()
        dataStoreScope.cancel()
        attachmentRoot.deleteRecursively()
    }

    @Test
    fun safFileStoreWritesAndReadsSelectedContentResolverUri() {
        runBlocking {
            val file = File(context.cacheDir, "selected-${System.nanoTime()}.mrbackup")
            val bytes = BackupCodec().encode(backupB())
            val store = SafBackupFileStore(context.contentResolver)

            assertTrue(store.write(Uri.fromFile(file), bytes))
            val read = store.read(Uri.fromFile(file)) as BackupFileReadResult.Success
            assertArrayEquals(bytes, read.bytes)

            file.delete()
        }
    }

    @Test
    fun v10BackupRestoresSharedPhotoBlobAndBothOwnerRelations() = runBlocking {
        val memo = MemoEntity(id = 101, title = "写真メモ", body = "", createdAt = 1, updatedAt = 1)
        database.memoDao().insert(memo)
        val diary = DiaryEntryEntity(
            id = 201,
            diaryDateEpochDay = timeProvider.currentLocalDate().toEpochDay(),
            body = "",
            state = DiaryState.DRAFT,
            createdAt = 2,
            updatedAt = 2,
        )
        database.diaryDao().insertIgnoringConflict(diary)
        val photos = listOf(
            android.graphics.Color.RED,
            android.graphics.Color.GREEN,
            android.graphics.Color.BLUE,
        ).mapIndexed { index, color ->
            val source = File(context.cacheDir, "photo-$index-${System.nanoTime()}.png")
            android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
                .also { bitmap ->
                    bitmap.eraseColor(color)
                    source.outputStream().use { output ->
                        assertTrue(
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output),
                        )
                    }
                    bitmap.recycle()
                }
            val size = source.length()
            val sha = AttachmentBlobStore.hash(source)
            attachmentStore.installValidated(source, sha, size)
            sha to size
        }
        database.withTransaction {
            photos.forEachIndexed { index, (sha, size) ->
                database.attachmentDao().insertBlob(
                    AttachmentBlobEntity(sha, "image", "image/png", size, 2, 2, 3L + index),
                )
                database.attachmentDao().insertMemoRelation(
                    MemoPhotoAttachmentEntity(
                        id = 301L + index,
                        memoId = 101,
                        blobSha256 = sha,
                        sortOrder = listOf(2, 0, 1)[index],
                        createdAt = 10L + index,
                    ),
                )
                database.attachmentDao().insertDiaryRelation(
                    DiaryPhotoAttachmentEntity(
                        id = 401L + index,
                        diaryEntryId = 201,
                        blobSha256 = sha,
                        sortOrder = listOf(1, 2, 0)[index],
                        createdAt = 20L + index,
                    ),
                )
            }
        }

        val prepared = engine.prepareBackup()
        assertEquals(0x50, prepared.file.inputStream().use { it.read() })
        val ready = engine.inspect(prepared.file) as BackupInspectionResult.Ready
        assertEquals(6, ready.candidate.preview.photoCount)
        prepared.discard()

        database.withTransaction {
            database.backupDao().deleteMemoPhotoAttachments()
            database.backupDao().deleteDiaryPhotoAttachments()
            database.backupDao().deleteAttachmentBlobs()
        }
        photos.forEach { (sha, _) -> attachmentStore.remove(sha) }

        assertEquals(BackupRestoreResult.Success, engine.restore(ready.candidate))
        assertEquals(3, database.backupDao().readAttachmentBlobs().size)
        assertEquals(
            listOf(302L, 303L, 301L),
            database.backupDao().readMemoPhotoAttachments().map { it.id },
        )
        assertEquals(
            listOf(403L, 401L, 402L),
            database.backupDao().readDiaryPhotoAttachments().map { it.id },
        )
        assertEquals(listOf(0, 1, 2), database.backupDao().readMemoPhotoAttachments().map { it.sortOrder })
        assertEquals(listOf(0, 1, 2), database.backupDao().readDiaryPhotoAttachments().map { it.sortOrder })
        photos.forEach { (sha, size) -> assertTrue(attachmentStore.isValid(sha, size)) }
    }


    @Test
    fun deletingTheMiddlePhotoStillBacksUpAndRestoresInOrder() = runBlocking {
        // Release audit B03: a middle delete used to leave sortOrder {0,2}, which the
        // validator rejects — every later backup failed forever. The delete now closes
        // its own gap, so the same flow must export and round-trip cleanly.
        val memoId = database.memoDao().insert(
            MemoEntity(title = "欠番のメモ", body = "", createdAt = 1, updatedAt = 1),
        )
        val fixtures = PhotoFixtureSource()
        val importingRepository = AttachmentRepository(
            database,
            database.attachmentDao(),
            database.noteDao(),
            context.contentResolver,
            attachmentStore,
            timeProvider::nowMillis,
            contentSource = fixtures,
        )
        val uris = listOf(
            android.graphics.Color.RED,
            android.graphics.Color.GREEN,
            android.graphics.Color.BLUE,
        ).mapIndexed { index, color ->
            val file = File(context.cacheDir, "b03-$index-${System.nanoTime()}.png")
            android.graphics.Bitmap.createBitmap(3, 2, android.graphics.Bitmap.Config.ARGB_8888)
                .also { bitmap ->
                    bitmap.eraseColor(color)
                    file.outputStream().use { output ->
                        assertTrue(
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output),
                        )
                    }
                    bitmap.recycle()
                }
            fixtures.register("b03-$index", file, "image/png")
        }
        assertEquals(3, importingRepository.importMemoPhotos(memoId, uris).added)

        val middle = database.attachmentDao().memoRelations(memoId)[1]
        importingRepository.deleteMemoPhoto(memoId, middle.id)
        assertEquals(
            listOf(0, 1),
            database.attachmentDao().memoRelations(memoId).map { it.sortOrder },
        )

        // Export succeeds where it used to die on the validator's contiguity require.
        val prepared = engine.prepareBackup()
        val ready = engine.inspect(prepared.file) as BackupInspectionResult.Ready
        assertEquals(2, ready.candidate.preview.photoCount)

        val keptShas = database.attachmentDao().memoRelations(memoId).map { it.blobSha256 }
        database.withTransaction {
            database.backupDao().deleteMemoPhotoAttachments()
            database.backupDao().deleteAttachmentBlobs()
        }
        keptShas.forEach { sha -> attachmentStore.remove(sha) }

        assertEquals(BackupRestoreResult.Success, engine.restore(ready.candidate))
        prepared.discard()
        val restored = database.attachmentDao().memoRelations(memoId)
        assertEquals(listOf(0, 1), restored.map { it.sortOrder })
        assertEquals(keptShas, restored.map { it.blobSha256 })
    }

    @Test
    fun roomFailureRollsBackOldDataAndCollectsNewlyInstalledRestoreBlob() = runBlocking {
        database.memoDao().insert(MemoEntity(id = 1, title = "before", body = "safe", createdAt = 1, updatedAt = 1))
        val exportRoot = File(context.cacheDir, "atomic-export-${System.nanoTime()}")
        val exportStore = AttachmentBlobStore(exportRoot)
        val image = File(context.cacheDir, "atomic-${System.nanoTime()}.png")
        android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
            .also { bitmap ->
                bitmap.eraseColor(android.graphics.Color.GREEN)
                image.outputStream().use { output ->
                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
                }
                bitmap.recycle()
            }
        val size = image.length()
        val sha = AttachmentBlobStore.hash(image)
        exportStore.installValidated(image, sha, size)
        val base = backupB()
        val document = base.copy(
            payload = base.payload.copy(
                attachmentBlobs = listOf(
                    AttachmentBlobBackupDto(sha, "image", "image/png", size, 2, 2, now),
                ),
                memoPhotoAttachments = listOf(
                    MemoPhotoAttachmentBackupDto(1, 100, sha, 0, now),
                ),
            ),
        )
        val archive = File(context.cacheDir, "atomic-${System.nanoTime()}.mrbackup")
        BackupContainer().write(document, exportStore, archive)
        val ready = engine.inspect(archive) as BackupInspectionResult.Ready
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER force_attachment_restore_failure " +
                "BEFORE INSERT ON attachment_blobs BEGIN " +
                "SELECT RAISE(ABORT, 'forced attachment restore failure'); END",
        )

        assertEquals(BackupRestoreResult.RoomFailure, engine.restore(ready.candidate))
        assertEquals(listOf("before"), database.backupDao().readMemos().map { it.title })
        assertTrue(database.backupDao().readAttachmentBlobs().isEmpty())
        assertFalse(attachmentStore.blobFile(sha).exists())

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER force_attachment_restore_failure")
        archive.delete()
        exportRoot.deleteRecursively()
        Unit
    }

    @Test
    fun restoreReplacesAllDataRunsPostProcessingRestoresSettingsAndAdvancesIds() = runBlocking {
        insertOriginalA()
        val candidate = candidateFor(backupB())
        assertFalse(candidate.preview.toString().contains("due sealed"))
        assertFalse(candidate.preview.toString().contains("awaiting presentation"))

        assertEquals(BackupRestoreResult.Success, engine.restore(candidate))

        val restored = database.backupDao().snapshot()
        assertEquals(listOf(100L), restored.memos.map { it.id })
        assertTrue(restored.memos.single().isFavorite)
        assertTrue(restored.memos.single().isPinned)
        assertEquals(90L, restored.memos.single().archivedAt)
        assertEquals(95L, restored.memos.single().trashedAt)
        assertEquals(listOf(200L), restored.memoComments.map { it.id })
        assertEquals(listOf(300L, 301L), restored.diaryEntries.map { it.id })
        assertEquals(listOf(400L, 401L), restored.futureDiaryComments.map { it.id })
        assertEquals(listOf(500L, 501L), restored.tags.map { it.id })
        assertEquals(
            listOf(100L to 500L, 100L to 501L),
            restored.memoTagRelations.map { it.memoId to it.tagId },
        )
        // Restore no longer locks past entries (HANDOFF §16.18): the row keeps the state it was saved with.
        assertEquals(DiaryState.DRAFT, database.diaryDao().findById(300)?.state)
        val due = database.futureDiaryCommentDao().findEntityById(400)
        assertEquals(now, due?.deliveredAt)
        assertNull(due?.revealedAt)
        assertEquals("pink", due?.appearanceColor)
        assertEquals("large", due?.appearanceSize)
        assertEquals("strong", due?.appearanceEmphasis)
        assertEquals("fixed_top", due?.motionMode)
        val awaitingPresentation = database.futureDiaryCommentDao().findEntityById(401)
        assertEquals(now - 50, awaitingPresentation?.revealedAt)
        assertNull(awaitingPresentation?.firstPresentedAt)
        assertEquals("fixed_bottom", awaitingPresentation?.motionMode)
        assertEquals(
            AppSettings(ThemeMode.DARK, PlaybackSpeed.FAST, CommentSize.LARGE, StageBackground.LIGHT),
            settingsRepository.settings.first(),
        )

        val memoId = database.memoDao().insert(MemoEntity(title = "new", body = "", createdAt = now, updatedAt = now))
        val commentId = database.memoCommentDao().insert(
            MemoCommentEntity(memoId = memoId, text = "new", createdAt = now, playbackOrder = 0),
        )
        val diaryId = database.diaryDao().insertIgnoringConflict(
            DiaryEntryEntity(
                diaryDateEpochDay = timeProvider.currentLocalDate().plusDays(1).toEpochDay(),
                body = "new",
                state = DiaryState.DRAFT,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val futureId = database.futureDiaryCommentDao().insert(
            FutureDiaryCommentEntity(
                diaryEntryId = diaryId,
                text = "new",
                sealedAt = now,
                revealAt = now + 1_000,
            ),
        )
        assertTrue(memoId > 100)
        assertTrue(commentId > 200)
        assertTrue(diaryId > 301)
        assertTrue(futureId > 401)
        val tagId = database.tagDao().insert(
            io.github.cragcoffee.memoripple.data.TagEntity(
                name = "new",
                normalizedName = "new",
                createdAt = now,
            ),
        )
        assertTrue(tagId > 501)
    }

    @Test
    fun versionOneRestoreDefaultsOrganizationFlagsToFalse() = runBlocking {
        val legacy = backupB().copy(
            formatVersion = 1,
            payload = backupB().payload.copy(
                memos = listOf(MemoBackupDto(100, "旧メモ", "本文", 100, 100)),
                tags = emptyList(),
                memoTagRelations = emptyList(),
            ),
        )

        assertEquals(BackupRestoreResult.Success, engine.restore(candidateFor(legacy)))

        val restored = requireNotNull(database.memoDao().findById(100))
        assertFalse(restored.isFavorite)
        assertFalse(restored.isPinned)
        assertTrue(database.tagDao().observeAllTags().first().isEmpty())
    }

    @Test
    fun versionFourteenRestoreMakesEveryMemoAMemoWhateverItsBodyLooksLike() = runBlocking {
        val legacy = backupB().copy(
            formatVersion = 14,
            payload = backupB().payload.copy(
                memos = listOf(
                    MemoBackupDto(100, "B", "restored", 100, 100),
                    MemoBackupDto(101, "骨組み", "- 項目\n  - 子", 100, 100),
                ),
                memoComments = emptyList(),
                memoTagRelations = emptyList(),
            ),
        )

        assertEquals(BackupRestoreResult.Success, engine.restore(candidateFor(legacy)))

        assertEquals("memo", database.memoDao().findById(100)?.kind)
        assertEquals("memo", database.memoDao().findById(101)?.kind)
        assertEquals("- 項目\n  - 子", database.memoDao().findById(101)?.body)
        assertEquals(listOf(100L, 101L), database.memoDao().observeStandaloneMemos("").first().map { it.id }.sorted())
        assertTrue(database.memoDao().observeOutlineDocuments("").first().isEmpty())
    }

    @Test
    fun versionFifteenRestoreKeepsOutlinesAndAFreshBackupWritesThemBackOut() = runBlocking {
        val mixed = backupB().copy(
            payload = backupB().payload.copy(
                memos = listOf(
                    MemoBackupDto(100, "B", "restored", 100, 100, kind = "memo"),
                    MemoBackupDto(101, "計画", "- 一\n  - 二", 100, 100, kind = "outline"),
                ),
                memoComments = listOf(MemoCommentBackupDto(200, 101, "アウトラインのコメント", 200, 0)),
                memoTagRelations = emptyList(),
            ),
        )
        assertEquals(BACKUP_FORMAT_VERSION, mixed.formatVersion)

        assertEquals(BackupRestoreResult.Success, engine.restore(candidateFor(mixed)))

        assertEquals("memo", database.memoDao().findById(100)?.kind)
        assertEquals("outline", database.memoDao().findById(101)?.kind)
        assertEquals(listOf(100L), database.memoDao().observeStandaloneMemos("").first().map { it.id })
        assertEquals(listOf(101L), database.memoDao().observeOutlineDocuments("").first().map { it.id })
        assertEquals("アウトラインのコメント", database.memoCommentDao().findForMemo(101).single().text)

        // Export → import: the kinds survive the round trip through a file.
        val prepared = engine.prepareBackup()
        val inspected = engine.inspect(prepared.file) as BackupInspectionResult.Ready
        val document = inspected.candidate.document
        inspected.candidate.discard()
        prepared.discard()
        assertEquals(BACKUP_FORMAT_VERSION, document.formatVersion)
        assertEquals(mapOf(100L to "memo", 101L to "outline"), document.payload.memos.associate { it.id to it.kind })
        assertEquals(BackupRestoreResult.Success, engine.restore(candidateFor(document)))
        assertEquals("outline", database.memoDao().findById(101)?.kind)
    }

    @Test
    fun versionFifteenRestoreLeavesEveryDocumentAtTheRootWithNoFolders() = runBlocking {
        val older = backupB().copy(
            formatVersion = 15,
            payload = backupB().payload.copy(
                memos = listOf(MemoBackupDto(100, "B", "restored", 100, 100, kind = "outline")),
                memoComments = emptyList(), memoTagRelations = emptyList(),
            ),
        )

        assertEquals(BackupRestoreResult.Success, engine.restore(candidateFor(older)))

        assertTrue(database.folderDao().all().isEmpty())
        assertNull(database.memoDao().findById(100)?.folderId)
        assertEquals("outline", database.memoDao().findById(100)?.kind)
    }

    @Test
    fun versionSixteenRestoreKeepsNestedFoldersAndMembershipAndAFreshBackupRoundTripsThem() = runBlocking {
        val mixed = backupB().copy(
            payload = backupB().payload.copy(
                folders = listOf(
                    FolderBackupDto(1, "開発", null, 10, 11),
                    FolderBackupDto(2, "Android", 1, 12, 13),
                    FolderBackupDto(3, "日常", null, 14, 15),
                ),
                memos = listOf(
                    MemoBackupDto(100, "仕様", "本文", 100, 100, kind = "memo", folderId = 2),
                    MemoBackupDto(101, "計画", "- 一", 100, 100, kind = "outline", folderId = 2),
                    MemoBackupDto(102, "棚上げ", "本文", 100, 100, archivedAt = 90, folderId = 1),
                    MemoBackupDto(103, "捨てた", "本文", 100, 100, trashedAt = 95, folderId = 3),
                    MemoBackupDto(104, "未分類", "本文", 100, 100),
                ),
                memoComments = emptyList(), memoTagRelations = emptyList(),
            ),
        )
        assertEquals(BACKUP_FORMAT_VERSION, mixed.formatVersion)

        assertEquals(BackupRestoreResult.Success, engine.restore(candidateFor(mixed)))

        assertEquals(listOf(1L to null, 2L to 1L, 3L to null), database.folderDao().all().map { it.id to it.parentFolderId })
        assertEquals(
            mapOf(100L to 2L, 101L to 2L, 102L to 1L, 103L to 3L, 104L to null),
            database.backupDao().readMemos().associate { it.id to it.folderId },
        )
        assertEquals(90L, database.memoDao().findById(102)?.archivedAt)
        assertEquals(95L, database.memoDao().findById(103)?.trashedAt)

        // Export → import: the tree and the membership survive the file.
        val prepared = engine.prepareBackup()
        val inspected = engine.inspect(prepared.file) as BackupInspectionResult.Ready
        val document = inspected.candidate.document
        inspected.candidate.discard()
        prepared.discard()
        assertEquals(BACKUP_FORMAT_VERSION, document.formatVersion)
        assertEquals(3, document.payload.folders.size)
        assertEquals(BackupRestoreResult.Success, engine.restore(candidateFor(document)))
        assertEquals(2L, database.memoDao().findById(101)?.folderId)
        assertEquals(1L, database.folderDao().findById(2)?.parentFolderId)
    }

    @Test
    fun aMemoPointingAtAMissingFolderIsRestoredAtTheRootAndACyclicTreeIsRefused() = runBlocking {
        val dangling = backupB().copy(
            payload = backupB().payload.copy(
                folders = listOf(FolderBackupDto(1, "開発", null, 10, 11)),
                memos = listOf(MemoBackupDto(100, "仕様", "本文", 100, 100, folderId = 999)),
                memoComments = emptyList(), memoTagRelations = emptyList(),
            ),
        )
        assertEquals(BackupRestoreResult.Success, engine.restore(candidateFor(dangling)))
        assertNull(database.memoDao().findById(100)?.folderId)
        assertEquals("本文", database.memoDao().findById(100)?.body)

        val cyclic = dangling.copy(
            payload = dangling.payload.copy(
                folders = listOf(FolderBackupDto(1, "A", 2, 1, 1), FolderBackupDto(2, "B", 1, 1, 1)),
            ),
        )
        // The app never writes such a file (the container validates first); a hand-made one
        // is what a restore could meet, and it is refused on the way in.
        val file = File(context.cacheDir, "cyclic-" + System.nanoTime() + ".mrbackup")
        java.util.zip.ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zip.write(kotlinx.serialization.json.Json.encodeToString(MemoRippleBackupDto.serializer(), cyclic).toByteArray())
            zip.closeEntry()
        }

        try {
            assertTrue(engine.inspect(file) !is BackupInspectionResult.Ready)
        } finally {
            file.delete()
        }
        // What was on the device is untouched by the refused file.
        assertEquals("本文", database.memoDao().findById(100)?.body)
    }

    @Test
    fun versionTwoRestoreDefaultsTagsAndRelationsToEmpty() = runBlocking {
        val legacy = backupB().copy(
            formatVersion = 2,
            payload = backupB().payload.copy(tags = emptyList(), memoTagRelations = emptyList()),
        )

        assertEquals(BackupRestoreResult.Success, engine.restore(candidateFor(legacy)))

        assertTrue(database.tagDao().observeAllTags().first().isEmpty())
        assertTrue(database.tagDao().observeAllRelations().first().isEmpty())
        assertEquals("B", database.memoDao().findById(100)?.title)
    }

    @Test
    fun preparedBackupUsesCurrentVersionAndPreservesExpressionsLifecycleOrganizationAndTags() =
        runBlocking {
        database.memoDao().insert(
            MemoEntity(
                id = 50,
                title = "整理済み",
                body = "本文",
                createdAt = 10,
                updatedAt = 20,
                isFavorite = true,
                isPinned = true,
                archivedAt = 25,
            ),
        )
        database.memoCommentDao().insertAtEnd(
            MemoCommentEntity(
                memoId = 50,
                text = "強い反応",
                createdAt = 22,
                appearanceColor = "pink",
                appearanceSize = "large",
                appearanceEmphasis = "strong",
                motionSpeed = "fast",
                motionPlacement = "top",
                motionMode = "fixed_top",
                flowDirection = "ltr",
                flowEffect = "wave",
            ),
        )
        database.tagDao().insert(
            io.github.cragcoffee.memoripple.data.TagEntity(
                id = 70,
                name = "仕事",
                normalizedName = "仕事",
                createdAt = 30,
            ),
        )
        database.tagDao().attach(io.github.cragcoffee.memoripple.data.MemoTagCrossRef(50, 70))
        database.diaryDao().insertIgnoringConflict(
            DiaryEntryEntity(
                id = 60,
                diaryDateEpochDay = timeProvider.currentLocalDate().toEpochDay(),
                body = "Future source",
                state = DiaryState.LOCKED,
                createdAt = 30,
                updatedAt = 30,
            ),
        )
        database.futureDiaryCommentDao().insert(
            FutureDiaryCommentEntity(
                id = 61,
                diaryEntryId = 60,
                text = "Styled future",
                sealedAt = 31,
                revealAt = now + 10_000,
                appearanceColor = "pink",
                appearanceSize = "large",
                appearanceEmphasis = "strong",
                motionMode = "fixed_bottom",
            ),
        )

        val prepared = engine.prepareBackup()
        val inspected = engine.inspect(prepared.file) as BackupInspectionResult.Ready
        val document = inspected.candidate.document
        inspected.candidate.discard()
        prepared.discard()

        assertEquals(BACKUP_FORMAT_VERSION, document.formatVersion)
        assertTrue(document.payload.memos.single().isFavorite)
        assertTrue(document.payload.memos.single().isPinned)
        assertEquals(25L, document.payload.memos.single().archivedAt)
        assertEquals(listOf(TagBackupDto(70, "仕事", 30)), document.payload.tags)
        assertEquals(listOf(MemoTagRelationBackupDto(50, 70)), document.payload.memoTagRelations)
        assertEquals("pink", document.payload.memoComments.single().colorRole)
        assertEquals("large", document.payload.memoComments.single().sizeRole)
        assertEquals("strong", document.payload.memoComments.single().emphasisRole)
        assertEquals("fast", document.payload.memoComments.single().speedRole)
        assertEquals("top", document.payload.memoComments.single().placementRole)
        assertEquals("fixed_top", document.payload.memoComments.single().motionMode)
        assertEquals("ltr", document.payload.memoComments.single().flowDirection)
        assertEquals("wave", document.payload.memoComments.single().flowEffect)
        assertEquals("pink", document.payload.futureDiaryComments.single().appearanceColor)
        assertEquals("large", document.payload.futureDiaryComments.single().appearanceSize)
        assertEquals("strong", document.payload.futureDiaryComments.single().appearanceEmphasis)
        assertEquals("fixed_bottom", document.payload.futureDiaryComments.single().motionMode)

        assertEquals(BackupRestoreResult.Success, engine.restore(candidateFor(document)))
        val restoredComment = database.memoCommentDao().findForMemo(50).single()
        assertEquals("pink", restoredComment.appearanceColor)
        assertEquals("large", restoredComment.appearanceSize)
        assertEquals("strong", restoredComment.appearanceEmphasis)
        assertEquals("fast", restoredComment.motionSpeed)
        assertEquals("top", restoredComment.motionPlacement)
        assertEquals("fixed_top", restoredComment.motionMode)
        assertEquals("ltr", restoredComment.flowDirection)
        assertEquals("wave", restoredComment.flowEffect)
        val restoredFuture = requireNotNull(
            database.futureDiaryCommentDao().findEntityById(61),
        )
        assertEquals("pink", restoredFuture.appearanceColor)
        assertEquals("large", restoredFuture.appearanceSize)
        assertEquals("strong", restoredFuture.appearanceEmphasis)
        assertEquals("fixed_bottom", restoredFuture.motionMode)
    }

    @Test
    fun versionThreeRestoreDefaultsLifecycleTimestampsToNull() = runBlocking {
        val legacy = backupB().copy(
            formatVersion = 3,
            payload = backupB().payload.copy(
                memos = listOf(MemoBackupDto(100, "旧v3メモ", "本文", 100, 100, true, true)),
            ),
        )
        assertEquals(BackupRestoreResult.Success, engine.restore(candidateFor(legacy)))
        val restored = requireNotNull(database.memoDao().findById(100))
        assertNull(restored.archivedAt)
        assertNull(restored.trashedAt)
    }

    @Test
    fun insertFailureRollsBackAllRoomDataAndLeavesSettingsUntouched() = runBlocking {
        insertOriginalA()
        settingsRepository.replaceAll(
            AppSettings(ThemeMode.LIGHT, PlaybackSpeed.SLOW, CommentSize.SMALL, StageBackground.THEME),
        )
        val before = database.backupDao().snapshot()
        val settingsBefore = settingsRepository.settings.first()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_restore BEFORE INSERT ON memos " +
                "WHEN NEW.title = 'reject_restore' BEGIN SELECT RAISE(ABORT, 'test'); END",
        )
        val rejected = backupB().let { document ->
            document.copy(
                payload = document.payload.copy(
                    memos = document.payload.memos.map { it.copy(title = "reject_restore") },
                ),
            )
        }

        assertEquals(BackupRestoreResult.RoomFailure, engine.restore(candidateFor(rejected)))

        assertEquals(before, database.backupDao().snapshot())
        assertEquals(settingsBefore, settingsRepository.settings.first())
    }

    private suspend fun insertOriginalA() {
        val today = timeProvider.currentLocalDate().toEpochDay()
        database.withTransaction {
            database.backupDao().replaceAll(
                RoomBackupSnapshot(
                    memos = listOf(MemoEntity(1, "A", "old", 1, 1)),
                    memoComments = listOf(MemoCommentEntity(2, 1, "old comment", 2, 0)),
                    diaryEntries = listOf(
                        DiaryEntryEntity(3, today, "old diary", DiaryState.LOCKED, 3, 3, lockedAt = 3),
                    ),
                    futureDiaryComments = listOf(
                        FutureDiaryCommentEntity(4, 3, "old future", 4, now + 10_000),
                    ),
                ),
            )
        }
    }

    private fun backupB(): MemoRippleBackupDto {
        val today = timeProvider.currentLocalDate().toEpochDay()
        return MemoRippleBackupDto(
            format = BACKUP_FORMAT_IDENTIFIER,
            formatVersion = BACKUP_FORMAT_VERSION,
            exportedAt = now - 1_000,
            appVersionName = "backup-source",
            appVersionCode = 1,
            payload = BackupPayloadDto(
                memos = listOf(
                    MemoBackupDto(
                        100,
                        "B",
                        "restored",
                        100,
                        100,
                        isFavorite = true,
                        isPinned = true,
                        archivedAt = 90,
                        trashedAt = 95,
                    ),
                ),
                memoComments = listOf(MemoCommentBackupDto(200, 100, "restored comment", 200, 0)),
                diaryEntries = listOf(
                    DiaryEntryBackupDto(300, today - 1, "past draft", "draft", 300, 300, null, null, null),
                    DiaryEntryBackupDto(301, today, "today", "locked", 301, 301, 301, null, 301),
                ),
                futureDiaryComments = listOf(
                    FutureDiaryCommentBackupDto(
                        400, 300, "due sealed", now - 1_000, now - 100, null, null, null,
                        "pink", "large", "strong", "fixed_top",
                    ),
                    FutureDiaryCommentBackupDto(
                        401, 301, "awaiting presentation", now - 1_000, now - 100,
                        now - 90, now - 50, null,
                        "blue", "small", "normal", "fixed_bottom",
                    ),
                ),
                tags = listOf(
                    TagBackupDto(500, "仕事", 500),
                    TagBackupDto(501, "Ｃｏｆｆｅｅ", 501),
                ),
                memoTagRelations = listOf(
                    MemoTagRelationBackupDto(100, 500),
                    MemoTagRelationBackupDto(100, 501),
                ),
                settings = SettingsBackupDto("dark", "fast", "large", "light"),
            ),
        )
    }

    private suspend fun candidateFor(document: MemoRippleBackupDto): RestoreCandidate {
        val file = File(context.cacheDir, "candidate-${System.nanoTime()}.mrbackup")
        if (document.formatVersion >= 10) {
            io.github.cragcoffee.memoripple.backup.BackupContainer().write(
                document,
                io.github.cragcoffee.memoripple.data.AttachmentBlobStore(context.filesDir),
                file,
            )
        } else {
            file.writeBytes(BackupCodec().encode(document))
        }
        return try {
            (engine.inspect(file) as BackupInspectionResult.Ready).candidate
        } finally {
            file.delete()
        }
    }

    private class FixedTimeProvider(private val now: Long) : TimeProvider {
        private val zone = ZoneId.of("Asia/Tokyo")
        override fun nowMillis(): Long = now
        override fun currentLocalDate(): LocalDate =
            java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        override fun currentZoneId(): ZoneId = zone
    }
}

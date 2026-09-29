package io.github.cragcoffee.memoripple

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.cragcoffee.memoripple.data.AppDatabase
import io.github.cragcoffee.memoripple.data.AttachmentBlobEntity
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.FolderEntity
import io.github.cragcoffee.memoripple.data.FutureDiaryCommentEntity
import io.github.cragcoffee.memoripple.data.MemoCommentEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoTagCrossRef
import io.github.cragcoffee.memoripple.data.NoteChapterEntity
import io.github.cragcoffee.memoripple.data.NoteEntity
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import io.github.cragcoffee.memoripple.domain.memos.isOutline
import io.github.cragcoffee.memoripple.domain.memos.memoKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private lateinit var context: Context
    private val databaseName = "memo-ripple-migration-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationTo16LeavesEveryExistingMemoStandingAloneAndOpensTheNoteTables() = runBlocking {
        createVersionOneDatabase()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            // A memo written before notes existed is a memo that belongs to no note.
            val memo = requireNotNull(database.memoDao().findById(1))
            assertEquals("既存メモ", memo.title)
            assertEquals(null, memo.noteId)
            assertEquals(null, memo.chapterId)
            assertEquals(0, memo.episodeOrder)

            val noteId = database.noteDao().insert(
                NoteEntity(title = "夜明け前に君と", coverColor = "plum", createdAt = 1, updatedAt = 1),
            )
            val chapterId = database.noteDao().insertChapter(
                NoteChapterEntity(noteId = noteId, title = "第一章", sortOrder = 0),
            )
            database.noteDao().placeEpisode(memo.id, noteId, chapterId, 0)

            val placed = requireNotNull(database.memoDao().findById(1))
            assertEquals(noteId, placed.noteId)
            assertEquals(chapterId, placed.chapterId)
            assertEquals(listOf(1L), database.noteDao().episodes(noteId).map { it.id })

            // Deleting the note releases its episodes; they were memos before it and stay memos.
            database.noteDao().delete(noteId)
            val released = requireNotNull(database.memoDao().findById(1))
            assertEquals(null, released.noteId)
            assertEquals(null, released.chapterId)
            assertEquals("既存メモ", released.title)
            assertTrue(database.noteDao().allChapters().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationTo17LeavesEveryNoteWithoutAPictureOnItsCover() = runBlocking {
        createVersionOneDatabase()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            // A note made before covers could hold a picture is a note with a colour and no picture.
            val noteId = database.noteDao().insert(
                NoteEntity(title = "夜明け前に君と", coverColor = "plum", createdAt = 1, updatedAt = 1),
            )
            assertEquals(null, requireNotNull(database.noteDao().findById(noteId)).coverBlobSha256)

            // A picture on a cover is a reference: the collector must not take the file away.
            val sha = "a".repeat(64)
            database.attachmentDao().insertBlob(
                AttachmentBlobEntity(
                    sha256 = sha,
                    kind = "photo",
                    mimeType = "image/jpeg",
                    sizeBytes = 10,
                    widthPx = 4,
                    heightPx = 3,
                    createdAt = 1,
                ),
            )
            assertTrue(sha in database.attachmentDao().unreferencedBlobShas())
            database.noteDao().setCoverPhoto(noteId, sha, 2)
            assertTrue(sha !in database.attachmentDao().unreferencedBlobShas())
            assertEquals(0, database.attachmentDao().deleteBlobIfUnreferenced(sha))

            // Taking the picture off the cover lets the file go again.
            database.noteDao().setCoverPhoto(noteId, null, 3)
            assertEquals(1, database.attachmentDao().deleteBlobIfUnreferenced(sha))
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationTo19LeavesEveryCommentUnlinkedAndNumbersFromOne() = runBlocking {
        createVersionOneDatabase()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            val memoId = database.memoDao().insert(
                io.github.cragcoffee.memoripple.data.MemoEntity(
                    title = "リンクの器", body = "本文", createdAt = 1, updatedAt = 1,
                ),
            )
            val first = database.memoCommentDao().insert(
                MemoCommentEntity(memoId = memoId, text = "一つ目", createdAt = 2),
            )
            val second = database.memoCommentDao().insert(
                MemoCommentEntity(memoId = memoId, text = "二つ目", createdAt = 3),
            )
            // A comment made before links existed carries no number.
            assertTrue(
                database.memoCommentDao().findForMemo(memoId).all { it.linkNo == null },
            )
            // Numbering starts at one, walks max+1, and a gap left by removal never refills.
            assertEquals(null, database.memoCommentDao().maxLinkNo(memoId))
            database.memoCommentDao().setLinkNo(first, 1)
            database.memoCommentDao().setLinkNo(second, 2)
            database.memoCommentDao().setLinkNo(first, null)
            assertEquals(2, database.memoCommentDao().maxLinkNo(memoId))
        } finally {
            database.close()
        }
    }

    /**
     * 文書種別: every memo written before the column existed is a memo, whatever its body looks
     * like, and nothing else about the row or anything hanging off it moves.
     */
    @Test
    fun migrationTo20MarksEveryExistingMemoAsAMemoAndKeepsEverythingElse() = runBlocking {
        createVersionNineteenDatabase()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            val memos = database.backupDao().readMemos()
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), memos.map { it.id })
            assertTrue(memos.all { it.kind == "memo" })
            assertTrue(memos.none { it.isOutline })

            // The plain memo, untouched: words, times, favourite, pin.
            val plain = memos[0]
            assertEquals("既存メモ", plain.title)
            assertEquals("既存本文", plain.body)
            assertEquals(100L, plain.createdAt)
            assertEquals(100L, plain.updatedAt)
            assertTrue(plain.isFavorite)
            assertTrue(plain.isPinned)
            // Written as an outline, still a memo — the body is not read to decide.
            val outlined = memos[1]
            assertEquals("- 項目\n  - 子 [R1] ←\n■ 見出し\n本文 [[既存メモ]]", outlined.body)
            assertEquals(MemoKind.MEMO, outlined.memoKind)
            // The episode keeps its place in its note; archive and trash keep their times.
            assertEquals(1L, memos[2].noteId)
            assertEquals(1L, memos[2].chapterId)
            assertEquals(0, memos[2].episodeOrder)
            assertEquals(400L, memos[3].archivedAt)
            assertEquals(500L, memos[4].trashedAt)

            val comments = database.memoCommentDao().findForMemo(1)
            assertEquals(listOf("先", "後"), comments.map(MemoCommentEntity::text))
            assertEquals("pink", comments.first().appearanceColor)
            assertEquals(1, database.memoCommentDao().findForMemo(2).single().linkNo)
            val note = requireNotNull(database.noteDao().findById(1))
            assertEquals("夜明け前に君と", note.title)
            assertEquals("港の話", note.subtitle)
            assertEquals(listOf("第一章"), database.noteDao().allChapters().map { it.title })
            assertEquals(listOf(3L), database.noteDao().episodes(1).map { it.id })
            assertEquals(listOf(MemoTagCrossRef(2, 1)), database.tagDao().observeAllRelations().first())
            val diary = requireNotNull(database.diaryDao().findByDate(20_956))
            assertEquals("既存の日記", diary.body)
            assertEquals(DiaryState.FINALIZED, diary.state)
            assertEquals(1_200L, database.futureDiaryCommentDao().findEntityById(50)?.firstPresentedAt)
            assertEquals("まだ封印中", database.futureDiaryCommentDao().findEntityById(51)?.text)

            // The wall still shows the memos it showed: kind = memo, standing alone, active.
            assertEquals(listOf(2L, 1L), database.memoDao().observeStandaloneMemos("").first().map { it.id })
            assertTrue(database.memoDao().observeOutlineDocuments("").first().isEmpty())

            // An outline made now is listed as an outline and never on the wall.
            val outlineId = database.memoDao().insert(
                MemoEntity(
                    title = "計画", body = "- 一", createdAt = 600, updatedAt = 600,
                    kind = MemoKind.OUTLINE.storageId,
                ),
            )
            assertEquals(listOf(outlineId), database.memoDao().observeOutlineDocuments("").first().map { it.id })
            assertEquals(listOf(2L, 1L), database.memoDao().observeStandaloneMemos("").first().map { it.id })
            assertEquals(listOf(outlineId), database.memoDao().observeOutlineDocuments("計画").first().map { it.id })
            assertTrue(database.memoDao().observeOutlineDocuments("既存").first().isEmpty())
        } finally {
            database.close()
        }
    }

    /**
     * フォルダ: every document written before folders existed stands at the root (`folderId`
     * null); nothing else about it moves, and the folder table opens empty.
     */
    @Test
    fun migrationTo21LeavesEveryDocumentAtTheRootAndOpensTheFolderTable() = runBlocking {
        createVersionTwentyDatabase()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            val memos = database.backupDao().readMemos()
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), memos.map { it.id })
            assertTrue(memos.all { it.folderId == null })
            assertEquals(listOf("memo", "memo", "memo", "memo", "memo", "outline"), memos.map { it.kind })
            // Words, times and states as they were.
            assertEquals("- 項目\n  - 子 [R1] ←\n■ 見出し\n本文 [[既存メモ]]", memos[1].body)
            assertEquals("- 一\n  - 二", memos[5].body)
            assertEquals(700L, memos[5].createdAt)
            assertEquals(400L, memos[3].archivedAt)
            assertEquals(500L, memos[4].trashedAt)
            assertEquals(1L, memos[2].noteId)
            assertEquals(1, database.memoCommentDao().findForMemo(2).single().linkNo)
            assertEquals(listOf(MemoTagCrossRef(2, 1)), database.tagDao().observeAllRelations().first())
            assertEquals("夜明け前に君と", database.noteDao().findById(1)?.title)
            assertEquals(DiaryState.FINALIZED, database.diaryDao().findByDate(20_956)?.state)
            assertTrue(database.folderDao().all().isEmpty())

            // The wall and the outliner page show what they showed.
            assertEquals(listOf(2L, 1L), database.memoDao().observeStandaloneMemos("").first().map { it.id })
            assertEquals(listOf(6L), database.memoDao().observeOutlineDocuments("").first().map { it.id })

            // A folder made now can hold a document of either kind.
            val folderId = database.folderDao().insert(
                FolderEntity(name = "開発", parentFolderId = null, createdAt = 800, updatedAt = 800),
            )
            assertEquals(1, database.memoDao().setFolder(6, folderId))
            assertEquals(folderId, database.memoDao().findById(6)?.folderId)
            assertEquals(null, database.memoDao().findById(1)?.folderId)
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationFrom1To8KeepsMemosAndCascadeDeletesComments() = runBlocking {
        createVersionOneDatabase()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            val memo = database.memoDao().findById(1)
            assertNotNull(memo)
            assertEquals("既存メモ", memo?.title)
            assertEquals(false, memo?.isFavorite)
            assertEquals(false, memo?.isPinned)

            database.memoCommentDao().insertAtEnd(
                MemoCommentEntity(memoId = 1, text = "後日のコメント", createdAt = 200),
            )
            database.memoDao().moveToTrash(requireNotNull(memo).id, 300)
            database.memoDao().deletePermanently(memo.id)

            assertEquals(
                emptyList<MemoCommentEntity>(),
                database.memoCommentDao().observeForMemo(1).first(),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationFrom2To8KeepsCreationTimesAndAssignsStableOrderPerMemo() = runBlocking {
        createVersionTwoDatabase()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            val memoOneComments = database.memoCommentDao().observeForMemo(1).first()
            assertEquals(listOf(10L, 11L, 20L), memoOneComments.map { it.id })
            assertEquals(listOf(100L, 100L, 200L), memoOneComments.map { it.createdAt })
            assertEquals(listOf(0, 1, 2), memoOneComments.map { it.playbackOrder })

            val memoTwoComments = database.memoCommentDao().observeForMemo(2).first()
            assertEquals(listOf(30L), memoTwoComments.map { it.id })
            assertEquals(listOf(0), memoTwoComments.map { it.playbackOrder })
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationFrom3To8KeepsMemoCommentOrderAndADayMayHoldSeveralDiaryEntries() = runBlocking {
        createVersionThreeDatabase()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            assertEquals("既存メモ", database.memoDao().findById(1)?.title)
            val comments = database.memoCommentDao().observeForMemo(1).first()
            assertEquals(listOf("先", "後"), comments.map { it.text })
            assertEquals(listOf(0, 1), comments.map { it.playbackOrder })

            val firstId = database.diaryDao().insertIgnoringConflict(
                DiaryEntryEntity(
                    diaryDateEpochDay = 20_956,
                    body = "今日の日記",
                    state = DiaryState.DRAFT,
                    createdAt = 100,
                    updatedAt = 100,
                ),
            )
            val duplicateId = database.diaryDao().insertIgnoringConflict(
                DiaryEntryEntity(
                    diaryDateEpochDay = 20_956,
                    body = "重複",
                    state = DiaryState.DRAFT,
                    createdAt = 200,
                    updatedAt = 200,
                ),
            )

            // Through the whole chain (the unique day index left in 22→23) a second entry on the
            // same day is an ordinary row; the day's first entry is the older one.
            assertTrue(firstId > 0)
            assertTrue(duplicateId > 0 && duplicateId != firstId)
            assertEquals("今日の日記", database.diaryDao().findByDate(20_956)?.body)
            assertEquals(listOf("今日の日記", "重複"), database.diaryDao().entriesForDate(20_956).map { it.body })
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationFrom4To8KeepsDiaryStateAndFutureCommentsCascadeWithDiary() = runBlocking {
        createVersionFourDatabase()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            val diary = requireNotNull(database.diaryDao().findByDate(20_956))
            assertEquals("既存の日記", diary.body)
            assertEquals(DiaryState.FINALIZED, diary.state)

            database.futureDiaryCommentDao().insert(
                FutureDiaryCommentEntity(
                    diaryEntryId = diary.id,
                    text = "未来の本文",
                    sealedAt = 100,
                    revealAt = 1_000,
                ),
            )
            assertEquals(1, database.futureDiaryCommentDao().observeForDiary(diary.id).first().size)

            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM diary_entries WHERE id = ?",
                arrayOf<Any>(diary.id),
            )
            assertTrue(database.futureDiaryCommentDao().observeForDiary(diary.id).first().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationFrom5To8PreservesFutureCommentAndMarksExistingRevealPresented() = runBlocking {
        createVersionFiveDatabase()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            assertEquals("既存メモ", database.memoDao().findById(1)?.title)
            val diary = requireNotNull(database.diaryDao().findByDate(20_956))
            assertEquals(DiaryState.FINALIZED, diary.state)

            val revealed = requireNotNull(database.futureDiaryCommentDao().findEntityById(50))
            assertEquals(diary.id, revealed.diaryEntryId)
            assertEquals("既に開封済み", revealed.text)
            assertEquals(1_000L, revealed.revealAt)
            assertEquals(1_100L, revealed.deliveredAt)
            assertEquals(1_200L, revealed.revealedAt)
            assertEquals(1_200L, revealed.firstPresentedAt)

            val sealed = requireNotNull(database.futureDiaryCommentDao().findEntityById(51))
            assertEquals("まだ封印中", sealed.text)
            assertEquals(null, sealed.firstPresentedAt)

            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM diary_entries WHERE id = ?",
                arrayOf<Any>(diary.id),
            )
            assertEquals(null, database.futureDiaryCommentDao().findEntityById(50))
            assertEquals(null, database.futureDiaryCommentDao().findEntityById(51))
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationFrom6To8PreservesMemoAndCommentsWithOrganizationDefaults() = runBlocking {
        createVersionSixDatabase()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            val memo = requireNotNull(database.memoDao().findById(1))
            assertEquals("既存メモ", memo.title)
            assertEquals("既存本文", memo.body)
            assertEquals(100L, memo.createdAt)
            assertEquals(100L, memo.updatedAt)
            assertEquals(false, memo.isFavorite)
            assertEquals(false, memo.isPinned)

            val comments = database.memoCommentDao().observeForMemo(1).first()
            assertEquals(listOf("先", "後"), comments.map(MemoCommentEntity::text))
            assertEquals(listOf(0, 1), comments.map(MemoCommentEntity::playbackOrder))
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationFrom7To8PreservesDataAndStartsWithNoTags() = runBlocking {
        createVersionSevenDatabase()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            val memo = requireNotNull(database.memoDao().findById(1))
            assertEquals("既存メモ", memo.title)
            assertTrue(memo.isFavorite)
            assertTrue(memo.isPinned)
            assertEquals(null, memo.archivedAt)
            assertEquals(null, memo.trashedAt)
            assertEquals(
                listOf("先", "後"),
                database.memoCommentDao().observeForMemo(1).first().map(MemoCommentEntity::text),
            )
            assertTrue(database.tagDao().observeAllTags().first().isEmpty())
            assertTrue(database.tagDao().observeAllRelations().first().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationFrom8To9AddsEmptyLifecycleTimestampsWithoutDataLoss() = runBlocking {
        createVersionEightDatabase()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            val memo = requireNotNull(database.memoDao().findById(1))
            assertEquals("既存メモ", memo.title)
            assertEquals(null, memo.archivedAt)
            assertEquals(null, memo.trashedAt)
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationFrom9To10AddsDefaultAppearanceWithoutChangingCommentData() = runBlocking {
        createVersionNineDatabase()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            val comments = database.memoCommentDao().findForMemo(1)
            assertEquals(listOf("先", "後"), comments.map(MemoCommentEntity::text))
            assertEquals(listOf(100L, 50L), comments.map(MemoCommentEntity::createdAt))
            assertEquals(listOf(0, 1), comments.map(MemoCommentEntity::playbackOrder))
            assertTrue(comments.all { it.appearanceColor == "default" })
            assertTrue(comments.all { it.appearanceSize == "standard" })
            assertTrue(comments.all { it.appearanceEmphasis == "normal" })
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationFrom10To11AddsDefaultMotionWithoutChangingCommentOrAppearance() = runBlocking {
        createVersionTenDatabase()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            val comments = database.memoCommentDao().findForMemo(1)
            assertEquals(listOf("先", "後"), comments.map(MemoCommentEntity::text))
            assertEquals(listOf(100L, 50L), comments.map(MemoCommentEntity::createdAt))
            assertEquals(listOf(0, 1), comments.map(MemoCommentEntity::playbackOrder))
            assertEquals("pink", comments.first().appearanceColor)
            assertEquals("large", comments.first().appearanceSize)
            assertEquals("strong", comments.first().appearanceEmphasis)
            assertTrue(comments.all { it.motionSpeed == "standard" })
            assertTrue(comments.all { it.motionPlacement == "auto" })
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationFrom11To12AddsFlowModeWithoutChangingExistingMotion() = runBlocking {
        createVersionElevenDatabase()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            val comments = database.memoCommentDao().findForMemo(1)
            assertEquals(listOf("先", "後"), comments.map(MemoCommentEntity::text))
            assertEquals("fast", comments.first().motionSpeed)
            assertEquals("bottom", comments.first().motionPlacement)
            assertTrue(comments.all { it.motionMode == "flow" })
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationFrom12To13AddsDefaultFutureExpressionWithoutChangingState() = runBlocking {
        createVersionTwelveDatabase()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            val sealed = requireNotNull(database.futureDiaryCommentDao().findEntityById(51))
            val delivered = requireNotNull(database.futureDiaryCommentDao().findEntityById(52))
            val awaiting = requireNotNull(database.futureDiaryCommentDao().findEntityById(53))
            val presented = requireNotNull(database.futureDiaryCommentDao().findEntityById(50))

            assertEquals(null, sealed.deliveredAt)
            assertEquals(1_500L, delivered.deliveredAt)
            assertEquals(1_600L, awaiting.revealedAt)
            assertEquals(null, awaiting.firstPresentedAt)
            assertEquals(1_200L, presented.firstPresentedAt)
            assertEquals("既に開封済み", presented.text)
            listOf(sealed, delivered, awaiting, presented).forEach { comment ->
                assertEquals("default", comment.appearanceColor)
                assertEquals("standard", comment.appearanceSize)
                assertEquals("normal", comment.appearanceEmphasis)
                assertEquals("flow", comment.motionMode)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationFrom13To15PreservesCommentAndCreatesEmptyPhotoTables() = runBlocking {
        createVersionThirteenDatabase()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            val comments = database.memoCommentDao().findForMemo(1)
            val comment = comments.first()
            assertEquals("先", comment.text)
            assertEquals(100L, comment.createdAt)
            assertEquals(0, comment.playbackOrder)
            assertEquals("pink", comment.appearanceColor)
            assertEquals("large", comment.appearanceSize)
            assertEquals("strong", comment.appearanceEmphasis)
            assertEquals("fast", comment.motionSpeed)
            assertEquals("bottom", comment.motionPlacement)
            assertEquals("fixed_top", comment.motionMode)
            assertEquals("rtl", comment.flowDirection)
            assertEquals("straight", comment.flowEffect)
            assertTrue(database.backupDao().readAttachmentBlobs().isEmpty())
            assertTrue(database.backupDao().readMemoPhotoAttachments().isEmpty())
            assertTrue(database.backupDao().readDiaryPhotoAttachments().isEmpty())
        } finally {
            database.close()
        }
    }

    /**
     * A version-19 database as a phone running the closed-test build holds it: the thirteen
     * fixture carried through the app's own 13→19 steps, then one row of everything that can
     * hang off a memo.
     */

    /** 並べた順: every document written before it has index 0 and keeps its folder; nothing else moves. */
    @Test
    fun migrationTo22GivesEveryDocumentIndexZeroAndKeepsItsFolder() = runBlocking {
        createVersionTwentyOneDatabase()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            val memos = database.backupDao().readMemos()
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), memos.map { it.id })
            assertTrue(memos.all { it.sortIndex == 0 })
            assertEquals(1L, memos[5].folderId)
            assertEquals("- 一\n  - 二", memos[5].body)
            assertEquals("開発", database.folderDao().findById(1)?.name)
            assertEquals(0, database.noteDao().findById(1)?.sortIndex)
            assertEquals(1, database.noteDao().setSortIndex(1, 2))
            assertEquals(2, database.noteDao().observeSummaries().first().single().sortIndex)
            assertEquals(1, database.memoDao().setSortIndex(6, 3))
            assertEquals(3, database.memoDao().findById(6)?.sortIndex)
        } finally {
            database.close()
        }
    }

    private fun createVersionTwentyOneDatabase() {
        createVersionTwentyDatabase()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(21) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = AppDatabase.MIGRATION_20_21.migrate(db)
                    },
                )
                .build(),
        )
        helper.writableDatabase.execSQL(
            "INSERT INTO `folders` (`id`, `name`, `parentFolderId`, `createdAt`, `updatedAt`) VALUES (1, '開発', NULL, 800, 800)",
        )
        helper.writableDatabase.execSQL("UPDATE `memos` SET `folderId` = 1 WHERE `id` = 6")
        helper.close()
    }

    private fun createVersionNineteenDatabase() {
        createVersionThirteenDatabase()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(19) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            AppDatabase.MIGRATION_13_14.migrate(db)
                            AppDatabase.MIGRATION_14_15.migrate(db)
                            AppDatabase.MIGRATION_15_16.migrate(db)
                            AppDatabase.MIGRATION_16_17.migrate(db)
                            AppDatabase.MIGRATION_17_18.migrate(db)
                            AppDatabase.MIGRATION_18_19.migrate(db)
                        }
                    },
                )
                .build(),
        )
        helper.writableDatabase.apply {
            execSQL(
                """
                INSERT INTO `notes` (`id`, `title`, `coverColor`, `createdAt`, `updatedAt`, `subtitle`)
                VALUES (1, '夜明け前に君と', 'plum', 240, 270, '港の話')
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO `note_chapters` (`id`, `noteId`, `title`, `sortOrder`) VALUES (1, 1, '第一章', 0)",
            )
            execSQL(
                """
                INSERT INTO `memos`
                    (`id`, `title`, `body`, `createdAt`, `updatedAt`, `noteId`, `chapterId`,
                     `episodeOrder`, `archivedAt`, `trashedAt`)
                VALUES
                    (2, '骨組み', '- 項目' || char(10) || '  - 子 [R1] ←' || char(10) || '■ 見出し' ||
                        char(10) || '本文 [[既存メモ]]', 200, 210, NULL, NULL, 0, NULL, NULL),
                    (3, '第一話', '本文C', 240, 250, 1, 1, 0, NULL, NULL),
                    (4, '棚上げ', '本文D', 300, 310, NULL, NULL, 0, 400, NULL),
                    (5, '捨てた', '本文E', 300, 310, NULL, NULL, 0, NULL, 500)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO `memo_comments`
                    (`id`, `memoId`, `text`, `createdAt`, `playbackOrder`, `linkNo`)
                VALUES (12, 2, 'リンク付き', 220, 0, 1)
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO `tags` (`id`, `name`, `normalizedName`, `createdAt`) VALUES (1, '仕事', '仕事', 230)",
            )
            execSQL("INSERT INTO `memo_tag_cross_refs` (`memoId`, `tagId`) VALUES (2, 1)")
        }
        helper.close()
    }

    /** A version-20 database: the nineteen fixture through the app's 19→20 step, plus one outline. */
    private fun createVersionTwentyDatabase() {
        createVersionNineteenDatabase()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(20) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = AppDatabase.MIGRATION_19_20.migrate(db)
                    },
                )
                .build(),
        )
        helper.writableDatabase.execSQL(
            """
            INSERT INTO `memos` (`id`, `title`, `body`, `createdAt`, `updatedAt`, `kind`)
            VALUES (6, '計画', '- 一' || char(10) || '  - 二', 700, 710, 'outline')
            """.trimIndent(),
        )
        helper.close()
    }

    private fun createVersionOneDatabase() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `memos` (
                                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    `title` TEXT NOT NULL,
                                    `body` TEXT NOT NULL,
                                    `createdAt` INTEGER NOT NULL,
                                    `updatedAt` INTEGER NOT NULL
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        helper.writableDatabase.execSQL(
            """
            INSERT INTO `memos` (`id`, `title`, `body`, `createdAt`, `updatedAt`)
            VALUES (1, '既存メモ', '既存本文', 100, 100)
            """.trimIndent(),
        )
        helper.close()
    }

    private fun createVersionTwoDatabase() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `memos` (
                                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    `title` TEXT NOT NULL,
                                    `body` TEXT NOT NULL,
                                    `createdAt` INTEGER NOT NULL,
                                    `updatedAt` INTEGER NOT NULL
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `memo_comments` (
                                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    `memoId` INTEGER NOT NULL,
                                    `text` TEXT NOT NULL,
                                    `createdAt` INTEGER NOT NULL,
                                    FOREIGN KEY(`memoId`) REFERENCES `memos`(`id`)
                                        ON UPDATE NO ACTION ON DELETE CASCADE
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                "CREATE INDEX IF NOT EXISTS `index_memo_comments_memoId` " +
                                    "ON `memo_comments` (`memoId`)",
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        helper.writableDatabase.apply {
            execSQL(
                """
                INSERT INTO `memos` (`id`, `title`, `body`, `createdAt`, `updatedAt`)
                VALUES (1, 'Memo 1', 'Body', 1, 1), (2, 'Memo 2', 'Body', 1, 1)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO `memo_comments` (`id`, `memoId`, `text`, `createdAt`)
                VALUES
                    (20, 1, 'late', 200),
                    (11, 1, 'same second', 100),
                    (10, 1, 'same first', 100),
                    (30, 2, 'other memo', 50)
                """.trimIndent(),
            )
        }
        helper.close()
    }

    private fun createVersionThreeDatabase() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `memos` (
                                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    `title` TEXT NOT NULL,
                                    `body` TEXT NOT NULL,
                                    `createdAt` INTEGER NOT NULL,
                                    `updatedAt` INTEGER NOT NULL
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `memo_comments` (
                                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    `memoId` INTEGER NOT NULL,
                                    `text` TEXT NOT NULL,
                                    `createdAt` INTEGER NOT NULL,
                                    `playbackOrder` INTEGER NOT NULL DEFAULT 0,
                                    FOREIGN KEY(`memoId`) REFERENCES `memos`(`id`)
                                        ON UPDATE NO ACTION ON DELETE CASCADE
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                "CREATE INDEX IF NOT EXISTS `index_memo_comments_memoId` " +
                                    "ON `memo_comments` (`memoId`)",
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        helper.writableDatabase.apply {
            execSQL(
                """
                INSERT INTO `memos` (`id`, `title`, `body`, `createdAt`, `updatedAt`)
                VALUES (1, '既存メモ', '既存本文', 100, 100)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO `memo_comments`
                    (`id`, `memoId`, `text`, `createdAt`, `playbackOrder`)
                VALUES
                    (10, 1, '先', 100, 0),
                    (11, 1, '後', 50, 1)
                """.trimIndent(),
            )
        }
        helper.close()
    }

    private fun createVersionFourDatabase() {
        createVersionThreeDatabase()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(4) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `diary_entries` (
                                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    `diaryDateEpochDay` INTEGER NOT NULL,
                                    `body` TEXT NOT NULL,
                                    `state` TEXT NOT NULL,
                                    `createdAt` INTEGER NOT NULL,
                                    `updatedAt` INTEGER NOT NULL,
                                    `finalizedAt` INTEGER,
                                    `correctionStartedAt` INTEGER,
                                    `lockedAt` INTEGER
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                                    "`index_diary_entries_diaryDateEpochDay` " +
                                    "ON `diary_entries` (`diaryDateEpochDay`)",
                            )
                        }
                    },
                )
                .build(),
        )
        helper.writableDatabase.execSQL(
            """
            INSERT INTO `diary_entries`
                (`id`, `diaryDateEpochDay`, `body`, `state`, `createdAt`, `updatedAt`, `finalizedAt`)
            VALUES (7, 20956, '既存の日記', 'FINALIZED', 100, 200, 200)
            """.trimIndent(),
        )
        helper.close()
    }

    private fun createVersionFiveDatabase() {
        createVersionFourDatabase()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(5) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `future_diary_comments` (
                                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    `diaryEntryId` INTEGER NOT NULL,
                                    `text` TEXT NOT NULL,
                                    `sealedAt` INTEGER NOT NULL,
                                    `revealAt` INTEGER NOT NULL,
                                    `deliveredAt` INTEGER,
                                    `revealedAt` INTEGER,
                                    FOREIGN KEY(`diaryEntryId`) REFERENCES `diary_entries`(`id`)
                                        ON UPDATE NO ACTION ON DELETE CASCADE
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                "CREATE INDEX IF NOT EXISTS " +
                                    "`index_future_diary_comments_diaryEntryId` " +
                                    "ON `future_diary_comments` (`diaryEntryId`)",
                            )
                            db.execSQL(
                                "CREATE INDEX IF NOT EXISTS `index_future_diary_comments_revealAt` " +
                                    "ON `future_diary_comments` (`revealAt`)",
                            )
                        }
                    },
                )
                .build(),
        )
        helper.writableDatabase.execSQL(
            """
            INSERT INTO `future_diary_comments`
                (`id`, `diaryEntryId`, `text`, `sealedAt`, `revealAt`, `deliveredAt`, `revealedAt`)
            VALUES
                (50, 7, '既に開封済み', 900, 1000, 1100, 1200),
                (51, 7, 'まだ封印中', 1300, 2000, NULL, NULL)
            """.trimIndent(),
        )
        helper.close()
    }

    private fun createVersionSixDatabase() {
        createVersionFiveDatabase()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(6) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            db.execSQL(
                                "ALTER TABLE `future_diary_comments` " +
                                    "ADD COLUMN `firstPresentedAt` INTEGER",
                            )
                            db.execSQL(
                                """
                                UPDATE `future_diary_comments`
                                SET `firstPresentedAt` = `revealedAt`
                                WHERE `revealedAt` IS NOT NULL
                                """.trimIndent(),
                            )
                        }
                    },
                )
                .build(),
        )
        helper.writableDatabase
        helper.close()
    }

    private fun createVersionSevenDatabase() {
        createVersionSixDatabase()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(7) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            db.execSQL(
                                "ALTER TABLE `memos` " +
                                    "ADD COLUMN `isFavorite` INTEGER NOT NULL DEFAULT 0",
                            )
                            db.execSQL(
                                "ALTER TABLE `memos` " +
                                    "ADD COLUMN `isPinned` INTEGER NOT NULL DEFAULT 0",
                            )
                            db.execSQL(
                                "UPDATE `memos` SET `isFavorite` = 1, `isPinned` = 1 " +
                                    "WHERE `id` = 1",
                            )
                        }
                    },
                )
                .build(),
        )
        helper.writableDatabase
        helper.close()
    }

    private fun createVersionEightDatabase() {
        createVersionSevenDatabase()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(8) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = AppDatabase.MIGRATION_7_8.migrate(db)
                    },
                )
                .build(),
        )
        helper.writableDatabase
        helper.close()
    }

    private fun createVersionNineDatabase() {
        createVersionEightDatabase()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(9) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = AppDatabase.MIGRATION_8_9.migrate(db)
                    },
                )
                .build(),
        )
        helper.writableDatabase
        helper.close()
    }

    private fun createVersionTenDatabase() {
        createVersionNineDatabase()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(10) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = AppDatabase.MIGRATION_9_10.migrate(db)
                    },
                )
                .build(),
        )
        helper.writableDatabase.execSQL(
            """
            UPDATE `memo_comments`
            SET `appearanceColor` = 'pink',
                `appearanceSize` = 'large',
                `appearanceEmphasis` = 'strong'
            WHERE `id` = 10
            """.trimIndent(),
        )
        helper.close()
    }

    private fun createVersionElevenDatabase() {
        createVersionTenDatabase()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(11) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = AppDatabase.MIGRATION_10_11.migrate(db)
                    },
                )
                .build(),
        )
        helper.writableDatabase.execSQL(
            "UPDATE `memo_comments` SET `motionSpeed` = 'fast', " +
                "`motionPlacement` = 'bottom' WHERE `id` = 10",
        )
        helper.close()
    }

    private fun createVersionTwelveDatabase() {
        createVersionElevenDatabase()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(12) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = AppDatabase.MIGRATION_11_12.migrate(db)
                    },
                )
                .build(),
        )
        helper.writableDatabase.execSQL(
            """
            INSERT INTO `future_diary_comments`
                (`id`, `diaryEntryId`, `text`, `sealedAt`, `revealAt`, `deliveredAt`,
                 `revealedAt`, `firstPresentedAt`)
            VALUES
                (52, 7, '配達済み', 1300, 1400, 1500, NULL, NULL),
                (53, 7, '初回表示前', 1300, 1400, 1500, 1600, NULL)
            """.trimIndent(),
        )
        helper.close()
    }

    private fun createVersionThirteenDatabase() {
        createVersionTwelveDatabase()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(13) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = AppDatabase.MIGRATION_12_13.migrate(db)
                    },
                )
                .build(),
        )
        helper.writableDatabase.execSQL(
            """
            UPDATE `memo_comments`
            SET `appearanceColor` = 'pink',
                `appearanceSize` = 'large',
                `appearanceEmphasis` = 'strong',
                `motionSpeed` = 'fast',
                `motionPlacement` = 'bottom',
                `motionMode` = 'fixed_top'
            WHERE `id` = 10
            """.trimIndent(),
        )
        helper.close()
    }
}

/**
 * Room 23 (HANDOFF §16.18): the unique index on the diary day goes, a plain (day, createdAt)
 * index comes; no row, state or timestamp changes; a second entry on a day is then possible.
 */
class DiaryJournalMigrationTest {
    private val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "journal-migration-test.db"

    @org.junit.Before
    fun deleteDatabase() {
        context.deleteDatabase(databaseName)
    }

    @org.junit.After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @org.junit.Test
    fun migrationTo23KeepsEveryEntryStateAndTimestampAndAllowsASecondEntryOnTheDay() = runBlocking {
        // A version-22 file with one row in each of the four states, with every timestamp set.
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(22) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            // Only the diary table matters here; Room validates the rest on open,
                            // so the other tables are created by walking the real chain from 3.
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `diary_entries` (
                                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    `diaryDateEpochDay` INTEGER NOT NULL,
                                    `body` TEXT NOT NULL,
                                    `state` TEXT NOT NULL,
                                    `createdAt` INTEGER NOT NULL,
                                    `updatedAt` INTEGER NOT NULL,
                                    `finalizedAt` INTEGER,
                                    `correctionStartedAt` INTEGER,
                                    `lockedAt` INTEGER
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                "CREATE UNIQUE INDEX IF NOT EXISTS `index_diary_entries_diaryDateEpochDay` ON `diary_entries` (`diaryDateEpochDay`)",
                            )
                        }
                        override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        helper.writableDatabase.apply {
            execSQL("INSERT INTO diary_entries VALUES (1, 20600, '下書き', 'DRAFT', 100, 110, NULL, NULL, NULL)")
            execSQL("INSERT INTO diary_entries VALUES (2, 20601, '確定', 'FINALIZED', 200, 210, 205, NULL, NULL)")
            execSQL("INSERT INTO diary_entries VALUES (3, 20602, '修正中', 'CORRECTING', 300, 310, 305, 308, NULL)")
            execSQL("INSERT INTO diary_entries VALUES (4, 20603, 'ロック', 'LOCKED', 400, 410, 405, 408, 410)")
        }
        helper.close()

        val database = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        database.close()
        // The migration itself, on the real file, without Room's schema validation of the other tables.
        val migrated = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(23) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                            AppDatabase.MIGRATION_22_23.migrate(db)
                    },
                )
                .build(),
        )
        val db = migrated.writableDatabase
        val rows = db.query("SELECT id, diaryDateEpochDay, body, state, createdAt, updatedAt, finalizedAt, correctionStartedAt, lockedAt FROM diary_entries ORDER BY id").use { cursor ->
            generateSequence { if (cursor.moveToNext()) (0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) } else null }.toList()
        }
        assertEquals(
            listOf(
                listOf("1", "20600", "下書き", "DRAFT", "100", "110", null, null, null),
                listOf("2", "20601", "確定", "FINALIZED", "200", "210", "205", null, null),
                listOf("3", "20602", "修正中", "CORRECTING", "300", "310", "305", "308", null),
                listOf("4", "20603", "ロック", "LOCKED", "400", "410", "405", "408", "410"),
            ),
            rows,
        )
        val indexNames = db.query("PRAGMA index_list(diary_entries)").use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(cursor.getColumnIndexOrThrow("name")) to cursor.getInt(cursor.getColumnIndexOrThrow("unique")) else null }.toList()
        }
        assertTrue(indexNames.none { it.first == "index_diary_entries_diaryDateEpochDay" })
        assertEquals(0, indexNames.single { it.first == "index_diary_entries_diaryDateEpochDay_createdAt" }.second)
        db.execSQL("INSERT INTO diary_entries VALUES (5, 20600, '同じ日の二本目', 'DRAFT', 500, 510, NULL, NULL, NULL)")
        assertEquals(2, db.query("SELECT COUNT(*) FROM diary_entries WHERE diaryDateEpochDay = 20600").use { it.moveToFirst(); it.getInt(0) })
        migrated.close()
    }
}

/** Room 24: two indexes on memos (createdAt, updatedAt) for the calendar's range queries; nothing else. */
class CalendarIndexMigrationTest {
    private val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "calendar-index-migration-test.db"

    @org.junit.Before
    fun deleteDatabase() {
        context.deleteDatabase(databaseName)
    }

    @org.junit.After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @org.junit.Test
    fun migrationTo24AddsTheTimeIndexesOnMemosAndKeepsEveryRow() = runBlocking {
        // A version-23 memos table with two rows, then the migration alone on the real file.
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(23) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `memos` (
                                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    `title` TEXT NOT NULL, `body` TEXT NOT NULL,
                                    `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                                    `isFavorite` INTEGER NOT NULL, `isPinned` INTEGER NOT NULL,
                                    `archivedAt` INTEGER, `trashedAt` INTEGER, `noteId` INTEGER, `chapterId` INTEGER,
                                    `episodeOrder` INTEGER NOT NULL, `kind` TEXT NOT NULL, `folderId` INTEGER,
                                    `sortIndex` INTEGER NOT NULL
                                )
                                """.trimIndent(),
                            )
                            db.execSQL("INSERT INTO memos VALUES (1, 'a', 'b', 100, 200, 0, 0, NULL, NULL, NULL, NULL, 0, 'memo', NULL, 0)")
                            db.execSQL("INSERT INTO memos VALUES (2, 'c', 'd', 300, 300, 1, 0, NULL, NULL, NULL, NULL, 0, 'outline', NULL, 0)")
                        }
                        override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        helper.writableDatabase; helper.close()
        val migrated = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(24) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                            AppDatabase.MIGRATION_23_24.migrate(db)
                    },
                )
                .build(),
        )
        val db = migrated.writableDatabase
        val indexes = db.query("PRAGMA index_list(memos)").use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(cursor.getColumnIndexOrThrow("name")) else null }.toSet()
        }
        assertTrue("createdAt index", "index_memos_createdAt" in indexes)
        assertTrue("updatedAt index", "index_memos_updatedAt" in indexes)
        val rows = db.query("SELECT id, title, createdAt, updatedAt, kind FROM memos ORDER BY id").use { cursor ->
            generateSequence { if (cursor.moveToNext()) (0 until cursor.columnCount).map { cursor.getString(it) } else null }.toList()
        }
        assertEquals(listOf(listOf("1", "a", "100", "200", "memo"), listOf("2", "c", "300", "300", "outline")), rows)
        migrated.close()
    }
}

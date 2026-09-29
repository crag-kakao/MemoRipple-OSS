package io.github.cragcoffee.memoripple

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.cragcoffee.memoripple.data.AppDatabase
import io.github.cragcoffee.memoripple.data.AttachmentBlobEntity
import io.github.cragcoffee.memoripple.data.AttachmentBlobStore
import io.github.cragcoffee.memoripple.data.AttachmentRepository
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.FutureDiaryCommentEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoPhotoAttachmentEntity
import io.github.cragcoffee.memoripple.data.MemoCommentEntity
import io.github.cragcoffee.memoripple.data.MemoTagCrossRef
import io.github.cragcoffee.memoripple.data.PhotoReorderResult
import io.github.cragcoffee.memoripple.data.TagEntity
import io.github.cragcoffee.memoripple.drive.DriveBackupStateStore
import io.github.cragcoffee.memoripple.drive.DriveBackupState
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DriveTagDirtyTrackingInstrumentationTest {
    private lateinit var database: AppDatabase
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        database.close()
        scope.cancel()
    }

    @Suppress("RestrictedApi")
    private suspend fun refreshDriveDirtyTables() {
        database.invalidationTracker.refresh(*DRIVE_DIRTY_TABLES)
    }

    private suspend fun awaitGeneration(
        stateStore: DriveBackupStateStore,
        step: String,
        predicate: (DriveBackupState) -> Boolean,
    ): DriveBackupState {
        val matched = withTimeoutOrNull<DriveBackupState>(15_000) {
            var current = stateStore.state.first()
            while (!predicate(current)) {
                yield()
                current = stateStore.state.first()
            }
            current
        }
        if (matched != null) return matched
        val finalState = stateStore.state.first()
        throw AssertionError(
            "Timed out after $step: changeGeneration=${finalState.changeGeneration}, " +
                "lastUploadedGeneration=${finalState.lastUploadedGeneration}",
        )
    }

    @Test
    fun tagAndRelationInvalidationsAdvanceDriveDirtyGeneration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "drive-tag-dirty-${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        val stateStore = DriveBackupStateStore(dataStore)
        val observer = object : InvalidationTracker.Observer(DRIVE_DIRTY_TABLES) {
            override fun onInvalidated(tables: Set<String>) {
                runBlocking { stateStore.recordChange() }
            }
        }
        database.invalidationTracker.addObserver(observer)
        try {
            val memoId = database.memoDao().insert(
                MemoEntity(title = "memo", body = "", createdAt = 1, updatedAt = 1),
            )
            refreshDriveDirtyTables()
            val afterMemo = awaitGeneration(stateStore, "memo insert") {
                it.changeGeneration >= 1
            }
            stateStore.recordUploadSuccess("remote", afterMemo.changeGeneration, 1)

            val tagId = database.tagDao().insert(
                TagEntity(name = "仕事", normalizedName = "仕事", createdAt = 2),
            )
            refreshDriveDirtyTables()
            val afterTag = awaitGeneration(stateStore, "tag insert") {
                it.changeGeneration > afterMemo.changeGeneration
            }
            assertTrue(afterTag.isDirty)
            stateStore.recordUploadSuccess("remote", afterTag.changeGeneration, 2)

            database.tagDao().attach(MemoTagCrossRef(memoId, tagId))
            refreshDriveDirtyTables()
            val afterRelation = awaitGeneration(stateStore, "tag attach") {
                it.changeGeneration > afterTag.changeGeneration
            }
            assertTrue(afterRelation.isDirty)
            stateStore.recordUploadSuccess("remote", afterRelation.changeGeneration, 3)

            database.tagDao().rename(tagId, "業務", "業務")
            refreshDriveDirtyTables()
            val afterRename = awaitGeneration(stateStore, "tag rename") {
                it.changeGeneration > afterRelation.changeGeneration
            }
            assertTrue(afterRename.isDirty)
            stateStore.recordUploadSuccess("remote", afterRename.changeGeneration, 4)

            database.tagDao().detach(memoId, tagId)
            refreshDriveDirtyTables()
            val afterDetach = awaitGeneration(stateStore, "tag detach") {
                it.changeGeneration > afterRename.changeGeneration
            }
            assertTrue(afterDetach.isDirty)
            stateStore.recordUploadSuccess("remote", afterDetach.changeGeneration, 5)

            database.tagDao().attach(MemoTagCrossRef(memoId, tagId))
            refreshDriveDirtyTables()
            val afterReattach = awaitGeneration(stateStore, "tag reattach") {
                it.changeGeneration > afterDetach.changeGeneration
            }
            assertTrue(afterReattach.isDirty)
            stateStore.recordUploadSuccess("remote", afterReattach.changeGeneration, 6)

            database.tagDao().delete(requireNotNull(database.tagDao().findById(tagId)))
            refreshDriveDirtyTables()
            val afterDelete = awaitGeneration(stateStore, "tag delete") {
                it.changeGeneration > afterReattach.changeGeneration
            }
            assertTrue(afterDelete.isDirty)
        } finally {
            database.invalidationTracker.removeObserver(observer)
        }
    }

    @Test
    fun photoRelationChangesAreDirtyButBlobGarbageCollectionIsNot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "drive-photo-dirty-${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        val stateStore = DriveBackupStateStore(dataStore)
        val observer = object : InvalidationTracker.Observer(DRIVE_DIRTY_TABLES) {
            override fun onInvalidated(tables: Set<String>) {
                runBlocking { stateStore.recordChange() }
            }
        }
        database.invalidationTracker.addObserver(observer)
        try {
            val memoId = database.memoDao().insert(
                MemoEntity(title = "photo", body = "", createdAt = 1, updatedAt = 1),
            )
            refreshDriveDirtyTables()
            val baseline = awaitGeneration(stateStore, "memo insert") {
                it.changeGeneration >= 1
            }
            stateStore.recordUploadSuccess("remote", baseline.changeGeneration, 1)

            val sha = "a".repeat(64)
            database.attachmentDao().insertBlob(
                AttachmentBlobEntity(sha, "image", "image/png", 1, 1, 1, 2),
            )
            refreshDriveDirtyTables()
            assertTrue(!stateStore.state.first().isDirty)

            val relationId = database.attachmentDao().insertMemoRelation(
                MemoPhotoAttachmentEntity(
                    memoId = memoId,
                    blobSha256 = sha,
                    sortOrder = 0,
                    createdAt = 3,
                ),
            )
            refreshDriveDirtyTables()
            val afterAdd = awaitGeneration(stateStore, "photo attach") {
                it.changeGeneration > baseline.changeGeneration
            }
            assertTrue(afterAdd.isDirty)
            stateStore.recordUploadSuccess("remote", afterAdd.changeGeneration, 4)

            database.attachmentDao().deleteMemoRelation(memoId, relationId)
            refreshDriveDirtyTables()
            val afterDelete = awaitGeneration(stateStore, "photo detach") {
                it.changeGeneration > afterAdd.changeGeneration
            }
            stateStore.recordUploadSuccess("remote", afterDelete.changeGeneration, 5)

            database.attachmentDao().deleteBlobIfUnreferenced(sha)
            refreshDriveDirtyTables()
            assertTrue(!stateStore.state.first().isDirty)
        } finally {
            database.invalidationTracker.removeObserver(observer)
        }
    }


    /** The state once its generation has stopped moving for a beat — every in-flight
     * invalidation tick absorbed, none silently pending. */
    private suspend fun awaitQuietGeneration(
        stateStore: DriveBackupStateStore,
        step: String,
    ): DriveBackupState {
        val settled = withTimeoutOrNull<DriveBackupState>(15_000) {
            while (true) {
                val before = stateStore.state.first().changeGeneration
                kotlinx.coroutines.delay(250)
                val current = stateStore.state.first()
                if (current.changeGeneration == before) return@withTimeoutOrNull current
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
        return settled ?: throw AssertionError("Generation never settled after $step")
    }

    @Test
    fun deleteAndNormalizeIsOneDirtyStepNotAGenerationStorm() = runBlocking {
        // Release audit B03: closing the sortOrder gap rides the same transaction as the
        // delete, so Drive sees one logical change — and nothing keeps ticking after it.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "drive-gap-dirty-${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        val stateStore = DriveBackupStateStore(dataStore)
        val observer = object : InvalidationTracker.Observer(DRIVE_DIRTY_TABLES) {
            override fun onInvalidated(tables: Set<String>) {
                runBlocking { stateStore.recordChange() }
            }
        }
        database.invalidationTracker.addObserver(observer)
        try {
            val memoId = database.memoDao().insert(
                MemoEntity(title = "gap dirty", body = "", createdAt = 1, updatedAt = 1),
            )
            // Three distinct blobs: (memoId, blobSha256) is unique, so a shared sha
            // would silently IGNORE the second and third inserts.
            val relationIds = listOf("b", "c", "d").mapIndexed { index, letter ->
                val sha = letter.repeat(64)
                database.attachmentDao().insertBlob(
                    AttachmentBlobEntity(sha, "image", "image/png", 1, 1, 1, 2),
                )
                database.attachmentDao().insertMemoRelation(
                    MemoPhotoAttachmentEntity(
                        memoId = memoId,
                        blobSha256 = sha,
                        sortOrder = index,
                        createdAt = 3L + index,
                    ),
                )
            }
            refreshDriveDirtyTables()
            awaitGeneration(stateStore, "photo attach") { it.changeGeneration >= 1 }
            // The setup writes land as several asynchronous ticks; let them all arrive
            // before drawing the baseline, or a straggler masquerades as new dirt.
            val baseline = awaitQuietGeneration(stateStore, "setup settle")
            stateStore.recordUploadSuccess("remote", baseline.changeGeneration, 1)

            database.attachmentDao().deleteMemoRelationAndNormalize(memoId, relationIds[1])
            refreshDriveDirtyTables()
            awaitGeneration(stateStore, "delete+normalize") {
                it.changeGeneration > baseline.changeGeneration
            }
            // No storm: the one transaction's ticks settle instead of climbing forever.
            val afterDelete = awaitQuietGeneration(stateStore, "delete settle")
            assertTrue(afterDelete.isDirty)
            assertTrue(
                database.attachmentDao().memoRelations(memoId)
                    .map { it.sortOrder } == listOf(0, 1),
            )

            // One upload settles it: with no further writes, the generation holds still.
            stateStore.recordUploadSuccess("remote", afterDelete.changeGeneration, 2)
            refreshDriveDirtyTables()
            val after = awaitQuietGeneration(stateStore, "post-upload settle")
            assertTrue(!after.isDirty)
            assertTrue(after.changeGeneration == afterDelete.changeGeneration)
        } finally {
            database.invalidationTracker.removeObserver(observer)
        }
    }

    @Test
    fun photoReorderIsDirtyButNoOpAndRejectedOrdersAreNot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = File(
            context.cacheDir,
            "drive-photo-reorder-${System.nanoTime()}.preferences_pb",
        )
        val blobRoot = File(context.cacheDir, "drive-photo-reorder-${System.nanoTime()}")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { preferences }
        val stateStore = DriveBackupStateStore(dataStore)
        val observer = object : InvalidationTracker.Observer(DRIVE_DIRTY_TABLES) {
            override fun onInvalidated(tables: Set<String>) {
                runBlocking { stateStore.recordChange() }
            }
        }
        try {
            val memoId = database.memoDao().insert(
                MemoEntity(title = "reorder", body = "", createdAt = 1, updatedAt = 1),
            )
            listOf("a", "b", "c").forEachIndexed { index, value ->
                val sha = value.repeat(64)
                database.attachmentDao().insertBlob(
                    AttachmentBlobEntity(sha, "image", "image/png", 1, 1, 1, 2L + index),
                )
                database.attachmentDao().insertMemoRelation(
                    MemoPhotoAttachmentEntity(
                        memoId = memoId,
                        blobSha256 = sha,
                        sortOrder = index,
                        createdAt = 10L + index,
                    ),
                )
            }
            refreshDriveDirtyTables()
            database.invalidationTracker.addObserver(observer)
            val baselineGeneration = stateStore.state.first().changeGeneration
            val repository = AttachmentRepository(
                database = database,
                dao = database.attachmentDao(),
                noteDao = database.noteDao(),
                contentResolver = context.contentResolver,
                blobStore = AttachmentBlobStore(blobRoot),
                nowMillis = { 20 },
            )
            val originalIds = repository.observeMemoPhotos(memoId).first().map { it.id }

            assertTrue(
                repository.reorderMemoPhotos(memoId, originalIds.reversed()) ==
                    PhotoReorderResult.CHANGED,
            )
            refreshDriveDirtyTables()
            val reordered = awaitGeneration(stateStore, "photo reorder") {
                it.changeGeneration > baselineGeneration
            }
            assertTrue(reordered.isDirty)
            stateStore.recordUploadSuccess("remote", reordered.changeGeneration, 2)

            assertTrue(
                repository.reorderMemoPhotos(memoId, originalIds.reversed()) ==
                    PhotoReorderResult.UNCHANGED,
            )
            refreshDriveDirtyTables()
            assertTrue(stateStore.state.first().changeGeneration == reordered.changeGeneration)
            assertTrue(!stateStore.state.first().isDirty)

            assertTrue(
                repository.reorderMemoPhotos(memoId, originalIds.dropLast(1)) ==
                    PhotoReorderResult.REJECTED,
            )
            refreshDriveDirtyTables()
            assertTrue(stateStore.state.first().changeGeneration == reordered.changeGeneration)
            assertTrue(!stateStore.state.first().isDirty)
        } finally {
            database.invalidationTracker.removeObserver(observer)
            blobRoot.deleteRecursively()
        }
    }

    @Test
    fun commentExpressionUpdatesAdvanceDriveDirtyGeneration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "drive-comment-dirty-${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        val stateStore = DriveBackupStateStore(dataStore)
        val observer = object : InvalidationTracker.Observer(DRIVE_DIRTY_TABLES) {
            override fun onInvalidated(tables: Set<String>) {
                runBlocking { stateStore.recordChange() }
            }
        }
        database.invalidationTracker.addObserver(observer)
        try {
            val memoId = database.memoDao().insert(
                MemoEntity(title = "memo", body = "", createdAt = 1, updatedAt = 1),
            )
            val comment = database.memoCommentDao().insertAtEnd(
                MemoCommentEntity(memoId = memoId, text = "反応", createdAt = 2),
            )
            refreshDriveDirtyTables()
            val baseline = awaitGeneration(stateStore, "comment insert") {
                it.changeGeneration >= 1
            }
            stateStore.recordUploadSuccess("remote", baseline.changeGeneration, 1)

            assertTrue(
                database.memoCommentDao().updateAppearance(
                    memoId = memoId,
                    commentId = comment.id,
                    color = "red",
                    size = "large",
                    emphasis = "strong",
                ) == 1,
            )
            refreshDriveDirtyTables()
            val updated = awaitGeneration(stateStore, "comment appearance update") {
                it.changeGeneration > baseline.changeGeneration
            }
            assertTrue(updated.isDirty)
            stateStore.recordUploadSuccess("remote", updated.changeGeneration, 2)

            assertTrue(
                database.memoCommentDao().updateMotion(
                    memoId = memoId,
                    commentId = comment.id,
                    mode = "fixed_top",
                    speed = "fast",
                    placement = "top",
                    direction = "ltr",
                    effect = "wave",
                ) == 1,
            )
            refreshDriveDirtyTables()
            val motionUpdated = awaitGeneration(stateStore, "comment motion update") {
                it.changeGeneration > updated.changeGeneration
            }
            assertTrue(motionUpdated.isDirty)
            val storedComment = database.memoCommentDao().findForMemo(memoId).single()
            assertTrue(storedComment.text == comment.text)
            assertTrue(storedComment.createdAt == comment.createdAt)
            assertTrue(storedComment.playbackOrder == comment.playbackOrder)
            assertTrue(storedComment.appearanceColor == "red")
            assertTrue(storedComment.motionSpeed == "fast")
            assertTrue(storedComment.motionPlacement == "top")
            assertTrue(storedComment.motionMode == "fixed_top")
            assertTrue(storedComment.flowDirection == "ltr")
            assertTrue(storedComment.flowEffect == "wave")
            assertTrue(database.memoDao().findById(memoId)?.updatedAt == 1L)
        } finally {
            database.invalidationTracker.removeObserver(observer)
        }
    }

    @Test
    fun futureExpressionInsertAdvancesDriveDirtyGeneration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "drive-future-dirty-${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        val stateStore = DriveBackupStateStore(dataStore)
        val observer = object : InvalidationTracker.Observer(DRIVE_DIRTY_TABLES) {
            override fun onInvalidated(tables: Set<String>) {
                runBlocking { stateStore.recordChange() }
            }
        }
        database.invalidationTracker.addObserver(observer)
        try {
            val diaryId = database.diaryDao().insertIgnoringConflict(
                DiaryEntryEntity(
                    diaryDateEpochDay = 20_000,
                    body = "日記",
                    state = DiaryState.LOCKED,
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
            refreshDriveDirtyTables()
            val baseline = awaitGeneration(stateStore, "diary insert") {
                it.changeGeneration >= 1
            }
            stateStore.recordUploadSuccess("remote", baseline.changeGeneration, 1)

            database.futureDiaryCommentDao().insert(
                FutureDiaryCommentEntity(
                    diaryEntryId = diaryId,
                    text = "未来",
                    sealedAt = 2,
                    revealAt = 3,
                    appearanceColor = "pink",
                    appearanceSize = "large",
                    appearanceEmphasis = "strong",
                    motionMode = "fixed_top",
                ),
            )
            refreshDriveDirtyTables()
            val updated = awaitGeneration(stateStore, "future expression insert") {
                it.changeGeneration > baseline.changeGeneration
            }

            assertTrue(updated.isDirty)
        } finally {
            database.invalidationTracker.removeObserver(observer)
        }
    }
}

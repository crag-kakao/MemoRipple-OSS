package io.github.cragcoffee.memoripple

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.cragcoffee.memoripple.backup.BackupEngine
import io.github.cragcoffee.memoripple.backup.BackupInspectionResult
import io.github.cragcoffee.memoripple.backup.BackupRestoreResult
import io.github.cragcoffee.memoripple.backup.RestoreCandidate
import io.github.cragcoffee.memoripple.data.AppDatabase
import io.github.cragcoffee.memoripple.data.AttachmentBlobStore
import io.github.cragcoffee.memoripple.data.AttachmentRepository
import io.github.cragcoffee.memoripple.data.DiaryContentStore
import io.github.cragcoffee.memoripple.data.DiaryDao
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.DiaryRepository
import io.github.cragcoffee.memoripple.data.FutureDiaryCommentEntity
import io.github.cragcoffee.memoripple.data.FutureDiaryCommentRepository
import io.github.cragcoffee.memoripple.data.MemoContentStore
import io.github.cragcoffee.memoripple.data.MemoRepository
import io.github.cragcoffee.memoripple.data.OutlineStore
import io.github.cragcoffee.memoripple.data.SettingsRepository
import io.github.cragcoffee.memoripple.data.TagRepository
import io.github.cragcoffee.memoripple.data.documents.RepositoryDocumentAccess
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentWriteResult
import io.github.cragcoffee.memoripple.domain.memos.MemoBlock
import io.github.cragcoffee.memoripple.domain.memos.MemoContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The diary's writers and restore never wait on each other forever (the lock order, the diary lock
 * audit 2026-09-25). The wiring is the app's: one [DiaryRepository] behind the AI's document door
 * ([RepositoryDocumentAccess.append] — the AI's APPEND to a journal after Human Confirmation) and
 * behind the real [BackupEngine.restore]; the autosave is the editor's own call
 * ([DiaryRepository.saveBlocks]). The only thing the test adds is a DAO that can pause a named path
 * at its first read of an entry, to line the paths up in the dangerous order — a deadlock is both
 * paths still unfinished after [DEADLOCK_MS]. After every race: no content lost, no append twice,
 * no half-restored state, nothing written to a LOCKED entry, the Future Diary untouched.
 */
@RunWith(AndroidJUnit4::class)
class DiaryLockOrderInstrumentationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "diary-lock-order-test.db"
    private val clock = AtomicLong(1_800_000_000_000L)
    private val zone = ZoneId.of("Asia/Tokyo")
    private val timeProvider = object : TimeProvider {
        override fun nowMillis(): Long = clock.incrementAndGet()
        override fun currentLocalDate(): LocalDate = java.time.Instant.ofEpochMilli(clock.get()).atZone(zone).toLocalDate()
        override fun currentZoneId(): ZoneId = zone
    }

    /** The app's diary DAO, with a pause a named coroutine meets once at its first read of an entry. */
    private class PausableDiaryDao(private val real: DiaryDao) : DiaryDao by real {
        val pauses = ConcurrentHashMap<String, suspend () -> Unit>()
        /** A named coroutine's n-th read of an entry throws — a failure after a write, inside its transaction. */
        val failOnRead = ConcurrentHashMap<String, Int>()
        override suspend fun findById(id: Long): DiaryEntryEntity? {
            val name = currentCoroutineContext()[CoroutineName]?.name
            name?.let { pauses.remove(it) }?.invoke()
            if (name != null) {
                val left = failOnRead.computeIfPresent(name) { _, n -> n - 1 }
                if (left == 0) { failOnRead.remove(name); throw IllegalStateException("injected failure") }
            }
            return real.findById(id)
        }
    }

    private lateinit var database: AppDatabase
    private lateinit var dao: PausableDiaryDao
    private lateinit var content: DiaryContentStore
    private lateinit var diary: DiaryRepository
    private lateinit var future: FutureDiaryCommentRepository
    private lateinit var documents: RepositoryDocumentAccess
    private lateinit var engine: BackupEngine
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var attachmentRoot: File
    private lateinit var scope: CoroutineScope

    private fun wire(db: AppDatabase) {
        database = db
        dao = PausableDiaryDao(db.diaryDao())
        content = DiaryContentStore(db, timeProvider::nowMillis)
        diary = DiaryRepository(dao, timeProvider, db.attachmentDao(), content)
        future = FutureDiaryCommentRepository(db.futureDiaryCommentDao(), db.diaryDao(), timeProvider)
        val memoContent = MemoContentStore(db)
        val outlines = OutlineStore(db)
        documents = RepositoryDocumentAccess(db, MemoRepository(db.memoDao(), timeProvider, memoContent, outlines), diary, TagRepository(db.tagDao()), timeProvider)
        val blobs = AttachmentBlobStore(attachmentRoot)
        val attachments = AttachmentRepository(db, db.attachmentDao(), db.noteDao(), context.contentResolver, blobs, timeProvider::nowMillis, content = memoContent, diaryContent = content)
        engine = BackupEngine(
            database = db,
            backupDao = db.backupDao(),
            settingsRepository = SettingsRepository(dataStore),
            diaryRepository = diary,
            futureDiaryCommentRepository = future,
            timeProvider = timeProvider,
            appVersionName = "instrumentation",
            appVersionCode = 1,
            blobStore = blobs,
            attachmentRepository = attachments,
            cacheDirectory = context.cacheDir,
        )
    }

    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()

    @Before
    fun setUp() {
        context.deleteDatabase(name)
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settingsFile = File(context.cacheDir, "diary-lock-${System.nanoTime()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) { settingsFile }
        attachmentRoot = File(context.filesDir, "diary-lock-${System.nanoTime()}")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        wire(open())
    }

    @After
    fun tearDown() {
        scope.cancel()
        runCatching { database.close() }
        context.deleteDatabase(name)
        dataStoreScope.cancel()
        attachmentRoot.deleteRecursively()
    }

    // --- the world: one journal, a sealed Future Diary comment, a backup of that, then a change ---

    private data class World(val entryId: Long, val candidate: RestoreCandidate, val atBackup: Tables)

    private data class Tables(
        val entries: List<DiaryEntryEntity>,
        val blocks: List<Pair<Long, String>>,
        val future: List<FutureDiaryCommentEntity>,
    )

    private suspend fun tables() = Tables(
        database.backupDao().readDiaryEntries(),
        database.backupDao().readDiaryContentBlocks().map { it.diaryEntryId to "${it.position}:${it.type}:${it.text}:${it.photoAttachmentId}" },
        database.backupDao().readFutureDiaryComments(),
    )

    private suspend fun world(): World {
        val entry = diary.createEntry(timeProvider.currentLocalDate().toEpochDay())
        diary.saveBody(entry.id, "朝の散歩", releaseIfBlank = false)
        database.futureDiaryCommentDao().insert(
            FutureDiaryCommentEntity(diaryEntryId = entry.id, text = "一年後の自分へ", sealedAt = clock.get(), revealAt = clock.get() + 365L * 86_400_000L),
        )
        val prepared = engine.prepareBackup()
        val candidate = (engine.inspect(prepared.file) as BackupInspectionResult.Ready).candidate
        val atBackup = tables()
        // After the backup the journal moves on, so a restore visibly changes it back.
        diary.saveBody(entry.id, "朝の散歩\n昼の会議", releaseIfBlank = false)
        return World(entry.id, candidate, atBackup)
    }

    private fun ref(id: Long) = DocumentRef(DocumentKind.JOURNAL, id)
    private suspend fun entry(id: Long) = database.diaryDao().findById(id)
    private fun diaryLocked(): Boolean =
        (DiaryRepository::class.java.getDeclaredField("operationMutex").apply { isAccessible = true }.get(diary) as Mutex).isLocked

    /** Both finish, or the test fails as a deadlock (and the stuck paths are cancelled). */
    private suspend fun <T> bothFinish(what: String, vararg jobs: Deferred<T>): List<T> =
        withTimeoutOrNull(DEADLOCK_MS) { jobs.toList().awaitAll() } ?: run {
            jobs.forEach { it.cancel() }
            fail("deadlock: $what — still waiting after ${DEADLOCK_MS} ms")
            error("unreachable")
        }

    private suspend fun waitFor(what: String, check: () -> Boolean) {
        withTimeoutOrNull(5_000) { while (!check()) delay(10) } ?: fail("never happened: $what")
    }

    /** The stored body is always its blocks' projection — never a half-written pair. */
    private suspend fun assertWhole(entryId: Long) {
        val body = entry(entryId)!!.body
        assertEquals("body = the blocks' projection", MemoContent.projection(content.materialize(entryId)), body)
    }

    private fun assertOnce(text: String, body: String) =
        assertTrue("「$text」 at most once in 「$body」", body.split(text).size - 1 <= 1)

    // --- 1. AI → restore: the AI's append is inside its transaction when restore starts ---

    @Test
    fun anAiAppendAlreadyWritingAndARestoreBothFinishAndTheRestoreIsWhole() = runBlocking {
        val w = world()
        val version = entry(w.entryId)!!.updatedAt
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        dao.pauses["ai"] = { inside.complete(Unit); release.await() }
        val ai = scope.async(CoroutineName("ai")) { documents.append(ref(w.entryId), "AIの追記", version) as Any }
        inside.await()
        val restore = scope.async(CoroutineName("restore")) { engine.restore(w.candidate) as Any }
        // Restore has taken the diary lock (or the append holds it already), then the append goes on.
        waitFor("the diary lock is held") { diaryLocked() }
        release.complete(Unit)
        val (appended, restored) = bothFinish("AI append (in its transaction) vs restore", ai, restore)
        assertEquals(BackupRestoreResult.Success, restored)
        assertTrue("the append wrote before the restore: $appended", appended is DocumentWriteResult.Done)
        // The restore is whole: exactly the backup, the append replaced with everything else.
        assertEquals(w.atBackup, tables())
    }

    // --- 2. restore → AI: the restore holds the diary lock when the AI appends ---

    @Test
    fun aRestoreUnderWayAndAnAiAppendBothFinishWithNothingHalfDone() = runBlocking {
        val w = world()
        val version = entry(w.entryId)!!.updatedAt
        val restore = scope.async(CoroutineName("restore")) { engine.restore(w.candidate) as Any }
        waitFor("restore holds the diary lock") { diaryLocked() || restore.isCompleted }
        val ai = scope.async(CoroutineName("ai")) { documents.append(ref(w.entryId), "AIの追記", version) as Any }
        val (restored, appended) = bothFinish("restore (holding the diary lock) vs AI append", restore, ai)
        assertEquals(BackupRestoreResult.Success, restored)
        // The restore landed first; the version the append held belongs to the journal it replaced,
        // so the append is a conflict and writes nothing — never onto the restored journal.
        assertEquals(DocumentWriteResult.Conflict, appended)
        assertEquals("the restore is whole, nothing appended", w.atBackup, tables())
    }

    // --- 3. autosave with an AI append: the editor's save holds the diary lock ---

    @Test
    fun theEditorsAutosaveAndAnAiAppendBothFinishAndNothingIsLostOrDoubled() = runBlocking {
        val w = world()
        val version = entry(w.entryId)!!.updatedAt
        val textId = content.materialize(w.entryId).filterIsInstance<MemoBlock.Text>().first().id
        val autosaveInside = CompletableDeferred<Unit>()
        val aiInside = CompletableDeferred<Unit>()
        dao.pauses["autosave"] = { autosaveInside.complete(Unit); withTimeoutOrNull(3_000) { aiInside.await() } }
        dao.pauses["ai"] = { aiInside.complete(Unit) }
        val futureBefore = database.backupDao().readFutureDiaryComments()
        val autosave = scope.async(CoroutineName("autosave")) { diary.saveBlocks(w.entryId, mapOf(textId to "朝の散歩\n昼の会議\n夜の読書"), releaseIfBlank = false) as Any }
        autosaveInside.await()
        val ai = scope.async(CoroutineName("ai")) { documents.append(ref(w.entryId), "AIの追記", version) as Any }
        val (_, appended) = bothFinish("autosave (holding the diary lock) vs AI append", autosave, ai)
        val body = entry(w.entryId)!!.body
        assertTrue("the autosave's words are kept: $body", body.startsWith("朝の散歩\n昼の会議\n夜の読書"))
        assertOnce("AIの追記", body)
        when (appended) {
            is DocumentWriteResult.Done -> assertTrue(body.endsWith("AIの追記"))
            is DocumentWriteResult.Conflict -> assertTrue("a conflict writes nothing", !body.contains("AIの追記"))
            else -> fail("unexpected: $appended")
        }
        assertEquals("the Future Diary is untouched", futureBefore, database.backupDao().readFutureDiaryComments())
    }

    // --- 4. a LOCKED journal takes no write, whoever races ---

    @Test
    fun aLockedJournalTakesNoWriteFromTheAiOrTheAutosave() = runBlocking {
        val w = world()
        val locked = entry(w.entryId)!!.copy(state = DiaryState.LOCKED)
        database.diaryDao().update(locked)
        val before = tables()
        val textId = content.materialize(w.entryId).filterIsInstance<MemoBlock.Text>().first().id
        val ai = scope.async(CoroutineName("ai")) { documents.append(ref(w.entryId), "AIの追記", locked.updatedAt) as Any }
        val autosave = scope.async(CoroutineName("autosave")) { diary.saveBlocks(w.entryId, mapOf(textId to "書き換え"), releaseIfBlank = false) as Any }
        val (appended, _) = bothFinish("AI append vs autosave on a LOCKED journal", ai, autosave)
        assertEquals(DocumentWriteResult.ReadOnly, appended)
        assertEquals("nothing written to a LOCKED journal", before, tables())
    }

    // --- 5. failure / rollback: a restore given up while it waits leaves nothing half-done ---

    @Test
    fun aRestoreCancelledWhileWaitingLeavesNothingHalfDoneAndTheAppendStandsOnce() = runBlocking {
        val w = world()
        val version = entry(w.entryId)!!.updatedAt
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        dao.pauses["ai"] = { inside.complete(Unit); release.await() }
        val ai = scope.async(CoroutineName("ai")) { documents.append(ref(w.entryId), "AIの追記", version) as Any }
        inside.await()
        val restore = scope.async(CoroutineName("restore")) { engine.restore(w.candidate) as Any }
        delay(300)
        restore.cancel()
        release.complete(Unit)
        val (appended) = bothFinish("AI append after a cancelled restore", ai)
        assertTrue(appended is DocumentWriteResult.Done)
        val body = entry(w.entryId)!!.body
        assertEquals("朝の散歩\n昼の会議\nAIの追記", body)
        assertOnce("AIの追記", body)
        assertEquals("the Future Diary is untouched", w.atBackup.future, database.backupDao().readFutureDiaryComments())
    }

    // --- 6. process recreation: after a race, a new process reads a whole state and writes on ---

    @Test
    fun afterARaceANewProcessReadsTheWholeStateAndAppendsOnce() = runBlocking {
        val w = world()
        val version = entry(w.entryId)!!.updatedAt
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        dao.pauses["ai"] = { inside.complete(Unit); release.await() }
        val ai = scope.async(CoroutineName("ai")) { documents.append(ref(w.entryId), "AIの追記", version) as Any }
        inside.await()
        val restore = scope.async(CoroutineName("restore")) { engine.restore(w.candidate) as Any }
        waitFor("the diary lock is held") { diaryLocked() }
        release.complete(Unit)
        bothFinish("AI append vs restore before the process goes", ai, restore)
        // The process goes; a new one opens the same file with new locks.
        database.close()
        wire(open())
        assertEquals("the restored state, whole", w.atBackup, tables())
        val again = entry(w.entryId)!!
        val result = documents.append(ref(w.entryId), "新しいプロセスの追記", again.updatedAt)
        assertTrue(result is DocumentWriteResult.Done)
        assertEquals("朝の散歩\n新しいプロセスの追記", entry(w.entryId)!!.body)
    }

    // --- added after the fix (2026-09-25): the order holds under every mix ---

    @Test
    fun twoAiAppendsAtOnceFinishOneWritesOnceAndTheOtherIsAConflict() = runBlocking {
        val w = world()
        val version = entry(w.entryId)!!.updatedAt
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        dao.pauses["ai1"] = { inside.complete(Unit); release.await() }
        val first = scope.async(CoroutineName("ai1")) { documents.append(ref(w.entryId), "一つ目の追記", version) as Any }
        inside.await()
        val second = scope.async(CoroutineName("ai2")) { documents.append(ref(w.entryId), "二つ目の追記", version) as Any }
        delay(100)
        release.complete(Unit)
        val (a, b) = bothFinish("two AI appends", first, second)
        assertTrue(a is DocumentWriteResult.Done)
        assertEquals("the second held the same version: a conflict, nothing written", DocumentWriteResult.Conflict, b)
        val body = entry(w.entryId)!!.body
        assertEquals("朝の散歩\n昼の会議\n一つ目の追記", body)
        assertWhole(w.entryId)
    }

    @Test
    fun autosaveAndAiAppendInManyRoundsAlwaysFinish() = runBlocking {
        val w = world()
        repeat(20) { round ->
            val version = entry(w.entryId)!!.updatedAt
            val textId = content.materialize(w.entryId).filterIsInstance<MemoBlock.Text>().first().id
            val current = entry(w.entryId)!!.body
            val autosave = scope.async(CoroutineName("autosave$round")) { diary.saveBlocks(w.entryId, mapOf(textId to "$current\n保存$round"), releaseIfBlank = false) as Any }
            val ai = scope.async(CoroutineName("ai$round")) { documents.append(ref(w.entryId), "追記$round", version) as Any }
            val (_, appended) = bothFinish("round $round", autosave, ai)
            val body = entry(w.entryId)!!.body
            assertOnce("追記$round", body)
            assertTrue("round $round: $appended", appended is DocumentWriteResult.Done || appended is DocumentWriteResult.Conflict)
            assertWhole(w.entryId)
        }
    }

    @Test
    fun restoreAutosaveAndAiAppendTogetherFinishInAValidState() = runBlocking {
        val w = world()
        val version = entry(w.entryId)!!.updatedAt
        val textId = content.materialize(w.entryId).filterIsInstance<MemoBlock.Text>().first().id
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        dao.pauses["ai"] = { inside.complete(Unit); release.await() }
        val ai = scope.async(CoroutineName("ai")) { documents.append(ref(w.entryId), "AIの追記", version) as Any }
        inside.await()
        val autosave = scope.async(CoroutineName("autosave")) { diary.saveBlocks(w.entryId, mapOf(textId to "自動保存の文"), releaseIfBlank = false) as Any }
        val restore = scope.async(CoroutineName("restore")) { engine.restore(w.candidate) as Any }
        delay(200)
        release.complete(Unit)
        val (appended, _, restored) = bothFinish("AI append + autosave + restore", ai, autosave, restore)
        assertTrue(appended is DocumentWriteResult.Done)
        assertEquals(BackupRestoreResult.Success, restored)
        val body = entry(w.entryId)!!.body
        // Whichever of the autosave and the restore went last, the state is one of theirs, whole.
        assertTrue("a valid final body: $body", body == "朝の散歩" || body == "自動保存の文")
        assertOnce("AIの追記", body)
        assertWhole(w.entryId)
        assertEquals("the Future Diary is the backup's", w.atBackup.future, database.backupDao().readFutureDiaryComments())
    }

    @Test
    fun aStaleVersionIsAConflictAndALockedJournalIsReadOnlyNothingWritten() = runBlocking {
        val w = world()
        val stale = w.atBackup.entries.single { it.id == w.entryId }.updatedAt
        val before = tables()
        assertEquals(DocumentWriteResult.Conflict, documents.append(ref(w.entryId), "古い版からの追記", stale))
        assertEquals(before, tables())
        val locked = entry(w.entryId)!!.copy(state = DiaryState.LOCKED)
        database.diaryDao().update(locked)
        val lockedTables = tables()
        assertEquals(DocumentWriteResult.ReadOnly, documents.append(ref(w.entryId), "封印後の追記", locked.updatedAt))
        assertEquals("nothing written to a LOCKED journal", lockedTables, tables())
        assertEquals(DocumentWriteResult.NotFound, documents.append(ref(9_999), "無い日記", 1))
    }

    @Test
    fun aFailureAfterTheWriteInsideTheTransactionRollsItAllBackAndReleasesTheLock() = runBlocking {
        val w = world()
        val version = entry(w.entryId)!!.updatedAt
        val before = tables()
        // The append's second read comes after its write, inside the same transaction.
        dao.failOnRead["ai"] = 2
        val failed = scope.async(CoroutineName("ai")) { runCatching { documents.append(ref(w.entryId), "失敗する追記", version) } }
        val (outcome) = bothFinish("a failing append", failed)
        assertTrue("the failure reaches the caller", outcome.isFailure)
        assertEquals("rolled back: nothing of the write is left", before, tables())
        // The lock was released: the next writer goes through.
        val next = documents.append(ref(w.entryId), "次の追記", version)
        assertTrue(next is DocumentWriteResult.Done)
        assertEquals("朝の散歩\n昼の会議\n次の追記", entry(w.entryId)!!.body)
        assertWhole(w.entryId)
    }

    @Test
    fun aCancelledAppendReleasesTheLockAndWritesNothing() = runBlocking {
        val w = world()
        val version = entry(w.entryId)!!.updatedAt
        val before = tables()
        val inside = CompletableDeferred<Unit>()
        dao.pauses["ai"] = { inside.complete(Unit); CompletableDeferred<Unit>().await() }
        val ai = scope.async(CoroutineName("ai")) { documents.append(ref(w.entryId), "取り消される追記", version) }
        inside.await()
        ai.cancel()
        runCatching { ai.await() }
        assertEquals("nothing written", before, tables())
        val textId = content.materialize(w.entryId).filterIsInstance<MemoBlock.Text>().first().id
        val autosave = scope.async(CoroutineName("autosave")) { diary.saveBlocks(w.entryId, mapOf(textId to "取り消しの後の保存"), releaseIfBlank = false) as Any }
        bothFinish("autosave after a cancelled append", autosave)
        assertEquals("取り消しの後の保存", entry(w.entryId)!!.body)
    }

    @Test
    fun anAppendInterruptedByTheProcessGoingLeavesAFileThatReopensWhole() = runBlocking {
        val w = world()
        val version = entry(w.entryId)!!.updatedAt
        val before = tables()
        val inside = CompletableDeferred<Unit>()
        dao.pauses["ai"] = { inside.complete(Unit); CompletableDeferred<Unit>().await() }
        val ai = scope.async(CoroutineName("ai")) { documents.append(ref(w.entryId), "途中で終わる追記", version) }
        inside.await()
        // The process goes mid-operation: the work is cut off and the file is closed.
        ai.cancel()
        runCatching { ai.await() }
        database.close()
        wire(open())
        assertEquals("the file reopens whole, nothing half-written", before, tables())
        val result = documents.append(ref(w.entryId), "再起動後の追記", entry(w.entryId)!!.updatedAt)
        assertTrue(result is DocumentWriteResult.Done)
        assertEquals("朝の散歩\n昼の会議\n再起動後の追記", entry(w.entryId)!!.body)
        assertWhole(w.entryId)
    }

    private companion object {
        const val DEADLOCK_MS = 10_000L
    }
}

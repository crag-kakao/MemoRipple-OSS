package io.github.cragcoffee.memoripple.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoRepositoryTest {
    private val dao = FakeMemoDao()
    private val clock = FixedMemoTimeProvider(1_000)
    private val repository = MemoRepository(dao, clock)

    @Test
    fun saveCreatesAndThenUpdatesTheSameMemo() = runBlocking {
        val created = repository.save(null, "タイトル", "本文", 100)
        val updated = repository.save(created, "新タイトル", "新本文", 200)

        assertEquals(1L, created?.id)
        assertEquals(created?.id, updated?.id)
        assertEquals(100L, updated?.createdAt)
        assertEquals(200L, updated?.updatedAt)
        assertEquals(listOf(updated), dao.values.value)
    }

    @Test
    fun anOutlineExistsAsSoonAsItIsMadeAndKeepsItsKindThroughEverySave() = runBlocking {
        val outline = repository.createOutline(100)
        val written = repository.save(outline, "", "- 一", 200)

        assertEquals("outline", outline.kind)
        assertEquals("", outline.body)
        assertEquals("outline", written?.kind)
        assertEquals("- 一", written?.body)
        assertEquals(listOf(outline.id), repository.observeOutlineDocuments("").first().map { it.id })
        assertTrue(repository.observeStandaloneMemos("").first().isEmpty())

        // A memo saved the ordinary way is a memo, whatever its body looks like.
        val memo = requireNotNull(repository.save(null, "", "- 一\n  - 二", 300))
        assertEquals("memo", memo.kind)
        assertEquals(listOf(memo.id), repository.observeStandaloneMemos("").first().map { it.id })
        assertEquals(listOf(outline.id), repository.observeOutlineDocuments("").first().map { it.id })
    }

    @Test
    fun emptyNewMemoIsNotStoredAndClearedExistingMemoIsPreserved() = runBlocking {
        assertNull(repository.save(null, "", "", 100))
        val created = repository.save(null, "", "本文", 200)
        val cleared = repository.save(created, " ", "\n", 300)
        assertEquals(created?.id, cleared?.id)
        assertEquals(1, dao.values.value.size)
    }

    @Test
    fun favoriteAndPinAreReactiveAndNeverChangeContentTimestamps() = runBlocking {
        val created = requireNotNull(repository.save(null, "大切", "本文", 100))

        assertTrue(repository.setFavorite(created.id, true))
        assertTrue(repository.setPinned(created.id, true))

        val organized = repository.observeMemos("").first().single()
        assertTrue(organized.isFavorite)
        assertTrue(organized.isPinned)
        assertEquals(100L, organized.createdAt)
        assertEquals(100L, organized.updatedAt)

        assertTrue(repository.setFavorite(created.id, false))
        assertTrue(repository.setPinned(created.id, false))
        val cleared = repository.findById(created.id)
        assertFalse(requireNotNull(cleared).isFavorite)
        assertFalse(cleared.isPinned)
        assertEquals(100L, cleared.createdAt)
        assertEquals(100L, cleared.updatedAt)
    }

    @Test
    fun contentAutosavePreservesOrganizationMetadata() = runBlocking {
        val created = requireNotNull(repository.save(null, "Title", "Body", 100))
        repository.setFavorite(created.id, true)
        repository.setPinned(created.id, true)

        val updated = repository.save(created, "Changed", "Changed body", 200)

        assertTrue(requireNotNull(updated).isFavorite)
        assertTrue(updated.isPinned)
        assertEquals(100L, updated.createdAt)
        assertEquals(200L, updated.updatedAt)
    }

    @Test
    fun lifecycleTransitionsPreserveTimestampsAndRestoreArchiveOrigin() = runBlocking {
        val created = requireNotNull(repository.save(null, "Title", "Body", 100))
        assertTrue(repository.archive(created.id))
        val archived = requireNotNull(repository.findById(created.id))
        assertEquals(1_000L, archived.archivedAt)
        assertEquals(100L, archived.updatedAt)
        clock.now = 2_000
        assertTrue(repository.moveToTrash(created.id))
        val trashed = requireNotNull(repository.findById(created.id))
        assertEquals(1_000L, trashed.archivedAt)
        assertEquals(2_000L, trashed.trashedAt)
        assertEquals(TrashRestoreDestination.ARCHIVED, repository.restoreFromTrash(created.id))
        assertEquals(1_000L, repository.findById(created.id)?.archivedAt)
        assertNull(repository.findById(created.id)?.trashedAt)
    }

    @Test
    fun permanentDeleteRefusesNonTrashedMemo() = runBlocking {
        val created = requireNotNull(repository.save(null, "Title", "Body", 100))
        assertFalse(repository.deletePermanently(created.id))
        assertTrue(repository.moveToTrash(created.id))
        assertTrue(repository.deletePermanently(created.id))
        assertNull(repository.findById(created.id))
    }

    @Test
    fun bulkFavoriteAndPinUseExplicitEndStateAndKeepTimestamps() = runBlocking {
        val first = requireNotNull(repository.save(null, "first", "", 100))
        val second = requireNotNull(repository.save(null, "second", "", 200))
        repository.setFavorite(second.id, true)
        repository.setPinned(first.id, true)

        assertEquals(2, repository.setFavoriteForMemos(setOf(first.id, second.id), true).count)
        assertEquals(2, repository.setPinnedForMemos(setOf(first.id, second.id), false).count)

        val values = dao.values.value.associateBy(MemoEntity::id)
        assertTrue(requireNotNull(values[first.id]).isFavorite)
        assertTrue(requireNotNull(values[second.id]).isFavorite)
        assertFalse(values[first.id]!!.isPinned)
        assertFalse(values[second.id]!!.isPinned)
        assertEquals(100L, values[first.id]?.updatedAt)
        assertEquals(200L, values[second.id]?.updatedAt)
    }

    @Test
    fun bulkLifecycleRevalidatesActiveIdsAndGuardedUndoDoesNotOverwriteLaterState() = runBlocking {
        val first = requireNotNull(repository.save(null, "first", "", 100))
        val second = requireNotNull(repository.save(null, "second", "", 200))
        repository.archive(second.id)
        clock.now = 2_000

        val change = repository.archiveMemos(setOf(first.id, second.id, 999))

        assertEquals(setOf(first.id), change.memoIds)
        assertEquals(100L, repository.findById(first.id)?.updatedAt)
        dao.moveToTrash(first.id, 3_000)
        assertEquals(0, repository.undoArchiveMemos(change))
        assertEquals(3_000L, repository.findById(first.id)?.trashedAt)
    }

    @Test
    fun bulkTrashAndUndoRestoreOnlyTheActiveOperation() = runBlocking {
        val first = requireNotNull(repository.save(null, "first", "", 100))
        val second = requireNotNull(repository.save(null, "second", "", 200))

        val change = repository.moveMemosToTrash(setOf(first.id, second.id))

        assertEquals(2, change.count)
        assertEquals(2, repository.undoMoveMemosToTrash(change))
        assertNull(repository.findById(first.id)?.trashedAt)
        assertNull(repository.findById(second.id)?.trashedAt)
        assertEquals(100L, repository.findById(first.id)?.updatedAt)
        assertEquals(200L, repository.findById(second.id)?.updatedAt)
    }

    @Test
    fun bulkUndoCannotRevertALaterSameClockLifecycleOperation() = runBlocking {
        val memo = requireNotNull(repository.save(null, "memo", "", 100))
        val firstArchive = repository.archiveMemos(setOf(memo.id))
        assertEquals(1, repository.undoArchiveMemos(firstArchive))
        assertTrue(repository.archive(memo.id))

        assertEquals(0, repository.undoArchiveMemos(firstArchive))
        assertTrue(repository.findById(memo.id)?.archivedAt != null)
        assertTrue(repository.findById(memo.id)!!.archivedAt!! > firstArchive.changedAt)
    }

    @Test
    fun reorderWritesTheGivenOrderAsSortIndexesAndTouchesNothingElse() = runBlocking {
        val a = repository.save(null, "a", "1", now = 10)!!
        val b = repository.save(null, "b", "2", now = 20)!!
        val c = repository.save(null, "c", "3", now = 30)!!

        repository.reorder(listOf(c.id, a.id, b.id))

        assertEquals(listOf(0, 1, 2), listOf(c.id, a.id, b.id).map { dao.findById(it)!!.sortIndex })
        assertEquals("2", dao.findById(b.id)!!.body)
        assertEquals(20, dao.findById(b.id)!!.updatedAt)
    }
}

private class FakeMemoDao : MemoDao {
    val values = MutableStateFlow<List<MemoEntity>>(emptyList())
    private var nextId = 1L

    override fun observeMemos(query: String): Flow<List<MemoEntity>> = values

    override fun observeMemo(id: Long): Flow<MemoEntity?> =
        values.map { list -> list.firstOrNull { it.id == id } }
    override fun observeStandaloneMemos(query: String): Flow<List<MemoEntity>> =
        values.map { list -> list.filter { it.kind == "memo" } }
    override fun observeOutlineDocuments(query: String): Flow<List<MemoEntity>> =
        values.map { list -> list.filter { it.kind == "outline" } }
    override fun observeArchivedMemos(query: String): Flow<List<MemoEntity>> = values
    override fun observeTrashedMemos(query: String): Flow<List<MemoEntity>> = values

    override suspend fun findById(id: Long): MemoEntity? = values.value.firstOrNull { it.id == id }

    override suspend fun insert(memo: MemoEntity): Long {
        val id = nextId++
        values.value += memo.copy(id = id)
        return id
    }

    override suspend fun updateContent(
        memoId: Long,
        title: String,
        body: String,
        updatedAt: Long,
    ): Int = updateMatching(memoId) { memo ->
        memo.copy(title = title, body = body, updatedAt = updatedAt)
    }

    override suspend fun setFavorite(memoId: Long, favorite: Boolean): Int =
        updateMatching(memoId) { it.copy(isFavorite = favorite) }

    override suspend fun setPinned(memoId: Long, pinned: Boolean): Int =
        updateMatching(memoId) { it.copy(isPinned = pinned) }

    override suspend fun allIds(): List<Long> = values.value.map(MemoEntity::id)

    override fun observeTouchedBetween(startInclusive: Long, endExclusive: Long): Flow<List<MemoEntity>> =
        values.map { list ->
            list.filter { it.archivedAt == null && it.trashedAt == null && (it.createdAt in startInclusive until endExclusive || it.updatedAt in startInclusive until endExclusive) }
        }

    override suspend fun findActiveIds(memoIds: List<Long>): List<Long> = values.value
        .filter { it.id in memoIds && it.archivedAt == null && it.trashedAt == null }
        .map(MemoEntity::id)

    override suspend fun setFavoriteForActiveMemos(memoIds: List<Long>, favorite: Boolean): Int =
        updateAll(memoIds) { it.copy(isFavorite = favorite) }

    override suspend fun setPinnedForActiveMemos(memoIds: List<Long>, pinned: Boolean): Int =
        updateAll(memoIds) { it.copy(isPinned = pinned) }

    override suspend fun archive(memoId: Long, archivedAt: Long): Int =
        updateMatching(memoId) { it.copy(archivedAt = archivedAt, trashedAt = null) }

    override suspend fun archiveActiveMemos(memoIds: List<Long>, archivedAt: Long): Int =
        updateAll(memoIds) { it.copy(archivedAt = archivedAt, trashedAt = null) }

    override suspend fun undoArchiveMemos(
        memoIds: List<Long>,
        expectedArchivedAt: Long,
    ): Int = updateAll(memoIds) {
        if (it.archivedAt == expectedArchivedAt && it.trashedAt == null) it.copy(archivedAt = null) else it
    }

    override suspend fun unarchive(memoId: Long): Int =
        updateMatching(memoId) { it.copy(archivedAt = null, trashedAt = null) }

    override suspend fun setFolder(memoId: Long, folderId: Long?): Int =
        updateMatching(memoId) { it.copy(folderId = folderId) }

    override suspend fun setSortIndex(memoId: Long, index: Int): Int =
        updateMatching(memoId) { it.copy(sortIndex = index) }

    override suspend fun moveAllFromFolder(from: Long, to: Long?): Int {
        val ids = values.value.filter { it.folderId == from }.map(MemoEntity::id)
        return updateAll(ids) { it.copy(folderId = to) }
    }

    override suspend fun moveToTrash(memoId: Long, trashedAt: Long): Int =
        updateMatching(memoId) { it.copy(trashedAt = trashedAt) }

    override suspend fun moveActiveMemosToTrash(memoIds: List<Long>, trashedAt: Long): Int =
        updateAll(memoIds) { it.copy(trashedAt = trashedAt) }

    override suspend fun undoMoveMemosToTrash(
        memoIds: List<Long>,
        expectedTrashedAt: Long,
    ): Int = updateAll(memoIds) {
        if (it.trashedAt == expectedTrashedAt && it.archivedAt == null) it.copy(trashedAt = null) else it
    }

    override suspend fun restoreFromTrash(memoId: Long): Int =
        updateMatching(memoId) { it.copy(trashedAt = null) }

    override suspend fun deletePermanently(memoId: Long): Int {
        val before = values.value.size
        values.value = values.value.filterNot { it.id == memoId && it.trashedAt != null }
        return before - values.value.size
    }

    override suspend fun deleteAllTrashed(): Int {
        val before = values.value.size
        values.value = values.value.filterNot { it.trashedAt != null }
        return before - values.value.size
    }

    private fun updateMatching(memoId: Long, transform: (MemoEntity) -> MemoEntity): Int {
        var updated = 0
        values.value = values.value.map { memo ->
            if (memo.id == memoId) {
                updated = 1
                transform(memo)
            } else {
                memo
            }
        }
        return updated
    }

    private fun updateAll(memoIds: List<Long>, transform: (MemoEntity) -> MemoEntity): Int {
        var updated = 0
        values.value = values.value.map { memo ->
            if (memo.id in memoIds) {
                val transformed = transform(memo)
                if (transformed != memo) updated++
                transformed
            } else {
                memo
            }
        }
        return updated
    }
}

private class FixedMemoTimeProvider(var now: Long) : TimeProvider {
    override fun nowMillis(): Long = now
    override fun currentLocalDate(): LocalDate = LocalDate.of(2026, 1, 1)
    override fun currentZoneId(): ZoneId = ZoneId.of("UTC")
}

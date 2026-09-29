package io.github.cragcoffee.memoripple.data

import io.github.cragcoffee.memoripple.domain.comments.CommentAppearance
import io.github.cragcoffee.memoripple.domain.comments.CommentColorRole
import io.github.cragcoffee.memoripple.domain.comments.CommentEmphasisRole
import io.github.cragcoffee.memoripple.domain.comments.CommentFlowDirection
import io.github.cragcoffee.memoripple.domain.comments.CommentFlowEffect
import io.github.cragcoffee.memoripple.domain.comments.CommentMotion
import io.github.cragcoffee.memoripple.domain.comments.CommentMotionMode
import io.github.cragcoffee.memoripple.domain.comments.CommentPlacementRole
import io.github.cragcoffee.memoripple.domain.comments.CommentSizeRole
import io.github.cragcoffee.memoripple.domain.comments.CommentSpeedRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoCommentRepositoryTest {
    private val dao = FakeMemoCommentDao()
    private val repository = MemoCommentRepository(dao)

    @Test
    fun addStoresTrimmedCommentWithMemoTimestampAndFirstOrder() = runBlocking {
        val saved = repository.add(memoId = 7, text = "  この設定まだ好き  ", now = 100)

        assertEquals(
            MemoCommentEntity(1, 7, "この設定まだ好き", 100, playbackOrder = 0),
            saved,
        )
        assertEquals(listOf(saved), repository.observeForMemo(7).first())
    }

    @Test
    fun addIgnoresBlankText() = runBlocking {
        assertNull(repository.add(memoId = 7, text = " \n\t", now = 100))
        assertEquals(emptyList<MemoCommentEntity>(), dao.values.value)
    }

    @Test
    fun newCommentIsAppendedAfterTheCurrentCustomOrder() = runBlocking {
        val a = requireNotNull(repository.add(1, "A", 100))
        val b = requireNotNull(repository.add(1, "B", 200))
        val c = requireNotNull(repository.add(1, "C", 300))
        assertTrue(repository.reorder(1, listOf(a.id, c.id, b.id)))

        repository.add(1, "ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!", 150)

        val observed = repository.observeForMemo(1).first()
        assertEquals(
            listOf("A", "C", "B", "ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!"),
            observed.map { it.text },
        )
        assertEquals(listOf(0, 1, 2, 3), observed.map { it.playbackOrder })
    }

    @Test
    fun reorderIsPersistedAndDoesNotAffectAnotherMemo() = runBlocking {
        val a = requireNotNull(repository.add(1, "A", 100))
        val b = requireNotNull(repository.add(1, "B", 200))
        val otherA = requireNotNull(repository.add(2, "other A", 100))
        val otherB = requireNotNull(repository.add(2, "other B", 200))

        assertTrue(repository.reorder(1, listOf(b.id, a.id)))

        assertEquals(listOf("B", "A"), repository.observeForMemo(1).first().map { it.text })
        assertEquals(listOf(otherA, otherB), repository.observeForMemo(2).first())
    }

    @Test
    fun deleteNormalizesRemainingPlaybackOrder() = runBlocking {
        val a = requireNotNull(repository.add(1, "A", 100))
        val b = requireNotNull(repository.add(1, "B", 200))
        val c = requireNotNull(repository.add(1, "C", 300))
        repository.reorder(1, listOf(c.id, b.id, a.id))

        repository.delete(b)

        val observed = repository.observeForMemo(1).first()
        assertEquals(listOf("C", "A"), observed.map { it.text })
        assertEquals(listOf(0, 1), observed.map { it.playbackOrder })
    }

    @Test
    fun resetUsesCreationTimeThenIdWithoutChangingCreationTime() = runBlocking {
        val late = requireNotNull(repository.add(1, "late", 300))
        val sameTimeFirst = requireNotNull(repository.add(1, "same first", 100))
        val sameTimeSecond = requireNotNull(repository.add(1, "same second", 100))
        repository.reorder(1, listOf(late.id, sameTimeSecond.id, sameTimeFirst.id))

        repository.resetToCreationOrder(1)

        val observed = repository.observeForMemo(1).first()
        assertEquals(listOf("same first", "same second", "late"), observed.map { it.text })
        assertEquals(listOf(100L, 100L, 300L), observed.map { it.createdAt })
        assertEquals(listOf(0, 1, 2), observed.map { it.playbackOrder })
    }

    @Test
    fun appearanceCreationAndTargetedUpdatePreserveContentOrderAndTime() = runBlocking {
        val original = requireNotNull(
            repository.add(
                memoId = 7,
                text = "ここ好き",
                now = 123,
                appearance = CommentAppearance(
                    colorRole = CommentColorRole.PINK,
                    sizeRole = CommentSizeRole.LARGE,
                    emphasisRole = CommentEmphasisRole.STRONG,
                ),
            ),
        )
        assertEquals("pink", original.appearanceColor)
        assertEquals("large", original.appearanceSize)
        assertEquals("strong", original.appearanceEmphasis)

        assertTrue(repository.updateAppearance(original, CommentAppearance.Default))

        val updated = repository.findForMemo(7).single()
        assertEquals(original.text, updated.text)
        assertEquals(original.createdAt, updated.createdAt)
        assertEquals(original.playbackOrder, updated.playbackOrder)
        assertEquals("default", updated.appearanceColor)
        assertEquals("standard", updated.appearanceSize)
        assertEquals("normal", updated.appearanceEmphasis)
    }

    @Test
    fun motionCreationAndTargetedUpdatePreserveAppearanceContentOrderAndTime() = runBlocking {
        val appearance = CommentAppearance(
            colorRole = CommentColorRole.PINK,
            sizeRole = CommentSizeRole.LARGE,
            emphasisRole = CommentEmphasisRole.STRONG,
        )
        val original = requireNotNull(
            repository.add(
                memoId = 7,
                text = "ここ好き",
                now = 123,
                appearance = appearance,
                motion = CommentMotion(
                    speedRole = CommentSpeedRole.FAST,
                    placementRole = CommentPlacementRole.TOP,
                ),
            ),
        )

        assertTrue(
            repository.updateMotion(
                original,
                CommentMotion(
                    mode = CommentMotionMode.FIXED_BOTTOM,
                    direction = CommentFlowDirection.LEFT_TO_RIGHT,
                    flowEffect = CommentFlowEffect.WAVE,
                ),
            ),
        )

        val updated = repository.findForMemo(7).single()
        assertEquals(original.text, updated.text)
        assertEquals(original.createdAt, updated.createdAt)
        assertEquals(original.playbackOrder, updated.playbackOrder)
        assertEquals(original.appearanceColor, updated.appearanceColor)
        assertEquals(original.appearanceSize, updated.appearanceSize)
        assertEquals(original.appearanceEmphasis, updated.appearanceEmphasis)
        assertEquals("standard", updated.motionSpeed)
        assertEquals("auto", updated.motionPlacement)
        assertEquals("fixed_bottom", updated.motionMode)
        assertEquals("ltr", updated.flowDirection)
        assertEquals("wave", updated.flowEffect)
    }
}

private class FakeMemoCommentDao : MemoCommentDao {
    val values = MutableStateFlow<List<MemoCommentEntity>>(emptyList())
    private var nextId = 1L

    override fun observeForMemo(memoId: Long): Flow<List<MemoCommentEntity>> = values.map { all ->
        all.filter { it.memoId == memoId }
            .sortedWith(compareBy(MemoCommentEntity::playbackOrder, MemoCommentEntity::id))
    }

    override suspend fun maxLinkNo(memoId: Long): Int? = values.value
        .filter { it.memoId == memoId }
        .mapNotNull(MemoCommentEntity::linkNo)
        .maxOrNull()

    override suspend fun setLinkNo(commentId: Long, linkNo: Int?): Int {
        var updated = 0
        values.value = values.value.map { comment ->
            if (comment.id == commentId) {
                updated = 1
                comment.copy(linkNo = linkNo)
            } else {
                comment
            }
        }
        return updated
    }

    override suspend fun findForMemo(memoId: Long): List<MemoCommentEntity> = values.value
        .filter { it.memoId == memoId }
        .sortedWith(compareBy(MemoCommentEntity::playbackOrder, MemoCommentEntity::id))

    override suspend fun nextPlaybackOrder(memoId: Long): Int =
        (values.value.filter { it.memoId == memoId }.maxOfOrNull { it.playbackOrder } ?: -1) + 1

    override suspend fun orderedIds(memoId: Long): List<Long> = values.value
        .filter { it.memoId == memoId }
        .sortedWith(compareBy(MemoCommentEntity::playbackOrder, MemoCommentEntity::id))
        .map { it.id }

    override suspend fun creationOrderedIds(memoId: Long): List<Long> = values.value
        .filter { it.memoId == memoId }
        .sortedWith(compareBy(MemoCommentEntity::createdAt, MemoCommentEntity::id))
        .map { it.id }

    override suspend fun updatePlaybackOrder(
        memoId: Long,
        commentId: Long,
        playbackOrder: Int,
    ): Int {
        var updated = 0
        values.value = values.value.map { comment ->
            if (comment.memoId == memoId && comment.id == commentId) {
                updated = 1
                comment.copy(playbackOrder = playbackOrder)
            } else {
                comment
            }
        }
        return updated
    }

    override suspend fun updateAppearance(
        memoId: Long,
        commentId: Long,
        color: String,
        size: String,
        emphasis: String,
    ): Int {
        var updated = 0
        values.value = values.value.map { comment ->
            if (comment.memoId == memoId && comment.id == commentId) {
                updated = 1
                comment.copy(
                    appearanceColor = color,
                    appearanceSize = size,
                    appearanceEmphasis = emphasis,
                )
            } else {
                comment
            }
        }
        return updated
    }

    override suspend fun updateMotion(
        memoId: Long,
        commentId: Long,
        mode: String,
        speed: String,
        placement: String,
        direction: String,
        effect: String,
    ): Int = updateMatching(memoId, commentId) { comment ->
        comment.copy(
            motionMode = mode,
            motionSpeed = speed,
            motionPlacement = placement,
            flowDirection = direction,
            flowEffect = effect,
        )
    }

    override suspend fun updateExpression(
        memoId: Long,
        commentId: Long,
        color: String,
        size: String,
        emphasis: String,
        mode: String,
        speed: String,
        placement: String,
        direction: String,
        effect: String,
    ): Int = updateMatching(memoId, commentId) { comment ->
        comment.copy(
            appearanceColor = color,
            appearanceSize = size,
            appearanceEmphasis = emphasis,
            motionMode = mode,
            motionSpeed = speed,
            motionPlacement = placement,
            flowDirection = direction,
            flowEffect = effect,
        )
    }

    private fun updateMatching(
        memoId: Long,
        commentId: Long,
        transform: (MemoCommentEntity) -> MemoCommentEntity,
    ): Int {
        var updated = 0
        values.value = values.value.map { comment ->
            if (comment.memoId == memoId && comment.id == commentId) {
                updated = 1
                transform(comment)
            } else {
                comment
            }
        }
        return updated
    }

    override suspend fun insert(comment: MemoCommentEntity): Long {
        val id = nextId++
        values.value += comment.copy(id = id)
        return id
    }

    override suspend fun deleteEntity(comment: MemoCommentEntity) {
        values.value = values.value.filterNot { it.id == comment.id }
    }
}

package io.github.cragcoffee.memoripple.data

import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.diary.FutureDiaryCommentItem
import io.github.cragcoffee.memoripple.domain.diary.FutureCommentExpression
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import io.github.cragcoffee.memoripple.domain.comments.CommentAppearance
import io.github.cragcoffee.memoripple.domain.comments.CommentColorRole
import io.github.cragcoffee.memoripple.domain.comments.CommentEmphasisRole
import io.github.cragcoffee.memoripple.domain.comments.CommentMotionMode
import io.github.cragcoffee.memoripple.domain.comments.CommentSizeRole
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FutureDiaryCommentRepositoryTest {
    @Test
    fun creationRequiresTodaysDiaryNonBlankTextAndFutureTime() = runBlocking {
        val fixture = FutureFixture()
        val todayDiary = fixture.diaryDao.seed(fixture.time.date.toEpochDay(), DiaryState.LOCKED)
        val pastDiary = fixture.diaryDao.seed(fixture.time.date.minusDays(1).toEpochDay())

        assertRejected(
            FutureCommentRejection.EMPTY_TEXT,
            fixture.repository.create(todayDiary.id, "  ", 2_000),
        )
        assertRejected(
            FutureCommentRejection.REVEAL_NOT_FUTURE,
            fixture.repository.create(todayDiary.id, "本文", 1_000),
        )
        assertRejected(
            FutureCommentRejection.NOT_DIARY_DATE,
            fixture.repository.create(pastDiary.id, "本文", 2_000),
        )
        assertTrue(fixture.repository.create(todayDiary.id, "本文", 2_000) is CreateFutureCommentResult.Success)
    }

    @Test
    fun oneDiaryCanOwnMultipleSealedCommentsWithoutExposingText() = runBlocking {
        val fixture = FutureFixture()
        val diary = fixture.diaryDao.seed(fixture.time.date.toEpochDay())
        fixture.repository.create(diary.id, "秘密A", 3_000)
        fixture.repository.create(diary.id, "秘密B", 2_000)

        val summaries = fixture.repository.observeForDiary(diary.id).first()

        assertEquals(2, summaries.size)
        assertTrue(summaries.all { it is FutureDiaryCommentItem.Sealed })
        assertTrue(summaries.none { it is FutureDiaryCommentItem.Revealed })
    }

    @Test
    fun finalSendPersistsExpressionWithTextAndRevealTime() = runBlocking {
        val fixture = FutureFixture()
        val diary = fixture.diaryDao.seed(fixture.time.date.toEpochDay())
        val expression = FutureCommentExpression(
            appearance = CommentAppearance(
                CommentColorRole.PINK,
                CommentSizeRole.LARGE,
                CommentEmphasisRole.STRONG,
            ),
            motionMode = CommentMotionMode.FIXED_TOP,
        )

        fixture.repository.create(diary.id, "未来の表現", 2_000, expression)

        val stored = fixture.futureDao.entities.value.single()
        assertEquals("未来の表現", stored.text)
        assertEquals(2_000L, stored.revealAt)
        assertEquals("pink", stored.appearanceColor)
        assertEquals("large", stored.appearanceSize)
        assertEquals("strong", stored.appearanceEmphasis)
        assertEquals("fixed_top", stored.motionMode)
    }

    @Test
    fun dueDeliveryIsPersistedAndDoesNotReverseWhenClockMovesBack() = runBlocking {
        val fixture = FutureFixture()
        val diary = fixture.diaryDao.seed(fixture.time.date.toEpochDay())
        fixture.repository.create(diary.id, "秘密", 2_000)

        assertEquals(0, fixture.repository.markDueDelivered())
        fixture.time.now = 2_000
        assertEquals(1, fixture.repository.markDueDelivered())
        assertEquals(2_000L, fixture.futureDao.entities.value.single().deliveredAt)

        fixture.time.now = 500
        assertEquals(0, fixture.repository.markDueDelivered())
        assertEquals(2_000L, fixture.futureDao.entities.value.single().deliveredAt)
        assertTrue(
            fixture.repository.observeForDiary(diary.id).first().single() is
                FutureDiaryCommentItem.Delivered,
        )
    }

    @Test
    fun sealedRevealIsRejectedAndDeliveredRevealReturnsTextOnlyOnce() = runBlocking {
        val fixture = FutureFixture()
        val diary = fixture.diaryDao.seed(fixture.time.date.toEpochDay())
        val id = (fixture.repository.create(diary.id, "初めて見る本文", 2_000) as
            CreateFutureCommentResult.Success).id

        assertNull(fixture.repository.revealById(id))
        fixture.time.now = 2_000
        val revealed = fixture.repository.revealById(id)

        assertEquals("初めて見る本文", revealed?.text)
        assertEquals(2_000L, revealed?.revealedAt)
        assertNull(fixture.repository.revealById(id))
        assertTrue(
            fixture.repository.observeForDiary(diary.id).first().single() is
                FutureDiaryCommentItem.AwaitingPresentation,
        )
        fixture.time.now = 3_000
        val completed = fixture.repository.markFirstPresentationCompleted(id)
        assertEquals(3_000L, completed?.firstPresentedAt)
        val history = fixture.repository.observeForDiary(diary.id).first().single()
            as FutureDiaryCommentItem.Revealed
        assertEquals("初めて見る本文", history.text)
    }

    @Test
    fun multipleDeliveredCommentsRevealInStableOldestOrder() = runBlocking {
        val fixture = FutureFixture()
        val diary = fixture.diaryDao.seed(fixture.time.date.toEpochDay())
        val later = fixture.repository.create(diary.id, "later", 1_800) as CreateFutureCommentResult.Success
        val firstTie = fixture.repository.create(diary.id, "first tie", 1_500) as CreateFutureCommentResult.Success
        val secondTie = fixture.repository.create(diary.id, "second tie", 1_500) as CreateFutureCommentResult.Success
        fixture.time.now = 2_000
        fixture.repository.markDueDelivered()

        assertEquals(firstTie.id, fixture.repository.revealNext()?.id)
        assertEquals(secondTie.id, fixture.repository.revealNext()?.id)
        assertEquals(later.id, fixture.repository.revealNext()?.id)
        assertNull(fixture.repository.revealNext())
    }

    @Test
    fun deleteRemovesSealedCommentWithoutRevealingIt() = runBlocking {
        val fixture = FutureFixture()
        val diary = fixture.diaryDao.seed(fixture.time.date.toEpochDay())
        val created = fixture.repository.create(diary.id, "削除される秘密", 2_000)
            as CreateFutureCommentResult.Success

        assertTrue(fixture.repository.delete(created.id))
        assertTrue(fixture.futureDao.entities.value.isEmpty())
    }

    @Test
    fun revealContextUsesTheCommentOwnSourceDiary() = runBlocking {
        val fixture = FutureFixture()
        val diaryA = fixture.diaryDao.seed(fixture.time.date.toEpochDay(), body = "Diary A本文")
        val diaryB = fixture.diaryDao.seed(fixture.time.date.toEpochDay(), body = "Diary B本文")
        val commentA = fixture.futureDao.insert(
            FutureDiaryCommentEntity(
                diaryEntryId = diaryA.id,
                text = "Aへのコメント",
                sealedAt = 1,
                revealAt = 2,
                deliveredAt = 3,
                revealedAt = 4,
            ),
        )
        fixture.futureDao.insert(
            FutureDiaryCommentEntity(
                diaryEntryId = diaryB.id,
                text = "Bへのコメント",
                sealedAt = 1,
                revealAt = 2,
                deliveredAt = 3,
                revealedAt = 4,
            ),
        )

        val context = requireNotNull(fixture.repository.findRevealContext(commentA))

        assertEquals(diaryA.id, context.sourceDiary.diaryEntryId)
        assertEquals("Diary A本文", context.sourceDiary.body)
        assertEquals("Aへのコメント", context.comment.text)
    }
}

private fun assertRejected(
    expected: FutureCommentRejection,
    result: CreateFutureCommentResult,
) {
    assertEquals(expected, (result as CreateFutureCommentResult.Rejected).reason)
}

private class FutureFixture {
    val time = FutureFakeTimeProvider()
    val diaryDao = FutureFakeDiaryDao()
    val futureDao = FutureFakeCommentDao()
    val repository = FutureDiaryCommentRepository(futureDao, diaryDao, time)
}

private class FutureFakeTimeProvider : TimeProvider {
    var now = 1_000L
    var date: LocalDate = LocalDate.of(2026, 8, 21)
    override fun nowMillis(): Long = now
    override fun currentLocalDate(): LocalDate = date
    override fun currentZoneId(): ZoneId = ZoneId.of("Asia/Tokyo")
}

private class FutureFakeDiaryDao : DiaryDao {
    private val values = MutableStateFlow<List<DiaryEntryEntity>>(emptyList())
    private var nextId = 1L
    override fun observeAll(): Flow<List<DiaryEntryEntity>> = values
    override fun observeBetween(
        startEpochDay: Long,
        endEpochDay: Long,
    ): Flow<List<DiaryEntryEntity>> = values.map { entries ->
        entries.filter { it.diaryDateEpochDay in startEpochDay..endEpochDay }
    }
    override suspend fun findByDate(epochDay: Long) = values.value.firstOrNull {
        it.diaryDateEpochDay == epochDay
    }
    override suspend fun findById(id: Long) = values.value.firstOrNull { it.id == id }
    override fun observeForDate(epochDay: Long): Flow<List<DiaryEntryEntity>> =
        values.map { entries -> entries.filter { it.diaryDateEpochDay == epochDay } }
    override suspend fun entriesForDate(epochDay: Long): List<DiaryEntryEntity> =
        values.value.filter { it.diaryDateEpochDay == epochDay }
    override suspend fun insert(entry: DiaryEntryEntity): Long = -1
    override suspend fun insertIgnoringConflict(entry: DiaryEntryEntity): Long = -1
    override suspend fun update(entry: DiaryEntryEntity): Int = 0
    override suspend fun deleteDraft(id: Long): Int = 0

    fun seed(
        epochDay: Long,
        state: DiaryState = DiaryState.DRAFT,
        body: String = "日記",
    ): DiaryEntryEntity {
        val entry = DiaryEntryEntity(
            id = nextId++,
            diaryDateEpochDay = epochDay,
            body = body,
            state = state,
            createdAt = 1,
            updatedAt = 1,
        )
        values.value += entry
        return entry
    }
}

private class FutureFakeCommentDao : FutureDiaryCommentDao {
    val entities = MutableStateFlow<List<FutureDiaryCommentEntity>>(emptyList())
    private var nextId = 1L

    override fun observeForDiary(diaryEntryId: Long): Flow<List<FutureDiaryCommentOverviewRecord>> =
        entities.map { values ->
            values.filter { it.diaryEntryId == diaryEntryId }
                .sortedWith(compareByDescending<FutureDiaryCommentEntity> { it.revealAt }.thenByDescending { it.id })
                .map { entity ->
                    FutureDiaryCommentOverviewRecord(
                        id = entity.id,
                        diaryEntryId = entity.diaryEntryId,
                        revealAt = entity.revealAt,
                        deliveredAt = entity.deliveredAt,
                        revealedAt = entity.revealedAt,
                        firstPresentedAt = entity.firstPresentedAt,
                        revealedText = entity.text.takeIf { entity.firstPresentedAt != null },
                        revealedAppearanceColor = entity.appearanceColor.takeIf {
                            entity.firstPresentedAt != null
                        },
                        revealedAppearanceSize = entity.appearanceSize.takeIf {
                            entity.firstPresentedAt != null
                        },
                        revealedAppearanceEmphasis = entity.appearanceEmphasis.takeIf {
                            entity.firstPresentedAt != null
                        },
                        revealedMotionMode = entity.motionMode.takeIf {
                            entity.firstPresentedAt != null
                        },
                    )
                }
        }

    override fun observeUndeliveredRevealCount(): Flow<Int> = entities.map { values ->
        values.count { it.deliveredAt != null && it.revealedAt == null }
    }

    override suspend fun insert(comment: FutureDiaryCommentEntity): Long {
        val id = nextId++
        entities.value += comment.copy(id = id)
        return id
    }

    override suspend fun markDueDelivered(now: Long, deliveredAt: Long): Int {
        var count = 0
        entities.value = entities.value.map {
            if (it.deliveredAt == null && it.revealAt <= now) {
                count++
                it.copy(deliveredAt = deliveredAt)
            } else it
        }
        return count
    }

    override suspend fun deleteById(id: Long): Int {
        val size = entities.value.size
        entities.value = entities.value.filterNot { it.id == id }
        return size - entities.value.size
    }

    override suspend fun findEntityById(id: Long) = entities.value.firstOrNull { it.id == id }

    override suspend fun findNextDeliveredEntity(): FutureDiaryCommentEntity? = entities.value
        .filter { it.deliveredAt != null && it.revealedAt == null }
        .sortedWith(
            compareBy<FutureDiaryCommentEntity> { it.deliveredAt }
                .thenBy { it.revealAt }
                .thenBy { it.id },
        )
        .firstOrNull()

    override suspend fun markRevealed(id: Long, revealedAt: Long): Int {
        var count = 0
        entities.value = entities.value.map {
            if (it.id == id && it.deliveredAt != null && it.revealedAt == null) {
                count++
                it.copy(revealedAt = revealedAt)
            } else it
        }
        return count
    }

    override suspend fun markFirstPresented(id: Long, completedAt: Long): Int {
        var count = 0
        entities.value = entities.value.map {
            if (it.id == id && it.revealedAt != null && it.firstPresentedAt == null) {
                count++
                it.copy(firstPresentedAt = completedAt)
            } else it
        }
        return count
    }

    override suspend fun findRevealed(id: Long): FutureDiaryCommentEntity? =
        entities.value.firstOrNull { it.id == id && it.revealedAt != null }
}

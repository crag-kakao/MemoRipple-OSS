package io.github.cragcoffee.memoripple.data

import io.github.cragcoffee.memoripple.domain.diary.FutureDiaryCommentItem
import io.github.cragcoffee.memoripple.domain.diary.FutureCommentExpression
import io.github.cragcoffee.memoripple.domain.diary.RevealedFutureDiaryComment
import io.github.cragcoffee.memoripple.domain.diary.FutureCommentRevealContext
import io.github.cragcoffee.memoripple.domain.diary.SourceDiaryContext
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface CreateFutureCommentResult {
    data class Success(val id: Long, val revealAt: Long) : CreateFutureCommentResult
    data class Rejected(val reason: FutureCommentRejection) : CreateFutureCommentResult
}

enum class FutureCommentRejection {
    DIARY_NOT_FOUND,
    NOT_DIARY_DATE,
    EMPTY_TEXT,
    REVEAL_NOT_FUTURE,
    NOT_DELIVERED,
}

class FutureDiaryCommentRepository(
    private val futureDao: FutureDiaryCommentDao,
    private val diaryDao: DiaryDao,
    private val timeProvider: TimeProvider,
) {
    private val mutex = Mutex()

    fun observeForDiary(diaryEntryId: Long): Flow<List<FutureDiaryCommentItem>> =
        futureDao.observeForDiary(diaryEntryId).map { records -> records.map(::toSafeItem) }

    fun observeDeliveredCount(): Flow<Int> = futureDao.observeUndeliveredRevealCount()

    fun currentDate(): LocalDate = timeProvider.currentLocalDate()

    fun toEpochMillis(localDateTime: LocalDateTime): Long =
        timeProvider.toEpochMillis(localDateTime)

    fun isFuture(revealAt: Long): Boolean = revealAt > timeProvider.nowMillis()

    suspend fun create(
        diaryEntryId: Long,
        text: String,
        revealAt: Long,
        expression: FutureCommentExpression = FutureCommentExpression.Default,
    ): CreateFutureCommentResult = mutex.withLock {
        val diary = diaryDao.findById(diaryEntryId)
            ?: return@withLock CreateFutureCommentResult.Rejected(
                FutureCommentRejection.DIARY_NOT_FOUND,
            )
        if (diary.diaryDateEpochDay != timeProvider.currentLocalDate().toEpochDay()) {
            return@withLock CreateFutureCommentResult.Rejected(
                FutureCommentRejection.NOT_DIARY_DATE,
            )
        }
        if (text.isBlank()) {
            return@withLock CreateFutureCommentResult.Rejected(FutureCommentRejection.EMPTY_TEXT)
        }
        val now = timeProvider.nowMillis()
        if (revealAt <= now) {
            return@withLock CreateFutureCommentResult.Rejected(
                FutureCommentRejection.REVEAL_NOT_FUTURE,
            )
        }
        val id = futureDao.insert(
            FutureDiaryCommentEntity(
                diaryEntryId = diaryEntryId,
                text = text,
                sealedAt = now,
                revealAt = revealAt,
                appearanceColor = expression.appearance.colorRole.storageId,
                appearanceSize = expression.appearance.sizeRole.storageId,
                appearanceEmphasis = expression.appearance.emphasisRole.storageId,
                motionMode = expression.motionMode.storageId,
            ),
        )
        CreateFutureCommentResult.Success(id, revealAt)
    }

    suspend fun markDueDelivered(): Int = mutex.withLock {
        val now = timeProvider.nowMillis()
        futureDao.markDueDelivered(now = now, deliveredAt = now)
    }

    /** Restore's lock: taken before the database transaction, like every caller here. */
    suspend fun <T> withOperationLock(block: suspend () -> T): T = mutex.withLock { block() }

    /** [markDueDelivered] for a caller already inside [withOperationLock]. */
    suspend fun markDueDeliveredLockHeld(): Int {
        val now = timeProvider.nowMillis()
        return futureDao.markDueDelivered(now = now, deliveredAt = now)
    }

    suspend fun revealNext(): RevealedFutureDiaryComment? = mutex.withLock {
        markDueWithoutLock()
        futureDao.revealNext(timeProvider.nowMillis())
    }

    suspend fun revealById(id: Long): RevealedFutureDiaryComment? = mutex.withLock {
        markDueWithoutLock()
        futureDao.revealById(id, timeProvider.nowMillis())
    }

    suspend fun revealNextContext(): FutureCommentRevealContext? = mutex.withLock {
        markDueWithoutLock()
        val comment = futureDao.revealNext(timeProvider.nowMillis()) ?: return@withLock null
        contextFor(comment)
    }

    suspend fun findRevealContext(id: Long): FutureCommentRevealContext? = mutex.withLock {
        val comment = futureDao.findRevealed(id)?.toRevealedComment() ?: return@withLock null
        contextFor(comment)
    }

    suspend fun markFirstPresentationCompleted(id: Long): RevealedFutureDiaryComment? =
        mutex.withLock {
            val now = timeProvider.nowMillis()
            futureDao.markFirstPresented(id, now)
            futureDao.findRevealed(id)?.toRevealedComment()
        }

    suspend fun delete(id: Long): Boolean = mutex.withLock { futureDao.deleteById(id) == 1 }

    private suspend fun markDueWithoutLock() {
        val now = timeProvider.nowMillis()
        futureDao.markDueDelivered(now = now, deliveredAt = now)
    }

    private fun toSafeItem(record: FutureDiaryCommentOverviewRecord): FutureDiaryCommentItem =
        when {
            record.firstPresentedAt != null -> FutureDiaryCommentItem.Revealed(
                id = record.id,
                diaryEntryId = record.diaryEntryId,
                revealAt = record.revealAt,
                text = requireNotNull(record.revealedText),
                revealedAt = requireNotNull(record.revealedAt),
                firstPresentedAt = record.firstPresentedAt,
                expression = FutureCommentExpression.fromStorageIds(
                    color = requireNotNull(record.revealedAppearanceColor),
                    size = requireNotNull(record.revealedAppearanceSize),
                    emphasis = requireNotNull(record.revealedAppearanceEmphasis),
                    motionMode = requireNotNull(record.revealedMotionMode),
                ),
            )
            record.revealedAt != null -> FutureDiaryCommentItem.AwaitingPresentation(
                id = record.id,
                diaryEntryId = record.diaryEntryId,
                revealAt = record.revealAt,
            )
            record.deliveredAt != null -> FutureDiaryCommentItem.Delivered(
                id = record.id,
                diaryEntryId = record.diaryEntryId,
                revealAt = record.revealAt,
            )
            else -> FutureDiaryCommentItem.Sealed(
                id = record.id,
                diaryEntryId = record.diaryEntryId,
                revealAt = record.revealAt,
            )
        }

    private suspend fun contextFor(
        comment: RevealedFutureDiaryComment,
    ): FutureCommentRevealContext? {
        val diary = diaryDao.findById(comment.diaryEntryId) ?: return null
        return FutureCommentRevealContext(
            sourceDiary = SourceDiaryContext(
                diaryEntryId = diary.id,
                diaryDateEpochDay = diary.diaryDateEpochDay,
                body = diary.body,
            ),
            comment = comment,
        )
    }

    private fun FutureDiaryCommentEntity.toRevealedComment(): RevealedFutureDiaryComment =
        RevealedFutureDiaryComment(
            id = id,
            diaryEntryId = diaryEntryId,
            text = text,
            revealAt = revealAt,
            revealedAt = requireNotNull(revealedAt),
            firstPresentedAt = firstPresentedAt,
            expression = FutureCommentExpression.fromStorageIds(
                color = appearanceColor,
                size = appearanceSize,
                emphasis = appearanceEmphasis,
                motionMode = motionMode,
            ),
        )
}

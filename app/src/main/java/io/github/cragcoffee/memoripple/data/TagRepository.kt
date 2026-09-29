package io.github.cragcoffee.memoripple.data

import android.database.sqlite.SQLiteConstraintException
import io.github.cragcoffee.memoripple.domain.tags.TagNameIssue
import io.github.cragcoffee.memoripple.domain.tags.TagNameNormalizer
import io.github.cragcoffee.memoripple.domain.tags.TagNameValidation
import kotlinx.coroutines.flow.Flow

sealed interface TagMutationResult {
    data class Success(val tag: TagEntity? = null) : TagMutationResult
    data class InvalidName(val issue: TagNameIssue) : TagMutationResult
    data object Duplicate : TagMutationResult
    data object Missing : TagMutationResult
}

class TagRepository(private val dao: TagDao) {
    fun observeAllTags(): Flow<List<TagEntity>> = dao.observeAllTags()
    fun observeAllRelations(): Flow<List<MemoTagCrossRef>> = dao.observeAllRelations()
    fun observeTagsForMemo(memoId: Long): Flow<List<TagEntity>> = dao.observeTagsForMemo(memoId)
    fun observeTagSummaries(): Flow<List<TagSummary>> = dao.observeTagSummaries()

    suspend fun create(name: String, now: Long): TagMutationResult {
        val valid = when (val result = TagNameNormalizer.validate(name)) {
            is TagNameValidation.Valid -> result.value
            is TagNameValidation.Invalid -> return TagMutationResult.InvalidName(result.issue)
        }
        return try {
            val id = dao.insert(TagEntity(name = valid.displayName, normalizedName = valid.normalizedName, createdAt = now))
            TagMutationResult.Success(requireNotNull(dao.findById(id)))
        } catch (_: SQLiteConstraintException) {
            TagMutationResult.Duplicate
        }
    }

    suspend fun rename(tagId: Long, name: String): TagMutationResult {
        val valid = when (val result = TagNameNormalizer.validate(name)) {
            is TagNameValidation.Valid -> result.value
            is TagNameValidation.Invalid -> return TagMutationResult.InvalidName(result.issue)
        }
        return try {
            if (dao.rename(tagId, valid.displayName, valid.normalizedName) == 1) {
                TagMutationResult.Success(dao.findById(tagId))
            } else {
                TagMutationResult.Missing
            }
        } catch (_: SQLiteConstraintException) {
            TagMutationResult.Duplicate
        }
    }

    suspend fun delete(tag: TagEntity) = dao.delete(tag)

    suspend fun attach(memoId: Long, tagId: Long): Boolean =
        dao.attach(MemoTagCrossRef(memoId, tagId)) != -1L

    suspend fun detach(memoId: Long, tagId: Long): Boolean = dao.detach(memoId, tagId) == 1

    suspend fun addTagsToMemos(memoIds: Set<Long>, tagIds: Set<Long>): BulkMemoMutation =
        if (memoIds.isEmpty() || tagIds.isEmpty()) {
            BulkMemoMutation(emptySet())
        } else {
            BulkMemoMutation(dao.addTagsToMemos(memoIds.toList(), tagIds.toList()).toSet())
        }

    suspend fun removeTagsFromMemos(memoIds: Set<Long>, tagIds: Set<Long>): BulkMemoMutation =
        if (memoIds.isEmpty() || tagIds.isEmpty()) {
            BulkMemoMutation(emptySet())
        } else {
            BulkMemoMutation(dao.removeTagsFromMemos(memoIds.toList(), tagIds.toList()).toSet())
        }
}

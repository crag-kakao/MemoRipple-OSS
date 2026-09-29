package io.github.cragcoffee.memoripple.ui.memos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoRepository
import io.github.cragcoffee.memoripple.data.MemoTagCrossRef
import io.github.cragcoffee.memoripple.data.TagEntity
import io.github.cragcoffee.memoripple.data.TagRepository
import io.github.cragcoffee.memoripple.data.TrashRestoreDestination
import io.github.cragcoffee.memoripple.domain.memos.MemoSearch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MemoCollection { ARCHIVE, TRASH }

data class MemoLifecycleUiState(
    val query: String = "",
    val memos: List<MemoEntity> = emptyList(),
    val tagsByMemo: Map<Long, List<TagEntity>> = emptyMap(),
)

data class MemoCollectionEvent(
    val message: String,
    val undoMemoId: Long? = null,
    val undoAction: MemoLifecycleAction? = null,
)

class MemoLifecycleViewModel(
    private val repository: MemoRepository,
    tagRepository: TagRepository,
    private val collection: MemoCollection,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val eventsMutable = MutableSharedFlow<MemoCollectionEvent>()
    val events = eventsMutable.asSharedFlow()
    // The collection is read whole and searched in memory, so archive and trash answer a query the
    // same way the main list does rather than falling back to a single word.
    private val memos = when (collection) {
        MemoCollection.ARCHIVE -> repository.observeArchivedMemos("")
        MemoCollection.TRASH -> repository.observeTrashedMemos("")
    }

    val uiState: StateFlow<MemoLifecycleUiState> = combine(
        query,
        memos,
        tagRepository.observeAllTags(),
        tagRepository.observeAllRelations(),
    ) { currentQuery, allMemos, tags, relations ->
        val tagsById = tags.associateBy(TagEntity::id)
        val tagNamesByMemo = relations.groupBy(MemoTagCrossRef::memoId)
            .mapValues { (_, refs) -> refs.mapNotNull { tagsById[it.tagId]?.name } }
        val parsed = MemoSearch.parse(currentQuery)
        val currentMemos = if (parsed.isEmpty) {
            allMemos
        } else {
            allMemos.filter { memo ->
                MemoSearch.matches(parsed, memo.title, memo.body, tagNamesByMemo[memo.id].orEmpty())
            }
        }
        val memoIds = currentMemos.mapTo(hashSetOf(), MemoEntity::id)
        val mapped = relations.asSequence()
            .filter { it.memoId in memoIds }
            .groupBy(MemoTagCrossRef::memoId)
            .mapValues { (_, refs) -> refs.mapNotNull { tagsById[it.tagId] } }
        MemoLifecycleUiState(currentQuery, currentMemos, mapped)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoLifecycleUiState())

    fun updateQuery(value: String) { query.value = value }

    fun unarchive(memoId: Long) = launchAction {
        if (repository.unarchive(memoId)) {
            eventsMutable.emit(MemoCollectionEvent("メモをアーカイブから戻しました", memoId, MemoLifecycleAction.ARCHIVED))
        }
    }

    fun moveToTrash(memoId: Long) = launchAction {
        if (repository.moveToTrash(memoId)) {
            eventsMutable.emit(MemoCollectionEvent("メモをゴミ箱へ移動しました", memoId, MemoLifecycleAction.TRASHED))
        }
    }

    fun restore(memoId: Long) = launchAction {
        val destination = repository.restoreFromTrash(memoId) ?: return@launchAction
        eventsMutable.emit(
            MemoCollectionEvent(
                if (destination == TrashRestoreDestination.ARCHIVED) {
                    "メモをアーカイブへ戻しました"
                } else {
                    "メモをメモ一覧へ戻しました"
                },
            ),
        )
    }

    fun deletePermanently(memoId: Long) = launchAction {
        if (repository.deletePermanently(memoId)) {
            eventsMutable.emit(MemoCollectionEvent("メモを完全に削除しました"))
        }
    }

    fun emptyTrash() = launchAction {
        val count = repository.emptyTrash()
        if (count > 0) eventsMutable.emit(MemoCollectionEvent("ゴミ箱を空にしました（${count}件）"))
    }

    fun undo(event: MemoCollectionEvent) {
        val id = event.undoMemoId ?: return
        viewModelScope.launch {
            when (event.undoAction) {
                MemoLifecycleAction.ARCHIVED -> repository.archive(id)
                MemoLifecycleAction.TRASHED -> repository.restoreFromTrash(id)
                null -> Unit
            }
        }
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        fun factory(
            repository: MemoRepository,
            tagRepository: TagRepository,
            collection: MemoCollection,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MemoLifecycleViewModel(repository, tagRepository, collection) as T
        }
    }
}

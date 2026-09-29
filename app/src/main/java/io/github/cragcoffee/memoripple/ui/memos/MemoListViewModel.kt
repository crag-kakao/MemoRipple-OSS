package io.github.cragcoffee.memoripple.ui.memos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import io.github.cragcoffee.memoripple.data.BulkLifecycleMutation
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.MemoRepository
import io.github.cragcoffee.memoripple.data.MemoTagCrossRef
import io.github.cragcoffee.memoripple.data.TagEntity
import io.github.cragcoffee.memoripple.data.TagRepository
import io.github.cragcoffee.memoripple.data.TagMutationResult
import io.github.cragcoffee.memoripple.domain.tags.TagNameNormalizer
import io.github.cragcoffee.memoripple.data.AttachmentRepository
import io.github.cragcoffee.memoripple.data.MemoPhotoCount
import io.github.cragcoffee.memoripple.data.SettingsRepository
import io.github.cragcoffee.memoripple.domain.WorkCommentScope
import io.github.cragcoffee.memoripple.domain.playback.CommentAnimationState
import io.github.cragcoffee.memoripple.domain.playback.CommentAnimator
import io.github.cragcoffee.memoripple.domain.playback.MemoWallTimelineFactory
import io.github.cragcoffee.memoripple.domain.playback.PlaybackItem
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.domain.memos.ImportedMemo
import io.github.cragcoffee.memoripple.domain.memos.MemoFilterMode
import io.github.cragcoffee.memoripple.domain.memos.MemoOrganizationPolicy
import io.github.cragcoffee.memoripple.domain.memos.MemoSelectionState
import io.github.cragcoffee.memoripple.domain.memos.MemoSortMode
import io.github.cragcoffee.memoripple.domain.memos.WallDisplayMode
import io.github.cragcoffee.memoripple.domain.memos.TagMatchMode
import io.github.cragcoffee.memoripple.data.FolderEntity
import io.github.cragcoffee.memoripple.data.FolderRepository
import io.github.cragcoffee.memoripple.data.FolderResult
import io.github.cragcoffee.memoripple.data.toNode
import io.github.cragcoffee.memoripple.domain.folders.FolderNavigator
import io.github.cragcoffee.memoripple.domain.folders.FolderRow
import io.github.cragcoffee.memoripple.domain.folders.FolderTree
import io.github.cragcoffee.memoripple.domain.memos.isOutline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import io.github.cragcoffee.memoripple.domain.memos.memoKind
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class MemoListUiState(
    val query: String = "",
    val filter: MemoFilterMode = MemoFilterMode.ALL,
    val sort: MemoSortMode = MemoSortMode.UPDATED_DESC,
    val memos: List<MemoEntity> = emptyList(),
    val totalMemoCount: Int = 0,
    /** The outlines (kind = outline), organised by the same search, order and tags as the wall. */
    val outlines: List<MemoEntity> = emptyList(),
    val totalOutlineCount: Int = 0,
    /** Every folder, for the picker and the breadcrumb; the tree is shared by both kinds. */
    val folders: List<FolderEntity> = emptyList(),
    /** The folder the pages are showing — null at the root. A way of looking, never stored. */
    val currentFolderId: Long? = null,
    /** The breadcrumb: the folders from the root down to the open one; empty at the root. */
    val folderPath: List<FolderEntity> = emptyList(),
    /** The folders inside the open one (or at the root), sorted the way tags are. */
    val childFolders: List<FolderEntity> = emptyList(),
    /** The navigator's tree as rows: roots, and under each expanded folder its children. */
    val navigatorRows: List<FolderRow> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val tagsByMemo: Map<Long, List<TagEntity>> = emptyMap(),
    val selectedTagIds: Set<Long> = emptySet(),
    val selectedTags: List<TagEntity> = emptyList(),
    val tagMatchMode: TagMatchMode = TagMatchMode.ANY,
    val selection: MemoSelectionState = MemoSelectionState(),
    val removableTags: List<TagEntity> = emptyList(),
    val photoCounts: Map<Long, Int> = emptyMap(),
) {
    val isSelectionMode: Boolean get() = selection.isSelectionMode
    val selectedMemoIds: Set<Long> get() = selection.selectedIds
}

enum class MemoLifecycleAction { ARCHIVED, TRASHED }

sealed interface MemoBulkEvent {
    val message: String

    data class Completed(override val message: String) : MemoBulkEvent
    data class Lifecycle(
        override val message: String,
        val action: MemoLifecycleAction,
        val change: BulkLifecycleMutation,
    ) : MemoBulkEvent
}

class MemoListViewModel(
    private val repository: MemoRepository,
    private val tagRepository: TagRepository,
    private val attachmentRepository: AttachmentRepository,
    private val settingsRepository: SettingsRepository? = null,
    private val folderRepository: FolderRepository? = null,
    // Navigation state the wall must not lose to process death: the open folder and the
    // navigator's expanded folders. Null when a caller has no saved-state owner (unit tests).
    private val savedStateHandle: SavedStateHandle? = null,
    private val wallAnimator: CommentAnimator = CommentAnimator(),
    private val wallTimelineFactory: MemoWallTimelineFactory = MemoWallTimelineFactory(),
) : ViewModel() {
    /** How the wall is drawn. Remembered on the device, because a way of reading should keep. */
    val displayMode: StateFlow<WallDisplayMode> =
        (settingsRepository?.wallDisplayMode ?: kotlinx.coroutines.flow.flowOf(WallDisplayMode.COMBINED))
            .stateIn(viewModelScope, SharingStarted.Eagerly, WallDisplayMode.COMBINED)

    fun updateDisplayMode(mode: WallDisplayMode) {
        viewModelScope.launch { settingsRepository?.setWallDisplayMode(mode) }
    }

    /** Whether the メモ/アウトライン wall lies flat as one column. Also a way of reading. */
    val wallSingleColumn: StateFlow<Boolean> =
        (settingsRepository?.wallSingleColumn ?: kotlinx.coroutines.flow.flowOf(false))
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun updateWallSingleColumn(value: Boolean) {
        viewModelScope.launch { settingsRepository?.setWallSingleColumn(value) }
    }

    private val _bulkEvents = MutableSharedFlow<MemoBulkEvent>()
    val bulkEvents = _bulkEvents.asSharedFlow()
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(MemoFilterMode.ALL)
    private val sort = MutableStateFlow(MemoSortMode.UPDATED_DESC)
    private val selectedTagIds = MutableStateFlow<Set<Long>>(emptySet())
    private val tagMatchMode = MutableStateFlow(TagMatchMode.ANY)
    private val selection = MutableStateFlow(MemoSelectionState())
    // The wall holds what is not spoken for: a note's episodes are read in the note.
    private val sourceMemos = repository.observeStandaloneMemos("")
    // The outliner page holds what was made there, by kind — never a memo written like one.
    private val sourceOutlines = repository.observeOutlineDocuments("")
    // The folder tree, shared by the memo and outliner pages; empty when folders are not wired.
    private val sourceFolders: Flow<List<FolderEntity>> =
        folderRepository?.observeFolders() ?: flowOf(emptyList())
    // Where the pages stand in the tree, and which folders the navigator holds open. UI state —
    // never in a document, never in Room — but navigation state all the same, so it is kept in
    // the saved state and comes back after process death (docs/FOLDER_NAVIGATOR_AUDIT.md).
    private val currentFolder = MutableStateFlow<Long?>(savedStateHandle?.get<Long>(KEY_CURRENT_FOLDER))
    private val expandedFolders = MutableStateFlow<Set<Long>>(
        savedStateHandle?.get<LongArray>(KEY_EXPANDED_FOLDERS)?.toSet().orEmpty(),
    )

    private fun setCurrentFolder(folderId: Long?) {
        currentFolder.value = folderId
        savedStateHandle?.set(KEY_CURRENT_FOLDER, folderId)
    }

    private fun setExpandedFolders(ids: Set<Long>) {
        expandedFolders.value = ids
        savedStateHandle?.set(KEY_EXPANDED_FOLDERS, ids.toLongArray())
    }

    private data class Sources(
        val memos: List<MemoEntity>,
        val outlines: List<MemoEntity>,
        val tags: List<TagEntity>,
        val relations: List<MemoTagCrossRef>,
        val photoCounts: List<MemoPhotoCount>,
        val folders: List<FolderEntity> = emptyList(),
    )

    private data class Controls(
        val query: String,
        val filter: MemoFilterMode,
        val sort: MemoSortMode,
        val selectedTagIds: Set<Long>,
        val tagMatchMode: TagMatchMode,
        val selection: MemoSelectionState,
        val currentFolderId: Long? = null,
        val expandedFolderIds: Set<Long> = emptySet(),
    )

    private val sources = combine(
        sourceMemos,
        sourceOutlines,
        tagRepository.observeAllTags(),
        tagRepository.observeAllRelations(),
        attachmentRepository.observeMemoPhotoCounts(),
    ) { memos, outlines, tags, relations, photoCounts ->
        Sources(memos, outlines, tags, relations, photoCounts)
    }.combine(sourceFolders) { sources, folders -> sources.copy(folders = folders) }

    private val organizationControls = combine(query, filter, sort) { query, filter, sort ->
        Triple(query, filter, sort)
    }

    private val controls = combine(
        organizationControls,
        selectedTagIds,
        tagMatchMode,
        selection,
    ) { base, tagIds, matchMode, selection ->
        Controls(base.first, base.second, base.third, tagIds, matchMode, selection)
    }.combine(currentFolder) { controls, folder -> controls.copy(currentFolderId = folder) }
        .combine(expandedFolders) { controls, expanded -> controls.copy(expandedFolderIds = expanded) }

    init {
        // The open folder is always visible in the navigator: its ancestors are held open the
        // moment it is chosen or restored, and only a tap on their chevrons closes them again.
        viewModelScope.launch {
            combine(currentFolder, sourceFolders) { folderId, folders -> folderId to folders }
                .collect { (folderId, folders) ->
                    val needed = FolderNavigator.revealing(folders.map(FolderEntity::toNode), folderId)
                    if (!expandedFolders.value.containsAll(needed)) setExpandedFolders(expandedFolders.value + needed)
                }
        }
        // The sort survives a restart, so a wall arranged by hand opens arranged by hand.
        settingsRepository?.let { settings ->
            viewModelScope.launch {
                settings.memoSortMode.first().let { stored ->
                    MemoSortMode.entries.firstOrNull { it.name == stored }?.let { sort.value = it }
                }
            }
        }
        viewModelScope.launch {
            tagRepository.observeAllTags().collect { tags ->
                selectedTagIds.value = selectedTagIds.value intersect
                    tags.mapTo(hashSetOf(), TagEntity::id)
            }
        }
        // What can stay picked is what is still on either page: memos and outlines. Measured against the memos
        // alone, picked outlines were let go whenever the memos table changed — every carry wrote it (2026-09-28).
        viewModelScope.launch {
            combine(sourceMemos, sourceOutlines) { memos, outlines ->
                memos.mapTo(hashSetOf(), MemoEntity::id).apply { outlines.mapTo(this, MemoEntity::id) }
            }.collect { active ->
                selection.value = selection.value.reconcile(active)
            }
        }
    }

    val uiState: StateFlow<MemoListUiState> = combine(sources, controls) { source, control ->
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        val sortedTags = source.tags.sortedWith { left, right ->
            collator.compare(left.name, right.name).takeIf { it != 0 } ?: left.id.compareTo(right.id)
        }
        val tagsById = sortedTags.associateBy(TagEntity::id)
        val effectiveTagIds = control.selectedTagIds.filterTo(linkedSetOf(), tagsById::containsKey)
        val tagIdsByMemo = source.relations.groupBy(MemoTagCrossRef::memoId)
            .mapValues { (_, values) -> values.mapTo(hashSetOf(), MemoTagCrossRef::tagId) }
        val tagsByMemo = tagIdsByMemo.mapValues { (_, ids) -> ids.mapNotNull(tagsById::get) }
        val removableTagIds = control.selection.selectedIds.flatMapTo(linkedSetOf()) {
            tagIdsByMemo[it].orEmpty()
        }
        // The open folder, if it still exists (a deleted one drops the pages back to the root).
        val folderId = control.currentFolderId?.takeIf { id -> source.folders.any { it.id == id } }
        val nodes = source.folders.map(FolderEntity::toNode)
        val childFolders = FolderTree.children(nodes, folderId)
            .mapNotNull { node -> source.folders.firstOrNull { it.id == node.id } }
            .sortedWith { left, right ->
                collator.compare(left.name, right.name).takeIf { it != 0 } ?: left.id.compareTo(right.id)
            }
        val folderPath = folderId?.let { id ->
            FolderTree.path(nodes, id).mapNotNull { node -> source.folders.firstOrNull { it.id == node.id } }
        }.orEmpty()
        val navigatorRows = FolderNavigator.rows(
            nodes,
            order = { left, right -> collator.compare(left.name, right.name).takeIf { it != 0 } ?: left.id.compareTo(right.id) },
            expanded = control.expandedFolderIds,
            selected = folderId,
        )
        // Browsing shows the open folder alone; a search reaches the whole kind, wherever a
        // document is filed — the search means what it meant before folders existed.
        val searching = control.query.isNotBlank()
        val memosInScope = if (searching) source.memos else source.memos.filter { it.folderId == folderId }
        val outlinesInScope = if (searching) source.outlines else source.outlines.filter { it.folderId == folderId }
        MemoListUiState(
            query = control.query,
            filter = control.filter,
            sort = control.sort,
            memos = MemoOrganizationPolicy.organize(
                memosInScope,
                control.query,
                control.filter,
                control.sort,
                tagIdsByMemo = tagIdsByMemo,
                selectedTagIds = effectiveTagIds,
                tagMatchMode = control.tagMatchMode,
                tagNamesByMemo = tagsByMemo.mapValues { (_, tags) -> tags.map(TagEntity::name) },
            ),
            totalMemoCount = source.memos.size,
            outlines = MemoOrganizationPolicy.organize(
                outlinesInScope,
                control.query,
                control.filter,
                control.sort,
                tagIdsByMemo = tagIdsByMemo,
                selectedTagIds = effectiveTagIds,
                tagMatchMode = control.tagMatchMode,
                tagNamesByMemo = tagsByMemo.mapValues { (_, tags) -> tags.map(TagEntity::name) },
            ),
            totalOutlineCount = source.outlines.size,
            folders = source.folders,
            currentFolderId = folderId,
            folderPath = folderPath,
            childFolders = childFolders,
            navigatorRows = navigatorRows,
            tags = sortedTags,
            tagsByMemo = tagsByMemo,
            selectedTagIds = effectiveTagIds,
            selectedTags = effectiveTagIds.mapNotNull(tagsById::get),
            tagMatchMode = control.tagMatchMode,
            selection = control.selection,
            removableTags = sortedTags.filter { it.id in removableTagIds },
            photoCounts = source.photoCounts.associate { it.memoId to it.count },
        )
    }
        // Off the main thread: with a query set, organize() folds and searches every body —
        // work that belongs on a worker, not under the keyboard. Emissions land on Main as
        // before; only the transform moves.
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            MemoListUiState(),
        )

    /**
     * Keeps memos read out of a Markdown file. Tags named by the file are attached, creating the
     * ones that do not exist yet, because that is what the file said the memo belongs to.
     */
    fun importMemos(memos: List<ImportedMemo>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val created = repository.createFromImport(memos)
            if (created.isNotEmpty()) {
                val known = tagRepository.observeAllTags().first()
                    .associateByTo(hashMapOf()) { TagNameNormalizer.normalizeKey(it.name) }
                created.forEach { (memoId, imported) ->
                    imported.tagNames.forEach { name ->
                        val key = TagNameNormalizer.normalizeKey(name)
                        if (key.isBlank()) return@forEach
                        val tag = known[key] ?: when (val result = tagRepository.create(name, now())) {
                            is TagMutationResult.Success -> result.tag?.also { known[key] = it }
                            else -> null
                        }
                        tag?.let { tagRepository.attach(memoId, it.id) }
                    }
                }
            }
            onDone(created.size)
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    /**
     * Makes an outline and hands back its id. It exists at once, empty, so the outliner can
     * open on it — the kind is set at birth and no screen changes it afterwards. It is filed
     * in the folder the pages are showing.
     */
    fun createOutline(onCreated: (Long) -> Unit) {
        viewModelScope.launch { onCreated(repository.createOutline(now(), currentFolder.value).id) }
    }

    // --- folders: where the pages stand, and what a folder is made into ---

    fun openFolder(folderId: Long) { if (!selection.value.isSelectionMode) setCurrentFolder(folderId) }

    /** Up one level. False at the root, where Back means what it always meant. */
    fun leaveFolder(): Boolean {
        val current = currentFolder.value ?: return false
        val nodes = uiState.value.folders.map(FolderEntity::toNode)
        setCurrentFolder(FolderTree.ancestors(nodes, current).lastOrNull()?.id)
        return true
    }

    /** Straight to [folderId] (null = the root) — a breadcrumb or navigator tap. */
    fun goToFolder(folderId: Long?) = setCurrentFolder(folderId)

    /** Opens or closes [folderId] in the navigator. */
    fun toggleFolderExpanded(folderId: Long) {
        val current = expandedFolders.value
        setExpandedFolders(if (folderId in current) current - folderId else current + folderId)
    }

    fun createFolder(name: String) = folderAction { it.create(name, currentFolder.value) }

    fun renameFolder(folderId: Long, name: String) = folderAction { it.rename(folderId, name) }

    fun moveFolder(folderId: Long, newParentId: Long?) = folderAction { it.move(folderId, newParentId) }

    /** Deletes the folder alone; what it held comes up to its parent. Leaves it if it was open. */
    fun deleteFolder(folderId: Long) = folderAction { folders ->
        val parent = folders.findById(folderId)?.parentFolderId
        folders.delete(folderId).also { result ->
            if (result is FolderResult.Done && currentFolder.value == folderId) setCurrentFolder(parent)
        }
    }

    /** Files a memo or an outline in [folderId] (null = the root). Only the place changes. */
    fun moveMemoToFolder(memoId: Long, folderId: Long?) = folderAction { it.assignMemo(memoId, folderId) }

    /**
     * Files every selected memo and outline in [folderId] (null = the root) — the selection's
     * フォルダへ移動. Only their place changes; a card whose folder vanished under it is reported,
     * the rest still move.
     */
    fun moveSelectionToFolder(folderId: Long?) = runBulk { ids ->
        val folders = folderRepository ?: return@runBulk "フォルダは使えません"
        var moved = 0
        var missingTarget = false
        ids.forEach { id ->
            when (folders.assignMemo(id, folderId)) {
                is FolderResult.Done -> moved++
                FolderResult.MissingTarget -> missingTarget = true
                else -> Unit
            }
        }
        if (missingTarget) "移動先のフォルダがありません" else "${moved}件をフォルダへ移動しました"
    }

    private fun folderAction(block: suspend (FolderRepository) -> FolderResult) {
        val folders = folderRepository ?: return
        viewModelScope.launch {
            val message = when (block(folders)) {
                is FolderResult.Done -> return@launch
                FolderResult.InvalidName -> "フォルダ名を入力してください"
                FolderResult.NotFound -> "そのフォルダはもうありません"
                FolderResult.MissingTarget -> "移動先のフォルダがありません"
                FolderResult.WouldCycle -> "フォルダを自分の中には移動できません"
            }
            _bulkEvents.emit(MemoBulkEvent.Completed(message))
        }
    }

    /**
     * The wall's own playback.
     *
     * It belongs to the list rather than to any memo on it, so it stops the moment what is on the
     * wall changes: a stream built from memos that are no longer shown is a stream about nothing.
     */
    val wallPlayback: StateFlow<CommentAnimationState> = wallAnimator.state

    fun playWall(bodies: List<String>, scope: WorkCommentScope, appSettings: AppSettings) {
        wallAnimator.play(wallTimelineFactory.create(bodies, scope, appSettings))
    }

    fun pauseWall() = wallAnimator.pause()

    fun resumeWall() = wallAnimator.resume()

    fun stopWall() = wallAnimator.stop()

    fun advanceWall(deltaMillis: Long) = wallAnimator.advanceBy(deltaMillis)

    fun wallOffsetPx(
        item: PlaybackItem,
        elapsedMillis: Long,
        containerWidthPx: Float,
        commentWidthPx: Float,
    ): Float = wallAnimator.horizontalOffsetPx(item, elapsedMillis, containerWidthPx, commentWidthPx)

    fun wallHasSomethingToSay(
        bodies: List<String>,
        scope: WorkCommentScope,
        appSettings: AppSettings,
    ): Boolean = wallTimelineFactory.create(bodies, scope, appSettings).items.isNotEmpty()

    override fun onCleared() {
        wallAnimator.stop()
        super.onCleared()
    }

    fun updateQuery(value: String) { if (!selection.value.isSelectionMode) query.value = value }
    fun updateFilter(value: MemoFilterMode) { if (!selection.value.isSelectionMode) filter.value = value }
    fun updateSort(value: MemoSortMode) {
        if (selection.value.isSelectionMode) return
        sort.value = value
        settingsRepository?.let { settings -> viewModelScope.launch { settings.setMemoSortMode(value.name) } }
    }

    /**
     * The cards of the open page in the order a drag left them. Their places are written, and
     * the page turns to 並べた順 if it was not there already — otherwise the move would vanish.
     */
    fun applyManualOrder(orderedIds: List<Long>) {
        viewModelScope.launch {
            repository.reorder(orderedIds)
            if (sort.value != MemoSortMode.MANUAL) {
                sort.value = MemoSortMode.MANUAL
                settingsRepository?.setMemoSortMode(MemoSortMode.MANUAL.name)
                _bulkEvents.emit(MemoBulkEvent.Completed("手動で並べた順に切り替えました"))
            }
        }
    }

    fun updateTagFilter(tagIds: Set<Long>, matchMode: TagMatchMode) {
        if (!selection.value.isSelectionMode) {
            selectedTagIds.value = tagIds
            tagMatchMode.value = matchMode
        }
    }

    fun requestSelectionMode() { selection.value = selection.value.request() }
    fun startSelection(memoId: Long) { selection.value = selection.value.selectOnly(memoId) }
    fun toggleSelection(memoId: Long) { selection.value = selection.value.toggle(memoId) }
    fun selectAllVisible() {
        selection.value = selection.value.selectAllVisible(uiState.value.memos.map(MemoEntity::id))
    }
    fun clearSelection() { selection.value = selection.value.clear() }

    fun setPinnedForSelection(pinned: Boolean) =
        setPinnedFor(selection.value.selectedIds, pinned)

    /**
     * One memo or several take the same turns.
     *
     * A memo acted on from its own menu is a set of one, so it lands in the same place, says the
     * same thing afterwards, and is undone the same way.
     */
    fun setPinnedFor(memoIds: Set<Long>, pinned: Boolean) = runOn(memoIds) { ids ->
        val result = repository.setPinnedForMemos(ids, pinned)
        "${result.count}件の${if (pinned) "ピンを固定" else "ピンを解除"}しました"
    }

    fun addTagsToSelection(tagIds: Set<Long>) = addTagsTo(selection.value.selectedIds, tagIds)

    fun addTagsTo(memoIds: Set<Long>, tagIds: Set<Long>) = runOn(memoIds) { ids ->
        val result = tagRepository.addTagsToMemos(ids, tagIds)
        "${result.count}件にタグを追加しました"
    }

    fun removeTagsFromSelection(tagIds: Set<Long>) = runBulk { ids ->
        val result = tagRepository.removeTagsFromMemos(ids, tagIds)
        "${result.count}件からタグを外しました"
    }

    fun archiveSelection() = archiveMemos(selection.value.selectedIds)

    fun archiveMemos(memoIds: Set<Long>) {
        if (memoIds.isEmpty()) return
        viewModelScope.launch {
            val change = repository.archiveMemos(memoIds)
            clearSelection()
            _bulkEvents.emit(
                MemoBulkEvent.Lifecycle(
                    "${change.count}件をアーカイブしました",
                    MemoLifecycleAction.ARCHIVED,
                    change,
                ),
            )
        }
    }

    fun trashSelection() = trashMemos(selection.value.selectedIds)

    fun trashMemos(memoIds: Set<Long>) {
        if (memoIds.isEmpty()) return
        viewModelScope.launch {
            val change = repository.moveMemosToTrash(memoIds)
            clearSelection()
            _bulkEvents.emit(
                MemoBulkEvent.Lifecycle(
                    "${change.count}件をゴミ箱へ移動しました",
                    MemoLifecycleAction.TRASHED,
                    change,
                ),
            )
        }
    }

    /** Copies a memo, its tags included, under a title that says it is a copy. */
    fun duplicate(memoId: Long, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val source = repository.findById(memoId)
            if (source == null) {
                onDone(false)
                return@launch
            }
            // Links resolve by title, so two memos may not share one. A copy of an outline
            // is an outline: the kind travels with the words.
            val copy = repository.save(
                existing = null,
                title = source.title.ifBlank {
                    if (source.isOutline) "無題のアウトライナー" else "無題のメモ"
                } + "のコピー",
                body = source.body,
                now = System.currentTimeMillis(),
                kind = source.memoKind,
                folderId = source.folderId,
            )
            if (copy == null) {
                onDone(false)
                return@launch
            }
            val tagIds = tagRepository.observeTagsForMemo(memoId).first()
                .mapTo(hashSetOf(), TagEntity::id)
            if (tagIds.isNotEmpty()) tagRepository.addTagsToMemos(setOf(copy.id), tagIds)
            onDone(true)
        }
    }

    private fun runBulk(block: suspend (Set<Long>) -> String) =
        runOn(selection.value.selectedIds, block)

    private fun runOn(memoIds: Set<Long>, block: suspend (Set<Long>) -> String) {
        if (memoIds.isEmpty()) return
        viewModelScope.launch {
            val message = block(memoIds)
            clearSelection()
            _bulkEvents.emit(MemoBulkEvent.Completed(message))
        }
    }

    fun undo(event: MemoBulkEvent.Lifecycle) {
        viewModelScope.launch {
            when (event.action) {
                MemoLifecycleAction.ARCHIVED -> repository.undoArchiveMemos(event.change)
                MemoLifecycleAction.TRASHED -> repository.undoMoveMemosToTrash(event.change)
            }
        }
    }

    companion object {
        // Saved-state keys: the open folder and the navigator's expanded folders.
        const val KEY_CURRENT_FOLDER = "currentFolderId"
        const val KEY_EXPANDED_FOLDERS = "expandedFolderIds"

        fun factory(
            repository: MemoRepository,
            tagRepository: TagRepository,
            attachmentRepository: AttachmentRepository,
            settingsRepository: SettingsRepository? = null,
            folderRepository: FolderRepository? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MemoListViewModel(
                    repository,
                    tagRepository,
                    attachmentRepository,
                    settingsRepository,
                    folderRepository,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }
}

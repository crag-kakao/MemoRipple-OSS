package io.github.cragcoffee.memoripple.ui.memos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.PhotoAttachment
import io.github.cragcoffee.memoripple.data.TagEntity
import io.github.cragcoffee.memoripple.domain.memos.displayTitle
import io.github.cragcoffee.memoripple.ui.attachments.AttachmentImageLoader
import io.github.cragcoffee.memoripple.ui.attachments.PhotoAttachmentStrip
import io.github.cragcoffee.memoripple.ui.components.DestructiveTextButton
import io.github.cragcoffee.memoripple.ui.components.ProductEmptyState
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.components.ProductTopBar
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.Flow

/** The archive holds both kinds; where each opens is the caller's one rule, not decided here. */
@Composable
fun MemoArchiveRoute(onBack: () -> Unit, onOpenMemo: (MemoEntity) -> Unit) {
    MemoCollectionRoute(MemoCollection.ARCHIVE, onBack, onOpenMemo)
}

@Composable
fun MemoTrashRoute(onBack: () -> Unit) {
    MemoCollectionRoute(MemoCollection.TRASH, onBack, onOpenMemo = {})
}

@Composable
private fun MemoCollectionRoute(
    collection: MemoCollection,
    onBack: () -> Unit,
    onOpenMemo: (MemoEntity) -> Unit,
) {
    val app = LocalContext.current.applicationContext as MemoRippleApplication
    val viewModel: MemoLifecycleViewModel = viewModel(
        key = collection.name,
        factory = MemoLifecycleViewModel.factory(app.memoRepository, app.tagRepository, collection),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val imageLoader = remember(app.attachmentRepository.blobStore) {
        AttachmentImageLoader(app.attachmentRepository.blobStore)
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val result = snackbar.showSnackbar(
                event.message,
                actionLabel = if (event.undoMemoId != null) "元に戻す" else null,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undo(event)
        }
    }
    MemoCollectionScreen(
        collection = collection,
        state = state,
        snackbar = snackbar,
        onBack = onBack,
        onQueryChange = viewModel::updateQuery,
        onOpenMemo = onOpenMemo,
        onUnarchive = viewModel::unarchive,
        onTrash = viewModel::moveToTrash,
        onRestore = viewModel::restore,
        onDelete = viewModel::deletePermanently,
        onEmptyTrash = viewModel::emptyTrash,
        observePhotos = app.attachmentRepository::observeMemoPhotos,
        imageLoader = imageLoader,
    )
}

@Composable
private fun MemoCollectionScreen(
    collection: MemoCollection,
    state: MemoLifecycleUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onOpenMemo: (MemoEntity) -> Unit,
    onUnarchive: (Long) -> Unit,
    onTrash: (Long) -> Unit,
    onRestore: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onEmptyTrash: () -> Unit,
    observePhotos: (Long) -> Flow<List<PhotoAttachment>>,
    imageLoader: AttachmentImageLoader,
) {
    var pendingDelete by remember { mutableStateOf<Long?>(null) }
    var showEmptyConfirmation by remember { mutableStateOf(false) }
    val isTrash = collection == MemoCollection.TRASH
    Scaffold(
        topBar = {
            ProductTopBar(
                title = if (isTrash) "ゴミ箱" else "アーカイブ",
                onBack = onBack,
                actions = {
                    if (isTrash && state.memos.isNotEmpty()) {
                        TextButton(
                            onClick = { showEmptyConfirmation = true },
                            modifier = Modifier.testTag("empty_trash"),
                        ) { Text("空にする") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).widthIn(max = 720.dp)
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().testTag(if (isTrash) "trash_search" else "archive_search"),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text("タイトルと本文を検索") },
            )
            if (state.memos.isEmpty()) {
                ProductEmptyState(
                    title = if (state.query.isNotBlank()) "検索結果がありません" else if (isTrash) "ゴミ箱は空です" else "アーカイブは空です",
                    description = if (state.query.isNotBlank()) "検索語に一致するメモがありません。" else if (isTrash) "削除したメモはここに移動します。" else "アーカイブしたメモはここに表示されます。",
                    modifier = Modifier.fillMaxSize().testTag(if (isTrash) "trash_empty_state" else "archive_empty_state"),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(top = ProductSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(ProductSpacing.md),
                ) {
                    items(state.memos, key = MemoEntity::id) { memo ->
                        val photos by remember(memo.id) { observePhotos(memo.id) }
                            .collectAsStateWithLifecycle(emptyList())
                        LifecycleMemoCard(
                            memo,
                            state.tagsByMemo[memo.id].orEmpty(),
                            photos,
                            imageLoader,
                            isTrash,
                            onOpen = { if (!isTrash) onOpenMemo(memo) },
                            onPrimary = { if (isTrash) onRestore(memo.id) else onUnarchive(memo.id) },
                            onSecondary = { if (isTrash) pendingDelete = memo.id else onTrash(memo.id) },
                        )
                    }
                }
            }
        }
    }
    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("メモを完全に削除しますか？") },
            text = { Text("コメントやタグとの関連も削除され、この操作は元に戻せません。") },
            confirmButton = {
                DestructiveTextButton("完全に削除", onClick = {
                    onDelete(id)
                    pendingDelete = null
                })
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("キャンセル") } },
        )
    }
    if (showEmptyConfirmation) {
        AlertDialog(
            onDismissRequest = { showEmptyConfirmation = false },
            title = { Text("ゴミ箱を空にしますか？") },
            text = { Text("ゴミ箱内のすべてのメモを完全に削除します。この操作は元に戻せません。") },
            confirmButton = {
                DestructiveTextButton("空にする", onClick = {
                    onEmptyTrash()
                    showEmptyConfirmation = false
                })
            },
            dismissButton = { TextButton(onClick = { showEmptyConfirmation = false }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun LifecycleMemoCard(
    memo: MemoEntity,
    tags: List<TagEntity>,
    photos: List<PhotoAttachment>,
    imageLoader: AttachmentImageLoader,
    trash: Boolean,
    onOpen: () -> Unit,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("${if (trash) "trash" else "archive"}_memo_${memo.id}")
            .clickable(enabled = !trash, onClick = onOpen),
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(memo.displayTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (memo.body.isNotBlank()) Text(memo.body.replace('\n', ' '), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (tags.isNotEmpty()) Text(tags.joinToString("  ") { "#${it.name}" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (photos.isNotEmpty()) {
                PhotoAttachmentStrip(
                    photos = photos,
                    imageLoader = imageLoader,
                    editable = false,
                    importing = false,
                    onDelete = {},
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            val lifecycleAt = if (trash) memo.trashedAt else memo.archivedAt
            Text(
                (if (trash) "ゴミ箱へ移動: " else "アーカイブ: ") +
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(requireNotNull(lifecycleAt))),
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onPrimary, modifier = Modifier.testTag("${if (trash) "restore" else "unarchive"}_${memo.id}")) {
                    Text(if (trash) "復元" else "戻す")
                }
                TextButton(onClick = onSecondary, modifier = Modifier.testTag("${if (trash) "delete_forever" else "archive_trash"}_${memo.id}")) {
                    Text(if (trash) "完全に削除" else "ゴミ箱へ")
                }
            }
        }
    }
}

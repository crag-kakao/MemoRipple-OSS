package io.github.cragcoffee.memoripple.ui.memos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.data.TagEntity
import io.github.cragcoffee.memoripple.data.TagMutationResult
import io.github.cragcoffee.memoripple.data.TagRepository
import io.github.cragcoffee.memoripple.data.TagSummary
import io.github.cragcoffee.memoripple.domain.tags.TagNameIssue
import io.github.cragcoffee.memoripple.domain.tags.TagNameNormalizer
import io.github.cragcoffee.memoripple.domain.tags.TagNameValidation
import io.github.cragcoffee.memoripple.ui.components.DestructiveTextButton
import io.github.cragcoffee.memoripple.ui.components.ProductEmptyState
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.components.ProductTopBar
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TagManagementViewModel(private val repository: TagRepository) : ViewModel() {
    val tags: StateFlow<List<TagSummary>> = repository.observeTagSummaries()
        .map { values ->
            val collator = Collator.getInstance(Locale.getDefault()).apply {
                strength = Collator.PRIMARY
            }
            values.sortedWith { left, right ->
                collator.compare(left.name, right.name).takeIf { it != 0 }
                    ?: left.id.compareTo(right.id)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun create(name: String, onComplete: (Boolean) -> Unit) = mutate(onComplete) {
        repository.create(name, System.currentTimeMillis())
    }

    fun rename(tagId: Long, name: String, onComplete: (Boolean) -> Unit) = mutate(onComplete) {
        repository.rename(tagId, name)
    }

    fun delete(summary: TagSummary) {
        viewModelScope.launch {
            repository.delete(
                TagEntity(summary.id, summary.name, summary.normalizedName, summary.createdAt),
            )
        }
    }

    fun clearMessage() { _message.value = null }

    private fun mutate(
        onComplete: (Boolean) -> Unit,
        operation: suspend () -> TagMutationResult,
    ) {
        viewModelScope.launch {
            when (val result = operation()) {
                is TagMutationResult.Success -> onComplete(true)
                TagMutationResult.Duplicate -> {
                    _message.value = "同じ名前のタグがあります"
                    onComplete(false)
                }
                is TagMutationResult.InvalidName -> {
                    _message.value = when (result.issue) {
                        TagNameIssue.BLANK -> "タグ名を入力してください"
                        TagNameIssue.TOO_LONG -> "タグ名は40文字以内で入力してください"
                    }
                    onComplete(false)
                }
                TagMutationResult.Missing -> {
                    _message.value = "タグが見つかりません"
                    onComplete(false)
                }
            }
        }
    }

    companion object {
        fun factory(repository: TagRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TagManagementViewModel(repository) as T
            }
    }
}

@Composable
fun TagManagementRoute(onBack: () -> Unit) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    val viewModel: TagManagementViewModel = viewModel(
        factory = TagManagementViewModel.factory(application.tagRepository),
    )
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    TagManagementScreen(
        tags = tags,
        message = message,
        onBack = onBack,
        onCreate = viewModel::create,
        onRename = viewModel::rename,
        onDelete = viewModel::delete,
        onMessageShown = viewModel::clearMessage,
    )
}

@Composable
private fun TagManagementScreen(
    tags: List<TagSummary>,
    message: String?,
    onBack: () -> Unit,
    onCreate: (String, (Boolean) -> Unit) -> Unit,
    onRename: (Long, String, (Boolean) -> Unit) -> Unit,
    onDelete: (TagSummary) -> Unit,
    onMessageShown: () -> Unit,
) {
    var editTarget by remember { mutableStateOf<TagSummary?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<TagSummary?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); onMessageShown() }
    }
    Scaffold(
        topBar = { ProductTopBar("タグを管理", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }, modifier = Modifier.testTag("create_tag")) {
                Icon(Icons.Outlined.Add, contentDescription = "新しいタグ")
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (tags.isEmpty()) {
                ProductEmptyState(
                    title = "タグはまだありません",
                    description = "メモをテーマや用途で整理できます。",
                    actionLabel = "新しいタグ",
                    onAction = { showCreate = true },
                    modifier = Modifier.fillMaxSize().testTag("tag_management_empty"),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().widthIn(max = 720.dp)
                        .padding(horizontal = ProductSize.screenHorizontalPadding)
                        .testTag("tag_management_list"),
                    verticalArrangement = Arrangement.spacedBy(ProductSpacing.sm),
                ) {
                    items(tags, key = TagSummary::id) { tag ->
                        TagManagementRow(
                            tag = tag,
                            onRename = { editTarget = tag },
                            onDelete = { deleteTarget = tag },
                        )
                    }
                }
            }
        }
    }

    if (showCreate || editTarget != null) {
        TagNameDialog(
            title = if (editTarget == null) "新しいタグ" else "タグ名を変更",
            initialValue = editTarget?.name.orEmpty(),
            confirmLabel = if (editTarget == null) "作成" else "変更",
            onConfirm = { value, finished ->
                val target = editTarget
                if (target == null) {
                    onCreate(value) {
                        finished()
                        if (it) showCreate = false
                    }
                } else {
                    onRename(target.id, value) {
                        finished()
                        if (it) editTarget = null
                    }
                }
            },
            onDismiss = { showCreate = false; editTarget = null },
        )
    }
    deleteTarget?.let { tag ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("タグ「${tag.name}」を削除しますか？") },
            text = { Text("このタグはメモから外れますが、メモ自体は削除されません。") },
            confirmButton = {
                DestructiveTextButton(
                    label = "タグを削除",
                    onClick = {
                        onDelete(tag)
                        deleteTarget = null
                    },
                )
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun TagManagementRow(tag: TagSummary, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(tag.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${tag.memoCount}件", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(
                onClick = { menu = true },
                modifier = Modifier.sizeIn(
                    minWidth = ProductSize.minimumTouchTarget,
                    minHeight = ProductSize.minimumTouchTarget,
                ).testTag("tag_more_${tag.id}"),
            ) { Icon(Icons.Outlined.MoreVert, contentDescription = "${tag.name}タグの操作") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("名前を変更") },
                    onClick = { menu = false; onRename() },
                    modifier = Modifier.testTag("rename_tag_${tag.id}"),
                )
                DropdownMenuItem(
                    text = { Text("削除") },
                    onClick = { menu = false; onDelete() },
                    modifier = Modifier.testTag("delete_tag_${tag.id}"),
                )
            }
        }
    }
}

@Composable
private fun TagNameDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onConfirm: (String, () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    var submitting by remember { mutableStateOf(false) }
    val valid = TagNameNormalizer.validate(value) is TagNameValidation.Valid
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("タグ名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("tag_name_input"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    submitting = true
                    onConfirm(value) { submitting = false }
                },
                enabled = valid && !submitting,
                modifier = Modifier.testTag("confirm_tag_name"),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

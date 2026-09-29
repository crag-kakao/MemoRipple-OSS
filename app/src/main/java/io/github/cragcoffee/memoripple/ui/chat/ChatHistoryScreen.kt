package io.github.cragcoffee.memoripple.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatConversation
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationStore
import io.github.cragcoffee.memoripple.domain.ai.conversation.LastConversationStore
import io.github.cragcoffee.memoripple.ui.components.ProductCompactTopBar
import io.github.cragcoffee.memoripple.ui.components.ProductEmptyState
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * チャット履歴 (docs/AI_CONVERSATION_HISTORY.md): the conversations, newest activity first — a
 * title, when it was last touched, a delete with a confirmation; 「すべてのチャット履歴を削除」 with
 * its own confirmation. A tap makes a conversation the current one and returns to the chat. Only
 * conversations, messages and safe context are ever removed here — never a document.
 */
@Composable
fun ChatHistoryRoute(onBack: () -> Unit) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    ChatHistoryScreen(store = application.chatHistoryRepository, current = application.lastConversationStore, onBack = onBack)
}

@Composable
internal fun ChatHistoryScreen(store: ConversationStore, current: LastConversationStore, onBack: () -> Unit) {
    val conversations by store.conversations().collectAsStateWithLifecycle(initialValue = emptyList())
    val currentId by current.lastConversationId.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var deleteAsk by remember { mutableStateOf<ChatConversation?>(null) }
    var deleteAllAsk by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            ProductCompactTopBar(
                centerContent = { Text("チャット履歴", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("chat_history_back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = ProductSize.screenHorizontalPadding).testTag("chat_history_screen"),
            verticalArrangement = Arrangement.spacedBy(ProductSpacing.sm),
        ) {
            if (conversations.isEmpty()) {
                item(key = "empty") {
                    ProductEmptyState(title = "チャット履歴はありません", description = "AIモードで最初のメッセージを送ると、ここに残ります。", modifier = Modifier.fillMaxWidth().testTag("chat_history_empty"))
                }
            }
            items(conversations, key = { it.id }) { conversation ->
                ConversationRow(
                    conversation = conversation,
                    isCurrent = conversation.id == currentId,
                    onOpen = { scope.launch { current.setLastConversationId(conversation.id); withContext(Dispatchers.Main.immediate) { onBack() } } },
                    onDelete = { deleteAsk = conversation },
                )
            }
            if (conversations.isNotEmpty()) {
                item(key = "delete_all") {
                    Column(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(ProductSpacing.lg))
                        OutlinedButton(onClick = { deleteAllAsk = true }, modifier = Modifier.fillMaxWidth().testTag("chat_history_delete_all")) {
                            Text("すべてのチャット履歴を削除", color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(ProductSpacing.xl))
                    }
                }
            }
        }
    }
    deleteAsk?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleteAsk = null },
            modifier = Modifier.testTag("chat_history_delete_dialog"),
            title = { Text("「${conversation.title}」を削除しますか？") },
            text = { Text("このチャットの履歴を端末から削除します。メモや日記は削除されません。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteAsk = null
                        scope.launch {
                            if (currentId == conversation.id) current.setLastConversationId(null)
                            store.delete(conversation.id)
                        }
                    },
                    modifier = Modifier.testTag("chat_history_delete_confirm"),
                ) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteAsk = null }, modifier = Modifier.testTag("chat_history_delete_cancel")) { Text("キャンセル") } },
        )
    }
    if (deleteAllAsk) {
        AlertDialog(
            onDismissRequest = { deleteAllAsk = false },
            modifier = Modifier.testTag("chat_history_delete_all_dialog"),
            title = { Text("すべてのチャット履歴を削除しますか？") },
            text = { Text("すべてのチャットの履歴を端末から削除します。メモや日記は削除されません。") },
            confirmButton = {
                TextButton(
                    onClick = { deleteAllAsk = false; scope.launch { current.setLastConversationId(null); store.deleteAll() } },
                    modifier = Modifier.testTag("chat_history_delete_all_confirm"),
                ) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteAllAsk = false }, modifier = Modifier.testTag("chat_history_delete_all_cancel")) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun ConversationRow(conversation: ChatConversation, isCurrent: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = isCurrent }.testTag("chat_history_row_${conversation.id}"),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = ProductSpacing.sm, bottom = ProductSpacing.sm, end = ProductSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(conversation.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatUpdated(conversation.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(ProductSize.minimumTouchTarget).testTag("chat_history_delete_${conversation.id}")) {
                Icon(Icons.Outlined.Delete, contentDescription = "このチャットを削除")
            }
        }
    }
}

private fun formatUpdated(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M/d HH:mm", Locale.JAPAN))

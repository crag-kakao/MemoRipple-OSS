package io.github.cragcoffee.memoripple.ui.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.domain.diary.FutureDiaryCommentItem
import io.github.cragcoffee.memoripple.domain.diary.FutureCommentExpression
import io.github.cragcoffee.memoripple.domain.comments.CommentMotion
import io.github.cragcoffee.memoripple.ui.components.DestructiveTextButton
import io.github.cragcoffee.memoripple.ui.components.ProductSheetHeader
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.components.ProductStatusChip
import io.github.cragcoffee.memoripple.ui.components.SectionHeader
import io.github.cragcoffee.memoripple.ui.memos.CommentExpressionSheet
import io.github.cragcoffee.memoripple.ui.memos.CommentExpressionVariant
import io.github.cragcoffee.memoripple.ui.memos.commentExpressionSummary
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun FutureDiaryCommentSection(
    comments: List<FutureDiaryCommentItem>,
    canCreate: Boolean,
    onCreate: () -> Unit,
    onDelete: (Long) -> Unit,
    onReplay: (Long) -> Unit,
) {
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = ProductSpacing.lg)) {
        HorizontalDivider()
        // Secondary to the body (2026-09-23): a quiet heading and one short line, not a block of
        // explanation. What it does and when it opens are unchanged.
        SectionHeader(
            title = "未来の自分へ",
            supportingText = "指定した日時まで封印して送れます。",
            modifier = Modifier.padding(top = ProductSpacing.lg),
        )
        if (canCreate) {
            OutlinedButton(
                onClick = onCreate,
                modifier = Modifier.align(Alignment.End).padding(top = ProductSpacing.md)
                    .testTag("open_future_comment_creator"),
            ) { Text("未来へ送る") }
        }
        if (comments.isEmpty()) {
            Text(
                "まだメッセージはありません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm).testTag("future_comment_empty_state"),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(comments, key = { it.id }) { comment ->
                    FutureCommentRow(
                        comment = comment,
                        onDelete = { pendingDeleteId = comment.id },
                        onReplay = { onReplay(comment.id) },
                    )
                }
            }
        }
    }

    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("この未来コメントを削除しますか？") },
            text = { Text("削除すると、このコメントを受け取ることはできません。内容は表示されません。") },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("キャンセル") }
            },
            confirmButton = {
                DestructiveTextButton(
                    label = "削除",
                    modifier = Modifier.testTag("confirm_delete_future_comment"),
                    onClick = {
                        onDelete(id)
                        pendingDeleteId = null
                    },
                )
            },
        )
    }
}

@Composable
private fun FutureCommentRow(
    comment: FutureDiaryCommentItem,
    onDelete: () -> Unit,
    onReplay: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = ProductSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = ProductSpacing.md)) {
                when (comment) {
                    is FutureDiaryCommentItem.Sealed -> {
                        ProductStatusChip("未来へ送信済み")
                        Text(
                            "${formatFutureDateTime(comment.revealAt)}に公開予定",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = ProductSpacing.sm),
                        )
                    }
                    is FutureDiaryCommentItem.Delivered -> {
                        ProductStatusChip("受け取り待ち")
                        Text(
                            formatFutureDateTime(comment.revealAt),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = ProductSpacing.sm),
                        )
                    }
                    is FutureDiaryCommentItem.AwaitingPresentation -> {
                        ProductStatusChip("受け取りを続ける")
                        Text(
                            formatFutureDateTime(comment.revealAt),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = ProductSpacing.sm),
                        )
                    }
                    is FutureDiaryCommentItem.Revealed -> {
                        ProductStatusChip("受け取り済み")
                        Text(comment.text, modifier = Modifier.padding(top = ProductSpacing.sm))
                        Text(
                            formatFutureDateTime(comment.revealAt),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!comment.expression.isDefault) {
                            Text(
                                comment.expression.summary(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = ProductSpacing.xs)
                                    .testTag("future_comment_expression_summary"),
                            )
                        }
                    }
                }
            }
            if (comment is FutureDiaryCommentItem.Revealed ||
                comment is FutureDiaryCommentItem.AwaitingPresentation
            ) {
                IconButton(onClick = onReplay, modifier = Modifier.testTag("replay_future_comment")) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = "未来コメントを再生")
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "未来コメントを削除")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FutureCommentCreatorSheet(
    initialDate: LocalDate,
    onResolveRevealAt: (LocalDate, LocalTime) -> Long,
    isFutureRevealAt: (Long) -> Boolean,
    onDismiss: () -> Unit,
    onSend: (String, Long, FutureCommentExpression) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(initialDate) }
    var time by remember { mutableStateOf(LocalTime.of(9, 0)) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }
    var showExpression by remember { mutableStateOf(false) }
    var expression by remember { mutableStateOf(FutureCommentExpression.Default) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val revealAt = onResolveRevealAt(date, time)
    val revealIsFuture = isFutureRevealAt(revealAt)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = ProductSpacing.xl)
                .padding(bottom = ProductSpacing.xxl),
        ) {
            ProductSheetHeader("未来の自分へ", onDismiss)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.lg)
                    .testTag("future_comment_input"),
                label = { Text("未来コメント") },
                minLines = 3,
                maxLines = 6,
            )
            SectionHeader("公開日時", modifier = Modifier.padding(top = ProductSpacing.xl))
            Row(Modifier.fillMaxWidth().padding(top = ProductSpacing.sm)) {
                OutlinedButton(onClick = { showDatePicker = true }) {
                    Text(date.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.JAPAN)))
                }
                OutlinedButton(
                    onClick = { showTimePicker = true },
                    modifier = Modifier.padding(start = ProductSpacing.sm),
                ) {
                    Text(time.format(DateTimeFormatter.ofPattern("HH:mm")))
                }
            }
            if (!revealIsFuture) {
                Text(
                    "公開日時は未来に設定してください",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = ProductSpacing.sm),
                )
            }
            OutlinedButton(
                onClick = { showExpression = true },
                modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.lg)
                    .testTag("future_comment_expression"),
            ) {
                Text(
                    "表現: ${commentExpressionSummary(
                        appearance = expression.appearance,
                        motion = CommentMotion(mode = expression.motionMode),
                    )}",
                )
            }
            Button(
                onClick = { showConfirmation = true },
                enabled = text.isNotBlank() && revealIsFuture,
                modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.xl)
                    .testTag("send_future_comment"),
            ) { Text("未来へ送る") }
        }
    }

    if (showDatePicker) {
        val selectedMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = selectedMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("決定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("キャンセル") }
            },
        ) { DatePicker(state = pickerState) }
    }

    if (showTimePicker) {
        val pickerState = rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    time = LocalTime.of(pickerState.hour, pickerState.minute)
                    showTimePicker = false
                }) { Text("決定") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("キャンセル") }
            },
        )
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("未来へ送りますか？") },
            text = {
                Text(
                    "送信すると、${formatFutureDateTime(revealAt)}まで内容を見ることも、" +
                    "編集することもできません。公開日時も変更できません。" +
                        "送った後は、コメントの表現も変更できません。",
                )
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) { Text("キャンセル") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmation = false
                        onSend(text, revealAt, expression)
                    },
                    modifier = Modifier.testTag("confirm_send_future_comment"),
                ) { Text("未来へ送る") }
            },
        )
    }

    if (showExpression) {
        CommentExpressionSheet(
            initialAppearance = expression.appearance,
            initialMotion = CommentMotion(mode = expression.motionMode),
            previewText = text,
            variant = CommentExpressionVariant.FUTURE,
            onDismiss = { showExpression = false },
            onConfirm = { appearance, motion ->
                expression = FutureCommentExpression(
                    appearance = appearance,
                    motionMode = motion.mode,
                )
                showExpression = false
            },
        )
    }
}

internal fun formatFutureDateTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.JAPAN))

internal fun FutureCommentExpression.summary(): String = commentExpressionSummary(
    appearance = appearance,
    motion = CommentMotion(mode = motionMode),
)

package io.github.cragcoffee.memoripple.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.portableexport.PortableExportEngine
import io.github.cragcoffee.memoripple.ui.components.ImportWording
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing

/**
 * 読める形式で書き出す — the confirmation before anything leaves. Deliberately not worded
 * like the backup: this makes ordinary files people read elsewhere, and it says so, along
 * with what that openness means.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PortableExportSheet(
    counts: PortableExportEngine.ExportCounts,
    includeTrash: Boolean,
    onIncludeTrashChange: (Boolean) -> Unit,
    stripPhotoMetadata: Boolean,
    onStripPhotoMetadataChange: (Boolean) -> Unit,
    format: PortableExportViewModel.Format,
    onFormatChange: (PortableExportViewModel.Format) -> Unit,
    onChooseDestination: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Content-heavy: opens fully so the plain-language notes are on screen, not folded
        // under a half-open sheet.
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        ),
        modifier = Modifier.testTag("portable_export_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ProductSize.screenHorizontalPadding),
        ) {
            Text("読める形式で書き出す", style = MaterialTheme.typography.titleLarge)
            Text(
                "メモや日記をMarkdownと写真にまとめて、ほかのアプリやパソコンでも" +
                    "読めるZIPファイルを作成します。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = ProductSpacing.xs),
            )
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(top = ProductSpacing.md)
                    .testTag("portable_export_counts"),
            ) {
                CountLine("メモ", "${counts.activeMemos}件")
                CountLine("アーカイブ", "${counts.archivedMemos}件")
                CountLine("日記", "${counts.diaries}件")
                CountLine("ノート", "${counts.notes}冊")
                CountLine("写真", "${counts.photos}枚")
                if (counts.photos > 0) {
                    CountLine("写真の容量（概算）", formatBytes(counts.estimatedPhotoBytes))
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement
                    .spacedBy(ProductSpacing.sm),
                modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.md),
            ) {
                Text("形式", style = MaterialTheme.typography.bodyLarge)
                androidx.compose.material3.FilterChip(
                    selected = format == PortableExportViewModel.Format.ZIP,
                    onClick = { onFormatChange(PortableExportViewModel.Format.ZIP) },
                    label = { Text("ZIP（Markdown＋HTML）") },
                    modifier = Modifier.testTag("portable_export_format_zip"),
                )
                androidx.compose.material3.FilterChip(
                    selected = format == PortableExportViewModel.Format.PDF,
                    onClick = { onFormatChange(PortableExportViewModel.Format.PDF) },
                    label = { Text("PDF") },
                    modifier = Modifier.testTag("portable_export_format_pdf"),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.md),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("ゴミ箱のメモも含める", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "含めた場合はZIP内のtrashフォルダに分かれます（${counts.trashedMemos}件）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = includeTrash,
                    onCheckedChange = onIncludeTrashChange,
                    modifier = Modifier.testTag("portable_export_trash_toggle"),
                )
            }
            if (format == PortableExportViewModel.Format.ZIP) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "写真の位置情報などを取り除く",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "JPEGの撮影日時・機種・位置情報を削除します（画質は変わりません）。" +
                                "その他の形式はそのまま書き出します",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = stripPhotoMetadata,
                        onCheckedChange = onStripPhotoMetadataChange,
                        modifier = Modifier.testTag("portable_export_strip_toggle"),
                    )
                }
            } else {
                Text(
                    "PDFは写真を描き直して載せるため、撮影日時・位置情報などの" +
                        "メタデータは含まれません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = ProductSpacing.sm)
                        .testTag("portable_export_pdf_note"),
                )
            }
            Text(
                "・これはMemoRippleへの完全復元用バックアップではありません\n" +
                    "・ZIP内の文章と写真は暗号化されません。保存先を利用できる人は内容を読めます\n" +
                    "・未開封の未来コメントは書き出しません\n" +
                    "・写真には撮影日時・機種・位置情報などのメタデータが含まれる可能性があります\n" +
                    "・保存先はこの後のファイル選択で選んだ場所だけです。Google Driveへは送信しません",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = ProductSpacing.md)
                    .testTag("portable_export_notes"),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ProductSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("portable_export_cancel"),
                ) { Text("キャンセル") }
                TextButton(
                    onClick = onChooseDestination,
                    modifier = Modifier.testTag("portable_export_choose"),
                ) { Text("保存先を選ぶ") }
            }
        }
    }
}

@Composable
private fun CountLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

/** The run itself: a count-based bar and one way out. Dismissal only through the buttons. */
@Composable
internal fun PortableExportProgressDialog(
    done: Int,
    total: Int,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("読める形式で書き出しています") },
        text = {
            Column {
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { done / total.toFloat() },
                        modifier = Modifier.fillMaxWidth().testTag("portable_export_progress"),
                    )
                    Spacer(Modifier.height(ProductSpacing.sm))
                    Text("$done / $total")
                } else {
                    CircularProgressIndicator()
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("portable_export_stop"),
            ) { Text("中止") }
        },
    )
}

@Composable
internal fun PortableExportResultDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("読める形式で書き出す") },
        text = { Text(message, modifier = Modifier.testTag("portable_export_result")) },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("portable_export_result_ok"),
            ) { Text("OK") }
        },
    )
}

/** The import's one question, with the counts to answer it by. Nothing moves until 取り込む. */
@Composable
internal fun PortableImportConfirmDialog(
    memoCount: Int,
    photoCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("書き出したZIPを取り込む") },
        text = {
            Text(
                ImportWording.portablePreview(documents = memoCount, photos = photoCount),
                modifier = Modifier.testTag("portable_import_preview"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("portable_import_confirm"),
            ) { Text("取り込む") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("portable_import_cancel"),
            ) { Text("キャンセル") }
        },
    )
}

@Composable
internal fun PortableImportProgressDialog(done: Int, total: Int, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("取り込んでいます") },
        text = {
            Column {
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { done / total.toFloat() },
                        modifier = Modifier.fillMaxWidth().testTag("portable_import_progress"),
                    )
                    Spacer(Modifier.height(ProductSpacing.sm))
                    Text("$done / $total")
                } else {
                    CircularProgressIndicator()
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("portable_import_stop"),
            ) { Text("中止") }
        },
    )
}

/** 毎日か毎週か — the automatic export's whole calendar. */
@Composable
internal fun PortableAutoExportIntervalDialog(
    currentDays: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自動書き出しの間隔") },
        text = {
            Column {
                listOf(1 to "毎日", 7 to "毎週").forEach { (days, label) ->
                    TextButton(
                        onClick = { onPick(days) },
                        modifier = Modifier.fillMaxWidth()
                            .testTag("auto_export_interval_$days"),
                    ) {
                        Text(
                            if ((currentDays >= 7) == (days >= 7)) "✓ $label" else label,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

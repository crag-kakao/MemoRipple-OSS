package io.github.cragcoffee.memoripple.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplatePolicy
import io.github.cragcoffee.memoripple.domain.memos.StarterTemplates
import io.github.cragcoffee.memoripple.domain.memos.TemplateAction
import io.github.cragcoffee.memoripple.domain.memos.TemplateFile
import io.github.cragcoffee.memoripple.domain.memos.TemplateImport
import io.github.cragcoffee.memoripple.ui.components.ProductInfoBanner
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.components.ProductTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * テンプレート (docs/CHAT_UI_TEMPLATE_V2.md): every template, with what it does; a tap opens the
 * editor; ＋ makes a new one; 書き出し / 読み込み move the whole list as the MemoRipple template file
 * (validated on the way in). Nothing here runs a template.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TemplatesRoute(onBack: () -> Unit, onEdit: (String?) -> Unit, onOpenFolders: () -> Unit = {}) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    val repository = application.templateRepository
    val context = LocalContext.current
    val templates by repository.templates.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    // the one template a long press offers to delete (2026-09-23); null while nothing is asked
    var deleteAsk by remember { mutableStateOf<MemoTemplate?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(TemplateFile.MIME_TYPE)) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = TemplateFile.export(repository.current())
                    context.contentResolver.openOutputStream(uri, "w")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
                }.getOrDefault(false)
            }
            message = if (ok) "テンプレートを書き出しました。" else "書き出せませんでした。"
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { input -> readBounded(input) } }.getOrNull()
            }
            message = when (val r = if (text == null) TemplateImport.Rejected("ファイルを読めませんでした") else TemplateFile.import(text)) {
                is TemplateImport.Rejected -> "読み込めません: ${r.reason}"
                is TemplateImport.Ready -> {
                    repository.replaceAll(MemoTemplatePolicy.upsertAll(repository.current(), r.templates))
                    "${r.templates.size}件のテンプレートを読み込みました。"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            ProductTopBar(
                title = "テンプレート",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { onEdit(null) }, modifier = Modifier.testTag("settings_add_template")) {
                        Icon(Icons.Outlined.Add, contentDescription = "テンプレートを追加")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (message != null) {
                ProductInfoBanner(title = message!!, supportingText = "", actionLabel = "閉じる", onAction = { message = null }, modifier = Modifier.fillMaxWidth().testTag("templates_message"), actionTestTag = "templates_message_dismiss")
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = ProductSpacing.lg, vertical = ProductSpacing.sm)) {
                OutlinedButton(onClick = { exportLauncher.launch("memoripple-templates" + TemplateFile.FILE_EXTENSION) }, enabled = templates.isNotEmpty(), modifier = Modifier.weight(1f).padding(end = ProductSpacing.xs).testTag("templates_export")) { Text("テンプレートを書き出す") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.weight(1f).padding(start = ProductSpacing.xs).testTag("templates_import")) { Text("テンプレートを読み込む") }
            }
            // template folders (2026-09-22): the user's own organisation for the ＋ picker's 自分のテンプレート
            TextButton(onClick = onOpenFolders, modifier = Modifier.padding(horizontal = ProductSpacing.md).testTag("templates_folders")) { Text("フォルダを管理") }
            LazyColumn(Modifier.fillMaxSize().testTag("templates_list")) {
                if (templates.isEmpty()) {
                    item(key = "empty") {
                        Column(Modifier.fillMaxWidth().padding(ProductSpacing.lg)) {
                            Text("まだテンプレートがありません", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("templates_empty"))
                            Text(
                                "＋で作成するか、下のスターターを複製して編集できます。チャットの＋から実行できます。この端末に保存されます。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = ProductSpacing.sm),
                            )
                        }
                    }
                }
                    items(templates, key = MemoTemplate::id) { template ->
                        // a long press offers to delete this one template (2026-09-23, the user's review); a tap still opens the editor
                        ListItem(
                            headlineContent = { Text(template.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    template.description.ifBlank { template.body.lineSequence().firstOrNull().orEmpty() },
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingContent = { Text(actionLabel(template), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).combinedClickable(onClick = { onEdit(template.id) }, onLongClick = { deleteAsk = template }, onLongClickLabel = "削除").testTag("settings_template_${template.id}"),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    // the six built-in starters: read-only here; a tap opens the editor on a copy the user may change and keep
                    item(key = "starters_title") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = ProductSpacing.lg).padding(top = ProductSpacing.lg, bottom = ProductSpacing.xs)) {
                            Text("スターターテンプレート", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("settings_starters"))
                            Text("最初から使えるテンプレートです。タップすると複製して編集できます。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(StarterTemplates.all, key = { "starter-${it.id}" }) { template ->
                        ListItem(
                            headlineContent = { Text(template.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(template.description, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailingContent = { Text(actionLabel(template), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onEdit(template.id) }.testTag("settings_starter_${template.id}"),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
            }
        }
    }
    deleteAsk?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteAsk = null },
            title = { Text("「${target.name}」を削除しますか？") },
            text = { Text("このテンプレートだけを削除します。メモや日記は削除されません。") },
            confirmButton = {
                TextButton(
                    onClick = { deleteAsk = null; scope.launch { repository.delete(target.id); application.settingsRepository.removeHomeShortcut(target.id) } },
                    modifier = Modifier.testTag("settings_template_delete_confirm"),
                ) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteAsk = null }) { Text("キャンセル") } },
            modifier = Modifier.testTag("settings_template_delete_dialog"),
        )
    }
}

/** What a template does, in the user's words, with how many things it asks for. */
private fun actionLabel(template: MemoTemplate): String =
    when (template.action) { TemplateAction.CREATE -> "作成する"; TemplateAction.SEARCH -> "探す"; TemplateAction.APPEND -> "追記する" } +
        if (template.fields.isNotEmpty()) "・項目${template.fields.size}" else ""

/** Reads at most the file cap plus one byte, so an oversized file is refused by the validator instead of filling memory. */
private fun readBounded(input: java.io.InputStream): String {
    val limit = TemplateFile.MAX_FILE_CHARS * 4 + 1
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0
    while (total <= limit) {
        val n = input.read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        total += n
    }
    return out.toString(Charsets.UTF_8.name())
}

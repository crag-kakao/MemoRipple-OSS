package io.github.cragcoffee.memoripple.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.domain.memos.StarterTemplates
import io.github.cragcoffee.memoripple.domain.memos.TemplateFolder
import io.github.cragcoffee.memoripple.domain.memos.TemplateFolderPolicy
import io.github.cragcoffee.memoripple.ui.components.ProductTopBar
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * テンプレートを管理 → フォルダ (2026-09-22): the user's template folders — one flat level — made, renamed,
 * moved up or down, and deleted here. A deleted folder never deletes a template: its templates become
 * 未分類 (`TemplateRepository.clearFolder`). Template organisation only; the wall's folders are elsewhere.
 */
@Composable
fun TemplateFoldersRoute(onBack: () -> Unit) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    val folders by application.templateFolderRepository.folders.collectAsState(initial = emptyList())
    val templates by application.templateRepository.templates.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf<TemplateFolder?>(null) }   // a folder being renamed, or a new one (blank id)
    var deleting by remember { mutableStateOf<TemplateFolder?>(null) }
    val counts = remember(templates, folders) { folders.associate { f -> f.id to templates.count { !StarterTemplates.isStarter(it.id) && it.folderId == f.id } } }

    Scaffold(
        topBar = {
            ProductTopBar(
                title = "フォルダ",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { naming = TemplateFolder("", "", folders.size) }, enabled = folders.size < TemplateFolderPolicy.MAX_FOLDERS, modifier = Modifier.testTag("template_folders_add")) {
                        Icon(Icons.Outlined.Add, contentDescription = "フォルダを追加")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("template_folders_list")) {
            item(key = "note") {
                Text(
                    "自分のテンプレートを入れるフォルダです（1階層）。フォルダを削除しても、中のテンプレートは未分類に戻るだけです。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = ProductSpacing.lg, vertical = ProductSpacing.sm),
                )
            }
            if (folders.isEmpty()) {
                item(key = "empty") { Text("まだフォルダはありません", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(ProductSpacing.lg).testTag("template_folders_empty")) }
            }
            val sorted = folders.sortedBy { it.order }
            items(sorted, key = TemplateFolder::id) { folder ->
                ListItem(
                    headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${counts[folder.id] ?: 0}件") },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { scope.launch { application.templateFolderRepository.move(folder.id, -1) } }, enabled = sorted.first().id != folder.id, modifier = Modifier.testTag("template_folder_up_${folder.id}")) { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "上へ") }
                            IconButton(onClick = { scope.launch { application.templateFolderRepository.move(folder.id, 1) } }, enabled = sorted.last().id != folder.id, modifier = Modifier.testTag("template_folder_down_${folder.id}")) { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "下へ") }
                            IconButton(onClick = { naming = folder }, modifier = Modifier.testTag("template_folder_rename_${folder.id}")) { Icon(Icons.Outlined.Edit, contentDescription = "名前を変更") }
                            IconButton(onClick = { deleting = folder }, modifier = Modifier.testTag("template_folder_delete_${folder.id}")) { Icon(Icons.Outlined.Delete, contentDescription = "削除") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("template_folder_${folder.id}"),
                )
            }
        }
    }

    naming?.let { target ->
        var name by remember(target) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(if (target.id.isBlank()) "フォルダを追加" else "名前を変更") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名前") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("template_folder_name_field")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clean = TemplateFolderPolicy.cleanName(name)
                        scope.launch {
                            if (target.id.isBlank()) application.templateFolderRepository.save(TemplateFolder(UUID.randomUUID().toString(), clean, target.order))
                            else application.templateFolderRepository.rename(target.id, clean)
                        }
                        naming = null
                    },
                    modifier = Modifier.testTag("template_folder_name_confirm"),
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("やめる") } },
            modifier = Modifier.testTag("template_folder_name_dialog"),
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("「${target.name}」を削除しますか？") },
            text = { Text("中のテンプレート（${counts[target.id] ?: 0}件）は削除されず、未分類に戻ります。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            // the folder goes, its templates stay — unassigned, nothing else about them touched
                            application.templateRepository.clearFolder(target.id)
                            application.templateFolderRepository.delete(target.id)
                        }
                        deleting = null
                    },
                    modifier = Modifier.testTag("template_folder_delete_confirm"),
                ) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("やめる") } },
        )
    }
}

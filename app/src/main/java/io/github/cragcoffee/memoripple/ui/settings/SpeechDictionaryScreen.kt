package io.github.cragcoffee.memoripple.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.domain.speech.SpeechDictionaryEntry
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.components.ProductTopBar
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * The readings the writer teaches the 読み上げ: 「兎にも角にも」 is said とにもかくにも, not
 * うさぎにもつのにも. Each row is one correction — the word as written, the word as said — with
 * its own switch, so a rule can rest without being forgotten. Applied to every text the app
 * speaks, before it reaches the engine.
 */
@Composable
fun SpeechDictionaryRoute(onBack: () -> Unit) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    val repository = application.settingsRepository
    val entries by repository.speechDictionary.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<SpeechDictionaryEntry?>(null) }
    var adding by remember { mutableStateOf(false) }

    fun save(next: List<SpeechDictionaryEntry>) {
        scope.launch { repository.setSpeechDictionary(next) }
    }

    Scaffold(
        topBar = {
            ProductTopBar(
                title = "ユーザー辞書",
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = { adding = true },
                        modifier = Modifier.testTag("add_speech_dictionary_entry"),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = "読みを追加")
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(ProductSpacing.lg),
            ) {
                Text(
                    "まだ読みがありません",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("speech_dictionary_empty"),
                )
                Text(
                    "＋で「書かれた言葉」と「読み上げる言葉」を登録すると、" +
                        "読み上げがその読みで話します。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = ProductSpacing.sm),
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).testTag("speech_dictionary_list"),
            ) {
                items(entries, key = SpeechDictionaryEntry::id) { entry ->
                    ListItem(
                        headlineContent = {
                            Text(entry.surface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                entry.reading,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = entry.enabled,
                                    onCheckedChange = { enabled ->
                                        save(
                                            entries.map {
                                                if (it.id == entry.id) it.copy(enabled = enabled)
                                                else it
                                            },
                                        )
                                    },
                                    modifier = Modifier
                                        .testTag("speech_dictionary_switch_${entry.id}"),
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable { editing = entry }
                            .testTag("speech_dictionary_entry_${entry.id}"),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    if (adding) {
        SpeechDictionaryEntryDialog(
            title = "読みを追加",
            initialSurface = "",
            initialReading = "",
            onConfirm = { surface, reading ->
                save(
                    entries + SpeechDictionaryEntry(
                        id = UUID.randomUUID().toString(),
                        surface = surface,
                        reading = reading,
                    ),
                )
                adding = false
            },
            onDismiss = { adding = false },
            onDelete = null,
        )
    }
    editing?.let { entry ->
        SpeechDictionaryEntryDialog(
            title = "読みを編集",
            initialSurface = entry.surface,
            initialReading = entry.reading,
            onConfirm = { surface, reading ->
                save(
                    entries.map {
                        if (it.id == entry.id) it.copy(surface = surface, reading = reading)
                        else it
                    },
                )
                editing = null
            },
            onDismiss = { editing = null },
            onDelete = {
                save(entries.filterNot { it.id == entry.id })
                editing = null
            },
        )
    }
}

@Composable
private fun SpeechDictionaryEntryDialog(
    title: String,
    initialSurface: String,
    initialReading: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var surface by remember { mutableStateOf(initialSurface) }
    var reading by remember { mutableStateOf(initialReading) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = surface,
                    onValueChange = { surface = it },
                    label = { Text("書かれた言葉") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("speech_dictionary_surface_field"),
                )
                OutlinedTextField(
                    value = reading,
                    onValueChange = { reading = it },
                    label = { Text("読み上げる言葉") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = ProductSpacing.sm)
                        .testTag("speech_dictionary_reading_field"),
                )
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .padding(top = ProductSpacing.sm)
                            .testTag("speech_dictionary_delete"),
                    ) {
                        Text("この読みを削除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(surface.trim(), reading.trim()) },
                enabled = surface.isNotBlank(),
                modifier = Modifier.testTag("speech_dictionary_save"),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
        modifier = Modifier.testTag("speech_dictionary_dialog"),
    )
}

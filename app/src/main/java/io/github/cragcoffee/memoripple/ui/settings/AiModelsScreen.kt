package io.github.cragcoffee.memoripple.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.domain.ai.models.CatalogEntry
import io.github.cragcoffee.memoripple.domain.ai.models.DeleteOutcome
import io.github.cragcoffee.memoripple.domain.ai.models.DownloadRequest
import io.github.cragcoffee.memoripple.domain.ai.models.InstallFailure
import io.github.cragcoffee.memoripple.domain.ai.models.InstallState
import io.github.cragcoffee.memoripple.domain.ai.models.ModelManager
import io.github.cragcoffee.memoripple.ui.components.ProductCompactTopBar
import io.github.cragcoffee.memoripple.ui.components.ProductInfoBanner
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import io.github.cragcoffee.memoripple.ui.components.ProductStatusChip
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton

/**
 * Local AI モデル (docs/AI_MODEL_MANAGEMENT.md): the two catalog models, described neutrally, each
 * with its size, its state, and the verbs — download / resume / retry / cancel, delete (with a
 * confirmation), select. Nothing here starts by itself; nothing here loads a model; the screen
 * knows the manager and its states, not a file, a hash or an engine.
 */
@Composable
fun AiModelsRoute(onBack: () -> Unit) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    val manager = application.modelManager
    val states by manager.states.collectAsStateWithLifecycle()
    val selected by manager.selectedModelId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var meteredAsk by remember { mutableStateOf<Pair<CatalogEntry, Long>?>(null) }
    var deleteAsk by remember { mutableStateOf<CatalogEntry?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val supported = manager.deviceSupported
    // Phase 7: what is on disk, as it is now — a file that vanished or is the wrong length since the last refresh shows so
    LaunchedEffect(Unit) { manager.refresh() }

    fun request(entry: CatalogEntry, allowMetered: Boolean) {
        scope.launch {
            when (val r = manager.requestDownload(entry.modelId, allowMetered)) {
                DownloadRequest.Started -> message = null
                is DownloadRequest.NeedsMeteredConfirmation -> meteredAsk = entry to r.remainingBytes
                is DownloadRequest.InsufficientStorage -> message = "空き容量が足りません（必要 約${gb(r.requiredBytes)}、空き 約${gb(r.availableBytes)}）。"
                is DownloadRequest.LowBattery -> message = "バッテリー残量が少ないため（${r.percent} %）、ダウンロードを始めませんでした。充電してからお試しください。"
                DownloadRequest.UnsupportedDevice -> message = "この端末では現在Local AIを利用できません。"
                DownloadRequest.Offline -> message = "ネットワークに接続していないため、ダウンロードを始められません。ダウンロード済みのモデルはオフラインでも使えます。"
                DownloadRequest.AlreadyDownloading, DownloadRequest.AnotherDownloadRunning -> message = "ダウンロードは一つずつ行います。"
                DownloadRequest.AlreadyInstalled -> message = null
                DownloadRequest.UnknownModel -> message = null
            }
        }
    }

    AiModelsScreen(
        entries = manager.entries,
        states = states,
        selected = selected,
        supported = supported,
        message = message,
        onDismissMessage = { message = null },
        onBack = onBack,
        onDownload = { entry -> request(entry, allowMetered = false) },
        onCancel = { entry -> scope.launch { manager.cancel(entry.modelId) } },
        onSelect = { entry -> scope.launch { manager.select(entry.modelId) } },
        onDelete = { entry -> deleteAsk = entry },
    )

    meteredAsk?.let { (entry, bytes) ->
        AlertDialog(
            onDismissRequest = { meteredAsk = null },
            modifier = Modifier.testTag("ai_model_metered_dialog"),
            title = { Text("モバイルデータでダウンロードしますか？") },
            text = { Text("Wi-Fi に接続していません。${entry.displayName} のダウンロードには約${gb(bytes)}の通信が必要です。") },
            confirmButton = { TextButton(onClick = { meteredAsk = null; request(entry, allowMetered = true) }, modifier = Modifier.testTag("ai_model_metered_confirm")) { Text("ダウンロード") } },
            dismissButton = { TextButton(onClick = { meteredAsk = null }, modifier = Modifier.testTag("ai_model_metered_cancel")) { Text("キャンセル") } },
        )
    }
    deleteAsk?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteAsk = null },
            modifier = Modifier.testTag("ai_model_delete_dialog"),
            title = { Text("${entry.displayName} を削除しますか？") },
            text = { Text("端末からモデルファイルを削除します。メモや日記は削除されません。もう一度使うにはダウンロードし直します。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteAsk = null
                        scope.launch {
                            when (manager.delete(entry.modelId)) {
                                DeleteOutcome.Downloading -> message = "ダウンロード中は削除できません。先にキャンセルしてください。"
                                else -> message = null
                            }
                        }
                    },
                    modifier = Modifier.testTag("ai_model_delete_confirm"),
                ) { Text("削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteAsk = null }, modifier = Modifier.testTag("ai_model_delete_cancel")) { Text("キャンセル") } },
        )
    }
}

/** The screen over its inputs alone (internal so the layout / semantics tests can render it without the manager). */
@Composable
internal fun AiModelsScreen(
    entries: List<CatalogEntry>,
    states: Map<String, InstallState>,
    selected: String?,
    supported: Boolean,
    message: String? = null,
    onDismissMessage: () -> Unit = {},
    onBack: () -> Unit = {},
    onDownload: (CatalogEntry) -> Unit = {},
    onCancel: (CatalogEntry) -> Unit = {},
    onSelect: (CatalogEntry) -> Unit = {},
    onDelete: (CatalogEntry) -> Unit = {},
) {
    Scaffold(
        topBar = {
            ProductCompactTopBar(
                centerContent = { Text("Local AIモデル", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("ai_models_back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = ProductSize.screenHorizontalPadding).testTag("ai_models_screen"),
            verticalArrangement = Arrangement.spacedBy(ProductSpacing.md),
        ) {
            item(key = "intro") {
                Text(
                    "メモや日記を探す・追記や作成の内容を提案するAIは、この端末の中で動きます。使うモデルを一つ選択してください。" +
                        "どちらを選んでも、書き込みはあなたが確認したときにだけ行われます。モデルはアプリには含まれず、ここからダウンロードします。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm).testTag("ai_models_intro"),
                )
            }
            if (!supported) {
                item(key = "unsupported") {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("ai_models_unsupported"),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("この端末では現在Local AIを利用できません", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(ProductSpacing.xs))
                            Text("AIモデルの実行に必要なCPU機能に対応していないため、ダウンロードも行いません。検索など、AI以外の機能はそのまま使えます。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (message != null) {
                item(key = "message") {
                    ProductInfoBanner(
                        title = message,
                        supportingText = "",
                        actionLabel = "閉じる",
                        onAction = onDismissMessage,
                        modifier = Modifier.fillMaxWidth().testTag("ai_model_message"),
                        actionTestTag = "ai_model_message_dismiss",
                    )
                }
            }
            items(entries, key = { it.modelId }) { entry ->
                ModelCard(
                    entry = entry,
                    state = states[entry.modelId] ?: InstallState.NotInstalled,
                    isSelected = selected == entry.modelId,
                    supported = supported,
                    onDownload = { onDownload(entry) },
                    onCancel = { onCancel(entry) },
                    onSelect = { onSelect(entry) },
                    onDelete = { onDelete(entry) },
                )
            }
            item(key = "bottom") { Spacer(Modifier.height(ProductSpacing.xl)) }
        }
    }
}

@Composable
private fun ModelCard(
    entry: CatalogEntry,
    state: InstallState,
    isSelected: Boolean,
    supported: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val id = entry.modelId
    val status = statusText(state)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        // Phase 7: a screen reader hears the card as selected or not, and its state in one sentence
        modifier = Modifier.fillMaxWidth().semantics { this.selected = isSelected; stateDescription = if (isSelected) "$status、使用中" else status }.testTag("ai_model_$id"),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(entry.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(ProductSpacing.xs))
            // Phase 7: the chips wrap (a fixed row clipped them at large font scales); the state chip tells installed from selected
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs), verticalArrangement = Arrangement.spacedBy(ProductSpacing.xs), modifier = Modifier.fillMaxWidth()) {
                ProductStatusChip(entry.category.label)
                ProductStatusChip(stateLabel(state), modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("ai_model_state_$id"))
                if (isSelected) {
                    ProductStatusChip("使用中", modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("ai_model_selected_$id"))
                }
            }
            Spacer(Modifier.height(ProductSpacing.xs))
            Text(entry.description, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(ProductSpacing.sm))
            entry.strengths.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall) }
            entry.tradeoffs.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(ProductSpacing.sm))
            Text(
                "ダウンロード 約${gb(entry.approximateDownloadBytes)} ・ 読み込み時のメモリ 約${gb(entry.approximateLoadedMemoryBytes)} ・ ${entry.license.summary}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(entry.source.publisherNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(ProductSpacing.sm))
            Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("ai_model_status_$id"))
            if (state is InstallState.Downloading) {
                val fraction = if (state.totalBytes > 0) (state.bytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = ProductSpacing.xs).testTag("ai_model_progress_$id"))
            }
            Spacer(Modifier.height(ProductSpacing.sm))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ProductSpacing.sm), verticalArrangement = Arrangement.spacedBy(ProductSpacing.xs), modifier = Modifier.fillMaxWidth()) {
                when (state) {
                    is InstallState.Installed -> {
                        if (!isSelected) {
                            Button(onClick = onSelect, enabled = supported, modifier = Modifier.testTag("ai_model_select_$id")) { Text("使用する") }
                        }
                        OutlinedButton(onClick = onDelete, modifier = Modifier.testTag("ai_model_delete_$id")) { Text("削除") }
                    }
                    is InstallState.Downloading, InstallState.Checking, InstallState.Verifying -> {
                        OutlinedButton(onClick = onCancel, enabled = state is InstallState.Downloading, modifier = Modifier.testTag("ai_model_action_$id")) { Text("キャンセル") }
                    }
                    is InstallState.Partial -> {
                        Button(onClick = onDownload, enabled = supported, modifier = Modifier.testTag("ai_model_action_$id")) { Text("再開") }
                        OutlinedButton(onClick = onDelete, modifier = Modifier.testTag("ai_model_delete_$id")) { Text("削除") }
                    }
                    is InstallState.Failed -> {
                        Button(onClick = onDownload, enabled = supported, modifier = Modifier.testTag("ai_model_action_$id")) { Text("再試行") }
                        if (state.partialBytes > 0) OutlinedButton(onClick = onDelete, modifier = Modifier.testTag("ai_model_delete_$id")) { Text("削除") }
                    }
                    InstallState.NotInstalled -> {
                        Button(onClick = onDownload, enabled = supported, modifier = Modifier.testTag("ai_model_action_$id")) { Text("ダウンロード") }
                    }
                }
            }
        }
    }
}

private fun statusText(state: InstallState): String = when (state) {
    InstallState.NotInstalled -> "未ダウンロード"
    is InstallState.Partial -> "ダウンロード途中（約${gb(state.bytes)}まで受信）"
    InstallState.Checking -> "確認しています…"
    is InstallState.Downloading -> if (state.totalBytes > 0) "ダウンロード中 ${gb(state.bytes)} / ${gb(state.totalBytes)}" else "ダウンロード中 ${gb(state.bytes)}"
    InstallState.Verifying -> "ファイルを検証しています…"
    is InstallState.Installed -> "ダウンロード済み（${gb(state.bytes)}）"
    is InstallState.Failed -> when (state.reason) {
        InstallFailure.NETWORK -> "ダウンロードが中断されました。ネットワークを確認して再試行してください。"
        InstallFailure.CHECKSUM_MISMATCH -> "ファイルの検証に失敗したため、削除しました。再試行してください。"
        InstallFailure.INSUFFICIENT_STORAGE -> "空き容量が足りません。"
        InstallFailure.UNSUPPORTED_DEVICE -> "この端末では利用できません。"
        InstallFailure.STORAGE_IO -> "保存できませんでした。空き容量を確認して再試行してください。"
        InstallFailure.CORRUPT_FILE -> "ファイルが壊れています（サイズが一致しません）。再試行するとダウンロードし直します。"
    }
}

/** The state as one word on a chip: installed is not selected, and the selected chip stands beside it. */
private fun stateLabel(state: InstallState): String = when (state) {
    InstallState.NotInstalled -> "未ダウンロード"
    is InstallState.Partial -> "ダウンロード途中"
    InstallState.Checking, is InstallState.Downloading -> "ダウンロード中"
    InstallState.Verifying -> "検証中"
    is InstallState.Installed -> "ダウンロード済み"
    is InstallState.Failed -> "失敗"
}

private fun gb(bytes: Long): String = String.format(java.util.Locale.JAPAN, "%.2f GB", bytes / 1_000_000_000.0)

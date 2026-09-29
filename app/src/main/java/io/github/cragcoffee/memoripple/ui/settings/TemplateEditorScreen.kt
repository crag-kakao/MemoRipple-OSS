package io.github.cragcoffee.memoripple.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.MemoRippleApplication
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplatePolicy
import io.github.cragcoffee.memoripple.domain.memos.StarterTemplates
import io.github.cragcoffee.memoripple.domain.memos.TemplateAction
import io.github.cragcoffee.memoripple.domain.memos.TemplateBodyDisplay
import io.github.cragcoffee.memoripple.domain.memos.TemplateDateToken
import io.github.cragcoffee.memoripple.domain.memos.TemplateField
import io.github.cragcoffee.memoripple.domain.memos.TemplateFieldType
import io.github.cragcoffee.memoripple.domain.memos.TemplateFlow
import io.github.cragcoffee.memoripple.domain.memos.TemplateFolder
import io.github.cragcoffee.memoripple.domain.memos.TemplateValues
import io.github.cragcoffee.memoripple.domain.memos.TemplateRenderer
import io.github.cragcoffee.memoripple.domain.memos.TemplateRendering
import io.github.cragcoffee.memoripple.domain.memos.TemplateScript
import io.github.cragcoffee.memoripple.domain.memos.TemplateSearchSpec
import io.github.cragcoffee.memoripple.domain.memos.TemplateTargetSpec
import io.github.cragcoffee.memoripple.domain.memos.TemplateValidation
import io.github.cragcoffee.memoripple.ui.components.ProductCompactTopBar
import io.github.cragcoffee.memoripple.ui.components.ProductSize
import io.github.cragcoffee.memoripple.ui.components.ProductSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

/**
 * The template editor (docs/CHAT_UI_TEMPLATE_V2.md §14), step by step — 基本情報 → 操作 → 入力項目
 * → 内容 → 確認 — in the user's words: 作成する / 探す / 追記する, メモ / アウトライン / 日記, a field by
 * its name and kind (1行 / 複数行 / 日付 / 選択 / はい・いいえ), never a key, a type name, a schema
 * or a `{{…}}`. A field goes into the body through 「項目を挿入」 as `⟦名前⟧`; the stored body keeps
 * `{{key}}` with a key the person never sees. 確認 shows a preview with sample values, then 保存
 * validates the definition (`TemplateValidation`) and names any problem in plain words. Needs no
 * model. Saving replaces the template with the same id; a starter opens as a copy (a new id).
 */
@Composable
fun TemplateEditorRoute(templateId: String?, onDone: () -> Unit) {
    val application = LocalContext.current.applicationContext as MemoRippleApplication
    val repository = application.templateRepository
    val folders by application.templateFolderRepository.folders.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    // a draft from 「この会話からテンプレートを作成」 rides the hand-off: taken once, edited like a new template, saved only here
    val draft = remember { if (templateId == null) application.templateDraftHandoff.take() else null }
    var initial by remember { mutableStateOf<MemoTemplate?>(draft) }
    var copyOfStarter by remember { mutableStateOf(draft != null) }
    var loaded by remember { mutableStateOf(templateId == null) }
    LaunchedEffect(templateId) {
        if (templateId != null) {
            val own = repository.current().firstOrNull { it.id == templateId }
            val starter = if (own == null) StarterTemplates.find(templateId) else null
            initial = own ?: starter?.copy(id = UUID.randomUUID().toString(), createdAt = 0, updatedAt = 0)
            copyOfStarter = own == null && starter != null
            loaded = true
        }
    }
    if (!loaded) return
    TemplateEditorScreen(
        initial = initial,
        isNew = initial == null || copyOfStarter,
        today = application.timeProvider.currentLocalDate(),
        now = { application.timeProvider.nowMillis() },
        onSave = { template -> scope.launch { repository.save(template); withContext(Dispatchers.Main.immediate) { onDone() } } },
        onDelete = if (initial != null && !copyOfStarter) ({ scope.launch { repository.delete(initial!!.id); withContext(Dispatchers.Main.immediate) { onDone() } } }) else null,
        onBack = onDone,
        folders = folders,
    )
}

/** One field being edited: everything as text so the form never loses a keystroke; the key is the app's, validated on save. */
internal data class FieldDraft(
    val key: String,
    val label: String = "",
    val type: TemplateFieldType = TemplateFieldType.TEXT,
    val required: Boolean = false,
    val default: String = "",
    val choices: String = "",
    /** The question the chat asks (§16); empty → asked by the label. */
    val question: String = "",
) {
    fun toField() = TemplateField(key, label.trim(), type, required, default.trim(), choices.split("\n", "、", ",").map { it.trim() }.filter { it.isNotEmpty() }, question.trim())

    companion object {
        fun of(f: TemplateField) = FieldDraft(f.key, f.label, f.type, f.required, f.default, f.choices.joinToString("\n"), f.question)
    }
}

private val STEPS = listOf("基本情報", "操作", "入力項目", "内容", "確認")

@Composable
internal fun TemplateEditorScreen(
    initial: MemoTemplate?,
    isNew: Boolean = initial == null,
    today: LocalDate = LocalDate.now(),
    now: () -> Long,
    onSave: (MemoTemplate) -> Unit,
    onDelete: (() -> Unit)?,
    onBack: () -> Unit,
    /** The user's template folders (2026-09-22); the editor picks one, or 未分類. */
    folders: List<TemplateFolder> = emptyList(),
) {
    var step by remember { mutableStateOf(0) }
    var folderId by remember { mutableStateOf(initial?.folderId) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var action by remember { mutableStateOf(initial?.action ?: TemplateAction.CREATE) }
    // 整理する (docs/THINK_TEMPLATES.md): a flow, not an action — the questions end in a result, a memo only on request
    var think by remember { mutableStateOf(initial?.flow == TemplateFlow.THINK) }
    var kind by remember { mutableStateOf(initial?.documentKind ?: DocumentKind.MEMO) }
    var fields by remember { mutableStateOf<List<FieldDraft>>(initial?.fields?.map { FieldDraft.of(it) } ?: emptyList()) }
    // the body as the person sees it: ⟦名前⟧ for a field; turned back into {{key}} on save
    var body by remember { mutableStateOf(TextFieldValue(TemplateBodyDisplay.toDisplay(initial?.body.orEmpty(), initial?.fields.orEmpty()))) }
    var query by remember { mutableStateOf(initial?.searchSpec?.query.orEmpty()) }
    var dateToken by remember { mutableStateOf(initial?.searchSpec?.dateToken) }
    var kinds by remember { mutableStateOf(initial?.searchSpec?.kinds ?: setOf(DocumentKind.MEMO, DocumentKind.OUTLINE, DocumentKind.JOURNAL)) }
    var askTarget by remember { mutableStateOf(initial?.targetSpec !is TemplateTargetSpec.Named) }
    var targetName by remember { mutableStateOf((initial?.targetSpec as? TemplateTargetSpec.Named)?.name.orEmpty()) }
    var targetQuestion by remember { mutableStateOf(initial?.targetQuestion.orEmpty()) }
    var problems by remember { mutableStateOf<List<String>>(emptyList()) }
    var deleteAsk by remember { mutableStateOf(false) }
    var stepNote by remember { mutableStateOf<String?>(null) }

    fun currentFields(): List<TemplateField> = fields.map { it.toField() }

    fun build(): MemoTemplate {
        val stamp = now()
        val storedBody = TemplateBodyDisplay.fromDisplay(body.text, currentFields())
        return MemoTemplate(
            id = initial?.id ?: UUID.randomUUID().toString(),
            name = MemoTemplatePolicy.cleanName(name),
            body = if (action == TemplateAction.SEARCH) "" else storedBody,
            description = description.trim(),
            action = if (think) TemplateAction.CREATE else action,
            documentKind = if (think) DocumentKind.MEMO else kind,
            flow = if (think) TemplateFlow.THINK else TemplateFlow.RECORD,
            folderId = folderId?.takeIf { id -> folders.any { it.id == id } },
            fields = currentFields(),
            searchSpec = if (action == TemplateAction.SEARCH) TemplateSearchSpec(query.trim(), dateToken, kinds) else null,
            targetSpec = if (action == TemplateAction.APPEND) (if (askTarget) TemplateTargetSpec.AskAtRun else TemplateTargetSpec.Named(targetName.trim())) else null,
            targetQuestion = if (action == TemplateAction.APPEND && askTarget) targetQuestion.trim() else "",
            createdAt = initial?.createdAt?.takeIf { it > 0 } ?: stamp,
            updatedAt = stamp,
        )
    }

    /** The one thing a step insists on before the next: a name on the first. Everything else is judged at 保存. */
    fun next() {
        if (step == 0 && name.isBlank()) { stepNote = "名前を入力してください"; return }
        stepNote = null
        step = (step + 1).coerceAtMost(STEPS.lastIndex)
    }
    fun prev() { stepNote = null; if (step == 0) onBack() else step -= 1 }

    BackHandler { prev() }

    Scaffold(
        topBar = {
            ProductCompactTopBar(
                centerContent = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(if (isNew) "テンプレートを作成" else "テンプレートを編集", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                        Text("${step + 1} / ${STEPS.size}　${STEPS[step]}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("template_editor_step_$step"))
                    }
                },
                navigationIcon = { IconButton(onClick = { prev() }, modifier = Modifier.testTag("template_editor_back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る") } },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = ProductSize.screenHorizontalPadding, vertical = ProductSpacing.sm), horizontalArrangement = Arrangement.spacedBy(ProductSpacing.sm)) {
                    OutlinedButton(onClick = { prev() }, modifier = Modifier.weight(1f).testTag("template_editor_prev")) { Text(if (step == 0) "やめる" else "戻る") }
                    if (step < STEPS.lastIndex) {
                        Button(onClick = { next() }, modifier = Modifier.weight(1f).testTag("template_editor_next")) { Text("次へ") }
                    } else {
                        Button(
                            onClick = {
                                val t = build()
                                val found = TemplateValidation.problems(t)
                                if (found.isEmpty()) onSave(t) else problems = found
                            },
                            modifier = Modifier.weight(1f).testTag("template_editor_save"),
                        ) { Text("保存") }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = ProductSize.screenHorizontalPadding).testTag("template_editor"),
            verticalArrangement = Arrangement.spacedBy(ProductSpacing.sm),
        ) {
            stepNote?.let { note ->
                item(key = "note") { Text(note, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = ProductSpacing.sm).testTag("template_editor_note")) }
            }
            when (step) {
                0 -> item(key = "basics") {
                    Column(Modifier.fillMaxWidth()) {
                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名前") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm).testTag("template_editor_name"))
                        OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("説明（任意）") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm).testTag("template_editor_description"))
                        Text("チャットの＋に、この名前で並びます。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = ProductSpacing.sm))
                        // the folder (2026-09-22): 未分類 or one of the user's — a row that opens the choice; starters have none to pick
                        var folderOpen by remember { mutableStateOf(false) }
                        Text("フォルダ", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = ProductSpacing.md))
                        Box {
                            Row(
                                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { folderOpen = true }.padding(vertical = ProductSpacing.sm).semantics(mergeDescendants = true) { }.testTag("template_editor_folder"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(folders.firstOrNull { it.id == folderId }?.name ?: "未分類", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(expanded = folderOpen, onDismissRequest = { folderOpen = false }) {
                                DropdownMenuItem(text = { Text("未分類") }, onClick = { folderOpen = false; folderId = null }, modifier = Modifier.testTag("template_editor_folder_none"))
                                folders.sortedBy { it.order }.forEach { f -> DropdownMenuItem(text = { Text(f.name) }, onClick = { folderOpen = false; folderId = f.id }, modifier = Modifier.testTag("template_editor_folder_${f.id}")) }
                            }
                        }
                        if (folders.isEmpty()) Text("フォルダは「テンプレートを管理」の「フォルダ」で作れます。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                1 -> item(key = "action") {
                    Column(Modifier.fillMaxWidth()) {
                        Text("このテンプレートは何をしますか？", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = ProductSpacing.sm))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs)) {
                            listOf(TemplateAction.CREATE to "作成する", TemplateAction.SEARCH to "探す", TemplateAction.APPEND to "追記する").forEach { (a, label) ->
                                FilterChip(selected = !think && action == a, onClick = { think = false; action = a }, label = { Text(label) }, modifier = Modifier.testTag("template_editor_action_${a.name}"))
                            }
                            FilterChip(selected = think, onClick = { think = true; action = TemplateAction.CREATE; kind = DocumentKind.MEMO }, label = { Text("整理する") }, modifier = Modifier.testTag("template_editor_action_THINK"))
                        }
                        Text(
                            when {
                                think -> "質問に一つずつ答えてもらい、整理した結果をチャットに表示します。メモに残すかは、そのとき選べます。"
                                action == TemplateAction.CREATE -> "入力項目を埋めた内容で、新しい記録を作ります。作る前に確認します。"
                                action == TemplateAction.SEARCH -> "決めた条件で記録を探し、チャットに結果を並べます。"
                                else -> "選んだ記録の末尾に、入力項目を埋めた内容を書き足します。書き足す前に確認します。"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = ProductSpacing.sm),
                        )
                        when {
                            think -> Unit
                            action == TemplateAction.CREATE -> {
                                Text("何を作りますか？", style = MaterialTheme.typography.titleSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs)) {
                                    listOf(DocumentKind.MEMO to "メモ", DocumentKind.OUTLINE to "アウトライン", DocumentKind.JOURNAL to "日記").forEach { (k, label) ->
                                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(label) }, modifier = Modifier.testTag("template_editor_kind_${k.name}"))
                                    }
                                }
                                if (kind == DocumentKind.JOURNAL) Text("日記は実行した日の日記になります。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            action == TemplateAction.SEARCH -> {
                                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("探す言葉（任意）") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("template_editor_query"))
                                Text("いつの記録？", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = ProductSpacing.sm))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs)) {
                                    FilterChip(selected = dateToken == null, onClick = { dateToken = null }, label = { Text("指定なし") }, modifier = Modifier.testTag("template_editor_date_NONE"))
                                    listOf(TemplateDateToken.TODAY to "今日", TemplateDateToken.YESTERDAY to "昨日", TemplateDateToken.THIS_WEEK to "今週", TemplateDateToken.LAST_WEEK to "先週").forEach { (d, label) ->
                                        FilterChip(selected = dateToken == d, onClick = { dateToken = d }, label = { Text(label) }, modifier = Modifier.testTag("template_editor_date_${d.name}"))
                                    }
                                }
                                Text("どの種類？", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = ProductSpacing.sm))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs)) {
                                    listOf(DocumentKind.MEMO to "メモ", DocumentKind.OUTLINE to "アウトライン", DocumentKind.JOURNAL to "日記").forEach { (k, label) ->
                                        FilterChip(selected = k in kinds, onClick = { kinds = if (k in kinds) kinds - k else kinds + k }, label = { Text(label) }, modifier = Modifier.testTag("template_editor_kinds_${k.name}"))
                                    }
                                }
                                Text("言葉か日付のどちらかは必要です。日付は実行した日を基準にします。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = ProductSpacing.sm))
                            }
                            else -> {
                                Text("どの記録に書き足しますか？", style = MaterialTheme.typography.titleSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs)) {
                                    FilterChip(selected = askTarget, onClick = { askTarget = true }, label = { Text("実行するたびに選ぶ") }, modifier = Modifier.testTag("template_editor_ask_target"))
                                    FilterChip(selected = !askTarget, onClick = { askTarget = false }, label = { Text("決まった記録") }, modifier = Modifier.testTag("template_editor_fixed_target"))
                                }
                                if (askTarget) {
                                    OutlinedTextField(value = targetQuestion, onValueChange = { targetQuestion = it }, label = { Text("追記先を聞く質問（任意）") }, placeholder = { Text("どのメモに追記しますか？") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm).testTag("template_editor_target_question"))
                                }
                                if (!askTarget) {
                                    OutlinedTextField(value = targetName, onValueChange = { targetName = it }, label = { Text("記録の名前") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm).testTag("template_editor_target"))
                                    Text("名前で探して書き足します。同じ名前が複数あれば実行時に選びます。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                2 -> {
                    item(key = "fields_intro") {
                        Column(Modifier.fillMaxWidth()) {
                            Text("実行するときに入力してもらう項目", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = ProductSpacing.sm))
                            Text(when { think -> "答えは結果に差し込めます。質問は1つ以上必要です。"; action == TemplateAction.SEARCH -> "探す言葉に差し込めます。なくても構いません。"; else -> "内容に差し込めます。なくても構いません。" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    fields.forEachIndexed { i, f ->
                        item(key = "field-${f.key}") {
                            FieldEditor(
                                index = i, draft = f, count = fields.size,
                                onChange = { v -> fields = fields.toMutableList().also { it[i] = v } },
                                onUp = { if (i > 0) fields = fields.toMutableList().also { it[i] = fields[i - 1]; it[i - 1] = f } },
                                onDown = { if (i < fields.lastIndex) fields = fields.toMutableList().also { it[i] = fields[i + 1]; it[i + 1] = f } },
                                onRemove = { fields = fields.toMutableList().also { it.removeAt(i) } },
                            )
                        }
                    }
                    item(key = "add_field") {
                        OutlinedButton(
                            onClick = { if (fields.size < TemplateValidation.MAX_FIELDS) fields = fields + FieldDraft(key = TemplateBodyDisplay.nextKey(fields.map { it.key })) },
                            enabled = fields.size < TemplateValidation.MAX_FIELDS,
                            modifier = Modifier.fillMaxWidth().testTag("template_editor_add_field"),
                        ) { Text("＋ 項目を追加") }
                    }
                }
                3 -> item(key = "content") {
                    Column(Modifier.fillMaxWidth()) {
                        if (action == TemplateAction.SEARCH) {
                            Text("探す条件", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = ProductSpacing.sm))
                            Text(TemplateBodyDisplay.describeSearch(TemplateSearchSpec(query.trim(), dateToken, kinds)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = ProductSpacing.xs).testTag("template_editor_search_summary"))
                            Text("探す言葉に項目を差し込むには、「操作」で ⟦項目の名前⟧ と書きます。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = ProductSpacing.sm))
                        } else {
                            Text(when { think -> "整理した結果"; action == TemplateAction.APPEND -> "書き足す内容"; else -> "作る内容" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = ProductSpacing.sm))
                            var insertOpen by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(onClick = { insertOpen = true }, modifier = Modifier.testTag("template_editor_insert_field")) { Text("＋ 項目を挿入") }
                                DropdownMenu(expanded = insertOpen, onDismissRequest = { insertOpen = false }) {
                                    // the day is every template's to insert — filled by the clock when it runs (docs/THINK_TEMPLATES.md)
                                    DropdownMenuItem(
                                        text = { Text(TemplateValues.TODAY_LABEL) },
                                        onClick = {
                                            insertOpen = false
                                            val token = TemplateBodyDisplay.token(TemplateValues.TODAY_LABEL)
                                            val at = body.selection.end.coerceIn(0, body.text.length)
                                            val text = body.text.substring(0, at) + token + body.text.substring(at)
                                            body = TextFieldValue(text, TextRange(at + token.length))
                                        },
                                        modifier = Modifier.testTag("template_editor_insert_today"),
                                    )
                                    fields.forEachIndexed { i, f ->
                                        if (f.label.isNotBlank()) {
                                            DropdownMenuItem(
                                                text = { Text(f.label) },
                                                onClick = {
                                                    insertOpen = false
                                                    val token = TemplateBodyDisplay.token(f.label)
                                                    val at = body.selection.end.coerceIn(0, body.text.length)
                                                    val text = body.text.substring(0, at) + token + body.text.substring(at)
                                                    body = TextFieldValue(text, TextRange(at + token.length))
                                                },
                                                modifier = Modifier.testTag("template_editor_insert_$i"),
                                            )
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = body, onValueChange = { body = it },
                                label = { Text(when { think -> "結果"; action == TemplateAction.APPEND -> "書き足す内容"; else -> "内容" }) },
                                minLines = 8, modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm).testTag("template_editor_body"),
                            )
                            Text("⟦項目の名前⟧ のところに、実行時の入力が入ります。⟦今日の日付⟧ はその日の日付になります。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = ProductSpacing.xs))
                        }
                    }
                }
                else -> {
                    if (problems.isNotEmpty()) {
                        item(key = "problems") {
                            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.sm).testTag("template_editor_problems")) {
                                Column(Modifier.padding(14.dp)) { problems.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall) } }
                            }
                        }
                    }
                    item(key = "summary") {
                        val t = build()
                        Column(Modifier.fillMaxWidth()) {
                            Text(MemoTemplatePolicy.cleanName(name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = ProductSpacing.sm))
                            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                when {
                                    think -> "整理する（結果をチャットに表示。メモとして保存は任意）"
                                    action == TemplateAction.CREATE -> "${kindLabel(kind)}を作成する"
                                    action == TemplateAction.SEARCH -> "探す"
                                    else -> if (askTarget) "実行時に選んだ記録に追記する" else "「${targetName.trim()}」に追記する"
                                } + if (t.fields.isNotEmpty()) "　·　入力項目 ${t.fields.size}" else "　·　入力項目なし",
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = ProductSpacing.xs),
                            )
                            // the conversation, in order (§16): the target question when one is asked, then each field's question
                            if (t.fields.isNotEmpty() || (action == TemplateAction.APPEND && askTarget)) {
                                Text("チャットで聞く順番", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = ProductSpacing.md))
                                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.xs)) {
                                    Column(Modifier.fillMaxWidth().padding(14.dp).testTag("template_editor_questions")) {
                                        var n = 0
                                        if (action == TemplateAction.APPEND && askTarget) { n++; Text("質問$n　${TemplateScript.targetQuestionOf(t)}", style = MaterialTheme.typography.bodyMedium) }
                                        t.fields.forEach { f -> n++; Text("質問$n　${TemplateScript.questionOf(f)}" + if (!f.required) "（スキップ可）" else "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = ProductSpacing.xs)) }
                                    }
                                }
                            }
                            Text("できあがり", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = ProductSpacing.md))
                            Text("入力項目に初期値か（名前）を入れた場合です。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.xs)) {
                                Text(previewOf(t, today), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp).testTag("template_editor_preview"))
                            }
                        }
                    }
                    if (onDelete != null) {
                        item(key = "delete") {
                            TextButton(onClick = { deleteAsk = true }, modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.lg).testTag("template_editor_delete")) { Text("このテンプレートを削除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(ProductSpacing.xl)) }
        }
    }
    if (deleteAsk && onDelete != null) {
        AlertDialog(
            onDismissRequest = { deleteAsk = false },
            modifier = Modifier.testTag("template_editor_delete_dialog"),
            title = { Text("「${MemoTemplatePolicy.cleanName(name)}」を削除しますか？") },
            text = { Text("テンプレートだけを削除します。メモや日記は削除されません。") },
            confirmButton = { TextButton(onClick = { deleteAsk = false; onDelete() }, modifier = Modifier.testTag("template_editor_delete_confirm")) { Text("削除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteAsk = false }, modifier = Modifier.testTag("template_editor_delete_cancel")) { Text("キャンセル") } },
        )
    }
}

/** One field: its name, its kind, whether it is required, its initial value, its choices; up / down / remove. No key anywhere. */
@Composable
private fun FieldEditor(index: Int, draft: FieldDraft, count: Int, onChange: (FieldDraft) -> Unit, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit) {
    val f = draft
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().testTag("template_editor_field_$index")) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("項目 ${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = onUp, enabled = index > 0, modifier = Modifier.testTag("template_editor_field_up_$index")) { Text("上へ") }
                TextButton(onClick = onDown, enabled = index < count - 1, modifier = Modifier.testTag("template_editor_field_down_$index")) { Text("下へ") }
            }
            OutlinedTextField(value = f.label, onValueChange = { onChange(f.copy(label = it)) }, label = { Text("項目の名前") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("template_editor_field_label_$index"))
            OutlinedTextField(
                value = f.question, onValueChange = { onChange(f.copy(question = it)) }, label = { Text("質問") },
                placeholder = { Text(if (f.label.isBlank()) "チャットで聞く言葉" else "${f.label}を入力してください") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.xs).testTag("template_editor_field_question_$index"),
            )
            Text("どんな入力？", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = ProductSpacing.sm))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs)) {
                listOf(TemplateFieldType.TEXT to "1行", TemplateFieldType.MULTILINE to "複数行", TemplateFieldType.DATE to "日付", TemplateFieldType.CHOICE to "選択", TemplateFieldType.BOOLEAN to "はい・いいえ").forEach { (t, label) ->
                    FilterChip(selected = f.type == t, onClick = { onChange(f.copy(type = t)) }, label = { Text(label) }, modifier = Modifier.testTag("template_editor_field_type_${index}_${t.name}"))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = ProductSpacing.xs)) {
                Switch(checked = f.required, onCheckedChange = { onChange(f.copy(required = it)) }, modifier = Modifier.testTag("template_editor_field_required_$index"))
                Text("必ず入力してもらう", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = ProductSpacing.sm))
            }
            when (f.type) {
                TemplateFieldType.DATE -> {
                    Text("初期値", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = ProductSpacing.xs))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(ProductSpacing.xs)) {
                        FilterChip(selected = f.default.isBlank(), onClick = { onChange(f.copy(default = "")) }, label = { Text("なし") }, modifier = Modifier.testTag("template_editor_field_default_${index}_none"))
                        FilterChip(selected = f.default.equals("TODAY", true), onClick = { onChange(f.copy(default = "TODAY")) }, label = { Text("実行した日") }, modifier = Modifier.testTag("template_editor_field_default_${index}_today"))
                        FilterChip(selected = f.default.equals("YESTERDAY", true), onClick = { onChange(f.copy(default = "YESTERDAY")) }, label = { Text("その前日") }, modifier = Modifier.testTag("template_editor_field_default_${index}_yesterday"))
                    }
                }
                TemplateFieldType.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = ProductSpacing.xs)) {
                    Switch(checked = f.default.lowercase() in setOf("true", "yes", "1", "はい", "on"), onCheckedChange = { onChange(f.copy(default = if (it) "true" else "false")) }, modifier = Modifier.testTag("template_editor_field_default_$index"))
                    Text("初期値は「はい」", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = ProductSpacing.sm))
                }
                TemplateFieldType.CHOICE -> {
                    OutlinedTextField(value = f.choices, onValueChange = { onChange(f.copy(choices = it)) }, label = { Text("選択肢（1行に1つ）") }, minLines = 2, modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.xs).testTag("template_editor_field_choices_$index"))
                    OutlinedTextField(value = f.default, onValueChange = { onChange(f.copy(default = it)) }, label = { Text("初めから選んでおく選択肢（任意）") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.xs).testTag("template_editor_field_default_$index"))
                }
                else -> OutlinedTextField(value = f.default, onValueChange = { onChange(f.copy(default = it)) }, label = { Text("初期値（任意）") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = ProductSpacing.xs).testTag("template_editor_field_default_$index"))
            }
            TextButton(onClick = onRemove, modifier = Modifier.testTag("template_editor_field_remove_$index")) { Text("この項目を削除", color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun kindLabel(kind: DocumentKind): String = when (kind) {
    DocumentKind.MEMO -> "メモ"
    DocumentKind.OUTLINE -> "アウトライン"
    DocumentKind.JOURNAL -> "日記"
}

/** What the template will come to, with sample values — the same renderer a run uses; never a write. */
private fun previewOf(t: MemoTemplate, today: LocalDate): String {
    val samples = TemplateBodyDisplay.sampleValues(t.fields, today)
    fun render(text: String) = when (val r = TemplateRenderer.render(text, samples)) {
        is TemplateRendering.Rendered -> r.text
        is TemplateRendering.UnknownPlaceholder -> "「${TemplateBodyDisplay.OPEN}${r.key}${TemplateBodyDisplay.CLOSE}」に対応する項目がありません"
    }
    if (t.flow == TemplateFlow.THINK) return "整理した結果をチャットに表示します。メモに残すかは、そのとき選べます。\n\n" + render(t.body).ifBlank { "（内容がありません）" }
    return when (t.action) {
        TemplateAction.CREATE -> "${kindLabel(t.documentKind)}を作成します。\n\n" + render(t.body).ifBlank { "（内容がありません）" }
        TemplateAction.SEARCH -> TemplateBodyDisplay.describeSearch(t.searchSpec?.copy(query = render(t.searchSpec.query)))
        TemplateAction.APPEND -> (when (val ts = t.targetSpec) {
            is TemplateTargetSpec.Named -> "「${ts.name}」に追記します。"
            TemplateTargetSpec.AskAtRun -> "実行時に選んだ記録に追記します。"
            null -> "追記先がありません。"
        }) + "\n\n" + render(t.body).ifBlank { "（内容がありません）" }
    }
}

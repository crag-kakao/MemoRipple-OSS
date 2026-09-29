package io.github.cragcoffee.memoripple.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.cragcoffee.memoripple.domain.WorkCommentScope
import io.github.cragcoffee.memoripple.domain.memos.HomeShortcutCodec
import io.github.cragcoffee.memoripple.domain.memos.HomeShortcutEntry
import io.github.cragcoffee.memoripple.domain.memos.HomeShortcuts
import io.github.cragcoffee.memoripple.domain.memos.WallDisplayMode
import io.github.cragcoffee.memoripple.domain.notes.NoteCoverPalette
import io.github.cragcoffee.memoripple.domain.speech.SpeechDictionary
import io.github.cragcoffee.memoripple.domain.speech.SpeechDictionaryEntry
import io.github.cragcoffee.memoripple.domain.settings.AppSettings
import io.github.cragcoffee.memoripple.domain.settings.CommentFont
import io.github.cragcoffee.memoripple.domain.settings.CommentSize
import io.github.cragcoffee.memoripple.domain.settings.PlaybackSpeed
import io.github.cragcoffee.memoripple.domain.settings.StageBackground
import io.github.cragcoffee.memoripple.domain.settings.ThemePaletteStyle
import io.github.cragcoffee.memoripple.domain.settings.ThemeSeed
import io.github.cragcoffee.memoripple.domain.settings.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map(::toAppSettings)
        .distinctUntilChanged()

    /**
     * How the memo wall is drawn. A way of looking, not a setting: it stays out of [AppSettings],
     * out of the backup, and out of "設定を初期値に戻す" — restoring a backup onto another device
     * should not change how that device's owner had been reading their wall.
     */
    val wallDisplayMode: Flow<WallDisplayMode> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { WallDisplayMode.fromStorageId(it[Keys.WALL_DISPLAY]) }
        .distinctUntilChanged()

    suspend fun setWallDisplayMode(mode: WallDisplayMode) = update(Keys.WALL_DISPLAY, mode.storageId)

    /**
     * Whether the メモ and アウトライン walls lie flat — one full-width card per row — instead of
     * standing as a two-column grid. A way of looking, so device-local like [wallDisplayMode]:
     * never backed up, never reset with the product settings.
     */
    val wallSingleColumn: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.WALL_SINGLE_COLUMN] == "true" }
        .distinctUntilChanged()

    suspend fun setWallSingleColumn(value: Boolean) =
        update(Keys.WALL_SINGLE_COLUMN, value.toString())

    /**
     * Whether the editor's shortcut bar stands in two rows instead of one. One row scrolls and
     * stays low; two rows show every tool at once for whoever wants that. A way of writing, so
     * device-local: never backed up, never reset with the product settings.
     */
    val editorToolbarTwoRows: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.EDITOR_TOOLBAR_TWO_ROWS] == "true" }
        .distinctUntilChanged()

    suspend fun setEditorToolbarTwoRows(value: Boolean) =
        update(Keys.EDITOR_TOOLBAR_TWO_ROWS, value.toString())

    /**
     * The shortcut bar's arrangement, one per kind of writing: `|`-joined EditorToolbarItem
     * ids, empty for the original order. メモ and ノート keep separate arrangements so
     * rearranging one never disturbs the other. A way of writing, so device-local: never
     * backed up, never reset with the product settings. Decoding lives in EditorToolbarOrder.
     */
    val editorToolbarOrder: Flow<String> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.EDITOR_TOOLBAR_ORDER].orEmpty() }
        .distinctUntilChanged()

    suspend fun setEditorToolbarOrder(value: String) =
        update(Keys.EDITOR_TOOLBAR_ORDER, value)

    /**
     * アウトライナーの折りたたみ: which lines of a memo the outliner shows folded, as the
     * content keys of `OutlineFoldKeys`, one preference for every memo
     * (`memoId=key,key;memoId=key`). A way of looking at a memo on this device — never in
     * the body, never in a backup, never touched by 設定を初期値に戻す.
     */
    fun outlinerFolds(memoId: Long): Flow<Set<String>> = dataStore.data
        .map { decodeOutlinerFolds(it[Keys.OUTLINER_FOLDS].orEmpty())[memoId].orEmpty() }
        .distinctUntilChanged()

    suspend fun setOutlinerFolds(memoId: Long, keys: Set<String>) {
        dataStore.edit { preferences ->
            val all = decodeOutlinerFolds(preferences[Keys.OUTLINER_FOLDS].orEmpty()).toMutableMap()
            if (keys.isEmpty()) all.remove(memoId) else all[memoId] = keys
            if (all.isEmpty()) {
                preferences.remove(Keys.OUTLINER_FOLDS)
            } else {
                preferences[Keys.OUTLINER_FOLDS] = all.entries.joinToString(";") { (id, folded) ->
                    "$id=${folded.joinToString(",")}"
                }
            }
        }
    }

    /**
     * アウトライナーの位置: the content key of the first line the outliner was showing when
     * it was left, one per memo (`memoId=key;memoId=key`) — so it opens where it was. View
     * state on this device, like the folds: never in the body, never in a backup.
     */
    fun outlinerScrollAnchor(memoId: Long): Flow<String?> = dataStore.data
        .map { decodeOutlinerAnchors(it[Keys.OUTLINER_SCROLL].orEmpty())[memoId] }
        .distinctUntilChanged()

    suspend fun setOutlinerScrollAnchor(memoId: Long, key: String?) {
        dataStore.edit { preferences ->
            val all = decodeOutlinerAnchors(preferences[Keys.OUTLINER_SCROLL].orEmpty()).toMutableMap()
            if (key.isNullOrBlank()) all.remove(memoId) else all[memoId] = key
            if (all.isEmpty()) {
                preferences.remove(Keys.OUTLINER_SCROLL)
            } else {
                preferences[Keys.OUTLINER_SCROLL] = all.entries.joinToString(";") { (id, anchor) -> "$id=$anchor" }
            }
        }
    }

    private fun decodeOutlinerAnchors(stored: String): Map<Long, String> =
        stored.split(';').mapNotNull { entry ->
            val id = entry.substringBefore('=', "").toLongOrNull() ?: return@mapNotNull null
            val key = entry.substringAfter('=', "")
            if (key.isBlank()) null else id to key
        }.toMap()

    /**
     * Drops the folds and scroll anchors of memos that no longer exist — view state that had
     * nothing left to describe. Returns how many memo entries went.
     */
    suspend fun pruneOutlinerViewState(liveMemoIds: Set<Long>): Int {
        var pruned = 0
        dataStore.edit { preferences ->
            val folds = decodeOutlinerFolds(preferences[Keys.OUTLINER_FOLDS].orEmpty())
            val keptFolds = folds.filterKeys { it in liveMemoIds }
            pruned += folds.size - keptFolds.size
            if (keptFolds.isEmpty()) {
                preferences.remove(Keys.OUTLINER_FOLDS)
            } else if (keptFolds.size != folds.size) {
                preferences[Keys.OUTLINER_FOLDS] = keptFolds.entries.joinToString(";") { (id, folded) ->
                    "$id=${folded.joinToString(",")}"
                }
            }
            val anchors = decodeOutlinerAnchors(preferences[Keys.OUTLINER_SCROLL].orEmpty())
            val keptAnchors = anchors.filterKeys { it in liveMemoIds }
            pruned += anchors.size - keptAnchors.size
            if (keptAnchors.isEmpty()) {
                preferences.remove(Keys.OUTLINER_SCROLL)
            } else if (keptAnchors.size != anchors.size) {
                preferences[Keys.OUTLINER_SCROLL] = keptAnchors.entries.joinToString(";") { (id, anchor) -> "$id=$anchor" }
            }
        }
        return pruned
    }

    /** Forgets every outliner fold on this device — a view-state reset, nothing of the memos. */
    suspend fun clearOutlinerFolds() {
        dataStore.edit {
            it.remove(Keys.OUTLINER_FOLDS)
            it.remove(Keys.OUTLINER_SCROLL)
        }
    }

    private fun decodeOutlinerFolds(stored: String): Map<Long, Set<String>> =
        stored.split(';').mapNotNull { entry ->
            val id = entry.substringBefore('=', "").toLongOrNull() ?: return@mapNotNull null
            val keys = entry.substringAfter('=', "").split(',').filter(String::isNotBlank).toSet()
            if (keys.isEmpty()) null else id to keys
        }.toMap()

    /**
     * The chips of a bar's 見出し・項目などの記号 or 流れ方 unit in the writer's order, hidden ones
     * marked — one arrangement per surface and group (`editor_toolbar_chips_<group>_<surface>`).
     * A way of writing, like the bar's own order: device-local, never backed up.
     */
    fun toolbarChips(surface: String, group: String): Flow<String> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[chipsKey(surface, group)].orEmpty() }
        .distinctUntilChanged()

    suspend fun setToolbarChips(surface: String, group: String, value: String) =
        update(chipsKey(surface, group), value)

    private fun chipsKey(surface: String, group: String) =
        stringPreferencesKey("editor_toolbar_chips_${group.lowercase()}_${surface.lowercase()}")

    /** The outliner's shortcut bar in the writer's order (see [editorToolbarOrder]); its own arrangement. */
    val editorToolbarOutlinerOrder: Flow<String> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.EDITOR_TOOLBAR_OUTLINER_ORDER].orEmpty() }
        .distinctUntilChanged()

    suspend fun setEditorToolbarOutlinerOrder(value: String) =
        update(Keys.EDITOR_TOOLBAR_OUTLINER_ORDER, value)

    val editorToolbarNoteOrder: Flow<String> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.EDITOR_TOOLBAR_NOTE_ORDER].orEmpty() }
        .distinctUntilChanged()

    suspend fun setEditorToolbarNoteOrder(value: String) =
        update(Keys.EDITOR_TOOLBAR_NOTE_ORDER, value)

    /**
     * Whether the editor's ⋮ menu offers 全てのテキストをコピー / PDFで保存. On until switched
     * off in 設定 — a menu the owner never uses can leave. Device-local like the rest of the
     * editor's ways of working.
     */
    /**
     * First-use guides (1.0 usability closure): each is shown once and never again. The
     * flags say only that the guide was seen — never that a permission is granted; the OS
     * stays the source of truth for permissions. Device-local: not in [AppSettings], not
     * in the backup wire format, and deliberately outside [resetToDefaults] so 設定を
     * 初期値に戻す does not replay the welcome. Collectors use a null initial value while
     * the DataStore is still loading, so no launch flashes the demo before the flag arrives.
     */
    val hasSeenWelcomeDemo: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.HAS_SEEN_WELCOME_DEMO] == "true" }
        .distinctUntilChanged()

    suspend fun setHasSeenWelcomeDemo(value: Boolean) =
        update(Keys.HAS_SEEN_WELCOME_DEMO, value.toString())

    val hasSeenOverlaySetup: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.HAS_SEEN_OVERLAY_SETUP] == "true" }
        .distinctUntilChanged()

    suspend fun setHasSeenOverlaySetup(value: Boolean) =
        update(Keys.HAS_SEEN_OVERLAY_SETUP, value.toString())

    val hasSeenOutlineGuide: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.HAS_SEEN_OUTLINE_GUIDE] == "true" }
        .distinctUntilChanged()

    suspend fun setHasSeenOutlineGuide(value: Boolean) =
        update(Keys.HAS_SEEN_OUTLINE_GUIDE, value.toString())

    val editorMenuCopyAll: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.EDITOR_MENU_COPY_ALL] != "false" }
        .distinctUntilChanged()

    suspend fun setEditorMenuCopyAll(value: Boolean) =
        update(Keys.EDITOR_MENU_COPY_ALL, value.toString())

    val editorMenuPdfExport: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.EDITOR_MENU_PDF_EXPORT] != "false" }
        .distinctUntilChanged()

    suspend fun setEditorMenuPdfExport(value: Boolean) =
        update(Keys.EDITOR_MENU_PDF_EXPORT, value.toString())

    /** Whether the ⋮ menu's export writes Word (.docx) instead of Markdown. Off by default. */
    val editorExportDocx: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.EDITOR_EXPORT_DOCX] == "true" }
        .distinctUntilChanged()

    suspend fun setEditorExportDocx(value: Boolean) =
        update(Keys.EDITOR_EXPORT_DOCX, value.toString())

    /**
     * Which outline symbol set the shortcut bar inserts (storage id of OutlineSymbolSet;
     * empty = 標準). Recognition always accepts every set — this only chooses what new marks
     * look like — so it is a way of writing: device-local, never backed up, never reset.
     */
    /**
     * メモの並び順 (name of MemoSortMode; empty = 更新が新しい順). A way of looking at the wall on
     * this device, kept so 並べた順 survives a restart; not in a backup; 設定を初期値に戻す clears it.
     */
    val memoSortMode: Flow<String> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.MEMO_SORT_MODE].orEmpty() }
        .distinctUntilChanged()

    suspend fun setMemoSortMode(value: String) = update(Keys.MEMO_SORT_MODE, value)

    val outlineSymbolSet: Flow<String> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.OUTLINE_SYMBOL_SET].orEmpty() }
        .distinctUntilChanged()

    suspend fun setOutlineSymbolSet(value: String) =
        update(Keys.OUTLINE_SYMBOL_SET, value)

    /**
     * How the shortcut bar labels its outline chips (storage id of OutlineChipLabel; empty =
     * 記号と言葉). A way of writing like the symbols themselves: device-local, never backed
     * up, never reset.
     */
    val outlineChipLabel: Flow<String> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.OUTLINE_CHIP_LABEL].orEmpty() }
        .distinctUntilChanged()

    suspend fun setOutlineChipLabel(value: String) =
        update(Keys.OUTLINE_CHIP_LABEL, value)

    /**
     * Whether the editor keeps its tag row out of sight. The wall's cards still wear their tags —
     * this only clears the writing surface for whoever tags rarely. Device-local like the rest.
     */
    val editorHideTags: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.EDITOR_HIDE_TAGS] == "true" }
        .distinctUntilChanged()

    suspend fun setEditorHideTags(value: Boolean) =
        update(Keys.EDITOR_HIDE_TAGS, value.toString())

    /**
     * テーマカラー and パレットスタイル: the seed the colour scheme grows from and the way it is
     * spread. Ways of looking, so device-local — the frozen backup keeps carrying only 外観
     * (theme mode), and a restore leaves this device's colours alone.
     */
    /** Phase 5 (docs/AI_MODEL_MANAGEMENT.md): the selected Local AI model, by catalog id only — never a path, url or hash; null = not chosen. */
    val selectedAiModelId: Flow<String?> = dataStore.data.map { it[Keys.AI_SELECTED_MODEL_ID]?.takeIf { id -> id.isNotBlank() } }

    suspend fun setSelectedAiModelId(modelId: String?) {
        dataStore.edit { prefs -> if (modelId == null) prefs.remove(Keys.AI_SELECTED_MODEL_ID) else prefs[Keys.AI_SELECTED_MODEL_ID] = modelId }
    }

    /** Phase 8 (docs/AI_CONVERSATION_HISTORY.md): the conversation the チャット AI mode shows — an id only, never content; null = none. */
    val lastChatConversationId: Flow<Long?> = dataStore.data.map { it[Keys.CHAT_LAST_CONVERSATION_ID]?.toLongOrNull() }

    suspend fun setLastChatConversationId(id: Long?) {
        dataStore.edit { prefs -> if (id == null) prefs.remove(Keys.CHAT_LAST_CONVERSATION_ID) else prefs[Keys.CHAT_LAST_CONVERSATION_ID] = id.toString() }
    }

    /** The history drawer's pins (2026-09-21): conversation ids only, never content; an id that no longer exists is simply skipped. */
    val pinnedChatConversationIds: Flow<Set<Long>> = dataStore.data.map { prefs -> prefs[Keys.CHAT_PINNED_CONVERSATION_IDS].orEmpty().mapNotNull { it.toLongOrNull() }.toSet() }

    suspend fun setChatConversationPinned(id: Long, pinned: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.CHAT_PINNED_CONVERSATION_IDS].orEmpty()
            prefs[Keys.CHAT_PINNED_CONVERSATION_IDS] = if (pinned) current + id.toString() else current - id.toString()
        }
    }

    /** The ＋ picker's pinned templates (Review Batch 2, 2026-09-22): template ids in pin order, never content; an id that names no template is skipped by the reader. */
    val pinnedTemplateIds: Flow<List<String>> = dataStore.data.map { prefs -> prefs[Keys.CHAT_PINNED_TEMPLATE_IDS].orEmpty().split("\n").filter { it.isNotBlank() } }

    suspend fun setTemplatePinned(id: String, pinned: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.CHAT_PINNED_TEMPLATE_IDS].orEmpty().split("\n").filter { it.isNotBlank() }
            val next = if (pinned) (current - id) + id else current - id
            prefs[Keys.CHAT_PINNED_TEMPLATE_IDS] = next.joinToString("\n")
        }
    }

    suspend fun clearPinnedTemplates() { dataStore.edit { prefs -> prefs.remove(Keys.CHAT_PINNED_TEMPLATE_IDS) } }

    /**
     * The chat's 「Local AIモデルが必要です」 hint, closed by its × (UI/UX review 2026-09-23): written
     * once and never written back, so a hint the user closed stays closed. A device preference —
     * outside the portable backup, like the pins.
     */
    val chatAiHintDismissed: Flow<Boolean> = dataStore.data.map { prefs -> prefs[Keys.CHAT_AI_HINT_DISMISSED] == "true" }

    suspend fun dismissChatAiHint() {
        dataStore.edit { prefs -> prefs[Keys.CHAT_AI_HINT_DISMISSED] = "true" }
    }

    /**
     * The chat home launcher (2026-09-23): the templates the user put on the home — a small JSON
     * list of ids and the names they gave them, in their order. A preference of this device, like
     * the pins: never in the portable backup, never a Room column, and never a copy of a template.
     */
    val homeShortcuts: Flow<List<HomeShortcutEntry>> = dataStore.data.map { prefs -> HomeShortcutCodec.decode(prefs[Keys.CHAT_HOME_SHORTCUTS]) }

    private suspend fun editHomeShortcuts(change: (List<HomeShortcutEntry>) -> List<HomeShortcutEntry>) {
        dataStore.edit { prefs -> prefs[Keys.CHAT_HOME_SHORTCUTS] = HomeShortcutCodec.encode(change(HomeShortcutCodec.decode(prefs[Keys.CHAT_HOME_SHORTCUTS]))) }
    }

    suspend fun addHomeShortcut(templateId: String) = editHomeShortcuts { HomeShortcuts.added(it, templateId) }

    suspend fun removeHomeShortcut(templateId: String) = editHomeShortcuts { HomeShortcuts.removed(it, templateId) }

    suspend fun renameHomeShortcut(templateId: String, label: String) = editHomeShortcuts { HomeShortcuts.renamed(it, templateId, label) }

    suspend fun clearHomeShortcuts() { dataStore.edit { prefs -> prefs.remove(Keys.CHAT_HOME_SHORTCUTS) } }

    /** The chat's chosen folder for what it creates (2026-09-22): one preference, the wall's folder id or none; a vanished folder is read as none by the chat. */
    val chatCreateFolderId: Flow<Long?> = dataStore.data.map { prefs -> prefs[Keys.CHAT_CREATE_FOLDER_ID] }

    suspend fun setChatCreateFolderId(id: Long?) {
        dataStore.edit { prefs -> if (id == null) prefs.remove(Keys.CHAT_CREATE_FOLDER_ID) else prefs[Keys.CHAT_CREATE_FOLDER_ID] = id }
    }

    /**
     * 「メモを選択」 (docs/CHAT_MEMO_CONTEXT.md, 2026-09-26): each conversation's selected memo — the raw
     * `"<conversationId>:<memoId>"` entries, ids only, never content; decoded by the chat's codec.
     */
    val chatSelectedMemoEntries: Flow<Set<String>> = dataStore.data.map { prefs -> prefs[Keys.CHAT_SELECTED_MEMOS].orEmpty() }

    suspend fun editChatSelectedMemoEntries(transform: (Set<String>) -> Set<String>) {
        dataStore.edit { prefs ->
            val next = transform(prefs[Keys.CHAT_SELECTED_MEMOS].orEmpty())
            if (next.isEmpty()) prefs.remove(Keys.CHAT_SELECTED_MEMOS) else prefs[Keys.CHAT_SELECTED_MEMOS] = next
        }
    }

    val themeSeed: Flow<ThemeSeed> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { ThemeSeed.fromStorageId(it[Keys.THEME_SEED]) }
        .distinctUntilChanged()

    suspend fun setThemeSeed(seed: ThemeSeed) = update(Keys.THEME_SEED, seed.storageId)

    val themePaletteStyle: Flow<ThemePaletteStyle> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { ThemePaletteStyle.fromStorageId(it[Keys.THEME_PALETTE_STYLE]) }
        .distinctUntilChanged()

    suspend fun setThemePaletteStyle(style: ThemePaletteStyle) =
        update(Keys.THEME_PALETTE_STYLE, style.storageId)

    /**
     * The typeface flowing comments are drawn with, everywhere they flow — the wall, the memo
     * stage, the reader, the overlay. A way of looking, so device-local: never backed up, never
     * reset with the product settings.
     */
    val commentFont: Flow<CommentFont> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { CommentFont.fromStorageId(it[Keys.COMMENT_FONT]) }
        .distinctUntilChanged()

    suspend fun setCommentFont(font: CommentFont) =
        update(Keys.COMMENT_FONT, font.storageId)

    /** The raw selection, which may also name a user font (`user:<id>`). Same key, same
     * device-local stance; old enum values keep meaning what they always did. */
    val commentFontId: Flow<String> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.COMMENT_FONT] ?: CommentFont.DEFAULT.storageId }
        .distinctUntilChanged()

    suspend fun setCommentFontId(storageId: String) = update(Keys.COMMENT_FONT, storageId)

    /** The user's own comment fonts — the catalog beside the files in [CommentFontStore]. */
    val userCommentFonts: Flow<List<io.github.cragcoffee.memoripple.domain.settings.UserCommentFont>> =
        dataStore.data
            .catch { error ->
                if (error is IOException) {
                    emit(androidx.datastore.preferences.core.emptyPreferences())
                } else {
                    throw error
                }
            }
            .map { preferences ->
                preferences[Keys.USER_COMMENT_FONTS]?.let { raw ->
                    runCatching {
                        kotlinx.serialization.json.Json.decodeFromString<
                            List<io.github.cragcoffee.memoripple.domain.settings.UserCommentFont>,
                            >(raw)
                    }.getOrNull()
                } ?: emptyList()
            }
            .distinctUntilChanged()

    suspend fun setUserCommentFonts(
        fonts: List<io.github.cragcoffee.memoripple.domain.settings.UserCommentFont>,
    ) = update(
        Keys.USER_COMMENT_FONTS,
        kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(
                io.github.cragcoffee.memoripple.domain.settings.UserCommentFont.serializer(),
            ),
            fonts.take(io.github.cragcoffee.memoripple.domain.settings.UserCommentFont.MAXIMUM),
        ),
    )

    /**
     * The font catalog for the store's orphan cleanup: null when it was never written or
     * cannot be read, so an empty or damaged store is never mistaken for "no fonts, delete all".
     */
    suspend fun userCommentFontsForCleanup(): List<io.github.cragcoffee.memoripple.domain.settings.UserCommentFont>? {
        val raw = runCatching { dataStore.data.first()[Keys.USER_COMMENT_FONTS] }.getOrNull() ?: return null
        return runCatching {
            kotlinx.serialization.json.Json.decodeFromString<
                List<io.github.cragcoffee.memoripple.domain.settings.UserCommentFont>,
                >(raw)
        }.getOrNull()
    }

    /** Built-in faces the user has deleted from the list; the default never joins. */
    val removedBuiltInCommentFonts: Flow<Set<String>> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences ->
            preferences[Keys.HIDDEN_COMMENT_FONTS]?.let { raw ->
                runCatching {
                    kotlinx.serialization.json.Json.decodeFromString<List<String>>(raw)
                }.getOrNull()?.toSet()
            } ?: emptySet()
        }
        .distinctUntilChanged()

    suspend fun setRemovedBuiltInCommentFonts(hidden: Set<String>) = update(
        Keys.HIDDEN_COMMENT_FONTS,
        kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(
                kotlinx.serialization.serializer<String>(),
            ),
            hidden.toList().sorted(),
        ),
    )

    /**
     * Whether each flowing comment wears its translucent backdrop plate. Off by default — the
     * letters fly bare with a cut-out edge, the way the video site draws them; on, the plate
     * stands between the words and the page. A way of looking, so device-local like the font.
     */
    val commentBackdrop: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.COMMENT_BACKDROP] == "true" }
        .distinctUntilChanged()

    suspend fun setCommentBackdrop(enabled: Boolean) =
        update(Keys.COMMENT_BACKDROP, enabled.toString())

    /**
     * How see-through flowing comments are, the video site's コメント透過: 0 is solid, 0.80 is
     * its 強. Applied to the whole comment — letters, edge, and plate alike — so what is behind
     * shows through evenly. Device-local like every way of looking.
     */
    val commentTransparency: Flow<Float> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.COMMENT_TRANSPARENCY]?.toFloatOrNull()?.coerceIn(0f, 0.95f) ?: 0f }
        .distinctUntilChanged()

    suspend fun setCommentTransparency(value: Float) =
        update(Keys.COMMENT_TRANSPARENCY, value.coerceIn(0f, 0.95f).toString())

    /**
     * Whether the wall speaks memo contents instead of titles. Off — the default — both the
     * launch greeting and the wall's ▶ flow only each memo's title, a roll call; on, they flow
     * the bodies, a reading. One switch for both surfaces: the greeting and the button are the
     * same voice, asked for at different moments. Device-local like every way of looking.
     */
    val wallPlaysContent: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.WALL_PLAYS_CONTENT] == "true" }
        .distinctUntilChanged()

    suspend fun setWallPlaysContent(enabled: Boolean) =
        update(Keys.WALL_PLAYS_CONTENT, enabled.toString())

    /**
     * Whether the wall's ▶ sends its comments to the overlay — over other apps — instead of the
     * wall's own sky. Off by default; the launch greeting always stays inline so opening the app
     * never walks through permission dialogs uninvited. Device-local like every way of looking.
     */
    val wallOverlayPlayback: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.WALL_OVERLAY_PLAYBACK] == "true" }
        .distinctUntilChanged()

    suspend fun setWallOverlayPlayback(enabled: Boolean) =
        update(Keys.WALL_OVERLAY_PLAYBACK, enabled.toString())

    /**
     * How playback was last asked for: where the comments are drawn, what flows, and — for the
     * overlay — which region and how densely. The play button plays with these instead of asking
     * again, which is what makes it one press. Device-local for the same reason the wall display
     * is: a restore should not change how this device's owner had been playing.
     */
    val playbackStyle: Flow<PlaybackStyle> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences ->
            PlaybackStyle(
                displayModeId = preferences[Keys.PLAYBACK_DISPLAY],
                contentModeId = preferences[Keys.PLAYBACK_CONTENT],
                overlayRegionId = preferences[Keys.OVERLAY_REGION],
                overlayDensityId = preferences[Keys.OVERLAY_DENSITY],
            )
        }
        .distinctUntilChanged()

    /**
     * Whether the diary's calendar shows one week or the whole month. Compact is the default:
     * the calendar's daily job is saying where today stands, and a week answers that in one
     * line. The month unfolds from the title when the reader goes looking. Device-local, like
     * every other way-of-looking.
     */
    val diaryCalendarCompact: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.DIARY_CALENDAR_COMPACT] != "false" }
        .distinctUntilChanged()

    suspend fun setDiaryCalendarCompact(compact: Boolean) =
        update(Keys.DIARY_CALENDAR_COMPACT, compact.toString())

    /**
     * Whether the composer keeps its expression after sending, and the expression it keeps —
     * the video site's 設定内容を保持する. Eight storage ids joined by '|': colour, size,
     * emphasis, speed, placement, mode, direction, effect. Device-local like the rest.
     */
    val keepCommentExpression: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.KEEP_COMMENT_EXPRESSION] == "true" }
        .distinctUntilChanged()

    val keptCommentExpression: Flow<String?> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.KEPT_COMMENT_EXPRESSION] }
        .distinctUntilChanged()

    suspend fun setKeepCommentExpression(keep: Boolean) =
        update(Keys.KEEP_COMMENT_EXPRESSION, keep.toString())

    suspend fun setKeptCommentExpression(joinedIds: String) =
        update(Keys.KEPT_COMMENT_EXPRESSION, joinedIds)

    /**
     * The favourite comments behind the composer's chips. The user owns this list — the built-in
     * five are only its starting point — so it lives here, capped like the site it copies.
     */
    val favoriteComments: Flow<List<FavoriteComment>> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences ->
            preferences[Keys.FAVORITE_COMMENTS]?.let { raw ->
                runCatching {
                    kotlinx.serialization.json.Json.decodeFromString<List<FavoriteComment>>(raw)
                }.getOrNull()
            } ?: FavoriteComment.Defaults
        }
        .distinctUntilChanged()

    suspend fun setFavoriteComments(comments: List<FavoriteComment>) = update(
        Keys.FAVORITE_COMMENTS,
        kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(FavoriteComment.serializer()),
            comments.take(FavoriteComment.MAXIMUM),
        ),
    )

    suspend fun resetFavoriteComments() {
        dataStore.edit { it.remove(Keys.FAVORITE_COMMENTS) }
    }

    /**
     * Whether this device's TTS engine reports in-utterance positions (onRangeStart):
     * "" = not yet observed, "yes" / "no" once a linked reading has run. Device-local — an
     * engine trait, not a choice — and never surfaced in the settings UI.
     */
    val speechRangeSupport: Flow<String> =
        stringFlow(Keys.SPEECH_RANGE_SUPPORT).distinctUntilChanged()

    suspend fun setSpeechRangeSupport(value: String) = update(Keys.SPEECH_RANGE_SUPPORT, value)

    /**
     * 読める形式の自動書き出し — device-local, like every "how this device works" preference:
     * the destination folder and the rhythm belong to this phone, so none of it enters the
     * backup, and reset/restore leave it untouched.
     */
    val portableAutoExportEnabled: Flow<Boolean> = stringFlow(Keys.PORTABLE_AUTO_EXPORT_ENABLED)
        .map { it == "true" }
        .distinctUntilChanged()

    val portableAutoExportTreeUri: Flow<String> =
        stringFlow(Keys.PORTABLE_AUTO_EXPORT_TREE).distinctUntilChanged()

    val portableAutoExportIntervalDays: Flow<Int> =
        stringFlow(Keys.PORTABLE_AUTO_EXPORT_INTERVAL)
            .map { it.toIntOrNull() ?: 1 }
            .distinctUntilChanged()

    val portableAutoExportLastRunMillis: Flow<Long> =
        stringFlow(Keys.PORTABLE_AUTO_EXPORT_LAST_RUN)
            .map { it.toLongOrNull() ?: 0L }
            .distinctUntilChanged()

    val portableAutoExportLastResult: Flow<String> =
        stringFlow(Keys.PORTABLE_AUTO_EXPORT_LAST_RESULT).distinctUntilChanged()

    suspend fun setPortableAutoExportEnabled(enabled: Boolean) =
        update(Keys.PORTABLE_AUTO_EXPORT_ENABLED, enabled.toString())

    suspend fun setPortableAutoExportTreeUri(uri: String) =
        update(Keys.PORTABLE_AUTO_EXPORT_TREE, uri)

    suspend fun setPortableAutoExportIntervalDays(days: Int) =
        update(Keys.PORTABLE_AUTO_EXPORT_INTERVAL, days.toString())

    suspend fun setPortableAutoExportLastRun(millis: Long, result: String) {
        update(Keys.PORTABLE_AUTO_EXPORT_LAST_RUN, millis.toString())
        update(Keys.PORTABLE_AUTO_EXPORT_LAST_RESULT, result)
    }

    private fun stringFlow(key: Preferences.Key<String>): Flow<String> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences -> preferences[key].orEmpty() }

    /**
     * The readings the writer taught the 読み上げ — surface to reading, applied before any text
     * reaches the speech engine. Device-local like the rest of the ways of looking and hearing.
     */
    val speechDictionary: Flow<List<SpeechDictionaryEntry>> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences ->
            preferences[Keys.SPEECH_DICTIONARY]?.let { raw ->
                runCatching {
                    kotlinx.serialization.json.Json.decodeFromString<List<SpeechDictionaryEntry>>(raw)
                }.getOrNull()
            } ?: emptyList()
        }
        .distinctUntilChanged()

    suspend fun setSpeechDictionary(entries: List<SpeechDictionaryEntry>) = update(
        Keys.SPEECH_DICTIONARY,
        kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(SpeechDictionaryEntry.serializer()),
            entries.take(SpeechDictionary.MAXIMUM),
        ),
    )

    suspend fun resetSpeechDictionary() {
        dataStore.edit { it.remove(Keys.SPEECH_DICTIONARY) }
    }

    /**
     * Whether the wall greets an opened app with its own stream, once per launch. On by
     * default — flowing comments are the centre of this app, and the first open is the one
     * moment they can introduce themselves — and switchable off in 設定.
     */
    /**
     * ルビは読みを読み上げる: whether speech says the reading written over a word
     * (｜戦《いくさ》 spoken as いくさ) instead of leaving the kanji to the engine.
     * Default off — the page's own words are what the voice reads unless asked.
     */
    /** ノートの閲覧モード: a swipe turns to the next / previous episode. On unless switched off. */
    val readerSwipeTurns: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.READER_SWIPE_TURNS] != "false" }
        .distinctUntilChanged()

    suspend fun setReaderSwipeTurns(enabled: Boolean) =
        update(Keys.READER_SWIPE_TURNS, enabled.toString())

    val speechReadRuby: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.SPEECH_READ_RUBY] == "true" }
        .distinctUntilChanged()

    suspend fun setSpeechReadRuby(enabled: Boolean) =
        update(Keys.SPEECH_READ_RUBY, enabled.toString())

    val autoPlayOnLaunch: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { it[Keys.AUTO_PLAY_ON_LAUNCH] != "false" }
        .distinctUntilChanged()

    suspend fun setAutoPlayOnLaunch(enabled: Boolean) =
        update(Keys.AUTO_PLAY_ON_LAUNCH, enabled.toString())

    suspend fun setPlaybackDisplayMode(id: String) = update(Keys.PLAYBACK_DISPLAY, id)
    suspend fun setPlaybackContentMode(id: String) = update(Keys.PLAYBACK_CONTENT, id)
    suspend fun setOverlayRegion(id: String) = update(Keys.OVERLAY_REGION, id)
    suspend fun setOverlayDensity(id: String) = update(Keys.OVERLAY_DENSITY, id)

    /** Tests share a device; this puts the remembered style back the way a fresh install has it. */
    suspend fun resetPlaybackStyle() {
        dataStore.edit { preferences ->
            preferences.remove(Keys.PLAYBACK_DISPLAY)
            preferences.remove(Keys.PLAYBACK_CONTENT)
            preferences.remove(Keys.OVERLAY_REGION)
            preferences.remove(Keys.OVERLAY_DENSITY)
        }
    }

    suspend fun setTheme(themeMode: ThemeMode) = update(Keys.THEME, themeMode.storageId)

    suspend fun setPlaybackSpeed(playbackSpeed: PlaybackSpeed) =
        update(Keys.PLAYBACK_SPEED, playbackSpeed.storageId)

    suspend fun setCommentSize(commentSize: CommentSize) =
        update(Keys.COMMENT_SIZE, commentSize.storageId)

    /**
     * The speed slider. The exact value is stored device-locally; the frozen backup vocabulary
     * (ゆっくり/標準/速い) is kept honest by also writing the nearest named speed, so a backup
     * restored elsewhere lands close to what this device was doing.
     */
    suspend fun setPlaybackSpeedScale(scale: Float) {
        dataStore.edit { preferences ->
            preferences[Keys.PLAYBACK_SPEED_SCALE] = scale.toString()
            preferences[Keys.PLAYBACK_SPEED] = PlaybackSpeed.nearest(scale).storageId
        }
    }

    /** The size slider; same arrangement as [setPlaybackSpeedScale]. */
    suspend fun setCommentSizeScale(scale: Float) {
        dataStore.edit { preferences ->
            preferences[Keys.COMMENT_SIZE_SCALE] = scale.toString()
            preferences[Keys.COMMENT_SIZE] = CommentSize.nearest(scale).storageId
        }
    }

    suspend fun setLongCommentReadability(enabled: Boolean) =
        update(Keys.LONG_COMMENT_READABILITY, enabled.toString())

    suspend fun setStageBackground(stageBackground: StageBackground) =
        update(Keys.STAGE_BACKGROUND, stageBackground.storageId)

    suspend fun setCommentScope(scope: WorkCommentScope) =
        update(Keys.COMMENT_SCOPE, scope.storageId)

    suspend fun replaceAll(settings: AppSettings) {
        dataStore.edit { preferences ->
            preferences[Keys.THEME] = settings.themeMode.storageId
            preferences[Keys.PLAYBACK_SPEED] = settings.playbackSpeed.storageId
            preferences[Keys.COMMENT_SIZE] = settings.commentSize.storageId
            preferences[Keys.STAGE_BACKGROUND] = settings.stageBackground.storageId
            preferences[Keys.COMMENT_SCOPE] = settings.commentScope.storageId
            // A restore makes the backup's named speed and size authoritative again; a slider
            // value left behind would silently override what was just restored.
            preferences.remove(Keys.PLAYBACK_SPEED_SCALE)
            preferences.remove(Keys.COMMENT_SIZE_SCALE)
        }
    }

    /** マイカラー for note covers: opaque ARGB values, the newest first. */
    val myCoverColors: Flow<List<Int>> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences -> decodeCoverColors(preferences[Keys.NOTE_COVER_MY_COLORS]) }
        .distinctUntilChanged()

    suspend fun setMyCoverColors(colors: List<Int>) = update(
        Keys.NOTE_COVER_MY_COLORS,
        colors.take(NoteCoverPalette.MAXIMUM).joinToString(","),
    )

    /** Keeps [argb] at the front of マイカラー; the oldest colour makes room for it. */
    suspend fun rememberCoverColor(argb: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.NOTE_COVER_MY_COLORS] = NoteCoverPalette
                .remembered(decodeCoverColors(preferences[Keys.NOTE_COVER_MY_COLORS]), argb)
                .joinToString(",")
        }
    }

    suspend fun forgetCoverColor(argb: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.NOTE_COVER_MY_COLORS] = NoteCoverPalette
                .forgotten(decodeCoverColors(preferences[Keys.NOTE_COVER_MY_COLORS]), argb)
                .joinToString(",")
        }
    }

    private fun decodeCoverColors(raw: String?): List<Int> =
        raw.orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }

    suspend fun resetToDefaults() {
        dataStore.edit { preferences ->
            preferences.remove(Keys.THEME)
            preferences.remove(Keys.PLAYBACK_SPEED)
            preferences.remove(Keys.COMMENT_SIZE)
            preferences.remove(Keys.STAGE_BACKGROUND)
            preferences.remove(Keys.COMMENT_SCOPE)
            // The sliders are 再生設定 too — the reset dialog promises they go back to 標準.
            preferences.remove(Keys.PLAYBACK_SPEED_SCALE)
            preferences.remove(Keys.COMMENT_SIZE_SCALE)
            preferences.remove(Keys.LONG_COMMENT_READABILITY)
            // The wall's sort is a display setting like the rest: back to 更新が新しい順.
            preferences.remove(Keys.MEMO_SORT_MODE)
            preferences.remove(Keys.READER_SWIPE_TURNS)
        }
    }

    private suspend fun update(key: Preferences.Key<String>, value: String) {
        dataStore.edit { it[key] = value }
    }

    private fun toAppSettings(preferences: Preferences): AppSettings {
        val playbackSpeed = PlaybackSpeed.fromStorageId(preferences[Keys.PLAYBACK_SPEED])
        val commentSize = CommentSize.fromStorageId(preferences[Keys.COMMENT_SIZE])
        return AppSettings(
            themeMode = ThemeMode.fromStorageId(preferences[Keys.THEME]),
            playbackSpeed = playbackSpeed,
            commentSize = commentSize,
            stageBackground = StageBackground.fromStorageId(preferences[Keys.STAGE_BACKGROUND]),
            commentScope = WorkCommentScope.fromStorageId(preferences[Keys.COMMENT_SCOPE]),
            playbackSpeedScale = preferences[Keys.PLAYBACK_SPEED_SCALE]?.toFloatOrNull()
                ?: playbackSpeed.velocityMultiplier,
            commentSizeScale = preferences[Keys.COMMENT_SIZE_SCALE]?.toFloatOrNull()
                ?: commentSize.scaleMultiplier,
            longCommentReadability =
                preferences[Keys.LONG_COMMENT_READABILITY] == "true",
        )
    }

    internal object Keys {
        val AI_SELECTED_MODEL_ID = stringPreferencesKey("ai_selected_model_id")
        val CHAT_LAST_CONVERSATION_ID = stringPreferencesKey("chat_last_conversation_id")
        val CHAT_PINNED_CONVERSATION_IDS = stringSetPreferencesKey("chat_pinned_conversation_ids")
        // a newline-joined list, not a set: the pin order is kept (a set would lose it); the key name mirrors the drawer's
        val CHAT_PINNED_TEMPLATE_IDS = stringPreferencesKey("chat_pinned_template_ids")
        val CHAT_CREATE_FOLDER_ID = longPreferencesKey("chat_create_folder_id")
        // 「メモを選択」 (2026-09-26): conversation id → memo id, ids only
        val CHAT_SELECTED_MEMOS = stringSetPreferencesKey("chat_selected_memo_ids")
        // closed once, never re-opened (UI/UX review 2026-09-23): only "true" is ever written here
        val CHAT_AI_HINT_DISMISSED = stringPreferencesKey("chat_ai_hint_dismissed")
        // the home launcher.s own slots (2026-09-23) — ids and the names the user gave them; not the picker.s pins
        val CHAT_HOME_SHORTCUTS = stringPreferencesKey("chat_home_shortcuts")
        val THEME = stringPreferencesKey("theme_mode")
        val LONG_COMMENT_READABILITY = stringPreferencesKey("long_comment_readability")
        val USER_COMMENT_FONTS = stringPreferencesKey("user_comment_fonts")
        val HIDDEN_COMMENT_FONTS = stringPreferencesKey("hidden_comment_fonts")
        val SPEECH_RANGE_SUPPORT = stringPreferencesKey("speech_range_start_support")
        val PORTABLE_AUTO_EXPORT_ENABLED = stringPreferencesKey("portable_auto_export_enabled")
        val PORTABLE_AUTO_EXPORT_TREE = stringPreferencesKey("portable_auto_export_tree")
        val PORTABLE_AUTO_EXPORT_INTERVAL = stringPreferencesKey("portable_auto_export_interval")
        val PORTABLE_AUTO_EXPORT_LAST_RUN = stringPreferencesKey("portable_auto_export_last_run")
        val PORTABLE_AUTO_EXPORT_LAST_RESULT =
            stringPreferencesKey("portable_auto_export_last_result")
        val PLAYBACK_SPEED = stringPreferencesKey("playback_speed")
        val COMMENT_SIZE = stringPreferencesKey("comment_size")
        val STAGE_BACKGROUND = stringPreferencesKey("stage_background")
        val COMMENT_SCOPE = stringPreferencesKey("comment_scope")
        val WALL_DISPLAY = stringPreferencesKey("memo_wall_display")
        val WALL_SINGLE_COLUMN = stringPreferencesKey("memo_wall_single_column")
        val EDITOR_TOOLBAR_TWO_ROWS = stringPreferencesKey("editor_toolbar_two_rows")
        val EDITOR_TOOLBAR_OUTLINER_ORDER = stringPreferencesKey("editor_toolbar_outliner_order")
        val EDITOR_TOOLBAR_ORDER = stringPreferencesKey("editor_toolbar_order")
        val OUTLINER_FOLDS = stringPreferencesKey("outliner_folds")
        val OUTLINER_SCROLL = stringPreferencesKey("outliner_scroll")
        val EDITOR_TOOLBAR_NOTE_ORDER = stringPreferencesKey("editor_toolbar_order_note")
        val EDITOR_MENU_COPY_ALL = stringPreferencesKey("editor_menu_copy_all")
        val HAS_SEEN_WELCOME_DEMO = stringPreferencesKey("has_seen_welcome_demo")
        val HAS_SEEN_OVERLAY_SETUP = stringPreferencesKey("has_seen_overlay_setup")
        val HAS_SEEN_OUTLINE_GUIDE = stringPreferencesKey("has_seen_outline_guide")
        val EDITOR_MENU_PDF_EXPORT = stringPreferencesKey("editor_menu_pdf_export")
        val EDITOR_EXPORT_DOCX = stringPreferencesKey("editor_export_docx")
        val OUTLINE_SYMBOL_SET = stringPreferencesKey("outline_symbol_set")
        val MEMO_SORT_MODE = stringPreferencesKey("memo_sort_mode")
        val OUTLINE_CHIP_LABEL = stringPreferencesKey("outline_chip_label")
        val EDITOR_HIDE_TAGS = stringPreferencesKey("editor_hide_tags")
        val PLAYBACK_DISPLAY = stringPreferencesKey("playback_display_mode")
        val PLAYBACK_CONTENT = stringPreferencesKey("playback_content_mode")
        val OVERLAY_REGION = stringPreferencesKey("overlay_region")
        val OVERLAY_DENSITY = stringPreferencesKey("overlay_density")
        val DIARY_CALENDAR_COMPACT = stringPreferencesKey("diary_calendar_compact")
        val KEEP_COMMENT_EXPRESSION = stringPreferencesKey("keep_comment_expression")
        val KEPT_COMMENT_EXPRESSION = stringPreferencesKey("kept_comment_expression")
        val FAVORITE_COMMENTS = stringPreferencesKey("favorite_comments")
        val AUTO_PLAY_ON_LAUNCH = stringPreferencesKey("auto_play_on_launch")
        val SPEECH_READ_RUBY = stringPreferencesKey("speech_read_ruby")
        val READER_SWIPE_TURNS = stringPreferencesKey("reader_swipe_turns")
        val NOTE_COVER_MY_COLORS = stringPreferencesKey("note_cover_my_colors")
        val COMMENT_FONT = stringPreferencesKey("comment_font")
        val THEME_SEED = stringPreferencesKey("theme_seed")
        val THEME_PALETTE_STYLE = stringPreferencesKey("theme_palette_style")
        val WALL_OVERLAY_PLAYBACK = stringPreferencesKey("wall_overlay_playback")
        val COMMENT_BACKDROP = stringPreferencesKey("comment_backdrop")
        val WALL_PLAYS_CONTENT = stringPreferencesKey("wall_plays_content")
        val PLAYBACK_SPEED_SCALE = stringPreferencesKey("playback_speed_scale")
        val COMMENT_SIZE_SCALE = stringPreferencesKey("comment_size_scale")
        val SPEECH_DICTIONARY = stringPreferencesKey("speech_dictionary")
        val COMMENT_TRANSPARENCY = stringPreferencesKey("comment_transparency")
    }
}

/** Raw storage ids; null means the app's own default. The UI layer maps them to its enums. */
data class PlaybackStyle(
    val displayModeId: String? = null,
    val contentModeId: String? = null,
    val overlayRegionId: String? = null,
    val overlayDensityId: String? = null,
)

/** One favourite comment: a chip's short face and the words it writes. */
@kotlinx.serialization.Serializable
data class FavoriteComment(
    val key: String,
    val label: String,
    val text: String,
) {
    companion object {
        const val MAXIMUM = 10

        /** The starting set; deleting or adding to it is the owner's business. */
        val Defaults = listOf(
            FavoriteComment("grass", "草", "wwwwwwww"),
            FavoriteComment("applause", "拍手", "88888888"),
            FavoriteComment("surprise", "驚き", "！？！？！？"),
            FavoriteComment("kita", "ｷﾀ━━", "ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!"),
            FavoriteComment("like_here", "ここ好き", "ここ好き"),
            FavoriteComment("cheer", "がんばれ", "がんばれ！"),
        )
    }
}

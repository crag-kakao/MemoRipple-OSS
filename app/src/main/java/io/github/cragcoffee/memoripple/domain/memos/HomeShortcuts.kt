package io.github.cragcoffee.memoripple.domain.memos

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The chat home's launcher (human brief 2026-09-23): the small grid the chat shows while a
 * conversation is still empty — four fixed entrances and up to four templates the user has put
 * there, like the shortcuts on a phone's home screen.
 *
 * A cell is an **entrance, not an action**: it names a path the chat already has, and tapping it
 * opens that path — a template's conversation script, the ＋ picker at a page, or one fixed
 * question whose answer walks the same validator → resolver → policy → preview → human
 * confirmation as everything else. Nothing here writes, confirms or asks a model for anything.
 *
 * The home is **its own short list** (2026-09-23, the user's review): 「＋ 追加」 puts a template
 * here and a long press takes it away or gives it another name. It is **not** the ＋ picker's
 * ピン留め — that orders the picker, this is the home — and the two are kept apart on purpose.
 */
sealed interface HomeShortcut {
    /** Stable within one grid: what the screen keys and tags a cell by. */
    val key: String

    /** What the cell says under its mark — never left out, never replaced by the mark alone. */
    val label: String

    /** 「何をメモしますか？」 — the answer becomes the body of a memo, previewed before anything is written. */
    data object Memo : HomeShortcut {
        override val key = "memo"
        override val label = "メモ"
    }

    /** 「何を探しますか？」 — the answer is a search, answered as cards in the conversation. */
    data object Search : HomeShortcut {
        override val key = "search"
        override val label = "探す"
    }

    /** The built-in 今日の振り返り template, asked one question at a time as always. */
    data object Journal : HomeShortcut {
        override val key = "journal"
        override val label = "日記"
    }

    /** The ＋ picker, opened at 整理・壁打ち. */
    data object Organize : HomeShortcut {
        override val key = "organize"
        override val label = "整理"
    }

    /**
     * One template the user put on the home: its own script, its own flow — a Think template
     * starts Think. [entry] may carry a name of the user's choosing; blank means the template's own.
     */
    data class Template(val entry: HomeShortcutEntry, val template: MemoTemplate) : HomeShortcut {
        override val key = "template:${template.id}"
        override val label = entry.label.ifBlank { template.name }

        /** Whether this cell shows a name the user gave it rather than the template's. */
        val renamed: Boolean get() = entry.label.isNotBlank()
    }

    /** Where a shortcut is added: the ＋ picker, in its 「ホームに追加」 mode. */
    data object Add : HomeShortcut {
        override val key = "add"
        override val label = "追加"
    }
}

/**
 * One slot of the home: which template, and the name the user gave it (blank = the template's own
 * name). Ids only — never a copy of a template, never its body; a slot whose template is gone is
 * simply skipped.
 */
@Serializable
data class HomeShortcutEntry(val templateId: String, val label: String = "")

/** Where the home's slots live (a light preference of this device); never in a backup. */
interface HomeShortcutStore {
    val shortcuts: Flow<List<HomeShortcutEntry>>

    /** Puts [templateId] at the end, if it is not there already and there is room. */
    suspend fun add(templateId: String)
    suspend fun remove(templateId: String)

    /** [label] blank restores the template's own name. */
    suspend fun rename(templateId: String, label: String)
}

object HomeShortcuts {
    /** メモ / 探す / 日記 / 整理 — always present, always in this order, never rearranged or removed. */
    const val FIXED = 4

    /** The user's own slots. */
    const val MAX_SHORTCUTS = 4

    /** Four columns, at most two rows. */
    const val MAX_CELLS = 8

    /** A name the user gives a cell has to fit one line under an icon. */
    const val MAX_LABEL_CHARS = 12

    val fixed: List<HomeShortcut> = listOf(HomeShortcut.Memo, HomeShortcut.Search, HomeShortcut.Journal, HomeShortcut.Organize)

    /** Trimmed and capped; a name that is no name is no name, and the template's own is used instead. */
    fun cleanLabel(raw: String): String = raw.trim().replace("\n", " ").take(MAX_LABEL_CHARS)

    /** [templateId] joins the end if it is new and there is room; an id already there is left where it is. */
    fun added(entries: List<HomeShortcutEntry>, templateId: String): List<HomeShortcutEntry> =
        if (entries.any { it.templateId == templateId } || entries.size >= MAX_SHORTCUTS) entries else entries + HomeShortcutEntry(templateId)

    fun removed(entries: List<HomeShortcutEntry>, templateId: String): List<HomeShortcutEntry> = entries.filterNot { it.templateId == templateId }

    fun renamed(entries: List<HomeShortcutEntry>, templateId: String, label: String): List<HomeShortcutEntry> =
        entries.map { if (it.templateId == templateId) it.copy(label = cleanLabel(label)) else it }

    /**
     * The grid for [entries] against [templates] (the user's own plus the starters, as the chat
     * already holds them): the fixed four, then the slots that still name a template in their
     * order, then — only while there is room — the entrance that adds one. No empty slot is ever
     * invented, and the grid never grows past [MAX_CELLS].
     */
    fun of(templates: List<MemoTemplate>, entries: List<HomeShortcutEntry>): List<HomeShortcut> {
        val chosen = entries.mapNotNull { e -> templates.firstOrNull { it.id == e.templateId }?.let { HomeShortcut.Template(e, it) } }.take(MAX_SHORTCUTS)
        val cells = fixed + chosen
        return if (chosen.size < MAX_SHORTCUTS) cells + HomeShortcut.Add else cells
    }
}

/** The slots on the wire: a small JSON list in one preference — ids and names, nothing else. */
object HomeShortcutCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(entries: List<HomeShortcutEntry>): String = json.encodeToString(entries.take(HomeShortcuts.MAX_SHORTCUTS))

    /** Anything unreadable is no shortcut at all: the home comes back with its fixed four. */
    fun decode(raw: String?): List<HomeShortcutEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<HomeShortcutEntry>>(raw) }.getOrDefault(emptyList())
            .filter { it.templateId.isNotBlank() }
            .distinctBy { it.templateId }
            .map { it.copy(label = HomeShortcuts.cleanLabel(it.label)) }
            .take(HomeShortcuts.MAX_SHORTCUTS)
    }
}

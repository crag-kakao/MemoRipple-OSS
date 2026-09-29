package io.github.cragcoffee.memoripple.domain.memos

import kotlinx.serialization.Serializable

/**
 * A template folder (2026-09-22): one flat level for the user's own templates in the ＋ picker's
 * 自分のテンプレート — an id, a name, a place in the order, and nothing else (no colour, no icon, no
 * nesting). Template *organisation*, in its own store beside the templates: it is not the memo
 * wall's folder (a document's location) and shares nothing with it.
 */
@Serializable
data class TemplateFolder(val id: String, val name: String, val order: Int)

/** The rules a folder list obeys; a deleted folder never deletes a template — it unassigns them. */
object TemplateFolderPolicy {
    const val MAX_FOLDERS = 30
    const val MAX_NAME_CHARS = 30

    fun cleanName(name: String, fallback: String = "フォルダ"): String = name.trim().take(MAX_NAME_CHARS).ifBlank { fallback }

    /** [folder] replaces the one with its id, else joins at the end; the order is renumbered 0..n. */
    fun upsert(existing: List<TemplateFolder>, folder: TemplateFolder): List<TemplateFolder> {
        val list = if (existing.any { it.id == folder.id }) existing.map { if (it.id == folder.id) folder.copy(order = it.order) else it } else (existing + folder.copy(order = existing.size)).take(MAX_FOLDERS)
        return renumber(list.sortedBy { it.order })
    }

    fun rename(existing: List<TemplateFolder>, id: String, name: String): List<TemplateFolder> = existing.map { if (it.id == id) it.copy(name = cleanName(name)) else it }

    /** One step up (-1) or down (+1); unchanged at either end. */
    fun move(existing: List<TemplateFolder>, id: String, direction: Int): List<TemplateFolder> {
        val sorted = existing.sortedBy { it.order }
        val from = sorted.indexOfFirst { it.id == id }
        val to = from + direction
        if (from < 0 || to !in sorted.indices) return existing
        return renumber(sorted.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun remove(existing: List<TemplateFolder>, id: String): List<TemplateFolder> = renumber(existing.sortedBy { it.order }.filterNot { it.id == id })

    /** The templates of a removed folder become 未分類; nothing else about them changes. */
    fun unassign(templates: List<MemoTemplate>, folderId: String): List<MemoTemplate> = templates.map { if (it.folderId == folderId) it.copy(folderId = null) else it }

    /** The list as given, numbered 0..n — the caller decides the order (a move hands the moved order in). */
    private fun renumber(list: List<TemplateFolder>): List<TemplateFolder> = list.mapIndexed { i, f -> f.copy(order = i) }
}

/**
 * The ＋ picker as an entrance (2026-09-22): the root names at most [MAX_ROOT_ROWS] pins and as many
 * recents (pins left out), then four folder rows; a folder is opened to see its templates.
 */
object TemplatePicker {
    const val MAX_ROOT_ROWS = 3

    /** One row of 自分のテンプレート: a folder (null = 未分類) and how many templates it holds. */
    data class MineRow(val folder: TemplateFolder?, val count: Int)

    data class Root(
        val pinned: List<MemoTemplate>,
        val morePinned: Boolean,
        val recent: List<MemoTemplate>,
        val builtInCounts: Map<ThinkTemplates.Section, Int>,
        val mineCount: Int,
    )

    fun root(templates: List<MemoTemplate>, pinnedIds: List<String>, recentIds: List<String>, folders: List<TemplateFolder>): Root {
        val pinnedAll = PinnedTemplates.resolve(pinnedIds, templates)
        val pinnedSet = pinnedAll.map { it.id }.toSet()
        val recentAll = recentIds.mapNotNull { id -> templates.firstOrNull { it.id == id } }.filterNot { it.id in pinnedSet }
        val starters = templates.filter { StarterTemplates.isStarter(it.id) }
        return Root(
            pinned = pinnedAll.take(MAX_ROOT_ROWS),
            morePinned = pinnedAll.size > MAX_ROOT_ROWS,
            recent = recentAll.take(MAX_ROOT_ROWS),
            builtInCounts = ThinkTemplates.Section.entries.associateWith { s -> starters.count { ThinkTemplates.sectionOf(it) == s } }.filterValues { it > 0 },
            mineCount = templates.count { !StarterTemplates.isStarter(it.id) },
        )
    }

    /** The starters of a built-in folder, in their order; a custom template is never here. */
    fun builtIn(templates: List<MemoTemplate>, section: ThinkTemplates.Section): List<MemoTemplate> =
        templates.filter { StarterTemplates.isStarter(it.id) && ThinkTemplates.sectionOf(it) == section }

    /** 自分のテンプレート: the user's folders in order, then 未分類 — each with its count; 未分類 holds an orphan folder id too. */
    fun mine(templates: List<MemoTemplate>, folders: List<TemplateFolder>): List<MineRow> {
        val sorted = folders.sortedBy { it.order }
        return sorted.map { f -> MineRow(f, inFolder(templates, sorted, f.id).size) } + MineRow(null, inFolder(templates, sorted, null).size)
    }

    /** The custom templates of one folder; null = 未分類, which also gathers an id no folder has. */
    fun inFolder(templates: List<MemoTemplate>, folders: List<TemplateFolder>, folderId: String?): List<MemoTemplate> {
        val known = folders.map { it.id }.toSet()
        return templates.filter { !StarterTemplates.isStarter(it.id) }.filter { t ->
            if (folderId == null) t.folderId == null || t.folderId !in known else t.folderId == folderId
        }
    }
}

/** Where the folders live (a preference beside the templates). */
interface TemplateFolderStore {
    val folders: kotlinx.coroutines.flow.Flow<List<TemplateFolder>>
    suspend fun current(): List<TemplateFolder>
}

package io.github.cragcoffee.memoripple.domain.folders

/**
 * One row of the folder navigator: a projection for drawing, never stored. Folders are where
 * documents are kept; the outline inside a document is body text and never appears here.
 */
data class FolderRow(
    val folderId: Long,
    val name: String,
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
    val selected: Boolean,
)

/**
 * The tree as the navigator shows it: every root, and under each expanded folder its
 * children, in [FolderTree.flatten]'s order. One pass over the flattened list with a single
 * cursor — "hidden below depth d" — so a closed folder hides its whole subtree without a
 * second walk, and a stale id in [expanded] (a folder since moved or deleted) changes nothing.
 */
object FolderNavigator {
    fun rows(
        folders: List<FolderNode>,
        order: Comparator<FolderNode>,
        expanded: Set<Long>,
        selected: Long?,
    ): List<FolderRow> {
        val parents = HashSet<Long>(folders.size)
        folders.forEach { node -> node.parentId?.let(parents::add) }
        val out = ArrayList<FolderRow>(folders.size)
        // Rows deeper than this are inside a collapsed folder; -1 = nothing collapsed above.
        var hiddenBelow = -1
        FolderTree.flatten(folders, order).forEach { (node, depth) ->
            if (hiddenBelow >= 0 && depth > hiddenBelow) return@forEach
            hiddenBelow = -1
            val hasChildren = node.id in parents
            val isExpanded = hasChildren && node.id in expanded
            out += FolderRow(
                folderId = node.id,
                name = node.name,
                depth = depth,
                hasChildren = hasChildren,
                expanded = isExpanded,
                selected = node.id == selected,
            )
            if (hasChildren && !isExpanded) hiddenBelow = depth
        }
        return out
    }

    /** The folders that must be expanded for [selected] to be visible: its ancestors. */
    fun revealing(folders: List<FolderNode>, selected: Long?): Set<Long> {
        if (selected == null) return emptySet()
        return FolderTree.ancestors(folders, selected).mapTo(LinkedHashSet()) { it.id }
    }
}

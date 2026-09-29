package io.github.cragcoffee.memoripple.domain.folders

/**
 * A folder as the tree sees it. Folders are where documents are kept; they know nothing
 * about a document's kind and nothing about the outline structure *inside* a document —
 * that is body text (OutlineText), never a folder.
 */
data class FolderNode(val id: Long, val parentId: Long?, val name: String)

/** What a folder may be called. Trimmed; empty or all-space is no name at all. */
object FolderName {
    fun normalize(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }
}

/**
 * The pure rules of the folder hierarchy: parent means containment, `null` parent means the
 * root, and the tree must stay a tree — a folder is never its own ancestor. Every screen and
 * the repository ask here; nothing decides a move or a delete on its own. Every walk stops
 * the moment it meets a folder twice, so a broken tree (which a backup could carry) is
 * answered, never looped over.
 */
object FolderTree {
    /**
     * The tree as a list for drawing: parents before children, siblings in [order], each row
     * with its depth (root children = 0). One pass — children are grouped once, then walked
     * with an explicit stack — so a navigator can redraw on every expand. Folders whose
     * parent is missing, and anything on a cycle, are not reachable from the root and are
     * left out rather than looped over.
     */
    fun flatten(folders: List<FolderNode>, order: Comparator<FolderNode>): List<Pair<FolderNode, Int>> {
        val childrenOf = folders.groupBy { it.parentId }
        val out = ArrayList<Pair<FolderNode, Int>>(folders.size)
        val seen = HashSet<Long>(folders.size)
        val stack = ArrayDeque<Pair<FolderNode, Int>>()
        childrenOf[null].orEmpty().sortedWith(order).asReversed().forEach { stack.addLast(it to 0) }
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            if (!seen.add(node.id)) continue
            out += node to depth
            childrenOf[node.id].orEmpty().sortedWith(order).asReversed().forEach { stack.addLast(it to depth + 1) }
        }
        return out
    }

    /** The folders whose parent is [parentId] (`null` = the root), in the order given. */
    fun children(folders: List<FolderNode>, parentId: Long?): List<FolderNode> =
        folders.filter { it.parentId == parentId }

    /** The parents of [id] from the root down to its own parent; empty at the root. */
    fun ancestors(folders: List<FolderNode>, id: Long): List<FolderNode> {
        val byId = folders.associateBy { it.id }
        val seen = hashSetOf(id)
        val chain = ArrayList<FolderNode>()
        var parentId = byId[id]?.parentId
        while (parentId != null && seen.add(parentId)) {
            val parent = byId[parentId] ?: break
            chain += parent
            parentId = parent.parentId
        }
        return chain.asReversed()
    }

    /** [ancestors] followed by the folder itself — the breadcrumb. */
    fun path(folders: List<FolderNode>, id: Long): List<FolderNode> {
        val self = folders.firstOrNull { it.id == id } ?: return emptyList()
        return ancestors(folders, id) + self
    }

    /** Every folder below [id], at any depth. */
    fun descendants(folders: List<FolderNode>, id: Long): Set<Long> {
        val childrenOf = folders.groupBy { it.parentId }
        val found = linkedSetOf<Long>()
        val queue = ArrayDeque<Long>().apply { add(id) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            childrenOf[current].orEmpty().forEach { child ->
                if (child.id != id && found.add(child.id)) queue.add(child.id)
            }
        }
        return found
    }

    /**
     * Whether [id] may be re-parented under [targetParentId]: the target exists (or is the
     * root), is not the folder itself and is not one of its descendants.
     */
    fun canMoveTo(folders: List<FolderNode>, id: Long, targetParentId: Long?): Boolean {
        if (folders.none { it.id == id }) return false
        if (targetParentId == null) return true
        if (targetParentId == id) return false
        if (folders.none { it.id == targetParentId }) return false
        return targetParentId !in descendants(folders, id)
    }

    /** Whether a document may be put into [folderId]: it exists, or it is the root. */
    fun canHold(folders: List<FolderNode>, folderId: Long?): Boolean =
        folderId == null || folders.any { it.id == folderId }

    /**
     * Whether [folders] is one sound tree: ids unique, every parent present, no cycle. What
     * a backup must satisfy before it is restored, and what the repository keeps true.
     */
    fun isWellFormed(folders: List<FolderNode>): Boolean {
        val byId = HashMap<Long, FolderNode>(folders.size)
        folders.forEach { if (byId.put(it.id, it) != null) return false }
        folders.forEach { folder ->
            val parent = folder.parentId
            if (parent != null && parent !in byId) return false
        }
        // Walk up from every folder; a walk that revisits a folder is a cycle.
        folders.forEach { start ->
            val seen = hashSetOf<Long>()
            var current: FolderNode? = start
            while (current != null) {
                if (!seen.add(current.id)) return false
                current = current.parentId?.let(byId::get)
            }
        }
        return true
    }

    /**
     * Deleting [id] never deletes what it holds: its documents and its child folders move up
     * to its parent (the root when it was a root folder). Returns the parent they go to, or
     * null when [id] is not a folder or was already at the root.
     */
    fun promotionTarget(folders: List<FolderNode>, id: Long): Long? =
        folders.firstOrNull { it.id == id }?.parentId
}

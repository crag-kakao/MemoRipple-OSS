package io.github.cragcoffee.memoripple.domain.folders

import io.github.cragcoffee.memoripple.domain.documents.CreateDestination
import kotlinx.coroutines.flow.Flow
import java.text.Collator
import java.util.Locale

/** One folder the chat may choose as a destination: the wall's folder, its name, its depth for indentation. */
data class FolderChoice(val id: Long, val name: String, val depth: Int)

/**
 * The wall's folders as a flat, indented list for the chat's 「フォルダを選択」 (2026-09-22) — the same
 * walk the navigator uses (`FolderTree.flatten`, the one tree walk), parents before children,
 * siblings by name. Choosing is the user's; the chat never invents a folder.
 */
object FolderChoices {
    fun of(folders: List<FolderNode>): List<FolderChoice> {
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        val order = Comparator<FolderNode> { a, b -> collator.compare(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id) }
        return FolderTree.flatten(folders, order).map { (node, depth) -> FolderChoice(node.id, node.name, depth) }
    }

    /** The chosen id as a destination — or null when nothing is chosen or the folder is gone (then the root, as before). */
    fun destination(choices: List<FolderChoice>, chosenId: Long?): CreateDestination? =
        chosenId?.let { id -> choices.firstOrNull { it.id == id }?.let { CreateDestination(it.id, it.name) } }
}

/** The port the chat reads the wall's folders through — and makes one with; implemented over the folder repository. */
interface DocumentFolderChoices {
    val choices: Flow<List<FolderChoice>>

    /** A new folder at the root by the wall's own rule; its id, or null when the name is no name (2026-09-22). */
    suspend fun create(name: String): Long?
}

/** The chat's one remembered destination (a preference: the folder id, or none). */
interface ChatDestinationStore {
    val folderId: Flow<Long?>
    suspend fun set(folderId: Long?)
}

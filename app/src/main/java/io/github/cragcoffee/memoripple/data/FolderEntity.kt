package io.github.cragcoffee.memoripple.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.cragcoffee.memoripple.domain.folders.FolderNode

/**
 * A folder: a place documents are kept, nested under another folder or at the root
 * (`parentFolderId = null`). No foreign key — the same rule as `memos.noteId`: `memos` is
 * never recreated, and what keeps `parentFolderId` and `memos.folderId` sound is
 * [FolderRepository], where every move and delete is one transaction checked against
 * the tree. A folder holds documents of any kind; the outline *inside* a document is body
 * text and has nothing to do with this table.
 */
@Entity(tableName = "folders", indices = [Index("parentFolderId")])
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentFolderId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

fun FolderEntity.toNode(): FolderNode = FolderNode(id, parentFolderId, name)

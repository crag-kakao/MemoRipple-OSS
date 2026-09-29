package io.github.cragcoffee.memoripple.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The note columns carry no foreign key. SQLite cannot add one to a table that already exists, and
 * `memos` is the parent of comments, tags and photos: recreating it to gain a constraint would put
 * everything hanging off it at risk for no benefit. Releasing episodes is done in
 * [NoteRepository] instead, where it is one statement and can be read.
 */
@Entity(
    tableName = "memos",
    // createdAt and updatedAt carry the calendar's month ranges (Room 24); the lists still order by
    // updatedAt, which the same index serves.
    indices = [Index("noteId"), Index("chapterId"), Index("kind"), Index("folderId"), Index("createdAt"), Index("updatedAt")],
)
data class MemoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isFavorite: Boolean = false,
    val isPinned: Boolean = false,
    val archivedAt: Long? = null,
    val trashedAt: Long? = null,
    /** The note this memo is an episode of, or null when it stands alone. */
    val noteId: Long? = null,
    val chapterId: Long? = null,
    /** Where it sits among the note's episodes. The displayed number comes from the position. */
    val episodeOrder: Int = 0,
    /**
     * `memo` or `outline` — a [io.github.cragcoffee.memoripple.domain.memos.MemoKind] storage id,
     * kept as the stored string so the row, the migration and the backup all carry the same
     * value. Read it through `memoKind`; nothing compares the raw string.
     */
    val kind: String = "memo",
    /** The folder this memo is kept in, or null at the root. Kept through archive and trash. */
    val folderId: Long? = null,
    /** 並べた順: the place the hand gave this card on its page (0 = not placed yet, shown newest first). */
    val sortIndex: Int = 0,
)

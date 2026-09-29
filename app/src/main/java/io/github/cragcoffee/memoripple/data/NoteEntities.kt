package io.github.cragcoffee.memoripple.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A note: an ordered run of memos read as one piece of writing.
 *
 * The note owns nothing a memo does not already have. It only says which memos belong together and
 * in what order, so a memo can leave a note and still be the same memo.
 */
@Entity(tableName = "notes", indices = [Index("coverBlobSha256")])
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /**
     * A line under the title: what the piece is, or a word to the writer from themselves. Empty is
     * the ordinary case, so it is stored as an empty string rather than as a null.
     */
    val subtitle: String = "",
    /** Storage id of the colour its cover is drawn in, so a note is recognisable in a list. */
    val coverColor: String,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * The picture on its cover, named by the hash of the picture itself, or null for a plain
     * colour. It carries no foreign key: SQLite cannot add one to a table that already exists, and
     * `notes` is the parent of chapters and of every memo that is an episode. What keeps the file
     * from being collected is that [AttachmentDao] counts this column as a reference.
     */
    val coverBlobSha256: String? = null,
    /** 並べた順: the place the hand gave this note in the list (0 = not placed yet, newest first). */
    val sortIndex: Int = 0,
)

/**
 * A heading inside a note. Episodes may sit under one or under none; the ones under none come first,
 * which is how a note reads before its writer has decided on chapters.
 */
@Entity(
    tableName = "note_chapters",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("noteId")],
)
data class NoteChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val title: String,
    val sortOrder: Int,
)

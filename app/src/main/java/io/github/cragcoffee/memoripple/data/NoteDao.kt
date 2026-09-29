package io.github.cragcoffee.memoripple.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** One row of the note list: the note plus what the list needs to draw without opening it. */
data class NoteSummary(
    val id: Long,
    val title: String,
    val subtitle: String,
    val coverColor: String,
    val coverBlobSha256: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val episodeCount: Int,
    val lastWrittenAt: Long,
    val sortIndex: Int = 0,
)

@Dao
interface NoteDao {

    @Query(
        """
        SELECT note.id, note.title, note.subtitle, note.coverColor, note.coverBlobSha256,
            note.createdAt, note.updatedAt, note.sortIndex,
            COUNT(memo.id) AS episodeCount,
            MAX(COALESCE(memo.updatedAt, note.updatedAt)) AS lastWrittenAt
        FROM notes AS note
        LEFT JOIN memos AS memo ON memo.noteId = note.id AND memo.trashedAt IS NULL
        GROUP BY note.id
        ORDER BY note.sortIndex ASC, lastWrittenAt DESC, note.id DESC
        """,
    )
    fun observeSummaries(): Flow<List<NoteSummary>>

    @Query("SELECT * FROM notes WHERE id = :noteId LIMIT 1")
    fun observeNote(noteId: Long): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :noteId LIMIT 1")
    suspend fun findById(noteId: Long): NoteEntity?

    /** One note's place in 並べた順; nothing else about it changes. */
    @Query("UPDATE notes SET sortIndex = :index WHERE id = :noteId")
    suspend fun setSortIndex(noteId: Long, index: Int): Int

    /** The whole order of the list as the hand left it, written as one change. */
    @Transaction
    suspend fun reorderNotes(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id -> setSortIndex(id, index) }
    }

    @Query("SELECT * FROM note_chapters WHERE noteId = :noteId ORDER BY sortOrder ASC, id ASC")
    fun observeChapters(noteId: Long): Flow<List<NoteChapterEntity>>

    @Query("SELECT * FROM note_chapters WHERE noteId = :noteId ORDER BY sortOrder ASC, id ASC")
    suspend fun chapters(noteId: Long): List<NoteChapterEntity>

    @Query(
        """
        SELECT * FROM memos
        WHERE noteId = :noteId AND trashedAt IS NULL
        ORDER BY episodeOrder ASC, id ASC
        """,
    )
    fun observeEpisodes(noteId: Long): Flow<List<MemoEntity>>

    @Query(
        """
        SELECT * FROM memos
        WHERE noteId = :noteId AND trashedAt IS NULL
        ORDER BY episodeOrder ASC, id ASC
        """,
    )
    suspend fun episodes(noteId: Long): List<MemoEntity>

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Insert
    suspend fun insertChapter(chapter: NoteChapterEntity): Long

    @Query("UPDATE notes SET title = :title, updatedAt = :now WHERE id = :noteId")
    suspend fun rename(noteId: Long, title: String, now: Long): Int

    @Query("UPDATE notes SET coverColor = :coverColor, updatedAt = :now WHERE id = :noteId")
    suspend fun setCoverColor(noteId: Long, coverColor: String, now: Long): Int

    @Query("UPDATE notes SET subtitle = :subtitle, updatedAt = :now WHERE id = :noteId")
    suspend fun setSubtitle(noteId: Long, subtitle: String, now: Long): Int

    @Query("UPDATE notes SET coverBlobSha256 = :sha256, updatedAt = :now WHERE id = :noteId")
    suspend fun setCoverPhoto(noteId: Long, sha256: String?, now: Long): Int

    @Query("UPDATE note_chapters SET title = :title WHERE id = :chapterId")
    suspend fun renameChapter(chapterId: Long, title: String): Int

    @Query("UPDATE note_chapters SET sortOrder = :sortOrder WHERE id = :chapterId")
    suspend fun updateChapterOrder(chapterId: Long, sortOrder: Int): Int

    @Query("DELETE FROM note_chapters WHERE id = :chapterId")
    suspend fun deleteChapterRow(chapterId: Long): Int

    @Query("DELETE FROM notes WHERE id = :noteId")
    suspend fun deleteNoteRow(noteId: Long): Int

    @Query("UPDATE memos SET chapterId = NULL WHERE chapterId = :chapterId")
    suspend fun releaseChapterEpisodes(chapterId: Long): Int

    @Query(
        "UPDATE memos SET noteId = NULL, chapterId = NULL, episodeOrder = 0 " +
            "WHERE noteId = :noteId",
    )
    suspend fun releaseNoteEpisodes(noteId: Long): Int

    /** Removing a heading keeps what was under it; those episodes come back to the front. */
    @Transaction
    suspend fun deleteChapter(chapterId: Long): Int {
        releaseChapterEpisodes(chapterId)
        return deleteChapterRow(chapterId)
    }

    /** Deleting a note releases its episodes. They were memos before it and they stay memos. */
    @Transaction
    suspend fun delete(noteId: Long): Int {
        releaseNoteEpisodes(noteId)
        return deleteNoteRow(noteId)
    }

    /** Several notes deleted as one: the same [delete] for each, all of them or none (the list's selection, 2026-09-28). */
    @Transaction
    suspend fun deleteAll(noteIds: Collection<Long>): Int = noteIds.sumOf { delete(it) }

    /** Where a chapter goes to stand before all the others. */
    @Query("SELECT COALESCE(MIN(sortOrder), 0) - 1 FROM note_chapters WHERE noteId = :noteId")
    suspend fun firstChapterOrder(noteId: Long): Int

    /** Renumbers the chapters into the order given, the way episodes are renumbered. */
    /**
     * Writes a whole arrangement at once: where the headings stand, and where each episode ended
     * up. One transaction, because half of this read back is a note nobody wrote.
     */
    @Transaction
    suspend fun arrange(
        noteId: Long,
        orderedChapterIds: List<Long>,
        episodes: List<Pair<Long, Long?>>,
    ) {
        orderedChapterIds.forEachIndexed { index, id -> updateChapterOrder(id, index) }
        episodes.forEachIndexed { index, (memoId, chapterId) ->
            placeEpisode(memoId, noteId, chapterId, index)
        }
    }

    @Transaction
    suspend fun reorderChapters(orderedChapterIds: List<Long>) {
        orderedChapterIds.forEachIndexed { index, id -> updateChapterOrder(id, index) }
    }

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM note_chapters WHERE noteId = :noteId")
    suspend fun nextChapterOrder(noteId: Long): Int

    @Query("SELECT COALESCE(MAX(episodeOrder), -1) + 1 FROM memos WHERE noteId = :noteId")
    suspend fun nextEpisodeOrder(noteId: Long): Int

    @Query(
        """
        UPDATE memos SET noteId = :noteId, chapterId = :chapterId, episodeOrder = :episodeOrder
        WHERE id = :memoId
        """,
    )
    suspend fun placeEpisode(memoId: Long, noteId: Long?, chapterId: Long?, episodeOrder: Int): Int

    @Transaction
    suspend fun reorderEpisodes(orderedMemoIds: List<Long>) {
        orderedMemoIds.forEachIndexed { index, memoId -> updateEpisodeOrder(memoId, index) }
    }

    @Query("UPDATE memos SET episodeOrder = :episodeOrder WHERE id = :memoId")
    suspend fun updateEpisodeOrder(memoId: Long, episodeOrder: Int): Int

    @Query("UPDATE memos SET chapterId = :chapterId WHERE id = :memoId")
    suspend fun updateEpisodeChapter(memoId: Long, chapterId: Long?): Int

    @Query("SELECT * FROM note_chapters ORDER BY id ASC")
    suspend fun allChapters(): List<NoteChapterEntity>
}

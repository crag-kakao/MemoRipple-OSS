package io.github.cragcoffee.memoripple.data

import io.github.cragcoffee.memoripple.domain.diary.SystemTimeProvider
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import io.github.cragcoffee.memoripple.domain.notes.NoteCoverColor
import io.github.cragcoffee.memoripple.domain.notes.NoteCoverPaint
import kotlinx.coroutines.flow.Flow

/**
 * Notes, and which memos are episodes of them.
 *
 * A note owns nothing: every episode is an ordinary memo that also happens to have a place in a
 * note. Releasing one, or deleting the note, leaves the writing exactly where it was.
 */
class NoteRepository(
    private val noteDao: NoteDao,
    private val timeProvider: TimeProvider = SystemTimeProvider(),
) {
    fun observeSummaries(): Flow<List<NoteSummary>> = noteDao.observeSummaries()

    fun observeNote(noteId: Long): Flow<NoteEntity?> = noteDao.observeNote(noteId)

    fun observeChapters(noteId: Long): Flow<List<NoteChapterEntity>> = noteDao.observeChapters(noteId)

    fun observeEpisodes(noteId: Long): Flow<List<MemoEntity>> = noteDao.observeEpisodes(noteId)

    suspend fun findById(noteId: Long): NoteEntity? = noteDao.findById(noteId)

    suspend fun create(title: String, subtitle: String = ""): NoteEntity {
        val now = timeProvider.nowMillis()
        val clean = title.trim().ifBlank { "無題のノート" }
        val note = NoteEntity(
            title = clean,
            subtitle = subtitle.trim(),
            coverColor = NoteCoverColor.suggestedFor(clean).storageId,
            createdAt = now,
            updatedAt = now,
        )
        return note.copy(id = noteDao.insert(note))
    }

    /** 並べた順: the notes in the order the hand left them; only their places change. */
    suspend fun reorder(orderedIds: List<Long>) = noteDao.reorderNotes(orderedIds)

    suspend fun rename(noteId: Long, title: String): Boolean =
        noteDao.rename(noteId, title.trim().ifBlank { "無題のノート" }, timeProvider.nowMillis()) == 1

    suspend fun setSubtitle(noteId: Long, subtitle: String): Boolean =
        noteDao.setSubtitle(noteId, subtitle.trim(), timeProvider.nowMillis()) == 1

    suspend fun setCoverColor(noteId: Long, color: NoteCoverColor): Boolean =
        setCoverPaint(noteId, NoteCoverPaint.Preset(color))

    suspend fun setCoverPaint(noteId: Long, paint: NoteCoverPaint): Boolean =
        noteDao.setCoverColor(noteId, paint.storageId, timeProvider.nowMillis()) == 1

    /** Deleting a note releases its episodes. They were memos before it and they stay memos. */
    suspend fun delete(noteId: Long): Boolean = noteDao.delete(noteId) == 1

    /** The same deletion for several notes, in one transaction; returns how many were deleted. */
    suspend fun deleteAll(noteIds: Collection<Long>): Int = noteDao.deleteAll(noteIds)

    suspend fun addChapter(noteId: Long, title: String, atStart: Boolean = false): NoteChapterEntity {
        val chapter = NoteChapterEntity(
            noteId = noteId,
            title = title.trim().ifBlank { "無題の章" },
            sortOrder = if (atStart) {
                noteDao.firstChapterOrder(noteId)
            } else {
                noteDao.nextChapterOrder(noteId)
            },
        )
        return chapter.copy(id = noteDao.insertChapter(chapter))
    }

    suspend fun renameChapter(chapterId: Long, title: String): Boolean =
        noteDao.renameChapter(chapterId, title.trim().ifBlank { "無題の章" }) == 1

    /** Removing a heading does not remove what was under it; those episodes come back to the front. */
    suspend fun deleteChapter(chapterId: Long): Boolean = noteDao.deleteChapter(chapterId) == 1

    /** Where every heading stands and every episode fell, written in one go. */
    suspend fun arrange(
        noteId: Long,
        placement: io.github.cragcoffee.memoripple.domain.notes.NoteArrangement.Placement,
    ) = noteDao.arrange(noteId, placement.chapterIds, placement.episodes)

    /** One transaction, so a note is never read with two chapters claiming the same place. */
    suspend fun reorderChapters(orderedChapterIds: List<Long>) =
        noteDao.reorderChapters(orderedChapterIds)

    /** Puts an existing memo at the end of a note. */
    suspend fun addEpisode(noteId: Long, memoId: Long, chapterId: Long? = null): Boolean =
        noteDao.placeEpisode(memoId, noteId, chapterId, noteDao.nextEpisodeOrder(noteId)) == 1

    suspend fun releaseEpisode(memoId: Long): Boolean =
        noteDao.placeEpisode(memoId, null, null, 0) == 1

    suspend fun moveEpisodeToChapter(memoId: Long, chapterId: Long?): Boolean =
        noteDao.updateEpisodeChapter(memoId, chapterId) == 1

    suspend fun reorderEpisodes(orderedMemoIds: List<Long>) =
        noteDao.reorderEpisodes(orderedMemoIds)

    suspend fun episodes(noteId: Long): List<MemoEntity> = noteDao.episodes(noteId)

    suspend fun chapters(noteId: Long): List<NoteChapterEntity> = noteDao.chapters(noteId)
}

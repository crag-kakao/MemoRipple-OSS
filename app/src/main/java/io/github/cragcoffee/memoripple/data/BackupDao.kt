package io.github.cragcoffee.memoripple.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

data class RoomBackupSnapshot(
    val memos: List<MemoEntity>,
    val memoComments: List<MemoCommentEntity>,
    val diaryEntries: List<DiaryEntryEntity>,
    val futureDiaryComments: List<FutureDiaryCommentEntity>,
    val tags: List<TagEntity> = emptyList(),
    val memoTagRelations: List<MemoTagCrossRef> = emptyList(),
    val attachmentBlobs: List<AttachmentBlobEntity> = emptyList(),
    val memoPhotoAttachments: List<MemoPhotoAttachmentEntity> = emptyList(),
    val diaryPhotoAttachments: List<DiaryPhotoAttachmentEntity> = emptyList(),
    val notes: List<NoteEntity> = emptyList(),
    val noteChapters: List<NoteChapterEntity> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    val memoContentBlocks: List<MemoContentBlockEntity> = emptyList(),
    val diaryContentBlocks: List<DiaryContentBlockEntity> = emptyList(),
    val outlineRows: List<OutlineRowEntity> = emptyList(),
)

@Dao
interface BackupDao {
    @Query("SELECT * FROM memos ORDER BY id ASC")
    suspend fun readMemos(): List<MemoEntity>

    @Query(
        "SELECT * FROM memo_comments " +
            "ORDER BY memoId ASC, playbackOrder ASC, id ASC",
    )
    suspend fun readMemoComments(): List<MemoCommentEntity>

    @Query("SELECT * FROM diary_entries ORDER BY diaryDateEpochDay ASC, id ASC")
    suspend fun readDiaryEntries(): List<DiaryEntryEntity>

    @Query(
        "SELECT * FROM future_diary_comments " +
            "ORDER BY diaryEntryId ASC, revealAt ASC, id ASC",
    )
    suspend fun readFutureDiaryComments(): List<FutureDiaryCommentEntity>

    @Query("SELECT * FROM tags ORDER BY id ASC")
    suspend fun readTags(): List<TagEntity>

    @Query("SELECT * FROM memo_tag_cross_refs ORDER BY memoId ASC, tagId ASC")
    suspend fun readMemoTagRelations(): List<MemoTagCrossRef>

    @Query("SELECT * FROM attachment_blobs ORDER BY sha256 ASC")
    suspend fun readAttachmentBlobs(): List<AttachmentBlobEntity>

    @Query("SELECT * FROM memo_photo_attachments ORDER BY memoId ASC, sortOrder ASC, id ASC")
    suspend fun readMemoPhotoAttachments(): List<MemoPhotoAttachmentEntity>

    @Query("SELECT * FROM memo_content_blocks ORDER BY memoId ASC, position ASC, id ASC")
    suspend fun readMemoContentBlocks(): List<MemoContentBlockEntity>

    @Query("DELETE FROM memo_content_blocks")
    suspend fun deleteMemoContentBlocks()

    @Query("SELECT * FROM diary_content_blocks ORDER BY diaryEntryId ASC, position ASC, id ASC")
    suspend fun readDiaryContentBlocks(): List<DiaryContentBlockEntity>

    @Query("DELETE FROM diary_content_blocks")
    suspend fun deleteDiaryContentBlocks()

    @Query("SELECT * FROM outline_rows ORDER BY memoId ASC, position ASC, rowId ASC")
    suspend fun readOutlineRows(): List<OutlineRowEntity>

    @Query("DELETE FROM outline_rows")
    suspend fun deleteOutlineRows()

    @Insert
    suspend fun insertOutlineRows(values: List<OutlineRowEntity>)

    @Insert
    suspend fun insertDiaryContentBlocks(values: List<DiaryContentBlockEntity>)

    @Insert
    suspend fun insertMemoContentBlocks(values: List<MemoContentBlockEntity>)

    @Query(
        "SELECT * FROM diary_photo_attachments " +
            "ORDER BY diaryEntryId ASC, sortOrder ASC, id ASC",
    )
    suspend fun readDiaryPhotoAttachments(): List<DiaryPhotoAttachmentEntity>

    @Query("SELECT * FROM notes ORDER BY id ASC")
    suspend fun readNotes(): List<NoteEntity>

    @Query("SELECT * FROM note_chapters ORDER BY noteId ASC, sortOrder ASC, id ASC")
    suspend fun readNoteChapters(): List<NoteChapterEntity>

    @Query("SELECT * FROM folders ORDER BY id ASC")
    suspend fun readFolders(): List<FolderEntity>

    @Query("DELETE FROM folders")
    suspend fun deleteFolders()

    @Insert
    suspend fun insertFolders(values: List<FolderEntity>)

    @Query("DELETE FROM note_chapters")
    suspend fun deleteNoteChapters()

    @Query("DELETE FROM notes")
    suspend fun deleteNotes()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNotes(values: List<NoteEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNoteChapters(values: List<NoteChapterEntity>)

    @Query("DELETE FROM memo_photo_attachments")
    suspend fun deleteMemoPhotoAttachments()

    @Query("DELETE FROM diary_photo_attachments")
    suspend fun deleteDiaryPhotoAttachments()

    @Query("DELETE FROM attachment_blobs")
    suspend fun deleteAttachmentBlobs()

    @Query("DELETE FROM memo_tag_cross_refs")
    suspend fun deleteMemoTagRelations()

    @Query("DELETE FROM tags")
    suspend fun deleteTags()

    @Query("DELETE FROM future_diary_comments")
    suspend fun deleteFutureDiaryComments()

    @Query("DELETE FROM diary_entries")
    suspend fun deleteDiaryEntries()

    @Query("DELETE FROM memo_comments")
    suspend fun deleteMemoComments()

    @Query("DELETE FROM memos")
    suspend fun deleteMemos()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMemos(values: List<MemoEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMemoComments(values: List<MemoCommentEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDiaryEntries(values: List<DiaryEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFutureDiaryComments(values: List<FutureDiaryCommentEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTags(values: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMemoTagRelations(values: List<MemoTagCrossRef>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttachmentBlobs(values: List<AttachmentBlobEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMemoPhotoAttachments(values: List<MemoPhotoAttachmentEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDiaryPhotoAttachments(values: List<DiaryPhotoAttachmentEntity>)

    @Transaction
    suspend fun snapshot(): RoomBackupSnapshot = RoomBackupSnapshot(
        memos = readMemos(),
        memoComments = readMemoComments(),
        diaryEntries = readDiaryEntries(),
        futureDiaryComments = readFutureDiaryComments(),
        tags = readTags(),
        memoTagRelations = readMemoTagRelations(),
        attachmentBlobs = readAttachmentBlobs(),
        memoPhotoAttachments = readMemoPhotoAttachments(),
        diaryPhotoAttachments = readDiaryPhotoAttachments(),
        notes = readNotes(),
        noteChapters = readNoteChapters(),
        folders = readFolders(),
        memoContentBlocks = readMemoContentBlocks(),
        diaryContentBlocks = readDiaryContentBlocks(),
        outlineRows = readOutlineRows(),
    )

    suspend fun replaceAll(snapshot: RoomBackupSnapshot) {
        deleteMemoContentBlocks()
        deleteDiaryContentBlocks()
        deleteOutlineRows()
        deleteMemoTagRelations()
        deleteMemoPhotoAttachments()
        deleteDiaryPhotoAttachments()
        deleteFutureDiaryComments()
        deleteDiaryEntries()
        deleteMemoComments()
        deleteTags()
        deleteMemos()
        deleteNoteChapters()
        deleteNotes()
        deleteAttachmentBlobs()
        deleteFolders()

        // Folders before memos: a memo names its folder, so the folder must already be there.
        if (snapshot.folders.isNotEmpty()) insertFolders(snapshot.folders)
        if (snapshot.notes.isNotEmpty()) insertNotes(snapshot.notes)
        if (snapshot.noteChapters.isNotEmpty()) insertNoteChapters(snapshot.noteChapters)
        if (snapshot.memos.isNotEmpty()) insertMemos(snapshot.memos)
        if (snapshot.tags.isNotEmpty()) insertTags(snapshot.tags)
        if (snapshot.attachmentBlobs.isNotEmpty()) insertAttachmentBlobs(snapshot.attachmentBlobs)
        if (snapshot.memoComments.isNotEmpty()) insertMemoComments(snapshot.memoComments)
        if (snapshot.diaryEntries.isNotEmpty()) insertDiaryEntries(snapshot.diaryEntries)
        if (snapshot.futureDiaryComments.isNotEmpty()) {
            insertFutureDiaryComments(snapshot.futureDiaryComments)
        }
        if (snapshot.memoTagRelations.isNotEmpty()) {
            insertMemoTagRelations(snapshot.memoTagRelations)
        }
        if (snapshot.memoPhotoAttachments.isNotEmpty()) {
            insertMemoPhotoAttachments(snapshot.memoPhotoAttachments)
        }
        if (snapshot.diaryPhotoAttachments.isNotEmpty()) {
            insertDiaryPhotoAttachments(snapshot.diaryPhotoAttachments)
        }
        // After the memos and their photo rows: a block names both.
        if (snapshot.memoContentBlocks.isNotEmpty()) insertMemoContentBlocks(snapshot.memoContentBlocks)
        // After the entries and their photo rows, the same way.
        if (snapshot.diaryContentBlocks.isNotEmpty()) insertDiaryContentBlocks(snapshot.diaryContentBlocks)
        // After the memos: a row names its outline.
        if (snapshot.outlineRows.isNotEmpty()) insertOutlineRows(snapshot.outlineRows)
    }
}

package io.github.cragcoffee.memoripple.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupNoteValidationTest {

    private fun issues(document: MemoRippleBackupDto): Set<BackupValidationIssue> =
        when (val result = BackupValidator().validate(document)) {
            BackupValidationResult.Valid -> emptySet()
            is BackupValidationResult.Invalid -> result.issues
        }

    @Test
    fun aNoteAndItsChaptersAndEpisodesRoundTripAsWritten() {
        val document = fullBackupFixture()

        assertTrue(issues(document).isEmpty())
        val restored = BackupMapper.toRoomSnapshot(document)
        assertEquals(listOf(60L), restored.notes.map { it.id })
        assertEquals("夜明け前に君と", restored.notes.single().title)
        assertEquals(listOf(70L), restored.noteChapters.map { it.id })
        assertEquals(
            listOf(60L, 60L),
            restored.memos.filter { it.noteId != null }.map { requireNotNull(it.noteId) },
        )
        assertEquals(listOf(0, 1), restored.memos.filter { it.noteId != null }.map { it.episodeOrder })
    }

    @Test
    fun aCoverPointingAtABlobTheFileDoesNotCarryIsRefused() {
        val document = fullBackupFixture().let { doc ->
            doc.copy(
                payload = doc.payload.copy(
                    notes = doc.payload.notes.map {
                        it.copy(coverBlobSha256 = "a".repeat(64))
                    },
                ),
            )
        }

        assertTrue(BackupValidationIssue.ORPHAN_PHOTO_ATTACHMENT in issues(document))
    }

    @Test
    fun aCoverShaThatIsNoShaAtAllIsRefused() {
        val document = fullBackupFixture().let { doc ->
            doc.copy(
                payload = doc.payload.copy(
                    notes = doc.payload.notes.map {
                        it.copy(coverBlobSha256 = "not-a-sha")
                    },
                ),
            )
        }

        assertTrue(BackupValidationIssue.ORPHAN_PHOTO_ATTACHMENT in issues(document))
    }

    @Test
    fun aChapterPointingAtANoteThatIsNotInTheFileIsRefused() {
        val document = fullBackupFixture().let { doc ->
            doc.copy(
                payload = doc.payload.copy(
                    noteChapters = listOf(NoteChapterBackupDto(70, 999, "宙に浮いた章", 0)),
                ),
            )
        }

        assertTrue(BackupValidationIssue.ORPHAN_NOTE_CHAPTER in issues(document))
    }

    @Test
    fun anEpisodePointingAtANoteThatIsNotInTheFileIsRefused() {
        val document = fullBackupFixture().let { doc ->
            doc.copy(
                payload = doc.payload.copy(
                    memos = doc.payload.memos.map {
                        if (it.id == 21L) it.copy(noteId = 999) else it
                    },
                ),
            )
        }

        assertTrue(BackupValidationIssue.ORPHAN_EPISODE in issues(document))
    }

    @Test
    fun twoNotesSharingAnIdAreRefused() {
        val document = fullBackupFixture().let { doc ->
            doc.copy(
                payload = doc.payload.copy(
                    notes = doc.payload.notes + doc.payload.notes,
                ),
            )
        }

        assertTrue(BackupValidationIssue.DUPLICATE_NOTE_ID in issues(document))
    }

    @Test
    fun aFileFromBeforeNotesExistedIsStillGood() {
        val document = fullBackupFixture().let { doc ->
            doc.copy(
                formatVersion = 10,
                payload = doc.payload.copy(
                    notes = emptyList(),
                    noteChapters = emptyList(),
                    memos = doc.payload.memos.map {
                        it.copy(noteId = null, chapterId = null, episodeOrder = 0)
                    },
                ),
            )
        }

        assertTrue(issues(document).isEmpty())
        // Nothing said which memos belonged together, so none of them do.
        assertTrue(BackupMapper.toRoomSnapshot(document).notes.isEmpty())
    }
}

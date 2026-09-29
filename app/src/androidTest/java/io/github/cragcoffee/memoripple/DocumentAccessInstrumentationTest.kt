package io.github.cragcoffee.memoripple

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.FutureDiaryCommentEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.documents.RepositoryDocumentAccess
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.documents.DocumentCreate
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentMetadata
import io.github.cragcoffee.memoripple.domain.documents.DocumentQuery
import io.github.cragcoffee.memoripple.domain.documents.DocumentReadResult
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSearchScope
import io.github.cragcoffee.memoripple.domain.documents.DocumentWriteResult
import io.github.cragcoffee.memoripple.domain.outline.OutlineNode
import io.github.cragcoffee.memoripple.domain.outline.OutlineText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Document boundary over the real repositories and Room (docs/DOCUMENT_BOUNDARY_AUDIT.md):
 * get / create / append for memo, outline and journal; NOT_FOUND, READ_ONLY and CONFLICT; the v1
 * search scope; and the future comment staying outside.
 */
@RunWith(AndroidJUnit4::class)
class DocumentAccessInstrumentationTest {
    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MemoRippleApplication

    private val access by lazy {
        RepositoryDocumentAccess(
            application.database, application.memoRepository, application.diaryRepository,
            application.tagRepository, application.timeProvider,
        )
    }

    @Before
    fun startEmpty() {
        runBlocking { application.database.clearAllTables() }
    }

    private fun memo(title: String, body: String, kind: String = "memo", folderId: Long? = null, noteId: Long? = null, trashedAt: Long? = null): Long = runBlocking {
        application.database.memoDao().insert(
            MemoEntity(title = title, body = body, createdAt = 1_000, updatedAt = 2_000, kind = kind, folderId = folderId, noteId = noteId, trashedAt = trashedAt),
        )
    }

    private fun journal(body: String, state: DiaryState = DiaryState.DRAFT, epochDay: Long = application.timeProvider.currentLocalDate().toEpochDay()): Long = runBlocking {
        application.database.diaryDao().insert(
            DiaryEntryEntity(diaryDateEpochDay = epochDay, body = body, state = state, createdAt = 1_000, updatedAt = 2_000, lockedAt = if (state == DiaryState.LOCKED) 3_000 else null),
        )
    }

    private fun found(ref: DocumentRef) = (runBlocking { access.get(ref) } as DocumentReadResult.Found).content

    @Test
    fun getReturnsEachKindAsADomainDocumentAndNotFoundForTheRest() {
        val memoId = memo("題", "本文\n二行目", folderId = null)
        val outlineId = memo("", "- 一\n  - 二", kind = "outline")
        val journalId = journal("朝の記録\n続き")

        val memo = found(DocumentRef(DocumentKind.MEMO, memoId))
        assertEquals("題", memo.title)
        assertEquals("本文\n二行目", memo.body)
        assertEquals(2_000L, memo.summary.updatedAt)
        assertTrue(memo.metadata is DocumentMetadata.Memo)

        val outline = found(DocumentRef(DocumentKind.OUTLINE, outlineId))
        assertEquals("- 一", outline.title)
        assertTrue(outline.metadata is DocumentMetadata.Outline)

        val entry = found(DocumentRef(DocumentKind.JOURNAL, journalId))
        assertEquals("朝の記録", entry.title)
        assertEquals("朝の記録\n続き", entry.body)
        val meta = entry.metadata as DocumentMetadata.Journal
        assertEquals(application.timeProvider.currentLocalDate(), meta.date)
        assertTrue(meta.editable)

        // A ref names one thing: the wrong kind for a row is not found, and so is a missing id.
        assertEquals(DocumentReadResult.NotFound, runBlocking { access.get(DocumentRef(DocumentKind.OUTLINE, memoId)) })
        assertEquals(DocumentReadResult.NotFound, runBlocking { access.get(DocumentRef(DocumentKind.MEMO, outlineId)) })
        assertEquals(DocumentReadResult.NotFound, runBlocking { access.get(DocumentRef(DocumentKind.JOURNAL, memoId + outlineId + journalId + 100)) })
        assertEquals(DocumentReadResult.NotFound, runBlocking { access.get(DocumentRef(DocumentKind.MEMO, 999_999)) })
    }

    @Test
    fun createDelegatesToEachKindsOwnRepositoryCall() {
        val folder = runBlocking { application.database.folderDao().insert(io.github.cragcoffee.memoripple.data.FolderEntity(name = "開発", parentFolderId = null, createdAt = 1, updatedAt = 1)) }
        val memo = runBlocking { access.create(DocumentCreate.Memo(folderId = folder)) } as DocumentWriteResult.Done
        val outline = runBlocking { access.create(DocumentCreate.Outline(folderId = folder)) } as DocumentWriteResult.Done
        val today = application.timeProvider.currentLocalDate()
        val entry = runBlocking { access.create(DocumentCreate.Journal(today)) } as DocumentWriteResult.Done

        val memoRow = runBlocking { application.database.memoDao().findById(memo.ref.id) }!!
        assertEquals("memo", memoRow.kind); assertEquals(folder, memoRow.folderId); assertEquals(DocumentKind.MEMO, memo.ref.kind)
        val outlineRow = runBlocking { application.database.memoDao().findById(outline.ref.id) }!!
        assertEquals("outline", outlineRow.kind); assertEquals(folder, outlineRow.folderId); assertEquals(DocumentKind.OUTLINE, outline.ref.kind)
        val entryRow = runBlocking { application.database.diaryDao().findById(entry.ref.id) }!!
        assertEquals(today.toEpochDay(), entryRow.diaryDateEpochDay); assertEquals(DiaryState.DRAFT, entryRow.state); assertEquals(DocumentKind.JOURNAL, entry.ref.kind)
        assertEquals(memoRow.updatedAt, memo.updatedAt)

        // Tomorrow's journal is not written today.
        assertTrue(runBlocking { access.create(DocumentCreate.Journal(today.plusDays(1))) } is DocumentWriteResult.Rejected)
    }

    @Test
    fun appendAddsAtTheEndOnlyAndMovesUpdatedAt() {
        val memoId = memo("題", "一行目")
        val before = found(DocumentRef(DocumentKind.MEMO, memoId))
        val done = runBlocking { access.append(before.ref, "追記", before.summary.updatedAt) } as DocumentWriteResult.Done
        val after = found(before.ref)
        assertEquals("一行目\n追記", after.body)
        assertTrue(after.summary.updatedAt > before.summary.updatedAt)
        assertEquals(after.summary.updatedAt, done.updatedAt)

        // An empty memo gets the text as its first line, not a leading blank line.
        val emptyId = memo("", "")
        val empty = found(DocumentRef(DocumentKind.MEMO, emptyId))
        runBlocking { access.append(empty.ref, "最初", empty.summary.updatedAt) }
        assertEquals("最初", found(empty.ref).body)
    }

    @Test
    fun appendToAnOutlineAddsExactlyOneRootNodeAtTheEnd() {
        val outlineId = memo("", "- 一\n  - 二", kind = "outline")
        val before = found(DocumentRef(DocumentKind.OUTLINE, outlineId))
        runBlocking { access.append(before.ref, "三\n四", before.summary.updatedAt) } as DocumentWriteResult.Done
        val after = found(before.ref)
        val entries = OutlineText.parse(after.body).entries
        assertEquals(3, entries.size)
        val last = entries.last() as OutlineNode
        assertEquals(0, last.depth)
        assertEquals("三 四", last.text)
        assertTrue(after.body.startsWith("- 一\n  - 二"))
    }

    @Test
    fun appendToAJournalWorksUnlessItIsLocked() {
        val openId = journal("朝")
        val open = found(DocumentRef(DocumentKind.JOURNAL, openId))
        runBlocking { access.append(open.ref, "夜", open.summary.updatedAt) } as DocumentWriteResult.Done
        assertEquals("朝\n夜", found(open.ref).body)

        val lockedId = journal("鍵", state = DiaryState.LOCKED, epochDay = application.timeProvider.currentLocalDate().minusDays(30).toEpochDay())
        val locked = found(DocumentRef(DocumentKind.JOURNAL, lockedId))
        assertEquals(DocumentWriteResult.ReadOnly, runBlocking { access.append(locked.ref, "追記", locked.summary.updatedAt) })
        assertEquals("鍵", found(locked.ref).body)
        assertEquals(false, (locked.metadata as DocumentMetadata.Journal).editable)
    }

    @Test
    fun aStaleExpectedUpdatedAtIsAConflictAndWritesNothing() {
        val memoId = memo("題", "本文")
        val ref = DocumentRef(DocumentKind.MEMO, memoId)
        val stale = found(ref).summary.updatedAt
        // Someone else saves in between (the editor's path).
        runBlocking { application.memoRepository.save(application.database.memoDao().findById(memoId), "題", "本文（別の端で更新）", stale + 5_000) }
        assertEquals(DocumentWriteResult.Conflict, runBlocking { access.append(ref, "追記", stale) })
        assertEquals("本文（別の端で更新）", found(ref).body)
        // With the current value the write goes through.
        val current = found(ref).summary.updatedAt
        assertTrue(runBlocking { access.append(ref, "追記", current) } is DocumentWriteResult.Done)
        assertEquals("本文（別の端で更新）\n追記", found(ref).body)

        val journalId = journal("朝")
        val jref = DocumentRef(DocumentKind.JOURNAL, journalId)
        assertEquals(DocumentWriteResult.Conflict, runBlocking { access.append(jref, "夜", found(jref).summary.updatedAt - 1) })
        assertEquals("朝", found(jref).body)
        assertEquals(DocumentWriteResult.NotFound, runBlocking { access.append(DocumentRef(DocumentKind.MEMO, 424242), "x", 0) })
    }

    @Test
    fun theFutureCommentStaysOutsideTheDocument() {
        val journalId = journal("今日の日記")
        runBlocking {
            val now = application.timeProvider.nowMillis()
            application.database.futureDiaryCommentDao().insert(
                FutureDiaryCommentEntity(diaryEntryId = journalId, text = "封をした未来の本文", sealedAt = now, revealAt = now + 86_400_000),
            )
        }
        val entry = found(DocumentRef(DocumentKind.JOURNAL, journalId))
        assertTrue(!entry.body.contains("封をした未来の本文"))
        assertTrue(entry.metadata.toString().contains("封をした未来の本文").not())
        // No kind names it: JOURNAL is the entry alone.
        assertEquals(3, DocumentKind.entries.size)
    }

    @Test
    fun searchReachesStandaloneMemosOutlinesAndJournalsButNotEpisodesOrTrash() {
        val folder = runBlocking { application.database.folderDao().insert(io.github.cragcoffee.memoripple.data.FolderEntity(name = "F", parentFolderId = null, createdAt = 1, updatedAt = 1)) }
        val hit = memo("計画", "会議の予定")
        val filed = memo("計画B", "会議の記録", folderId = folder)
        val outline = memo("", "- 会議\n  - 議題", kind = "outline")
        val episode = memo("会議のエピソード", "本文", noteId = 1)
        val trashed = memo("会議（削除）", "本文", trashedAt = 5)
        val entry = journal("会議の日記")

        assertEquals(DocumentSearchScope.V1, access.searchableKinds)
        val all = runBlocking { access.search(DocumentQuery("会議")) }.map { it.ref }
        assertEquals(setOf(DocumentRef(DocumentKind.MEMO, hit), DocumentRef(DocumentKind.MEMO, filed), DocumentRef(DocumentKind.OUTLINE, outline), DocumentRef(DocumentKind.JOURNAL, entry)), all.toSet())
        assertTrue(all.none { (it.id == episode || it.id == trashed) && it.kind == DocumentKind.MEMO })
        // Journals alone, when asked for alone — the scope is written into the type.
        assertEquals(listOf(DocumentRef(DocumentKind.JOURNAL, entry)), runBlocking { access.search(DocumentQuery("会議", kinds = setOf(DocumentKind.JOURNAL))) }.map { it.ref })
        // Folder narrows; syntax is MemoSearch's.
        assertEquals(listOf(DocumentRef(DocumentKind.MEMO, filed)), runBlocking { access.search(DocumentQuery("会議", folderId = folder)) }.map { it.ref })
        assertEquals(listOf(DocumentRef(DocumentKind.MEMO, hit)), runBlocking { access.search(DocumentQuery("会議 -記録", kinds = setOf(DocumentKind.MEMO))) }.map { it.ref })
    }
}

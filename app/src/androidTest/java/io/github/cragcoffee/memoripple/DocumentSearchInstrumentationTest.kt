package io.github.cragcoffee.memoripple

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cragcoffee.memoripple.data.DiaryEntryEntity
import io.github.cragcoffee.memoripple.data.FolderEntity
import io.github.cragcoffee.memoripple.data.FutureDiaryCommentEntity
import io.github.cragcoffee.memoripple.data.MemoEntity
import io.github.cragcoffee.memoripple.data.documents.RepositoryDocumentAccess
import io.github.cragcoffee.memoripple.domain.diary.DiaryState
import io.github.cragcoffee.memoripple.domain.documents.DocumentDateRange
import io.github.cragcoffee.memoripple.domain.documents.DocumentDestination
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentQuery
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSearchScope
import io.github.cragcoffee.memoripple.domain.documents.destination
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Search across the three kinds (docs/JOURNAL_SEARCH_AUDIT.md): words over memos, outlines and
 * journals; a date range over a journal's day and a memo's made-or-written day; kinds and folder
 * filters; one order; what stays out (episodes, archive, trash, future comments); the empty-query rule.
 */
@RunWith(AndroidJUnit4::class)
class DocumentSearchInstrumentationTest {
    private val application: MemoRippleApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MemoRippleApplication

    private val access by lazy {
        RepositoryDocumentAccess(
            application.database, application.memoRepository, application.diaryRepository,
            application.tagRepository, application.timeProvider,
        )
    }

    private val today: LocalDate get() = application.timeProvider.currentLocalDate()

    @Before
    fun startEmpty() {
        runBlocking { application.database.clearAllTables() }
    }

    private fun at(date: LocalDate, hour: Int, minute: Int): Long =
        application.timeProvider.toEpochMillis(LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)))

    private fun memo(title: String, body: String, kind: String = "memo", folderId: Long? = null, noteId: Long? = null, createdAt: Long = 1_000, updatedAt: Long = createdAt, archivedAt: Long? = null, trashedAt: Long? = null): Long = runBlocking {
        application.database.memoDao().insert(
            MemoEntity(title = title, body = body, createdAt = createdAt, updatedAt = updatedAt, kind = kind, folderId = folderId, noteId = noteId, archivedAt = archivedAt, trashedAt = trashedAt),
        )
    }

    private fun journal(body: String, date: LocalDate, updatedAt: Long = 1_000, state: DiaryState = DiaryState.DRAFT): Long = runBlocking {
        application.database.diaryDao().insert(
            DiaryEntryEntity(diaryDateEpochDay = date.toEpochDay(), body = body, state = state, createdAt = updatedAt, updatedAt = updatedAt, lockedAt = if (state == DiaryState.LOCKED) updatedAt else null),
        )
    }

    private fun search(query: DocumentQuery) = runBlocking { access.search(query) }
    private fun refs(query: DocumentQuery) = search(query).map { it.ref }

    @Test
    fun wordsReachMemosOutlinesAndJournalsAndKindsNarrowThem() {
        val memo = memo("会議のメモ", "本文", updatedAt = 3_000)
        val outline = memo("", "- 会議\n  - 議題", kind = "outline", updatedAt = 2_000)
        val entry = journal("会議の日記\n続き", today, updatedAt = 1_000)
        memo("関係ない", "別の話")
        journal("別の日記", today)

        assertEquals(setOf(DocumentKind.MEMO, DocumentKind.OUTLINE, DocumentKind.JOURNAL), access.searchableKinds)
        assertEquals(DocumentSearchScope.V1, access.searchableKinds)
        // No kinds given: all three, newest updated first.
        assertEquals(listOf(DocumentRef(DocumentKind.MEMO, memo), DocumentRef(DocumentKind.OUTLINE, outline), DocumentRef(DocumentKind.JOURNAL, entry)), refs(DocumentQuery("会議")))
        assertEquals(listOf(DocumentRef(DocumentKind.JOURNAL, entry)), refs(DocumentQuery("会議", kinds = setOf(DocumentKind.JOURNAL))))
        assertEquals(listOf(DocumentRef(DocumentKind.MEMO, memo)), refs(DocumentQuery("会議", kinds = setOf(DocumentKind.MEMO))))
        assertEquals(listOf(DocumentRef(DocumentKind.OUTLINE, outline)), refs(DocumentQuery("会議", kinds = setOf(DocumentKind.OUTLINE))))
        assertEquals(listOf(DocumentRef(DocumentKind.MEMO, memo), DocumentRef(DocumentKind.OUTLINE, outline)), refs(DocumentQuery("会議", kinds = setOf(DocumentKind.MEMO, DocumentKind.OUTLINE))))
        assertTrue(refs(DocumentQuery("存在しない言葉")).isEmpty())
        // The summary is the document: its ref opens it, its title is the shared rule's.
        val journalSummary = search(DocumentQuery("会議", kinds = setOf(DocumentKind.JOURNAL))).single()
        assertEquals("会議の日記", journalSummary.title)
        assertEquals(1_000L, journalSummary.updatedAt)
        assertEquals(DocumentDestination.Journal(entry), journalSummary.ref.destination())
        assertEquals(DocumentDestination.MemoEditor(memo), search(DocumentQuery("会議", kinds = setOf(DocumentKind.MEMO))).single().ref.destination())
        assertEquals(DocumentDestination.Outliner(outline), search(DocumentQuery("会議", kinds = setOf(DocumentKind.OUTLINE))).single().ref.destination())
    }

    @Test
    fun aJournalIsFoundByItsFirstLineItsBodyAndJapanesePartialWords() {
        val first = journal("朝の散歩\nよく晴れていた", today)
        val deep = journal("メモ\n夕方に長い散歩をした", today)
        journal("無関係", today)
        assertEquals(setOf(first, deep), refs(DocumentQuery("散歩", kinds = setOf(DocumentKind.JOURNAL))).map { it.id }.toSet())
        assertEquals(listOf(first), refs(DocumentQuery("朝の", kinds = setOf(DocumentKind.JOURNAL))).map { it.id })
        assertEquals(listOf(deep), refs(DocumentQuery("散歩 -晴れ", kinds = setOf(DocumentKind.JOURNAL))).map { it.id })
        // A locked entry is still read by a search.
        val locked = journal("鍵のかかった散歩", today.minusDays(40), state = DiaryState.LOCKED)
        assertTrue(refs(DocumentQuery("散歩", kinds = setOf(DocumentKind.JOURNAL))).any { it.id == locked })
    }

    @Test
    fun aDateRangeSelectsJournalsByTheirDayAndSeveralOnOneDayAllComeBack() {
        val d = LocalDate.of(2026, 9, 17)
        val morning = journal("朝", d, updatedAt = 1_000)
        val evening = journal("夜", d, updatedAt = 2_000)
        val dayBefore = journal("前日", d.minusDays(1), updatedAt = 3_000)
        val dayAfter = journal("翌日", d.plusDays(1), updatedAt = 4_000)

        assertEquals(listOf(evening, morning), refs(DocumentQuery(dateRange = DocumentDateRange.day(d), kinds = setOf(DocumentKind.JOURNAL))).map { it.id })
        assertEquals(listOf(dayAfter, dayBefore, evening, morning), refs(DocumentQuery(dateRange = DocumentDateRange.of(d.minusDays(1), d.plusDays(1)), kinds = setOf(DocumentKind.JOURNAL))).map { it.id })
        // Words and a range together.
        assertEquals(listOf(morning), refs(DocumentQuery("朝", dateRange = DocumentDateRange.day(d))).map { it.id })
        assertTrue(refs(DocumentQuery("朝", dateRange = DocumentDateRange.day(d.plusDays(5)))).isEmpty())
    }

    @Test
    fun monthAndYearBoundariesAndTheLocalDayOfAMemoAreRespected() {
        val dec31 = LocalDate.of(2025, 12, 31)
        val jan1 = LocalDate.of(2026, 1, 1)
        val old = journal("大晦日", dec31)
        val newYear = journal("元日", jan1)
        assertEquals(setOf(old, newYear), refs(DocumentQuery(dateRange = DocumentDateRange.of(dec31, jan1), kinds = setOf(DocumentKind.JOURNAL))).map { it.id }.toSet())
        assertEquals(listOf(newYear), refs(DocumentQuery(dateRange = DocumentDateRange.day(jan1), kinds = setOf(DocumentKind.JOURNAL))).map { it.id })
        assertEquals(listOf(old), refs(DocumentQuery(dateRange = DocumentDateRange.of(LocalDate.of(2025, 12, 1), dec31), kinds = setOf(DocumentKind.JOURNAL))).map { it.id })

        // A memo written at 23:30 belongs to that local day; one at 00:30 to the next.
        val late = memo("遅い", "本文", createdAt = at(dec31, 23, 30))
        val early = memo("早い", "本文", createdAt = at(jan1, 0, 30))
        assertEquals(listOf(late), refs(DocumentQuery(dateRange = DocumentDateRange.day(dec31), kinds = setOf(DocumentKind.MEMO))).map { it.id })
        assertEquals(listOf(early), refs(DocumentQuery(dateRange = DocumentDateRange.day(jan1), kinds = setOf(DocumentKind.MEMO))).map { it.id })
        // Written on another day: a memo made on Dec 31 and edited on Jan 1 is on both days.
        val edited = memo("編集", "本文", createdAt = at(dec31, 10, 0), updatedAt = at(jan1, 10, 0))
        assertTrue(refs(DocumentQuery(dateRange = DocumentDateRange.day(dec31), kinds = setOf(DocumentKind.MEMO))).any { it.id == edited })
        assertTrue(refs(DocumentQuery(dateRange = DocumentDateRange.day(jan1), kinds = setOf(DocumentKind.MEMO))).any { it.id == edited })
    }

    @Test
    fun aFolderFilterLeavesJournalsOutAndTheExcludedRowsStayOut() {
        val folder = runBlocking { application.database.folderDao().insert(FolderEntity(name = "F", parentFolderId = null, createdAt = 1, updatedAt = 1)) }
        val filed = memo("会議", "本文", folderId = folder)
        memo("会議", "本文")
        val entry = journal("会議", today)
        memo("会議のエピソード", "本文", noteId = 1)
        memo("会議（保管）", "本文", archivedAt = 5)
        memo("会議（削除）", "本文", trashedAt = 5)
        runBlocking {
            val now = application.timeProvider.nowMillis()
            application.database.futureDiaryCommentDao().insert(
                FutureDiaryCommentEntity(diaryEntryId = entry, text = "会議の未来コメント", sealedAt = now, revealAt = now + 86_400_000),
            )
        }
        assertEquals(listOf(DocumentRef(DocumentKind.MEMO, filed)), refs(DocumentQuery("会議", folderId = folder)))
        val all = refs(DocumentQuery("会議"))
        assertEquals(3, all.size) // filed memo, root memo, the journal
        assertTrue(all.contains(DocumentRef(DocumentKind.JOURNAL, entry)))
        assertTrue(refs(DocumentQuery("未来コメント")).isEmpty())
        assertTrue(refs(DocumentQuery("エピソード")).isEmpty())
        assertTrue(refs(DocumentQuery("保管")).isEmpty())
        assertTrue(refs(DocumentQuery("削除")).isEmpty())
    }

    @Test
    fun anUnboundedQueryFindsNothingButADateAloneIsASearch() {
        journal("今日", today)
        memo("今日のメモ", "本文", createdAt = at(today, 9, 0))
        assertTrue(refs(DocumentQuery("")).isEmpty())
        assertTrue(refs(DocumentQuery("   ", kinds = setOf(DocumentKind.JOURNAL))).isEmpty())
        val dated = refs(DocumentQuery(dateRange = DocumentDateRange.day(today)))
        assertEquals(setOf(DocumentKind.JOURNAL, DocumentKind.MEMO), dated.map { it.kind }.toSet())
    }
}

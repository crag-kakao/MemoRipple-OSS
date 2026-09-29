package io.github.cragcoffee.memoripple.domain.export

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableExportPlannerTest {

    private val zone = ZoneId.of("Asia/Tokyo")

    private fun memo(
        id: Long,
        title: String = "メモ$id",
        shelf: PortableShelf = PortableShelf.ACTIVE,
        updatedAt: Long = id,
        photos: List<PortablePhoto> = emptyList(),
    ) = PortableMemo(
        id = id, title = title, body = "本文$id", createdAt = 1_725_000_000_000,
        updatedAt = updatedAt, favorite = false, pinned = false, shelf = shelf,
        tags = emptyList(), comments = emptyList(), photos = photos,
    )

    @Test
    fun shelvesSplitAndTrashStaysHomeByDefault() {
        val plan = PortableExportPlanner.plan(
            PortableSnapshot(
                memos = listOf(
                    memo(1),
                    memo(2, shelf = PortableShelf.ARCHIVED),
                    memo(3, shelf = PortableShelf.TRASHED),
                ),
                diaries = emptyList(),
                notes = emptyList(),
            ),
            includeTrash = false,
            zone = zone,
        )
        assertEquals(1, plan.activeMemos.size)
        assertEquals(1, plan.archivedMemos.size)
        assertTrue(plan.trashedMemos.isEmpty())
        assertTrue(plan.activeMemos.single().markdownPath.contains("/memos/active/"))
        assertTrue(plan.archivedMemos.single().markdownPath.contains("/memos/archived/"))
    }

    @Test
    fun trashJoinsOnRequestUnderItsOwnFolder() {
        val plan = PortableExportPlanner.plan(
            PortableSnapshot(listOf(memo(3, shelf = PortableShelf.TRASHED)), emptyList(), emptyList()),
            includeTrash = true,
            zone = zone,
        )
        assertEquals(1, plan.trashedMemos.size)
        assertTrue(plan.trashedMemos.single().markdownPath.startsWith("MemoRipple-Export/trash/"))
    }

    @Test
    fun orderingIsNewestFirstAndDeterministic() {
        val plan = PortableExportPlanner.plan(
            PortableSnapshot(
                memos = listOf(memo(1, updatedAt = 10), memo(2, updatedAt = 30), memo(3, updatedAt = 20)),
                diaries = listOf(
                    PortableDiary(1, epochDay = 20_500, body = "", stateLabel = "下書き", photos = emptyList(), futureComments = emptyList()),
                    PortableDiary(2, epochDay = 20_700, body = "", stateLabel = "下書き", photos = emptyList(), futureComments = emptyList()),
                ),
                notes = emptyList(),
            ),
            includeTrash = false,
            zone = zone,
        )
        assertEquals(listOf(2L, 3L, 1L), plan.activeMemos.map { it.memo.id })
        assertEquals(listOf(2L, 1L), plan.diaries.map { it.diary.id })
    }

    @Test
    fun equalTitlesGetDistinctFolders() {
        val plan = PortableExportPlanner.plan(
            PortableSnapshot(listOf(memo(1, title = "同じ"), memo(2, title = "同じ")), emptyList(), emptyList()),
            includeTrash = false,
            zone = zone,
        )
        val paths = plan.activeMemos.map { it.markdownPath }
        assertEquals(2, paths.toSet().size)
    }

    @Test
    fun photoPlansKeepOrderAndRelativeLinks() {
        val photos = listOf(
            PortablePhoto("a".repeat(64), "image/jpeg", 10),
            PortablePhoto("b".repeat(64), "image/png", 20),
        )
        val planned = PortableExportPlanner.plan(
            PortableSnapshot(listOf(memo(1, photos = photos)), emptyList(), emptyList()),
            includeTrash = false,
            zone = zone,
        ).activeMemos.single()
        assertEquals(listOf(1, 2), planned.photos.map { it.displayIndex })
        assertEquals(listOf("photos/photo-01.jpg", "photos/photo-02.png"), planned.photos.map { it.markdownRef })
        // The photo entry sits inside the memo's own folder.
        val folder = planned.markdownPath.removeSuffix("/memo.md")
        assertTrue(planned.photos.all { it.entryPath.startsWith("$folder/photos/") })
    }

    @Test
    fun everyPlannedPathIsZipSafeEvenFromHostileTitles() {
        val hostile = listOf(
            memo(1, title = "../../etc/passwd"),
            memo(2, title = "a/b\\c:d*e?f\"g<h>i|j"),
            memo(3, title = "."),
            memo(4, title = "あ".repeat(300)),
        )
        val note = PortableNote(
            id = 9, title = "../note", subtitle = "", createdAt = 0, updatedAt = 0,
            coverPhoto = PortablePhoto("c".repeat(64), "image/webp", 1),
            sections = listOf(
                PortableNoteSection(
                    title = "章/悪意",
                    episodes = listOf(
                        PortableEpisode(5, 1, "../ep", "x", listOf(PortablePhoto("d".repeat(64), "image/gif", 2))),
                    ),
                ),
            ),
        )
        val plan = PortableExportPlanner.plan(
            PortableSnapshot(hostile, emptyList(), listOf(note)),
            includeTrash = false,
            zone = zone,
        )
        val allPaths = plan.allMemos.flatMap { listOf(it.markdownPath) + it.photos.map(PortableExportPlanner.PlannedPhoto::entryPath) } +
            plan.notes.flatMap { n ->
                listOf(n.readmePath) + listOfNotNull(n.coverPhoto?.entryPath) +
                    n.episodeFiles.flatMap { listOf(it.markdownPath) + it.photos.map(PortableExportPlanner.PlannedPhoto::entryPath) }
            }
        assertTrue(allPaths.isNotEmpty())
        allPaths.forEach { path ->
            assertTrue("unsafe: $path", PortableExportNaming.isSafeEntryPath(path))
        }
    }

    @Test
    fun anEmptyDatabaseStillPlansCleanly() {
        val plan = PortableExportPlanner.plan(
            PortableSnapshot(emptyList(), emptyList(), emptyList()),
            includeTrash = true,
            zone = zone,
        )
        assertEquals(0, plan.totalUnits)
        assertFalse(PortableMarkdownRenderer.index(plan).contains("## メモ"))
    }

    @Test
    fun progressUnitsCountDocumentsAndPhotos() {
        val plan = PortableExportPlanner.plan(
            PortableSnapshot(
                listOf(memo(1, photos = listOf(PortablePhoto("a".repeat(64), "image/jpeg", 1)))),
                listOf(PortableDiary(1, 20_500, "x", "下書き", emptyList(), emptyList())),
                emptyList(),
            ),
            includeTrash = false,
            zone = zone,
        )
        // memo.md + its photo + diary.md
        assertEquals(3, plan.totalUnits)
    }
}

/** Several journal entries on one day, even in the same minute, each get their own folder. */
class PortableExportJournalPathTest {
    private val zone = ZoneId.of("Asia/Tokyo")

    @Test
    fun entriesOfOneDayAndMinuteGetDistinctPathsCarryingTheirIds() {
        // 2026-02-15 09:10 JST for both; ids differ.
        val at = java.time.ZonedDateTime.of(2026, 2, 15, 9, 10, 0, 0, zone).toInstant().toEpochMilli()
        val day = java.time.LocalDate.of(2026, 2, 15).toEpochDay()
        val plan = PortableExportPlanner.plan(
            PortableSnapshot(
                memos = emptyList(),
                diaries = listOf(
                    PortableDiary(7, day, "一本目", "編集中", emptyList(), emptyList(), createdAt = at),
                    PortableDiary(8, day, "二本目", "編集中", emptyList(), emptyList(), createdAt = at + 20_000),
                ),
                notes = emptyList(),
            ),
            includeTrash = false,
            zone = zone,
        )
        val paths = plan.diaries.map { it.markdownPath }
        assertEquals(2, paths.toSet().size)
        assertTrue(paths.any { it.endsWith("/diaries/2026/2026-02-15/0910-7/diary.md") })
        assertTrue(paths.any { it.endsWith("/diaries/2026/2026-02-15/0910-8/diary.md") })
        assertEquals(listOf(8L, 7L), plan.diaries.map { it.diary.id })
    }
}

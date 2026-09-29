package io.github.cragcoffee.memoripple.domain.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteArrangementTest {

    private fun episode(id: Long, chapterId: Long?) = NoteStructure.EpisodeInput(
        memoId = id,
        title = "第${id}",
        characterCount = 0,
        updatedAt = 0,
        chapterId = chapterId,
    )

    private val sections = NoteStructure.sections(
        chapters = listOf(
            NoteStructure.ChapterInput(10, "第一章", 0),
            NoteStructure.ChapterInput(20, "第二章", 1),
        ),
        episodes = listOf(episode(1, 10), episode(2, 10), episode(3, 20)),
    )

    @Test
    fun aNoteIsOneRunOfRowsWithItsHeadingsStandingInIt() {
        assertEquals(
            listOf("chapter-10", "episode-1", "episode-2", "chapter-20", "episode-3"),
            NoteArrangement.rows(sections).map(NoteArrangement.Row::key),
        )
    }

    @Test
    fun aHeadingCrossingAnEpisodeTakesItOverOrGivesItUp() {
        val rows = NoteArrangement.rows(sections)
        // 第二章 steps up past 第2, so 第2 is now below it and becomes one of its episodes.
        val moved = requireNotNull(NoteArrangement.moved(rows, index = 3, delta = -1))
        assertEquals(
            listOf("chapter-10", "episode-1", "chapter-20", "episode-2", "episode-3"),
            moved.map(NoteArrangement.Row::key),
        )
        val placement = NoteArrangement.placement(moved)
        assertEquals(listOf(1L to 10L, 2L to 20L, 3L to 20L), placement.episodes)
    }

    @Test
    fun anEpisodeCrossingAHeadingLeavesTheChapterItWasIn() {
        val rows = NoteArrangement.rows(sections)
        // 第3 steps up past 第二章, so it is no longer under it.
        val moved = requireNotNull(NoteArrangement.moved(rows, index = 4, delta = -1))
        assertEquals(listOf(1L to 10L, 2L to 10L, 3L to 10L), NoteArrangement.placement(moved).episodes)
    }

    @Test
    fun anEpisodeLiftedAboveEveryHeadingBelongsToNone() {
        var rows = NoteArrangement.rows(sections)
        repeat(4) { step -> rows = requireNotNull(NoteArrangement.moved(rows, 4 - step, -1)) }
        assertEquals("episode-3", rows.first().key)
        assertEquals(null, NoteArrangement.placement(rows).episodes.first().second)
    }

    @Test
    fun headingsKeepTheOrderTheyStandIn() {
        val rows = NoteArrangement.rows(sections)
        // 第二章 climbs over 第2, 第1 and 第一章 and ends up first.
        var moved = rows
        repeat(3) { step -> moved = requireNotNull(NoteArrangement.moved(moved, 3 - step, -1)) }
        assertEquals(listOf(20L, 10L), NoteArrangement.placement(moved).chapterIds)
    }

    @Test
    fun aRowAtTheEdgeHasNowhereToGo() {
        val rows = NoteArrangement.rows(sections)
        assertNull(NoteArrangement.moved(rows, 0, -1))
        assertNull(NoteArrangement.moved(rows, rows.lastIndex, 1))
    }
}

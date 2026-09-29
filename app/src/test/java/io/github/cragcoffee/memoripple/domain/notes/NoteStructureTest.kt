package io.github.cragcoffee.memoripple.domain.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteStructureTest {

    private fun chapter(id: Long, title: String, order: Int) =
        NoteStructure.ChapterInput(id, title, order)

    private fun episode(id: Long, title: String, chapterId: Long? = null) =
        NoteStructure.EpisodeInput(id, title, title.length, id, chapterId)

    @Test
    fun theNumberingRunsStraightThroughTheNoteAcrossChapters() {
        val sections = NoteStructure.sections(
            chapters = listOf(chapter(10, "第一章", 0), chapter(20, "第二章", 1)),
            episodes = listOf(
                episode(1, "はじまり", chapterId = 10),
                episode(2, "つづき", chapterId = 10),
                episode(3, "転回", chapterId = 20),
            ),
        )

        assertEquals(listOf(1, 2, 3), NoteStructure.flatten(sections).map(NoteEpisode::number))
        assertEquals(listOf("第一章", "第二章"), sections.map(NoteSection::title))
    }

    @Test
    fun episodesUnderNoChapterStartTheNoteWhereWritingStarts() {
        val sections = NoteStructure.sections(
            chapters = listOf(chapter(10, "第一章", 0)),
            episodes = listOf(
                episode(1, "前書き"),
                episode(2, "はじまり", chapterId = 10),
            ),
        )

        assertNull(sections.first().title)
        assertEquals(listOf("前書き"), sections.first().episodes.map(NoteEpisode::title))
        assertEquals("第一章", sections[1].title)
    }

    @Test
    fun aNoteWithNoChaptersIsOneRunOfEpisodes() {
        val sections = NoteStructure.sections(
            chapters = emptyList(),
            episodes = listOf(episode(1, "一"), episode(2, "二")),
        )

        assertEquals(1, sections.size)
        assertNull(sections.single().chapterId)
        assertEquals(listOf(1, 2), sections.single().episodes.map(NoteEpisode::number))
    }

    @Test
    fun anEmptyChapterIsStillAHeading() {
        val sections = NoteStructure.sections(
            chapters = listOf(chapter(10, "まだ書いていない章", 0)),
            episodes = emptyList(),
        )

        assertEquals(listOf("まだ書いていない章"), sections.map(NoteSection::title))
        assertTrue(sections.single().episodes.isEmpty())
    }

    @Test
    fun anEpisodeWhoseChapterIsGoneComesBackToTheFrontRatherThanDisappearing() {
        val sections = NoteStructure.sections(
            chapters = listOf(chapter(10, "残った章", 0)),
            episodes = listOf(
                episode(1, "消えた章の話", chapterId = 99),
                episode(2, "残った話", chapterId = 10),
            ),
        )

        assertEquals(listOf("消えた章の話"), sections.first().episodes.map(NoteEpisode::title))
        assertEquals(2, NoteStructure.flatten(sections).size)
    }

    @Test
    fun chaptersFollowTheirOrderAndTiesFallBackToTheirId() {
        val sections = NoteStructure.sections(
            chapters = listOf(
                chapter(30, "三", 2),
                chapter(10, "一", 0),
                chapter(21, "二b", 1),
                chapter(20, "二a", 1),
            ),
            episodes = emptyList(),
        )

        assertEquals(listOf("一", "二a", "二b", "三"), sections.map(NoteSection::title))
    }

    @Test
    fun aNoteWithNothingInItHasNothingToShow() {
        assertTrue(NoteStructure.sections(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun aCoverColourIsPickedFromTheTitleAndStaysTheSame() {
        assertEquals(
            NoteCoverColor.suggestedFor("夜明け前に君と"),
            NoteCoverColor.suggestedFor("夜明け前に君と"),
        )
        assertEquals(NoteCoverColor.Default, NoteCoverColor.fromStorageId("知らない色"))
        NoteCoverColor.entries.forEach {
            assertEquals(it, NoteCoverColor.fromStorageId(it.storageId))
        }
    }
}

package io.github.cragcoffee.memoripple.domain.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineEpisodesTest {

    @Test
    fun everyHeadingBecomesAnEpisodeAndKeepsWhatWasUnderIt() {
        val drafts = OutlineEpisodes.of(
            "■ 発端\n- 港に着いた日のこと\n- 灯台守はもういない\n■ 結末\n- まだ決めていない",
        )
        assertEquals(2, drafts.size)
        assertEquals("発端", drafts[0].title)
        assertEquals("- 港に着いた日のこと\n- 灯台守はもういない", drafts[0].body)
        assertEquals("結末", drafts[1].title)
    }

    @Test
    fun aNestedHeadingIsAnEpisodeLikeAnyOther() {
        val drafts = OutlineEpisodes.of("■ 発端\n  ■ 港の場面\n  - 灯台守はもういない")
        assertEquals(listOf("発端", "港の場面"), drafts.map(OutlineEpisodes.Draft::title))
        // What was written inside something stands at the left once it stands alone.
        assertEquals("- 灯台守はもういない", drafts[1].body)
    }

    @Test
    fun theIndentAWholeBlockSharesIsRemovedAndItsOwnShapeIsKept() {
        val drafts = OutlineEpisodes.of("■ 中盤\n  - 手紙が見つかる\n    - 名前が消えている")
        assertEquals("- 手紙が見つかる\n  - 名前が消えている", drafts[0].body)
    }

    @Test
    fun whatIsWrittenBeforeTheFirstHeadingStaysBehind() {
        val drafts = OutlineEpisodes.of("この話は港から始まる\n■ 発端\n- 港に着いた日のこと")
        assertEquals(1, drafts.size)
        assertEquals("- 港に着いた日のこと", drafts[0].body)
    }

    @Test
    fun aHeadingWithNothingUnderItIsStillAnEpisode() {
        val drafts = OutlineEpisodes.of("■ 結末")
        assertEquals(listOf(OutlineEpisodes.Draft("結末", "")), drafts)
    }

    @Test
    fun aMarkdownHeadingCountsTheSameWayItDoesEverywhereElse() {
        assertEquals(listOf("発端"), OutlineEpisodes.of("# 発端\n- 港").map(OutlineEpisodes.Draft::title))
    }

    @Test
    fun aMemoWithNoHeadingHasNothingToMake() {
        assertFalse(OutlineEpisodes.canMake("記法のない一行。"))
        assertEquals(emptyList<OutlineEpisodes.Draft>(), OutlineEpisodes.of("記法のない一行。"))
    }

    @Test
    fun aMemoWithOneHeadingHasSomethingToMake() {
        assertTrue(OutlineEpisodes.canMake("散文の行\n■ 発端"))
    }
}

package io.github.cragcoffee.memoripple.domain

import io.github.cragcoffee.memoripple.domain.memos.LinkableMemo
import io.github.cragcoffee.memoripple.domain.memos.MemoLinkGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteLinkTest {

    private val memos = listOf(
        LinkableMemo(1, "会議メモ", "[[買い物リスト]]も参照"),
        LinkableMemo(2, "買い物リスト", "牛乳"),
        LinkableMemo(3, "日報", "[[会議メモ]]の続き"),
    )

    @Test
    fun aReferenceIsRecognisedAndItsTitleRead() {
        assertEquals(listOf("買い物リスト"), NoteLink.titles("[[買い物リスト]]も参照"))
    }

    @Test
    fun theSameTitleIsListedOnce() {
        assertEquals(listOf("会議"), NoteLink.titles("[[会議]]と[[会議]]"))
    }

    @Test
    fun bracketsThatWrapNothingAreNotAReference() {
        assertTrue(NoteLink.spans("[[]]").isEmpty())
        assertTrue(NoteLink.spans("[[  ]]").isEmpty())
        assertTrue(NoteLink.spans("配列[0]").isEmpty())
    }

    @Test
    fun aReferenceDoesNotRunAcrossALineBreak() {
        assertTrue(NoteLink.spans("[[会議\nメモ]]").isEmpty())
    }

    @Test
    fun theBracketsComeOffForWhoeverIsReading() {
        assertEquals("買い物リストも参照", NoteLink.strip("[[買い物リスト]]も参照"))
        assertEquals("会議メモの続き", BodyText.readable("[[会議メモ]]の続き"))
        assertEquals("太字とリンク", BodyText.readable("**太字**と[[リンク]]"))
    }

    @Test
    fun insertingWritesTheReferenceWhereTheCursorIs() {
        val result = NoteLink.insert(OutlineEdit("ここに", 3, 3), "会議メモ")

        assertEquals("ここに[[会議メモ]]", result.text)
        assertEquals(result.text.length, result.selectionStart)
    }

    @Test
    fun insertingReplacesWhateverWasSelected() {
        assertEquals("[[会議メモ]]", NoteLink.insert(OutlineEdit("下書き", 0, 3), "会議メモ").text)
    }

    @Test
    fun anOutgoingLinkFindsTheMemoItNames() {
        val outgoing = MemoLinkGraph.outgoing("[[買い物リスト]]も参照", memos)

        assertEquals(1, outgoing.size)
        assertTrue(outgoing.single().isResolved)
        assertEquals(2L, outgoing.single().memoId)
    }

    @Test
    fun aLinkToAMemoThatDoesNotExistIsKeptRatherThanDropped() {
        val outgoing = MemoLinkGraph.outgoing("[[まだ無いメモ]]", memos)

        assertEquals(1, outgoing.size)
        assertFalse(outgoing.single().isResolved)
        assertEquals("まだ無いメモ", outgoing.single().title)
    }

    @Test
    fun backlinksReportEveryMemoPointingHere() {
        val links = MemoLinkGraph.of(memos[0], memos)

        assertEquals(listOf(3L), links.backlinks.map { it.id })
        assertEquals(listOf(2L), links.outgoing.map { it.memoId })
    }

    @Test
    fun aMemoLinkingToItselfIsNotItsOwnBacklink() {
        val self = LinkableMemo(9, "自分", "[[自分]]")

        assertTrue(MemoLinkGraph.backlinks(self, listOf(self)).isEmpty())
    }

    @Test
    fun anUntitledMemoHasNoBacklinksBecauseThereIsNothingToNameIt() {
        val untitled = LinkableMemo(9, "", "本文")

        assertTrue(MemoLinkGraph.backlinks(untitled, memos).isEmpty())
    }

    @Test
    fun titlesMatchTheWayTagsDo() {
        val target = listOf(LinkableMemo(1, "ＡＢＣ", ""))

        assertTrue(MemoLinkGraph.outgoing("[[abc]]", target).single().isResolved)
    }
}

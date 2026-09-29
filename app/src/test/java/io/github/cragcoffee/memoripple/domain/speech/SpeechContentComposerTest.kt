package io.github.cragcoffee.memoripple.domain.speech

import io.github.cragcoffee.memoripple.data.MemoCommentEntity
import io.github.cragcoffee.memoripple.domain.diary.FutureCommentRevealContext
import io.github.cragcoffee.memoripple.domain.diary.RevealedFutureDiaryComment
import io.github.cragcoffee.memoripple.domain.diary.SourceDiaryContext
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechContentComposerTest {
    private val subject = SpeechContentComposer()

    @Test
    fun `memo comments use stable playback order and skip non-speech presets`() {
        val comments = listOf(
            comment(id = 2, text = "B", order = 2),
            comment(id = 1, text = "A", order = 0),
            comment(id = 4, text = "wwwwwwww", order = 1),
            comment(id = 3, text = "C", order = 1),
            comment(id = 5, text = "88888888", order = 3),
            comment(id = 6, text = "！？！？！？", order = 4),
            comment(id = 7, text = "ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!", order = 5),
            comment(id = 8, text = "ここ好き", order = 6),
            comment(id = 9, text = "がんばれ！", order = 7),
        )

        assertEquals(
            "題名\n\n本文\n\nA\n\nC\n\nB\n\nここ好き\n\nがんばれ！",
            subject.memo("題名", "本文", comments, includeComments = true),
        )
    }

    @Test
    fun `memo omits comments unless explicitly requested`() {
        assertEquals(
            "題名\n\n本文",
            subject.memo("題名", "# 本文", listOf(comment(1, "コメント", 0)), false),
        )
    }

    @Test
    fun `future text is inaccessible until first presentation completes`() {
        val pending = context(firstPresentedAt = null)
        val completed = context(firstPresentedAt = 30L)

        FutureSpeechContent.entries.forEach { content ->
            assertEquals("", subject.future(pending, content))
        }
        assertEquals("元の日記", subject.future(completed, FutureSpeechContent.SOURCE_DIARY))
        assertEquals("未来", subject.future(completed, FutureSpeechContent.FUTURE_COMMENT))
        assertEquals("元の日記\n\n未来", subject.future(completed, FutureSpeechContent.BOTH))
    }

    private fun comment(id: Long, text: String, order: Int) = MemoCommentEntity(
        id = id,
        memoId = 1,
        text = text,
        createdAt = id,
        playbackOrder = order,
    )

    private fun context(firstPresentedAt: Long?) = FutureCommentRevealContext(
        sourceDiary = SourceDiaryContext(1, 2, "# 元の日記"),
        comment = RevealedFutureDiaryComment(
            id = 3,
            diaryEntryId = 1,
            text = "未来",
            revealAt = 10,
            revealedAt = 20,
            firstPresentedAt = firstPresentedAt,
        ),
    )
}

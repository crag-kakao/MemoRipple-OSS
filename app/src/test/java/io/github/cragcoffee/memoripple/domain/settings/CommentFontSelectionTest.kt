package io.github.cragcoffee.memoripple.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommentFontSelectionTest {

    private val label: (CommentFont) -> String = { font ->
        when (font) {
            CommentFont.DEFAULT -> "デフォルト"
            CommentFont.GOTHIC -> "ゴシック体"
            CommentFont.MINCHO -> "明朝体"
            CommentFont.ROUNDED -> "丸文字体"
        }
    }

    @Test
    fun userIdsRoundTripAndBundledIdsStayThemselves() {
        val font = UserCommentFont(id = "abc", name = "手書き", fileName = "abc.ttf")
        val stored = CommentFontSelection.userStorageId(font)
        assertEquals("user:abc", stored)
        assertEquals("abc", CommentFontSelection.userIdOrNull(stored))
        assertNull(CommentFontSelection.userIdOrNull("mincho"))
        assertNull(CommentFontSelection.userIdOrNull(null))
    }

    @Test
    fun theRowNamesWhatIsChosen() {
        val font = UserCommentFont(id = "abc", name = "手書き", fileName = "abc.ttf")
        assertEquals("手書き", CommentFontSelection.displayName("user:abc", listOf(font), label))
        assertEquals("明朝体", CommentFontSelection.displayName("mincho", listOf(font), label))
        assertEquals("デフォルト", CommentFontSelection.displayName("default", emptyList(), label))
    }

    @Test
    fun aVanishedUserFontReadsAsTheDefaultAgain() {
        assertEquals(
            "デフォルト",
            CommentFontSelection.displayName("user:gone", emptyList(), label),
        )
    }

    @Test
    fun anUnknownStoredValueFallsBackToTheDefault() {
        assertEquals("デフォルト", CommentFontSelection.displayName("no-such", emptyList(), label))
    }
}

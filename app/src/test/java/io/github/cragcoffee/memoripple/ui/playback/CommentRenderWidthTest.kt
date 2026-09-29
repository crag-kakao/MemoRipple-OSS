package io.github.cragcoffee.memoripple.ui.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentRenderWidthTest {
    @Test
    fun fractionalGlyphWidthIsRoundedUpBeforePadding() {
        val width = calculateCommentRenderWidthPx(
            measuredTextWidthPx = 100.01f,
            contentHorizontalPaddingPx = 7f,
            glyphSafetyPaddingPx = 2f,
        )

        assertEquals(119f, width, 0f)
    }

    @Test
    fun glyphSafetyMarginIsSeparateFromContentPadding() {
        val withoutSafety = calculateCommentRenderWidthPx(100f, 7f, 0f)
        val withSafety = calculateCommentRenderWidthPx(100f, 7f, 2f)

        assertEquals(4f, withSafety - withoutSafety, 0f)
        assertTrue(withSafety > 100f)
    }
}

package io.github.cragcoffee.memoripple.domain.diary

import io.github.cragcoffee.memoripple.domain.comments.CommentColorRole
import io.github.cragcoffee.memoripple.domain.comments.CommentEmphasisRole
import io.github.cragcoffee.memoripple.domain.comments.CommentMotionMode
import io.github.cragcoffee.memoripple.domain.comments.CommentSizeRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FutureCommentExpressionTest {
    @Test
    fun defaultsMatchLegacyFuturePresentation() {
        val expression = FutureCommentExpression.Default

        assertEquals(CommentColorRole.DEFAULT, expression.appearance.colorRole)
        assertEquals(CommentSizeRole.STANDARD, expression.appearance.sizeRole)
        assertEquals(CommentEmphasisRole.NORMAL, expression.appearance.emphasisRole)
        assertEquals(CommentMotionMode.FLOW, expression.motionMode)
        assertTrue(expression.isDefault)
    }

    @Test
    fun knownStableIdsRoundTripAndUnknownLocalValuesFallBackSafely() {
        val known = FutureCommentExpression.fromStorageIds(
            color = "pink",
            size = "large",
            emphasis = "strong",
            motionMode = "fixed_bottom",
        )
        val unknown = FutureCommentExpression.fromStorageIds(
            color = "future_color",
            size = "huge",
            emphasis = "loud",
            motionMode = "wave",
        )

        assertEquals(CommentColorRole.PINK, known.appearance.colorRole)
        assertEquals(CommentSizeRole.LARGE, known.appearance.sizeRole)
        assertEquals(CommentEmphasisRole.STRONG, known.appearance.emphasisRole)
        assertEquals(CommentMotionMode.FIXED_BOTTOM, known.motionMode)
        assertEquals(FutureCommentExpression.Default, unknown)
    }
}

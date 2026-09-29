package io.github.cragcoffee.memoripple.ui.diary

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.cragcoffee.memoripple.domain.comments.CommentColorRole
import io.github.cragcoffee.memoripple.domain.comments.CommentEmphasisRole
import io.github.cragcoffee.memoripple.domain.comments.CommentMotionMode
import io.github.cragcoffee.memoripple.domain.comments.CommentSizeRole
import io.github.cragcoffee.memoripple.domain.diary.FutureCommentExpression
import io.github.cragcoffee.memoripple.domain.diary.FutureDiaryCommentItem
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FutureCommentExpressionInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun creatorEditsSupportedExpressionWithoutSpeedOrPlacementAndRetainsFailedDraft() {
        var sent: FutureCommentExpression? = null
        composeRule.setContent {
            MaterialTheme {
                FutureCommentCreatorSheet(
                    initialDate = LocalDate.of(2027, 1, 1),
                    onResolveRevealAt = { _, _ -> 2_000L },
                    isFutureRevealAt = { true },
                    onDismiss = {},
                    onSend = { _, _, expression -> sent = expression },
                )
            }
        }

        composeRule.onNodeWithTag("future_comment_input").performTextInput("ここまで続けられた？")
        composeRule.onNodeWithTag("future_comment_expression").performScrollTo().performClick()
        composeRule.onNodeWithText("未来コメントの表現").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("motion_speed_standard").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("motion_placement_auto").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("motion_direction_rtl").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("motion_effect_straight").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag("appearance_color_pink").performScrollTo().performClick()
        composeRule.onNodeWithTag("appearance_size_large").performScrollTo().performClick()
        composeRule.onNodeWithTag("appearance_emphasis_strong").performScrollTo().performClick()
        composeRule.onNodeWithTag("motion_mode_fixed_top").performScrollTo().performClick()
        composeRule.onNodeWithTag("comment_motion_preview_label").assertTextContains("上に固定")
        composeRule.onNodeWithTag("confirm_comment_appearance").performScrollTo().performClick()

        composeRule.onNodeWithTag("future_comment_expression")
            .assertTextContains("ピンク・大きめ・強調・上に固定", substring = true)
        composeRule.onNodeWithTag("send_future_comment").performScrollTo().performClick()
        composeRule.onNodeWithText("送った後は、コメントの表現も変更できません。", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_send_future_comment").performClick()

        composeRule.runOnIdle {
            val expression = requireNotNull(sent)
            assertEquals(CommentColorRole.PINK, expression.appearance.colorRole)
            assertEquals(CommentSizeRole.LARGE, expression.appearance.sizeRole)
            assertEquals(CommentEmphasisRole.STRONG, expression.appearance.emphasisRole)
            assertEquals(CommentMotionMode.FIXED_TOP, expression.motionMode)
        }
        composeRule.onNodeWithTag("future_comment_input").assertIsDisplayed()
        composeRule.onNodeWithTag("future_comment_expression")
            .assertTextContains("ピンク・大きめ・強調・上に固定", substring = true)
    }

    @Test
    fun sealedAndDeliveredRowsExposeNeitherTextNorExpressionMetadata() {
        composeRule.setContent {
            MaterialTheme {
                FutureDiaryCommentSection(
                    comments = listOf(
                        FutureDiaryCommentItem.Sealed(1, 10, 2_000),
                        FutureDiaryCommentItem.Delivered(2, 10, 3_000),
                    ),
                    canCreate = false,
                    onCreate = {},
                    onDelete = {},
                    onReplay = {},
                )
            }
        }

        assertTrue(composeRule.onAllNodesWithText("ピンク", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("大きめ", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("固定", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("future_comment_expression_summary").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun postPresentationRowShowsOnlyQuietNonDefaultSummary() {
        val expression = FutureCommentExpression.fromStorageIds(
            color = "pink",
            size = "large",
            emphasis = "strong",
            motionMode = "fixed_bottom",
        )
        composeRule.setContent {
            MaterialTheme {
                FutureDiaryCommentSection(
                    comments = listOf(
                        FutureDiaryCommentItem.Revealed(
                            id = 1,
                            diaryEntryId = 10,
                            revealAt = 2_000,
                            text = "受け取った記録",
                            revealedAt = 2_000,
                            firstPresentedAt = 3_000,
                            expression = expression,
                        ),
                    ),
                    canCreate = false,
                    onCreate = {},
                    onDelete = {},
                    onReplay = {},
                )
            }
        }

        composeRule.onNodeWithText("受け取った記録").assertIsDisplayed()
        composeRule.onNodeWithTag("future_comment_expression_summary")
            .assertTextContains("ピンク・大きめ・強調・下に固定")
    }
}

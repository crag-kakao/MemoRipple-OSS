package io.github.cragcoffee.memoripple.ui.memos

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.cragcoffee.memoripple.domain.comments.CommentAppearance
import io.github.cragcoffee.memoripple.domain.comments.CommentMotion
import io.github.cragcoffee.memoripple.domain.comments.CommentMotionMode
import io.github.cragcoffee.memoripple.domain.comments.CommentFlowDirection
import io.github.cragcoffee.memoripple.domain.comments.CommentFlowEffect
import io.github.cragcoffee.memoripple.domain.comments.CommentPlacementRole
import io.github.cragcoffee.memoripple.domain.comments.CommentSpeedRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CommentExpressionSheetInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fixedModeIsPreviewedAndPreservesHiddenFlowSettings() {
        var confirmed: CommentMotion? = null
        composeRule.setContent {
            MaterialTheme {
                CommentExpressionSheet(
                    initialAppearance = CommentAppearance.Default,
                    initialMotion = CommentMotion(
                        speedRole = CommentSpeedRole.FAST,
                        placementRole = CommentPlacementRole.BOTTOM,
                        direction = CommentFlowDirection.LEFT_TO_RIGHT,
                        flowEffect = CommentFlowEffect.WAVE,
                    ),
                    previewText = "複数行になっても全文を表示する固定コメント",
                    onDismiss = {},
                    onConfirm = { _, motion -> confirmed = motion },
                )
            }
        }

        // The section headings are gone: every row now says what it is, so the preview and the
        // flow settings are looked for by what they are rather than by a word above them.
        composeRule.onNodeWithTag("comment_appearance_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("motion_speed_fast").performScrollTo().assertExists()
        composeRule.onNodeWithContentDescription("大きさ 標準").assertExists()
        composeRule.onNodeWithContentDescription("強調 通常").assertExists()
        composeRule.onNodeWithContentDescription("表示方法 流れる").assertExists()
        composeRule.onNodeWithTag("motion_mode_fixed_top").performScrollTo().performClick()
        composeRule.onNodeWithTag("comment_motion_preview_label").assertTextContains("上に固定")
        assertTrue(composeRule.onAllNodesWithTag("motion_speed_fast").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("motion_direction_ltr").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("motion_effect_wave").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag("motion_mode_flow").performClick()
        composeRule.onNodeWithTag("motion_speed_fast").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("motion_placement_bottom").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("motion_direction_ltr").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("motion_effect_wave").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("motion_mode_fixed_bottom").performScrollTo().performClick()
        composeRule.onNodeWithTag("confirm_comment_appearance").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(CommentMotionMode.FIXED_BOTTOM, confirmed?.mode)
            assertEquals(CommentSpeedRole.FAST, confirmed?.speedRole)
            assertEquals(CommentPlacementRole.BOTTOM, confirmed?.placementRole)
            assertEquals(CommentFlowDirection.LEFT_TO_RIGHT, confirmed?.direction)
            assertEquals(CommentFlowEffect.WAVE, confirmed?.flowEffect)
        }
    }
}

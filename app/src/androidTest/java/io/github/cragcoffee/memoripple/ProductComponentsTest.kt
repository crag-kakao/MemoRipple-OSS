package io.github.cragcoffee.memoripple

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.ui.components.ProductEmptyState
import io.github.cragcoffee.memoripple.ui.components.ProductInfoBanner
import io.github.cragcoffee.memoripple.ui.components.ProductSettingsRow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProductComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyStateExplainsNextActionAndKeepsItInteractive() {
        var actionInvoked = false
        composeRule.setContent {
            MaterialTheme {
                ProductEmptyState(
                    title = "まだメモがありません",
                    description = "思いついたことを自由に書いてみましょう。",
                    actionLabel = "新しいメモ",
                    onAction = { actionInvoked = true },
                )
            }
        }

        composeRule.onNodeWithText("まだメモがありません").assertIsDisplayed()
        composeRule.onNodeWithText("思いついたことを自由に書いてみましょう。")
            .assertIsDisplayed()
        composeRule.onNodeWithText("新しいメモ").performClick()
        assertTrue(actionInvoked)
    }

    @Test
    fun settingsRowHasMergedDescriptionTouchTargetAndDisabledSemantics() {
        composeRule.setContent {
            MaterialTheme {
                ProductSettingsRow(
                    title = "自動バックアップ",
                    supportingText = "無効",
                    testTag = "settings_row",
                    enabled = false,
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("自動バックアップ、無効")
            .assertIsNotEnabled()
        composeRule.onNodeWithTag("settings_row")
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun infoBannerKeepsItsActionAddressable() {
        composeRule.setContent {
            MaterialTheme {
                ProductInfoBanner(
                    title = "コメントを再生中",
                    supportingText = "残り 12秒",
                    actionLabel = "終了",
                    onAction = {},
                    actionTestTag = "stop_action",
                )
            }
        }

        composeRule.onNodeWithText("コメントを再生中").assertIsDisplayed()
        composeRule.onNodeWithText("残り 12秒").assertIsDisplayed()
        composeRule.onNodeWithTag("stop_action")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }
}

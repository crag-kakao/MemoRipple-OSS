package io.github.cragcoffee.memoripple

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.domain.ai.ModelAvailability
import io.github.cragcoffee.memoripple.domain.ai.ModelUnavailableReason
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessage
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessageKind
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.ui.chat.AiPanelState
import io.github.cragcoffee.memoripple.ui.chat.ChatScreen
import io.github.cragcoffee.memoripple.ui.chat.ChatUiState
import io.github.cragcoffee.memoripple.ui.theme.MemoRippleTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * UI/UX review 2026-09-23 — the two remarks, on the screen itself.
 *
 * The hint above the input (「自由文の依頼にはLocal AIモデルが必要です。」) carries a × that closes it,
 * and a closed hint never renders again — the state says so and the screen obeys, whatever the
 * availability. The conversation reads at 16 sp: the user's bubble and MemoRipple's line alike.
 */
class ChatHintDismissInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val noModel = ModelAvailability.Unavailable(ModelUnavailableReason.NO_MODEL_CONFIGURED)
    private val unsupported = ModelAvailability.Unavailable(ModelUnavailableReason.UNSUPPORTED_DEVICE)

    private var shown by mutableStateOf<(@Composable () -> Unit)?>(null)

    private fun render(content: @Composable () -> Unit) {
        val first = shown == null
        shown = content
        if (first) {
            composeRule.setContent {
                MemoRippleTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                        Box(Modifier.requiredWidth(360.dp)) { shown?.invoke() }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun count(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    /** The size the line is actually laid out at — read from the Text itself, which is a child of the bubble. */
    private fun fontSizeSp(substring: String): Float {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(substring, substring = true, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.first().layoutInput.style.fontSize.value
    }

    @Test
    fun theHintOffersACloseAndACloseMeansGoneForGood() {
        var dismissed = false
        render {
            ChatScreen(
                state = ChatUiState(ai = AiPanelState(availability = noModel), aiHintDismissed = dismissed),
                onDismissAiHint = { dismissed = true },
            )
        }
        composeRule.onNodeWithTag("chat_ai_hint", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("chat_ai_hint_dismiss", useUnmergedTree = true).assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithTag("chat_ai_hint_dismiss", useUnmergedTree = true).performClick()
        assertTrue("the tap reaches the view model", dismissed)

        // the state that a closed hint produces: the line is gone, and the chat is untouched otherwise
        render { ChatScreen(state = ChatUiState(ai = AiPanelState(availability = noModel), aiHintDismissed = true)) }
        assertEquals("the hint is gone", 0, count("chat_ai_hint"))
        assertEquals("and so is its ×", 0, count("chat_ai_hint_dismiss"))
        listOf("chat_input", "chat_plus", "chat_send", "chat_folder_chip").forEach {
            composeRule.onNodeWithTag(it, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun aClosedHintStaysClosedForEveryReasonTheModelIsMissing() {
        render { ChatScreen(state = ChatUiState(ai = AiPanelState(availability = unsupported), aiHintDismissed = false)) }
        composeRule.onNodeWithTag("chat_ai_hint", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("chat_ai_hint_dismiss", useUnmergedTree = true).assertIsDisplayed()
        listOf(unsupported, noModel).forEach { availability ->
            render { ChatScreen(state = ChatUiState(ai = AiPanelState(availability = availability), aiHintDismissed = true)) }
            assertEquals("a closed hint came back for $availability", 0, count("chat_ai_hint"))
        }
    }

    @Test
    fun theConversationReadsAtSixteenSp() {
        val transcript = listOf(
            ChatMessage(1, 1, ChatRole.USER, ChatMessageKind.TEXT, "きょうの思いつき", 1),
            ChatMessage(2, 1, ChatRole.ASSISTANT, ChatMessageKind.TEXT, "アイデアメモを始めます。\nまず、どんなアイデアですか？", 2),
        )
        render { ChatScreen(state = ChatUiState(historyEnabled = true, conversationId = 1, transcript = transcript, ai = AiPanelState(availability = noModel), aiHintDismissed = true)) }
        assertEquals("the user's bubble", 16f, fontSizeSp("きょうの思いつき"), 0.01f)
        assertEquals("MemoRipple's line", 16f, fontSizeSp("どんなアイデアですか"), 0.01f)
    }
}

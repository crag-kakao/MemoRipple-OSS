package io.github.cragcoffee.memoripple

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.cragcoffee.memoripple.domain.ai.AiFailureStage
import io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult
import io.github.cragcoffee.memoripple.domain.ai.CommandPreview
import io.github.cragcoffee.memoripple.domain.ai.DocumentVersion
import io.github.cragcoffee.memoripple.domain.ai.ExecutionDecision
import io.github.cragcoffee.memoripple.domain.ai.ExecutionPolicy
import io.github.cragcoffee.memoripple.domain.ai.ModelAvailability
import io.github.cragcoffee.memoripple.domain.ai.ModelUnavailableReason
import io.github.cragcoffee.memoripple.domain.ai.PendingWrite
import io.github.cragcoffee.memoripple.domain.ai.ResolutionResult
import io.github.cragcoffee.memoripple.domain.ai.ResolvedCommand
import io.github.cragcoffee.memoripple.domain.ai.WriteOutcome
import io.github.cragcoffee.memoripple.domain.ai.models.InstallState
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary
import io.github.cragcoffee.memoripple.domain.memos.HomeShortcutEntry
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.StarterTemplates
import io.github.cragcoffee.memoripple.domain.settings.ThemeMode
import io.github.cragcoffee.memoripple.ui.chat.AiPanelState
import io.github.cragcoffee.memoripple.ui.chat.AiPhase
import io.github.cragcoffee.memoripple.ui.chat.ChatScreen
import io.github.cragcoffee.memoripple.ui.chat.ChatUiState
import io.github.cragcoffee.memoripple.ui.settings.AiModelsScreen
import io.github.cragcoffee.memoripple.ui.theme.MemoRippleTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 7 (docs/CHAT_V1_RELEASE_READINESS.md): the Chat and Local AI モデル screens rendered on
 * their own — at font scale 2.0, on a 360 dp column (the S20's width), in the dark and the light
 * theme — with their accessibility semantics. Nothing here needs a model, a database or an
 * activity of the product.
 */
class ChatV1LayoutInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val a = TestModelServer.A_ID
    private val b = TestModelServer.B_ID
    private val available = ModelAvailability.Available(TestAiSelection.default)

    private fun preview(): AiInteractionResult.WritePreview {
        val target = DocumentSummary(DocumentRef(DocumentKind.MEMO, 1), "MemoRipple開発", 1, 1)
        val p = CommandPreview.Append(target, "Folder対応完了", DocumentVersion(1), "## 進捗\n- Chat v0 完了")
        val decision = ExecutionPolicy.decide(ResolutionResult.Resolved(ResolvedCommand.Append(target, p.text, p.expectedVersion, p.currentBody))) as ExecutionDecision.RequiresConfirmation
        return AiInteractionResult.WritePreview(decision.preview, PendingWrite(decision))
    }

    private fun previewState() = ChatUiState(input = "MemoRipple開発に『Folder対応完了』を追記して", ai = AiPanelState(phase = AiPhase.DONE, askedText = "x", result = preview(), availability = available))

    private var shownWidth by mutableStateOf(360)
    private var shownFontScale by mutableStateOf(1f)
    private var shownTheme by mutableStateOf(ThemeMode.LIGHT)
    private var shown by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** [width] dp wide, top-left aligned, at [fontScale]: the bounds read in root coordinates then start at 0. One setContent per test; later calls swap the state. */
    private fun render(width: Int = 360, fontScale: Float = 1f, themeMode: ThemeMode = ThemeMode.LIGHT, content: @Composable () -> Unit) {
        val first = shown == null
        shownWidth = width; shownFontScale = fontScale; shownTheme = themeMode; shown = content
        if (first) {
            composeRule.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, shownFontScale)) {
                    MemoRippleTheme(themeMode = shownTheme) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                            Box(Modifier.requiredWidth(shownWidth.dp)) { shown?.invoke() }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertWithin(tag: String, width: Int) {
        val bounds = composeRule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()
        assertTrue("$tag runs past $width dp: right = ${bounds.right}", bounds.right.value <= width + 0.5f)
        assertTrue("$tag starts before the column", bounds.left.value >= -0.5f)
    }

    // --- RED 35, 37: the chat at font scale 2.0 and on 360 dp — the toggle, the chips, the preview and its buttons stay reachable ---

    @Test
    fun theChatControlsSurviveFontScaleTwoOnA360DpColumn() {
        render(width = 360, fontScale = 2f) { ChatScreen(state = previewState()) }
        listOf("chat_plus", "chat_send", "chat_ai_preview_append", "chat_ai_preview_cancel", "chat_ai_preview_confirm").forEach { tag ->
            // below the fold at this size is fine (the list scrolls); off the column to the right is not
            val node = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
            runCatching { node.performScrollTo() }   // the mode switch sits above the list (no scrollable parent); the cards inside it scroll
            node.assertIsDisplayed()
            assertWithin(tag, 360)
        }
        composeRule.onNodeWithTag("chat_ai_preview_confirm").assertHasClickAction()
        composeRule.onNodeWithTag("chat_ai_preview_cancel").assertHasClickAction()
    }

    @Test
    fun theChatBarAndTopControlsFitA360DpColumnAtFontScaleTwo() {
        // Chat UI redesign (2026-09-21): no chips — the bar (＋, input, send), the history and the new-chat controls, the empty state
        render(width = 360, fontScale = 2f) { ChatScreen(state = ChatUiState(historyEnabled = true, ai = AiPanelState(availability = ModelAvailability.Unavailable(ModelUnavailableReason.NO_MODEL_CONFIGURED)))) }
        listOf("chat_plus", "chat_input", "chat_send", "chat_history", "chat_new_conversation", "chat_overflow", "chat_ai_hint", "chat_empty").forEach { tag ->
            composeRule.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
            assertWithin(tag, 360)
        }
    }

    // --- the chat home launcher (2026-09-23): four columns must still fit, and a long pin name must not push one out ---

    @Test
    fun theHomeLauncherFitsA360DpColumnAtFontScaleTwo() {
        val long = MemoTemplate(id = "u-long", name = "とても長いカスタムテンプレートの名前がここに入ります", body = "本文")
        render(width = 360, fontScale = 2f) {
            ChatScreen(
                state = ChatUiState(
                    historyEnabled = true,
                    templates = StarterTemplates.all + long,
                    homeShortcuts = listOf(HomeShortcutEntry("u-long"), HomeShortcutEntry("starter-think-idea"), HomeShortcutEntry("starter-this-week")),
                    ai = AiPanelState(availability = ModelAvailability.Unavailable(ModelUnavailableReason.NO_MODEL_CONFIGURED)),
                ),
            )
        }
        listOf("chat_home_launcher", "chat_home_shortcut_memo", "chat_home_shortcut_search", "chat_home_shortcut_journal", "chat_home_shortcut_organize", "chat_home_shortcut_template_u-long", "chat_home_shortcut_template_starter-think-idea", "chat_home_shortcut_template_starter-this-week", "chat_home_shortcut_add").forEach { tag ->
            val node = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
            runCatching { node.performScrollTo() }
            node.assertIsDisplayed()
            assertWithin(tag, 360)
        }
    }

    @Test
    fun theHomeLauncherRendersInBothThemes() {
        listOf(ThemeMode.DARK, ThemeMode.LIGHT).forEach { mode ->
            render(themeMode = mode) {
                ChatScreen(state = ChatUiState(historyEnabled = true, templates = StarterTemplates.all, homeShortcuts = listOf(HomeShortcutEntry("starter-think-idea")), ai = AiPanelState(availability = available)))
            }
            composeRule.onNodeWithTag("chat_home_launcher", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("chat_home_shortcut_memo", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("chat_home_shortcut_template_starter-think-idea", useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun thePreviewFitsA360DpColumnAtTheLargeSystemFontSize() {
        render(width = 360, fontScale = 1.3f) { ChatScreen(state = previewState()) }
        listOf("chat_ai_preview_append", "chat_ai_preview_version", "chat_ai_preview_note", "chat_ai_preview_confirm").forEach { assertWithin(it, 360) }
    }

    // --- RED 36: model cards at font scale 2.0 ---

    @Test
    fun theModelCardsAndTheirControlsSurviveFontScaleTwoOnA360DpColumn() {
        val states = mapOf(a to InstallState.Downloading(300_000, 1_200_000), b to InstallState.Installed(800_000))
        render(width = 360, fontScale = 2f) { AiModelsScreen(entries = TestModelServer.catalog, states = states, selected = b, supported = true) }
        listOf("ai_model_$a", "ai_model_state_$a", "ai_model_action_$a", "ai_model_progress_$a").forEach { assertWithin(it, 360) }
        composeRule.onNodeWithTag("ai_model_action_$a", useUnmergedTree = true).performScrollTo().assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithTag("ai_model_$b").performScrollTo()
        listOf("ai_model_$b", "ai_model_state_$b", "ai_model_selected_$b", "ai_model_delete_$b").forEach { assertWithin(it, 360) }
    }

    // --- RED 38: dark and light ---

    @Test
    fun theAiCardsRenderInBothThemes() {
        listOf(ThemeMode.DARK, ThemeMode.LIGHT).forEach { mode ->
            render(themeMode = mode) {
                // the setup card is the result of a free-text send; the hint sits above the input (Chat UI redesign 2026-09-21)
                ChatScreen(state = ChatUiState(ai = AiPanelState(phase = AiPhase.DONE, askedText = "x", result = AiInteractionResult.ModelUnavailable(ModelUnavailableReason.NO_MODEL_CONFIGURED), availability = ModelAvailability.Unavailable(ModelUnavailableReason.NO_MODEL_CONFIGURED))))
            }
            composeRule.onNodeWithTag("chat_ai_model_unavailable").assertIsDisplayed()
            composeRule.onNodeWithTag("chat_ai_open_settings").assertIsDisplayed()
            composeRule.onNodeWithTag("chat_ai_hint").assertIsDisplayed()
            composeRule.onNodeWithTag("chat_input").assertIsDisplayed()
        }
    }

    @Test
    fun theFailureCardsAndTheirActionsRenderInBothThemes() {
        listOf(ThemeMode.DARK, ThemeMode.LIGHT).forEach { mode ->
            render(themeMode = mode) {
                ChatScreen(state = ChatUiState(ai = AiPanelState(phase = AiPhase.DONE, askedText = "x", result = AiInteractionResult.RuntimeError(AiFailureStage.LOAD, "test"), availability = available)))
            }
            composeRule.onNodeWithTag("chat_ai_runtime_error").assertIsDisplayed()
            composeRule.onNodeWithTag("chat_ai_retry").assertIsDisplayed().assertHasClickAction()
            composeRule.onNodeWithTag("chat_ai_dismiss").assertIsDisplayed()
        }
        render(themeMode = ThemeMode.DARK) {
            ChatScreen(state = ChatUiState(ai = AiPanelState(phase = AiPhase.DONE, askedText = "x", result = AiInteractionResult.Written(WriteOutcome.Conflict, preview().preview), availability = available)))
        }
        composeRule.onNodeWithTag("chat_ai_write_conflict").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_ai_reconfirm").assertIsDisplayed().assertHasClickAction()
    }

    // --- RED 32, 33, 34: semantics a screen reader gets — selected, disabled, state, progress, live status ---

    @Test
    fun theModelCardsCarrySelectedStateAndProgressSemantics() {
        val states = mapOf(a to InstallState.Downloading(300_000, 1_200_000), b to InstallState.Installed(800_000))
        render { AiModelsScreen(entries = TestModelServer.catalog, states = states, selected = b, supported = true) }
        composeRule.onNodeWithTag("ai_model_$b").assertIsSelected()
        composeRule.onNodeWithTag("ai_model_$a").assertIsNotSelected()
        val stateOfA = composeRule.onNodeWithTag("ai_model_$a").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertTrue("state description says downloading: $stateOfA", stateOfA.contains("ダウンロード中"))
        val stateOfB = composeRule.onNodeWithTag("ai_model_$b").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertTrue("state description says installed and in use: $stateOfB", stateOfB.contains("ダウンロード済み") && stateOfB.contains("使用中"))
        assertEquals(1, composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun anUnsupportedDeviceDisablesEveryDownloadControlForAScreenReaderToo() {
        render { AiModelsScreen(entries = TestModelServer.catalog, states = mapOf(a to InstallState.NotInstalled, b to InstallState.NotInstalled), selected = null, supported = false) }
        composeRule.onNodeWithTag("ai_models_unsupported").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_model_action_$a", useUnmergedTree = true).assertIsNotEnabled()
        composeRule.onNodeWithTag("ai_model_action_$b", useUnmergedTree = true).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun theAiStatusLinesArePoliteLiveRegions() {
        render { ChatScreen(state = ChatUiState(ai = AiPanelState(phase = AiPhase.LOADING, askedText = "x", availability = available))) }
        assertEquals(LiveRegionMode.Polite, composeRule.onNodeWithTag("chat_ai_status_loading").fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
        render { ChatScreen(state = ChatUiState(ai = AiPanelState(phase = AiPhase.GENERATING, askedText = "x", availability = available))) }
        composeRule.onNodeWithTag("chat_ai_status_generating").assertIsDisplayed()
        assertEquals(LiveRegionMode.Polite, composeRule.onNodeWithTag("chat_ai_status_generating").fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
        render { ChatScreen(state = ChatUiState(ai = AiPanelState(phase = AiPhase.EXECUTING, askedText = "x", result = preview(), availability = available))) }
        assertEquals(LiveRegionMode.Polite, composeRule.onNodeWithTag("chat_ai_executing").fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
    }

    /** 2026-09-21 night (S20 review): a short user line is a narrow bubble on the right — not a band across the row; the template's opening 「今日の振り返り」 too. */
    @org.junit.Test
    fun aShortUserLineIsANarrowBubbleOnTheRight() {
        val user = io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessage(1, 1, io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole.USER, io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessageKind.TEXT, "今日の振り返り", 1)
        val assistant = io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessage(2, 1, io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole.ASSISTANT, io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessageKind.TEXT, "今日の振り返りを始めます。\nまず、今日の良かったことは？", 2)
        render(width = 360) { ChatScreen(state = ChatUiState(historyEnabled = true, transcript = listOf(user, assistant), ai = AiPanelState(availability = available))) }
        val bubble = composeRule.onNodeWithTag("chat_message_1", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("narrow: width = ${bubble.right - bubble.left}", (bubble.right - bubble.left).value < 180f)
        assertTrue("on the right: right = ${bubble.right}", bubble.right.value >= 360f - 24f - 1f)   // the screen's own side padding
        val line = composeRule.onNodeWithTag("chat_message_2", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("the assistant stays flat on the left", line.left.value <= 24f + 1f)
    }
}

package io.github.cragcoffee.memoripple

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The user's UI/UX review remarks of 2026-09-21 (docs/CHAT_UI_TEMPLATE_V2.md §13), pinned to the
 * sources: the chat shaped like the reference (menu / title with the model as a subtitle / compose
 * icon / overflow; assistant meta row with timing and copy; 「メッセージを入力」), a new chat as its own
 * stage without the bottom bar, the input bar following the keyboard, a model switch with 「AIモデルなし」.
 */
class ChatUiReviewFixPolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val main = "src/main/java/io/github/cragcoffee/memoripple"

    @Test
    fun theChatFollowsTheReferenceShape() {
        val screen = text("$main/ui/chat/ChatScreen.kt")
        assertTrue("the compose icon is the edit-square drawable", screen.contains("R.drawable.ic_edit_square") && file("src/main/res/drawable/ic_edit_square.xml").isFile)
        assertTrue("the history entry is the menu icon", screen.contains("Icons.Outlined.Menu") || screen.contains("Icons.Filled.Menu"))
        assertTrue("the placeholder", screen.contains("メッセージを入力"))
        listOf("chat_model_button", "chat_model_none", "chat_model_option_", "chat_model_manage", "chat_ai_timing", "chat_message_copy", "chat_scroll_to_bottom", "chat_subtitle").forEach {
            assertTrue("the chat has $it", screen.contains(it))
        }
    }

    @Test
    fun theInputBarFollowsTheKeyboard() {
        val screen = text("$main/ui/chat/ChatScreen.kt")
        assertTrue("the bar pads for the IME", screen.contains(".imePadding()"))
        assertTrue("the list follows the keyboard", screen.contains("isImeVisible"))
        val app = text("$main/ui/MemoRippleApp.kt")
        assertTrue("the bottom navigation gives way to the keyboard on the chat", app.contains("isImeVisible"))
    }

    @Test
    fun aNewChatIsItsOwnStageWithoutTheBottomBar() {
        val app = text("$main/ui/MemoRippleApp.kt")
        assertTrue(app.contains("const val CHAT_STAGE = \"chat-stage\""))
        assertTrue("the stage is not a bottom-bar route", app.contains("composable(Routes.CHAT_STAGE)") && !app.contains("currentRoute == Routes.CHAT_STAGE ||"))
        val screen = text("$main/ui/chat/ChatScreen.kt")
        assertTrue("the stage has its own background", screen.contains("surfaceContainerLowest"))
        assertTrue("the stage goes back with a back arrow", screen.contains("chat_stage_back"))
    }

    @Test
    fun choosingNoModelStopsTheAiThroughTheManager() {
        val vm = text("$main/ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("fun selectNoModel(") && vm.contains("fun selectModel("))
        assertTrue("none = clearSelection (which unloads the runtime)", vm.substringAfter("fun selectNoModel(").substringBefore("fun selectModel(").contains("clearSelection()"))
        assertTrue("a model = select (never a load)", vm.substringAfter("fun selectModel(").contains(".select("))
        assertFalse("the chat never loads a model by itself", vm.contains(".load("))
        val orchestrator = text("$main/domain/ai/AiOrchestrator.kt")
        assertTrue("timing reaches the screen as numbers only", orchestrator.contains("data class AiTiming(") && orchestrator.contains("onTiming"))
        val forbidden = listOf("title", "content", ".text", "message.", "transcript", "results.", "summary", "askedText", "userText", "DocumentRef", ".id}")
        listOf("$main/ui/chat/ChatViewModel.kt", "$main/ui/chat/ChatScreen.kt", "$main/domain/ai/AiOrchestrator.kt").forEach { p ->
            text(p).lines().filter { it.contains("Log.") || it.contains("onNote(\"") }.forEach { line ->
                forbidden.forEach { token -> assertFalse("$p: a log line carries $token → $line", line.contains(token)) }
            }
        }
    }
}

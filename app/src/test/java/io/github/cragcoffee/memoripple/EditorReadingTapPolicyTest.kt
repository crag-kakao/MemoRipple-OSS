package io.github.cragcoffee.memoripple

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Three editor remarks (human, 2026-09-22 00:06, docs/CHAT_UI_TEMPLATE_V2.md §16.14):
 * 1. the コメント一覧's arranging carries a row the way 設定's ショートカットバー does — the same
 *    card drag, the same slide of the neighbours;
 * 2. in 閲覧モード a single tap on the title opens writing with the keyboard on the title;
 * 3. a double tap on a written body line opens writing with the keyboard on the body — while
 *    the page's own double tap (an empty place) stays what it was, and a voice or a playback
 *    still keeps the page.
 */
class EditorReadingTapPolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val main = "src/main/java/io/github/cragcoffee/memoripple"

    @Test
    fun theCommentListCarriesARowLikeTheToolbarOrderScreen() {
        val editor = text("$main/ui/memos/MemoEditorScreen.kt")
        val sheet = editor.substringAfter("fun UserCommentsSheet(").substringBefore("\n@Composable")
        listOf("CardDragController(", ".carriedCard(", ".cardDragHandle(", "animateItem()", "onCrossed =").forEach {
            assertTrue("the comment list has $it", sheet.contains(it))
        }
        assertFalse("no step-swap threshold drag any more", sheet.contains("rememberDraggableState") || sheet.contains("REORDER_DRAG_THRESHOLD"))
        val toolbar = text("$main/ui/settings/ToolbarOrderScreen.kt")
        assertTrue("the same helpers as 設定's bar", toolbar.contains(".carriedCard(") && toolbar.contains(".cardDragHandle(") && toolbar.contains("animateItem()"))
    }

    @Test
    fun aTapOnTheTitleAndADoubleTapOnALineOpenWritingWithTheKeyboard() {
        val editor = text("$main/ui/memos/MemoEditorScreen.kt")
        assertTrue("the title takes a single tap in 閲覧モード", editor.contains("memo_title_reading_tap"))
        assertTrue("the title tap is silent under a voice or a playback", Regex("readingMode && !speechActive && !playbackActive").containsMatchIn(editor))
        assertTrue("the fields can be asked for focus", text("$main/ui/memos/MemoEditorScreen.kt").substringAfter("internal fun EditorTextField(").substringBefore(") {").contains("focusRequester: FocusRequester?"))
        assertTrue("the screen asks for the keyboard after the switch", editor.contains("keyboardController?.show()"))
        val reading = text("$main/ui/memos/MemoReadingView.kt")
        assertTrue("a line answers a double tap on its own", reading.contains("onDoubleTapLine"))
        assertTrue("the page's own double tap stays", reading.contains("onDoubleTapEdit"))
        val bodySurface = editor.substringAfter("MemoReadingView(").substringBefore("MemoBodyEditor(")
        assertTrue("the line double tap is null under a voice or a playback", Regex("onDoubleTapLine = if \\(speechActive \\|\\| playbackActive\\) \\{\\s*null").containsMatchIn(bodySurface))
        assertTrue("and so is the page's", Regex("onDoubleTapEdit = if \\(speechActive \\|\\| playbackActive\\) \\{\\s*null").containsMatchIn(bodySurface))
    }
}

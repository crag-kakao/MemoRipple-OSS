package io.github.cragcoffee.memoripple

import io.github.cragcoffee.memoripple.ui.memos.EditorShortcutPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The editors' shortcut-bar panels (テンプレート / メモへのリンク / コメントリンク; the user's remark
 * of 2026-09-21, docs/CHAT_UI_TEMPLATE_V2.md §16.13): one panel at a time — a second tap on the
 * bar swaps, never stacks, and × leaves nothing behind; each panel wears a drag handle and goes
 * away on a swipe down like the 再生設定 sheet, while still living in the editor's window so the
 * keyboard stays; and a template can be made right inside the テンプレート panel, without the
 * settings list.
 */
class EditorShortcutPanelPolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val main = "src/main/java/io/github/cragcoffee/memoripple"

    @Test
    fun aTapOnTheBarOpensSwapsOrClosesButNeverStacks() {
        assertEquals(EditorShortcutPanel.TEMPLATE, EditorShortcutPanel.toggle(null, EditorShortcutPanel.TEMPLATE))
        assertEquals("another tool swaps the panel", EditorShortcutPanel.MEMO_LINK, EditorShortcutPanel.toggle(EditorShortcutPanel.TEMPLATE, EditorShortcutPanel.MEMO_LINK))
        assertEquals(EditorShortcutPanel.COMMENT_LINK, EditorShortcutPanel.toggle(EditorShortcutPanel.MEMO_LINK, EditorShortcutPanel.COMMENT_LINK))
        assertNull("the same tool again closes it", EditorShortcutPanel.toggle(EditorShortcutPanel.TEMPLATE, EditorShortcutPanel.TEMPLATE))
    }

    @Test
    fun bothEditorsHoldOnePanelStateInsteadOfThreeBooleans() {
        listOf("$main/ui/memos/MemoEditorScreen.kt", "$main/ui/outline/OutlinerScreen.kt").forEach { path ->
            val source = text(path)
            listOf("showTemplatePicker", "showLinkPicker", "showCommentLinkPicker").forEach { flag ->
                assertFalse("$path still keeps $flag", source.contains("var $flag"))
            }
            assertTrue("$path drives its panels by EditorShortcutPanel", source.contains("EditorShortcutPanel.toggle("))
        }
    }

    @Test
    fun thePanelWearsADragHandleSwipesAwayAndKeepsTheKeyboard() {
        val editor = text("$main/ui/memos/MemoEditorScreen.kt")
        val panel = editor.substringAfter("private fun KeyboardKeepingPanel(").substringBefore("\n@Composable")
        assertTrue("a drag handle", panel.contains("DragHandle"))
        assertTrue("a vertical drag closes it", panel.contains("draggable(") || panel.contains("detectVerticalDragGestures"))
        assertTrue("the handle is reachable by tag", panel.contains("_handle"))
        assertFalse("still in the editor's window, so the keyboard stays", panel.contains("ModalBottomSheet"))
        assertTrue("the panel slides in and out", panel.contains("animateTo("))
    }

    @Test
    fun theTemplatePanelMakesATemplateWithoutTheSettingsList() {
        val editor = text("$main/ui/memos/MemoEditorScreen.kt")
        val sheet = editor.substringAfter("internal fun MemoTemplateSheet(").substringBefore("\n@Composable")
        listOf("memo_template_create", "memo_template_new_name", "memo_template_new_body", "memo_template_new_save", "memo_template_new_from_memo", "memo_template_new_back").forEach {
            assertTrue("the template panel has $it", sheet.contains(it))
        }
        assertTrue(sheet.contains("\"テンプレートを作成\""))
        val vm = text("$main/ui/memos/MemoEditorViewModel.kt")
        val create = vm.substringAfter("fun createTemplate(").substringBefore("\n    fun ")
        assertTrue("a new template is judged by the same policy as 「テンプレートとして保存」", create.contains("MemoTemplatePolicy.isUsable("))
        assertTrue("and kept in the same store", create.contains("templateRepository.save("))
        assertTrue("the outliner's bar makes one too", text("$main/ui/outline/OutlinerAids.kt").contains("onCreateTemplate"))
    }
}

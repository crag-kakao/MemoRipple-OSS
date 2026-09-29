package io.github.cragcoffee.memoripple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The picker's entrance and template folders (human brief 2026-09-22) — the sources keep: the root
 * renders no folder's templates until it is opened; the drill-down stays inside the sheet; template
 * folders are their own store and never a document folder; the editor picks a folder; a deleted
 * folder unassigns; the backup carries folders as defaulted format-19 fields; Room 25 unchanged.
 */
class TemplateFolderPolicyTest {
    private fun file(path: String): File = File(path).let { if (it.isFile) it else File("app/$path") }
    private fun text(path: String) = file(path).readText()
    private val main = "src/main/java/io/github/cragcoffee/memoripple"

    @Test
    fun thePickerRootShowsFolderRowsAndOpensThemInsideTheSheet() {
        val screen = text("$main/ui/chat/ChatScreen.kt")
        listOf("chat_template_folder_record", "chat_template_folder_think", "chat_template_folder_search", "chat_template_folder_mine", "chat_template_folder_back", "chat_template_pinned_all", "chat_template_folder_mine_none", "chat_template_folder_mine_").forEach { assertTrue("the picker has $it", screen.contains(it)) }
        listOf("\"自分のテンプレート\"", "\"未分類\"", "\"すべて表示\"", "\"まだフォルダはありません\"").forEach { assertTrue("the picker says $it", screen.contains(it)) }
        assertTrue("the drill-down is a page of the same sheet", screen.contains("sealed interface PickerPage") || screen.contains("enum class PickerPage") || screen.contains("PickerPage."))
        assertFalse("no route for a folder", text("$main/ui/MemoRippleApp.kt").contains("template-folder?"))
        assertTrue("the root's rows come from the domain", screen.contains("TemplatePicker.root("))
        assertTrue("the create entry stays", screen.contains("chat_template_create"))
    }

    @Test
    fun templateFoldersAreTheirOwnStoreNeverADocumentFolder() {
        val domain = text("$main/domain/memos/TemplateFolders.kt")
        assertTrue(domain.contains("data class TemplateFolder(") && domain.contains("object TemplateFolderPolicy"))
        listOf("FolderEntity", "domain.folders", "FolderTree", "folderDao", "memos.folderId").forEach { assertFalse("template folders reach the document folders: $it", domain.contains(it)) }
        val repo = text("$main/data/TemplateFolderRepository.kt")
        assertTrue("a preference beside the templates, not Room", repo.contains("memo_template_folders") && !repo.contains("androidx.room"))
        assertTrue("Room 26 (content blocks, 2026-09-24)", text("$main/data/AppDatabase.kt").contains("version = 29"))
        assertFalse("the document folder repository knows nothing of templates", text("$main/data/FolderRepository.kt").contains("Template"))
    }

    @Test
    fun theEditorPicksAFolderAndDeletingAFolderUnassigns() {
        val editor = text("$main/ui/settings/TemplateEditorScreen.kt")
        listOf("template_editor_folder", "template_editor_folder_none", "template_editor_folder_").forEach { assertTrue("the editor has $it", editor.contains(it)) }
        assertTrue(editor.contains("\"フォルダ\""))
        val folders = text("$main/ui/settings/TemplateFoldersScreen.kt")
        listOf("template_folders_add", "template_folders_list", "template_folder_rename_", "template_folder_delete_", "template_folder_up_", "template_folder_down_").forEach { assertTrue("the folder screen has $it", folders.contains(it)) }
        assertTrue("a deleted folder leaves its templates unclassified", folders.contains("unassign(") || folders.contains("clearFolder("))
        assertTrue("the templates screen leads there", text("$main/ui/settings/PhraseLibraryScreens.kt").contains("templates_folders"))
        assertTrue("the conversation draft is unclassified", !text("$main/domain/ai/conversation/ConversationTemplateDraft.kt").contains("folderId ="))
    }

    @Test
    fun theBackupCarriesFoldersAsDefaultedFormat19FieldsAndTheFileDoesNot() {
        val dtos = text("$main/backup/BackupDtos.kt")
        assertTrue("Backup 20 since content blocks (2026-09-24)", dtos.contains("BACKUP_FORMAT_VERSION = 23"))
        assertTrue("the template's folder, defaulted", dtos.substringAfter("data class TemplateBackupDto(").substringBefore("\n)").contains("val folderId: String? = null"))
        assertTrue("the folders, defaulted", dtos.contains("val templateFolders: List<TemplateFolderBackupDto> = emptyList()"))
        val mapper = text("$main/backup/BackupMapper.kt")
        assertTrue(mapper.contains("fun toTemplateFolders(") && mapper.contains("folderId = t.folderId"))
        assertTrue("the engine restores them beside the templates", text("$main/backup/BackupEngine.kt").contains("templateFolderRepository"))
        assertTrue("the template file drops the folder", text("$main/domain/memos/TemplateFile.kt").contains("folderId = null"))
        assertEquals("the file's version is unchanged", 1, Regex("FILE_FORMAT_VERSION = ([0-9]+)").find(text("$main/domain/memos/TemplateFile.kt"))!!.groupValues[1].toInt())
    }
}

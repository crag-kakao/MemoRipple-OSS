package io.github.cragcoffee.memoripple.domain.memos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Template folders and the picker's entrance (human brief 2026-09-22): a custom template may sit
 * in one flat folder (`folderId`, null = 未分類); folders are id / name / order in their own store,
 * never a document folder; a deleted folder leaves its templates unclassified; the ＋ picker's
 * root shows at most three pins, at most three recents (pins left out), and four folder rows —
 * the three built-in ones the starters are classified into, and 自分のテンプレート.
 */
class TemplateFoldersTest {
    private fun t(id: String, folderId: String? = null) = MemoTemplate(id, id, "x", folderId = folderId)

    @Test
    fun aTemplateHasOneOptionalFolderAndAnOlderJsonReadsAsUnclassified() {
        assertNull(MemoTemplate("a", "A", "x").folderId)
        assertEquals("f1", t("a", "f1").folderId)
        assertTrue("still the legacy shape when unclassified", MemoTemplate("a", "A", "x").isLegacyShape)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        assertNull(json.decodeFromString(MemoTemplate.serializer(), "{\"id\":\"a\",\"name\":\"A\",\"body\":\"x\"}").folderId)
    }

    @Test
    fun foldersAreKeptInOrderRenamedMovedAndRemoved() {
        val a = TemplateFolder("f1", "仕事", 0); val b = TemplateFolder("f2", "読書", 1)
        val list = TemplateFolderPolicy.upsert(TemplateFolderPolicy.upsert(emptyList(), a), b)
        assertEquals(listOf(a, b), list)
        assertEquals(listOf("読書", "仕事"), TemplateFolderPolicy.move(list, "f2", -1).map { it.name })
        assertEquals(list, TemplateFolderPolicy.move(list, "f1", -1))
        assertEquals("開発", TemplateFolderPolicy.rename(list, "f1", "開発").first { it.id == "f1" }.name)
        assertEquals(listOf(b.copy(order = 0)), TemplateFolderPolicy.remove(list, "f1"))
        assertEquals("a name is trimmed and capped", "x".repeat(TemplateFolderPolicy.MAX_NAME_CHARS), TemplateFolderPolicy.cleanName(" " + "x".repeat(50)))
        assertEquals("フォルダ", TemplateFolderPolicy.cleanName("   "))
        assertTrue(TemplateFolderPolicy.MAX_FOLDERS in 10..100)
    }

    @Test
    fun removingAFolderLeavesItsTemplatesUnclassified() {
        val templates = listOf(t("a", "f1"), t("b", "f2"), t("c"))
        val after = TemplateFolderPolicy.unassign(templates, "f1")
        assertEquals(listOf(null, "f2", null), after.map { it.folderId })
        assertEquals("nothing else changes", templates.map { it.id }, after.map { it.id })
    }

    @Test
    fun thePickerRootIsAnEntranceNotAList() {
        val starters = StarterTemplates.all
        val custom = listOf(t("u1", "f1"), t("u2", "f1"), t("u3"), t("u4", "gone"))
        val all = custom + starters
        val root = TemplatePicker.root(all, pinnedIds = listOf("starter-daily-review", "u1", "starter-this-week", "u3"), recentIds = listOf("u1", "starter-meeting-memo", "u2", "starter-idea-memo", "u3"), folders = listOf(TemplateFolder("f1", "仕事", 0)))
        assertEquals("at most three pins, in pin order", listOf("starter-daily-review", "u1", "starter-this-week"), root.pinned.map { it.id })
        assertTrue("a fourth pin is behind すべて表示", root.morePinned)
        assertEquals("at most three recents, pins left out", listOf("starter-meeting-memo", "u2", "starter-idea-memo"), root.recent.map { it.id })
        assertEquals("the root names no other template", 6, root.pinned.size + root.recent.size)
        assertEquals(mapOf(ThinkTemplates.Section.RECORD to 4, ThinkTemplates.Section.THINK to 4, ThinkTemplates.Section.SEARCH to 2), root.builtInCounts)
        assertEquals("every custom template counts under 自分のテンプレート", 4, root.mineCount)
        // inside the folders
        assertEquals(listOf("starter-daily-review", "starter-meeting-memo", "starter-idea-memo", "starter-project-log"), TemplatePicker.builtIn(all, ThinkTemplates.Section.RECORD).map { it.id })
        assertEquals(listOf("starter-this-week", "starter-yesterday-journal"), TemplatePicker.builtIn(all, ThinkTemplates.Section.SEARCH).map { it.id })
        assertTrue("a custom template is never in a built-in folder", TemplatePicker.builtIn(all, ThinkTemplates.Section.RECORD).none { it.id.startsWith("u") })
        val mine = TemplatePicker.mine(all, listOf(TemplateFolder("f1", "仕事", 0)))
        assertEquals(listOf("f1" to 2, null to 2), mine.map { it.folder?.id to it.count })
        assertEquals("an orphan folderId reads as 未分類", listOf("u3", "u4"), TemplatePicker.inFolder(all, listOf(TemplateFolder("f1", "仕事", 0)), null).map { it.id })
        assertEquals(listOf("u1", "u2"), TemplatePicker.inFolder(all, listOf(TemplateFolder("f1", "仕事", 0)), "f1").map { it.id })
        assertEquals(3, TemplatePicker.MAX_ROOT_ROWS)
    }

    @Test
    fun startersKeepTheirBuiltInClassificationAndACopyIsFree() {
        StarterTemplates.all.forEach { assertNull("a starter is classified by what it is, never by a folder", it.folderId) }
        val copy = StarterTemplates.meetingMemo.copy(id = "u-copy", folderId = "f1")
        assertEquals("f1", copy.folderId)
        assertTrue(TemplateValidation.problems(copy).isEmpty())
    }

    @Test
    fun theTemplateFileCarriesNoFolderAndAnImportIsUnclassified() {
        val text = TemplateFile.export(listOf(t("u1", "f1")))
        assertTrue("a folder id is this device's, not the file's", !text.contains("\"folderId\":\""))
        val back = TemplateFile.import(text) as TemplateImport.Ready
        assertNull(back.templates.single().folderId)
    }
}

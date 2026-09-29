package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Template v2 RED 40–43: the MemoRipple template file — a versioned, declarative JSON document;
 * import validates every template and refuses anything that is not a template definition.
 */
class TemplateFileTest {
    private val create = MemoTemplate(id = "m1", name = "会議メモ", body = "# {{title}}", description = "d", fields = listOf(TemplateField("title", "タイトル", TemplateFieldType.TEXT, required = true)), createdAt = 5, updatedAt = 6)
    private val search = MemoTemplate(id = "s1", name = "今週", body = "", action = TemplateAction.SEARCH, searchSpec = TemplateSearchSpec("MemoRipple", TemplateDateToken.THIS_WEEK, setOf(DocumentKind.MEMO)))
    private val append = MemoTemplate(id = "a1", name = "追記", body = "- {{e}}", action = TemplateAction.APPEND, targetSpec = TemplateTargetSpec.Named("開発ログ"), fields = listOf(TemplateField("e", "内容", TemplateFieldType.TEXT, required = true)))

    @Test
    fun exportThenImportRoundTripsEveryTemplateAndCarriesTheFormatVersion() {
        val text = TemplateFile.export(listOf(create, search, append))
        assertTrue(text.contains("\"format\":\"memoripple_templates\"") || text.contains("\"format\": \"memoripple_templates\""))
        assertTrue(text.contains("\"formatVersion\":1") || text.contains("\"formatVersion\": 1"))
        val back = TemplateFile.import(text) as TemplateImport.Ready
        assertEquals(listOf(create, search, append), back.templates)
    }

    @Test
    fun aFileThatIsNotATemplateFileIsRefusedWithAReason() {
        listOf("", "not json", "{}", "[]", """{"format":"something_else","formatVersion":1,"templates":[]}""", """{"format":"memoripple_templates","formatVersion":99,"templates":[]}""").forEach {
            assertTrue(it, TemplateFile.import(it) is TemplateImport.Rejected)
        }
    }

    @Test
    fun anInvalidTemplateInsideAValidFileIsRefusedNotSilentlyFixed() {
        val bad = """{"format":"memoripple_templates","formatVersion":1,"templates":[{"id":"x","name":"x","body":"{{undeclared}}","action":"CREATE","documentKind":"MEMO","fields":[]}]}"""
        val r = TemplateFile.import(bad)
        assertTrue(r is TemplateImport.Rejected)
        assertTrue((r as TemplateImport.Rejected).reason.contains("undeclared"))
        val unknownAction = """{"format":"memoripple_templates","formatVersion":1,"templates":[{"id":"x","name":"x","body":"b","action":"DELETE","documentKind":"MEMO","fields":[]}]}"""
        assertTrue(TemplateFile.import(unknownAction) is TemplateImport.Rejected)
        val unknownType = """{"format":"memoripple_templates","formatVersion":1,"templates":[{"id":"x","name":"x","body":"{{a}}","fields":[{"key":"a","label":"a","type":"SCRIPT"}]}]}"""
        assertTrue(TemplateFile.import(unknownType) is TemplateImport.Rejected)
    }

    @Test
    fun pathsUrlsAndCodeInATemplateAreNeverAnythingButText() {
        // a template may *contain* such strings as text — they are never interpreted; a key that is not a key is refused
        val keyInjection = """{"format":"memoripple_templates","formatVersion":1,"templates":[{"id":"../x","name":"x","body":"{{a}}","fields":[{"key":"a","label":"a","type":"TEXT"}]}]}"""
        assertTrue("an id that is a path is refused", TemplateFile.import(keyInjection) is TemplateImport.Rejected)
        val textOnly = MemoTemplate(id = "t", name = "x", body = "file:///etc/passwd http://x <script>alert(1)</script> \${env}", fields = emptyList())
        val back = TemplateFile.import(TemplateFile.export(listOf(textOnly))) as TemplateImport.Ready
        assertEquals(textOnly.body, back.templates.single().body)
        val huge = MemoTemplate(id = "h", name = "x", body = "a".repeat(TemplateValidation.MAX_BODY_CHARS + 1))
        assertTrue(TemplateFile.import(TemplateFile.export(listOf(huge))) is TemplateImport.Rejected)
    }

    @Test
    fun importedIdsAreKeptSoAReimportReplacesRatherThanDuplicates() {
        val once = TemplateFile.import(TemplateFile.export(listOf(create))) as TemplateImport.Ready
        val merged = MemoTemplatePolicy.upsertAll(listOf(create.copy(name = "old")), once.templates)
        assertEquals(1, merged.size)
        assertEquals("会議メモ", merged.single().name)
    }
}

package io.github.cragcoffee.memoripple.domain.memos

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoDocxExportTest {

    private fun entriesOf(bytes: ByteArray): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        return entries
    }

    @Test
    fun aMemoLeavesAsTheSmallestValidDocx() {
        val entries = entriesOf(
            MemoDocxExport.render(
                ExportableMemo(
                    title = "買い物",
                    body = "■ 目録\n牛乳とパン。\n\n<急ぎ> & \"注意\"",
                    tagNames = listOf("家", "買い物 リスト"),
                ),
            ),
        )
        assertEquals(
            setOf("[Content_Types].xml", "_rels/.rels", "word/document.xml"),
            entries.keys,
        )
        val document = entries.getValue("word/document.xml")
        // The title stands large and bold; the app's heading leaves bold at its own size.
        assertTrue(document.contains("""<w:sz w:val="34"/>"""))
        assertTrue(document.contains(">買い物</w:t>"))
        assertTrue(document.contains(">目録</w:t>"))
        // Prose survives with its markup escaped, and the ■ never reaches the document.
        assertTrue(document.contains("牛乳とパン。"))
        assertTrue(document.contains("&lt;急ぎ&gt; &amp; \"注意\""))
        assertFalse(document.contains("■"))
        // A blank line is a paragraph of air, and the tags close the file as hashtags.
        assertTrue(document.contains("<w:p></w:p>"))
        assertTrue(document.contains("#家 #買い物_リスト"))
    }

    @Test
    fun theFileNameFollowsTheMarkdownRulesWithItsOwnExtension() {
        assertEquals("買い物.docx", MemoDocxExport.fileName("買い物"))
        assertTrue(MemoDocxExport.fileName("a/b:c").endsWith(".docx"))
        assertEquals("無題のメモ.docx", MemoDocxExport.fileName("  "))
    }
}

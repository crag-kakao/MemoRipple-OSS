package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.domain.WorkCommentSyntax
import io.github.cragcoffee.memoripple.domain.WorkLineType
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes one memo out as Word (.docx), for whoever asked 設定 for it instead of Markdown.
 *
 * A .docx is a ZIP of small XML parts, and this memo needs only the smallest set the format
 * requires — content types, the package relationship, and the document itself — built by hand
 * with the platform's own ZIP writer, so no dependency enters the app for one file format.
 *
 * The title leaves as a large bold paragraph and each heading the app marks with `■ ` leaves
 * bold at its depth; every other line leaves as the plain paragraph it is, blank lines
 * included, so the document reads the way the memo did. Tags close the file the way the
 * Markdown export writes them. Photos are not part of this; they stay with the backup.
 */
object MemoDocxExport {

    const val MIME_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val EXTENSION = ".docx"

    /** A file name a file manager will accept, derived from what the memo is called. */
    fun fileName(title: String): String =
        MemoMarkdownExport.fileName(title).removeSuffix(MemoMarkdownExport.EXTENSION) + EXTENSION

    fun render(memo: ExportableMemo): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.put("[Content_Types].xml", CONTENT_TYPES)
            zip.put("_rels/.rels", PACKAGE_RELS)
            zip.put("word/document.xml", documentXml(memo))
        }
        return output.toByteArray()
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun documentXml(memo: ExportableMemo): String = buildString {
        append(
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\n" +
                """<w:document xmlns:w=""" +
                "\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>",
        )
        appendParagraph(memo.title.ifBlank { "無題のメモ" }, bold = true, halfPointSize = 34)
        if (memo.body.isNotBlank()) {
            memo.body.trimEnd().lineSequence().forEach { line ->
                val syntax = WorkCommentSyntax.recognize(line)
                if (syntax?.type == WorkLineType.HEADING) {
                    appendParagraph(syntax.text, bold = true, halfPointSize = 28)
                } else {
                    appendParagraph(line)
                }
            }
        }
        if (memo.tagNames.isNotEmpty()) {
            appendParagraph("")
            appendParagraph(
                memo.tagNames.joinToString(" ") { "#" + it.trim().replace(' ', '_') },
            )
        }
        append("</w:body></w:document>")
    }

    private fun StringBuilder.appendParagraph(
        text: String,
        bold: Boolean = false,
        halfPointSize: Int? = null,
    ) {
        append("<w:p>")
        if (text.isNotEmpty()) {
            append("<w:r>")
            if (bold || halfPointSize != null) {
                append("<w:rPr>")
                if (bold) append("<w:b/>")
                if (halfPointSize != null) {
                    append("""<w:sz w:val="$halfPointSize"/>""")
                }
                append("</w:rPr>")
            }
            append("""<w:t xml:space="preserve">""").append(escape(text)).append("</w:t>")
            append("</w:r>")
        }
        append("</w:p>")
    }

    private fun escape(text: String): String = buildString(text.length) {
        text.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                // Control characters other than tab are not legal in XML 1.0 text.
                '\t' -> append(character)
                else -> if (character.code >= 0x20) append(character)
            }
        }
    }

    private const val CONTENT_TYPES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\n" +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType=""" +
            "\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/word/document.xml" ContentType=""" +
            "\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "</Types>"

    private const val PACKAGE_RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\n" +
            """<Relationships xmlns=""" +
            "\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            """<Relationship Id="rId1" Type=""" +
            "\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" " +
            """Target="word/document.xml"/>""" +
            "</Relationships>"
}

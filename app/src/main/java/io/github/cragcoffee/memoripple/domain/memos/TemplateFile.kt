package io.github.cragcoffee.memoripple.domain.memos

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The MemoRipple template file: a versioned, declarative JSON document and nothing else. */
@Serializable
data class TemplateFileDto(
    /** Required on the way in: an object without them is not a template file. */
    val format: String,
    val formatVersion: Int,
    val templates: List<MemoTemplate> = emptyList(),
)

sealed interface TemplateImport {
    data class Ready(val templates: List<MemoTemplate>) : TemplateImport
    data class Rejected(val reason: String) : TemplateImport
}

/**
 * Export / import of templates (docs/CHAT_UI_TEMPLATE_V2.md §import / export). The file carries
 * definitions only — no code, no path, no URL is ever read from it; every template is validated
 * with [TemplateValidation] before it is accepted, and an unknown format or version is refused.
 */
object TemplateFile {
    const val FILE_FORMAT = "memoripple_templates"
    const val FILE_FORMAT_VERSION = 1
    const val MAX_TEMPLATES_PER_FILE = 200
    const val MAX_FILE_CHARS = 4_000_000
    const val FILE_EXTENSION = ".memoripple-templates.json"
    const val MIME_TYPE = "application/json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    /** A folder id is this device's, not the file's: every template travels unclassified (2026-09-22). */
    fun export(templates: List<MemoTemplate>): String = json.encodeToString(TemplateFileDto.serializer(), TemplateFileDto(FILE_FORMAT, FILE_FORMAT_VERSION, templates.map { it.copy(folderId = null) }))

    fun import(text: String): TemplateImport {
        if (text.isBlank()) return TemplateImport.Rejected("空のファイルです")
        if (text.length > MAX_FILE_CHARS) return TemplateImport.Rejected("ファイルが大きすぎます")
        val dto = try {
            json.decodeFromString(TemplateFileDto.serializer(), text)
        } catch (_: SerializationException) {
            return TemplateImport.Rejected("MemoRippleのテンプレートファイルではありません")
        } catch (_: IllegalArgumentException) {
            return TemplateImport.Rejected("MemoRippleのテンプレートファイルではありません")
        }
        if (dto.format != FILE_FORMAT) return TemplateImport.Rejected("MemoRippleのテンプレートファイルではありません")
        if (dto.formatVersion > FILE_FORMAT_VERSION || dto.formatVersion < 1) return TemplateImport.Rejected("このファイルの形式（バージョン ${dto.formatVersion}）はこのバージョンでは読めません")
        if (dto.templates.size > MAX_TEMPLATES_PER_FILE) return TemplateImport.Rejected("テンプレートが多すぎます")
        dto.templates.forEachIndexed { i, t ->
            val problems = TemplateValidation.problems(t)
            if (problems.isNotEmpty()) return TemplateImport.Rejected("テンプレート ${i + 1}「${t.name.take(20)}」: ${problems.first()}")
        }
        return TemplateImport.Ready(dto.templates)
    }
}

package io.github.cragcoffee.memoripple.domain.memos

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How the editor shows a body to a person (docs/CHAT_UI_TEMPLATE_V2.md §14): a placeholder is
 * shown by the field's *label* — `⟦会議名⟧` — never by its key; the stored body keeps `{{key}}`.
 * The brackets ⟦ ⟧ are the mapping's own and are not something a person types. A placeholder
 * whose key has no field stays as it is, so the validator can name it.
 */
object TemplateBodyDisplay {
    const val OPEN = "⟦"
    const val CLOSE = "⟧"
    private val TOKEN = Regex("$OPEN([^$OPEN$CLOSE]{1,80})$CLOSE")

    fun token(label: String): String = "$OPEN${label.trim()}$CLOSE"

    /** `{{key}}` → `⟦label⟧` for every declared field, and `{{today}}` → `⟦今日の日付⟧`. */
    fun toDisplay(body: String, fields: List<TemplateField>): String =
        fields.fold(body.replace("{{${TemplateValues.TODAY_KEY}}}", token(TemplateValues.TODAY_LABEL))) { acc, f -> if (f.key.isBlank()) acc else acc.replace("{{${f.key}}}", token(f.label)) }

    /** `⟦label⟧` → `{{key}}`; a bracketed word that names no field is left as typed. */
    fun fromDisplay(text: String, fields: List<TemplateField>): String {
        val byLabel = fields.filter { it.key.isNotBlank() }.associateBy { it.label.trim() }
        return TOKEN.replace(text) { m ->
            val label = m.groupValues[1].trim()
            if (label == TemplateValues.TODAY_LABEL) "{{${TemplateValues.TODAY_KEY}}}" else byLabel[label]?.let { "{{${it.key}}}" } ?: m.value
        }
    }

    /** The next unused key for a field a person adds — a plain `field_N`; the person never sees it. */
    fun nextKey(existing: Collection<String>): String {
        var n = existing.size + 1
        while ("field_$n" in existing) n++
        return "field_$n"
    }

    /** Sample values for a preview: the default when there is one, else the label in parentheses (a DATE resolves like a run). */
    fun sampleValues(fields: List<TemplateField>, today: LocalDate): Map<String, String> = mapOf(TemplateValues.TODAY_KEY to today.toString()) + fields.associate { f ->
        f.key to when {
            f.type == TemplateFieldType.DATE -> TemplateValues.formatDate(f.default.ifBlank { "TODAY" }, today) ?: "（${f.label}）"
            f.type == TemplateFieldType.BOOLEAN -> if (f.default.lowercase() in setOf("true", "yes", "1", "はい", "on")) "はい" else "いいえ"
            f.default.isNotBlank() -> f.default
            else -> "（${f.label}）"
        }
    }

    /** What a SEARCH template will look for, in the user's words. */
    fun describeSearch(spec: TemplateSearchSpec?): String {
        if (spec == null) return "検索条件がありません"
        val kinds = listOf(
            io.github.cragcoffee.memoripple.domain.documents.DocumentKind.MEMO to "メモ",
            io.github.cragcoffee.memoripple.domain.documents.DocumentKind.OUTLINE to "アウトライン",
            io.github.cragcoffee.memoripple.domain.documents.DocumentKind.JOURNAL to "日記",
        ).filter { it.first in spec.kinds }.joinToString("・") { it.second }.ifBlank { "（種類なし）" }
        val date = when (spec.dateToken) {
            null -> ""
            TemplateDateToken.TODAY -> "今日の"
            TemplateDateToken.YESTERDAY -> "昨日の"
            TemplateDateToken.THIS_WEEK -> "今週の"
            TemplateDateToken.LAST_WEEK -> "先週の"
        }
        val words = if (spec.query.isBlank()) "" else "「${spec.query}」を含む"
        return "$date$words$kinds を探します"
    }

    fun formatDay(day: LocalDate): String = day.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.JAPAN))
}
